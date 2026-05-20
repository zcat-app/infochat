package app.zcat.infochat.provider.messaging;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization step 1.5 inbound rate cap per
 * docs/spec/security.md §Rate limiting. The intake-step splice
 * (M1-044b) calls {@link #tryAcquire} immediately after the unicode
 * normalize + max-body gate and before any DB read, LLM, or
 * authorization branch. A return of {@code false} drops the inbound
 * with no further work — the spec's "drop counter only" promise.
 *
 * <p><b>Token-bucket shape.</b> Per-{@code (adapter, contactId)}
 * {@link ConcurrentHashMap} of {@link Bucket} entries; each entry
 * holds a token count and the wall-clock epoch-millis of its last
 * refill. {@link #tryAcquire} synchronizes on the bucket reference,
 * adds {@code elapsed_ms * cap / refillWindow_ms} tokens (capped at
 * {@code cap}), then decrements on success. The {@code synchronized
 * (bucket)} block plus the {@code computeIfAbsent} on the map gives
 * race-safe acquire under concurrent inbound from the same contact.
 * </p>
 *
 * <p><b>Memory bound.</b> A {@link Scheduled} sweep evicts idle
 * buckets — those whose token count is full AND whose
 * {@code lastRefillEpochMillis} is older than the eviction
 * threshold. The sweep runs in the Quarkus scheduler thread; eviction
 * cannot race a live acquire because the predicate is checked under
 * the same bucket monitor.</p>
 *
 * <p><b>Clock seam.</b> A constructor parameter — production uses
 * {@link Clock#systemUTC()} (the no-arg CDI constructor's default);
 * tests construct the bean directly with a controllable
 * {@code TestClock} (defined as a static inner class of
 * {@code RateCapBucketTest}). No CDI producer, no JDBC.</p>
 *
 * <p><b>State volatility.</b> Buckets reset on Provider restart. The
 * cap is a flood-bound, not a security boundary — an attacker who
 * survives a restart sees a fresh budget, which the spec tolerates
 * (the brute-force counter in {@code InviteCodeConsumer} is the
 * durable security signal; this bucket is the in-memory flood
 * signal).</p>
 */
@ApplicationScoped
public class RateCapBucket {

    @ConfigProperty(name = "infochat.rate-cap.inbound-per-minute", defaultValue = "60")
    int inboundPerMinute;

    @ConfigProperty(name = "infochat.rate-cap.refill-window", defaultValue = "PT1M")
    Duration refillWindow;

    @ConfigProperty(name = "infochat.rate-cap.eviction-threshold", defaultValue = "PT10M")
    Duration evictionThreshold;

    private Clock clock = Clock.systemUTC();

    private final ConcurrentHashMap<Key, Bucket> buckets = new ConcurrentHashMap<>();

    public RateCapBucket() {
        // CDI no-arg constructor; @ConfigProperty fields populated post-construction.
    }

    /**
     * Test seam. Plain-JUnit tests bypass CDI and instantiate the
     * bean with a controllable {@link Clock} and explicit
     * cap/window/eviction values. Package-private — the rest of the
     * provider tree consumes the bean via CDI.
     */
    RateCapBucket(Clock clock, int inboundPerMinute, Duration refillWindow, Duration evictionThreshold) {
        this.clock = clock;
        this.inboundPerMinute = inboundPerMinute;
        this.refillWindow = refillWindow;
        this.evictionThreshold = evictionThreshold;
    }

    /**
     * @return {@code true} iff the per-{@code (adapter, contactId)}
     *         bucket has at least one token after refill; the call
     *         decrements one token on success. {@code false} on
     *         empty bucket — the caller drops the inbound.
     */
    public boolean tryAcquire(String adapter, String contactId) {
        Key key = new Key(adapter, contactId);
        Bucket bucket = buckets.computeIfAbsent(
                key, k -> new Bucket(inboundPerMinute, clock.millis()));
        synchronized (bucket) {
            long now = clock.millis();
            long elapsed = Math.max(0L, now - bucket.lastRefillEpochMillis);
            long windowMs = refillWindow.toMillis();
            // Continuous refill: cap tokens added per refillWindow elapsed.
            // refillCount = elapsed * cap / windowMs; advance lastRefill by
            // exactly the time consumed by those tokens so leftover sub-token
            // elapsed carries forward to the next call.
            long refillCount = elapsed * (long) inboundPerMinute / windowMs;
            if (refillCount > 0) {
                bucket.tokens = (int) Math.min((long) inboundPerMinute, (long) bucket.tokens + refillCount);
                bucket.lastRefillEpochMillis += refillCount * windowMs / (long) inboundPerMinute;
            }
            if (bucket.tokens > 0) {
                bucket.tokens--;
                return true;
            }
            return false;
        }
    }

    /**
     * Periodically evict full + idle buckets to bound memory. Quarkus
     * runtime invokes this method on the scheduler thread; plain-JUnit
     * tests never see it fire (no scheduler runtime).
     */
    @Scheduled(every = "{infochat.rate-cap.sweep-interval:5m}")
    void evictIdleBuckets() {
        long thresholdEpochMillis = clock.millis() - evictionThreshold.toMillis();
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                return bucket.tokens == inboundPerMinute
                        && bucket.lastRefillEpochMillis < thresholdEpochMillis;
            }
        });
    }

    private record Key(String adapter, String contactId) {
        Key {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(contactId, "contactId");
        }
    }

    private static final class Bucket {
        int tokens;
        long lastRefillEpochMillis;

        Bucket(int initialTokens, long nowEpochMillis) {
            this.tokens = initialTokens;
            this.lastRefillEpochMillis = nowEpochMillis;
        }
    }
}
