package app.zcat.infochat.messaging.impl.signal;

import org.jboss.logging.Logger;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Signal production adapter. Spawns signal-cli as a TCP-JSON-RPC
 * daemon child process via {@link SignalSubprocess}, demultiplexes
 * the daemon's wire protocol via {@link SignalJsonRpcClient}, and
 * delivers inbound DM and group-mention messages through the SPI's
 * {@link MessagingAdapter.InboundHandler} and membership events
 * through the registered {@link MessagingAdapter.MembershipHandler}
 * (group-scope envelopes route via {@link SignalGroupHandler}).
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
    // Single-flight latch: two restart notifications must not run two
    // concurrent reconnects (overlapping disconnect/connect would race the
    // reader/dispatcher teardown). A failed attempt simply ends; the next
    // restart notification retries.
    private final AtomicBoolean reconnectInFlight = new AtomicBoolean();

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
     *                       recognition. Sourced by Provider-side
     *                       wiring (M1-035b/M1-105) from
     *                       {@code infochat.adapters.signal.bot-aci}.
     * @param daemonEndpoint the local TCP endpoint signal-cli's
     *                       {@code daemon --tcp host:port} will bind
     *                       and the JSON-RPC client will connect to.
     */
    public SignalAdapter(String binary,
                         String dataDir,
                         String account,
                         String botAci,
                         InetSocketAddress daemonEndpoint) {
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
     * @throws MessagingException on transport startup failure (the SPI
     *         contract): subprocess spawn failure, daemon endpoint not
     *         reachable within the probe timeout, or JSON-RPC connect
     *         failure.
     * @throws IllegalStateException if the capability-only constructor
     *         was used, or on invalid operator config (blank bot ACI) —
     *         programming/config errors, not transport failures.
     */
    @Override
    public void start() throws MessagingException {
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
            // PERMANENT: a spawn failure is a missing/unexecutable binary,
            // not a recoverable transport outage (and the throw-site
            // discipline defaults the undecidable cases to PERMANENT).
            throw new MessagingException(FailureCategory.PERMANENT,
                    "Failed to start signal-cli subprocess", e);
        }
        attachSubprocess(sp);
        if (!awaitEndpoint(daemonEndpoint, ENDPOINT_PROBE_TIMEOUT)) {
            sp.stop();
            this.subprocess = null;
            throw new MessagingException(FailureCategory.TRANSIENT,
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
        connectClient(c, sp);
        attachClient(c);
        LOG.infof("Signal adapter started; daemon endpoint=%s", daemonEndpoint);
    }

    /**
     * Connect the JSON-RPC client, mapping a connect-time
     * {@link IOException} to the SPI's checked {@link MessagingException}
     * (TRANSIENT: the daemon answered the endpoint probe moments earlier,
     * so a refused/reset connect is a recoverable outage shape) and
     * tearing the just-started subprocess down so a failed {@link #start()}
     * leaves no orphaned child.
     *
     * <p>Package-private seam mirroring {@link #attachClient} /
     * {@link #attachSubprocess}: a daemon that vanishes between the
     * endpoint probe and the connect is a timing window no test can
     * produce deterministically through {@link #start()}, so the
     * connect-failure contract is pinned against this seam.</p>
     */
    void connectClient(SignalJsonRpcClient c, SignalSubprocess sp) throws MessagingException {
        try {
            c.connect();
        } catch (IOException e) {
            sp.stop();
            this.subprocess = null;
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "Failed to connect SignalJsonRpcClient", e);
        }
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

    /**
     * SPI lifecycle teardown — delegates to {@link #close()} (the
     * pre-existing teardown entry point) so both spellings share one
     * idempotent implementation.
     */
    @Override
    public void stop() {
        close();
    }

    @Override
    public Identity assertIdentity(InboundMessage msg) {
        // The cryptographic assertion lives in the JSON-RPC client's
        // inbound translation (sourceUuid is the ACI signal-cli has
        // already verified at the protocol layer); the inbound delivered
        // here already carries the verified Identity. SimpleX-parity
        // pattern from InMemoryAdapter.
        return msg.sender();
    }

    @Override
    public MessageHandle send(OutboundMessage msg) throws MessagingException {
        return requireConnected("send").send(msg);
    }

    @Override
    public void update(MessageHandle handle, String body) throws MessagingException {
        requireConnected("update").update(handle, body);
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) throws MessagingException {
        requireConnected("finalizeMessage").finalizeHandle(handle, body);
    }

    @Override
    public void setTyping(ScopeRef scope, boolean typing) {
        SignalJsonRpcClient c = client;
        if (c == null) {
            // setTyping is best-effort per SPI; no exception.
            return;
        }
        c.setTyping(scope, typing);
    }

    @Override
    public void setInboundHandler(InboundHandler handler) {
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
     * built by {@link #groupHandler()}, which the JSON-RPC client's
     * group-notification route drives (wired in {@link #attachClient}).
     */
    @Override
    public void setMembershipEventHandler(MembershipHandler handler) {
        this.membershipHandler = handler;
    }

    /**
     * Wire a connected JSON-RPC client into this adapter: store it,
     * register the currently-set inbound handler, and route non-DM
     * receive notifications into a {@link SignalGroupHandler}. The
     * group route builds a fresh handler per notification so callbacks
     * registered after {@link #start()} are still seen — mirrors
     * {@link #setInboundHandler}'s live re-wire, and
     * {@link #groupHandler()} is stateless so the allocation is cheap.
     *
     * <p>Package-private seam: the FakeSignalCli-driven tests exercise
     * this exact production wiring without {@link #start()}, which
     * requires a real signal-cli subprocess.</p>
     */
    void attachClient(SignalJsonRpcClient c) {
        this.client = c;
        InboundHandler current = handler;
        if (current != null) {
            c.setInboundHandler(current);
        }
        c.setGroupNotificationHandler(params -> groupHandler().handleReceive(params));
    }

    /**
     * Wire a started subprocess supervisor into this adapter: store it
     * and register the restart→reconnect listener, so a supervised
     * respawn revives the JSON-RPC transport that died with the previous
     * child (design §6.4.6: subprocess + connection are one supervised
     * unit).
     *
     * <p>Package-private seam mirroring {@link #attachClient}: the
     * FakeSignalCli-driven tests exercise the production restart→reconnect
     * wiring without {@link #start()}, which requires a real signal-cli
     * binary.</p>
     */
    void attachSubprocess(SignalSubprocess sp) {
        this.subprocess = sp;
        sp.onRestart(this::onSubprocessRestart);
    }

    /**
     * Restart notification entry point. Fires on the supervisor's
     * single-thread watchdog scheduler — the reconnect blocks on an
     * endpoint probe (up to {@link #ENDPOINT_PROBE_TIMEOUT}) and a reader
     * join, so it must hop to its own thread or crash detection stalls.
     */
    private void onSubprocessRestart() {
        Thread t = new Thread(this::reconnect, "signal-adapter-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void reconnect() {
        if (!reconnectInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            SignalSubprocess sp = subprocess;
            SignalJsonRpcClient c = client;
            if (sp == null || c == null) {
                // close() ran, or start() never finished wiring the client —
                // nothing to revive.
                return;
            }
            if (!awaitEndpoint(sp.endpoint(), ENDPOINT_PROBE_TIMEOUT)) {
                // Daemon respawned but its endpoint never came up — likely
                // crashing again; the next restart notification retries.
                LOG.warnf("Signal reconnect aborted: daemon endpoint %s not reachable within %s",
                        sp.endpoint(), ENDPOINT_PROBE_TIMEOUT);
                return;
            }
            // Teardown-before-serve: disconnect() joins the old reader and
            // shuts the dispatch executor before connect() builds fresh
            // ones, so a half-dead prior connection can never interleave
            // with (or double-deliver into) the new one. Sends during this
            // window classify TRANSIENT inside the client.
            c.disconnect();
            try {
                c.connect();
            } catch (IOException e) {
                LOG.warnf("Signal reconnect failed: %s; awaiting next supervised restart",
                        e.getClass().getSimpleName());
                return;
            }
            if (subprocess == null || client == null) {
                // close() won the race while we were connecting — do not
                // resurrect the transport after teardown.
                c.disconnect();
                return;
            }
            LOG.infof("Signal adapter reconnected after subprocess restart");
        } finally {
            reconnectInFlight.set(false);
        }
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
        // Exponential backoff from the base probe interval toward a 1 s
        // ceiling; each sleep is additionally capped at the time remaining
        // so the loop never oversleeps past the deadline.
        long sleepMs = ENDPOINT_PROBE_INTERVAL.toMillis();
        while (System.nanoTime() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(endpoint, (int) ENDPOINT_PROBE_INTERVAL.toMillis() * 2);
                return true;
            } catch (IOException e) {
                long remainingMs = Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
                try {
                    Thread.sleep(Math.min(sleepMs, remainingMs));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                sleepMs = Math.min(sleepMs * 2, 1_000);
            }
        }
        return false;
    }
}
