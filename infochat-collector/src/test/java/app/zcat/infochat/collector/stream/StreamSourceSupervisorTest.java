package app.zcat.infochat.collector.stream;

import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.ingest.StreamSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StreamSourceSupervisor} using the
 * package-private {@link FakeStreamSource} double. No Quarkus container —
 * the supervisor's test constructor runs init() eagerly.
 */
class StreamSourceSupervisorTest {

    @Test
    void registerStartsBackgroundWorker() throws InterruptedException {
        StreamSourceSupervisor supervisor = new StreamSourceSupervisor(Duration.ofSeconds(1));
        CountDownLatch release = new CountDownLatch(1);
        FakeStreamSource source = FakeStreamSource.blockingStart(release);

        supervisor.register(7L, "spec", source, post -> { });

        // start() entered the worker but is still blocked on the release
        // latch — proves register() returned without waiting for start().
        assertTrue(source.startEntered.await(2, TimeUnit.SECONDS), "start() should have begun");
        assertTrue(supervisor.isReady(), "supervisor ready before start() completes");

        release.countDown();
    }

    @Test
    void drainFlushesWithinTimeout() throws InterruptedException {
        StreamSourceSupervisor supervisor = new StreamSourceSupervisor(Duration.ofSeconds(1));
        List<NormalizedPost> buffered = List.of(
                FakeStreamSource.samplePost("a"), FakeStreamSource.samplePost("b"));
        FakeStreamSource source = FakeStreamSource.flushingOnStop(buffered);
        List<NormalizedPost> delivered = new CopyOnWriteArrayList<>();

        supervisor.register(3L, "spec", source, delivered::add);
        assertTrue(source.startEntered.await(2, TimeUnit.SECONDS), "deliver callback wired by start()");

        Map<Long, Boolean> outcomes = supervisor.drainAll(Duration.ofSeconds(1));

        assertEquals(Boolean.TRUE, outcomes.get(3L), "source flushed within the budget");
        assertEquals(2, delivered.size(), "both buffered events flushed to the deliver callback");
        assertEquals(0L, supervisor.eventsLostOnShutdown(3L), "clean drain loses no events");
    }

    @Test
    void drainTimeoutDropsInFlight() throws InterruptedException {
        StreamSourceSupervisor supervisor = new StreamSourceSupervisor(Duration.ofSeconds(1));
        FakeStreamSource source = FakeStreamSource.slowStop(10_000L);

        supervisor.register(9L, "spec", source, post -> { });
        assertTrue(source.startEntered.await(2, TimeUnit.SECONDS));

        Map<Long, Boolean> outcomes = supervisor.drainAll(Duration.ofMillis(100));

        assertEquals(Boolean.FALSE, outcomes.get(9L), "source did not flush within the budget");
        assertEquals(1L, supervisor.eventsLostOnShutdown(9L), "lost-events counter incremented on drain timeout");
    }

    @Test
    void stopSingleSource() throws InterruptedException {
        StreamSourceSupervisor supervisor = new StreamSourceSupervisor(Duration.ofSeconds(1));
        FakeStreamSource one = FakeStreamSource.inert();
        FakeStreamSource two = FakeStreamSource.inert();

        supervisor.register(1L, "spec", one, post -> { });
        supervisor.register(2L, "spec", two, post -> { });
        assertTrue(one.startEntered.await(2, TimeUnit.SECONDS));
        assertTrue(two.startEntered.await(2, TimeUnit.SECONDS));

        supervisor.stop(1L);

        assertTrue(one.stopCalled(), "stopped source's stop() invoked");
        assertFalse(two.stopCalled(), "other source left running");
    }
}

/**
 * Controllable {@link StreamSource} test double, shared by the supervisor
 * unit test and IT. Top-level package-private (not an inner class) per the
 * module's test-double convention. Each knob defaults to "inert"; a factory
 * sets only the behavior a test exercises.
 */
final class FakeStreamSource implements StreamSource {

    /**
     * Counted down on entry to {@code start()}, before it blocks — lets a
     * test assert the worker actually began without racing {@code start()}'s
     * return.
     */
    final CountDownLatch startEntered = new CountDownLatch(1);

    // start() blocks on this until released — models a long-lived worker
    // that has not finished establishing its subscription.
    private final CountDownLatch startRelease;

    // Events the source flushes through the deliver callback when stop() runs.
    private final List<NormalizedPost> bufferedEvents;

    // How long stop() takes before returning — a value past the drain
    // budget models a source that fails to flush in time.
    private final long stopDelayMillis;

    private final AtomicBoolean stopCalled = new AtomicBoolean(false);
    private volatile Consumer<NormalizedPost> deliver;

    private FakeStreamSource(CountDownLatch startRelease, List<NormalizedPost> bufferedEvents, long stopDelayMillis) {
        this.startRelease = startRelease;
        this.bufferedEvents = bufferedEvents;
        this.stopDelayMillis = stopDelayMillis;
    }

    /** A source whose {@code start()} blocks until {@code startRelease} fires. */
    static FakeStreamSource blockingStart(CountDownLatch startRelease) {
        return new FakeStreamSource(startRelease, List.of(), 0L);
    }

    /** A source whose {@code stop()} flushes {@code bufferedEvents} and returns at once. */
    static FakeStreamSource flushingOnStop(List<NormalizedPost> bufferedEvents) {
        return new FakeStreamSource(new CountDownLatch(0), bufferedEvents, 0L);
    }

    /** A source whose {@code stop()} sleeps {@code stopDelayMillis} — used to force a drain timeout. */
    static FakeStreamSource slowStop(long stopDelayMillis) {
        return new FakeStreamSource(new CountDownLatch(0), List.of(), stopDelayMillis);
    }

    /** A source that starts and stops immediately with nothing to flush. */
    static FakeStreamSource inert() {
        return new FakeStreamSource(new CountDownLatch(0), List.of(), 0L);
    }

    static NormalizedPost samplePost(String upstreamIdentifier) {
        return new NormalizedPost(1L, upstreamIdentifier, null, "body", null, null, Instant.now(), Map.of());
    }

    @Override
    public void start(long sourceId, @NonNull String filterSpec, @NonNull Consumer<NormalizedPost> deliver) {
        this.deliver = deliver;
        startEntered.countDown();
        try {
            startRelease.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        stopCalled.set(true);
        if (stopDelayMillis > 0L) {
            try {
                Thread.sleep(stopDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // interrupted drain: abandon the flush, events dropped
            }
        }
        Consumer<NormalizedPost> sink = deliver;
        if (sink != null) {
            for (NormalizedPost event : bufferedEvents) {
                sink.accept(event);
            }
        }
    }

    boolean stopCalled() {
        return stopCalled.get();
    }
}
