package app.zcat.infochat.collector.stream;

import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.ingest.StreamSource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Per-source bookkeeping the {@link StreamSourceSupervisor} holds for one
 * registered {@link StreamSource}: the identity needed to start and drain
 * the worker, the {@link Future} of the asynchronously-submitted
 * {@code start()} call, and the per-source "events lost on shutdown"
 * counter.
 *
 * <p>Package-private — the supervisor is the only legitimate holder. The
 * mutable fields are touched from the supervisor's calling thread and the
 * drain thread, so they are guarded by {@code volatile}/atomic rather than
 * a lock.</p>
 */
final class StreamSourceRegistration {

    private final StreamDispatchKey dispatchKey;
    private final String filterSpec;
    private final StreamSource source;
    private final Consumer<NormalizedPost> deliver;
    private final AtomicLong eventsLostOnShutdown = new AtomicLong();

    // Assigned in startOn() (called once, right after construction); null only
    // in the pre-start window, which stop() null-guards. NullAway.Init: the
    // field is logically non-null once the source is started.
    @SuppressWarnings("NullAway.Init")
    private volatile Future<?> startFuture;

    StreamSourceRegistration(StreamDispatchKey dispatchKey, String filterSpec,
                             StreamSource source, Consumer<NormalizedPost> deliver) {
        this.dispatchKey = dispatchKey;
        this.filterSpec = filterSpec;
        this.source = source;
        this.deliver = deliver;
    }

    StreamDispatchKey dispatchKey() {
        return dispatchKey;
    }

    long eventsLostOnShutdown() {
        return eventsLostOnShutdown.get();
    }

    /**
     * Submit the wrapped source's {@code start()} to {@code executor}. The
     * supervisor calls this once, immediately after constructing the
     * registration; the registration is considered started the moment the
     * task is submitted, NOT when {@code start()} returns — async startup
     * means a relay unreachable at boot never blocks the caller.
     */
    void startOn(ExecutorService executor) {
        // Unwrap the typed handle to the bare long at the StreamSource SPI
        // boundary: start() stamps the key onto every delivered post and core
        // persists it as a long. The handle is a supervisor-side wrapper only
        // and must not cross into infochat-core (M1-371).
        startFuture = executor.submit(() -> source.start(dispatchKey.value(), filterSpec, deliver));
    }

    /**
     * Submit the wrapped source's {@code stop()} to {@code executor} and
     * return a handle the supervisor awaits within the drain budget.
     * {@code stop()} is where the impl flushes in-flight events to the
     * outbox (architecture.md §Ingest SPIs), so draining IS calling
     * {@code stop()} and waiting for it to return.
     */
    StreamSourceDrainHandle beginDrain(ExecutorService executor) {
        Future<?> stopFuture = executor.submit(source::stop);
        return new StreamSourceDrainHandle(this, stopFuture);
    }

    /** Cancel the start worker, then stop the source synchronously. */
    void stopNow() {
        if (startFuture != null) {
            startFuture.cancel(true);
        }
        source.stop();
    }

    /**
     * Record that this source did not flush within the drain budget. The
     * supervisor cannot observe the impl's in-flight buffer size through
     * the base {@link StreamSource} SPI ({@code stop()} returns void), so
     * the counter counts drain timeouts — one increment per shutdown on
     * which this source failed to flush — which the operator reads as
     * "this source lost in-flight events on this shutdown". Per-event
     * accuracy is the impl's job on reconnect (architecture.md §Ingest
     * SPIs, the {@code since=last_persisted_event_at} gap).
     */
    void recordDrainTimeout() {
        eventsLostOnShutdown.incrementAndGet();
    }
}
