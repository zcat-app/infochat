# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-09 17:42
**Reviewer:** senior-developer (opus)

## Headline findings

- [medium] PERFORMANCE — `eval/reeval/ReEvaluationJob.java:444-451` — the re-eval candidate scan has no partition-pruning bound and no supporting index, so every poll tick seq-scans all `post` partitions; cost grows with total table size, not candidate count.
- [low] MAINTAINABILITY-RULES-DRIFT — `ssrf/CollectorSsrfClientProducer.java:31-38` — the CDI SSRF-client producer is injected by exactly one of eleven outbound-HTTP consumers; the other ten construct `new SsrfGuardedHttpClient()` directly, so the producer's stated "consumers obtain the guard by injection … a test can override" contract is false for almost every call site.

## Detail

### F1. Re-evaluation candidate scan reads every post partition on every tick

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:444-451` (query); supporting-index gap is cross-module in `infochat-core` V7/V22

**Current code:**

```java
final String sql =
    "SELECT id, fetched_at, stage2_failed, re_eval_attempts, stage2_verdict FROM post "
        + "WHERE ("
        + "  (stage2_failed = TRUE AND status != 'NEEDS_REVIEW')"
        + "  OR "
        + "  (status = 'QUARANTINED' AND stage2_done = TRUE AND stage2_failed = FALSE"
        + "   AND (stage2_verdict = 'UNKNOWN' OR re_eval_attempts > 0))"
        + ") ORDER BY fetched_at, id LIMIT ?";
```

**Why this is wrong / suboptimal / risky:**

`post` is partitioned by `RANGE (fetched_at)` (V7). This predicate carries no `fetched_at` lower bound, so the planner cannot prune partitions — it must touch every monthly partition that has ever existed within the retention horizon. The available indexes do not help either: `idx_post_status_fetched` is `(status, fetched_at DESC)`, but the first disjunct (`stage2_failed = TRUE AND status != 'NEEDS_REVIEW'`) has no usable `status` equality anchor (it is a negation matching RAW/READY/QUARANTINED), and `stage2_failed` / `stage2_verdict` / `re_eval_attempts` are unindexed (confirmed: no `CREATE INDEX` in any migration references these columns). The result is a full multi-partition scan of `post` on every `infochat.reeval.poll-interval` tick (5m by default; 5m on vps/remote-llm).

This is the exact problem the sibling `PerSourceUnknownTracker.checkAllSources` was deliberately engineered to avoid: it adds a `p.fetched_at >= now() - (window + PARTITION_SCAN_SLACK)` bound *specifically* so partition pruning applies, with a documented slack constant (`PartitionPruner`/`PerSourceUnknownTracker.java:40-47`). `ReEvaluationJob` runs over the same table on the same kind of cadence but omits the equivalent guard, so the two jobs disagree on whether all-partition scans are acceptable. The cost is invisible at small scale and compounds silently as the post table grows — the worst shape for a regression, because it never fails, just gets slower.

**Recommended fix:**

Add the partition-pruning `fetched_at` bound (mirroring `PerSourceUnknownTracker`) and a matching partial index in `infochat-core`. The candidate set is bounded in time anyway: a re-eval candidate is only meaningful while its partition is still retained, and `re_eval_attempts` caps the lifetime of any single post in the queue.

```java
// ReEvaluationJob.enumerateCandidates
Instant cutoff = Instant.now().minus(REEVAL_SCAN_WINDOW); // e.g. retention horizon + slack
final String sql =
    "SELECT id, fetched_at, stage2_failed, re_eval_attempts, stage2_verdict FROM post "
        + "WHERE fetched_at >= ? "
        + "  AND ("
        + "  (stage2_failed = TRUE AND status != 'NEEDS_REVIEW')"
        + "  OR "
        + "  (status = 'QUARANTINED' AND stage2_done = TRUE AND stage2_failed = FALSE"
        + "   AND (stage2_verdict = 'UNKNOWN' OR re_eval_attempts > 0))"
        + ") ORDER BY fetched_at, id LIMIT ?";
```

Pair with a partial index in a new `infochat-core` migration:

```sql
-- supports the two re-eval disjuncts; partial so it stays small
CREATE INDEX idx_post_reeval_queue ON post (fetched_at, id)
    WHERE stage2_failed = TRUE
       OR (status = 'QUARANTINED' AND stage2_done = TRUE);
```

**Reasoning:**

The `fetched_at >= ?` bound restores partition pruning so the planner skips partitions outside the window — the single most effective lever on a range-partitioned table. The partial index then turns the in-window scan into an index range scan whose size tracks the actual re-eval backlog rather than total post volume. The window must exceed the longest legitimate residence time of a re-eval candidate (a `stage2_failed` post awaiting a healthy judge), which is bounded by the post-partition retention horizon — anything older has been pruned out of the table entirely, so excluding it loses nothing.

**Trade-offs:**

A `fetched_at` window that is set shorter than the retention horizon could drop a candidate that is still physically present but older than the window. Sizing the window at "retention horizon + slack" (the same reasoning `PerSourceUnknownTracker` documents) makes that set empty by construction. The extra partial index costs write amplification on the `post` table, but it is partial (only in-flight/failed rows qualify) so it stays small.

**Alternative options:**

- **Option A** (recommended) — `fetched_at` bound + partial index.
- **Option B** — partial index only, no query change. Pros: smaller diff, no window-sizing judgment call. Cons: the index helps the planner find qualifying rows but the absence of a `fetched_at` predicate still prevents partition-level pruning, so an empty-backlog tick can still open every partition's index; weaker than Option A on a large historical table.

---

### F2. SSRF-client CDI producer is bypassed by nearly every consumer

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/ssrf/CollectorSsrfClientProducer.java:31-38` (and the eleven consumer constructors)

**Current code:**

```java
@ApplicationScoped
public class CollectorSsrfClientProducer {
    @Produces
    @Singleton
    public SsrfGuardedHttpClient ssrfGuardedHttpClient() {
        return new SsrfGuardedHttpClient();
    }
}
```

The producer's javadoc states: *"This producer supplies one shared, default-strict instance through CDI so collector consumers obtain the SSRF guard by injection rather than constructing their own."* In practice the only injection site is `NostrStreamSource.Registrar` (`@Inject SsrfGuardedHttpClient ssrfClient`). Every HTTP-shaped fetcher and asset source constructs its own instead, in its no-arg CDI constructor:

```
fetcher/rss/RssFetcher.java:54:                 this(new SsrfGuardedHttpClient());
fetcher/nitter/NitterFetcher.java:43:           this(new SsrfGuardedHttpClient());
fetcher/youtube/YouTubeFetcher.java:39:         this(new SsrfGuardedHttpClient());
fetcher/odysee/OdyseeFetcher.java:43:           this(new SsrfGuardedHttpClient());
fetcher/bluesky/BlueskyFetcher.java:52:         this(new SsrfGuardedHttpClient(), resolvePageCap());
fetcher/reddit/RedditFetcher.java:54:           this(new SsrfGuardedHttpClient(), pageCap);
assets/source/CoingeckoSnapshotSource.java:64:  this(new SsrfGuardedHttpClient());
assets/source/KrakenSnapshotSource.java:67:     this(new SsrfGuardedHttpClient());
assets/source/BitfinexSnapshotSource.java:63:   this(new SsrfGuardedHttpClient());
```

**Why this is wrong / suboptimal / risky:**

This is not a security hole — `new SsrfGuardedHttpClient()` is the same default-strict guard (real `IpBlocklist`, timeout/body/redirect caps), so all egress is still gated. It is a maintainability-drift finding: a producer exists whose documented purpose ("consumers obtain the guard by injection … a single managed client that a test can override (a CDI alternative/mock) and that future consumers share") is contradicted by ten of eleven consumers. A reader who wants to harden the guard centrally (tighten the blocklist, swap to a stricter resolver, add a CDI alternative in a test) will edit the producer and reasonably believe every outbound path now routes through it — but only the Nostr path does. The per-fetcher package-private test seam is what actually makes those classes testable, so the producer's "a test can override" rationale does not apply to them either.

The cost of the duplication is low because `SsrfGuardedHttpClient` builds a fresh per-call `java.net.http.HttpClient` inside a try-with-resources (`SsrfGuardedHttpClient.java:340`) — the instance retains no thread pool, so ~10 instances carry no meaningful runtime overhead. That is why this is low, not medium: the harm is a misleading central seam, not resource cost.

**Recommended fix:**

Pick one shape and make it consistent. The least-churn option is to delete the producer and update the Nostr `Registrar` to construct its own guard like every other consumer (it already has a package-private constructor seam used by tests). The alternative is to make the producer real by injecting it into the fetchers/asset sources.

```java
// Option A: delete CollectorSsrfClientProducer; in NostrStreamSource.Registrar
private final SsrfGuardedHttpClient ssrfClient = new SsrfGuardedHttpClient();
```

**Reasoning:**

Either direction removes the false invariant. Deleting the producer makes the codebase honest about how the guard is obtained (per-consumer construction, overridable via each class's package-private constructor seam) and removes a bean nothing meaningful depends on. Making the producer real centralizes the guard so a future hardening edit lands once — but it requires converting every fetcher/asset-source to constructor injection of the bean, a larger diff that fights the existing per-class test-seam pattern.

**Trade-offs:**

Option A loses the (currently unrealized) ability to override the guard once via a CDI alternative; given that no consumer uses that path today and each class already has its own seam, nothing is actually lost. If a future ticket genuinely wants a single overridable guard, Option B is the right shape then.

**Alternative options:**

- **Option A** (recommended) — delete the producer; Nostr `Registrar` constructs its own guard.
- **Option B** — keep the producer and convert all ten direct-construction sites to `@Inject SsrfGuardedHttpClient`. Pros: realizes the producer's stated centralization benefit. Cons: larger diff; must rework each class's no-arg/test-seam constructor pair; reintroduces the question of how tests inject a permissive blocklist (today they use the package-private constructor, which constructor injection would not remove but would sit awkwardly beside).

## Synthesizer-relevant observations

- F1's complete fix requires a new index migration in `infochat-core` (`db/migration/`), which owns the `post` schema. The collector-side query change is independently useful but the partition-pruning win depends on the paired index. Coordinate the two so the migration and the query bound land together.
