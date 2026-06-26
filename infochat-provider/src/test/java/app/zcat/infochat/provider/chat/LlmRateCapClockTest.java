package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a fixed instant and asserts that
 * the per-user sliding-window decision in {@link LlmRateCap#tryAcquire}
 * is computed against the injected clock, not the wall clock (M1-460,
 * CLAUDE.md §9 injectable-time rule). Without a controllable clock the
 * post-60 s refill boundary cannot be exercised deterministically — it
 * would depend on real elapsed wall-clock time.
 */
class LlmRateCapClockTest {

    private static final Instant START = Instant.parse("2026-06-26T12:00:00Z");

    @Test
    void slidingWindowDecisionIsPinnedToInjectedClock() {
        int capPerMinute = 3;
        LlmRateCap rateCap = new LlmRateCap(capPerMinute);
        TestClock clock = new TestClock(START);
        rateCap.clock = clock;

        UUID userId = UUID.randomUUID();

        // At the fixed instant: capPerMinute acquires succeed.
        for (int i = 0; i < capPerMinute; i++) {
            assertTrue(rateCap.tryAcquire(userId),
                    "acquire " + i + " within the cap must succeed");
        }

        // The next one is over-cap at the same instant -> rejected.
        assertFalse(rateCap.tryAcquire(userId),
                "the (cap+1)th acquire at the same instant must be rejected");

        // Advance the pinned clock past the 60 s window: the earlier
        // timestamps fall outside windowStart and are pruned, so a
        // further acquire succeeds — all decided against the injected
        // instant, never the wall clock.
        clock.advance(Duration.ofSeconds(61));
        assertTrue(rateCap.tryAcquire(userId),
                "an acquire after the 60 s window has elapsed must succeed");
    }

    /**
     * Mutable test clock whose reading advances only on {@link #advance}.
     * Static inner class, mirroring {@code RateCapBucketTest.TestClock}.
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
