package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.OutboundRateLimiter;
import app.zcat.infochat.messaging.ScopeRef;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SimpleX production adapter. Owns a {@link SimpleXSubprocess} that runs
 * a local simplex-chat instance and a {@link SimpleXWebSocketClient} that
 * speaks the simplex-chat WebSocket bot API. Implements the full
 * {@link MessagingAdapter} contract: send / update / finalizeMessage via
 * the WebSocket, typing as a capability-declared no-op, identity assertion
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
 * design §6.4.2 (SimpleX has no first-class typing indicator), so
 * {@link #setTyping} is a no-op — the SPI contract
 * ({@link MessagingAdapter#setTyping}) says "No-op for adapters with
 * {@link CapabilityFlags#supportsTypingIndicator} false"; its
 * best-effort wording covers transport-failure absorption on adapters
 * that DO support typing, not permission to send the command anyway.</p>
 */
public final class SimpleXAdapter implements MessagingAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleXAdapter.class);

    // maxInboundMessageBytes is the 16 KiB laptop default per
    // docs/design/06-messaging.md §6.2.2 (profile-tunable).
    // maxSendsPerSecond and minEditInterval take design
    // §6.4.2's conservative values (5/s, 600 ms floor), to be raised only
    // after observation.
    private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
            /* supportsMentionByContactId */ true,
            /* supportsMembershipEvents   */ false,
            /* supportsCodeFormatting     */ false,
            /* supportsMarkdownLinks      */ false,
            /* maxInboundMessageBytes     */ 16_384,
            /* maxSendsPerSecond          */ 5,
            /* supportsMessageEdit        */ true,
            /* supportsTypingIndicator    */ false,
            /* minEditInterval            */ Duration.ofMillis(600));

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
    // Outbound send pacer (design §6.3.6): bounds transmits to
    // CAPABILITIES.maxSendsPerSecond so the Provider cannot drive
    // simplex-chat fast enough to trip its server-side rate limit. Shared
    // across send / update / finalizeMessage — each frame draws one token.
    private final OutboundRateLimiter outboundRate =
            new OutboundRateLimiter(CAPABILITIES.maxSendsPerSecond(), Clock.systemUTC());
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
    // True from the moment the reconnect path starts tearing down the old
    // WebSocket client until a fresh one is swapped in. Sends during this
    // window classify TRANSIENT (the outage is recoverable); a closed
    // SimpleXWebSocketClient would otherwise classify them PERMANENT. Left
    // set when a rebuild attempt fails — the outage continues until the
    // next restart notification (or the supervisor's FAILED transition).
    private volatile boolean reconnecting;
    // Single-flight latch: two restart notifications must not run two
    // concurrent rebuilds (overlapping close/build would race the client
    // swap). Always cleared, unlike `reconnecting`.
    private final AtomicBoolean reconnectInFlight = new AtomicBoolean();

    /**
     * Capability-only constructor. The resulting adapter can answer
     * {@link #name}, {@link #trustLevel}, {@link #capabilities}, and
     * {@link #setInboundHandler} (the handler is stored for inspection)
     * but every transport call — {@link #start}, {@link #send},
     * {@link #update}, {@link #finalizeMessage} — throws because no
     * config or {@link HttpClient} was provided ({@link #setTyping} is a
     * capability-declared no-op on every instance). Used by
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

    @Override
    public boolean isWellFormedContactId(String contactId) {
        return SimpleXIdentity.isWellFormed(contactId);
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
        attachSubprocess(sub);
        try {
            waitForWebSocketReady(cfg.wsPort());
            rebuildWebSocket();
        } catch (MessagingException e) {
            sub.stop();
            this.subprocess = null;
            throw e;
        }
    }

    /**
     * Wire a started subprocess supervisor into this adapter: store it
     * and register the restart→rebuild listener, so a supervised respawn
     * revives the WebSocket client that died with the previous child —
     * the rebuild mechanism the {@link SimpleXSubprocess} javadoc
     * promises (design §6.4.6: subprocess + connection are one
     * supervised unit).
     *
     * <p>Package-private seam: the FakeSimpleXProcess-driven tests
     * exercise the production restart→rebuild wiring without
     * {@link #start()}, which requires a real simplex-chat binary.</p>
     */
    void attachSubprocess(SimpleXSubprocess sub) {
        this.subprocess = sub;
        sub.onRestart(this::onSubprocessRestart);
    }

    /**
     * Build a fresh {@link SimpleXWebSocketClient} (with its group
     * handler), start it, and swap it into {@link #webSocket}.
     * {@code SimpleXWebSocketClient} is terminal after {@code close()}
     * — its {@code closed} flag never resets — so reviving the
     * transport always means a fresh instance; this method is the one
     * construction site shared by {@link #start()} and the post-restart
     * reconnect path.
     *
     * <p>The group handler funnels its deliveries through onInbound so
     * both DM and group paths share the volatile-field read of
     * {@code inboundHandler} and the misbehaving-handler protection in
     * one place. A Provider that calls setInboundHandler after
     * start() still gets group messages routed correctly because
     * onInbound re-reads the field on each dispatch.</p>
     */
    void rebuildWebSocket() throws MessagingException {
        SimpleXConfig cfg = requireWired();
        HttpClient http = httpClient;
        SimpleXIdentity identity = botIdentity;
        if (http == null || identity == null) {
            throw new IllegalStateException(
                    "SimpleXAdapter not fully wired (httpClient/botIdentity are null)");
        }
        URI uri = URI.create("ws://127.0.0.1:" + cfg.wsPort());
        SimpleXGroupHandler groupHandler = new SimpleXGroupHandler(identity, this::onInbound);
        SimpleXWebSocketClient ws = new SimpleXWebSocketClient(
                uri, http, this::onInbound, groupHandler::onGroupCandidate);
        ws.start();
        this.webSocket = ws;
    }

    /**
     * Restart notification entry point. Fires on the supervisor virtual
     * thread — the rebuild blocks on the WebSocket-ready probe (up to
     * {@link #WS_READY_TIMEOUT}), so it must hop to its own thread or
     * the supervisor's {@code waitFor} crash detection is delayed.
     */
    private void onSubprocessRestart() {
        Thread.ofVirtual()
                .name("simplex-adapter-reconnect")
                .start(this::reconnect);
    }

    private void reconnect() {
        if (!reconnectInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            SimpleXConfig cfg = config;
            SimpleXWebSocketClient old = webSocket;
            if (cfg == null || old == null) {
                // close() ran, or start() never finished wiring the client —
                // nothing to revive.
                return;
            }
            // Teardown-before-serve: the old client's listener is closed
            // before the fresh client exists, so a half-dead prior
            // connection can never interleave with (or double-deliver
            // into) the new one. The flag is set FIRST so sends in the
            // gap classify TRANSIENT instead of hitting the closed
            // client's PERMANENT.
            reconnecting = true;
            old.close();
            waitForWebSocketReady(cfg.wsPort());
            rebuildWebSocket();
            if (subprocess == null) {
                // close() won the race while we were rebuilding — do not
                // resurrect the transport after teardown.
                SimpleXWebSocketClient fresh = webSocket;
                if (fresh != null) {
                    fresh.close();
                    webSocket = null;
                }
                return;
            }
            reconnecting = false;
            LOG.info("SimpleX adapter reconnected after subprocess restart");
        } catch (MessagingException e) {
            // `reconnecting` stays set: the outage continues, sends stay
            // TRANSIENT, and the next restart notification (or the
            // supervisor's FAILED transition) resolves it.
            LOG.warn("SimpleX reconnect failed ({}); awaiting next supervised restart",
                    e.category());
        } finally {
            reconnectInFlight.set(false);
        }
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
        // A closed adapter is not "reconnecting" — post-close sends must
        // resolve to the null→PERMANENT branch, not a stale TRANSIENT.
        reconnecting = false;
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

    /**
     * True once the supervised simplex-chat subprocess has exhausted its
     * crash cap and latched {@link SimpleXSubprocess.State#FAILED} — the
     * readiness-truth signal Provider polls. A null subprocess (never
     * started, or torn down) is not a terminal failure.
     */
    @Override
    public boolean supervisorTerminallyFailed() {
        SimpleXSubprocess sub = subprocess;
        return sub != null && sub.state() == SimpleXSubprocess.State.FAILED;
    }

    /**
     * Inbound deliveries dropped on dispatch-queue overflow, read through
     * the live WebSocket client. Zero before the transport is wired.
     */
    @Override
    public long droppedInboundCount() {
        SimpleXWebSocketClient ws = webSocket;
        return ws == null ? 0L : ws.droppedInboundCount();
    }

    @Override
    public MessageHandle send(OutboundMessage msg) throws MessagingException {
        SimpleXWebSocketClient ws = requireConnected();
        // Over-cap texts are split into ordered chunks (design §6.3.4) —
        // before chunking, a digest past the 4 000-byte SimpleX text cap
        // failed PERMANENT and the recipient received nothing. sendCommand
        // blocks on each chunk's ack, so chunk N+1 transmits only after
        // chunk N is accepted: transport-order delivery with no extra
        // sequencing. Each chunk draws its own rate-limiter token. A
        // chunked send is not atomic — a mid-sequence failure can deliver
        // a prefix; the Provider retry then re-sends from the first chunk,
        // which the §6.3.5 duplicate tolerance accepts.
        List<String> chunks = SimpleXOutboundChunker.chunk(msg.text());
        String chatItemId = transmitChunk(ws, msg.scope(), chunks.getFirst());
        for (int i = 1; i < chunks.size(); i++) {
            chatItemId = transmitChunk(ws, msg.scope(), chunks.get(i));
        }
        // The handle tracks the LAST chunk, so a later update() /
        // finalizeMessage() edits the message closest to the reader's
        // view; the single-chunk case is unchanged.
        String opaque = "simplex-" + handleCounter.incrementAndGet();
        TrackedHandle tracked = new TrackedHandle(
                new SimpleXMessageHandle(chatItemId, msg.scope(), msg.correlationId()));
        synchronized (handles) {
            handles.put(opaque, tracked);
        }
        return new MessageHandle(opaque);
    }

    /** Encode, pace, and transmit one chunk; returns the ack's chat-item id. */
    private String transmitChunk(SimpleXWebSocketClient ws, ScopeRef scope, String text)
            throws MessagingException {
        String corrId = nextCorrId();
        String envelope = SimpleXMessageCodec.encodeSendCommand(corrId, scope, text);
        outboundRate.acquire();
        return ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
    }

    @Override
    public void update(MessageHandle handle, String body) throws MessagingException {
        TrackedHandle tracked = requireKnownAndOpen(handle);
        SimpleXWebSocketClient ws = requireConnected();
        if (hasFallenBack(tracked)) {
            // A prior edit on this handle was unrecoverable; never edit
            // again (design §6.4.5: "subsequent update calls continue to
            // fall back") — go straight to a fresh send.
            fallbackSend(ws, tracked.handle, body);
            return;
        }
        try {
            String corrId = nextCorrId();
            String envelope = SimpleXMessageCodec.encodeUpdateCommand(
                    corrId, tracked.handle.chatItemId(), tracked.handle.scope(), body);
            // update returns the (possibly changed) chatItemId — the SimpleX
            // surface re-acks edits with the same id. We don't need the return
            // value here, only the success/failure outcome.
            outboundRate.acquire();
            ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
        } catch (MessagingException e) {
            // TRANSIENT (reconnect gap, network reset) must propagate so the
            // Provider retries the same edit; only an unrecoverable PERMANENT
            // edit triggers the fresh-send fallback.
            if (e.category() != FailureCategory.PERMANENT) {
                throw e;
            }
            markFellBack(handle.opaqueValue());
            fallbackSend(ws, tracked.handle, body);
        }
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) throws MessagingException {
        TrackedHandle tracked = requireKnownAndOpen(handle);
        SimpleXWebSocketClient ws = requireConnected();
        if (hasFallenBack(tracked)) {
            fallbackSend(ws, tracked.handle, body);
            markFinalized(handle.opaqueValue());
            return;
        }
        try {
            String corrId = nextCorrId();
            String envelope = SimpleXMessageCodec.encodeFinalizeCommand(
                    corrId, tracked.handle.chatItemId(), tracked.handle.scope(), body);
            outboundRate.acquire();
            ws.sendCommand(corrId, envelope, ACK_TIMEOUT);
        } catch (MessagingException e) {
            if (e.category() != FailureCategory.PERMANENT) {
                throw e;
            }
            // The terminal edit is unrecoverable (over-cap encode rejection,
            // or a deleted/too-old item): fall back to a fresh send so the
            // final body still reaches the reader instead of freezing the
            // placeholder. finalize clears the fallback path (design §6.4.5)
            // by finalizing the handle below.
            fallbackSend(ws, tracked.handle, body);
        }
        // SPI contract: after finalizeMessage, any update() on the same
        // handle MUST throw PERMANENT. The flag is checked above in
        // requireKnownAndOpen — set it here on success (edit or fallback).
        markFinalized(handle.opaqueValue());
    }

    /**
     * Fresh-send fallback for an unrecoverable edit (design §6.3.8: "the
     * adapter MUST fall back to sending a NEW message via {@code send}, with
     * {@code correlationId} matching the original"; §6.4.5 restates it for
     * SimpleX). The body routes through the same {@link SimpleXOutboundChunker}
     * {@link #send} uses, so an over-cap final body — the placeholder-freeze
     * loss path the encoder's {@code requireWithinCap} would otherwise reject
     * a second time — is split to fit the SimpleX text cap. {@code internal}
     * carries the original {@code correlationId}, so the fresh send stays tied
     * to the originating outbound; the new chat-item id is discarded because a
     * fallen-back handle never edits again.
     */
    private void fallbackSend(SimpleXWebSocketClient ws, SimpleXMessageHandle internal, String body)
            throws MessagingException {
        for (String chunk : SimpleXOutboundChunker.chunk(body)) {
            transmitChunk(ws, internal.scope(), chunk);
        }
    }

    @Override
    public void setTyping(ScopeRef scope, boolean typing) {
        // No-op per the SPI contract: capabilities.supportsTypingIndicator
        // is false (SimpleX has no first-class typing indicator, design
        // §6.4.2), and MessagingAdapter#setTyping declares "No-op for
        // adapters with CapabilityFlags#supportsTypingIndicator false".
        // No transport command may be issued here.
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

    private TrackedHandle requireKnownAndOpen(MessageHandle handle) throws MessagingException {
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
        return tracked;
    }

    private boolean hasFallenBack(TrackedHandle tracked) {
        synchronized (handles) {
            return tracked.fellBack;
        }
    }

    private void markFellBack(String opaqueValue) {
        synchronized (handles) {
            TrackedHandle tracked = handles.get(opaqueValue);
            if (tracked != null) {
                tracked.fellBack = true;
            }
        }
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
        // Checked BEFORE the null→PERMANENT branch: a send during the
        // restart→rebuild gap is a recoverable outage the Provider's retry
        // machinery should ride out, while an un-started adapter stays
        // PERMANENT (the cross-adapter contract pinned by
        // AdapterCapabilityContractTest).
        if (reconnecting) {
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "SimpleX transport is reconnecting after a subprocess restart");
        }
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
        /**
         * Guarded by the {@code handles} monitor. Set once an unrecoverable
         * edit has switched this handle to fresh-send fallback (design
         * §6.3.8 / §6.4.5); every subsequent update/finalize then fresh-sends
         * without re-attempting the doomed in-place edit.
         */
        boolean fellBack;

        TrackedHandle(SimpleXMessageHandle handle) {
            this.handle = handle;
        }
    }
}
