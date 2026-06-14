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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
     * and group paths (mirroring SimpleX). The two are independent: an
     * envelope line under this char cap can still carry a body over the
     * byte cap (multi-byte chars, or envelope framing overhead the body
     * does not pay), so the body cap is enforced separately at decode.</p>
     */
    private static final int MAX_INBOUND_LINE_CHARS = 16_384;

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

    private final InetSocketAddress endpoint;
    private final String account;
    private final SignalMessageCodec codec;
    private final Duration responseTimeout;
    private final Runnable hungRestartHook;
    private final int inboundQueueCapacity;
    // Cumulative count of inbound notifications dropped on queue overflow
    // (distinct from the benign shutdown-time drops in dispatchAsync).
    // Instance-scoped, so it accumulates across reconnects.
    private final AtomicLong droppedInboundCount = new AtomicLong();

    // Consecutive JSON-RPC response timeouts. Reset to zero whenever the
    // daemon answers (a success OR an error response — either proves it is
    // alive); reaching HUNG_TIMEOUT_THRESHOLD forces one subprocess restart
    // per crossing (see recordTimeout).
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger();

    private final ConcurrentMap<String, CompletableFuture<SignalMessageCodec.JsonRpcMessage>> pending =
            new ConcurrentHashMap<>();

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
    @Nullable private volatile Socket socket;
    @Nullable private volatile BufferedWriter writer;
    @Nullable private volatile Thread readerThread;
    // Per-connection inbound dispatch thread (created by connect, shut
    // down by disconnect). Notifications hop off the reader thread
    // here so a blocking InboundHandler/MembershipHandler cannot
    // deadlock against the reader that delivers its ack; responses
    // never enter this queue. The backing queue is BOUNDED
    // (inboundQueueCapacity): a ThreadPoolExecutor with the default
    // AbortPolicy rejects an execute() once the queue is full, which
    // dispatchAsync turns into a drop-newest with a counter — the rate
    // cap downstream of this queue bounds work per dequeued item, never
    // the queue's own memory (docs/design/06-messaging.md §6.3.7).
    @Nullable private volatile ExecutorService dispatchExecutor;
    // Reference to the executor's backing queue, kept so tests can read
    // its depth; recreated alongside dispatchExecutor in connect().
    @Nullable private volatile BlockingQueue<Runnable> dispatchQueue;

    /**
     * Convenience constructor with no hung-process escalation wired:
     * consecutive request timeouts are still counted but never restart the
     * subprocess. Production wiring ({@link SignalAdapter#start()}) uses the
     * five-arg form so the supervisor ({@link SignalSubprocess}) can be
     * kicked when the daemon wedges.
     */
    SignalJsonRpcClient(InetSocketAddress endpoint,
                        String account,
                        SignalMessageCodec codec,
                        Duration responseTimeout) {
        this(endpoint, account, codec, responseTimeout, () -> { });
    }

    SignalJsonRpcClient(InetSocketAddress endpoint,
                        String account,
                        SignalMessageCodec codec,
                        Duration responseTimeout,
                        Runnable hungRestartHook) {
        this(endpoint, account, codec, responseTimeout, hungRestartHook, INBOUND_QUEUE_CAPACITY);
    }

    // Test seam: a small capacity drives the overflow path deterministically
    // without flooding the production-default 1000-deep queue.
    SignalJsonRpcClient(InetSocketAddress endpoint,
                        String account,
                        SignalMessageCodec codec,
                        Duration responseTimeout,
                        Runnable hungRestartHook,
                        int inboundQueueCapacity) {
        this.endpoint = endpoint;
        this.account = account;
        this.codec = codec;
        this.responseTimeout = responseTimeout;
        this.hungRestartHook = hungRestartHook;
        this.inboundQueueCapacity = inboundQueueCapacity;
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
        // hung-restart kick against the new child.
        consecutiveTimeouts.set(0);
        this.socket = s;
        this.writer = w;
        // Fresh dispatcher per connect — disconnect() shuts the prior
        // one down, and a shut-down executor rejects all tasks. The
        // backing queue is bounded; on overflow the default AbortPolicy
        // rejects execute() and dispatchAsync drops the newest notification.
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(inboundQueueCapacity);
        this.dispatchQueue = queue;
        this.dispatchExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, queue,
                Thread.ofVirtual().name("signal-inbound-dispatch").factory());
        Thread t = new Thread(this::readerLoop, "signal-jsonrpc-reader");
        t.setDaemon(true);
        this.readerThread = t;
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
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                LOG.debugf("error closing socket: %s", e.getMessage());
            }
        }
        Thread t = readerThread;
        if (t != null) {
            try {
                t.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Wake any awaiting callers so they fail fast rather than time out.
        // Runs BEFORE the dispatcher shutdown: a dispatch thread blocked in
        // call() (a handler replying synchronously) is parked on one of
        // these futures and must be released for the shutdown to complete.
        // PERMANENT per the FailureCategory classification matrix
        // (closed-before-ack): the connection that would have acked these
        // calls is gone, so retrying cannot succeed until the transport is
        // rebuilt —
        // the same category SimpleX stamps when close() drains its pending
        // commands. call() unwraps this exception to preserve the category.
        pending.forEach((id, f) -> f.completeExceptionally(
                new MessagingException(FailureCategory.PERMANENT,
                        "SignalJsonRpcClient disconnected before response (closed-before-ack)")));
        pending.clear();
        ExecutorService executor = dispatchExecutor;
        if (executor != null) {
            // Queued-but-undelivered notifications are dropped — the
            // connection is going away, matching at-most-once inbound.
            executor.shutdownNow();
            this.dispatchExecutor = null;
            this.dispatchQueue = null;
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
        SignalMessageCodec.JsonRpcMessage.Response response = call(rpcId, request);
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
            editMessage(internal, body);
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
                editMessage(internal, body);
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
        call(rpcId, request);
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

    private void editMessage(SignalMessageHandle internal, String body) throws MessagingException {
        long rpcId = rpcIdGen.incrementAndGet();
        // internal.recipient() is the address the original send targeted — a
        // contact id for DM, an adapter group id for group. The original
        // scope selects which signal-cli addressing field that identifier
        // rides in: the `recipient` array (DM) or the `groupId` string.
        String request = internal.original().scope() instanceof ScopeRef.Group
                ? codec.encodeGroupUpdateMessage(
                        rpcId, account, internal.recipient(), internal.timestamp(), body)
                : codec.encodeUpdateMessage(
                        rpcId, account, internal.recipient(), internal.timestamp(), body);
        call(rpcId, request);
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
     * Write the request line, await the keyed Response/ErrorResponse,
     * translate ErrorResponse to a classified
     * {@link MessagingException}. The returned message is guaranteed
     * to be a {@link SignalMessageCodec.JsonRpcMessage.Response} —
     * notifications are demultiplexed elsewhere, and ErrorResponse
     * throws.
     */
    private SignalMessageCodec.JsonRpcMessage.Response call(long rpcId, String request)
            throws MessagingException {
        String id = String.valueOf(rpcId);
        CompletableFuture<SignalMessageCodec.JsonRpcMessage> future = new CompletableFuture<>();
        pending.put(id, future);
        BufferedWriter w = writer;
        if (w == null) {
            pending.remove(id);
            throw new MessagingException(
                    FailureCategory.TRANSIENT, "SignalJsonRpcClient not connected");
        }
        try {
            synchronized (w) {
                w.write(request);
                w.write('\n');
                w.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            throw new MessagingException(
                    FailureCategory.TRANSIENT, "JSON-RPC write failed", e);
        }
        SignalMessageCodec.JsonRpcMessage msg;
        try {
            msg = future.get(responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            recordTimeout();
            throw new MessagingException(
                    FailureCategory.TRANSIENT, "JSON-RPC response timed out after " + responseTimeout, e);
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            // TRANSIENT per the FailureCategory classification matrix
            // (interrupted-awaiting-ack): the interrupt is a local
            // lifecycle event, not a verdict on the transport or the
            // message — same category SimpleX stamps on this state.
            throw new MessagingException(
                    FailureCategory.TRANSIENT, "Interrupted awaiting JSON-RPC response", e);
        } catch (ExecutionException e) {
            // Unwrap MessagingException raised by disconnect()'s pending
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
            hungRestartHook.run();
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

    private void readerLoop() {
        Socket s = socket;
        if (s == null) {
            return;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
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
                        handleLine(sb.toString());
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
                handleLine(sb.toString());
            }
        } catch (IOException e) {
            LOG.debugf("signal-cli reader loop exited: %s", e.getMessage());
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

    private void handleLine(String line) {
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
            case SignalMessageCodec.JsonRpcMessage.Response r -> completePending(r.id(), r);
            case SignalMessageCodec.JsonRpcMessage.ErrorResponse e -> completePending(e.id(), e);
            case SignalMessageCodec.JsonRpcMessage.Notification n -> dispatchAsync(n);
        }
    }

    /**
     * Hand one notification to the dispatch thread. Responses bypass
     * this hop (they complete pending futures directly on the reader
     * thread) — routing them through the queue would re-introduce the
     * deadlock this hop exists to break: a handler blocked in the
     * dispatch thread would sit ahead of its own ack.
     */
    private void dispatchAsync(SignalMessageCodec.JsonRpcMessage.Notification n) {
        ExecutorService executor = dispatchExecutor;
        if (executor == null) {
            // readerLoop only runs after connect() set the field; null
            // means disconnect() already tore it down mid-read.
            return;
        }
        try {
            executor.execute(() -> dispatchNotification(n));
        } catch (RejectedExecutionException e) {
            if (executor.isShutdown()) {
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

    /** Visible for tests: current depth of the bounded dispatch queue (0 when disconnected). */
    int dispatchQueueDepth() {
        BlockingQueue<Runnable> queue = dispatchQueue;
        return queue == null ? 0 : queue.size();
    }

    /**
     * Live connection state for {@link SignalAdapter#connected()}: the
     * dispatch queue exists exactly between {@code connect()} and
     * {@code disconnect()} — the same lifecycle as the reader/dispatcher
     * pair that makes the transport usable.
     */
    boolean isConnected() {
        return dispatchQueue != null;
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
        return codec.extractDm(n.params())
                .map(dm -> redactContactId(dm.senderContactId()))
                .orElse("non-dm");
    }

    /**
     * Non-reversible short token for a sender contact id, safe to log
     * under D37 (a Signal ACI / phone number is a sensitive identifier and
     * is never logged raw). Stable per sender so a repeat flooder stays
     * correlatable in the overflow WARN line without exposing the id.
     */
    private static String redactContactId(String contactId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(contactId.getBytes(StandardCharsets.UTF_8));
            return "contact#" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandated algorithm; its absence cannot happen.
            throw new AssertionError(e);
        }
    }

    private void completePending(String id, SignalMessageCodec.JsonRpcMessage msg) {
        CompletableFuture<SignalMessageCodec.JsonRpcMessage> future = pending.remove(id);
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
        Optional<SignalMessageCodec.ReceivedDm> dm = codec.extractDm(n.params());
        if (dm.isEmpty()) {
            // Not a DM — group-scope envelopes, typing, receipts, and
            // sync notifications all land here. The group route's own
            // decode (SignalGroupHandler.handleReceive) keeps only the
            // group-scope shapes, so handing it every non-DM receive
            // notification is safe.
            dispatchGroupNotification(n.params());
            return;
        }
        MessagingAdapter.InboundHandler handler = inboundHandler;
        if (handler == null) {
            LOG.debugf("inbound Signal DM dropped — no InboundHandler set");
            return;
        }
        SignalMessageCodec.ReceivedDm received = dm.get();
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
            // and keep dispatching. D37: log the exception class only — the
            // Throwable's message may carry inbound chat-mode bytes.
            LOG.warnf("inbound Signal handler threw %s; dropping message, dispatch continues",
                    e.getClass().getSimpleName());
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
            // must not kill the dispatch thread. D37: class name only —
            // the Throwable's message may carry inbound group bytes.
            LOG.warnf("Signal group-route handler threw %s; dropping notification, dispatch continues",
                    e.getClass().getSimpleName());
        }
    }
}
