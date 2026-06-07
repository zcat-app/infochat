package app.zcat.infochat.collector.stream;


import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handle on one source's in-progress drain. {@link
 * StreamSourceRegistration#beginDrain} submits the source's {@code stop()}
 * and wraps the resulting {@link Future} here; the {@link
 * StreamSourceSupervisor} then awaits every handle against a single shared
 * deadline so {@code drainAll}'s timeout bounds the TOTAL drain, not each
 * source independently.
 */
final class StreamSourceDrainHandle {

    private final StreamSourceRegistration registration;
    private final Future<?> stopFuture;

    StreamSourceDrainHandle(StreamSourceRegistration registration, Future<?> stopFuture) {
        this.registration = registration;
        this.stopFuture = stopFuture;
    }

    long sourceId() {
        return registration.sourceId();
    }

    /**
     * Wait for {@code stop()} to return, but no later than {@code deadline}.
     * Returns {@code true} if the source flushed and stopped in time. On
     * timeout, cancels the stop worker, records the drain timeout on the
     * registration (incrementing its lost-events counter), and returns
     * {@code false} — the in-flight events are dropped per architecture.md
     * §Ingest SPIs ("Events not drained within a profile-driven hard
     * timeout are dropped").
     *
     * <p>A {@code stop()} that throws is treated as a failed drain (counter
     * incremented) rather than propagated: shutdown must keep draining the
     * remaining sources even if one source's teardown blows up.</p>
     */
    boolean awaitUntil(Instant deadline) {
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        try {
            stopFuture.get(Math.max(0L, remainingMillis), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            stopFuture.cancel(true);
            registration.recordDrainTimeout();
            return false;
        } catch (ExecutionException e) {
            registration.recordDrainTimeout();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            registration.recordDrainTimeout();
            return false;
        }
    }
}
