package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SimpleX production adapter. Owns a {@link SimpleXSubprocess} that runs
 * a local simplex-chat instance and a {@link SimpleXWebSocketClient} that
 * speaks the simplex-chat WebSocket bot API. Implements the full
 * {@link MessagingAdapter} contract: send / update / finalizeMessage via
 * the WebSocket, typing as a best-effort fire-and-forget, identity assertion
 * from the SimpleX-cryptographically-routed inbound envelope (decision
 * D10 + D32, {@code docs/spec/messaging.md} §Per-adapter trust level).
 *
 * <p>The no-arg constructor is preserved so the pre-existing
 * {@code SimpleXAdapterSkeletonTest} (which only inspects the static
 * capability flags) keeps passing. Call sites that intend to
 * {@link #start} or transport messages MUST use the configured
 * constructor — the no-arg variant intentionally cannot reach the wire,
 * and methods that need the deps throw {@link IllegalStateException}.
 * Provider-side wiring (M1-105) instantiates with the configured form.</p>
 *
 * <p>The supportsTypingIndicator capability flag is {@code false} per
 * design §6.4.2 (SimpleX has no first-class typing indicator). {@link
 * #setTyping} still issues the {@code apiSetContactTyping}-shaped command
 * on a best-effort basis; if simplex-chat rejects it, the
 * fire-and-forget path absorbs the failure — typing is best-effort by
 * the SPI's own contract ({@link MessagingAdapter#setTyping}).</p>
 */
public final class SimpleXAdapter implements MessagingAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleXAdapter.class);

    // maxInboundMessageBytes is the 16 KiB laptop default per
    // docs/design/06-messaging.md §6.2.2 (profile-tunable). maxMessageBytes,
    // maxSendsPerSecond, and minEditInterval are best-guess defaults not
    // fixed by spec and are expected to be tuned against a live simplex-chat
    // in M1-105.
    private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
            /* supportsMentionByContactId */ true,
            /* supportsMembershipEvents   */ false,
            /* supportsCodeFormatting     */ false,
            /* supportsMarkdownLinks      */ false,
            /* supportsMultilineCode      */ false,
            /* supportsAttachments        */ false,
            /* supportsThreading          */ false,
            /* maxMessageBytes            */ 2_000,
            /* maxInboundMessageBytes     */ 16_384,
            /* maxInflightSends           */ 4,
            /* maxSendsPerSecond          */ 8,
            /* supportsMessageEdit        */ true,
            /* supportsTypingIndicator    */ false,
            /* minEditInterval            */ Duration.ZERO);

    static final Duration WS_READY_TIMEOUT = Duration.ofSeconds(10);
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    private final @Nullable SimpleXConfig config;
    private final @Nullable HttpClient httpClient;
    private final @Nullable Consumer<String> adminNotifier;
    private final @Nullable SimpleXIdentity botIdentity;

    /**
     * Upper bound on tracked send-handles. Pre-M1-148 the handle and
     * finalized tables grew for the life of the adapter (one entry per
     * send, never removed). Access-order LRU keeps the hot tail; an
     * evicted handle behaves exactly like an unknown one (PERMANENT
     * "unknown handle"), the same outcome a Provider restart produces —
     * handles are in-process-only by the {@link MessageHandle} contract.
     */
    static final int MAX_TRACKED_HANDLES = 1_024;

    private final AtomicLong handleCounter = new AtomicLong();
    private final AtomicLong commandCounter = new AtomicLong();
    /** Guarded by its own monitor — LinkedHashMap is not thread-safe. */
    private final Map<String, TrackedHandle> handles =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TrackedHandle> eldest) {
                    return size() > MAX_TRACKED_HANDLES;
                }
            };

    private volatile @Nullable InboundHandler inboundHandler;
    private volatile @Nullable SimpleXSubprocess subprocess;
    private volatile @Nullable SimpleXWebSocketClient webSocket;

    /**
     * Capability-only constructor. The resulting adapter can answer
     * {@link #name}, {@link #trustLevel}, {@link #capabilities}, and
     * {@link #setInboundHandler} (the handler is stored for inspection)
     * but every transport call — {@link #start}, {@link #send},
     * {@link #update}, {@link #finalizeMessage}, {@link #setTyping},
     * {@link #assertIdentity} — throws because no config or
     * {@link HttpClient} was provided. Used by
     * {@code SimpleXAdapterSkeletonTest} to assert the static capability
     * surface.
     */
    public SimpleXAdapter() {
        this.config = null;
        this.httpClient = null;
        this.adminNotifier = null;
        this.botIdentity = null;
    }

    /**
     * Full constructor used by Provider-side wiring (M1-105). Supplies
     * the operator config, the JDK {@link HttpClient} used to dial the
     * simplex-chat WebSocket, the admin-notification consumer the
     * subprocess supervisor calls at the FAILED transition, and the
     * bot's per-adapter SimpleX identity used as the D10 trust anchor
     * for group mention recognition (see {@link SimpleXGroupHandler}).
     */
    public SimpleXAdapter(SimpleXConfig config,
                          HttpClient httpClient,
                          Consumer<String> adminNotifier,
                          SimpleXIdentity botIdentity) {
        this.config = config;
        this.httpClient = httpClient;
        this.adminNotifier = adminNotifier;
        this.botIdentity = botIdentity;
    }

    @Override
    public String name() {
        return "simplex";
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
     * Start the simplex-chat subprocess, wait for its WebSocket endpoint
     * to become reachable, then open the WebSocket. Acceptance items 5 +
     * 6 of M1-103. Throws {@link MessagingException} (categorised) on
     * launch / readiness / connect failure so Provider sees the failure
     * via the same exception channel as transport faults.
     */
    @Override
    public void start() throws MessagingException {
        SimpleXConfig cfg = requireWired();
        HttpClient http = httpClient;
        Consumer<String> notify = adminNotifier;
        SimpleXIdentity identity = botIdentity;
        if (http == null || notify == null || identity == null) {
            throw new IllegalStateException(
                    "SimpleXAdapter not fully wired (httpClient/adminNotifier/botIdentity are null)");
        }
        // System-boundary validation: filesystem/port checks the operator
        // promised at config time. Runs only for activated adapters so an
        // inmemory-only deployment never trips simplex's checks. Without
        // this call a mis-typed binary path or out-of-range ws-port
        // surfaces deep inside subprocess launch as an opaque exception
        // that MessagingStartup's §6.7 per-adapter catch silently absorbs.
        cfg.validate();
        // D10 trust anchor: the bot's queue address must be a real
        // cryptographic identity, never blank. A blank identity would let
        // SimpleXMentionParser.botMentioned match any mention list entry
        // that decodes to empty bytes (the forged-mention class the spec
        // forever excludes per security.md §"What's intentionally NOT in
        // v1"). Property key is named so an operator can fix it directly.
        if (identity.queueAddress().isBlank()) {
            throw new IllegalStateException(
                    "infochat.adapters.simplex.bot-queue-address must be set"
                            + " to the bot's own SimpleX queue address (distinct"
                            + " from the bootstrap admin's queue address in"
                            + " infochat.adapters.simplex.admin)");
        }
        SimpleXSubprocess sub = new SimpleXSubprocess(
                SimpleXSubprocess.commandFor(cfg),
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                SimpleXSubprocess.DEFAULT_CRASH_CAP,
                notify,
                new Random());
        sub.start();
        this.subprocess = sub;
        try {
            waitForWebSocketReady(cfg.wsPort());
        } catch (MessagingException e) {
            sub.stop();
            this.subprocess = null;
            throw e;
        }
        URI uri = URI.create("ws://127.0.0.1:" + cfg.wsPort());
        // The group handler funnels its deliveries through onInbound so
        // both DM and group paths share the volatile-field read of
        // `inboundHandler` and the misbehaving-handler protection in
        // one place. A Provider that calls setInboundHandler after
        // start() still gets group messages routed correctly because
        // onInbound re-reads the field on each dispatch.
        SimpleXGroupHandler groupHandler = new SimpleXGroupHandler(identity, this::onInbound);
        SimpleXWebSocketClient ws = new SimpleXWebSocketClient(
                uri, http, this::onInbound, groupHandler::onGroupCandidate);
        try {
            ws.start();
        } catch (MessagingException e) {
            sub.stop();
            this.subprocess = null;
            throw e;
        }
        this.webSocket = ws;
    }

    /**
     * Disconnect the WebSocket and terminate the simplex-chat subprocess
     * (SIGTERM, grace, SIGKILL via {@link SimpleXSubprocess#stop}).
     * Idempotent — safe to call on a never-started adapter or after a
     * prior close.
     */
    public void close() {
        SimpleXWebSocketClient ws = webSocket;
        if (ws != null) {
            ws.close();
            webSocket = null;
        }
        SimpleXSubprocess sub = subprocess;
        if (sub != null) {
            sub.stop();
            subprocess = null;
        }
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
        // SimpleX is HIGH trust because the contact id is the cryptographic
        // queue address SimpleX's message-routing layer verifies before
        // delivery to the bot. The codec extracted the verified contact id
        // into msg.sender() at decode time; assertIdentity returns it
        // directly (the assertion already happened upstream of the codec).
        return msg.sender();
    }

    @Override
    public MessageHandle send(OutboundMessage msg) throws MessagingException {
        SimpleXWebSocketClient ws = requireConnected();
        String corrId = nextCorrId();
        String envelope = SimpleXMessageCodec.encodeSendCommand(corrId, msg.scope(), msg.text());
        String chatItemId = ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
        String opaque = "simplex-" + handleCounter.incrementAndGet();
        TrackedHandle tracked = new TrackedHandle(
                new SimpleXMessageHandle(chatItemId, msg.scope(), msg.correlationId()));
        synchronized (handles) {
            handles.put(opaque, tracked);
        }
        return new MessageHandle(opaque);
    }

    @Override
    public void update(MessageHandle handle, String body) throws MessagingException {
        SimpleXMessageHandle internal = requireKnownAndOpen(handle);
        SimpleXWebSocketClient ws = requireConnected();
        String corrId = nextCorrId();
        String envelope = SimpleXMessageCodec.encodeUpdateCommand(
                corrId, internal.chatItemId(), internal.scope(), body);
        // update returns the (possibly changed) chatItemId — the SimpleX
        // surface re-acks edits with the same id. We don't need the return
        // value here, only the success/failure outcome.
        ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) throws MessagingException {
        SimpleXMessageHandle internal = requireKnownAndOpen(handle);
        SimpleXWebSocketClient ws = requireConnected();
        String corrId = nextCorrId();
        String envelope = SimpleXMessageCodec.encodeFinalizeCommand(
                corrId, internal.chatItemId(), internal.scope(), body);
        ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
        // SPI contract: after finalizeMessage, any update() on the same
        // handle MUST throw PERMANENT. The flag is checked above in
        // requireKnownAndOpen — set it here on success.
        markFinalized(handle.opaqueValue());
    }

    @Override
    public void setTyping(ScopeRef scope, boolean typing) {
        SimpleXWebSocketClient ws = webSocket;
        if (ws == null) {
            // Best-effort: an un-started or closed adapter silently absorbs
            // the typing pulse per SPI Javadoc on setTyping (no throw).
            return;
        }
        try {
            String envelope = SimpleXMessageCodec.encodeTypingCommand(
                    nextCorrId(), scope, typing);
            ws.sendFireAndForget(envelope);
        } catch (MessagingException e) {
            // Best-effort: encodeTypingCommand now propagates a checked
            // PERMANENT on a bad queue address (the codec's encode-time
            // validator). setTyping has a no-throw SPI contract, so absorb
            // it here exactly as the ws == null branch above absorbs an
            // un-started adapter.
            LOG.debug("setTyping absorbed encode failure: {}", e.category());
        }
    }

    @Override
    public void setInboundHandler(InboundHandler handler) {
        this.inboundHandler = handler;
    }

    // -- internals -----------------------------------------------------------

    private void onInbound(InboundMessage msg) {
        InboundHandler current = inboundHandler;
        if (current == null) {
            // No registered consumer yet — Provider hasn't called
            // setInboundHandler. Dropping is the right move; the inbound
            // event arrived before the consumer attached.
            LOG.debug("dropping inbound; no handler registered");
            return;
        }
        try {
            current.onMessage(msg);
        } catch (RuntimeException e) {
            // The handler is Provider-side code; a misbehaving handler must
            // not tear the WebSocket listener thread down.
            LOG.warn("inbound handler threw: {}", e.getClass().getSimpleName());
        }
    }

    private SimpleXMessageHandle requireKnownAndOpen(MessageHandle handle) throws MessagingException {
        TrackedHandle tracked;
        synchronized (handles) {
            tracked = handles.get(handle.opaqueValue());
        }
        if (tracked == null) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "unknown handle: " + handle.opaqueValue());
        }
        synchronized (handles) {
            if (tracked.finalized) {
                throw new MessagingException(FailureCategory.PERMANENT,
                        "handle already finalized: " + handle.opaqueValue());
            }
        }
        return tracked.handle;
    }

    private void markFinalized(String opaqueValue) {
        synchronized (handles) {
            TrackedHandle tracked = handles.get(opaqueValue);
            if (tracked != null) {
                tracked.finalized = true;
            }
        }
    }

    private SimpleXConfig requireWired() {
        SimpleXConfig cfg = config;
        if (cfg == null) {
            throw new IllegalStateException(
                    "SimpleXAdapter constructed without config — use the (config, httpClient, "
                            + "adminNotifier) constructor for transport operations");
        }
        return cfg;
    }

    private SimpleXWebSocketClient requireConnected() throws MessagingException {
        SimpleXWebSocketClient ws = webSocket;
        if (ws == null) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "SimpleXAdapter is not started; call start() first");
        }
        return ws;
    }

    private String nextCorrId() {
        return "simplex-cmd-" + commandCounter.incrementAndGet();
    }

    /**
     * TCP-probe loop: dial 127.0.0.1:wsPort until the handshake succeeds
     * (simplex-chat opened its socket) or the deadline elapses. The WS
     * endpoint must be reachable before WS connect — otherwise the
     * {@link HttpClient} handshake fails with a connection-refused that
     * the supervisor then routes through a full restart cycle.
     *
     * <p>Probe pacing is exponential backoff: simplex-chat usually opens
     * its socket quickly, so probe densely at first (50 ms) and back off
     * ×2 toward a 1 s ceiling as failures accumulate. Each sleep is
     * additionally capped at the time remaining to the deadline so the
     * loop never oversleeps past it.</p>
     */
    private void waitForWebSocketReady(int port) throws MessagingException {
        long deadline = System.nanoTime() + WS_READY_TIMEOUT.toNanos();
        long sleepMs = 50;
        Throwable lastError = null;
        while (System.nanoTime() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                lastError = e;
                long remainingMs = Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
                try {
                    Thread.sleep(Math.min(sleepMs, remainingMs));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new MessagingException(FailureCategory.TRANSIENT,
                            "interrupted while waiting for WebSocket port", ie);
                }
                sleepMs = Math.min(sleepMs * 2, 1_000);
            }
        }
        throw new MessagingException(FailureCategory.TRANSIENT,
                "simplex-chat WebSocket port " + port + " not reachable within "
                        + WS_READY_TIMEOUT,
                lastError);
    }

    /**
     * One tracked send-handle: the internal SimpleX handle plus its
     * finalized flag, merged into a single LRU entry so eviction can
     * never strand a finalized-flag for a forgotten handle (the
     * pre-M1-148 shape kept two parallel unbounded maps).
     */
    private static final class TrackedHandle {
        final SimpleXMessageHandle handle;
        /** Guarded by the {@code handles} monitor. */
        boolean finalized;

        TrackedHandle(SimpleXMessageHandle handle) {
            this.handle = handle;
        }
    }
}
