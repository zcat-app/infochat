package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.impl.signal.SignalAdapter;
import app.zcat.infochat.messaging.impl.simplex.SimpleXAdapter;
import app.zcat.infochat.messaging.impl.simplex.SimpleXConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Provider-side CDI Producers that expose {@link SimpleXAdapter} and
 * {@link SignalAdapter} as {@code @ApplicationScoped} beans, per D46's
 * v1 production deployment shape (Provider runs any non-empty subset of
 * {InMemory, SimpleX, Signal} simultaneously). The
 * {@code infochat-messaging-adapter} library jar carries no CDI
 * annotations of its own (it stays usable as a plain Java library);
 * the registry-side Producer for {@link app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter}
 * lives at {@link AdapterRegistry#inMemoryAdapter()} and this class
 * contributes the two remaining production beans alongside it.
 *
 * <p><b>Separate file from {@link AdapterRegistry} by design.</b>
 * M1-105 froze {@link AdapterRegistry}'s reviewed surface; the
 * SimpleX / Signal Producers land here so the registry diff stays
 * untouched. Gate 2 ("every name in CSV resolves to a registered
 * bean") then accepts {@code infochat.adapters=simplex,signal}
 * because {@code Instance<MessagingAdapter>} resolves to all three
 * production beans once Quarkus discovers this class.</p>
 *
 * <p><b>Construction inputs.</b></p>
 * <ul>
 *   <li>SimpleX reads {@code infochat.adapters.simplex.binary},
 *       {@code .data-dir}, {@code .ws-port} into a
 *       {@link SimpleXConfig}. {@link SimpleXConfig#validate()} runs
 *       inside {@link SimpleXAdapter#start()} so a deployment that
 *       does not enable SimpleX never trips its filesystem checks.
 *       The bot's own SimpleX queue address — the D10 trust anchor —
 *       is NOT a config input: {@code SimpleXAdapter.start()} derives
 *       it by querying the running simplex-chat for the bot's own
 *       address, so it cannot be mistyped and an admin-key rotation
 *       ({@code .admin}, the bootstrap admin's queue address, consumed
 *       by {@link AdapterRegistry} gate 7) cannot move it.</li>
 *   <li>Signal reads {@code infochat.adapters.signal.binary},
 *       {@code .data-dir}, {@code .account}, and {@code .endpoint}
 *       (host:port of signal-cli's TCP daemon). The bot's own ACI —
 *       the D10 trust anchor — is NOT a config input: {@code
 *       SignalAdapter.start()} derives it from the signal-cli account
 *       store under {@code .data-dir}, so it cannot be mistyped and an
 *       admin-key rotation ({@code .admin}, the bootstrap admin's ACI,
 *       consumed by {@link AdapterRegistry} gate 7) cannot move
 *       it.</li>
 * </ul>
 *
 * <p><b>Admin notifier stub.</b> {@link SimpleXAdapter}'s constructor
 * takes a {@code Consumer<String>} the subprocess supervisor invokes
 * at FAILED transitions. v1 has no unified admin-notification surface
 * yet, so the Producer passes a stub that logs at WARN — the
 * notification message is captured in the Provider's logs (which the
 * operator already monitors) without inventing a half-finished
 * notification channel. The dedicated channel lands when the
 * unified surface does; until then the gap is auditable via this
 * comment + the WARN log line.</p>
 */
@ApplicationScoped
public class ProductionAdapterBeans {

    private static final Logger log = LoggerFactory.getLogger(ProductionAdapterBeans.class);

    // All string properties are injected as Optional<String> because
    // AdapterRegistry.start() materializes every discovered
    // MessagingAdapter bean (including SimpleX and Signal) even when
    // only `inmemory` is on the activation list. A required @ConfigProperty
    // would abort Provider boot in an inmemory-only deployment that does
    // not configure simplex/signal. Optional + .orElse("") lets the
    // Producer succeed with placeholder values for any unconfigured
    // adapter; .start() is the failure surface for invalid config, and
    // .start() is invoked only for adapters in infochat.adapters.
    // (defaultValue = "" on @ConfigProperty does not work — SmallRyeConfig
    // treats the annotation's empty-string default as "no default" because
    // the annotation default itself is "".)

    @ConfigProperty(name = SimpleXConfig.BINARY_KEY)
    Optional<String> simplexBinary;

    @ConfigProperty(name = SimpleXConfig.DATA_DIR_KEY)
    Optional<String> simplexDataDir;

    @ConfigProperty(name = SimpleXConfig.WS_PORT_KEY, defaultValue = "" + SimpleXConfig.DEFAULT_WS_PORT)
    int simplexWsPort;

    @ConfigProperty(name = "infochat.adapters.signal.binary")
    Optional<String> signalBinary;

    @ConfigProperty(name = "infochat.adapters.signal.data-dir")
    Optional<String> signalDataDir;

    @ConfigProperty(name = "infochat.adapters.signal.account")
    Optional<String> signalAccount;

    @ConfigProperty(name = "infochat.adapters.signal.endpoint", defaultValue = "127.0.0.1:7654")
    String signalEndpoint;

    /**
     * Opt-in for a non-loopback signal-cli daemon endpoint. signal-cli's
     * TCP JSON-RPC daemon has no authentication — any process that can
     * reach the endpoint can drive the bot's Signal account. The loopback
     * default ({@code 127.0.0.1}) keeps it reachable only from the same
     * host. An operator who deliberately binds it to a non-loopback
     * address (e.g. behind a host firewall on a multi-NIC box) must set
     * this {@code true} to acknowledge the widened trust surface;
     * otherwise {@link #parseEndpoint} fails boot closed.
     */
    @ConfigProperty(name = "infochat.adapters.signal.allow-non-loopback-endpoint",
            defaultValue = "false")
    boolean signalAllowNonLoopbackEndpoint;

    @Produces
    @ApplicationScoped
    SimpleXAdapter simpleXAdapter() {
        SimpleXConfig config = new SimpleXConfig(
                simplexBinary.orElse(""),
                simplexDataDir.orElse(""),
                simplexWsPort);
        // Explicit connect timeout: the default HttpClient has none, so a
        // simplex-chat that accepts the TCP dial but never completes the
        // WS handshake would park the connecting thread indefinitely.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Consumer<String> adminNotifier = msg ->
                log.warn("simplex adapter admin notification: {}", msg);
        return new SimpleXAdapter(config, httpClient, adminNotifier);
    }

    @Produces
    @ApplicationScoped
    SignalAdapter signalAdapter() {
        InetSocketAddress endpoint = parseEndpoint(signalEndpoint, signalAllowNonLoopbackEndpoint);
        return new SignalAdapter(
                signalBinary.orElse(""),
                signalDataDir.orElse(""),
                signalAccount.orElse(""),
                endpoint);
    }

    /**
     * Parse a {@code host:port} string into an {@link InetSocketAddress}.
     * Fails fast with {@link IllegalStateException} naming the offending
     * value so an operator can fix the exact property — this runs at
     * Producer @PostConstruct time, before any traffic, satisfying the
     * "validation at system boundaries" carve-out from the
     * No-defensive-code rule (config parsing is a system boundary).
     *
     * <p>Besides shape and port-range, the host is held to the loopback
     * trust model: signal-cli's TCP JSON-RPC daemon is unauthenticated, so
     * a non-loopback bind exposes the bot's Signal account to anything that
     * can route to the host. A non-loopback host is rejected unless
     * {@code allowNonLoopback} (the operator's explicit
     * {@code infochat.adapters.signal.allow-non-loopback-endpoint} opt-in)
     * is set. The check fails closed: a host that does not resolve to a
     * confirmed loopback address is treated as non-loopback.</p>
     *
     * <p>Package-private (not {@code private}) so the loopback policy is
     * unit-testable without booting the Producer.</p>
     */
    static InetSocketAddress parseEndpoint(String hostPort, boolean allowNonLoopback) {
        int idx = hostPort.lastIndexOf(':');
        if (idx <= 0 || idx == hostPort.length() - 1) {
            throw new IllegalStateException(
                    "infochat.adapters.signal.endpoint must be host:port, got: " + hostPort);
        }
        String host = hostPort.substring(0, idx);
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(idx + 1));
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "infochat.adapters.signal.endpoint port is not numeric: " + hostPort, e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException(
                    "infochat.adapters.signal.endpoint port out of 1..65535 range: " + port);
        }
        InetSocketAddress endpoint = new InetSocketAddress(host, port);
        InetAddress resolved = endpoint.getAddress();
        boolean loopback = resolved != null && resolved.isLoopbackAddress();
        if (!loopback && !allowNonLoopback) {
            throw new IllegalStateException(
                    "infochat.adapters.signal.endpoint host \"" + host + "\" is not a loopback"
                            + " address; signal-cli's unauthenticated TCP JSON-RPC daemon is"
                            + " trusted only on loopback. Set"
                            + " infochat.adapters.signal.allow-non-loopback-endpoint=true to opt"
                            + " into a non-loopback endpoint (e.g. a firewalled multi-NIC host).");
        }
        return endpoint;
    }
}
