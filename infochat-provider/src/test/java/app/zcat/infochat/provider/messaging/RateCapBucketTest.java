package app.zcat.infochat.provider.messaging;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit (no {@code @QuarkusTest}) — bypasses CDI to instantiate
 * {@link RateCapBucket} directly with a controllable {@link TestClock}
 * (defined as a static inner class). Four invariants are pinned:
 *
 * <ol>
 *   <li>{@code underCap} — first N inbounds for one {@code (adapter,
 *       contactId)} tuple return {@code true}.</li>
 *   <li>{@code overCap} — the (N+1)-th inbound from the same tuple
 *       returns {@code false}.</li>
 *   <li>{@code independent} — two distinct tuples are bucketed
 *       independently; saturating one does not affect the other.</li>
 *   <li>{@code refill} — after the refill window elapses the bucket
 *       admits another N inbounds.</li>
 * </ol>
 */
class RateCapBucketTest {

    private static final int CAP = 3;
    private static final Duration REFILL_WINDOW = Duration.ofMinutes(1);
    private static final Duration EVICTION_THRESHOLD = Duration.ofMinutes(10);

    @Test
    void underCap() {
        TestClock clock = new TestClock(Instant.parse("2026-05-20T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "rate-1"),
                    "the first " + CAP + " calls must succeed (i=" + i + ")");
        }
    }

    @Test
    void overCap() {
        TestClock clock = new TestClock(Instant.parse("2026-05-20T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "rate-1"));
        }
        assertFalse(bucket.tryAcquire("inmemory", "rate-1"),
                "the (CAP+1)-th call without time advance must return false");
    }

    @Test
    void independent() {
        TestClock clock = new TestClock(Instant.parse("2026-05-20T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        // Interleave two contact ids — each must respect its own cap.
        // Drain rate-A; rate-B must still admit CAP calls afterwards.
        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "rate-A"));
        }
        assertFalse(bucket.tryAcquire("inmemory", "rate-A"),
                "rate-A's bucket is drained");

        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "rate-B"),
                    "rate-B's bucket must be untouched by rate-A drain (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquire("inmemory", "rate-B"),
                "rate-B is also drained after its own CAP calls");
    }

    @Test
    void refill() {
        TestClock clock = new TestClock(Instant.parse("2026-05-20T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        // Drain the bucket.
        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "rate-1"));
        }
        assertFalse(bucket.tryAcquire("inmemory", "rate-1"),
                "bucket drained; next call without refill must fail");

        // Advance the clock past the refill window — CAP tokens added back.
        clock.advance(REFILL_WINDOW);

        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "rate-1"),
                    "after a full refill window elapses, CAP calls succeed again (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquire("inmemory", "rate-1"),
                "the post-refill bucket is also bounded at CAP");
    }

    /**
     * Test seam — a mutable Clock whose {@link #millis()} reading
     * advances only when the test calls {@link #advance(Duration)}.
     * Static inner class per the M1-044a ticket's "no separate
     * TestClock.java" constraint.
     */
    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant initial) {
            this.now = initial;
        }

        void advance(Duration delta) {
            this.now = this.now.plus(delta);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public long millis() {
            return now.toEpochMilli();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
