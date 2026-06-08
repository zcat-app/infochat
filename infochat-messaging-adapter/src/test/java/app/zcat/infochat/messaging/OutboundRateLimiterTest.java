package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Plain JUnit — drives {@link OutboundRateLimiter#reserveWaitMillis()}
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
            assertEquals(0L, limiter.reserveWaitMillis(),
                    "the first " + CAP + " sends in one second transmit without pacing (i=" + i + ")");
        }
    }

    @Test
    void sendPastCapPaces() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        OutboundRateLimiter limiter = new OutboundRateLimiter(CAP, clock);

        for (int i = 0; i < CAP; i++) {
            limiter.reserveWaitMillis();
        }
        assertTrue(limiter.reserveWaitMillis() > 0L,
                "the (cap+1)-th send within the same second must wait for a token");
    }

    @Test
    void budgetRefillsAfterOneSecond() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        OutboundRateLimiter limiter = new OutboundRateLimiter(CAP, clock);

        for (int i = 0; i < CAP; i++) {
            limiter.reserveWaitMillis();
        }
        assertTrue(limiter.reserveWaitMillis() > 0L, "bucket drained within the second");

        clock.advance(Duration.ofSeconds(1));

        assertEquals(0L, limiter.reserveWaitMillis(),
                "after a full second the budget refills and the next send transmits without pacing");
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
