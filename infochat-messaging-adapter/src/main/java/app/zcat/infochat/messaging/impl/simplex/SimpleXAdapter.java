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
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

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

    // maxInboundMessageBytes is single-sourced from the codec's enforcement
    // constant so the capability and the decode-time cap cannot drift; v1
    // ships the fixed 16 KiB value per docs/design/06-messaging.md §6.2.2.
    // maxSendsPerSecond and minEditInterval take design §6.4.2's conservative
    // values (5/s, 600 ms floor), to be raised only after observation.
    private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
            /* supportsMentionByContactId */ true,
            /* supportsMembershipEvents   */ false,
            /* supportsCodeFormatting     */ false,
            /* supportsMarkdownLinks      */ false,
            /* maxInboundMessageBytes     */ SimpleXMessageCodec.MAX_INBOUND_TEXT_BYTES,
            /* maxSendsPerSecond          */ 5,
            /* supportsMessageEdit        */ true,
            /* supportsTypingIndicator    */ false,
            /* minEditInterval            */ Duration.ofMillis(600));

    static final Duration WS_READY_TIMEOUT = Duration.ofSeconds(10);
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    private final @Nullable SimpleXConfig config;
    private final @Nullable HttpClient httpClient;
    private final @Nullable Consumer<String> adminNotifier;

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

    // Late-bound by AdapterMetrics.bindAdapter at registration (the same
    // shape as setInboundHandler). The noop() initializer keeps unbound
    // instances — plain-constructed tests, a never-registered adapter —
    // emitting into a throwaway registry instead of null-checking at
    // every emission site.
    private volatile AdapterMetrics metrics = AdapterMetrics.noop();
    private volatile @Nullable InboundHandler inboundHandler;
    private volatile @Nullable SimpleXSubprocess subprocess;
    private volatile @Nullable SimpleXWebSocketClient webSocket;
    // Group-mention dispatch handler bound to the SimpleXIdentity derived
    // from the running simplex-chat at start() and re-derived after each
    // supervised restart (the D10 trust anchor — never operator-typed).
    // Null until the first successful derivation; group candidates arriving
    // earlier are dropped in onGroupCandidate. Volatile: written by the
    // start()/reconnect threads, read on the WS inbound-dispatch thread.
    private volatile @Nullable SimpleXGroupHandler groupHandler;
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
    // Terminal latch: set by close()/stop(), never reset. Checked BEFORE
    // `reconnecting` in requireConnected so a closed adapter classifies every
    // subsequent send PERMANENT — even when an in-flight reconnect() set the
    // reconnecting flag and then lost the teardown race, or is still parked in
    // its ready-probe. `reconnecting` is owned by reconnect() (which clears it
    // on every exit path); close() latches this guard instead of racing that
    // flag.
    private volatile boolean closedForGood;

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
    }

    /**
     * Full constructor used by Provider-side wiring (M1-105). Supplies
     * the operator config, the JDK {@link HttpClient} used to dial the
     * simplex-chat WebSocket, and the admin-notification consumer the
     * subprocess supervisor calls at the FAILED transition. The bot's
     * per-adapter SimpleX identity — the D10 trust anchor for group
     * mention recognition (see {@link SimpleXGroupHandler}) — is NOT a
     * construction input: {@link #start()} derives it by querying the
     * running simplex-chat for the bot's own address, so it cannot be
     * mistyped and an admin-key rotation cannot move it.
     */
    public SimpleXAdapter(SimpleXConfig config,
                          HttpClient httpClient,
                          Consumer<String> adminNotifier) {
        this.config = config;
        this.httpClient = httpClient;
        this.adminNotifier = adminNotifier;
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
     * to become reachable, open the WebSocket, then derive the bot's own
     * queue address — the D10 trust anchor — by querying the running
     * simplex-chat (never from operator config). Acceptance items 5 + 6
     * of M1-103. Throws {@link MessagingException} (categorised) on
     * launch / readiness / connect / query failure, or
     * {@link IllegalStateException} when the derived address fails the
     * well-formedness gate; either way the failure fails THIS adapter
     * only (MessagingStartup's §6.7 per-adapter catch absorbs both).
     */
    @Override
    public void start() throws MessagingException {
        SimpleXConfig cfg = requireWired();
        HttpClient http = httpClient;
        Consumer<String> notify = adminNotifier;
        if (http == null || notify == null) {
            throw new IllegalStateException(
                    "SimpleXAdapter not fully wired (httpClient/adminNotifier are null)");
        }
        // System-boundary validation: filesystem/port checks the operator
        // promised at config time. Runs only for activated adapters so an
        // inmemory-only deployment never trips simplex's checks. Without
        // this call a mis-typed binary path or out-of-range ws-port
        // surfaces deep inside subprocess launch as an opaque exception
        // that MessagingStartup's §6.7 per-adapter catch silently absorbs.
        cfg.validate();
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
            deriveAndAdoptIdentity();
        } catch (MessagingException | IllegalStateException e) {
            // The identity derivation runs after the WS client is up, so a
            // failed start() must tear BOTH halves down or the subprocess
            // and the connected client leak past the failure. The catch
            // covers the derivation step's two failure shapes — query
            // transport/extraction (MessagingException) and adoption of a
            // non-well-formed address (IllegalStateException) — plus the
            // pre-existing readiness/connect failures.
            SimpleXWebSocketClient ws = webSocket;
            if (ws != null) {
                ws.close();
                this.webSocket = null;
            }
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
     * Build a fresh {@link SimpleXWebSocketClient}, start it, and swap it
     * into {@link #webSocket}. {@code SimpleXWebSocketClient} is terminal
     * after {@code close()} — its {@code closed} flag never resets — so
     * reviving the transport always means a fresh instance; this method
     * is the one construction site shared by {@link #start()} and the
     * post-restart reconnect path.
     *
     * <p>Group candidates route through {@link #onGroupCandidate}, which
     * re-reads the volatile anchor-bound {@link #groupHandler} on each
     * dispatch — the identity is derived only AFTER the WebSocket is up
     * (see {@link #deriveAndAdoptIdentity}), so the client cannot capture
     * it at construction time. The handler itself funnels deliveries
     * through onInbound so both DM and group paths share the
     * volatile-field read of {@code inboundHandler} and the
     * misbehaving-handler protection in one place.</p>
     */
    void rebuildWebSocket() throws MessagingException {
        SimpleXConfig cfg = requireWired();
        HttpClient http = httpClient;
        if (http == null) {
            throw new IllegalStateException(
                    "SimpleXAdapter not fully wired (httpClient is null)");
        }
        URI uri = URI.create("ws://127.0.0.1:" + cfg.wsPort());
        SimpleXWebSocketClient ws = new SimpleXWebSocketClient(
                uri, http, this::onInbound, this::onGroupCandidate);
        ws.start();
        this.webSocket = ws;
    }

    /**
     * Derive the bot's own queue address by querying the running
     * simplex-chat over the just-(re)built WebSocket and adopt it as the
     * D10 anchor. Composed by {@link #start()} and the reconnect path
     * strictly AFTER {@code waitForWebSocketReady}/{@code rebuildWebSocket}
     * — a query issued before the WebSocket is up would surface a spurious
     * TRANSIENT failure. Package-private so FakeSimpleXProcess-driven
     * tests can exercise the production derivation path without
     * {@code start()}'s real-binary requirement.
     */
    void deriveAndAdoptIdentity() throws MessagingException {
        SimpleXWebSocketClient ws = webSocket;
        if (ws == null) {
            // close() raced the (re)build and tore the client down — the
            // anchor cannot be derived and the adapter is going away.
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket torn down before the bot identity could be derived");
        }
        String corrId = nextCorrId();
        String envelope = SimpleXMessageCodec.encodeShowMyAddressCommand(corrId);
        adoptBotQueueAddress(ws.sendCommand(corrId, envelope, ACK_TIMEOUT));
    }

    /**
     * Adopt a queue address derived from the running simplex-chat as the
     * bot's D10 trust anchor: validate well-formedness (the same
     * {@link SimpleXIdentity#isWellFormed} gate the registry applies to
     * the bootstrap admin id — a malformed identity would let
     * {@code SimpleXMentionParser.botMentioned} match a mention-list entry
     * that decodes to empty/forged bytes, the class security.md §"What's
     * intentionally NOT in v1" forever excludes), then bind a fresh
     * {@link SimpleXGroupHandler} to it. No canonicalization, unlike
     * Signal's {@code adoptBotAci} — queue addresses are case-sensitive
     * URL-safe base64. The failure message names the derivation source,
     * never the value (D37: queue addresses are never logged raw).
     *
     * <p>Package-private seam, mirroring {@code SignalAdapter.adoptBotAci}:
     * FakeSimpleXProcess-driven tests that need a routable anchor without
     * a full derivation round-trip adopt one directly.</p>
     */
    void adoptBotQueueAddress(String queueAddress) {
        if (!SimpleXIdentity.isWellFormed(queueAddress)) {
            throw new IllegalStateException(
                    "queue address derived from simplex-chat's show-address query is not"
                            + " a well-formed SimpleX queue address — the running"
                            + " simplex-chat may not match the modeled wire contract"
                            + " (length=" + queueAddress.length() + ")");
        }
        SimpleXIdentity identity = new SimpleXIdentity(queueAddress);
        this.groupHandler = new SimpleXGroupHandler(identity, this::onInbound);
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
            // Re-establish the D10 anchor against the restarted process so
            // post-restart group routing compares against a consistent
            // anchor — the restarted simplex-chat is the same source the
            // original derivation read, but it is re-queried rather than
            // assumed unchanged.
            deriveAndAdoptIdentity();
            if (subprocess == null) {
                // close() won the race while we were rebuilding — do not
                // resurrect the transport after teardown. Clear reconnecting
                // so the flag never outlives the reconnect: leaving it set
                // here is what left a closed adapter classifying post-close
                // sends TRANSIENT forever. (closedForGood is the authoritative
                // terminal guard, but reconnect() still owns the full
                // lifecycle of its own flag.)
                SimpleXWebSocketClient fresh = webSocket;
                if (fresh != null) {
                    fresh.close();
                    webSocket = null;
                }
                reconnecting = false;
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
        } catch (IllegalStateException e) {
            // Re-derivation adopted nothing: the restarted simplex-chat
            // answered with a non-well-formed address. Same posture as the
            // transport failure above — the outage continues until the next
            // supervised restart. The prior anchor stays in place for any
            // frames the dying connection already dispatched.
            LOG.warn("SimpleX reconnect failed (identity re-derivation);"
                    + " awaiting next supervised restart");
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
        // Latch the terminal guard FIRST so a concurrent send observes the
        // closed state before the transport fields are torn down. This is the
        // authoritative "post-close sends are PERMANENT" signal; close() does
        // not touch `reconnecting` (that flag is reconnect()'s to manage, and
        // clearing it here only raced an in-flight reconnect).
        closedForGood = true;
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

    /**
     * Live transport state for the {@code adapter.connection.status}
     * gauge: a wired WebSocket client that is neither mid-reconnect nor
     * terminally closed. Mirrors {@link #requireConnected()}'s guard
     * order without its exception classification.
     */
    @Override
    public boolean connected() {
        return !closedForGood && !reconnecting && webSocket != null;
    }

    /** Dispatch-queue depth, read through the live WebSocket client. */
    @Override
    public int inboundQueueDepth() {
        SimpleXWebSocketClient ws = webSocket;
        return ws == null ? 0 : ws.dispatchQueueDepth();
    }

    @Override
    public void bindMetrics(AdapterMetrics metrics) {
        this.metrics = metrics;
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
            recordFallbackSend(tracked);
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
            recordUpdateFail();
            fallbackSend(ws, tracked.handle, body);
            recordFallbackSend(tracked);
        }
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) throws MessagingException {
        TrackedHandle tracked = requireKnownAndOpen(handle);
        SimpleXWebSocketClient ws = requireConnected();
        if (hasFallenBack(tracked)) {
            fallbackSend(ws, tracked.handle, body);
            recordFallbackSend(tracked);
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
            recordUpdateFail();
            fallbackSend(ws, tracked.handle, body);
            recordFallbackSend(tracked);
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

    /**
     * §6.12 counter for every update/finalize call that resolved as a
     * fresh-send fallback — both the failing edit itself and the
     * subsequent calls a fallen-back handle short-circuits.
     */
    private void recordFallbackSend(TrackedHandle tracked) {
        metrics.updateOutcome(name(), tracked.handle.scope(),
                AdapterMetrics.UpdateOutcome.FALLBACK_SEND);
    }

    /**
     * §6.12 per-reason counter, incremented once at the failing edit
     * (not on the short-circuited repeats). Reason is {@code unknown}:
     * SimpleX's single {@code CEInvalidChatItemUpdate} rejection tag
     * covers "item too old, deleted, or not the bot's own message"
     * (§6.4.5) without discriminating, so any more specific label would
     * be fabricated.
     */
    private void recordUpdateFail() {
        metrics.updateFail(name(), AdapterMetrics.UpdateFailReason.UNKNOWN);
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

    /**
     * Group-candidate entry point handed to the WebSocket client. Re-reads
     * the volatile anchor-bound handler on every dispatch (mirroring
     * {@link #onInbound}'s volatile re-read of {@code inboundHandler}) so
     * routing always compares against the most recently derived identity.
     * A candidate arriving in the window between the WebSocket coming up
     * and the anchor being derived is dropped — lifecycle state, the same
     * shape as onInbound's no-handler drop. The recognised-but-unmentioned
     * drop inside {@link SimpleXGroupHandler} stays log-free by design;
     * this DEBUG line covers only the no-anchor window.
     */
    private void onGroupCandidate(SimpleXMessageCodec.GroupCandidate gc) {
        SimpleXGroupHandler handler = groupHandler;
        if (handler == null) {
            LOG.debug("dropping group candidate; bot identity not yet derived");
            return;
        }
        handler.onGroupCandidate(gc);
    }

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
        // A terminally-closed adapter is PERMANENT, checked BEFORE the
        // reconnecting branch: close() can race an in-flight reconnect() that
        // (re-)set the reconnecting flag, and a closed adapter must never
        // classify sends TRANSIENT — that would loop the Provider's retry
        // forever against a transport that will never come back.
        if (closedForGood) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "SimpleXAdapter is closed");
        }
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
