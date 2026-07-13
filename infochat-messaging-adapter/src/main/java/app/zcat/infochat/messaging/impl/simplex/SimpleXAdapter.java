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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
 * <p>The transport carries <b>no authentication</b>: the WebSocket bot API
 * is a loopback channel to the co-located {@link SimpleXSubprocess}, and the
 * bot identity lives in the subprocess data-dir, not in any session token or
 * cookie. The adapter therefore models no session-auth state at all for the
 * v1 loopback-IPC transport — there is nothing to authenticate against a
 * process the adapter itself spawned (design §6.4.6).</p>
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
    // Group-invitation handler (M1-515): late-bound by Provider via
    // setGroupInvitationHandler, the same shape as inboundHandler. Null until
    // Provider attaches; invitations arriving earlier are dropped in
    // onGroupInvitation. Volatile: written by the registration thread, read on
    // the WS inbound-dispatch thread.
    private volatile @Nullable InvitationHandler invitationHandler;
    private volatile @Nullable SimpleXSubprocess subprocess;
    private volatile @Nullable SimpleXWebSocketClient webSocket;
    // Group-candidate dispatch handler, (re)built by buildGroupHandler() at
    // start() and after each supervised restart. Group @-mention recognition
    // uses the per-group memberId carried in each frame (D51), not this
    // handler's construction-time state; the handler is still gated on the build
    // so its lifecycle matches a successful (re)connect. Null until the first
    // build; group candidates arriving earlier are dropped in onGroupCandidate.
    // Volatile: written by the start()/reconnect threads, read on the WS
    // inbound-dispatch thread.
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
     * subprocess supervisor calls at the FAILED transition. Group @-mention
     * recognition (see {@link SimpleXGroupHandler}) is anchored to the
     * per-group memberId carried in each frame (D51), so the bot needs no
     * construction-time identity and none is derived at start.
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
     * Canonicalize an operator-supplied admin value to the bare queue
     * address inbound messages byte-match: when it is a SimpleX contact
     * link, extract the bare queue id by reusing the bot-identity
     * extractor {@link SimpleXMessageCodec#extractQueueAddressId} (the
     * single source of extraction truth, §Context drift). An
     * already-bare value, or a link with no extractable queue id, yields
     * {@code null} from the extractor and is returned unchanged so the
     * {@link #isWellFormedContactId} gate makes the accept/reject
     * decision (M1-465).
     */
    @Override
    public String canonicalizeContactId(String contactId) {
        String extracted = SimpleXMessageCodec.extractQueueAddressId(contactId);
        return extracted == null ? contactId : extracted;
    }

    /**
     * Start the simplex-chat subprocess, wait for its WebSocket endpoint
     * to become reachable, open the WebSocket, then build the
     * group-candidate dispatch handler ({@link #buildGroupHandler}).
     * Throws {@link MessagingException} (categorised) on launch / readiness
     * / connect failure; the failure fails THIS adapter only
     * (MessagingStartup's §6.7 per-adapter catch absorbs it).
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
        // M1-430: arm the off-loopback bind guard before start() so the
        // supervisor thread sees the registration. After the child reaches
        // RUNNING the supervisor waits for the chat-server port to bind, then
        // probes whether it is reachable off loopback; a true result fails the
        // subprocess (FAILED + admin notify), which the post-readiness check
        // below turns into a start() failure so the adapter never serves the
        // exposed credential-free WebSocket (trust boundary #7).
        sub.onStartupBindCheck(() -> awaitBindThenProbe(cfg.wsPort()));
        sub.start();
        attachSubprocess(sub);
        try {
            waitForWebSocketReady(cfg.wsPort());
            rebuildWebSocket();
            buildGroupHandler();
            if (sub.state() == SimpleXSubprocess.State.FAILED) {
                // The concurrent bind guard latched FAILED: the chat-server port
                // is exposed off loopback. Refuse the start; the catch tears the
                // just-built WebSocket down and stops the (already killed)
                // subprocess. Checked after the WS round-trips, by which point
                // the guard's single non-loopback connect has long completed,
                // so a healthy loopback-only start reliably reads RUNNING here.
                throw new MessagingException(FailureCategory.PERMANENT,
                        "simplex-chat chat-server port is exposed on a"
                                + " non-loopback interface; refusing to serve");
            }
        } catch (MessagingException e) {
            // A failed start() must tear BOTH halves down or the subprocess and
            // the connected WebSocket client leak past the failure: the client
            // is built (rebuildWebSocket) before the readiness/FAILED checks
            // that can still abort the start.
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
     * re-reads the volatile {@link #groupHandler} on each dispatch — the
     * handler is built only AFTER the WebSocket is up (see
     * {@link #buildGroupHandler}), so a candidate arriving before the build
     * is dropped. The handler itself funnels deliveries through onInbound so
     * both DM and group paths share the volatile-field read of
     * {@code inboundHandler} and the misbehaving-handler protection in one
     * place.</p>
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
                uri, http, this::onInbound, this::onGroupCandidate, this::onGroupInvitation);
        // Bind the current metrics before start() so a drop on the very first
        // frame is counted; bindMetrics() below re-binds a live ws if metrics
        // arrive after the transport is already up.
        ws.bindMetrics(metrics);
        ws.start();
        this.webSocket = ws;
    }

    /**
     * Build the group-candidate dispatch handler and publish it to the
     * volatile {@link #groupHandler} field. Composed by {@link #start()} and
     * the reconnect path strictly AFTER {@code rebuildWebSocket} so the
     * handler's lifecycle matches a successful (re)connect — group candidates
     * arriving before it is built are dropped (see {@link #onGroupCandidate}).
     * The handler is stateless w.r.t. identity: group @-mention recognition
     * reads the per-group memberId from each frame (D51), so the handler needs
     * no derived bot address — it only funnels recognised mentions through
     * {@link #onInbound}.
     *
     * <p>Package-private seam: FakeSimpleXProcess-driven tests drive the
     * handler lifecycle without {@code start()}'s real-binary requirement.</p>
     */
    void buildGroupHandler() {
        this.groupHandler = new SimpleXGroupHandler(this::onInbound);
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
            // Rebuild the group-candidate handler against the restarted
            // transport so post-restart group routing resumes — the handler is
            // stateless w.r.t. identity (D51 memberId recognition reads the
            // anchor per-frame), so this is a plain rebuild, not a re-derivation.
            buildGroupHandler();
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
     * order without its exception classification. Also consults the
     * client's own {@link SimpleXWebSocketClient#isClosed()} so a
     * peer-closed socket reads as disconnected before the supervisor
     * notices and flips {@code reconnecting}.
     */
    @Override
    public boolean connected() {
        SimpleXWebSocketClient ws = webSocket;
        return !closedForGood && !reconnecting && ws != null && !ws.isClosed();
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
        SimpleXWebSocketClient ws = webSocket;
        if (ws != null) {
            // Registration may bind metrics after the transport is already up
            // (reconnect path); propagate so the live client's drop counters
            // are not stranded on the noop registry.
            ws.bindMetrics(metrics);
        }
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

    @Override
    public void setGroupInvitationHandler(InvitationHandler handler) {
        this.invitationHandler = handler;
    }

    @Override
    public void joinGroup(String adapterGroupId) throws MessagingException {
        SimpleXWebSocketClient ws = requireConnected();
        String corrId = nextCorrId();
        // /_join is a one-off control command (no chat-item handle, not a paced
        // user message), so it is fire-and-forget and draws no rate token. The
        // membership transition invited→connected lands later as an async event
        // (M1-515).
        ws.sendNoAck(corrId, SimpleXMessageCodec.encodeJoinGroupCommand(corrId, adapterGroupId));
    }

    @Override
    public Optional<String> connectContact() throws MessagingException {
        SimpleXWebSocketClient ws = requireConnected();
        String corrId = nextCorrId();
        // One-off control command (not a paced user message) — same
        // no-rate-token posture as joinGroup. The ack payload IS the current
        // contact link, so the value is live at command time, never a
        // boot-time snapshot; it flows only into this return value (D37 —
        // never logged, never persisted).
        String link = ws.sendCommand(corrId,
                SimpleXMessageCodec.encodeShowAddressCommand(corrId), ACK_TIMEOUT);
        return Optional.of(link);
    }

    // -- internals -----------------------------------------------------------

    /**
     * Group-candidate entry point handed to the WebSocket client. Re-reads
     * the volatile {@code groupHandler} on every dispatch (mirroring
     * {@link #onInbound}'s volatile re-read of {@code inboundHandler}) so a
     * handler rebuilt by a reconnect is picked up immediately. A candidate
     * arriving in the window between the WebSocket coming up and the group
     * handler being built is dropped — lifecycle state, the same shape as
     * onInbound's no-handler drop. The recognised-but-unmentioned drop inside
     * {@link SimpleXGroupHandler} stays log-free by design; this DEBUG line
     * covers only the not-yet-built window.
     */
    private void onGroupCandidate(SimpleXMessageCodec.GroupCandidate gc) {
        SimpleXGroupHandler handler = groupHandler;
        if (handler == null) {
            LOG.debug("dropping group candidate; group handler not yet built");
            return;
        }
        handler.onGroupCandidate(gc);
    }

    /**
     * Group-invitation entry point handed to the WebSocket client (M1-515).
     * Re-reads the volatile {@code invitationHandler} on every dispatch
     * (mirroring {@link #onInbound}) so a handler attached after the transport
     * came up is picked up immediately; an invitation arriving before Provider
     * registers is dropped (lifecycle, the same shape as onInbound's no-handler
     * drop). The codec's
     * {@link SimpleXMessageCodec.ReceivedGroupInvitation} is surfaced to
     * Provider as the adapter-agnostic {@link GroupInvitation}.
     */
    private void onGroupInvitation(SimpleXMessageCodec.ReceivedGroupInvitation invitation) {
        InvitationHandler current = invitationHandler;
        if (current == null) {
            LOG.debug("dropping group invitation; no handler registered");
            return;
        }
        try {
            current.onInvitation(new GroupInvitation(
                    invitation.adapterGroupId(), invitation.inviterContactId()));
        } catch (RuntimeException e) {
            // Provider-side handler; a misbehaving handler must not tear the WS
            // inbound-dispatch thread down. The invitation carries no
            // user-authored prose, but a wrapped SQLException message could, so
            // the message stays suppressed (D37) — the class + stack localizes a
            // handler bug without leaking content, the same shape as onInbound.
            LOG.warn("group-invitation handler threw, suppressed per D37; class + stack:\n{}",
                    stackWithoutMessage(e));
        }
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
            // not tear the WebSocket listener thread down. D37: the exception
            // MESSAGE may carry inbound chat-mode body bytes, so it stays
            // suppressed — but the class + stack (class/method/file/line carry
            // no user content) are logged so a Provider handler bug is
            // localizable from the log (M1-358).
            LOG.warn("inbound handler threw, message suppressed per D37; class + stack:\n{}",
                    stackWithoutMessage(e));
        }
    }

    /**
     * Cause-chain render depth cap, mirroring the spec's SafeLog bound
     * (security.md §"User content in exceptions", depth 5): stops an
     * acyclic-but-deep chain from bloating a single log line (M1-542
     * redteam finding).
     */
    private static final int MAX_CAUSE_CHAIN_DEPTH = 5;

    /**
     * Render a throwable's class name and stack frames (class/method/file/
     * line) WITHOUT its message — {@link Throwable#getMessage()} /
     * {@code toString()} may carry inbound chat-mode body bytes (D37), but
     * {@link StackTraceElement} and class names never do. Lets an inbound
     * handler bug be localized from the log without leaking user content
     * (M1-358). The cause chain is walked and rendered the same way
     * ("Caused by:" per level), so a wrapped failure whose real reason is a
     * cause — not the top throwable — is diagnosable (M1-542 / live finding
     * F-live-2); cause class names + frames are equally content-free, and
     * every level's message stays suppressed. The walk is bounded to
     * {@link #MAX_CAUSE_CHAIN_DEPTH} levels (with a truncation marker) so a
     * deep acyclic chain cannot bloat the log line. Package-private: the D37
     * "stack yes, message no" property is pinned directly in a unit test.
     */
    static String stackWithoutMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        // Identity-tracked so a self-referential or cyclic cause chain
        // terminates instead of looping forever, exactly as
        // Throwable.printStackTrace guards its own cause walk; the depth cap
        // additionally bounds an acyclic-but-deep chain (M1-542).
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = t;
        int depth = 0;
        while (current != null && seen.add(current)) {
            if (depth == MAX_CAUSE_CHAIN_DEPTH) {
                sb.append("\n... (cause chain truncated at depth ")
                        .append(MAX_CAUSE_CHAIN_DEPTH).append(')');
                break;
            }
            if (current != t) {
                sb.append("\nCaused by: ");
            }
            sb.append(current.getClass().getName());
            for (StackTraceElement frame : current.getStackTrace()) {
                sb.append("\n\tat ").append(frame);
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    private TrackedHandle requireKnownAndOpen(MessageHandle handle) throws MessagingException {
        // One lock acquisition across the lookup and the finalized check: the
        // two are read atomically so a concurrent finalize cannot land between
        // them, and the single critical section replaces the prior pair.
        synchronized (handles) {
            TrackedHandle tracked = handles.get(handle.opaqueValue());
            if (tracked == null) {
                throw new MessagingException(FailureCategory.PERMANENT,
                        "unknown handle: " + handle.opaqueValue());
            }
            if (tracked.finalized) {
                throw new MessagingException(FailureCategory.PERMANENT,
                        "handle already finalized: " + handle.opaqueValue());
            }
            return tracked;
        }
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
    /**
     * Off-loopback bind check run on the {@link SimpleXSubprocess} supervisor
     * thread after the child reaches RUNNING (M1-430). The bind interface is
     * only observable once the port is bound, so this first waits for readiness
     * (reusing {@link #waitForWebSocketReady}); a port that never binds is
     * reported not-exposed — the adapter's own readiness wait then fails the
     * start. Once bound, a single-shot {@link SimpleXLoopbackProbe} reports
     * whether the port is reachable on a non-loopback interface.
     */
    private boolean awaitBindThenProbe(int port) {
        try {
            waitForWebSocketReady(port);
        } catch (MessagingException e) {
            return false;
        }
        return SimpleXLoopbackProbe.isExposedOffLoopback(port);
    }

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
