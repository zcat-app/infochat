package app.zcat.infochat.messaging.impl.signal;

import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.OutboundRateLimiter;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;

/**
 * JSON-RPC 2.0 client for signal-cli's TCP daemon endpoint. Owns the
 * wire connection, a single reader thread that demultiplexes
 * responses and inbound notifications, request-response correlation
 * via {@code rpcId}, the adapter-internal {@link SignalMessageHandle}
 * registry keyed by the SPI's opaque {@link MessageHandle} string,
 * and failure classification of send/update/finalize errors into
 * {@link FailureCategory#TRANSIENT} vs {@link FailureCategory#PERMANENT}
 * at the throw site per {@code docs/spec/messaging.md} §Failure
 * handling.
 *
 * <p>Reader-loop responsibility: read one line, decode via
 * {@link SignalMessageCodec}, and dispatch — Response /
 * ErrorResponse complete the per-{@code rpcId} pending future ON the
 * reader thread; Notification {@code method="receive"} is handed off
 * to a single dedicated dispatch thread, where it is translated to an
 * {@link InboundMessage} and delivered to the registered
 * {@link MessagingAdapter.InboundHandler}. The split is load-bearing:
 * a handler may block inside {@code onMessage} (e.g. send a reply and
 * await its ack), so handlers must never run on the reader thread —
 * the reader is the only thread that can complete the ack the handler
 * is waiting for. One dispatch thread (not a pool) preserves
 * per-connection FIFO delivery order by construction. Receive
 * notifications that
 * are not DM-scope ({@link SignalMessageCodec#extractDm} returning
 * empty) are routed raw to the registered group-notification handler
 * — the adapter's {@code SignalGroupHandler}, whose own decode
 * filters group-scope envelopes from the typing / receipt / sync
 * notifications that also land on this route.</p>
 *
 * <p>Failure-classification policy at the JSON-RPC error layer:
 * code {@code -32603} ({@code "Internal error"}) is treated as
 * {@link FailureCategory#TRANSIENT} — signal-cli's daemon raises
 * this for transient remote-signaling-server faults that resolve on
 * Provider's retry. All other JSON-RPC error codes ({@code -32700}
 * parse, {@code -32600} invalid request, {@code -32601} method-not-
 * found, {@code -32602} invalid params, and signal-cli's custom
 * codes for blocked-recipient / identity-revoked / policy-rejection)
 * map to {@link FailureCategory#PERMANENT} per the spec's
 * "default to PERMANENT when uncertain" forcing function — as does an
 * error response whose code is missing or non-numeric (the codec
 * surfaces it as code 0). Network failures (write {@link IOException},
 * response timeout) classify as {@link FailureCategory#TRANSIENT} so
 * Provider's retry policy gets a chance to recover the call; the
 * cross-adapter states (interrupted-awaiting-ack → TRANSIENT,
 * closed-before-ack → PERMANENT) follow the classification matrix in
 * {@link FailureCategory}.</p>
 */
class SignalJsonRpcClient {

    private static final Logger LOG = Logger.getLogger(SignalJsonRpcClient.class);

    private static final String OPAQUE_PREFIX = "signal-";

    /** §6.12 {@code adapter} label value — mirrors {@code SignalAdapter.name()}. */
    private static final String ADAPTER_NAME = "signal";

    /**
     * Coarse hard cap on the per-line UTF-16 character count read from
     * the daemon socket: a stream-layer guard against a peer that never
     * emits a newline, which would otherwise grow the line buffer until
     * OOM. Lines exceeding the cap are drained and dropped without being
     * passed to the JSON codec, bounding heap cost from a buggy /
     * compromised peer on the loopback daemon port.
     *
     * <p>This is a char-domain bound on the whole JSON-RPC envelope line,
     * NOT the {@code maxInboundMessageBytes} capability. That capability
     * is a UTF-8 byte budget on the decoded message body and is enforced
     * in {@link SignalMessageCodec#exceedsInboundByteCap} on both the DM
     * and group paths (mirroring SimpleX). The two are genuinely distinct
     * layers, so this line cap is sized strictly <em>above</em> the body
     * cap — never equal to it. If the two were the same value, the
     * envelope framing and JSON string-escaping a body always pays would
     * push the enclosing line over the shared cap before a body at (or
     * just under) the byte budget could reach {@code exceedsInboundByteCap},
     * silently dropping legal messages at the line layer (M1-486). Sizing
     * the line cap at 4× the body cap clears worst-case escaping (a body of
     * all {@code "}/{@code \\} doubles in the JSON string) plus envelope
     * overhead, so the body-cap layer — not this OOM guard — governs which
     * messages are dropped, while the raw line stays bounded at ~64 K chars.</p>
     */
    static final int MAX_INBOUND_LINE_CHARS = 4 * SignalMessageCodec.MAX_INBOUND_TEXT_BYTES;

    /**
     * Number of consecutive JSON-RPC response timeouts that classifies the
     * signal-cli daemon as hung — alive (so {@link Process#onExit()} never
     * fires) but not answering — and forces a subprocess restart via
     * {@link #hungRestartHook}. The request timeout is the only liveness
     * signal available for a deadlocked-but-alive child. Set to 3 to match
     * the "3 consecutive failures" escalation threshold the messaging design
     * uses for the adapter auth/network-failure counters
     * ({@code docs/design/06-messaging.md} §6.4.6); a single timeout is a
     * normal transient and must never trigger a restart.
     */
    private static final int HUNG_TIMEOUT_THRESHOLD = 3;

    /**
     * Default bound on the inbound-dispatch executor's work queue. A
     * hostile peer can deliver receive notifications faster than the single
     * dispatch thread drains them (each does identity-resolution DB work
     * downstream), so the JDK default unbounded LinkedBlockingQueue would
     * grow without bound and OOM the only user-facing service
     * ({@code docs/design/06-messaging.md} §6.3.7). At the cap the newest
     * notification is dropped — see {@link #dispatchAsync}. A named
     * constant, not runtime config, matching the sibling cap
     * {@link #MAX_TRACKED_HANDLES}; the capacity is a constructor parameter
     * so tests can drive the overflow path with a small queue.
     */
    static final int INBOUND_QUEUE_CAPACITY = 1_000;

    /**
     * Bound on the daemon-socket TCP connect, in milliseconds. Without it,
     * {@code new Socket(addr, port)} connects with the OS default (~75–120s
     * on Linux): a SYN that never draws a SYN-ACK — the daemon crashed in the
     * gap after {@code SignalAdapter.awaitEndpoint}'s probe, the host's accept
     * backlog saturated, or a partition opened — pins the calling thread for
     * over a minute. {@code SignalAdapter.start()} calls {@link #connect()}
     * synchronously, so that hang blocks Provider startup past its grace
     * window; the bound turns it into a fast {@link java.net.SocketTimeoutException}
     * the {@code connectClient} seam already classifies TRANSIENT, driving a
     * supervisor restart instead. Aligned to {@code SignalAdapter}'s own
     * per-attempt endpoint-probe connect timeout ({@code ENDPOINT_PROBE_INTERVAL*2
     * = 200 ms} against the same just-probed localhost daemon); the probe's
     * symbol is private to that class and cannot be shared.
     */
    static final int CONNECT_TIMEOUT_MS = 200;

    /**
     * Convenience-constructor default cap for the outbound pacer: an
     * effectively-unpaced rate. Only the non-injecting callers reach it —
     * the capability-introspection paths and the unit tests that drive the
     * wire protocol directly; production wiring ({@link SignalAdapter#start()})
     * injects the capability-derived cap so real §6.3.6 pacing applies. A
     * cap this high keeps a direct-client test's handful of outbound frames
     * inside the starting burst, so no real {@link Thread#sleep} pacing fires.
     */
    private static final int UNPACED_DEFAULT_CAP = 1_000_000;

    /**
     * Explicit stand-in for "no daemon-generation gating": every connection
     * stamps this same constant at {@link #connect()} and the fire-time
     * check reads it again, so {@code stamp == daemonGeneration.getAsLong()}
     * always holds and {@link #hungRestartHook} fires unconditionally. Every
     * constructor that accepts a {@code hungRestartHook} requires a
     * {@link LongSupplier} explicitly — none may silently fall back to this
     * behavior — so a future production wiring that omitted the generation
     * argument fails to compile instead of quietly reopening
     * RT-M1-681-r2-1 (M1-683). Tests that deliberately want an ungated
     * restart — the transport-death tests, which assert the restart fires
     * and have no supervised subprocess behind the hook — pass this
     * constant explicitly instead of relying on an implicit default.
     */
    static final LongSupplier ALWAYS_MATCHING_GENERATION = () -> 0L;

    private final InetSocketAddress endpoint;
    private final String account;
    private final SignalMessageCodec codec;
    private final Duration responseTimeout;
    private final Runnable hungRestartHook;
    // The live SignalSubprocess generation, read at connect() to stamp each
    // connection and again before firing hungRestartHook to confirm the
    // child this connection served is still current (M1-681). Every
    // constructor requires this explicitly — ALWAYS_MATCHING_GENERATION is
    // the supported opt-out for a deliberately ungated restart (M1-683);
    // there is no implicit default a hook can silently inherit. Production
    // wires SignalSubprocess::generation.
    private final LongSupplier daemonGeneration;
    private final int inboundQueueCapacity;
    // Outbound send pacer (design §6.3.6): one token per outbound wire
    // frame. The client owns the draw so the §6.3.6 "one token per frame"
    // contract holds structurally regardless of how many frames a single
    // SPI call expands into — notably the edit-failure fresh-send fallback,
    // whose extra frame draws its own token via pacedCall (M1-359).
    private final OutboundRateLimiter outboundRate;
    // Cumulative count of inbound notifications dropped on queue overflow
    // (distinct from the benign shutdown-time drops in dispatchAsync).
    // Instance-scoped, so it accumulates across reconnects.
    private final AtomicLong droppedInboundCount = new AtomicLong();

    // Consecutive JSON-RPC response timeouts. Reset to zero whenever the
    // daemon answers (a success OR an error response — either proves it is
    // alive); reaching HUNG_TIMEOUT_THRESHOLD forces one subprocess restart
    // per crossing (see recordTimeout).
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger();

    // The live connection, or null between disconnect() and the next
    // connect(). ONE client instance spans reconnects (unlike SimpleX's
    // per-rebuild client), so every piece of state a reader may mutate on
    // its way out lives on the connection it belongs to rather than here —
    // see SignalConnection for why that is load-bearing and not cosmetic
    // (M1-681). Threads capture it into a local once and use that local
    // for the whole operation; re-reading the field mid-operation is what
    // would let work straddle a reconnect.
    @Nullable private volatile SignalConnection current;

    /**
     * Upper bound on tracked send-handles, mirroring SimpleXAdapter's
     * MAX_TRACKED_HANDLES. Eviction-on-finalize alone does not bound
     * the map: fire-once replies — the common case — are never
     * finalized and would accumulate for the life of the connection.
     * Access-order LRU keeps the hot tail; an evicted handle behaves
     * exactly like an unknown one (PERMANENT "not open"), the same
     * outcome a Provider restart produces — handles are
     * in-process-only by the {@link MessageHandle} contract.
     */
    static final int MAX_TRACKED_HANDLES = 1_024;

    // Open-window handle registry: a handle lives here from send() until
    // finalizeHandle() removes it or LRU eviction reclaims the slot.
    // "Unknown handle", "already finalized", and "evicted" all resolve
    // to a missing key — all PERMANENT per the SPI invariant, so the
    // lookup collapses to a single check.
    /** Guarded by its own monitor — LinkedHashMap is not thread-safe. */
    private final Map<String, SignalMessageHandle> handles =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SignalMessageHandle> eldest) {
                    return size() > MAX_TRACKED_HANDLES;
                }
            };
    private final AtomicLong rpcIdGen = new AtomicLong();
    private final AtomicLong handleIdGen = new AtomicLong();

    // Late-bound by SignalAdapter (bindMetrics/attachClient) from
    // AdapterMetrics.bindAdapter at registration. The noop() initializer
    // keeps unbound instances — plain-constructed tests, a client built
    // before registration — emitting into a throwaway registry instead
    // of null-checking at every emission site.
    private volatile AdapterMetrics metrics = AdapterMetrics.noop();
    private volatile MessagingAdapter.@Nullable InboundHandler inboundHandler;
    private volatile @Nullable Consumer<JsonObject> groupNotificationHandler;

    /**
     * Convenience constructor with no hung-process escalation wired:
     * consecutive request timeouts are still counted but never restart the
     * subprocess. Production wiring ({@link SignalAdapter#start()}) uses the
     * full constructor so the supervisor ({@link SignalSubprocess}) can be
     * kicked when the daemon wedges.
     */
    SignalJsonRpcClient(InetSocketAddress endpoint,
                        String account,
                        SignalMessageCodec codec,
                        Duration responseTimeout) {
        this(endpoint, account, codec, responseTimeout, () -> { }, ALWAYS_MATCHING_GENERATION);
    }

    // Test seam: a hook plus a daemon-generation supplier, at the default
    // capacity and unpaced. Lets a transport-death test advance the
    // generation across a simulated respawn to prove the restart gate,
    // without threading an OutboundRateLimiter through the case. The
    // generation supplier is required, not defaulted (M1-683) — pass
    // ALWAYS_MATCHING_GENERATION explicitly for a deliberately ungated hook.
    SignalJsonRpcClient(InetSocketAddress endpoint,
                        String account,
                        SignalMessageCodec codec,
                        Duration responseTimeout,
                        Runnable hungRestartHook,
                        LongSupplier daemonGeneration) {
        this(endpoint, account, codec, responseTimeout, hungRestartHook, INBOUND_QUEUE_CAPACITY,
                new OutboundRateLimiter(UNPACED_DEFAULT_CAP, Clock.systemUTC()), daemonGeneration);
    }

    // Test seam: a small capacity drives the overflow path deterministically
    // without flooding the production-default 1000-deep queue, at the
    // default (unpaced) pacer. Same required-generation rule as the seam
    // above (M1-683) — no arity here may pair a hook with an implicit
    // default.
    SignalJsonRpcClient(InetSocketAddress endpoint,
                        String account,
                        SignalMessageCodec codec,
                        Duration responseTimeout,
                        Runnable hungRestartHook,
                        int inboundQueueCapacity,
                        LongSupplier daemonGeneration) {
        this(endpoint, account, codec, responseTimeout, hungRestartHook, inboundQueueCapacity,
                new OutboundRateLimiter(UNPACED_DEFAULT_CAP, Clock.systemUTC()), daemonGeneration);
    }

    // Production seam: SignalAdapter.start() injects the capability-derived
    // pacer (cap 5) so outbound transmits are paced (the Signal fallback
    // token-charge test injects a counting pacer to pin the per-frame draw),
    // AND SignalSubprocess::generation so the shared-subprocess restart is
    // gated on the child this connection served still being current (M1-681).
    SignalJsonRpcClient(InetSocketAddress endpoint,
                        String account,
                        SignalMessageCodec codec,
                        Duration responseTimeout,
                        Runnable hungRestartHook,
                        int inboundQueueCapacity,
                        OutboundRateLimiter outboundRate,
                        LongSupplier daemonGeneration) {
        this.endpoint = endpoint;
        this.account = account;
        this.codec = codec;
        this.responseTimeout = responseTimeout;
        this.hungRestartHook = hungRestartHook;
        this.inboundQueueCapacity = inboundQueueCapacity;
        this.outboundRate = outboundRate;
        this.daemonGeneration = daemonGeneration;
    }

    void setInboundHandler(MessagingAdapter.InboundHandler handler) {
        this.inboundHandler = handler;
    }

    /**
     * Register the route for receive notifications that are not
     * DM-scope. The handler receives the raw {@code params} object of
     * the JSON-RPC notification ({@code SignalGroupHandler}'s
     * {@code handleReceive} input shape).
     */
    void setGroupNotificationHandler(Consumer<JsonObject> handler) {
        this.groupNotificationHandler = handler;
    }

    void connect() throws IOException {
        // Bounded connect (CONNECT_TIMEOUT_MS): an unconnected socket plus an
        // explicit connect(endpoint, timeout) so an unanswered SYN fails fast
        // instead of pinning startup on the OS default. The socket comes from
        // the newSocket() seam so a test can inject one that records the
        // timeout argument without opening a real connection.
        Socket s = newSocket();
        s.connect(endpoint, CONNECT_TIMEOUT_MS);
        BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        // A fresh connection talks to a fresh daemon: a timeout streak
        // carried over from before the outage (e.g. 2 of 3) must not
        // combine with one post-reconnect timeout into a spurious
        // hung-restart kick against the new child. This counter tracks the
        // CHILD rather than the socket, so it stays on the client while the
        // per-socket state moves onto the connection below.
        consecutiveTimeouts.set(0);
        // Fresh state per connect — nothing carries over. The reader is
        // handed ITS connection, so everything it can later latch, drain or
        // tear down belongs to that connection and a successor published by
        // a later connect() is unreachable from it (M1-681). The connection
        // also records the daemon generation live NOW, so its reader can
        // later tell whether the child it served is still current before
        // asking the supervisor to SIGKILL it.
        SignalConnection conn = new SignalConnection(
                s, w, inboundQueueCapacity, daemonGeneration.getAsLong());
        Thread t = new Thread(() -> readerLoop(conn), "signal-jsonrpc-reader");
        t.setDaemon(true);
        conn.readerThread = t;
        this.current = conn;
        t.start();
    }

    /**
     * Socket factory seam mirroring {@code SignalAdapter.connectClient}'s
     * test idiom: {@link #connect()} obtains its unconnected socket here so a
     * test can override this to inject a socket that records the
     * {@link #CONNECT_TIMEOUT_MS} argument (and throws to exercise the
     * timeout path) without a real network connection — the connect window is
     * one no test can produce deterministically through {@code start()}.
     */
    Socket newSocket() {
        return new Socket();
    }

    void disconnect() {
        SignalConnection conn = current;
        // Retired before the teardown starts, so isConnected() goes false at
        // once, no new call() can attach to a connection being torn down, and
        // the reader's latch sees it is no longer current (no restart fires
        // for a close we initiated).
        this.current = null;
        if (conn != null) {
            // Set BEFORE the socket close (the SimpleX local-close suppression):
            // closing the socket exits the reader, whose latch then reads this
            // close as intentional and fires no restart — reconnect()'s
            // teardown-before-serve and stop() must never restart the child.
            conn.closed = true;
            try {
                conn.socket.close();
            } catch (IOException e) {
                LOG.debugf("error closing socket: %s", e.getMessage());
            }
            Thread t = conn.readerThread;
            if (t != null) {
                try {
                    t.join(SignalConnection.READER_JOIN_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Wake any awaiting callers so they fail fast rather than time out.
            // Runs BEFORE the dispatcher shutdown: a dispatch thread blocked in
            // call() (a handler replying synchronously) is parked on one of
            // these futures and must be released for the shutdown to complete.
            conn.drainPending();
            conn.shutdownDispatcher();
        }
        // Drop any open per-handle state — a reconnect starts with a
        // fresh registry; stale handles from before the disconnect
        // resolve to PERMANENT on the next update attempt, which is
        // correct (the original send was on a now-dead connection).
        synchronized (handles) {
            handles.clear();
        }
    }

    MessageHandle send(OutboundMessage msg) throws MessagingException {
        ScopeRef scope = msg.scope();
        // The address signal-cli delivers to: a contact id for DM scope, an
        // adapter group id for group scope. signal-cli's `send` carries a DM
        // in a `recipient` array but a group in a single `groupId` string —
        // the two are mutually exclusive on the wire, so the scope selects
        // the encoder. The address is stored on the handle so editMessage
        // re-addresses subsequent edits to the same destination.
        String destination = destination(scope);
        long rpcId = rpcIdGen.incrementAndGet();
        String request = scope instanceof ScopeRef.Group
                ? codec.encodeGroupSend(rpcId, account, destination, msg.text())
                : codec.encodeSend(rpcId, account, destination, msg.text());
        SignalMessageCodec.JsonRpcMessage.Response response = pacedCall(rpcId, request);
        long timestamp = extractLong(response.result(), "timestamp", "send");
        long handleSerial = handleIdGen.incrementAndGet();
        MessageHandle handle = new MessageHandle(OPAQUE_PREFIX + handleSerial);
        synchronized (handles) {
            handles.put(handle.opaqueValue(), new SignalMessageHandle(timestamp, destination, msg, false));
        }
        return handle;
    }

    void update(MessageHandle handle, String body) throws MessagingException {
        SignalMessageHandle internal = lookupOpen(handle);
        if (internal.fellBack()) {
            // A prior edit on this handle was unrecoverable; never edit again
            // (design §6.3.8: subsequent updates continue to fall back).
            fallbackSend(handle, internal, body);
            recordFallbackSend(internal);
            return;
        }
        try {
            editMessage(handle, internal, body);
        } catch (MessagingException e) {
            // TRANSIENT (write failure, response timeout, closed-before-ack)
            // must propagate so the Provider retries the same edit; only an
            // unrecoverable PERMANENT edit (edit window expired, item deleted)
            // triggers the fresh-send fallback (design §6.5.7).
            if (e.category() != FailureCategory.PERMANENT) {
                throw e;
            }
            recordUpdateFail();
            fallbackSend(handle, internal, body);
            recordFallbackSend(internal);
        }
    }

    void finalizeHandle(MessageHandle handle, String body) throws MessagingException {
        SignalMessageHandle internal = lookupOpen(handle);
        if (internal.fellBack()) {
            fallbackSend(handle, internal, body);
            recordFallbackSend(internal);
        } else {
            try {
                editMessage(handle, internal, body);
            } catch (MessagingException e) {
                if (e.category() != FailureCategory.PERMANENT) {
                    throw e;
                }
                recordUpdateFail();
                fallbackSend(handle, internal, body);
                recordFallbackSend(internal);
            }
        }
        // Eviction-on-finalize bounds the open-handle map. Subsequent
        // update() on this handle resolves to a missing key in
        // lookupOpen, which throws PERMANENT — same category the SPI
        // requires for "already finalized", so no behavioral change.
        synchronized (handles) {
            handles.remove(handle.opaqueValue());
        }
    }

    /**
     * Fresh-send fallback for an unrecoverable {@code editMessage} (design
     * §6.3.8: "the adapter MUST fall back to sending a NEW message via
     * {@code send}, with {@code correlationId} matching the original";
     * §6.5.7 restates it for Signal). The new message re-addresses the
     * original recipient and scope; {@code internal.original()} carries the
     * original {@code correlationId}, so the fresh send stays tied to the
     * originating outbound. The new {@code timestamp} is discarded — the
     * handle is now in fallback mode, so every later update/finalize
     * fresh-sends too and never edits again.
     */
    private void fallbackSend(MessageHandle handle, SignalMessageHandle internal, String body)
            throws MessagingException {
        long rpcId = rpcIdGen.incrementAndGet();
        String request = internal.original().scope() instanceof ScopeRef.Group
                ? codec.encodeGroupSend(rpcId, account, internal.recipient(), body)
                : codec.encodeSend(rpcId, account, internal.recipient(), body);
        // pacedCall, not call: the fresh-send fallback is an extra wire frame
        // beyond the failed edit, so it draws its own §6.3.6 token — without
        // this, sustained fallback transmits at 2x maxSendsPerSecond (M1-359).
        pacedCall(rpcId, request);
        // Switch the stored handle into fallback mode so a subsequent
        // update skips the doomed edit. A concurrent eviction/finalize may
        // have removed it; re-put only if still present.
        synchronized (handles) {
            SignalMessageHandle present = handles.get(handle.opaqueValue());
            if (present != null) {
                handles.put(handle.opaqueValue(), present.asFallenBack());
            }
        }
    }

    void setTyping(ScopeRef scope, boolean typing) {
        if (!(scope instanceof ScopeRef.Dm dm)) {
            // Group typing indicators are intentionally not sent: setTyping is
            // best-effort per the SPI, so a non-DM scope drops the call rather
            // than fail.
            return;
        }
        long rpcId = rpcIdGen.incrementAndGet();
        String request = codec.encodeSendTyping(rpcId, account, dm.contactId(), typing);
        try {
            call(rpcId, request);
            // §6.12 typing counter, emitted at the wire so only pulses
            // that actually reached signal-cli count — absorbed failures
            // and the group-scope drop above stay invisible, and
            // capability-declared no-op adapters (SimpleX) stay at zero.
            metrics.typingToggle(ADAPTER_NAME, scope, typing);
        } catch (MessagingException e) {
            // setTyping is declared without `throws MessagingException` — typing
            // pulses are best-effort UI hints per the SPI; absorb here.
            LOG.debugf("sendTyping failed (best-effort): %s", e.getMessage());
        }
    }

    private void editMessage(MessageHandle handle, SignalMessageHandle internal, String body)
            throws MessagingException {
        long rpcId = rpcIdGen.incrementAndGet();
        // internal.recipient() is the address the original send targeted — a
        // contact id for DM, an adapter group id for group. The original
        // scope selects which signal-cli addressing field that identifier
        // rides in: the `recipient` array (DM) or the `groupId` string.
        String request = internal.original().scope() instanceof ScopeRef.Group
                ? codec.encodeGroupEditSend(
                        rpcId, account, internal.recipient(), internal.timestamp(), body)
                : codec.encodeEditSend(
                        rpcId, account, internal.recipient(), internal.timestamp(), body);
        SignalMessageCodec.JsonRpcMessage.Response response = pacedCall(rpcId, request);
        // An edit is itself a send, so its response carries a FRESH timestamp
        // for the new revision. Refresh the stored handle so the NEXT edit on
        // it targets that latest revision: official Signal clients accept an
        // edit chain targeting the latest revision, and targeting-latest also
        // works where implementations accept the original's timestamp, so it
        // is the dominant strategy under either chain semantic (live probe
        // 2026-07-04 proved only a single edit hop; F-live-11 / M1-566).
        long latestTimestamp = extractLong(response.result(), "timestamp", "send");
        // A concurrent eviction/finalize may have removed the handle;
        // re-put only if still present (same guard as fallbackSend).
        synchronized (handles) {
            SignalMessageHandle present = handles.get(handle.opaqueValue());
            if (present != null) {
                handles.put(handle.opaqueValue(), present.withTimestamp(latestTimestamp));
            }
        }
    }

    private static String destination(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm dm -> dm.contactId();
            case ScopeRef.Group group -> group.adapterGroupId();
        };
    }

    private SignalMessageHandle lookupOpen(MessageHandle handle) throws MessagingException {
        // The access-order get() bumps the handle's recency, so handles
        // still being updated stay clear of the LRU eviction tail.
        SignalMessageHandle internal;
        synchronized (handles) {
            internal = handles.get(handle.opaqueValue());
        }
        if (internal == null) {
            // Missing key collapses two cases — never-existed and
            // already-finalized — both of which the SPI classifies as
            // PERMANENT. Preserving the distinction would require an
            // unbounded "finalized" set; dropping it bounds memory.
            throw new MessagingException(
                    FailureCategory.PERMANENT, "Signal handle is not open (unknown or already finalized)");
        }
        return internal;
    }

    /**
     * A paced outbound wire frame: draw one {@link OutboundRateLimiter}
     * token, then dispatch via {@link #call}. Co-locating the token draw
     * with the frame makes the §6.3.6 "one token per wire frame" contract
     * structurally true regardless of how many frames one SPI call expands
     * into — {@link #fallbackSend} emits a SECOND frame from a single
     * {@code update}/{@code finalize}, and routing it here charges that
     * extra frame its own token (M1-359). The token is drawn BEFORE the
     * write, so a frame that then fails (e.g. a rejected edit) still counts
     * against the rate, matching SimpleX's pace-then-transmit shape.
     *
     * <p>{@link #setTyping} deliberately bypasses this and calls
     * {@link #call} directly: typing pulses are best-effort UI hints, not a
     * paced send/update/finalize transmit (§6.3.6 names those three only).</p>
     */
    private SignalMessageCodec.JsonRpcMessage.Response pacedCall(long rpcId, String request)
            throws MessagingException {
        outboundRate.acquire();
        return call(rpcId, request);
    }

    /**
     * Write the request line, await the keyed Response/ErrorResponse,
     * translate ErrorResponse to a classified
     * {@link MessagingException}. The returned message is guaranteed
     * to be a {@link SignalMessageCodec.JsonRpcMessage.Response} —
     * notifications are demultiplexed elsewhere, and ErrorResponse
     * throws.
     */
    private SignalMessageCodec.JsonRpcMessage.Response call(long rpcId, String request)
            throws MessagingException {
        // Captured ONCE for the whole call: the request line, the pending
        // future it registers and the response that completes it must all
        // belong to the same connection. Re-reading the fields per step is
        // what would let one call straddle a reconnect (M1-681).
        SignalConnection conn = current;
        if (conn == null) {
            throw new MessagingException(
                    FailureCategory.TRANSIENT, "SignalJsonRpcClient not connected");
        }
        String id = String.valueOf(rpcId);
        CompletableFuture<SignalMessageCodec.JsonRpcMessage> future = new CompletableFuture<>();
        conn.pending.put(id, future);
        try {
            synchronized (conn.writer) {
                conn.writer.write(request);
                conn.writer.write('\n');
                conn.writer.flush();
            }
        } catch (IOException e) {
            conn.pending.remove(id);
            throw new MessagingException(
                    FailureCategory.TRANSIENT, "JSON-RPC write failed", e);
        }
        SignalMessageCodec.JsonRpcMessage msg;
        try {
            msg = future.get(responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            conn.pending.remove(id);
            recordTimeout();
            throw new MessagingException(
                    FailureCategory.TRANSIENT, "JSON-RPC response timed out after " + responseTimeout, e);
        } catch (InterruptedException e) {
            conn.pending.remove(id);
            Thread.currentThread().interrupt();
            // TRANSIENT per the FailureCategory classification matrix
            // (interrupted-awaiting-ack): the interrupt is a local
            // lifecycle event, not a verdict on the transport or the
            // message — same category SimpleX stamps on this state.
            throw new MessagingException(
                    FailureCategory.TRANSIENT, "Interrupted awaiting JSON-RPC response", e);
        } catch (ExecutionException e) {
            // Unwrap MessagingException raised by the connection's pending
            // drain so the caller sees its category (closed-before-ack →
            // PERMANENT per the FailureCategory matrix), mirroring
            // SimpleXWebSocketClient.sendCommand's unwrap.
            Throwable cause = e.getCause();
            if (cause instanceof MessagingException me) {
                throw me;
            }
            throw new MessagingException(
                    FailureCategory.TRANSIENT,
                    "JSON-RPC call failed: " + (cause == null ? e.getMessage() : cause.getMessage()),
                    cause);
        }
        // The daemon answered (Response or ErrorResponse below); it is not
        // hung, so clear the consecutive-timeout streak.
        consecutiveTimeouts.set(0);
        if (msg instanceof SignalMessageCodec.JsonRpcMessage.ErrorResponse err) {
            // The signal-cli error TEXT routinely embeds destination
            // identifiers (phone numbers, ACIs) and other user-content
            // fragments; including it in the exception message would
            // leak to any non-SafeLog log site upstream. Carry only the
            // numeric JSON-RPC error code — the category classification
            // is what the Provider's retry policy actually consumes.
            throw new MessagingException(
                    classify(err),
                    "signal-cli JSON-RPC error code " + err.code());
        }
        // The reader thread only completes pending futures with Response or
        // ErrorResponse; notifications take a different path. The cast is an
        // internal-trust boundary, not defensive code.
        return (SignalMessageCodec.JsonRpcMessage.Response) msg;
    }

    private void recordTimeout() {
        int n = consecutiveTimeouts.incrementAndGet();
        // CAS-then-fire so a burst of concurrent timeouts that crosses the
        // threshold restarts the subprocess once, not once per timeout: only
        // the thread whose observed count still equals the live counter wins
        // the reset, and it alone runs the hook.
        if (n >= HUNG_TIMEOUT_THRESHOLD && consecutiveTimeouts.compareAndSet(n, 0)) {
            LOG.warnf("signal-cli unresponsive after %d consecutive JSON-RPC timeouts;"
                    + " forcing subprocess restart", HUNG_TIMEOUT_THRESHOLD);
            // Fire only against the child the CURRENT connection served and
            // only while that child is still live (same daemon-generation gate
            // as the reader-exit latch, RT-M1-681-r2-1): a null connection
            // means disconnect() already retired it, and a generation mismatch
            // means the daemon was already respawned, so killing now would take
            // out a healthy successor. Mark before firing: the restart SIGKILLs
            // the child, which kills the socket, which exits the reader — whose
            // latch must then see this death as already handled (its CAS loses)
            // rather than fire a second restart for the one death (M1-681).
            SignalConnection conn = current;
            if (conn != null && daemonGeneration.getAsLong() == conn.daemonGeneration) {
                conn.restartRequested.set(true);
                hungRestartHook.run();
            }
        }
    }

    private static FailureCategory classify(SignalMessageCodec.JsonRpcMessage.ErrorResponse err) {
        // JSON-RPC -32603 ("Internal error") covers signal-cli transient
        // faults from its upstream signaling server; retry has a chance.
        // Everything else — protocol errors, recipient-unknown, blocked,
        // identity revoked, and a missing/non-numeric code (which the
        // codec surfaces as 0, never as -32603) — is PERMANENT per the
        // spec's default-to-PERMANENT rule.
        if (err.code() == -32603) {
            return FailureCategory.TRANSIENT;
        }
        return FailureCategory.PERMANENT;
    }

    private static long extractLong(JsonObject obj, String key, String method) throws MessagingException {
        if (!obj.containsKey(key)) {
            throw new MessagingException(
                    FailureCategory.PERMANENT,
                    method + " response missing required field: " + key);
        }
        // instanceof, not getJsonNumber: this is daemon output at a trust
        // boundary, and a wrong-typed field must surface as the SPI's
        // classified MessagingException, never as a ClassCastException
        // escaping send(). Same for a non-integral number below — the
        // value is a millisecond timestamp, so a fractional or oversized
        // number is equally malformed (longValueExact throws on both).
        if (!(obj.get(key) instanceof JsonNumber number)) {
            throw new MessagingException(
                    FailureCategory.PERMANENT,
                    method + " response field is not a number: " + key);
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException e) {
            throw new MessagingException(
                    FailureCategory.PERMANENT,
                    method + " response field is not a long: " + key, e);
        }
    }

    private void readerLoop(SignalConnection conn) {
        try {
            readAllLines(conn);
        } finally {
            // EVERY reader exit is a transport death: the clean-EOF loop end,
            // the mid-oversize-drain EOF return inside readAllLines, the
            // IOException arm, and an escaping RuntimeException all mean no
            // inbound byte will ever arrive again. finally — not per-arm
            // calls — is what makes that exhaustive (M1-681).
            latchTransportDeath(conn);
        }
    }

    private void readAllLines(SignalConnection conn) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.socket.getInputStream(), StandardCharsets.UTF_8))) {
            // Cap-aware read loop: BufferedReader.readLine() has no
            // length bound, so a peer that never emits a newline would
            // grow the internal buffer until OOM. We accumulate into a
            // StringBuilder ourselves, drop the line on overflow, and
            // resume at the next terminator.
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) != -1) {
                if (c == '\n') {
                    if (sb.length() > 0) {
                        handleLine(conn, sb.toString());
                    }
                    sb.setLength(0);
                    continue;
                }
                if (sb.length() >= MAX_INBOUND_LINE_CHARS) {
                    // Content already discarded; no user data leaks
                    // into the log message.
                    LOG.warnf("dropped inbound JSON-RPC line exceeding %d-char cap",
                            MAX_INBOUND_LINE_CHARS);
                    sb.setLength(0);
                    if (!skipToNewline(r)) {
                        // EOF while draining the oversize line; the
                        // partial content is dropped, same as the
                        // under-cap trailing case below.
                        return;
                    }
                } else {
                    sb.append((char) c);
                }
            }
            // Trailing data without a terminator (peer half-closed mid-line)
            // is processed only when under the cap; otherwise dropped.
            if (sb.length() > 0) {
                handleLine(conn, sb.toString());
            }
        } catch (IOException e) {
            LOG.debugf("signal-cli reader loop exited: %s", e.getMessage());
        }
    }

    /**
     * Terminal handler for every reader-loop exit, mirroring
     * {@code SimpleXWebSocketClient.latchTransportDeath}. A reader that
     * stops reading means no inbound byte — response or notification —
     * will ever arrive on this connection again, so the channel is dead
     * even while signal-cli itself keeps running (the JSON-RPC channel is
     * a TCP socket, not the child's stdio). Pre-M1-681 this exit set no
     * flag: {@code isConnected()} stayed true, pending futures hung until
     * timeout, and — because a dead reader also kills the inbound traffic
     * whose replies were the only thing {@link #recordTimeout} could count
     * — nothing ever escalated. The silence was self-sustaining.
     *
     * <p>The latch marks the channel dead (honest {@link #isConnected()}),
     * then drains {@link #pending} with the same closed-before-ack
     * PERMANENT category {@link #disconnect()} stamps — BEFORE any
     * dispatcher shutdown, because a dispatch-thread handler blocked in
     * {@code call()} parks on one of these futures and interrupting it
     * first would misclassify its failure as interrupted-awaiting-ack
     * TRANSIENT. On a PEER-initiated death it additionally tears the
     * dispatcher down — discarding queued-but-undelivered inbound is the
     * at-most-once stance, but a death is an outage, not a shutdown, so
     * the depth is counted and logged (count only — no content, no
     * contact ids, D37) — and fires {@link #hungRestartHook} so the
     * supervised restart-to-reconnect path rebuilds the channel with no
     * dependence on outbound traffic. A local {@link #disconnect()} set
     * {@code closed} before closing the socket, so that exit drains and
     * returns without firing.</p>
     *
     * <p>Never delegates to {@code disconnect()}: it joins this very
     * reader thread (2 s cap), so the latch is mark + drain + counted
     * teardown + guarded fire, nothing more. It needs no ownership check
     * for those three, because everything it touches belongs to
     * {@code conn} and a reader superseded by a later {@code connect()}
     * has no reference to the replacement — see {@link SignalConnection}.
     * The restart hook is the exception: the subprocess is SHARED, so
     * ownership IS re-checked before firing. The restart-requested CAS
     * keeps this latch and the consecutive-timeout escalation from
     * double-firing a restart for one death (the hung restart's SIGKILL
     * kills the socket, which exits the reader).</p>
     */
    private void latchTransportDeath(SignalConnection conn) {
        boolean peerInitiated = !conn.closed;
        conn.closed = true;
        conn.drainPending();
        if (!peerInitiated) {
            return;
        }
        int discarded = conn.shutdownDispatcher();
        if (discarded > 0) {
            long total = droppedInboundCount.addAndGet(discarded);
            LOG.warnf("transport death discarded %d queued inbound deliveries (total dropped %d)",
                    discarded, total);
        }
        // The one effect a per-connection carrier cannot scope: the hook
        // SIGKILLs the child that ALL connections share. Gate it on the
        // daemon GENERATION this connection served still being live — NOT on
        // `conn == current`, which is insufficient: the supervised restart
        // respawns the child (generation advances) up to ENDPOINT_PROBE_TIMEOUT
        // before reconnect() retires the dead connection, so a stale reader's
        // `conn == current` can still hold while a healthy successor daemon
        // already runs, and firing then kills IT (RT-M1-681-r2-1). The
        // generation read is live and monotonic; a respawn that could bump it
        // is scheduled with a backoff of at least hundreds of ms after the
        // death that triggers it, so it cannot slip between this check and the
        // hook on the reader thread. Runs outside any lock: the hook is
        // caller-injected and kills a child process, so holding a lifecycle
        // lock across it would put unbounded foreign code in the
        // connection-publish path (M1-681).
        if (daemonGeneration.getAsLong() == conn.daemonGeneration
                && conn.restartRequested.compareAndSet(false, true)) {
            LOG.warnf("signal-cli JSON-RPC channel died (reader exited); forcing subprocess restart");
            hungRestartHook.run();
        }
    }

    /**
     * Bulk-skip the remainder of an oversize line: read in chunks and
     * scan for the terminator instead of issuing one locked
     * {@code read()} call per discarded char. mark/reset keeps the
     * chars after the terminator in the stream for the next line.
     *
     * @return true when a terminator was consumed; false on EOF.
     */
    private static boolean skipToNewline(BufferedReader r) throws IOException {
        char[] chunk = new char[8_192];
        while (true) {
            r.mark(chunk.length);
            int n = r.read(chunk);
            if (n == -1) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (chunk[i] == '\n') {
                    // Rewind, then consume exactly through the
                    // terminator so the next line's chars survive.
                    r.reset();
                    long toSkip = i + 1L;
                    while (toSkip > 0) {
                        toSkip -= r.skip(toSkip);
                    }
                    return true;
                }
            }
        }
    }

    private void handleLine(SignalConnection conn, String line) {
        SignalMessageCodec.JsonRpcMessage msg;
        try {
            msg = codec.decode(line);
        } catch (RuntimeException e) {
            // RuntimeException, not just the codec's IllegalArgumentException:
            // any NPE/CCE a hostile frame provokes out of decode must cost the
            // line, never the reader thread — signal-cli stays alive when its
            // stream carries garbage, so a dead reader is a permanently deaf
            // adapter that no restart machinery notices.
            // The raw line may carry user-content from a chat-mode message
            // body or signal-cli error text; per D37 and §"User content in
            // exceptions" we must NOT log the bytes themselves or the
            // Throwable's user-bearing message. Class name only.
            LOG.warnf("ignoring malformed inbound JSON-RPC line (parse failure: %s)",
                    e.getClass().getSimpleName());
            return;
        }
        switch (msg) {
            case SignalMessageCodec.JsonRpcMessage.Response r -> completePending(conn, r.id(), r);
            case SignalMessageCodec.JsonRpcMessage.ErrorResponse e -> completePending(conn, e.id(), e);
            case SignalMessageCodec.JsonRpcMessage.Notification n -> dispatchAsync(conn, n);
        }
    }

    /**
     * Hand one notification to the dispatch thread. Responses bypass
     * this hop (they complete pending futures directly on the reader
     * thread) — routing them through the queue would re-introduce the
     * deadlock this hop exists to break: a handler blocked in the
     * dispatch thread would sit ahead of its own ack.
     */
    private void dispatchAsync(SignalConnection conn, SignalMessageCodec.JsonRpcMessage.Notification n) {
        try {
            conn.dispatchExecutor.execute(() -> dispatchNotification(n));
        } catch (RejectedExecutionException e) {
            if (conn.dispatchExecutor.isShutdown()) {
                // disconnect() shut the dispatcher down while the reader was
                // draining its final lines; dropping is correct, the
                // connection is going away (at-most-once inbound).
                LOG.debugf("inbound notification dropped — dispatcher shut down");
                return;
            }
            // The bounded dispatch queue is full: inbound is arriving faster
            // than the single dispatch thread can drain it. Drop the newest
            // notification and count it, rather than let the queue grow
            // without bound and OOM the only user-facing service
            // (docs/design/06-messaging.md §6.3.7).
            long dropped = droppedInboundCount.incrementAndGet();
            // §6.3.7 overflow shed. scope_kind is "unknown" (null scope): the
            // drop fires at enqueue, before the notification is decoded into a
            // dm/group scope.
            metrics.inboundDropped(ADAPTER_NAME, null, AdapterMetrics.DropReason.QUEUE_FULL);
            LOG.warnf("inbound dispatch queue full (cap %d); dropped newest from %s (total dropped %d)",
                    inboundQueueCapacity, redactSender(n), dropped);
        }
    }

    /**
     * Package-private accessor exposing the open-handle count for
     * regression tests that pin the eviction-on-finalize behavior of
     * the {@link #handles} map (the original-tests / DOS-cap remediation
     * surface). Production code does NOT consume this — it has no
     * functional effect on send/update/finalize.
     */
    int openHandleCount() {
        synchronized (handles) {
            return handles.size();
        }
    }

    /** Visible for tests: count of inbound notifications dropped on queue overflow. */
    long droppedInboundCount() {
        return droppedInboundCount.get();
    }

    /** Visible for tests: the current connection's reader thread. */
    @Nullable Thread readerThread() {
        SignalConnection conn = current;
        return conn == null ? null : conn.readerThread;
    }

    /** Visible for tests: current depth of the bounded dispatch queue (0 when disconnected). */
    int dispatchQueueDepth() {
        SignalConnection conn = current;
        return conn == null ? 0 : conn.dispatchQueue.size();
    }

    /**
     * Live connection state for {@link SignalAdapter#connected()}: a
     * connection exists exactly between {@code connect()} and
     * {@code disconnect()} — the same lifecycle as the reader/dispatcher
     * pair that makes the transport usable — AND its death latch has not
     * fired: a reader-loop exit means no inbound byte will ever arrive
     * again, so a latched channel must read disconnected even though the
     * connection object is still populated (M1-681).
     */
    boolean isConnected() {
        SignalConnection conn = current;
        return conn != null && !conn.closed;
    }

    /** Late-binding from {@link SignalAdapter}; see the {@code metrics} field. */
    void bindMetrics(AdapterMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * §6.12 counter for every update/finalize call that resolved as a
     * fresh-send fallback — both the failing edit itself and the
     * subsequent calls a fallen-back handle short-circuits.
     */
    private void recordFallbackSend(SignalMessageHandle internal) {
        metrics.updateOutcome(ADAPTER_NAME, internal.original().scope(),
                AdapterMetrics.UpdateOutcome.FALLBACK_SEND);
    }

    /**
     * §6.12 per-reason counter, incremented once at the failing edit
     * (not on the short-circuited repeats). Reason is {@code unknown}:
     * the response translation in {@link #call} classifies only the
     * retry category, so the edit-window-expired / item-deleted
     * distinction design §6.5.7 envisions is not observable here — a
     * more specific label would be fabricated.
     */
    private void recordUpdateFail() {
        metrics.updateFail(ADAPTER_NAME, AdapterMetrics.UpdateFailReason.UNKNOWN);
    }

    /**
     * Best-effort redacted sender for an overflow WARN line. A receive
     * notification may be a DM (sender recoverable via the codec) or a
     * group / typing / receipt frame (no DM sender) — the latter logs
     * {@code "non-dm"}.
     */
    private String redactSender(SignalMessageCodec.JsonRpcMessage.Notification n) {
        return switch (codec.extractDm(n.params())) {
            case SignalMessageCodec.DmMessage dm ->
                    SignalMessageCodec.redactContactId(dm.received().senderContactId());
            case SignalMessageCodec.OversizeDm od ->
                    SignalMessageCodec.redactContactId(od.senderContactId());
            case SignalMessageCodec.NotDm ignored -> "non-dm";
        };
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

    private void completePending(SignalConnection conn, String id,
                                 SignalMessageCodec.JsonRpcMessage msg) {
        CompletableFuture<SignalMessageCodec.JsonRpcMessage> future = conn.pending.remove(id);
        if (future != null) {
            future.complete(msg);
            return;
        }
        LOG.debugf("dropping JSON-RPC response with unknown id: %s", id);
    }

    private void dispatchNotification(SignalMessageCodec.JsonRpcMessage.Notification n) {
        if (!"receive".equals(n.method())) {
            return;
        }
        switch (codec.extractDm(n.params())) {
            // Not a DM — group-scope envelopes, typing, receipts, and sync
            // notifications all land here. The group route's own decode
            // (SignalGroupHandler.handleReceive) keeps only the group-scope
            // shapes, so handing it every non-DM receive notification is safe.
            case SignalMessageCodec.NotDm ignored -> dispatchGroupNotification(n.params());
            // §6.3.10 transport size-cap shed: silent at the boundary (no
            // reply), but observable. Sender redacted before logging (D37);
            // adapterMessageId is a synthetic id, safe to log.
            case SignalMessageCodec.OversizeDm od -> {
                metrics.inboundDropped(ADAPTER_NAME,
                        new ScopeRef.Dm(od.senderContactId()), AdapterMetrics.DropReason.OVERSIZE);
                LOG.warnf("inbound dropped — exceeds %d-byte size cap; from %s adapterMessageId %s",
                        SignalMessageCodec.MAX_INBOUND_TEXT_BYTES,
                        SignalMessageCodec.redactContactId(od.senderContactId()),
                        od.adapterMessageId());
            }
            case SignalMessageCodec.DmMessage dm -> dispatchDm(dm.received());
        }
    }

    /** Deliver a decoded DM to the registered inbound handler on the dispatch thread. */
    private void dispatchDm(SignalMessageCodec.ReceivedDm received) {
        MessagingAdapter.InboundHandler handler = inboundHandler;
        if (handler == null) {
            LOG.debugf("inbound Signal DM dropped — no InboundHandler set");
            return;
        }
        Identity sender = new Identity(
                received.senderContactId(), received.senderDisplayName(), Instant.now());
        InboundMessage inbound = new InboundMessage(
                sender,
                new ScopeRef.Dm(received.senderContactId()),
                received.body(),
                Instant.ofEpochMilli(received.timestamp()),
                "signal-" + received.timestamp());
        try {
            handler.onMessage(inbound);
        } catch (RuntimeException e) {
            // Mirror SimpleXAdapter.onInbound: a Provider-side handler that
            // throws must NOT propagate and kill the dispatch thread, which
            // would leave the subprocess alive but deaf. Drop this message
            // and keep dispatching. D37: the Throwable's MESSAGE may carry
            // inbound chat-mode body bytes, so it stays suppressed — the class
            // + stack (no user content) localize a Provider handler bug (M1-358).
            LOG.warnf("inbound Signal handler threw, message suppressed per D37; "
                    + "dispatch continues; class + stack:\n%s", stackWithoutMessage(e));
        }
    }

    private void dispatchGroupNotification(JsonObject receiveParams) {
        Consumer<JsonObject> route = groupNotificationHandler;
        if (route == null) {
            return;
        }
        try {
            route.accept(receiveParams);
        } catch (RuntimeException e) {
            // Same dispatch-survival invariant as the DM path above: a
            // throw from group translation or a Provider-side handler
            // must not kill the dispatch thread. D37: the Throwable's
            // message may carry inbound group bytes, so it stays suppressed
            // — the class + stack (no user content) localize the bug (M1-358).
            LOG.warnf("Signal group-route handler threw, message suppressed per D37; "
                    + "dispatch continues; class + stack:\n%s", stackWithoutMessage(e));
        }
    }
}
