package app.zcat.infochat.provider.messaging;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
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

    // D47 step 3.5 (M1-112). Per-group reply bucket shared across
    // approval states — bounds outbound adapter-send cost on pending /
    // rejected / approved groups alike. Window is fixed at 15 minutes
    // by the property name; the refill-window key is declared with a
    // default so tests can construct against a controllable clock.
    @ConfigProperty(name = "infochat.ratelimit.group-reply-per-15min", defaultValue = "10")
    int groupReplyCap;

    @ConfigProperty(name = "infochat.ratelimit.group-reply-refill-window", defaultValue = "PT15M")
    Duration groupReplyRefillWindow;

    private Clock clock = Clock.systemUTC();

    private final ConcurrentHashMap<Key, Bucket> buckets = new ConcurrentHashMap<>();

    // D47 per-group bucket map. Eviction shares the contact-bucket
    // sweep below; the predicate (idle past evictionThreshold) is
    // key-shape-independent.
    private final ConcurrentHashMap<UUID, Bucket> groupBuckets = new ConcurrentHashMap<>();

    public RateCapBucket() {
        // CDI no-arg constructor; @ConfigProperty fields populated post-construction.
    }

    /**
     * Test seam (contact bucket only). Plain-JUnit tests bypass CDI and
     * instantiate the bean with a controllable {@link Clock} and
     * explicit cap/window/eviction values. Group-bucket defaults match
     * the application-properties laptop values so tests that don't care
     * about group buckets still construct cleanly. Package-private —
     * the rest of the provider tree consumes the bean via CDI.
     */
    RateCapBucket(Clock clock, int inboundPerMinute, Duration refillWindow, Duration evictionThreshold) {
        this(clock, inboundPerMinute, refillWindow, evictionThreshold, 10, Duration.ofMinutes(15));
    }

    /**
     * Test seam (contact + group buckets). New for M1-112 so
     * {@code GroupApprovalCheckTest} can drive the bucket-exhausted
     * scenario with a small cap against a controllable clock.
     */
    RateCapBucket(Clock clock,
                  int inboundPerMinute,
                  Duration refillWindow,
                  Duration evictionThreshold,
                  int groupReplyCap,
                  Duration groupReplyRefillWindow) {
        this.clock = clock;
        this.inboundPerMinute = inboundPerMinute;
        this.refillWindow = refillWindow;
        this.evictionThreshold = evictionThreshold;
        this.groupReplyCap = groupReplyCap;
        this.groupReplyRefillWindow = groupReplyRefillWindow;
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
     * D47 step 3.5 (M1-112). Per-group reply bucket — token-bucket
     * keyed on {@code groups.id}. The bucket is shared across approval
     * states so a pending or rejected group's fixed-reply outputs
     * count against the same budget as approved-group processing.
     * Exhaustion returns {@code false}; the caller silently drops the
     * @-mention with no reply per spec §Rate limiting per-group reply.
     *
     * <p>Refill cadence and cap are independent of the contact bucket
     * — the group cap is per-15-minutes by design, the contact cap is
     * per-minute. The two maps share the eviction sweep below.</p>
     */
    public boolean tryAcquireGroupReply(@NonNull UUID groupId) {
        Bucket bucket = groupBuckets.computeIfAbsent(
                groupId, k -> new Bucket(groupReplyCap, clock.millis()));
        synchronized (bucket) {
            long now = clock.millis();
            long elapsed = Math.max(0L, now - bucket.lastRefillEpochMillis);
            long windowMs = groupReplyRefillWindow.toMillis();
            long refillCount = elapsed * (long) groupReplyCap / windowMs;
            if (refillCount > 0) {
                bucket.tokens = (int) Math.min((long) groupReplyCap, (long) bucket.tokens + refillCount);
                bucket.lastRefillEpochMillis += refillCount * windowMs / (long) groupReplyCap;
            }
            if (bucket.tokens > 0) {
                bucket.tokens--;
                return true;
            }
            return false;
        }
    }

    /**
     * Periodically evict idle buckets to bound memory. Quarkus runtime
     * invokes this method on the scheduler thread; plain-JUnit tests
     * never see it fire (no scheduler runtime).
     *
     * <p>The predicate is idle-alone: any bucket whose
     * {@code lastRefillEpochMillis} is older than the eviction
     * threshold is evicted, regardless of current token count. The
     * earlier predicate also required the token count to be at cap,
     * which pinned drained-and-abandoned buckets forever — refill is
     * lazy inside {@link #tryAcquire}, so a contact that drained their
     * bucket and never returned never refilled back to cap and never
     * matched the eviction gate. A returning contact whose bucket was
     * evicted pays a one-time bucket-cold cost (a fresh full bucket
     * via {@code computeIfAbsent}), which is the same shape a process
     * restart would produce. See the /redteam M1-044a DOS finding
     * (docs/plan/m1/redteam/M1-044a-2026-05-21.md) and M1-044b's
     * Implementation notes §"Rate-cap eviction-predicate fix".</p>
     *
     * <p>Sweeps both the contact map and the group map under the same
     * threshold — the predicate is key-shape independent.</p>
     */
    @Scheduled(every = "{infochat.rate-cap.sweep-interval:5m}")
    void evictIdleBuckets() {
        long thresholdEpochMillis = clock.millis() - evictionThreshold.toMillis();
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                return bucket.lastRefillEpochMillis < thresholdEpochMillis;
            }
        });
        groupBuckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                return bucket.lastRefillEpochMillis < thresholdEpochMillis;
            }
        });
    }

    /**
     * Test-only seam: report the current bucket-map size so
     * {@code RateCapBucketTest.evictionDrainedIdle} can assert eviction
     * actually removed the entry from the map. Package-private —
     * production callers consume {@link #tryAcquire}, never the size.
     */
    int bucketCount() {
        return buckets.size();
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
