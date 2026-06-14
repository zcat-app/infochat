package app.zcat.infochat.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

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
 * clock and drive {@link #reserveWaitNanos} (the pure, no-sleep core)
 * directly to assert pacing deterministically.</p>
 *
 * <p><b>Nanosecond accounting.</b> The per-token interval is held in
 * nanoseconds, not milliseconds: a millisecond interval integer-divides
 * to 1&nbsp;ms/token for any cap &gt; 1000/s, silently flooring a
 * 10000/s cap to 1000/s (M1-359). Nanoseconds give the bucket the
 * resolution to honour caps an order of magnitude above 1000/s — the
 * {@code InMemoryAdapter}'s 10000/s declares "effectively unlimited",
 * and a sub-millisecond per-token interval is the only way to mean it.</p>
 */
public final class OutboundRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long perTokenNanos;
    private final Clock clock;
    private final Object lock = new Object();

    // Total tokens reserved (one per reserveWaitNanos / acquire). Visible
    // for tests that pin the "one token per wire frame" contract by
    // counting draws across a send/update/fallback sequence (M1-359).
    private final AtomicLong acquiredCount = new AtomicLong();

    // Epoch-nanos at which the bucket next hands out a token. Guarded by
    // {@code lock}. It may lag up to one whole second (= one full bucket
    // of cap tokens) behind "now" — that lag is the burst credit;
    // reserveWaitNanos() clamps it so idle never accrues more than a
    // full bucket.
    private long nextFreeNanos;

    public OutboundRateLimiter(int maxSendsPerSecond, Clock clock) {
        // System boundary: the cap originates from a CapabilityFlags value
        // an adapter declares; a non-positive rate is a misconfiguration
        // that would divide-by-zero (cap == 0) or invert the bucket
        // (cap < 0), so reject it at construction rather than pace nonsense.
        if (maxSendsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "maxSendsPerSecond must be positive: " + maxSendsPerSecond);
        }
        // Round the per-token interval UP (ceil of NANOS_PER_SECOND / cap):
        // a floor would let the realized rate creep ABOVE the declared
        // ceiling, and this pacer exists to stay under the transport's
        // server-side limit, so erring slow by at most one nanosecond is
        // the safe direction. For the exact-divisor production caps (5) the
        // ceil is exact; it only rounds for non-divisor caps.
        this.perTokenNanos = Math.max(1L,
                (NANOS_PER_SECOND + maxSendsPerSecond - 1) / maxSendsPerSecond);
        this.clock = clock;
        // Start full: the cursor sits one whole second (minus one slot)
        // behind construction time, so the first `maxSendsPerSecond`
        // acquires grant immediately.
        this.nextFreeNanos = nowNanos() - NANOS_PER_SECOND + perTokenNanos;
    }

    /**
     * Draw one token, blocking the calling thread until it is available
     * (a bounded park, &le; ~1s/cap). Interruption restores the flag and
     * returns — the token is already reserved and the pacing wait is a
     * transport courtesy, not a correctness gate.
     */
    public void acquire() {
        long waitNanos = reserveWaitNanos();
        if (waitNanos <= 0L) {
            return;
        }
        try {
            // Duration overload (not Thread.sleep(long)) so a sub-millisecond
            // per-token interval at high caps is honoured at nanosecond
            // resolution instead of being rounded to a whole millisecond.
            Thread.sleep(Duration.ofNanos(waitNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reserve the next token and return how long the caller must wait
     * (nanos) before it is live; {@code 0} when a token is available
     * now. Mutates the bucket cursor but never sleeps, so tests drive it
     * against a controllable {@link Clock} to assert "the first cap calls
     * are free, the next paces, and the budget refills after one second".
     */
    long reserveWaitNanos() {
        long now = nowNanos();
        synchronized (lock) {
            // Clamp the burst credit: the cursor may sit at most one full
            // bucket behind now, so an idle limiter never mints more than
            // `cap` tokens at once.
            long burstFloor = now - NANOS_PER_SECOND + perTokenNanos;
            if (nextFreeNanos < burstFloor) {
                nextFreeNanos = burstFloor;
            }
            long grantAt = nextFreeNanos;
            nextFreeNanos = grantAt + perTokenNanos;
            acquiredCount.incrementAndGet();
            return Math.max(0L, grantAt - now);
        }
    }

    /** The per-token interval in nanoseconds. Visible for tests. */
    long perTokenNanos() {
        return perTokenNanos;
    }

    /**
     * Total tokens reserved so far. {@code public} only because the
     * cross-package Signal fallback test ({@code impl.signal}) reads it to
     * pin that one fallen-back update draws two tokens (M1-359); no
     * production code consumes it.
     */
    public long acquiredCount() {
        return acquiredCount.get();
    }

    // Epoch-nanos from the seam Clock. clock.instant() carries nanosecond
    // resolution (the TestClock returns an exact Instant), so the bucket
    // can pace below the one-millisecond floor clock.millis() imposed.
    private long nowNanos() {
        Instant now = clock.instant();
        return now.getEpochSecond() * NANOS_PER_SECOND + now.getNano();
    }
}
