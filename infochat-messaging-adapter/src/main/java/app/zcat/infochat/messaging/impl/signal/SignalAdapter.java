package app.zcat.infochat.messaging.impl.signal;

import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.util.Locale;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Signal production adapter. Spawns signal-cli as a TCP-JSON-RPC
 * daemon child process via {@link SignalSubprocess}, demultiplexes
 * the daemon's wire protocol via {@link SignalJsonRpcClient}, and
 * delivers inbound DM messages through the SPI's
 * {@link MessagingAdapter.InboundHandler}.
 *
 * <p>Signal's contact id is the ACI (Account Credential Identifier),
 * the UUID signal-cli surfaces as {@code mentionUuid} — the D10 trust
 * anchor — so this adapter is {@link AdapterTrustLevel#HIGH}. No CDI
 * annotations on this class: M1-106 fixed the
 * {@code InMemoryAdapter}-parity posture that CDI discovery is
 * Provider-side wiring (M1-035b / M1-105). The Provider-side producer
 * reads {@code infochat.adapters.signal.*} via Quarkus Config
 * (validated by the {@link SignalConfig} eager bean at boot) and
 * constructs the adapter with the resolved binary path, data dir,
 * account, and TCP daemon endpoint.</p>
 *
 * <p>{@link SignalConfig} is NOT held by reference here — it is a
 * boot-time validation bean (its {@code @PostConstruct} fails
 * startup on bad operator config). The adapter takes the resolved
 * string values directly via constructor so the
 * {@code messaging-adapter} module does not depend on the Quarkus
 * Config injection of {@link SignalConfig}'s private fields
 * (SignalConfig has no public getters by M1-106 design, and adding
 * them is out of M1-107's files_scope). The Provider-side wiring
 * documents this contract.</p>
 *
 * <p>Failed-state observation: when {@link SignalSubprocess} hits
 * its restart cap, {@code state() == FAILED} is observable by
 * Provider's {@code AdapterRegistry} (M1-105), which dispatches the
 * throttled admin notification per {@code docs/design/06-messaging.md}
 * §6.5.6 — this module does not depend on {@code infochat-core} and
 * therefore does not call {@code ThrottledAdminNotifier} directly.
 * Acceptance item 4 ("throttled admin notification") is satisfied by
 * this contract: the adapter exposes the failed state; Provider does
 * the notification.</p>
 */
public final class SignalAdapter implements MessagingAdapter {

    private static final Logger LOG = Logger.getLogger(SignalAdapter.class);

    // 16 KiB laptop default per docs/design/06-messaging.md §6.2.2;
    // maxMessageBytes / maxSendsPerSecond / minEditInterval are best-guess
    // defaults expected to be tuned against a live signal-cli.
    private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
            /* supportsMentionByContactId */ true,
            /* supportsMembershipEvents   */ true,
            /* supportsCodeFormatting     */ true,
            /* supportsMarkdownLinks      */ false,
            /* supportsMultilineCode      */ false,
            /* supportsAttachments        */ false,
            /* supportsThreading          */ false,
            /* maxMessageBytes            */ 2_000,
            /* maxInboundMessageBytes     */ 16_384,
            /* maxInflightSends           */ 4,
            /* maxSendsPerSecond          */ 8,
            /* supportsMessageEdit        */ true,
            /* supportsTypingIndicator    */ true,
            /* minEditInterval            */ Duration.ZERO);

    private static final Duration ENDPOINT_PROBE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration ENDPOINT_PROBE_INTERVAL = Duration.ofMillis(100);
    private static final Duration JSONRPC_RESPONSE_TIMEOUT = Duration.ofSeconds(15);
    private static final int SUBPROCESS_MAX_RESTARTS = 5;

    @Nullable private final String binary;
    @Nullable private final String dataDir;
    @Nullable private final String account;
    @Nullable private final String botAci;
    @Nullable private final InetSocketAddress daemonEndpoint;

    @Nullable private volatile SignalSubprocess subprocess;
    @Nullable private volatile SignalJsonRpcClient client;
    @Nullable private volatile InboundHandler handler;
    @Nullable private volatile MembershipHandler membershipHandler;

    /**
     * Capability-introspection constructor used by tests that only
     * inspect {@link #name()}, {@link #trustLevel()}, and
     * {@link #capabilities()}. Calling {@link #start()} on an adapter
     * built with this constructor throws {@link IllegalStateException}.
     */
    public SignalAdapter() {
        this.binary = null;
        this.dataDir = null;
        this.account = null;
        this.botAci = null;
        this.daemonEndpoint = null;
    }

    /**
     * Production constructor. Provider-side wiring (M1-035b/M1-105)
     * reads {@code infochat.adapters.signal.*} via Quarkus Config and
     * constructs the adapter with the resolved values; the boot-time
     * {@link SignalConfig} bean validates the same keys eagerly so a
     * misconfigured deployment fails startup, not the first
     * {@link #start()}.
     *
     * @param binary         the signal-cli executable path.
     * @param dataDir        the signal-cli data directory.
     * @param account        the signal-cli account identifier (E.164 phone or ACI).
     * @param botAci         the bot's per-adapter ACI (UUID) — the D10
     *                       trust anchor for group-mode mention
     *                       recognition. Resolved by Provider-side
     *                       wiring (M1-035b/M1-105) via
     *                       {@link SignalIdentity#resolve}.
     * @param daemonEndpoint the local TCP endpoint signal-cli's
     *                       {@code daemon --tcp host:port} will bind
     *                       and the JSON-RPC client will connect to.
     */
    public SignalAdapter(@NonNull String binary,
                         @NonNull String dataDir,
                         @NonNull String account,
                         @NonNull String botAci,
                         @NonNull InetSocketAddress daemonEndpoint) {
        this.binary = binary;
        this.dataDir = dataDir;
        this.account = account;
        // Canonicalize at construction so the cross-adapter
        // (adapter, contact_id) join key from messaging.md §Per-adapter
        // trust level cannot be broken by case-folding upstream — the
        // group handler's mention check compares lower-cased ACIs.
        this.botAci = botAci.toLowerCase(Locale.ROOT);
        this.daemonEndpoint = daemonEndpoint;
    }

    @Override
    public String name() {
        return "signal";
    }

    @Override
    public CapabilityFlags capabilities() {
        return CAPABILITIES;
    }

    @Override
    public AdapterTrustLevel trustLevel() {
        return AdapterTrustLevel.HIGH;
    }

    /**
     * Start the signal-cli subprocess, wait for its TCP JSON-RPC
     * endpoint to become reachable, then open the JSON-RPC client.
     *
     * @throws IllegalStateException if the capability-only constructor
     *         was used or if the start sequence fails.
     */
    public void start() {
        if (binary == null || dataDir == null || account == null
                || botAci == null || daemonEndpoint == null) {
            throw new IllegalStateException(
                    "SignalAdapter.start() requires the production constructor "
                            + "(binary, dataDir, account, botAci, daemonEndpoint).");
        }
        // D10 trust anchor: the bot's ACI must be a real Signal account
        // identifier, never blank. A blank ACI breaks the ACI-anchored
        // mention recognition group handler builds (lower-cased compare
        // in SignalGroupHandler) and conflates the bot's identity with
        // any operator-supplied blank-default. Property key is named so
        // the operator can fix it directly.
        if (botAci.isBlank()) {
            throw new IllegalStateException(
                    "infochat.adapters.signal.bot-aci must be set to the"
                            + " bot's own Signal ACI (distinct from the"
                            + " bootstrap admin's ACI in"
                            + " infochat.adapters.signal.admin)");
        }
        ProcessBuilder pb = new ProcessBuilder(
                binary,
                "--config", dataDir,
                "-a", account,
                "daemon",
                "--tcp", daemonEndpoint.getHostString() + ":" + daemonEndpoint.getPort());
        SignalSubprocess sp = new SignalSubprocess(
                pb,
                daemonEndpoint,
                SignalSubprocess.BackoffPolicy.laptopDefault(),
                SUBPROCESS_MAX_RESTARTS);
        try {
            sp.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start signal-cli subprocess", e);
        }
        this.subprocess = sp;
        if (!awaitEndpoint(daemonEndpoint, ENDPOINT_PROBE_TIMEOUT)) {
            sp.stop();
            this.subprocess = null;
            throw new IllegalStateException(
                    "signal-cli daemon endpoint " + daemonEndpoint + " not reachable within "
                            + ENDPOINT_PROBE_TIMEOUT);
        }
        // Wire the hung-process escalation: when the JSON-RPC client sees a
        // run of consecutive response timeouts (a daemon that is alive but
        // deadlocked, so SignalSubprocess.onExit never fires), it kicks the
        // supervisor to force-restart the subprocess.
        SignalJsonRpcClient c = new SignalJsonRpcClient(
                daemonEndpoint, account, new SignalMessageCodec(), JSONRPC_RESPONSE_TIMEOUT,
                sp::restartHung);
        try {
            c.connect();
        } catch (IOException e) {
            sp.stop();
            this.subprocess = null;
            throw new IllegalStateException("Failed to connect SignalJsonRpcClient", e);
        }
        this.client = c;
        InboundHandler current = handler;
        if (current != null) {
            c.setInboundHandler(current);
        }
        LOG.infof("Signal adapter started; daemon endpoint=%s", daemonEndpoint);
    }

    /** Disconnect the JSON-RPC client and stop the signal-cli subprocess. */
    public void close() {
        SignalJsonRpcClient c = client;
        if (c != null) {
            c.disconnect();
            this.client = null;
        }
        SignalSubprocess sp = subprocess;
        if (sp != null) {
            sp.stop();
            this.subprocess = null;
        }
        LOG.infof("Signal adapter stopped");
    }

    @Override
    public Identity assertIdentity(@NonNull InboundMessage msg) {
        // The cryptographic assertion lives in the JSON-RPC client's
        // inbound translation (sourceUuid is the ACI signal-cli has
        // already verified at the protocol layer); the inbound delivered
        // here already carries the verified Identity. SimpleX-parity
        // pattern from InMemoryAdapter.
        return msg.sender();
    }

    @Override
    public MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException {
        return requireConnected("send").send(msg);
    }

    @Override
    public void update(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
        requireConnected("update").update(handle, body);
    }

    @Override
    public void finalize(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
        requireConnected("finalize").finalizeHandle(handle, body);
    }

    @Override
    public void setTyping(@NonNull ScopeRef scope, boolean typing) {
        SignalJsonRpcClient c = client;
        if (c == null) {
            // setTyping is best-effort per SPI; no exception.
            return;
        }
        c.setTyping(scope, typing);
    }

    @Override
    public void setInboundHandler(@NonNull InboundHandler handler) {
        this.handler = handler;
        SignalJsonRpcClient c = client;
        if (c != null) {
            c.setInboundHandler(handler);
        }
    }

    /**
     * Capture Provider's membership-event callback. Signal exposes
     * member-joined / member-left natively at the group v2 protocol
     * layer (capability flag {@code supportsMembershipEvents=true}),
     * so the adapter surfaces these events directly rather than
     * synthesising them from delivery failures. The dispatch path is
     * built by {@link #groupHandler()} once Provider has registered
     * both callbacks; the upstream JSON-RPC reader wiring that drives
     * the dispatch lands with the multi-adapter integration (M1-109).
     */
    @Override
    public void setMembershipEventHandler(@NonNull MembershipHandler handler) {
        this.membershipHandler = handler;
    }

    /**
     * Build a {@link SignalGroupHandler} that carries the bot's ACI and
     * the currently-registered inbound and membership callbacks. Returns
     * a fresh handler each call so a Provider that re-registers
     * callbacks at runtime sees the latest references; the handler is
     * stateless so allocation is cheap.
     *
     * @return a SignalGroupHandler ready to translate group-scope
     *         signal-cli notifications.
     * @throws IllegalStateException if the capability-only constructor
     *         was used (no botAci available).
     */
    SignalGroupHandler groupHandler() {
        if (botAci == null) {
            throw new IllegalStateException(
                    "SignalAdapter.groupHandler() requires the production constructor "
                            + "(botAci is needed for ACI mention recognition).");
        }
        return new SignalGroupHandler(botAci, handler, membershipHandler);
    }

    private SignalJsonRpcClient requireConnected(String op) throws MessagingException {
        SignalJsonRpcClient c = client;
        if (c == null) {
            throw new MessagingException(
                    FailureCategory.PERMANENT,
                    "Signal adapter " + op + ": JSON-RPC client not connected (start() not called or close() in progress)");
        }
        return c;
    }

    private static boolean awaitEndpoint(InetSocketAddress endpoint, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(endpoint, (int) ENDPOINT_PROBE_INTERVAL.toMillis() * 2);
                return true;
            } catch (IOException e) {
                try {
                    Thread.sleep(ENDPOINT_PROBE_INTERVAL.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
