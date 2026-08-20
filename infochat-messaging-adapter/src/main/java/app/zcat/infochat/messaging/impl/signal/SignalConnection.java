package app.zcat.infochat.messaging.impl.signal;

import org.jspecify.annotations.Nullable;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;

import java.io.BufferedWriter;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One JSON-RPC transport connection's state, owned by
 * {@link SignalJsonRpcClient} and swapped as a unit by
 * {@code connect()}.
 *
 * <p><b>Why a carrier object and not fields on the client (M1-681).</b>
 * {@code SignalJsonRpcClient} is ONE instance that spans reconnects,
 * while {@code SimpleXAdapter} builds a fresh
 * {@code SimpleXWebSocketClient} per rebuild. With the transport state
 * held directly on the client, a reader thread that outlived
 * {@code disconnect()}'s bounded 2 s join could still reach fields a
 * later {@code connect()} had already replaced — latching a live
 * connection dead, draining its in-flight calls closed-before-ack and
 * shutting down its dispatcher. Handing the reader a reference to this
 * object instead makes its reach structurally equal to its own
 * connection: there is no path from here to a successor. SimpleX gets
 * that property free from its per-rebuild lifecycle; Signal has to
 * state it.</p>
 *
 * <p>The one effect this scoping cannot cover is the supervised-restart
 * hook, because the signal-cli subprocess is shared across connections
 * rather than owned by one. {@link SignalJsonRpcClient} therefore
 * re-checks connection ownership before firing it; see
 * {@code latchTransportDeath}.</p>
 *
 * <p>Not thread-safe as a whole, and does not need to be: every field is
 * immutable, individually thread-safe, or a volatile flag — {@link #closed}
 * (death latch / disconnect), {@link #lastInboundActivity} + {@link #silenceWarned} (reader + probe).</p>
 */
final class SignalConnection {

    /** Bounded join used by {@code disconnect()} when retiring this connection. */
    static final long READER_JOIN_MS = TimeUnit.SECONDS.toMillis(2);

    final Socket socket;

    /**
     * The {@code SignalSubprocess} generation live when this connection was
     * built. The shared subprocess restart is gated on this still matching
     * the subprocess's current generation, so a reader whose child has
     * already been replaced cannot SIGKILL the healthy successor (M1-681).
     * 0 when no subprocess is wired (the convenience/test client
     * constructors), which makes the gate a no-op — every connection then
     * carries generation 0 and the check always passes, preserving the
     * plain fire-on-death behavior those constructors had.
     */
    final long daemonGeneration;

    /** Guarded by its own monitor — one writer per connection, many senders. */
    final BufferedWriter writer;

    /**
     * Per-connection inbound dispatch thread. Notifications hop off the
     * reader thread here so a blocking InboundHandler/MembershipHandler
     * cannot deadlock against the reader that delivers its ack;
     * responses never enter this queue.
     */
    final ExecutorService dispatchExecutor;

    /**
     * The dispatcher's backing queue, kept so tests can read its depth.
     * BOUNDED: a {@link ThreadPoolExecutor} with the default AbortPolicy
     * rejects {@code execute()} once it is full, which {@code
     * dispatchAsync} turns into a drop-newest with a counter — the rate
     * cap downstream of this queue bounds work per dequeued item, never
     * the queue's own memory (docs/design/06-messaging.md §6.3.7).
     */
    final BlockingQueue<Runnable> dispatchQueue;

    /** In-flight JSON-RPC calls on THIS connection, keyed by rpcId. */
    final ConcurrentMap<String, CompletableFuture<SignalMessageCodec.JsonRpcMessage>> pending =
            new ConcurrentHashMap<>();

    /**
     * Latched when this connection's transport is torn down — locally by
     * {@code disconnect()} (set BEFORE the socket close, so the reader's
     * exit reads as intentional and fires no restart) or by the reader's
     * own death latch on a peer-initiated death. Folded into
     * {@code isConnected()} so a dead JSON-RPC channel never reports
     * healthy while signal-cli keeps running.
     */
    volatile boolean closed;

    /**
     * At most one subprocess restart per connection death, whichever
     * detector observes it first. The consecutive-timeout escalation sets
     * this before firing (its restart SIGKILLs the child, which kills the
     * socket, which exits the reader — whose latch must treat the death as
     * already handled); the reader's latch fires only when it wins the CAS.
     * One-directional on purpose: the latch defers to the timeout path,
     * never the reverse, so the timeout counter logic stays intact.
     */
    final AtomicBoolean restartRequested = new AtomicBoolean();

    /** Instant of the last inbound NOTIFICATION frame — any receive shape re-stamps it; probe responses deliberately do not (see the client's liveness probe, messaging.md §Failure handling). */
    volatile Instant lastInboundActivity;

    /** WARN-once-per-crossing latch for the connected-but-silent WARN: set by the probe, cleared by the reader on inbound traffic. */
    volatile boolean silenceWarned;

    /**
     * Set by {@code connect()} immediately after construction — the thread
     * cannot be built before the connection it reads for. Nullable only
     * across that window.
     */
    @Nullable volatile Thread readerThread;

    SignalConnection(Socket socket, BufferedWriter writer, int inboundQueueCapacity,
                     long daemonGeneration, Instant initialLastInboundActivity) {
        this.socket = socket;
        this.writer = writer;
        this.daemonGeneration = daemonGeneration;
        this.lastInboundActivity = initialLastInboundActivity;
        this.dispatchQueue = new LinkedBlockingQueue<>(inboundQueueCapacity);
        this.dispatchExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, dispatchQueue,
                Thread.ofVirtual().name("signal-inbound-dispatch").factory());
    }

    /**
     * Fail every in-flight call on this connection. PERMANENT per the
     * {@link FailureCategory} classification matrix (closed-before-ack):
     * the connection that would have acked these calls is gone, so
     * retrying cannot succeed until the transport is rebuilt — the same
     * category SimpleX stamps when {@code close()} drains its pending
     * commands. {@code call()} unwraps this exception to preserve the
     * category.
     */
    void drainPending() {
        pending.forEach((id, f) -> f.completeExceptionally(
                new MessagingException(FailureCategory.PERMANENT,
                        "SignalJsonRpcClient disconnected before response (closed-before-ack)")));
        pending.clear();
    }

    /**
     * Stop the dispatcher, discarding queued-but-undelivered inbound —
     * this connection is going away, which is the at-most-once inbound
     * stance.
     *
     * @return how many queued deliveries were discarded, so a caller that
     *     is handling an outage (rather than a clean shutdown) can count
     *     and log them.
     */
    int shutdownDispatcher() {
        return dispatchExecutor.shutdownNow().size();
    }
}
