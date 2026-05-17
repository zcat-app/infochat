package io.infochat.provider.messaging;

import io.infochat.messaging.AdapterTrustLevel;
import io.infochat.messaging.CapabilityFlags;
import io.infochat.messaging.MessagingAdapter;
import io.infochat.messaging.impl.inmemory.InMemoryAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provider-side adapter discovery + activation, per
 * {@code docs/design/06-messaging.md} §6.7. Discovers every CDI bean
 * implementing {@link MessagingAdapter}, filters to the subset whose
 * {@link MessagingAdapter#name()} appears in {@code infochat.adapters},
 * applies the six startup gates in the §6.7 documented order, and
 * registers the {@link InboundRouter} as the inbound handler on each
 * activated adapter. A connection failure on one adapter does not
 * prevent the others from coming up — that resilience loop lives in
 * {@link MessagingStartup#startAllAdapters}.
 *
 * <p><b>Producer for {@link InMemoryAdapter}.</b> The
 * {@code infochat-messaging-adapter} module is a plain library jar
 * (no {@code beans.xml}, no CDI annotations on
 * {@link InMemoryAdapter}). Without a {@link Produces} method here
 * the {@code Instance<MessagingAdapter>} injection point resolves to
 * an empty set in production and the {@code inmemory} name fails
 * gate 2 with no actionable error. The producer below is the single
 * mechanism that exposes {@link InMemoryAdapter} as an
 * {@code @ApplicationScoped} CDI bean; the messaging-adapter jar
 * stays frozen at M1-035a's commit.</p>
 *
 * <p><b>Startup order.</b> {@link MessagingStartup} (at
 * {@code @Priority(300)} per the Provider startup table in
 * {@code docs/design/01-architecture.md} §1.4.3) drives
 * {@link #start()}; the registry runs after the lock guard (50),
 * Flyway (100), and the new-post reconciler (250) have completed.
 * MVP InMemoryAdapter has no transport to start, so per-adapter
 * lifecycle wiring is shape-only — T3-A's SimpleX/Signal beans will
 * exercise the resilience loop in earnest.</p>
 *
 * <p><b>Gate order.</b> The six gates are evaluated in §6.7's
 * documented order; the first failure short-circuits with the most
 * specific {@link IllegalStateException} the implementation can
 * raise at that point. Each gate has a dedicated {@code @Test} in
 * {@code StartupGatesTest}.</p>
 */
@ApplicationScoped
public class AdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdapterRegistry.class);

    /**
     * MVP has no group SPI wired; gate 4 (mention-by-id +
     * group-SPI-wired) is vacuously satisfied at the constant level.
     * Tests exercise gate 4 by setting a hidden per-adapter test
     * property {@code infochat.adapters.<name>.test-group-spi-wired=true}
     * which the registry consults at gate-4 evaluation. T2-F flips this
     * constant (or migrates it to a real config property) when the
     * group SPI lands.
     */
    private static final boolean GROUP_SPI_WIRED = false;

    /**
     * Production name reserved by {@link InMemoryAdapter}. Gate 5
     * rejects this name appearing alongside any other adapter in
     * the activated set.
     */
    static final String INMEMORY_NAME = "inmemory";

    @Inject
    @Any
    Instance<MessagingAdapter> discoveredAdapters;

    @ConfigProperty(name = "infochat.adapters")
    String adaptersCsv;

    @Inject
    InboundRouter inboundRouter;

    private final List<MessagingAdapter> activatedAdapters = new ArrayList<>();

    /**
     * Producer that exposes {@link InMemoryAdapter} as an
     * {@code @ApplicationScoped} CDI bean. The messaging-adapter
     * library jar carries no CDI annotations of its own; without
     * this producer the bean would not be discoverable.
     */
    @Produces
    @ApplicationScoped
    InMemoryAdapter inMemoryAdapter() {
        return new InMemoryAdapter();
    }

    /**
     * Production entry point. Reads the injected {@code infochat.adapters}
     * value and applies the six startup gates. {@link MessagingStartup}
     * calls this at {@code @PostConstruct}.
     */
    public void start() {
        start(adaptersCsv);
    }

    /**
     * Apply the six startup gates from §6.7 in order against the given
     * comma-separated adapter list, then wire each activated adapter's
     * inbound handler to the {@link InboundRouter} and emit the §6.8
     * activation log line. The parameterized form exists so tests can
     * exercise each gate's sad path without having to round-trip
     * through Quarkus config sources (CDI client proxies do not
     * propagate direct field assignment to the underlying bean).
     */
    public void start(String csv) {
        // Idempotent: starting twice in the same JVM (e.g. across tests
        // that exercise different csv configurations) clears the prior
        // activated set so the new activation does not double-up.
        activatedAdapters.clear();

        List<String> requested = parseAdaptersList(csv);

        // Gate 1: infochat.adapters non-empty.
        if (requested.isEmpty()) {
            throw new IllegalStateException(
                    "infochat.adapters: no adapters configured (value=\""
                            + csv + "\")");
        }

        // Gate 2: every name resolves to a registered bean.
        Map<String, MessagingAdapter> byName = new LinkedHashMap<>();
        for (MessagingAdapter adapter : discoveredAdapters) {
            byName.put(adapter.name(), adapter);
        }
        List<MessagingAdapter> activating = new ArrayList<>();
        for (String name : requested) {
            MessagingAdapter adapter = byName.get(name);
            if (adapter == null) {
                throw new IllegalStateException(
                        "infochat.adapters: unknown adapter name \"" + name
                                + "\" (registered: " + byName.keySet() + ")");
            }
            activating.add(adapter);
        }

        // Gate 3: supportsMarkdownLinks=false per §6.2.1.
        for (MessagingAdapter adapter : activating) {
            if (adapter.capabilities().supportsMarkdownLinks()) {
                throw new IllegalStateException(
                        "Adapter \"" + adapter.name()
                                + "\" declares supportsMarkdownLinks=true (§6.2.1 forbids markdown links in v1)");
            }
        }

        // Gate 4: supportsMentionByContactId=false + group-SPI-wired per §6.7.
        for (MessagingAdapter adapter : activating) {
            CapabilityFlags caps = adapter.capabilities();
            if (!caps.supportsMentionByContactId() && isGroupSpiWired(adapter)) {
                throw new IllegalStateException(
                        "Adapter \"" + adapter.name()
                                + "\" declares supportsMentionByContactId=false but the group SPI is wired (§6.7)");
            }
        }

        // Gate 5: production-exclusion (inmemory + others) per §6.6.
        if (activating.size() > 1) {
            boolean hasInMemory = activating.stream()
                    .anyMatch(a -> INMEMORY_NAME.equals(a.name()));
            if (hasInMemory) {
                String names = activating.stream()
                        .map(MessagingAdapter::name)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                throw new IllegalStateException(
                        "Production-exclusion: \"" + INMEMORY_NAME
                                + "\" cannot run alongside other adapters (activated: "
                                + names + ") per §6.6");
            }
        }

        // Gate 6: per-adapter LOW-trust opt-in per §6.8.
        for (MessagingAdapter adapter : activating) {
            if (adapter.trustLevel() == AdapterTrustLevel.LOW
                    && !isLowTrustAllowed(adapter.name())) {
                throw new IllegalStateException(
                        "Adapter \"" + adapter.name()
                                + "\" reports trust=LOW but infochat.adapters."
                                + adapter.name()
                                + ".allow-low-trust=true is not set (§6.8)");
            }
        }

        // All gates passed. Wire each activated adapter to the router and
        // emit the §6.8 activation log line. Order matters: setReplyTarget
        // first so a misbehaving adapter that synchronously delivers from
        // inside setInboundHandler still finds a non-null reply target.
        for (MessagingAdapter adapter : activating) {
            inboundRouter.setReplyTarget(adapter);
            adapter.setInboundHandler(inboundRouter);
            log.info("activating adapter: {} (trust={}{})",
                    adapter.name(),
                    adapter.trustLevel(),
                    adapter.trustLevel() == AdapterTrustLevel.LOW
                            ? "; allow-low-trust=true"
                            : "");
            activatedAdapters.add(adapter);
        }
    }

    /** Immutable snapshot of activated adapters for {@link MessagingStartup} and tests. */
    public List<MessagingAdapter> activatedAdapters() {
        return List.copyOf(activatedAdapters);
    }

    /**
     * Per-adapter group-SPI-wired probe. Production MVP has no group
     * SPI; T2-F flips {@link #GROUP_SPI_WIRED} or migrates this probe
     * to a real config key. Tests exercise gate 4 by setting a hidden
     * per-adapter property {@code infochat.adapters.<name>.test-group-spi-wired=true}.
     */
    private boolean isGroupSpiWired(MessagingAdapter adapter) {
        Optional<Boolean> testFlag = ConfigProvider.getConfig().getOptionalValue(
                "infochat.adapters." + adapter.name() + ".test-group-spi-wired",
                Boolean.class);
        return testFlag.orElse(GROUP_SPI_WIRED);
    }

    private static List<String> parseAdaptersList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static boolean isLowTrustAllowed(String adapterName) {
        Config config = ConfigProvider.getConfig();
        return config.getOptionalValue(
                "infochat.adapters." + adapterName + ".allow-low-trust",
                Boolean.class).orElse(false);
    }
}
