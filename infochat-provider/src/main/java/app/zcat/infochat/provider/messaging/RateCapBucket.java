package app.zcat.infochat.provider.messaging;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    // D47 per-group LLM sub-bucket (M1-222). Approved-group backstop
    // bounding aggregate LLM-triggering operations across all group
    // members — the per-user LlmRateCap fires first; this cap bounds
    // groups with many active members. Window fixed at 15 minutes by
    // the property name; same declared-default pattern as group-reply.
    @ConfigProperty(name = "infochat.ratelimit.group-llm-per-15min", defaultValue = "5")
    int groupLlmCap;

    @ConfigProperty(name = "infochat.ratelimit.group-llm-refill-window", defaultValue = "PT15M")
    Duration groupLlmRefillWindow;

    // D47 per-group command sub-bucket (M1-222 redteam follow-up).
    // Approved-group aggregate bound on slash-command dispatch volume
    // across all group members per design §4.9; overflow sends the
    // fixed group.command_rate_limit reply. Window fixed at 15 minutes
    // by the property name; same declared-default pattern as the other
    // two group buckets.
    @ConfigProperty(name = "infochat.ratelimit.group-commands-per-15min", defaultValue = "20")
    int groupCommandCap;

    @ConfigProperty(name = "infochat.ratelimit.group-command-refill-window", defaultValue = "PT15M")
    Duration groupCommandRefillWindow;

    private Clock clock = Clock.systemUTC();

    private final ConcurrentHashMap<Key, Bucket> buckets = new ConcurrentHashMap<>();

    // D47 per-group bucket map. Eviction shares the contact-bucket
    // sweep below; the predicate (idle past evictionThreshold) is
    // key-shape-independent.
    private final ConcurrentHashMap<UUID, Bucket> groupBuckets = new ConcurrentHashMap<>();

    // D47 per-group LLM bucket map (M1-222). Separate from the reply
    // map — the two sub-buckets have independent caps and budgets per
    // design §4.9; one group id holds one entry in each.
    private final ConcurrentHashMap<UUID, Bucket> groupLlmBuckets = new ConcurrentHashMap<>();

    // D47 per-group command bucket map (M1-222 redteam follow-up).
    // Independent cap and budget per design §4.9; one group id holds
    // one entry in each of the three group maps.
    private final ConcurrentHashMap<UUID, Bucket> groupCommandBuckets = new ConcurrentHashMap<>();

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
     * Test seam (contact + group-reply buckets). New for M1-112 so
     * {@code GroupApprovalCheckTest} can drive the bucket-exhausted
     * scenario with a small cap against a controllable clock.
     * Group-LLM defaults match the application-properties laptop
     * values, mirroring the 4-arg seam's group-reply defaulting.
     */
    RateCapBucket(Clock clock,
                  int inboundPerMinute,
                  Duration refillWindow,
                  Duration evictionThreshold,
                  int groupReplyCap,
                  Duration groupReplyRefillWindow) {
        this(clock, inboundPerMinute, refillWindow, evictionThreshold,
                groupReplyCap, groupReplyRefillWindow, 5, Duration.ofMinutes(15));
    }

    /**
     * Test seam (contact + group-reply + group-LLM buckets). New for
     * M1-222 so {@code RateCapBucketTest} can drive the group-LLM
     * exhaustion / refill / eviction scenarios with a small cap
     * against a controllable clock. Group-command defaults match the
     * application-properties laptop values, mirroring the narrower
     * seams' defaulting.
     */
    RateCapBucket(Clock clock,
                  int inboundPerMinute,
                  Duration refillWindow,
                  Duration evictionThreshold,
                  int groupReplyCap,
                  Duration groupReplyRefillWindow,
                  int groupLlmCap,
                  Duration groupLlmRefillWindow) {
        this(clock, inboundPerMinute, refillWindow, evictionThreshold,
                groupReplyCap, groupReplyRefillWindow, groupLlmCap, groupLlmRefillWindow,
                20, Duration.ofMinutes(15));
    }

    /**
     * Test seam (all four buckets). New for the M1-222 redteam
     * follow-up so {@code RateCapBucketTest} can drive the
     * group-command exhaustion / refill / eviction scenarios with a
     * small cap against a controllable clock.
     */
    RateCapBucket(Clock clock,
                  int inboundPerMinute,
                  Duration refillWindow,
                  Duration evictionThreshold,
                  int groupReplyCap,
                  Duration groupReplyRefillWindow,
                  int groupLlmCap,
                  Duration groupLlmRefillWindow,
                  int groupCommandCap,
                  Duration groupCommandRefillWindow) {
        this.clock = clock;
        this.inboundPerMinute = inboundPerMinute;
        this.refillWindow = refillWindow;
        this.evictionThreshold = evictionThreshold;
        this.groupReplyCap = groupReplyCap;
        this.groupReplyRefillWindow = groupReplyRefillWindow;
        this.groupLlmCap = groupLlmCap;
        this.groupLlmRefillWindow = groupLlmRefillWindow;
        this.groupCommandCap = groupCommandCap;
        this.groupCommandRefillWindow = groupCommandRefillWindow;
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
    public boolean tryAcquireGroupReply(UUID groupId) {
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
     * D47 per-group LLM sub-bucket (M1-222). Token-bucket keyed on
     * {@code groups.id}, bounding LLM-triggering operations across all
     * members of one approved group — the aggregate backstop behind
     * the per-user {@code LlmRateCap}, which fires first by call-site
     * construction. Exhaustion returns {@code false}; the caller sends
     * the fixed {@code group.llm_rate_limit} bundle reply per design
     * §4.9 (NOT a silent drop — that is the reply bucket's overflow
     * action). The "approved only" constraint is positional: only
     * approved groups reach the chat dispatch that consults this
     * bucket. Periodic digests are system-initiated and never consult
     * this bucket.
     */
    public boolean tryAcquireGroupLlm(UUID groupId) {
        Bucket bucket = groupLlmBuckets.computeIfAbsent(
                groupId, k -> new Bucket(groupLlmCap, clock.millis()));
        synchronized (bucket) {
            long now = clock.millis();
            long elapsed = Math.max(0L, now - bucket.lastRefillEpochMillis);
            long windowMs = groupLlmRefillWindow.toMillis();
            long refillCount = elapsed * (long) groupLlmCap / windowMs;
            if (refillCount > 0) {
                bucket.tokens = (int) Math.min((long) groupLlmCap, (long) bucket.tokens + refillCount);
                bucket.lastRefillEpochMillis += refillCount * windowMs / (long) groupLlmCap;
            }
            if (bucket.tokens > 0) {
                bucket.tokens--;
                return true;
            }
            return false;
        }
    }

    /**
     * D47 per-group command sub-bucket (M1-222 redteam follow-up).
     * Token-bucket keyed on {@code groups.id}, bounding slash-command
     * dispatch volume across all members of one approved group.
     * Exhaustion returns {@code false}; the caller sends the fixed
     * {@code group.command_rate_limit} bundle reply per design §4.9
     * (NOT a silent drop — that is the reply bucket's overflow
     * action). The "approved only" constraint is positional: only
     * approved groups reach the slash dispatch that consults this
     * bucket. DM slash dispatch never consults it.
     */
    public boolean tryAcquireGroupCommand(UUID groupId) {
        Bucket bucket = groupCommandBuckets.computeIfAbsent(
                groupId, k -> new Bucket(groupCommandCap, clock.millis()));
        synchronized (bucket) {
            long now = clock.millis();
            long elapsed = Math.max(0L, now - bucket.lastRefillEpochMillis);
            long windowMs = groupCommandRefillWindow.toMillis();
            long refillCount = elapsed * (long) groupCommandCap / windowMs;
            if (refillCount > 0) {
                bucket.tokens = (int) Math.min((long) groupCommandCap, (long) bucket.tokens + refillCount);
                bucket.lastRefillEpochMillis += refillCount * windowMs / (long) groupCommandCap;
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
     * <p>Sweeps the contact map, the group-reply map, the group-LLM
     * map, and the group-command map with the same
     * key-shape-independent predicate, but
     * each map's effective threshold is
     * {@code max(evictionThreshold, thatMapsRefillWindow)}: eviction
     * recreates a key with a FULL allotment, which is only equivalent
     * to lazy refill once the bucket has been idle for at least one
     * whole refill window. A threshold below the window would let a
     * drained-then-idle bucket be reborn full early — sustained rate
     * above the configured cap (redteam M1-222 finding 2; the 15-min
     * group windows exceed the PT10M default threshold, the 1-min
     * contact window does not).</p>
     */
    @Scheduled(every = "{infochat.rate-cap.sweep-interval:5m}")
    void evictIdleBuckets() {
        long now = clock.millis();
        evictIdle(buckets, now - effectiveEvictionMillis(refillWindow));
        evictIdle(groupBuckets, now - effectiveEvictionMillis(groupReplyRefillWindow));
        evictIdle(groupLlmBuckets, now - effectiveEvictionMillis(groupLlmRefillWindow));
        evictIdle(groupCommandBuckets, now - effectiveEvictionMillis(groupCommandRefillWindow));
    }

    private long effectiveEvictionMillis(Duration mapRefillWindow) {
        return Math.max(evictionThreshold.toMillis(), mapRefillWindow.toMillis());
    }

    private static <K> void evictIdle(ConcurrentHashMap<K, Bucket> map, long thresholdEpochMillis) {
        map.entrySet().removeIf(entry -> {
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

    /**
     * Test-only seam: report the current group-LLM bucket-map size so
     * {@code RateCapBucketTest} can assert the eviction sweep removed
     * the entry from the map. Package-private — production callers
     * consume {@link #tryAcquireGroupLlm}, never the size.
     */
    int groupLlmBucketCount() {
        return groupLlmBuckets.size();
    }

    /**
     * Test-only seam: report the current group-command bucket-map size
     * so {@code RateCapBucketTest} can assert the eviction sweep
     * removed the entry from the map. Package-private — production
     * callers consume {@link #tryAcquireGroupCommand}, never the size.
     */
    int groupCommandBucketCount() {
        return groupCommandBuckets.size();
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
