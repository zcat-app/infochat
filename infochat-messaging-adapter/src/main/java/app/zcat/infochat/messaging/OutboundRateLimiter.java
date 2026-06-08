package app.zcat.infochat.messaging;

import java.time.Clock;

/**
 * Outbound send pacer — a token bucket that throttles one production
 * {@link MessagingAdapter}'s outbound transmits to its declared
 * {@link CapabilityFlags#maxSendsPerSecond}. This is transport
 * self-protection: it keeps the Provider from driving SimpleX's /
 * Signal's servers fast enough to trip their server-side rate limits or
 * flag the bot. It sits strictly <em>underneath</em> the Provider's
 * per-user rate limiter and is never a second user-facing throttle
 * ({@code docs/spec/messaging.md} §Failure handling — the per-user
 * limiter is the single source of truth for "slow this user down";
 * {@code docs/design/06-messaging.md} §6.3.6).
 *
 * <p>One instance per adapter, shared across {@code send} /
 * {@code update} / {@code finalizeMessage}: every outbound frame draws
 * one token. The Provider chunks oversize messages, so one SPI call is
 * one frame (§6.2.2). The bucket starts full, so a burst up to the
 * per-second cap transmits without delay; sustained sending past the
 * cap paces to the configured rate. On an empty bucket {@link #acquire}
 * blocks the calling thread for the sub-second interval until the next
 * token accrues — the adapter send path is already synchronous-blocking
 * (it awaits the transport ack) and runs on Provider virtual threads
 * (blocking style, CLAUDE.md §Stack), so a bounded park is the "block
 * briefly" §6.3.6 sanctions.</p>
 *
 * <p>Outbound concurrency is bounded separately by the transport itself
 * (one outstanding send per connection, §6.4 / §6.5), not by this
 * limiter — this limiter caps the rate, not the in-flight count.</p>
 *
 * <p><b>Clock seam.</b> The constructor takes a {@link Clock};
 * production passes {@link Clock#systemUTC()}, tests pass a controllable
 * clock and drive {@link #reserveWaitMillis} (the pure, no-sleep core)
 * directly to assert pacing deterministically.</p>
 */
public final class OutboundRateLimiter {

    private final long perTokenMillis;
    private final Clock clock;
    private final Object lock = new Object();

    // Epoch-millis at which the bucket next hands out a token. Guarded by
    // {@code lock}. It may lag up to one whole second (= one full bucket
    // of cap tokens) behind "now" — that lag is the burst credit;
    // reserveWaitMillis() clamps it so idle never accrues more than a
    // full bucket.
    private long nextFreeMillis;

    public OutboundRateLimiter(int maxSendsPerSecond, Clock clock) {
        this.perTokenMillis = Math.max(1L, 1_000L / maxSendsPerSecond);
        this.clock = clock;
        // Start full: the cursor sits one whole second (minus one slot)
        // behind construction time, so the first `maxSendsPerSecond`
        // acquires grant immediately.
        this.nextFreeMillis = clock.millis() - 1_000L + perTokenMillis;
    }

    /**
     * Draw one token, blocking the calling thread until it is available
     * (a bounded park, &le; ~1s/cap). Interruption restores the flag and
     * returns — the token is already reserved and the pacing wait is a
     * transport courtesy, not a correctness gate.
     */
    public void acquire() {
        long waitMillis = reserveWaitMillis();
        if (waitMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reserve the next token and return how long the caller must wait
     * (millis) before it is live; {@code 0} when a token is available
     * now. Mutates the bucket cursor but never sleeps, so tests drive it
     * against a controllable {@link Clock} to assert "the first cap calls
     * are free, the next paces, and the budget refills after one second".
     */
    long reserveWaitMillis() {
        long now = clock.millis();
        synchronized (lock) {
            // Clamp the burst credit: the cursor may sit at most one full
            // bucket behind now, so an idle limiter never mints more than
            // `cap` tokens at once.
            long burstFloor = now - 1_000L + perTokenMillis;
            if (nextFreeMillis < burstFloor) {
                nextFreeMillis = burstFloor;
            }
            long grantAt = nextFreeMillis;
            nextFreeMillis = grantAt + perTokenMillis;
            return Math.max(0L, grantAt - now);
        }
    }
}
