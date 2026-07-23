package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.zcat.infochat.messaging.ContactIdRedactor;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JDK-{@link HttpClient}-backed WebSocket connection to a running
 * simplex-chat instance. Owns the live socket, delegates framing/JSON
 * details to {@link SimpleXMessageCodec}, and exposes a
 * synchronous-looking {@link #sendCommand} API so callers (the adapter's
 * {@code send}/{@code update}/{@code finalize}) can stay blocking per the
 * Quarkus/JDK-25 virtual-thread + blocking style (CLAUDE.md §Stack, D46).
 *
 * <p>Reconnect is intentionally not owned by this class. Per
 * {@code docs/design/06-messaging.md} §6.4.6 the simplex-chat process and
 * its WebSocket form one supervised unit — {@link SimpleXSubprocess}
 * restarts the process on crash, and the adapter then re-runs
 * {@link #start} from scratch. Tearing the WS down independently of the
 * process would leave the adapter in a half-state nobody can recover; the
 * supervisor manages the whole pair. What this class does own (M1-674) is
 * the death signal: a peer-initiated close or transport error latches
 * {@code closed} and fires the transport-death notifier, so the adapter
 * can pace a rebuild while the process is still alive — see
 * {@link #onTransportDeath}.</p>
 *
 * <p>Trust boundary: the WebSocket is an EXTERNAL system boundary, so
 * frames are validated by {@link SimpleXMessageCodec#decode}. A malformed
 * frame is logged and dropped, never propagated — one bad frame must not
 * tear the connection down (parallel to {@code NostrRelayConnection}'s
 * MalformedFrameException discipline).</p>
 */
final class SimpleXWebSocketClient {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleXWebSocketClient.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    // Caps the pre-assembly buffer for fragmented text frames so a hostile
    // peer cannot OOM the adapter. Mirrors NostrRelayConnection's bound.
    static final int MAX_FRAME_BYTES = 1_048_576;

    // Bound on ONE frame's transmission (the sendText future completing),
    // not the ack round-trip. Transmission to the loopback simplex-chat
    // process is sub-millisecond when healthy; hitting this means the
    // socket is wedged and the supervisor restart is the recovery path.
    static final Duration TRANSMIT_TIMEOUT = Duration.ofSeconds(10);

    // Default bound on the inbound-dispatch executor's work queue. A
    // hostile peer can deliver inbound frames faster than the single
    // dispatch thread drains them (each onInbound does identity-resolution
    // DB work downstream), so the JDK default unbounded LinkedBlockingQueue
    // would grow without bound and OOM the only user-facing service
    // (docs/design/06-messaging.md §6.3.7). At the cap the newest delivery
    // is dropped — see dispatchAsync. A named constant, not runtime config,
    // matching the sibling caps MAX_FRAME_BYTES / MAX_TRACKED_HANDLES; the
    // capacity is a constructor parameter so tests can drive the overflow
    // path with a small queue.
    static final int INBOUND_QUEUE_CAPACITY = 1_000;

    // Adapter label for the inbound-drop counter; mirrors SimpleXAdapter.name().
    private static final String ADAPTER_NAME = "simplex";

    /**
     * Receives decoded inbound chat messages on the client's dedicated
     * inbound-dispatch thread (never the WS listener thread — the
     * consumer may block, e.g. reply synchronously and await the ack
     * only the listener thread can deliver).
     */
    @FunctionalInterface
    interface InboundConsumer {
        void onInbound(InboundMessage msg);
    }

    /**
     * Receives decoded group-scope candidates on the client's dedicated
     * inbound-dispatch thread (same threading contract as
     * {@link InboundConsumer}). The mention-recognition decision
     * belongs to the downstream consumer ({@link SimpleXGroupHandler});
     * the client just routes the variant.
     */
    @FunctionalInterface
    interface GroupCandidateConsumer {
        void onGroupCandidate(SimpleXMessageCodec.GroupCandidate gc);
    }

    /**
     * Receives decoded group invitations on the client's dedicated
     * inbound-dispatch thread (same threading contract as
     * {@link InboundConsumer} — the consumer may block on a DB read and an
     * outbound join). The accept/decline decision belongs to the downstream
     * consumer (Provider's {@code GroupInvitationHandler}); the client just
     * routes the variant (M1-515).
     */
    @FunctionalInterface
    interface GroupInvitationConsumer {
        void onGroupInvitation(SimpleXMessageCodec.ReceivedGroupInvitation invitation);
    }

    private final URI uri;
    private final HttpClient httpClient;
    private final InboundConsumer inboundConsumer;
    private final GroupCandidateConsumer groupCandidateConsumer;
    private final GroupInvitationConsumer groupInvitationConsumer;

    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    // Single dedicated inbound-dispatch thread: Inbound / GroupCandidate
    // frames hop off the WS listener thread here so a blocking consumer
    // (a handler replying synchronously from onMessage) cannot deadlock
    // against the listener that must deliver its ack. SendAck and
    // CommandError never enter this queue — they complete pending
    // futures directly on the listener thread. One thread (not a pool)
    // preserves per-connection FIFO delivery order by construction. The
    // worker is created lazily on first dispatch, so a never-started
    // client spawns no thread; the instance is terminal after close()
    // (the `closed` flag never resets), matching the executor's
    // shutdown-once lifecycle.
    //
    // The backing queue is BOUNDED (inboundQueueCapacity): a ThreadPoolExecutor
    // with the default AbortPolicy rejects an execute() once the queue is full,
    // which dispatchAsync turns into a drop-newest with a counter — the rate
    // cap downstream of this queue bounds work per dequeued item, never the
    // queue's own memory (docs/design/06-messaging.md §6.3.7).
    private final int inboundQueueCapacity;
    private final BlockingQueue<Runnable> dispatchQueue;
    private final ExecutorService dispatchExecutor;
    // Cumulative count of inbound deliveries dropped on queue overflow or
    // discarded queued-but-undelivered by a peer-initiated transport death
    // (distinct from the benign local-close shutdown drops in dispatchAsync).
    private final AtomicLong droppedInboundCount = new AtomicLong();
    // Late-bound by SimpleXAdapter (rebuildWebSocket / bindMetrics) from
    // AdapterMetrics.bindAdapter at registration; the noop() initializer keeps
    // a never-bound client (unit tests, pre-registration) emitting into a
    // throwaway registry rather than NPE-ing on a drop.
    private volatile AdapterMetrics metrics = AdapterMetrics.noop();
    // Null until connect() completes the handshake; every read copies to a
    // local and guards on null before use.
    private volatile @Nullable WebSocket webSocket;
    // Serializes frame transmission: the JDK WebSocket permits only ONE
    // outstanding text send per connection, so transmit() holds this
    // monitor from sendText() until the returned future completes.
    // Concurrent senders queue here instead of racing into the JDK's
    // IllegalStateException rejection, which silently drops the frame.
    private final Object sendLock = new Object();
    private volatile boolean closed = false;
    // Fired at most once, from the WS listener thread, when the PEER (or a
    // transport error) kills the connection — never for a local close();
    // see latchTransportDeath. Seeded to a no-op so clients the adapter has
    // not wired (unit tests) need no null check, mirroring the metrics field.
    private volatile Runnable transportDeathNotifier = () -> { };

    // A client built without an invitation consumer ignores group invitations:
    // the inbound / group-candidate / transport tests do not exercise the
    // invitation path, so their existing constructors keep their signatures and
    // default to this (the same noop-default idiom as the metrics field). Only
    // the production wiring (SimpleXAdapter) passes a real consumer (M1-515).
    private static final GroupInvitationConsumer NOOP_INVITATION = invitation -> { };

    SimpleXWebSocketClient(URI uri,
                           HttpClient httpClient,
                           InboundConsumer inboundConsumer,
                           GroupCandidateConsumer groupCandidateConsumer) {
        this(uri, httpClient, inboundConsumer, groupCandidateConsumer,
                NOOP_INVITATION, INBOUND_QUEUE_CAPACITY);
    }

    /** Production constructor: wires the real group-invitation consumer (M1-515). */
    SimpleXWebSocketClient(URI uri,
                           HttpClient httpClient,
                           InboundConsumer inboundConsumer,
                           GroupCandidateConsumer groupCandidateConsumer,
                           GroupInvitationConsumer groupInvitationConsumer) {
        this(uri, httpClient, inboundConsumer, groupCandidateConsumer,
                groupInvitationConsumer, INBOUND_QUEUE_CAPACITY);
    }

    // Test seam: a small capacity drives the overflow path deterministically
    // without flooding the production-default 1000-deep queue.
    SimpleXWebSocketClient(URI uri,
                           HttpClient httpClient,
                           InboundConsumer inboundConsumer,
                           GroupCandidateConsumer groupCandidateConsumer,
                           int inboundQueueCapacity) {
        this(uri, httpClient, inboundConsumer, groupCandidateConsumer,
                NOOP_INVITATION, inboundQueueCapacity);
    }

    SimpleXWebSocketClient(URI uri,
                           HttpClient httpClient,
                           InboundConsumer inboundConsumer,
                           GroupCandidateConsumer groupCandidateConsumer,
                           GroupInvitationConsumer groupInvitationConsumer,
                           int inboundQueueCapacity) {
        this.uri = uri;
        this.httpClient = httpClient;
        this.inboundConsumer = inboundConsumer;
        this.groupCandidateConsumer = groupCandidateConsumer;
        this.groupInvitationConsumer = groupInvitationConsumer;
        this.inboundQueueCapacity = inboundQueueCapacity;
        this.dispatchQueue = new LinkedBlockingQueue<>(inboundQueueCapacity);
        this.dispatchExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, dispatchQueue,
                Thread.ofVirtual().name("simplex-inbound-dispatch").factory());
    }

    /**
     * Block until the WebSocket handshake completes or
     * {@link #CONNECT_TIMEOUT} elapses. The adapter's {@code start()}
     * routes the failure to a {@link MessagingException} so Provider sees
     * a categorised failure rather than a bare exception.
     */
    void start() throws MessagingException {
        try {
            this.webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(uri, new Listener())
                    .get(CONNECT_TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "interrupted while opening WebSocket to " + uri, e);
        } catch (TimeoutException e) {
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "WebSocket handshake to " + uri + " did not complete within "
                            + CONNECT_TIMEOUT, e);
        } catch (ExecutionException e) {
            // The handshake failed because simplex-chat refused the connection,
            // the port is not listening yet, or the network rejected the dial.
            // The supervisor will restart the subprocess; treat as transient.
            // A future completed exceptionally always carries a cause.
            Throwable cause = Objects.requireNonNull(e.getCause());
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "WebSocket handshake to " + uri + " failed: "
                            + cause.getClass().getSimpleName(), cause);
        }
    }

    /**
     * Tear the connection down. Idempotent. Any in-flight
     * {@link #sendCommand} calls complete exceptionally with a
     * {@link FailureCategory#PERMANENT} {@link MessagingException} so
     * callers do not block forever on a corked socket.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.abort();
        }
        // Drain pending command futures; the wire closing on us means none
        // will ever be acked. The category is PERMANENT because retrying on a
        // closed socket cannot succeed — callers must rebuild via start().
        // Runs BEFORE the dispatcher shutdown: a dispatch thread blocked in
        // sendCommand (a consumer replying synchronously) is parked on one
        // of these futures and must be released first.
        var snapshot = Map.copyOf(pending);
        pending.clear();
        for (var entry : snapshot.entrySet()) {
            entry.getValue().completeExceptionally(new MessagingException(
                    FailureCategory.PERMANENT,
                    "WebSocket closed before command " + entry.getKey() + " was acked"));
        }
        // Queued-but-undelivered inbound frames are dropped — the
        // connection is going away, matching at-most-once inbound.
        dispatchExecutor.shutdownNow();
    }

    /**
     * Send a command envelope and block until the matching {@link
     * SimpleXMessageCodec.SendAck} arrives, returning the chat-item id the
     * adapter stores in {@link SimpleXMessageHandle}. A
     * {@link SimpleXMessageCodec.CommandError} response completes
     * exceptionally with a {@link MessagingException} whose
     * {@link FailureCategory} comes straight from
     * {@link SimpleXMessageCodec#classifyError(String)}.
     *
     * @param corrId       adapter-chosen correlation id matching the {@code corrId}
     *                     field in {@code envelopeJson} (the codec already wrote it).
     * @param envelopeJson the JSON envelope produced by {@link SimpleXMessageCodec}.
     * @param ackTimeout   bound on how long to wait for the ack.
     */
    String sendCommand(String corrId,
                                String envelopeJson,
                                Duration ackTimeout) throws MessagingException {
        if (closed) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket is closed; cannot send corrId=" + corrId);
        }
        WebSocket ws = webSocket;
        if (ws == null) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket is not yet started; cannot send corrId=" + corrId);
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(corrId, future);
        try {
            transmit(ws, corrId, envelopeJson);
            return future.get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "interrupted while awaiting ack for corrId=" + corrId, e);
        } catch (TimeoutException e) {
            throw new MessagingException(FailureCategory.TRANSIENT,
                    "no ack for corrId=" + corrId + " within " + ackTimeout, e);
        } catch (ExecutionException e) {
            // Unwrap MessagingException raised by the listener thread so the
            // caller sees the original FailureCategory, not a wrapper.
            // A future completed exceptionally always carries a cause.
            Throwable cause = Objects.requireNonNull(e.getCause());
            if (cause instanceof MessagingException me) {
                throw me;
            }
            throw new MessagingException(FailureCategory.PERMANENT,
                    "ack future for corrId=" + corrId + " failed: "
                            + cause.getClass().getSimpleName(),
                    cause);
        } catch (RuntimeException e) {
            // close() can abort the WebSocket between the `closed` check above
            // and ws.sendText() here; the JDK WebSocket then rejects the send
            // with an IllegalStateException (a RuntimeException) that would
            // otherwise escape sendCommand raw and uncategorised. A send
            // COLLISION cannot raise this — transmit() serializes senders —
            // so a synchronous IllegalStateException is unambiguously the
            // aborted-socket case. Classify as
            // PERMANENT — the socket is gone and retrying on it cannot succeed;
            // the caller must rebuild via start(), the same category close()
            // stamps on the pending futures it drains.
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket send for corrId=" + corrId + " failed (closed concurrently): "
                            + e.getClass().getSimpleName(), e);
        } finally {
            pending.remove(corrId);
        }
    }

    /**
     * Transmit a command whose success is observed out-of-band rather than
     * through a {@code corrId} ack — used for {@code /_join} (M1-515): the live
     * wire returns {@code userAcceptedGroupSent} (corrId) plus an async
     * {@code userJoinedGroup}, neither carrying a chat-item handle the caller
     * needs, so registering and awaiting a pending future would only time out.
     * The same closed/not-started guards and {@link #transmit} TRANSIENT
     * classification as {@link #sendCommand} apply, so a wedged socket still
     * routes through the supervisor restart path; only the ack wait is dropped.
     */
    void sendNoAck(String corrId, String envelopeJson) throws MessagingException {
        if (closed) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket is closed; cannot send corrId=" + corrId);
        }
        WebSocket ws = webSocket;
        if (ws == null) {
            throw new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket is not yet started; cannot send corrId=" + corrId);
        }
        transmit(ws, corrId, envelopeJson);
    }

    /**
     * Transmit one text frame, holding {@link #sendLock} from
     * {@code sendText} until the JDK confirms the frame left the
     * socket (the returned future completes). The JDK WebSocket
     * permits only one outstanding text send per connection; a second
     * {@code sendText} while the prior send's future is incomplete is
     * rejected with {@link IllegalStateException} and its frame is
     * never transmitted. Awaiting the future INSIDE the lock makes
     * concurrent senders queue at the monitor instead of hitting that
     * rejection, so a collision can never fail a send.
     *
     * <p>Every failure here is {@link FailureCategory#TRANSIENT}: a
     * timed-out or failed transmission means the socket is wedged or
     * mid-teardown, and the supervisor restart recovers it — the same
     * category the previous fire-and-discard shape surfaced via the
     * ack timeout. (A transmit timeout releases the lock with the
     * send still outstanding; the next transmit is then rejected
     * asynchronously and lands in the {@code ExecutionException} arm
     * below, still TRANSIENT — never PERMANENT for a collision.)
     * A synchronous {@link RuntimeException} from {@code sendText}
     * (the concurrently-aborted-socket case) propagates raw for the
     * caller to classify.</p>
     */
    private void transmit(WebSocket ws, String corrId, String envelopeJson)
            throws MessagingException {
        synchronized (sendLock) {
            try {
                ws.sendText(envelopeJson, true)
                        .get(TRANSMIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingException(FailureCategory.TRANSIENT,
                        "interrupted while transmitting corrId=" + corrId, e);
            } catch (TimeoutException e) {
                throw new MessagingException(FailureCategory.TRANSIENT,
                        "frame for corrId=" + corrId + " not transmitted within "
                                + TRANSMIT_TIMEOUT, e);
            } catch (ExecutionException e) {
                // A future completed exceptionally always carries a cause.
                Throwable cause = Objects.requireNonNull(e.getCause());
                throw new MessagingException(FailureCategory.TRANSIENT,
                        "WebSocket transmit for corrId=" + corrId + " failed: "
                                + cause.getClass().getSimpleName(), cause);
            }
        }
    }

    /** Visible for tests. */
    boolean isClosed() {
        return closed;
    }

    /** Visible for tests: inbound deliveries dropped on queue overflow or transport death. */
    long droppedInboundCount() {
        return droppedInboundCount.get();
    }

    /** Visible for tests: current depth of the bounded dispatch queue. */
    int dispatchQueueDepth() {
        return dispatchQueue.size();
    }

    /** Late-binding from {@link SimpleXAdapter}; see the {@code metrics} field. */
    void bindMetrics(AdapterMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Register the adapter's transport-death notifier (M1-674). MUST be
     * called before {@link #start()}: the listener only runs once the
     * handshake completes, so a pre-start registration can never miss the
     * death event (a post-start registration could — the peer may close
     * the socket the instant the handshake finishes). Fires on the WS
     * listener thread; implementations must hop to their own thread
     * before blocking. Last registration wins.
     */
    void onTransportDeath(Runnable notifier) {
        this.transportDeathNotifier = notifier;
    }

    // Package-private (not private): the peer-close latch tests drive
    // onClose/onError directly — the RFC-6455 test fake cannot emit a
    // server-side close frame — while production construction stays
    // inside start().
    final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private boolean skipUntilLast = false;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.@Nullable CompletionStage<?> onText(WebSocket webSocket,
                                                              CharSequence data,
                                                              boolean last) {
            if (skipUntilLast) {
                if (last) {
                    skipUntilLast = false;
                }
                webSocket.request(1);
                return null;
            }
            if ((long) buffer.length() + data.length() > MAX_FRAME_BYTES) {
                LOG.warn("simplex-chat fragment would exceed {} bytes; dropping frame",
                        MAX_FRAME_BYTES);
                buffer.setLength(0);
                skipUntilLast = !last;
                webSocket.request(1);
                return null;
            }
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                dispatch(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.@Nullable CompletionStage<?> onClose(WebSocket webSocket,
                                                               int statusCode,
                                                               String reason) {
            latchTransportDeath(new MessagingException(FailureCategory.PERMANENT,
                    "WebSocket closed by peer: " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            latchTransportDeath(new MessagingException(FailureCategory.TRANSIENT,
                    "WebSocket error: " + error.getClass().getSimpleName(), error));
        }
    }

    /**
     * Terminal-event handler shared by {@code Listener.onClose} and
     * {@code onError}. Pre-M1-674 these only drained pending futures, so a
     * peer-initiated close left {@code closed} false and the adapter kept
     * reporting a dead transport as healthy with nothing ever rebuilding
     * it (audit finding MSG-1). Now the death latches {@code closed}
     * (honest {@link #isClosed()}), tears the dispatcher down (the
     * connection is gone; dropping queued-but-undelivered inbound keeps
     * at-most-once inbound, but unlike the deliberate local-close teardown
     * the discarded depth is counted and logged), and fires the
     * transport-death notifier so the adapter drives a paced rebuild.
     * Pending futures drain first — a dispatch-thread consumer blocked in
     * sendCommand parks on one of them and must be released before its
     * executor is shut down, the same ordering {@link #close()} documents.
     *
     * <p>A local {@link #close()} sets {@code closed} before aborting the
     * socket, so a terminal event the abort may provoke is suppressed here
     * (not a peer death). The unsynchronized check-then-act race — close()
     * and a genuine peer event latching concurrently, double-firing the
     * notifier — is benign: the adapter's closed-for-good guard makes the
     * extra notification a no-op, and the JDK delivers at most one
     * terminal event per connection.</p>
     */
    private void latchTransportDeath(MessagingException drainCause) {
        boolean peerInitiated = !closed;
        closed = true;
        failAllPending(drainCause);
        if (peerInitiated) {
            // The returned runnables are the queued-but-undelivered inbound
            // deliveries. Dropping them is the at-most-once stance, but a
            // transport death is an outage, not a shutdown, so the drop is
            // counted (the readiness payload's dropped-inbound field reads
            // this counter) and logged: an operator can tell "the death
            // erased accepted work" from "nobody sent anything". Count only —
            // no message content, no contact ids (D37).
            int discarded = dispatchExecutor.shutdownNow().size();
            if (discarded > 0) {
                long total = droppedInboundCount.addAndGet(discarded);
                LOG.warn("transport death discarded {} queued inbound deliveries (total dropped {})",
                        discarded, total);
            }
            transportDeathNotifier.run();
        }
    }

    private void dispatch(String frame) {
        SimpleXMessageCodec.DecodedFrame decoded;
        try {
            decoded = SimpleXMessageCodec.decode(frame);
        } catch (SimpleXMessageCodec.MalformedFrameException e) {
            // No interpolation of the exception message or the frame
            // contents — security.md §User content in exceptions: the
            // application logger MUST NOT carry user-authored prose.
            // The exception's message is fixed by codec contract, so the
            // log site stays defense-in-depth by not echoing it either.
            LOG.warn("simplex-chat sent a malformed frame, skipping");
            return;
        }
        switch (decoded) {
            case SimpleXMessageCodec.Inbound in ->
                    dispatchAsync(in.message().sender().contactId(),
                            () -> inboundConsumer.onInbound(in.message()));
            case SimpleXMessageCodec.GroupCandidate gc ->
                    dispatchAsync(gc.senderContactId(),
                            () -> groupCandidateConsumer.onGroupCandidate(gc));
            // Hop off the listener thread (the consumer reads the DB and may
            // issue an outbound join) and key the FIFO ordering on the inviter,
            // mirroring the GroupCandidate dispatch (M1-515).
            case SimpleXMessageCodec.ReceivedGroupInvitation inv ->
                    dispatchAsync(inv.inviterContactId(),
                            () -> groupInvitationConsumer.onGroupInvitation(inv));
            case SimpleXMessageCodec.SendAck ack -> completePending(ack.corrId(), ack.chatItemId());
            // Completes on the listener thread exactly like SendAck — the
            // caller may be the single inbound-dispatch thread blocked in
            // sendCommand (a handler resolving the bot address synchronously
            // from onMessage), so queueing this behind it via dispatchAsync
            // would deadlock the same-adapter query. The link value flows only
            // into the pending future, never a log line (D37).
            case SimpleXMessageCodec.ContactAddress addr ->
                    completePending(addr.corrId(), addr.contactLink());
            case SimpleXMessageCodec.CommandError err -> failPending(err);
            case SimpleXMessageCodec.OversizeDropped od -> {
                // §6.3.10 transport size-cap shed: silent at the boundary (no
                // reply — emitting one below the Provider rate cap reopens the
                // DoS-amplification surface), but observable. Sender is
                // redacted before logging (D37: queue addresses never raw);
                // adapterMessageId is a server id, safe to log.
                metrics.inboundDropped(ADAPTER_NAME, od.scope(), AdapterMetrics.DropReason.OVERSIZE);
                LOG.warn("inbound dropped — exceeds {}-byte size cap; from {} adapterMessageId {}",
                        SimpleXMessageCodec.MAX_INBOUND_TEXT_BYTES,
                        ContactIdRedactor.redact(od.senderContactId()), od.adapterMessageId());
            }
            case SimpleXMessageCodec.Ignored ignored ->
                    LOG.debug("simplex-chat frame ignored: {}", ignored.reason());
        }
    }

    /**
     * Hand one inbound delivery to the dispatch thread. Acks bypass
     * this hop (they complete pending futures directly on the listener
     * thread) — routing them through the queue would re-introduce the
     * deadlock this hop exists to break: a consumer blocked in the
     * dispatch thread would sit ahead of its own ack.
     *
     * <p>{@code senderContactId} is used only to attribute an overflow
     * drop in the WARN line; it is redacted before logging.</p>
     */
    private void dispatchAsync(String senderContactId, Runnable delivery) {
        try {
            dispatchExecutor.execute(delivery);
        } catch (RejectedExecutionException e) {
            if (dispatchExecutor.isShutdown()) {
                // close() or a transport death shut the dispatcher down while
                // the listener was delivering its final frames; dropping is
                // correct, the connection is going away (at-most-once inbound).
                LOG.debug("inbound frame dropped — dispatcher shut down");
                return;
            }
            // The bounded dispatch queue is full: inbound is arriving faster
            // than the single dispatch thread can drain it. Drop the newest
            // delivery (the one that could not be enqueued) and count it,
            // rather than let the queue grow without bound and OOM the only
            // user-facing service (docs/design/06-messaging.md §6.3.7).
            long dropped = droppedInboundCount.incrementAndGet();
            // §6.3.7 overflow shed. scope_kind is "unknown" (null scope): the
            // drop fires at enqueue, decoupled from the decoded dm/group scope.
            metrics.inboundDropped(ADAPTER_NAME, null, AdapterMetrics.DropReason.QUEUE_FULL);
            LOG.warn("inbound dispatch queue full (cap {}); dropped newest from {} (total dropped {})",
                    inboundQueueCapacity, ContactIdRedactor.redact(senderContactId), dropped);
        }
    }

    private void completePending(String corrId, String chatItemId) {
        CompletableFuture<String> future = pending.remove(corrId);
        if (future == null) {
            // No matching pending command — either a server-async event we do
            // not handle yet, or the caller has already given up. Drop.
            LOG.debug("no pending command for ack corrId={}", corrId);
            return;
        }
        future.complete(chatItemId);
    }

    private void failPending(SimpleXMessageCodec.CommandError err) {
        CompletableFuture<String> future = pending.remove(err.corrId());
        MessagingException ex = new MessagingException(err.category(),
                "simplex-chat error: " + err.detail());
        if (future == null) {
            LOG.debug("no pending command for error corrId={}: {}", err.corrId(), err.detail());
            return;
        }
        future.completeExceptionally(ex);
    }

    private void failAllPending(MessagingException ex) {
        var snapshot = Map.copyOf(pending);
        pending.clear();
        for (var future : snapshot.values()) {
            future.completeExceptionally(ex);
        }
    }
}
