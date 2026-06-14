package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit — drives {@link OutboundRateLimiter#reserveWaitNanos()}
 * (the no-sleep core) against a controllable {@link TestClock} so the
 * pacing assertions are deterministic and fast (no real
 * {@code Thread.sleep}). Proves the design §6.3.6 maxSendsPerSecond
 * enforcement: a burst up to the cap transmits without pacing, the next
 * send within the same second must wait for a token, and a full second
 * later the budget refills.
 */
class OutboundRateLimiterTest {

    private static final int CAP = 5;

    @Test
    void burstUpToCapTransmitsWithoutWait() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        OutboundRateLimiter limiter = new OutboundRateLimiter(CAP, clock);

        for (int i = 0; i < CAP; i++) {
            assertEquals(0L, limiter.reserveWaitNanos(),
                    "the first " + CAP + " sends in one second transmit without pacing (i=" + i + ")");
        }
    }

    @Test
    void sendPastCapPaces() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        OutboundRateLimiter limiter = new OutboundRateLimiter(CAP, clock);

        for (int i = 0; i < CAP; i++) {
            limiter.reserveWaitNanos();
        }
        assertTrue(limiter.reserveWaitNanos() > 0L,
                "the (cap+1)-th send within the same second must wait for a token");
    }

    @Test
    void budgetRefillsAfterOneSecond() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        OutboundRateLimiter limiter = new OutboundRateLimiter(CAP, clock);

        for (int i = 0; i < CAP; i++) {
            limiter.reserveWaitNanos();
        }
        assertTrue(limiter.reserveWaitNanos() > 0L, "bucket drained within the second");

        clock.advance(Duration.ofSeconds(1));

        assertEquals(0L, limiter.reserveWaitNanos(),
                "after a full second the budget refills and the next send transmits without pacing");
    }

    @Test
    void highCapUsesSubMillisecondTokenIntervalNotOneThousandFloor() {
        // Pre-fix millisecond accounting floored any cap > 1000/s to
        // 1 ms/token = 1000/s. Nanosecond accounting honours the cap:
        // 10000/s is 100_000 ns/token, not the 1_000_000 ns (1 ms) a 1000/s
        // floor would impose (M1-359, acceptance item 2).
        OutboundRateLimiter limiter = new OutboundRateLimiter(
                10_000, new TestClock(Instant.parse("2026-06-08T00:00:00Z")));
        assertEquals(100_000L, limiter.perTokenNanos(),
                "a 10000/s cap must pace at 100_000 ns/token (10000/s), not 1_000_000 ns (1000/s)");
    }

    @Test
    void highCapBurstHoldsFullCapTokens() {
        // Achieved pacing: a fresh 10000/s bucket grants 10000 free tokens
        // in the starting burst — proof the realized rate is 10000/s, not
        // the 1000 the old millisecond floor allowed.
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        OutboundRateLimiter limiter = new OutboundRateLimiter(10_000, clock);

        for (int i = 0; i < 10_000; i++) {
            assertEquals(0L, limiter.reserveWaitNanos(),
                    "the first 10000 sends in one second transmit without pacing (i=" + i + ")");
        }
        assertTrue(limiter.reserveWaitNanos() > 0L,
                "the 10001st send within the same second must wait for a token");
    }

    @Test
    void productionCapOfFiveIsUnchanged() {
        // The exact-divisor production caps (SimpleX=5, Signal=5) keep their
        // pre-fix pacing: 5/s is 200_000_000 ns/token (the ceil is exact).
        OutboundRateLimiter limiter = new OutboundRateLimiter(
                CAP, new TestClock(Instant.parse("2026-06-08T00:00:00Z")));
        assertEquals(200_000_000L, limiter.perTokenNanos(),
                "a cap of 5 must still pace at 200 ms/token (5/s)");
    }

    @Test
    void nonPositiveCapIsRejected() {
        Clock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundRateLimiter(0, clock), "a zero cap must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundRateLimiter(-1, clock), "a negative cap must be rejected");
    }

    /**
     * Test seam — a mutable Clock whose {@link #millis()} reading
     * advances only when the test calls {@link #advance(Duration)}.
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
