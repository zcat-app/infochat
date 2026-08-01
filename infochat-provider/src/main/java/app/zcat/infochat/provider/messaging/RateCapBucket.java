package app.zcat.infochat.provider.messaging;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
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

    // Hard cap on the number of distinct (adapter, contactId) buckets in
    // `buckets`. Since M1-229 the per-id map holds ONLY registered
    // contacts — unregistered inbound shares strangerBuckets and never
    // mints a per-id entry — so this cap now backstops the registered
    // (invite-gated) key space, a far smaller population than the
    // pre-M1-229 all-comers space. (Operator-visible semantics change:
    // the default 100000 is no longer the all-inbound key ceiling.) The
    // cap still composes with eviction the same way: a full map rejects a
    // NEW key exactly like an over-cap inbound (silent drop, no throw, no
    // outbound), and the eviction sweep below reclaims idle keys over
    // time. A new key is NEVER admitted by evicting a live one (that
    // would reintroduce the M1-044a DOS shape).
    @ConfigProperty(name = "infochat.rate-cap.max-contact-buckets", defaultValue = "100000")
    int maxContactBuckets;

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

    // M1-705 cheap-command bucket (design §4.9, spec §Rate limiting
    // "Parser-only + DB-read paginated commands"). Per-(adapter,
    // contactId) bucket drawn by the commands that share the low cost
    // profile — /help, /status, /list-sources, /get-sources, /get-tags,
    // /saved, /audit, /export, /quarantine list, and the asset commands.
    // Distinct from the step-1.5 transport bucket: it sits BEHIND it on
    // the dispatch path, so its friendly-reject overflow is metered
    // outbound cost, not amplification. Window fixed at 1 minute by the
    // property name; same declared-default pattern as the group buckets.
    @ConfigProperty(name = "infochat.ratelimit.cheap-commands-per-minute", defaultValue = "30")
    int cheapCommandCap;

    @ConfigProperty(name = "infochat.ratelimit.cheap-command-refill-window", defaultValue = "PT1M")
    Duration cheapCommandRefillWindow;

    // M1-705 /add-source bucket (design §4.9). Per-user (users.id)
    // hourly bucket bounding the outbound-cost command — every accepted
    // /add-source drives a UrlProbe fetch. Window fixed at 1 hour by the
    // property name.
    @ConfigProperty(name = "infochat.ratelimit.add-source-per-hour", defaultValue = "5")
    int addSourceCap;

    @ConfigProperty(name = "infochat.ratelimit.add-source-refill-window", defaultValue = "PT1H")
    Duration addSourceRefillWindow;

    // M1-705 dedicated per-admin quarantine bucket (design §4.9's
    // 100/min row). Replaces the pre-M1-705 namespaced reuse of the
    // transport cap (tryAcquire("quarantine", adminId)), which silently
    // inherited the 60/min inbound cap instead of the designed
    // per-admin 100/min. Keyed on the admin's users.id; covers both
    // /quarantine approve and /quarantine reject.
    @ConfigProperty(name = "infochat.ratelimit.quarantine-per-minute", defaultValue = "100")
    int quarantineCap;

    @ConfigProperty(name = "infochat.ratelimit.quarantine-refill-window", defaultValue = "PT1M")
    Duration quarantineRefillWindow;

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

    // M1-705 cheap-command bucket map, keyed per-(adapter, contactId)
    // like the transport map but WITHOUT the maxContactBuckets
    // key-space cap: the map is consulted only at step-6 dispatch and
    // in admin/registered command handlers, where the actor is already
    // registered (invite-gated), so the M1-229/M1-205 stranger-flood
    // defenses do not apply. Idle entries are reclaimed by the eviction
    // sweep below.
    private final ConcurrentHashMap<Key, Bucket> cheapCommandBuckets = new ConcurrentHashMap<>();

    // M1-705 /add-source bucket map, keyed on the caller's users.id.
    private final ConcurrentHashMap<UUID, Bucket> addSourceBuckets = new ConcurrentHashMap<>();

    // M1-705 dedicated per-admin quarantine bucket map, keyed on the
    // admin's users.id.
    private final ConcurrentHashMap<UUID, Bucket> quarantineBuckets = new ConcurrentHashMap<>();

    // M1-229 shared stranger limiter, keyed by adapter name. ALL
    // unregistered inbound on one adapter (a RegisteredContactSet miss
    // at step 1.5) shares this single bucket — strangers never mint a
    // per-(adapter, contactId) entry in `buckets`, so a Sybil flood of
    // distinct stranger ids cannot grow the per-id map (the M1-205
    // capacity-wall DOS this split remediates). Reuses inboundPerMinute
    // + refillWindow: strangers collectively get the same per-minute
    // rate one registered contact gets individually. The key space is
    // bounded by the (tiny, fixed) enabled-adapter count, so this map
    // needs no key-space cap and is intentionally NOT swept by
    // evictIdleBuckets — there is nothing to reclaim. Per-adapter (not
    // global) so one adapter's flood cannot starve another adapter's
    // newcomers (D46 isolation).
    private final ConcurrentHashMap<String, Bucket> strangerBuckets = new ConcurrentHashMap<>();

    public RateCapBucket() {
        // CDI no-arg constructor; @ConfigProperty fields populated post-construction.
    }

    /**
     * Test seam. Plain-JUnit tests bypass CDI and instantiate the bean
     * with a controllable {@link Clock} and a {@link Settings} snapshot
     * — start from {@link Settings#defaults()} and override only the
     * bucket(s) under test via the per-bucket withers. Public (not
     * package-private) because the plain-JUnit handler tests that
     * construct against it live outside the {@code messaging} package
     * ({@code command}, {@code command.asset}); the rest of the
     * provider tree consumes the bean via CDI, where
     * {@code @ConfigProperty} populates the fields instead.
     */
    public RateCapBucket(Clock clock, Settings settings) {
        this.clock = clock;
        this.inboundPerMinute = settings.inboundPerMinute();
        this.refillWindow = settings.refillWindow();
        this.evictionThreshold = settings.evictionThreshold();
        this.groupReplyCap = settings.groupReplyCap();
        this.groupReplyRefillWindow = settings.groupReplyRefillWindow();
        this.groupLlmCap = settings.groupLlmCap();
        this.groupLlmRefillWindow = settings.groupLlmRefillWindow();
        this.groupCommandCap = settings.groupCommandCap();
        this.groupCommandRefillWindow = settings.groupCommandRefillWindow();
        this.cheapCommandCap = settings.cheapCommandCap();
        this.cheapCommandRefillWindow = settings.cheapCommandRefillWindow();
        this.addSourceCap = settings.addSourceCap();
        this.addSourceRefillWindow = settings.addSourceRefillWindow();
        this.quarantineCap = settings.quarantineCap();
        this.quarantineRefillWindow = settings.quarantineRefillWindow();
        // No Settings component for the key-space cap — mirror the
        // @ConfigProperty default for the no-CDI test path (the flood test
        // overrides this field directly with a small value).
        this.maxContactBuckets = 100_000;
    }

    /**
     * Immutable snapshot of the bucket caps and windows the CDI bean
     * reads from {@code @ConfigProperty} fields — the test seam's
     * replacement for the former telescoping constructors.
     * {@link #defaults()} mirrors the {@code @ConfigProperty} declared
     * defaults; each wither overrides one bucket's cap/window pair so a
     * test names only the values it exercises.
     */
    public record Settings(int inboundPerMinute,
                           Duration refillWindow,
                           Duration evictionThreshold,
                           int groupReplyCap,
                           Duration groupReplyRefillWindow,
                           int groupLlmCap,
                           Duration groupLlmRefillWindow,
                           int groupCommandCap,
                           Duration groupCommandRefillWindow,
                           int cheapCommandCap,
                           Duration cheapCommandRefillWindow,
                           int addSourceCap,
                           Duration addSourceRefillWindow,
                           int quarantineCap,
                           Duration quarantineRefillWindow) {

        /** The {@code @ConfigProperty} declared defaults, as one snapshot. */
        public static Settings defaults() {
            return new Settings(60, Duration.ofMinutes(1), Duration.ofMinutes(10),
                    10, Duration.ofMinutes(15),
                    5, Duration.ofMinutes(15),
                    20, Duration.ofMinutes(15),
                    30, Duration.ofMinutes(1),
                    5, Duration.ofHours(1),
                    100, Duration.ofMinutes(1));
        }

        public Settings withContactBucket(int inboundPerMinute, Duration refillWindow,
                                          Duration evictionThreshold) {
            return new Settings(inboundPerMinute, refillWindow, evictionThreshold,
                    groupReplyCap, groupReplyRefillWindow,
                    groupLlmCap, groupLlmRefillWindow,
                    groupCommandCap, groupCommandRefillWindow,
                    cheapCommandCap, cheapCommandRefillWindow,
                    addSourceCap, addSourceRefillWindow,
                    quarantineCap, quarantineRefillWindow);
        }

        public Settings withGroupReplyBucket(int cap, Duration window) {
            return new Settings(inboundPerMinute, refillWindow, evictionThreshold,
                    cap, window,
                    groupLlmCap, groupLlmRefillWindow,
                    groupCommandCap, groupCommandRefillWindow,
                    cheapCommandCap, cheapCommandRefillWindow,
                    addSourceCap, addSourceRefillWindow,
                    quarantineCap, quarantineRefillWindow);
        }

        public Settings withGroupLlmBucket(int cap, Duration window) {
            return new Settings(inboundPerMinute, refillWindow, evictionThreshold,
                    groupReplyCap, groupReplyRefillWindow,
                    cap, window,
                    groupCommandCap, groupCommandRefillWindow,
                    cheapCommandCap, cheapCommandRefillWindow,
                    addSourceCap, addSourceRefillWindow,
                    quarantineCap, quarantineRefillWindow);
        }

        public Settings withGroupCommandBucket(int cap, Duration window) {
            return new Settings(inboundPerMinute, refillWindow, evictionThreshold,
                    groupReplyCap, groupReplyRefillWindow,
                    groupLlmCap, groupLlmRefillWindow,
                    cap, window,
                    cheapCommandCap, cheapCommandRefillWindow,
                    addSourceCap, addSourceRefillWindow,
                    quarantineCap, quarantineRefillWindow);
        }

        public Settings withCheapCommandBucket(int cap, Duration window) {
            return new Settings(inboundPerMinute, refillWindow, evictionThreshold,
                    groupReplyCap, groupReplyRefillWindow,
                    groupLlmCap, groupLlmRefillWindow,
                    groupCommandCap, groupCommandRefillWindow,
                    cap, window,
                    addSourceCap, addSourceRefillWindow,
                    quarantineCap, quarantineRefillWindow);
        }

        public Settings withAddSourceBucket(int cap, Duration window) {
            return new Settings(inboundPerMinute, refillWindow, evictionThreshold,
                    groupReplyCap, groupReplyRefillWindow,
                    groupLlmCap, groupLlmRefillWindow,
                    groupCommandCap, groupCommandRefillWindow,
                    cheapCommandCap, cheapCommandRefillWindow,
                    cap, window,
                    quarantineCap, quarantineRefillWindow);
        }

        public Settings withQuarantineBucket(int cap, Duration window) {
            return new Settings(inboundPerMinute, refillWindow, evictionThreshold,
                    groupReplyCap, groupReplyRefillWindow,
                    groupLlmCap, groupLlmRefillWindow,
                    groupCommandCap, groupCommandRefillWindow,
                    cheapCommandCap, cheapCommandRefillWindow,
                    addSourceCap, addSourceRefillWindow,
                    cap, window);
        }
    }

    /**
     * Per-{@code (adapter, contactId)} acquire — the registered-contact
     * path. Equivalent to {@link #tryAcquire(String, String, boolean)}
     * with {@code registered=true}. Retained as the call shape for the
     * per-actor caps that are always over a known/bounded key space
     * ({@code QuarantineCommandHandler}'s per-admin quarantine cap) and
     * for the existing per-id rate-cap tests.
     *
     * @return {@code true} iff the per-{@code (adapter, contactId)}
     *         bucket has at least one token after refill; the call
     *         decrements one token on success. {@code false} on
     *         empty bucket — the caller drops the inbound.
     */
    public boolean tryAcquire(String adapter, String contactId) {
        return tryAcquire(adapter, contactId, true);
    }

    /**
     * Inbound rate cap split by registration (M1-229), consulted at
     * {@link InboundRouter} step 1.5.
     *
     * <p>{@code registered=true} → the per-{@code (adapter, contactId)}
     * bucket (bounded by {@code maxContactBuckets}, which now backstops
     * only the registered key space). {@code registered=false} → the
     * single shared per-adapter stranger limiter; {@code contactId} is
     * deliberately ignored so a flood of distinct stranger ids cannot
     * mint per-id state. Either branch decrements one token on success;
     * {@code false} means the caller drops the inbound (silent, spec
     * §Authorization model step 1.5).</p>
     */
    public boolean tryAcquire(String adapter, String contactId, boolean registered) {
        if (!registered) {
            return tryAcquireStranger(adapter);
        }
        Key key = new Key(adapter, contactId);
        // Pre-auth key-space cap: a NEW contact is dropped (silent, no
        // throw, no outbound) once the map is full, exactly like an
        // over-cap inbound; an EXISTING key always proceeds. This check
        // precedes the computeIfAbsent inside tryAcquireFrom so a full map
        // cannot mint a fresh entry. The containsKey / size / create window
        // is benign for a flood bound — one dispatch thread per adapter, so
        // any overshoot is bounded by the few concurrent callers, never the
        // unbounded growth the M1-044a DOS shape needs.
        if (!buckets.containsKey(key) && buckets.size() >= maxContactBuckets) {
            return false;
        }
        return tryAcquireFrom(buckets, key, inboundPerMinute, refillWindow);
    }

    /**
     * Single source of the token-bucket refill + decrement body shared by
     * every acquire path — the contact bucket, the shared stranger
     * limiter, and the three per-group sub-buckets. Resolves the bucket
     * for {@code key} in {@code map} (minting a full one on first touch),
     * then under the bucket monitor lazily refills {@code cap} tokens per
     * {@code refillWindow} elapsed — advancing
     * {@code lastRefillEpochMillis} by exactly the time the added tokens
     * consume so sub-token elapsed carries forward to the next call — and
     * decrements one token on success. A refill-semantics change lives
     * here only.
     *
     * @return {@code true} iff a token was available after refill (one is
     *         consumed); {@code false} on an empty bucket — the caller
     *         drops the inbound.
     */
    private <K> boolean tryAcquireFrom(ConcurrentHashMap<K, Bucket> map, K key,
                                       int cap, Duration refillWindow) {
        Bucket bucket = map.computeIfAbsent(key, k -> new Bucket(cap, clock.millis()));
        synchronized (bucket) {
            long now = clock.millis();
            long elapsed = Math.max(0L, now - bucket.lastRefillEpochMillis);
            long windowMs = refillWindow.toMillis();
            long refillCount = elapsed * (long) cap / windowMs;
            if (refillCount > 0) {
                bucket.tokens = (int) Math.min((long) cap, (long) bucket.tokens + refillCount);
                bucket.lastRefillEpochMillis += refillCount * windowMs / (long) cap;
            }
            if (bucket.tokens > 0) {
                bucket.tokens--;
                return true;
            }
            return false;
        }
    }

    /**
     * M1-229 shared stranger limiter. One token bucket per adapter,
     * reusing the contact cap/window, consumed by ALL unregistered
     * inbound on that adapter. {@code computeIfAbsent} (no key-space
     * cap) is safe here: the key is the adapter name, bounded by the
     * fixed enabled-adapter count, so this map cannot be grown by a
     * flood of distinct contact ids the way the per-id map could.
     */
    private boolean tryAcquireStranger(String adapter) {
        return tryAcquireFrom(strangerBuckets, adapter, inboundPerMinute, refillWindow);
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
        return tryAcquireFrom(groupBuckets, groupId, groupReplyCap, groupReplyRefillWindow);
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
        return tryAcquireFrom(groupLlmBuckets, groupId, groupLlmCap, groupLlmRefillWindow);
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
        return tryAcquireFrom(groupCommandBuckets, groupId, groupCommandCap, groupCommandRefillWindow);
    }

    /**
     * M1-705 cheap-command bucket (design §4.9). Token-bucket keyed
     * per-{@code (adapter, contactId)}, drawn by the parser-only /
     * DB-read commands that share the low cost profile (spec §Rate
     * limiting): the eight router-classified bean commands
     * ({@code /help}, {@code /status}, {@code /list-sources},
     * {@code /get-sources}, {@code /get-tags}, {@code /saved},
     * {@code /audit}, {@code /export}), {@code /quarantine list}, and
     * the asset commands (in-handler draws). Distinct from the step-1.5
     * transport bucket — draining one never drains the other. Sits
     * BEHIND the transport bucket on every path that consults it, so
     * its overflow can afford a friendly reject (outbound cost already
     * metered at step 1.5) rather than the transport bucket's silent
     * drop.
     *
     * <p>Shares the transport bucket's accepted state-volatility
     * posture: a flood-bound, not a security boundary; buckets reset on
     * Provider restart.</p>
     */
    public boolean tryAcquireCheapCommand(String adapter, String contactId) {
        return tryAcquireFrom(cheapCommandBuckets, new Key(adapter, contactId),
                cheapCommandCap, cheapCommandRefillWindow);
    }

    /**
     * Seconds until the caller's cheap-command bucket next admits a
     * command — the honest {@code {N}s} for the friendly reject (design
     * §4.9 "slow down, try again in {N}s"). Computed from bucket state
     * under the bucket monitor, never a hardcoded constant: an empty
     * bucket reports the ceiling of the time until its next lazy-refill
     * token; a bucket with tokens (or none minted yet) reports 0.
     */
    public long cheapCommandRetryAfterSeconds(String adapter, String contactId) {
        return retryAfterSeconds(cheapCommandBuckets, new Key(adapter, contactId),
                cheapCommandCap, cheapCommandRefillWindow);
    }

    /**
     * Refund one cheap-command token (M1-705) — the counterpart of the
     * group-LLM refund discipline at {@code InboundRouter}: when the
     * per-group command backstop rejects AFTER the cheap bucket
     * charged, the sender's personal budget must not drain on a fixed
     * reply. Restores at most one token (capped at the configured cap)
     * under the bucket monitor; a no-op when no bucket was minted
     * (nothing was charged).
     */
    public void refundCheapCommand(String adapter, String contactId) {
        Bucket bucket = cheapCommandBuckets.get(new Key(adapter, contactId));
        if (bucket == null) {
            return;
        }
        synchronized (bucket) {
            bucket.tokens = Math.min(cheapCommandCap, bucket.tokens + 1);
        }
    }

    /**
     * M1-705 /add-source bucket (design §4.9). Token-bucket keyed on
     * the caller's {@code users.id}, bounding the outbound-cost command
     * (every accepted {@code /add-source} drives a UrlProbe fetch) to
     * the designed 5/hour. Distinct from the cheap-command bucket — a
     * user who drains the hourly add-source budget keeps their cheap
     * commands, and vice versa. Same state-volatility posture as the
     * contact bucket (restart resets the budget; tolerated).
     */
    public boolean tryAcquireAddSource(UUID userId) {
        return tryAcquireFrom(addSourceBuckets, userId, addSourceCap, addSourceRefillWindow);
    }

    /**
     * Seconds until the caller's /add-source bucket next admits — the
     * retry delay for the explanatory over-cap reply. Same honest-from-
     * bucket-state discipline as {@link #cheapCommandRetryAfterSeconds}.
     */
    public long addSourceRetryAfterSeconds(UUID userId) {
        return retryAfterSeconds(addSourceBuckets, userId, addSourceCap, addSourceRefillWindow);
    }

    /**
     * M1-705 dedicated per-admin quarantine bucket (design §4.9's
     * 100/min row). Token-bucket keyed on the admin's {@code users.id},
     * drawn by {@code /quarantine approve} and {@code /quarantine
     * reject}. Replaces the pre-M1-705 namespaced
     * {@code tryAcquire("quarantine", adminId)} reuse, which inherited
     * the transport cap's value (60/min) instead of the designed
     * per-admin cap — an admin at the transport cap can still perform
     * quarantine actions up to this bucket's own configured cap.
     */
    public boolean tryAcquireQuarantine(UUID adminId) {
        return tryAcquireFrom(quarantineBuckets, adminId, quarantineCap, quarantineRefillWindow);
    }

    /**
     * Retry-after computation shared by the friendly-reject accessors.
     * Mirrors the lazy-refill arithmetic of {@link #tryAcquireFrom}
     * under the same bucket monitor so the reported delay can never
     * under-report: the next token lands one
     * {@code refillWindow / cap} slot after the bucket's advanced
     * {@code lastRefillEpochMillis}. Returns 0 when the bucket already
     * holds a token or has never been minted (an immediate acquire
     * would succeed); rounds UP to whole seconds otherwise so the
     * user-facing delay is never optimistic.
     */
    private <K> long retryAfterSeconds(ConcurrentHashMap<K, Bucket> map, K key,
                                       int cap, Duration refillWindow) {
        Bucket bucket = map.get(key);
        if (bucket == null) {
            return 0;
        }
        synchronized (bucket) {
            if (bucket.tokens > 0) {
                return 0;
            }
            long now = clock.millis();
            long slotMs = Math.max(1L, refillWindow.toMillis() / (long) cap);
            long nextTokenAt = bucket.lastRefillEpochMillis + slotMs;
            long waitMs = Math.max(0L, nextTokenAt - now);
            return (waitMs + 999L) / 1000L;
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
        evictIdle(cheapCommandBuckets, now - effectiveEvictionMillis(cheapCommandRefillWindow));
        evictIdle(addSourceBuckets, now - effectiveEvictionMillis(addSourceRefillWindow));
        evictIdle(quarantineBuckets, now - effectiveEvictionMillis(quarantineRefillWindow));
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

    /**
     * Test-only seam: report the current shared-stranger bucket-map
     * size (one entry per adapter that has seen unregistered inbound) so
     * {@code RateCapBucketTest} can assert a distinct-stranger flood
     * mints exactly one shared bucket and never grows the per-id map.
     * Package-private — production callers consume {@link #tryAcquire},
     * never the size.
     */
    int strangerBucketCount() {
        return strangerBuckets.size();
    }

    private record Key(String adapter, String contactId) {
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
