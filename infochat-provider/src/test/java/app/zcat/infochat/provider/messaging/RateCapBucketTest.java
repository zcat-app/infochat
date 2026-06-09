package app.zcat.infochat.provider.messaging;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * Widened-eviction predicate (M1-044b folded /redteam M1-044a DOS
     * finding). Pre-M1-044b, evictIdleBuckets required {@code tokens ==
     * cap} AND idle past threshold; a drained-and-abandoned bucket
     * never refilled (refill is lazy inside tryAcquire) and so stayed
     * pinned forever. The fix drops the tokens-at-cap gate so any
     * idle bucket evicts regardless of token count.
     *
     * <p>This test seeds a bucket by draining it (so {@code tokens ==
     * 0}), then advances the clock past the eviction threshold WITHOUT
     * any further {@code tryAcquire} call (so {@code lastRefillEpochMillis}
     * stays stale), runs {@link RateCapBucket#evictIdleBuckets} directly,
     * and asserts the bucket entry has been removed from the underlying
     * map via {@link RateCapBucket#bucketCount()}.</p>
     */
    @Test
    void evictionDrainedIdle() {
        TestClock clock = new TestClock(Instant.parse("2026-05-20T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        // Drain the bucket to 0 tokens via CAP tryAcquire calls. The
        // last call leaves lastRefillEpochMillis at the current clock
        // (no refill happened — elapsed=0 in the same tick).
        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "rate-drained"));
        }
        assertEquals(1, bucket.bucketCount(),
                "bucket map should hold one entry after the drain");

        // Advance the clock past evictionThreshold WITHOUT calling
        // tryAcquire — lastRefillEpochMillis stays at the drain time,
        // so the entry is now "drained and idle past threshold." Under
        // the pre-M1-044b predicate this entry would not be evicted
        // (tokens != cap); under the widened predicate it is.
        clock.advance(EVICTION_THRESHOLD.plus(Duration.ofMinutes(1)));

        bucket.evictIdleBuckets();

        assertEquals(0, bucket.bucketCount(),
                "drained-and-idle bucket must be evicted under the widened predicate");
    }

    /**
     * Pre-auth key-space bound (acceptance item 3 / gpt S5). Contact ids
     * enter {@link RateCapBucket#tryAcquire} from the adapter boundary
     * before any registration or auth check, so a flood of distinct
     * {@code (adapter, contactId)} tuples must not grow the bucket map
     * without bound. With the hard cap set small, driving far more distinct
     * contacts than the cap leaves {@link RateCapBucket#bucketCount()}
     * pinned at the cap: the first {@code cap} distinct contacts each mint
     * a (full) bucket and succeed, and every distinct contact past the cap
     * is rejected (return false) exactly like an over-cap inbound and never
     * inserted.
     */
    @Test
    void contactBucketKeySpaceBoundedUnderDistinctContactFlood() {
        TestClock clock = new TestClock(Instant.parse("2026-05-20T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);
        int cap = 5;
        bucket.maxContactBuckets = cap;

        for (int i = 0; i < cap * 10; i++) {
            boolean acquired = bucket.tryAcquire("inmemory", "flood-" + i);
            if (i < cap) {
                assertTrue(acquired,
                        "the first " + cap + " distinct contacts each mint a bucket (i=" + i + ")");
            } else {
                assertFalse(acquired,
                        "a distinct contact past the key-space cap is rejected (i=" + i + ")");
            }
            assertTrue(bucket.bucketCount() <= cap,
                    "the bucket map must never exceed the key-space cap (i=" + i + ")");
        }
        assertEquals(cap, bucket.bucketCount(),
                "after the flood the map holds exactly the cap, not one more");
    }

    // ----- M1-229 inbound rate-cap split: registered per-id vs shared -----
    // stranger limiter. Per docs/spec/security.md §Rate limiting +
    // §Authorization model step 1.5 and the M1-205 capacity-wall DOS
    // remediation: an unregistered sender (the 3-arg tryAcquire's
    // registered=false branch) shares ONE per-adapter bucket and mints
    // no per-(adapter, contactId) state; a registered sender keeps an
    // independent per-id bucket. Driven through the 4-arg seam with a
    // small cap against the controllable TestClock.

    /**
     * Acceptance item 1: a flood of DISTINCT unregistered contact ids
     * does not grow the per-id bucket map — strangers no longer create
     * per-id rate-cap state. The per-id map stays empty (no registered
     * contacts here) and all strangers share exactly one per-adapter
     * bucket.
     */
    @Test
    void strangerFloodDoesNotGrowPerIdBucketMap() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        for (int i = 0; i < CAP * 100; i++) {
            // registered=false → shared stranger limiter, distinct ids.
            bucket.tryAcquire("inmemory", "stranger-" + i, false);
        }

        assertEquals(0, bucket.bucketCount(),
                "a distinct-stranger flood must mint ZERO per-(adapter, contactId) buckets");
        assertEquals(1, bucket.strangerBucketCount(),
                "all strangers on one adapter share exactly one shared bucket");
    }

    /**
     * Acceptance item 2: sustained unregistered inbound is bounded by a
     * single shared/aggregate limiter — over-budget stranger inbound
     * returns false (the caller drops it silently), and the bound is on
     * the AGGREGATE across distinct stranger ids, not per id.
     */
    @Test
    void sustainedUnregisteredInboundBoundedBySharedLimiter() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "s-" + i, false),
                    "the first CAP stranger inbounds (each a DISTINCT id) drain the shared budget (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquire("inmemory", "s-distinct-new", false),
                "a further DISTINCT stranger is dropped — bounded by the SHARED limiter, not a fresh per-id bucket");
    }

    /**
     * Acceptance item 3: a registered contact retains an independent
     * per-user inbound rate cap, and a stranger flood that fully drains
     * the shared limiter does NOT consume the registered user's per-id
     * budget.
     */
    @Test
    void registeredContactRetainsIndependentPerUserCapUnaffectedByStrangerFlood() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        // Drain the shared stranger budget completely with a flood.
        for (int i = 0; i < CAP * 10; i++) {
            bucket.tryAcquire("inmemory", "flood-" + i, false);
        }
        assertFalse(bucket.tryAcquire("inmemory", "flood-x", false),
                "precondition: the shared stranger budget is drained");

        // A registered contact still has its full, independent per-id
        // budget — the flood drained a different bucket.
        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "regular", true),
                    "a registered contact keeps its own per-id budget despite the stranger flood (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquire("inmemory", "regular", true),
                "the registered per-id bucket is independently bounded at CAP");
    }

    /**
     * Acceptance item 4 (the M1-205 capacity-wall regression guard): a
     * brand-new contact's invite-code message is admitted while the
     * shared stranger budget has capacity, is transiently rate-limited
     * once the budget is drained, and becomes admissible AGAIN after the
     * budget refills — registration is rate-limited, never permanently
     * closed under a sustained flood. No per-id state is ever minted for
     * a stranger.
     */
    @Test
    void strangerAdmittedWhenSharedBudgetHasCapacityAndAgainAfterRefill() {
        TestClock clock = new TestClock(Instant.parse("2026-06-08T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD);

        // An active flood drains the shared budget.
        for (int i = 0; i < CAP; i++) {
            assertTrue(bucket.tryAcquire("inmemory", "flood-" + i, false));
        }
        assertFalse(bucket.tryAcquire("inmemory", "newcomer", false),
                "during the flood a brand-new contact is rate-limited (transient), NOT a held capacity-wall lockout");

        // Advance past the refill window — registration is not closed.
        clock.advance(REFILL_WINDOW);

        assertTrue(bucket.tryAcquire("inmemory", "newcomer", false),
                "after the shared budget refills the newcomer's invite message is admitted again "
                        + "(the M1-205 capacity-wall lockout is replaced by a self-clearing rate)");
        assertEquals(0, bucket.bucketCount(),
                "no stranger — flooding or newcomer — ever minted a per-id bucket");
    }

    // ----- D47 per-group LLM sub-bucket (M1-222) --------------------------
    // Per docs/spec/security.md §Rate limiting "Per-group LLM rate (D47)":
    // a separate sub-bucket per approved group bounding LLM-triggering
    // operations across all group members. Driven through the 8-arg test
    // seam with a small cap against the controllable TestClock, mirroring
    // the contact-bucket tests above.

    private static final int GROUP_LLM_CAP = 3;
    private static final Duration GROUP_LLM_REFILL_WINDOW = Duration.ofMinutes(15);

    private static RateCapBucket bucketWithGroupLlmSeam(TestClock clock) {
        return new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD,
                10, Duration.ofMinutes(15), GROUP_LLM_CAP, GROUP_LLM_REFILL_WINDOW);
    }

    @Test
    void groupLlmOverCapReturnsFalseAfterExhaustion() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupLlmSeam(clock);
        UUID groupId = UUID.randomUUID();

        for (int i = 0; i < GROUP_LLM_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupLlm(groupId),
                    "the first " + GROUP_LLM_CAP + " group-LLM acquires must succeed (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquireGroupLlm(groupId),
                "the (cap+1)-th group-LLM acquire without time advance must return false");
    }

    @Test
    void groupLlmRefillsOverFifteenMinuteWindow() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupLlmSeam(clock);
        UUID groupId = UUID.randomUUID();

        // Drain the group-LLM bucket.
        for (int i = 0; i < GROUP_LLM_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupLlm(groupId));
        }
        assertFalse(bucket.tryAcquireGroupLlm(groupId),
                "group-LLM bucket drained; next acquire without refill must fail");

        // Advance the clock past the 15-minute refill window — cap
        // tokens added back, mirroring the contact-bucket refill test.
        clock.advance(GROUP_LLM_REFILL_WINDOW);

        for (int i = 0; i < GROUP_LLM_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupLlm(groupId),
                    "after a full refill window elapses, cap acquires succeed again (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquireGroupLlm(groupId),
                "the post-refill group-LLM bucket is also bounded at cap");
    }

    /**
     * The group-LLM map shares {@link RateCapBucket#evictIdleBuckets}
     * with the contact and group-reply maps — the idle predicate is
     * key-shape independent. Mirrors {@link #evictionDrainedIdle}: a
     * drained bucket left idle past the map's EFFECTIVE eviction
     * threshold — {@code max(evictionThreshold, refillWindow)}, here
     * the 15-minute group-LLM window — must be removed from the map.
     */
    @Test
    void groupLlmDrainedIdleBucketIsEvicted() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupLlmSeam(clock);
        UUID groupId = UUID.randomUUID();

        for (int i = 0; i < GROUP_LLM_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupLlm(groupId));
        }
        assertEquals(1, bucket.groupLlmBucketCount(),
                "group-LLM map should hold one entry after the drain");

        clock.advance(GROUP_LLM_REFILL_WINDOW.plus(Duration.ofMinutes(1)));

        bucket.evictIdleBuckets();

        assertEquals(0, bucket.groupLlmBucketCount(),
                "drained-and-idle group-LLM bucket must be evicted by the shared sweep");
    }

    /**
     * Redteam M1-222 finding 2 (DOS-low): eviction recreates a key with
     * a FULL allotment, so evicting before one whole refill window has
     * elapsed would mint tokens faster than the configured schedule
     * (~1.5x at the PT10M default threshold vs the 15-minute window).
     * The sweep therefore applies {@code max(evictionThreshold,
     * refillWindow)} per map: idle past the 10-minute threshold but
     * within the 15-minute window, the drained entry must SURVIVE the
     * sweep and the next acquire must still fail.
     *
     * <p>Cap 1 by construction — one token per 15 minutes means an
     * 11-minute advance accrues zero lazy-refill tokens, so a {@code
     * false} here distinguishes "entry survived, still drained" from
     * "entry survived but naturally refilled" (at cap 3 the same
     * advance would legitimately accrue 2 tokens).</p>
     */
    @Test
    void groupLlmEvictionDoesNotOutpaceRefillWindow() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD,
                10, Duration.ofMinutes(15), 1, GROUP_LLM_REFILL_WINDOW);
        UUID groupId = UUID.randomUUID();

        assertTrue(bucket.tryAcquireGroupLlm(groupId), "the single token drains the cap-1 bucket");
        assertFalse(bucket.tryAcquireGroupLlm(groupId), "bucket drained");

        // Past the PT10M eviction threshold, within the PT15M window.
        clock.advance(EVICTION_THRESHOLD.plus(Duration.ofMinutes(1)));

        bucket.evictIdleBuckets();

        assertEquals(1, bucket.groupLlmBucketCount(),
                "a drained bucket idle less than one refill window must survive the sweep");
        assertFalse(bucket.tryAcquireGroupLlm(groupId),
                "no rebirth-at-full: the next acquire before the window elapses still fails");
    }

    // ----- D47 per-group command sub-bucket (M1-222 redteam follow-up) ----
    // Per docs/spec/security.md §Rate limiting "Per-group command rate
    // (D47)": a sub-bucket per approved group bounding total command volume
    // from all members. Driven through the 10-arg test seam with a small
    // cap against the controllable TestClock, mirroring the group-LLM
    // tests above.

    private static final int GROUP_COMMAND_CAP = 3;
    private static final Duration GROUP_COMMAND_REFILL_WINDOW = Duration.ofMinutes(15);

    private static RateCapBucket bucketWithGroupCommandSeam(TestClock clock, int groupCommandCap) {
        return new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD,
                10, Duration.ofMinutes(15), GROUP_LLM_CAP, GROUP_LLM_REFILL_WINDOW,
                groupCommandCap, GROUP_COMMAND_REFILL_WINDOW);
    }

    @Test
    void groupCommandOverCapReturnsFalseAfterExhaustion() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupCommandSeam(clock, GROUP_COMMAND_CAP);
        UUID groupId = UUID.randomUUID();

        for (int i = 0; i < GROUP_COMMAND_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupCommand(groupId),
                    "the first " + GROUP_COMMAND_CAP + " group-command acquires must succeed (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquireGroupCommand(groupId),
                "the (cap+1)-th group-command acquire without time advance must return false");
    }

    @Test
    void groupCommandRefillsOverFifteenMinuteWindow() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupCommandSeam(clock, GROUP_COMMAND_CAP);
        UUID groupId = UUID.randomUUID();

        // Drain the group-command bucket.
        for (int i = 0; i < GROUP_COMMAND_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupCommand(groupId));
        }
        assertFalse(bucket.tryAcquireGroupCommand(groupId),
                "group-command bucket drained; next acquire without refill must fail");

        clock.advance(GROUP_COMMAND_REFILL_WINDOW);

        for (int i = 0; i < GROUP_COMMAND_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupCommand(groupId),
                    "after a full refill window elapses, cap acquires succeed again (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquireGroupCommand(groupId),
                "the post-refill group-command bucket is also bounded at cap");
    }

    /**
     * The group-command map shares {@link RateCapBucket#evictIdleBuckets}
     * with the other three maps. Mirrors
     * {@link #groupLlmDrainedIdleBucketIsEvicted}: a drained bucket
     * left idle past the map's effective eviction threshold —
     * {@code max(evictionThreshold, refillWindow)} — must be removed.
     */
    @Test
    void groupCommandDrainedIdleBucketIsEvicted() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupCommandSeam(clock, GROUP_COMMAND_CAP);
        UUID groupId = UUID.randomUUID();

        for (int i = 0; i < GROUP_COMMAND_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupCommand(groupId));
        }
        assertEquals(1, bucket.groupCommandBucketCount(),
                "group-command map should hold one entry after the drain");

        clock.advance(GROUP_COMMAND_REFILL_WINDOW.plus(Duration.ofMinutes(1)));

        bucket.evictIdleBuckets();

        assertEquals(0, bucket.groupCommandBucketCount(),
                "drained-and-idle group-command bucket must be evicted by the shared sweep");
    }

    /**
     * Mirrors {@link #groupLlmEvictionDoesNotOutpaceRefillWindow} for
     * the command map: the sweep must pass the command map's OWN
     * 15-minute window into the {@code max(evictionThreshold,
     * refillWindow)} widening — a drained entry idle past the PT10M
     * threshold but within the window must survive, still drained.
     */
    @Test
    void groupCommandEvictionDoesNotOutpaceRefillWindow() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupCommandSeam(clock, 1);
        UUID groupId = UUID.randomUUID();

        assertTrue(bucket.tryAcquireGroupCommand(groupId), "the single token drains the cap-1 bucket");
        assertFalse(bucket.tryAcquireGroupCommand(groupId), "bucket drained");

        // Past the PT10M eviction threshold, within the PT15M window.
        clock.advance(EVICTION_THRESHOLD.plus(Duration.ofMinutes(1)));

        bucket.evictIdleBuckets();

        assertEquals(1, bucket.groupCommandBucketCount(),
                "a drained bucket idle less than one refill window must survive the sweep");
        assertFalse(bucket.tryAcquireGroupCommand(groupId),
                "no rebirth-at-full: the next acquire before the window elapses still fails");
    }

    // ----- D47 step 3.5 per-group reply bucket (M1-112) -------------------
    // Per docs/spec/security.md §Rate limiting "per-group reply": one
    // sub-bucket per group bounding outbound reply volume across approval
    // states. Driven through the 6-arg test seam with a small cap against
    // the controllable TestClock, mirroring the group-LLM / group-command
    // tests above. (The reply bucket had no direct coverage before T11.)

    private static final int GROUP_REPLY_CAP = 3;
    private static final Duration GROUP_REPLY_REFILL_WINDOW = Duration.ofMinutes(15);

    private static RateCapBucket bucketWithGroupReplySeam(TestClock clock) {
        return new RateCapBucket(clock, CAP, REFILL_WINDOW, EVICTION_THRESHOLD,
                GROUP_REPLY_CAP, GROUP_REPLY_REFILL_WINDOW);
    }

    @Test
    void groupReplyOverCapReturnsFalseAfterExhaustion() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupReplySeam(clock);
        UUID groupId = UUID.randomUUID();

        for (int i = 0; i < GROUP_REPLY_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupReply(groupId),
                    "the first " + GROUP_REPLY_CAP + " group-reply acquires must succeed (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquireGroupReply(groupId),
                "the (cap+1)-th group-reply acquire without time advance must return false");
    }

    @Test
    void groupReplyRefillsOverWindow() {
        TestClock clock = new TestClock(Instant.parse("2026-06-07T00:00:00Z"));
        RateCapBucket bucket = bucketWithGroupReplySeam(clock);
        UUID groupId = UUID.randomUUID();

        // Drain the group-reply bucket.
        for (int i = 0; i < GROUP_REPLY_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupReply(groupId));
        }
        assertFalse(bucket.tryAcquireGroupReply(groupId),
                "group-reply bucket drained; next acquire without refill must fail");

        // Advance past the 15-minute reply window — cap tokens added back.
        clock.advance(GROUP_REPLY_REFILL_WINDOW);

        for (int i = 0; i < GROUP_REPLY_CAP; i++) {
            assertTrue(bucket.tryAcquireGroupReply(groupId),
                    "after a full refill window elapses, cap acquires succeed again (i=" + i + ")");
        }
        assertFalse(bucket.tryAcquireGroupReply(groupId),
                "the post-refill group-reply bucket is also bounded at cap");
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
