# Deep code review: module

**Target:** module infochat-collector
**Lens:** module
**Module path:**
    infochat-collector/
**Date:** 2026-06-07 01:30
**Reviewer:** senior-developer (opus)

## Headline findings

- [critical] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java:116 — No cross-tick UID dedup exists anywhere on the fetch path, so every HTTP-fetch tick re-ingests the entire feed as fresh RAW posts, violating schema.md §UID derivation ("the UID is the dedup key for refetches").
- [high] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/partition/PartitionCreator.java:56 — The partition scheduler only ever provisions next month, never the current month, so a fresh deployment after July 2026 (or a restart after a month-spanning outage) wedges every insert into all five partitioned tables for the remainder of the month.
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:198-218 — The re-eval BENIGN release path emits neither the `new_post` NOTIFY (spec: fires on every `post.status → READY`) nor the `quarantine_review` BENIGN_CLOSED NOTIFY, and promotes a post to READY that never ran tagger/entity/embedding.
- [medium] PERFORMANCE — cross-cutting (see CURRENT-CODE) — Every `@Scheduled` eval-pipeline poller uses the default `ConcurrentExecution.PROCEED`; a tick slower than its interval overlaps the next tick, double-picking the same posts (duplicate LLM/embed calls, `post_embedding`/`post_entity` PK violations).
- [medium] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java:100-105 — The per-source UNKNOWN auto-disable notification uses one global notifier key, so the one-shot disable notification for a second source within the throttle window is permanently suppressed.
- [medium] PERFORMANCE — infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java:260-276 — The semantic-candidate query's join-predicate distance shape cannot use `idx_post_embedding_hnsw`; it degenerates to a full distance scan over the time window per driving post.
- [medium] SIMPLIFICATION — infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/ — RssFetcher, NitterFetcher, OdyseeFetcher, and YouTubeFetcher are four ~90-line copies of the same GET-then-parse body, differing only in names and message prefixes.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/AdminReviewTtlJob.java:78-85 — The TTL sweep inner-joins `post`, defeating the `quarantine.post_fetched_at` denormalization that exists precisely so quarantine rows survive partition drops; PENDING rows whose post partition is gone never auto-reject.
- [low] MAINTAINABILITY-RULES-DRIFT — cross-cutting (see CURRENT-CODE) — Defensive null checks on values internal contracts already guarantee non-null (§7 violations): `Stage1Worker.onPostKey(key == null)`, `response == null` in three LLM workers, `originalBody == null` on a `@NonNull` parameter.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java:83 — `Instant.parse` on the upstream `indexedAt` propagates an exception that fails the whole tick, inconsistent with the RSS parser's deliberate null-on-malformed-date policy; RedditResponseParser similarly passes an empty `name` through instead of rejecting at the parser.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java:78-108 — Paginating fetchers (Bluesky, Reddit) implement the per-tick page cap but not the spec-committed "pagination cap hit per source" counter or saturation notification (architecture.md §Pagination cap saturation).
- [low] SIMPLIFICATION — infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoader.java:358-371 — `sha256Hex` reimplements `app.zcat.infochat.core.util.Sha256.hex` (already used by the sibling BootstrapLoader); `readBigDecimal` is likewise triplicated verbatim across the three asset sources.

## Detail

### F1. No cross-tick dedup on the fetch path — every tick re-ingests the full feed

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** critical
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java:106-117 (with FetchScheduler.java:230-237 as the calling path)

**Current code:**

```java
final String sql =
    "INSERT INTO post ("
        + "  id, uid, source_id, upstream_identifier, url, title, body, "
        ...
        + ") "
        + "ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING "
        + "RETURNING id, fetched_at";
```

The only dedup gate is the `(source_id, upstream_identifier, fetched_at)` UNIQUE. Every fetcher stamps a fresh `fetchedAt = Instant.now()` per tick (e.g. `RssFetcher.fetch`), so the conflict can only fire for duplicates *within a single tick*. V7's own comment assigns the missing half elsewhere:

```sql
-- UNIQUE (uid, fetched_at) is per-window dedup; cross-window dedup
-- (a re-fetched item landing in a later partition) is the fetcher's
-- responsibility per docs/spec/schema.md §UID derivation.
```

No fetcher, nor `FetchScheduler.tickOnce`, nor `PostPersister`, implements that responsibility. A grep across `src/main/java` confirms there is no `WHERE uid = ?` existence check or "what's new since last time" query anywhere on the polled path. `PostPersisterIT.persistIsNoOpOnDuplicateSourceUpstreamFetchedAt` only exercises the same-`fetched_at` case.

**Why this is wrong / suboptimal / risky:**

`docs/spec/schema.md` §Posts: "The UID is unique globally and is the dedup key for refetches and cross-relay redelivery (decision D38)." `docs/spec/architecture.md` §Ingest SPIs: "The fetcher is stateless between ticks; 'what's new since last time' is a query against `posts`, not in-memory state." Neither commitment is implemented.

Concrete consequence: an RSS feed serving its usual 50 most-recent items, polled at the configured `infochat.fetch.rss.interval=5m`, inserts 50 brand-new RAW rows every 5 minutes — ~14,400 duplicate posts per source per day. Each duplicate runs Stage 1, tagger LLM call, entity-extraction LLM call, and an embedding call, then reaches `READY` and fires `new_post`. Users see the same article repeated on every `/summary`; the LLM budget burns continuously on already-evaluated content; the post partitions and the HNSW index bloat without bound. The Nostr path has the same hole at a smaller scale: the in-memory `NostrDedupFilter` is per-process, and NIP-01 `since` is inclusive, so the newest already-persisted event re-inserts once per Collector restart (NostrDedupFilter's own javadoc concedes the DB constraint "does NOT catch two writes for the same upstream id with different fetched_at").

**Recommended fix:**

```java
// PostPersister.persist — make the INSERT conditional on global UID absence.
// Single Collector instance (D41 advisory lock) means no INSERT race to defend.
final String sql =
    "INSERT INTO post (id, uid, source_id, ...) "
        + "SELECT gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RAW', "
        + "       FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, '{}' "
        + "WHERE NOT EXISTS (SELECT 1 FROM post WHERE uid = ?) "
        + "ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING "
        + "RETURNING id, fetched_at";
```

plus a non-unique index on `post (uid)` (a plain index is legal on a partitioned table; only unique constraints must embed the partition key).

**Reasoning:**

`uid = sha256(source_id || '|' || upstream_identifier)` is already computed in `deriveUid` and is the spec's designated dedup key. The `NOT EXISTS` arm makes refetch dedup global across partitions while keeping the existing same-tick conflict arm. The empty-`Optional` return already short-circuits the eval-queue emit, so no caller changes. This also closes the Nostr per-restart duplicate.

**Trade-offs:**

- One extra index on `post(uid)` and an index probe per insert — negligible against the LLM cost of a duplicate evaluation.
- Posts re-published upstream with the same guid but changed content stay deduped (no re-evaluation). That is exactly what schema.md §UID derivation commits to, so it is not a regression.
- The dedup horizon equals the post retention horizon: after the partition holding the original drops, a refetch re-ingests. That matches the TTL model.

**Alternative options:**

- **Option A** (the recommended fix above).
- **Option B** — fetch-side "what's new since last time": each fetcher (or the scheduler) queries `SELECT upstream_identifier FROM post WHERE source_id = ? AND fetched_at > now() - retention` and filters before persisting — pros: batch-shaped, matches the architecture.md wording literally — cons: per-tick read of potentially large id sets, logic duplicated per fetcher, still needs the persister gate as backstop.
- **Option C** — `published_at`-cursor filtering like the Nostr `since` cursor — cons: RSS/Reddit items are routinely edited/bumped and `published_at` is nullable; unsound as the sole gate.

---

### F2. PartitionCreator never provisions the current month

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/partition/PartitionCreator.java:54-70

**Current code:**

```java
@Scheduled(every = "{infochat.partitions.check-interval}")
void onTick() {
    YearMonth nextMonth = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
    try {
        provision(nextMonth);
        lastSuccessfulRun = Instant.now();
    } catch (SQLException e) {
        LOG.errorf(e, "Partition provisioning for %s failed", nextMonth);
    }
    ...
}
```

**Why this is wrong / suboptimal / risky:**

The migrations pre-provision fixed months only (V7/V11/V28/V29: 2026-05; V30: 2026-06 and 2026-07). The scheduler provisions exclusively `now + 1 month`. Two realistic scenarios leave the **current** month without a partition, and nothing ever creates it:

1. **Fresh deployment after July 2026.** Flyway creates the May–July 2026 partitions; the first tick creates next month's; the deploy month itself has no partition. With no DEFAULT partition (deliberate, Invariant 6), every `INSERT INTO post / post_embedding / post_entity / post_reference / price_snapshot` fails with "no partition of relation found for row" until the month rolls over or an operator hand-writes DDL.
2. **Outage spanning a whole month.** A Collector down for the entirety of month M never runs M's tick that would have created M+1; restarting in M+1 provisions M+2 but not M+1.

The 25-day liveness WARN does not catch either case — `lastSuccessfulRun` is freshly seeded at construction and the next-month provisioning *succeeds*.

**Recommended fix:**

```java
@Scheduled(every = "{infochat.partitions.check-interval}")
void onTick() {
    YearMonth current = YearMonth.now(ZoneOffset.UTC);
    try {
        provision(current);            // idempotent: CREATE TABLE IF NOT EXISTS
        provision(current.plusMonths(1));
        lastSuccessfulRun = Instant.now();
    } catch (SQLException e) {
        LOG.errorf(e, "Partition provisioning failed", e);
    }
    ...
}
```

Additionally run the same provisioning once at startup (`@Startup`/`@PostConstruct` or `@Observes StartupEvent`) instead of waiting up to 24h for the first tick.

**Reasoning:**

`CREATE TABLE IF NOT EXISTS` makes the extra call a no-op in the steady state, while guaranteeing the active month is always covered regardless of deployment date or downtime history. The startup invocation closes the window where a freshly deployed Collector accepts fetch work for up to one `check-interval` before any partition check has run.

**Trade-offs:**

None — the fix is strictly better. One extra idempotent DDL statement per table per day.

---

### F3. Re-eval BENIGN release: missing `new_post` / `quarantine_review` NOTIFYs and skipped pipeline stages

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:129-150, 198-218

**Current code:**

```java
private void promoteToReady(Connection conn, ReEvalCandidate candidate) throws SQLException {
    final String sql =
        "UPDATE post SET status = 'READY', ready_at = now(), status_changed_at = now(), "
            + "re_eval_attempts = re_eval_attempts + 1 "
            + "WHERE id = ? AND fetched_at = ?";
    ...
}

private void closeQuarantineRows(Connection conn, UUID postId) throws SQLException {
    final String sql =
        "UPDATE quarantine SET status = 'BENIGN_CLOSED', updated_at = now() "
            + "WHERE post_id = ? AND status = 'PENDING'";
    ...
}
```

**Why this is wrong / suboptimal / risky:**

Three drifts against the spec, all in `applyBenignReEval`:

1. **Missing `new_post` NOTIFY.** `architecture.md` §Inter-service communication: "`new_post` — fires on `post.status → READY`." `promoteToReady` performs exactly that transition with no `pg_notify('new_post', ...)`. `ReadyPromoter.promoteOne` and the spec'd `approve_quarantine` procedure both emit it. Without the NOTIFY, the released post becomes visible only when an unrelated later post's NOTIFY (or a Provider restart) advances the catch-up cursor past it — unbounded latency on a quiet deployment.
2. **Missing `quarantine_review` NOTIFY for `BENIGN_CLOSED`.** The channel contract is "all quarantine state-machine moves visible to the Provider role" — `BENIGN_CLOSED` is in the closed transition list. `Stage2VerdictHandler.applyBenign` dutifully emits it (`emitQuarantineNotifyForClosedRows`); `closeQuarantineRows` here does not, so the same logical transition emits or not depending on which code path performed it.
3. **READY without tagger/entity/embedding.** An UNKNOWN-verdict post was quarantined immediately after Stage 2 — tagger, entity extraction, and embedding never ran (`status='RAW'` is their pickup gate). `promoteToReady` flips it straight to READY with `tags = '{}'`, no embedding, no entities, contradicting `architecture.md` §Pipelines ("`READY` promotion waits for both to finish") and silently bypassing the tagger's bootstrap-tags fallback guarantee. The post is invisible to every tag-filtered retrieval path forever.

**Recommended fix:**

```java
// (a) in applyBenignReEval's transaction, after closeQuarantineRows:
emitQuarantineReviewBenignClosed(conn, candidate.postId()); // mirror Stage2VerdictHandler

// (b) for the UNKNOWN class, release back into the pipeline instead of
// flipping READY directly: the remaining stages then run and ReadyPromoter
// performs the READY flip + new_post NOTIFY through the standard path.
private void releaseToPipeline(Connection conn, ReEvalCandidate candidate) throws SQLException {
    final String sql =
        "UPDATE post SET status = 'RAW', status_changed_at = now(), "
            + "re_eval_attempts = re_eval_attempts + 1 "
            + "WHERE id = ? AND fetched_at = ?";
    ...
}
```

**Reasoning:**

Routing the release through `status='RAW'` makes the existing tagger/entity/embedding pollers and `ReadyPromoter` do their jobs: the post receives tags (or bootstrap fallback), an embedding, and the spec-mandated `new_post` NOTIFY in the same transaction as the READY flip — one mechanism, no duplicated NOTIFY-emission code. The `quarantine_review` emit reuses the exact pattern already present in `Stage2VerdictHandler`.

**Trade-offs:**

- `security.md` §Re-evaluation job literally words the transition as "`QUARANTINED → READY`", while `architecture.md` requires READY to wait for all stages — the two spec files are in tension. Option A satisfies the architecture invariant and the NOTIFY contract at the cost of a `spec:` clarification commit for the security.md wording. If the literal `QUARANTINED → READY` wording is authoritative, the minimal alternative is Option B.

**Alternative options:**

- **Option A** (the recommended fix above).
- **Option B** — keep the direct READY flip but add `pg_notify('new_post', ...)` (payload `{ready_at, post_id}`) inside the same transaction, plus the `quarantine_review` emit — pros: matches security.md's literal wording, smaller diff — cons: permanently tag-less/embedding-less READY posts remain.

---

### F4. Scheduled pollers allow overlapping executions (no `ConcurrentExecution.SKIP`)

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// EmbeddingWorker.java:164
@Scheduled(every = "{infochat.embeddings.poll-interval}")
public void onTick() { ... processBatch(pending); }

// EntityExtractorWorker.java:178
@Scheduled(every = "{infochat.llm.entity.poll-interval}")
public void onTick() { ... }

// TaggerWorker.java:187
@Scheduled(every = "{infochat.llm.tagger.poll-interval}")
public void onTick() { ... }

// FetchScheduler.java:173, ReEvaluationJob.java:86, LinkingJob.java:123,
// AdminReviewTtlJob.java:57 — same shape.
```

**Why this is wrong / suboptimal / risky:**

Quarkus `@Scheduled` defaults to `ConcurrentExecution.PROCEED`: if a tick outlives its interval, the next tick runs concurrently with it. The eval pollers tick every 5 seconds and perform LLM/embedding HTTP calls that routinely exceed 5 seconds on local Ollama. The pickup queries claim nothing (no `FOR UPDATE SKIP LOCKED`, no claimed-at flag — the `*_done` flag only advances after the slow work completes), so an overlapping tick enumerates the same posts:

- `EmbeddingWorker`: tick 2 re-embeds the same batch (duplicate paid/expensive embed call), then its `INSERT INTO post_embedding` violates `PRIMARY KEY (post_id, fetched_at)` and the whole transaction rolls back with an `IllegalStateException` into the scheduler log. The semaphore bounds in-flight calls but not duplicate pickup.
- `EntityExtractorWorker`: same — duplicate LLM call, then a `post_entity` PK violation.
- `TaggerWorker`: duplicate LLM call; last write wins silently.
- `FetchScheduler`: `lastTickByKind` is updated only after the whole heartbeat completes, so an overlapping heartbeat re-fetches every source (the dedup that would absorb this is F1).

Single-instance deployment (D41) means same-method overlap is the only race, and it is fully eliminated by the annotation.

**Recommended fix:**

```java
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;

@Scheduled(every = "{infochat.embeddings.poll-interval}",
           concurrentExecution = ConcurrentExecution.SKIP)
public void onTick() { ... }
```

Apply to `EmbeddingWorker`, `EntityExtractorWorker`, `TaggerWorker`, `ReadyPromoter`, `FetchScheduler`, `ReEvaluationJob`, `LinkingJob`, `AdminReviewTtlJob`, and `AssetSnapshotFetcher`'s three per-host ticks.

**Reasoning:**

`SKIP` makes "at most one execution of this poller at a time" a declarative property. Each poller's per-tick batch limit already bounds work; skipped ticks are simply absorbed by the next one. No claim-marking SQL or locking is needed.

**Trade-offs:**

None — the fix is strictly better for these pollers. (It does not protect against multiple Collector instances, but D41's advisory lock already excludes that topology.)

---

### F5. Per-source UNKNOWN auto-disable notifications coalesce across sources

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java:99-105

**Current code:**

```java
if (updated > 0) {
    throttledAdminNotifier.notifyOnce(
        ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE,
        ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE,
        "Source " + sourceId + " auto-disabled: UNKNOWN rate " ...);
}
```

**Why this is wrong / suboptimal / risky:**

`notifyOnce` emits at most once per `(key, throttle-window)` — default window 1h. The key here is the global constant `"source-unknown-auto-disable"`. The `active → failed` flip is one-shot per source (the `AND status='active'` guard), so each source produces exactly one `notifyOnce` call, ever. If source B crosses the threshold 10 minutes after source A, B's notification is suppressed and never retried — the operator learns B was silently disabled only by noticing missing posts. `security.md` §Per-source UNKNOWN auto-disable commits to "a throttled admin notification fires citing the source id, the observed UNKNOWN rate, and the threshold" per disable. Every sibling ladder in this module already keys per target: `FetchScheduler` uses `"fetch_failure_ladder:" + row.uuid()`, `AssetSnapshotFetcher` uses `"asset-source-failed:" + asset + ":" + subVerb`, the Nostr Registrar uses `"nostr-source-failed:" + sourceUuid`.

**Recommended fix:**

```java
throttledAdminNotifier.notifyOnce(
    ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE + ":" + sourceId,
    ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE,
    "Source " + sourceId + " auto-disabled: UNKNOWN rate " ...);
```

**Reasoning:**

Per-source keys give exactly one EMITTED per source's `active → failed` transition — the same shape as every other D42 ladder in the module. Key cardinality is bounded by the operator-controlled source row count (the same argument SourceRepository's javadoc already makes).

**Trade-offs:**

None — the fix is strictly better and matches the established convention.

---

### F6. Semantic-linking query bypasses the HNSW index

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java:260-276

**Current code:**

```java
final String sql =
    "SELECT pe2.post_id, (pe1.embedding <=> pe2.embedding) AS distance "
        + "  FROM post_embedding pe1 "
        + "  JOIN post_embedding pe2 ON pe2.post_id <> pe1.post_id "
        + " WHERE pe1.post_id = ? "
        + "   AND pe1.fetched_at = ? "
        + "   AND pe2.fetched_at >= ? "
        + "   AND (pe1.embedding <=> pe2.embedding) < ? "
        ...
        + " ORDER BY distance ASC, pe2.post_id ASC "
        + " LIMIT ?";
```

**Why this is wrong / suboptimal / risky:**

pgvector's HNSW index is used only for the shape `ORDER BY embedding_column <=> <constant/parameter> LIMIT k`. Here the distance operand is `pe1.embedding`, a join column — the planner cannot drive the `pe2` side through `idx_post_embedding_hnsw`, so every driving post forces a sequential distance computation over every `post_embedding` row in the 48h window. Cost per LinkingJob tick is `O(batch × window-size)` 768-dim cosine evaluations and grows linearly with ingest rate — exactly the "perf regression that compounds as data grows" pattern, and the category's "wrong-shape pgvector index usage". At small v1 volumes this is tolerable; at a few thousand posts per window it dominates the tick and competes with the eval pipeline for CPU and pool connections.

**Recommended fix:**

```java
// 1) Load the driving vector once (tiny query by PK):
//    SELECT embedding FROM post_embedding WHERE post_id = ? AND fetched_at = ?
// 2) Run the candidate scan with the vector as a bound constant so the
//    ORDER BY matches the HNSW shape:
final String sql =
    "SELECT post_id, (embedding <=> ?::vector) AS distance "
        + "  FROM post_embedding "
        + " WHERE fetched_at >= ? AND post_id <> ? "
        + " ORDER BY embedding <=> ?::vector "
        + " LIMIT ?";          // K = maxLinksPerPost + headroom
// 3) Apply the < semanticThreshold filter and the NOT EXISTS dedup on the
//    returned candidates (in SQL via a wrapping query, or in Java — the
//    candidate list is ≤ K rows).
```

**Reasoning:**

With a constant query vector, Postgres performs an HNSW index scan and stops after K neighbours instead of scoring the whole window. Threshold and dedup filtering over ≤ K returned rows is cheap. The over-fetch headroom compensates for candidates eliminated by the threshold/dedup filters.

**Trade-offs:**

- HNSW is approximate: a true neighbour can be missed (recall < 1.0), whereas the current sequential scan is exact. For a best-effort link graph that is an accepted trade — the index was created for exactly this workload.
- The `fetched_at >= ?` filter is applied post-scan by the index path, so K must include headroom (or `hnsw.ef_search` raised) to avoid under-filling; if most of the table lies outside the window the over-fetch grows. Mitigated by partition pruning once old partitions drop.
- Two statements instead of one.

---

### F7. Four copy-pasted RSS-shaped fetchers

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFetcher.java:66-92 (and nitter/NitterFetcher.java, odysee/OdyseeFetcher.java, youtube/YouTubeFetcher.java — same body)

**Current code:**

```java
// RssFetcher.fetch — NitterFetcher / OdyseeFetcher / YouTubeFetcher repeat
// this verbatim, changing only the exception class and the message prefix.
Instant fetchedAt = Instant.now();
HttpResponse<byte[]> response;
try {
    response = client.get(URI.create(identifier));
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RssFetchException("RSS fetch interrupted for " + UrlRedactor.redact(identifier), e);
} catch (IOException e) {
    throw new RssFetchException("RSS fetch I/O failure for " + UrlRedactor.redact(identifier) + ": " + e.getMessage(), e);
}
int status = response.statusCode();
if (status < 200 || status >= 300) {
    throw new RssFetchException("RSS fetch got HTTP " + status + " for " + UrlRedactor.redact(identifier));
}
return RssFeedParser.parse(sourceId, response.body(), fetchedAt);
```

**Why this is wrong / suboptimal / risky:**

Four classes (~370 lines total) carry the same constructor seam, the same three-arm try/catch ladder, the same status check, and the same delegation to `RssFeedParser` — only the kind label varies. Every future RSS-shaped kind (and every fix to the ladder, e.g. a new redaction rule) must be replicated four-plus times; divergence under §1 surgical-change discipline is inevitable. This is past the "three similar lines beats a premature abstraction" threshold — it is four similar classes.

**Recommended fix:**

```java
// fetcher/RssShapedFetch.java — package-private static helper.
final class RssShapedFetch {
    static List<NormalizedPost> fetch(String kindLabel, SsrfGuardedHttpClient client,
                                      long sourceId, String identifier) {
        Instant fetchedAt = Instant.now();
        HttpResponse<byte[]> response;
        try {
            response = client.get(URI.create(identifier));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RssShapedFetchException(kindLabel + " fetch interrupted for "
                + UrlRedactor.redact(identifier), e);
        } catch (IOException e) {
            throw new RssShapedFetchException(kindLabel + " fetch I/O failure for "
                + UrlRedactor.redact(identifier) + ": " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RssShapedFetchException(kindLabel + " fetch got HTTP " + status
                + " for " + UrlRedactor.redact(identifier));
        }
        return RssFeedParser.parse(sourceId, response.body(), fetchedAt);
    }
}

// Each concrete fetcher shrinks to the CDI shell:
@FetcherKind("nitter")
@ApplicationScoped
public class NitterFetcher implements Fetcher {
    private final SsrfGuardedHttpClient client;
    public NitterFetcher() { this(new SsrfGuardedHttpClient()); }
    NitterFetcher(SsrfGuardedHttpClient client) { this.client = client; }
    @Override
    public List<NormalizedPost> fetch(long sourceId, String identifier) {
        return RssShapedFetch.fetch("Nitter", client, sourceId, identifier);
    }
}
```

**Reasoning:**

One helper, four ~15-line shells. The CDI identity per kind (needed by the `@FetcherKind` qualifier) is preserved; the D42 error-class string in the scheduler log keeps its kind label via the message prefix. Net removal of roughly 250 lines.

**Trade-offs:**

- The per-kind exception types collapse into one (`RssShapedFetchException`); `FetchScheduler` only logs `e.getClass().getSimpleName()`, so the log error-class loses the kind name — compensated by the kind already appearing in the scheduler's own log line and the message prefix.

---

### F8. Duplicated utility helpers (`sha256Hex`, `readBigDecimal`)

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/bootstrap/BootstrapAssetsLoader.java:358-371; assets/source/{Coingecko,Kraken,Bitfinex}SnapshotSource.java (identical private `readBigDecimal`)

**Current code:**

```java
// BootstrapAssetsLoader.java
private static String sha256Hex(byte[] data) {
    MessageDigest md;
    try {
        md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable in this JRE", e);
    }
    byte[] digest = md.digest(data);
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
        hex.append(String.format("%02x", b & 0xff));
    }
    return hex.toString();
}
```

**Why this is wrong / suboptimal / risky:**

`app.zcat.infochat.core.util.Sha256.hex(byte[])` already exists and is used by the sibling `BootstrapLoader` in the same package for the identical purpose (bootstrap-file content digest). Reimplementing it locally is dead weight and risks formatting divergence between the two loaders' audit `target_id` values. Similarly, the three asset sources each carry a verbatim copy of the same 13-line `readBigDecimal(JsonNode)` null-tolerant parser.

**Recommended fix:**

```java
// BootstrapAssetsLoader:
String sha256 = Sha256.hex(bytes);   // delete the local sha256Hex

// assets/source/JsonNumbers.java (package-private):
static @Nullable BigDecimal readBigDecimal(JsonNode node) { ...one copy... }
```

**Reasoning:**

Removes ~40 duplicate lines and pins both bootstrap loaders to the same digest rendering.

**Trade-offs:**

None — the fix is strictly better.

---

### F9. Defensive null checks inside the trust boundary (§7)

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** cross-cutting (see CURRENT-CODE)

**Current code:**

```java
// Stage1Worker.java:87-90 — payload comes from the in-process channel whose
// sole producer emits non-null keys:
public void onPostKey(PostPersister.PersistedPostKey key) {
    if (key == null) {
        return;
    }

// EntityExtractorWorker.java:241, TaggerWorker.java:298, Stage2Worker.java:204 —
// LlmProvider.generate is declared non-null-returning (null-marked package):
String text = response == null ? null : response.text();

// Stage2Worker.java:199 — originalBody is a @NonNull parameter:
.replace("{{content}}", originalBody == null ? "" : originalBody);
```

**Why this is wrong / suboptimal / risky:**

§7 (engineering-rules-verbatim.md): "no null-checks for parameters that callers cannot legally pass null for" inside the trust boundary. All four sites guard values whose contracts NullAway already enforces: the eval-queue is an in-process channel fed only by `EvalQueueProducer`; `LlmProvider.generate` returns a bare (non-null) `LlmResponse` under the null-marked package default; `judgeBody`'s `originalBody` parameter is explicitly `@NonNull`. The checks are unreachable branches that muddy the contract ("can this actually be null?") and contradict the machine-checked signatures one line above them.

**Recommended fix:**

```java
// Stage1Worker
public void onPostKey(PostPersister.PersistedPostKey key) {
    PostRow row;
    ...

// workers
String text = response.text();

// Stage2Worker.tryOnce
.replace("{{content}}", originalBody);
```

**Reasoning:**

Deleting the dead guards restores the §7a property that the signature is the contract. If a test double genuinely returns null, NullAway-onboarded test sources (or the stub's own contract) are the right place to fix it, not production branches.

**Trade-offs:**

None — the fix is strictly better; behavior in legal executions is unchanged.

---

### F10. AdminReviewTtlJob's inner join defeats the quarantine denormalization

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/AdminReviewTtlJob.java:78-85

**Current code:**

```java
final String sql =
    "SELECT q.id, q.post_id, p.fetched_at "
        + "FROM quarantine q "
        + "JOIN post p ON p.id = q.post_id AND p.fetched_at = q.post_fetched_at "
        + "WHERE q.status = 'PENDING' "
        + "  AND q.flagged_at <= ? "
        ...
```

**Why this is wrong / suboptimal / risky:**

`quarantine.post_fetched_at` (and `post_uid`) are denormalized precisely so quarantine rows remain self-sufficient after the parent post's partition is dropped (V10: "denormalized for survival past partition drop" — QuarantineDao's javadoc repeats it). The inner `JOIN post` reintroduces the dependency: once the T2 partition pruner lands and a partition holding a post with a still-PENDING quarantine row is dropped, that row falls out of the enumeration forever and Invariant 6's "a `PENDING` quarantine row aged past the admin-review TTL ... auto-`reject`s" stops holding — the row pollutes `/quarantine list` indefinitely. The join also fetches nothing the table doesn't already have (`p.fetched_at = q.post_fetched_at` by the join condition itself).

**Recommended fix:**

```java
final String sql =
    "SELECT id, post_id, post_fetched_at "
        + "FROM quarantine "
        + "WHERE status = 'PENDING' "
        + "  AND flagged_at <= ? "
        + "ORDER BY flagged_at "
        + "LIMIT ?";
```

The follow-up `UPDATE post ... WHERE id = ? AND fetched_at = ? AND status = 'NEEDS_REVIEW'` already tolerates a missing post (0 rows updated).

**Reasoning:**

Reading the denormalized column removes the join entirely, is cheaper, and makes the TTL sweep correct independent of post retention — the property the denormalization was built for.

**Trade-offs:**

None — the fix is strictly better. (Today, with no pruner shipped, behavior is identical.)

---

### F11. Inconsistent upstream-input tolerance in the Bluesky/Reddit parsers

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java:82-83; fetcher/reddit/RedditResponseParser.java:58-66

**Current code:**

```java
// BlueskyResponseParser
String indexedAtRaw = textOrNull(postNode, "indexedAt");
Instant publishedAt = indexedAtRaw != null ? Instant.parse(indexedAtRaw) : null;

// RedditResponseParser
return new NormalizedPost(
    sourceId,
    data.path("name").asText(),   // "" when the field is absent
    ...
```

**Why this is wrong / suboptimal / risky:**

The RSS/Atom parser deliberately maps malformed dates to `null` ("publishedAt ← parsed ..., nullable on parse failure") so one bad item cannot fail the batch. The Bluesky parser instead lets `Instant.parse` throw `DateTimeParseException` on a malformed `indexedAt`, failing the entire tick and feeding the D42 failure ladder — five consecutive malformed responses auto-disable the source over a cosmetic field. The Reddit parser converts a missing `name` to the empty string; the empty identifier then reaches `PostPersister`, which throws `IllegalArgumentException` *for the whole tick* with a message blaming an "SPI contract violation" — but schema.md §UID derivation says ID-less items "are rejected at the Fetcher boundary; they never reach the outbox", i.e. the parser is the right rejection point with the right error wording.

**Recommended fix:**

```java
// Bluesky — match the RSS parser's tolerance:
Instant publishedAt = parseInstantOrNull(indexedAtRaw);

private static @Nullable Instant parseInstantOrNull(@Nullable String raw) {
    if (raw == null) return null;
    try { return Instant.parse(raw); } catch (DateTimeParseException e) { return null; }
}

// Reddit — reject at the parser with a per-source-actionable message:
String name = data.path("name").asText();
if (name.isEmpty()) {
    throw new BlueskyParseException-style RedditParseException(
        "Reddit listing child missing 'name'; cannot derive upstreamIdentifier");
}
```

**Reasoning:**

`publishedAt` is a nullable, non-load-bearing field; degrading it must not cost a tick (let alone a source). The identifier *is* load-bearing, so failing loudly is right — but at the fetcher boundary the spec names, with a message describing the upstream malformation rather than an internal SPI violation.

**Trade-offs:**

A genuinely malformed `indexedAt` becomes silent (null `published_at`) instead of visible via tick failure. That is the same trade the RSS parser already made; the spec's failure ladder is for transport/parse failures, not per-field degradation.

---

### F12. Pagination-cap saturation counter not implemented

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java:78-108; fetcher/reddit/RedditFetcher.java:70-107

**Current code:**

```java
for (int page = 0; page < pageCap; page++) {
    ...
    cursor = parsed.cursor();
    if (cursor == null) {
        break;
    }
}
return Collections.unmodifiableList(allPosts);
```

**Why this is wrong / suboptimal / risky:**

`architecture.md` §Pagination cap saturation: "Fetchers expose a per-tick 'pagination cap hit per source' counter. When a single source consistently saturates the cap across multiple ticks ..., a throttled admin notification fires once per saturation transition." Both paginating fetchers exit the loop silently when the cap is reached with a non-null cursor still outstanding. Nothing records the saturation, no counter exists, and no notification path can ever fire — a source persistently producing faster than a tick can drain is invisible to the operator.

**Recommended fix:**

```java
// Fetcher signals saturation to the scheduler (the component that owns
// per-source state and the notifier). Minimal shape: log with a canonical
// error_class the future notifier can coalesce on, mirroring the
// ERROR_CLASS_* convention used by Stage1Pipeline/EmbeddingWorker:
if (page == pageCap - 1 && cursor != null) {
    LOG.warnf("Bluesky source saturated page cap %d with cursor outstanding "
        + "(error_class=fetch.pagination_cap_saturated, source dispatch=%d)",
        pageCap, sourceId);
}
```

**Reasoning:**

The spec commitment is a counter plus a transition-throttled notification; the multi-tick threshold logic belongs with the scheduler/notifier, but the saturation *signal* must originate in the fetcher loop, and today it does not exist at all. Landing the canonical error-class log line is the established incremental pattern in this module (Stage1Pipeline, EmbeddingWorker did the same ahead of the T2-G notifier).

**Trade-offs:**

The log-line-only form defers the "consistently across multiple ticks" thresholding to a follow-up; until then operators must grep. Strictly better than silence.

---

## Synthesizer-relevant observations

- Hand-written `@NonNull` persists throughout this module (156 occurrences across 37 main-source files) and in `infochat-core`, while §7a / D48 and the parent pom comment state "No `@NonNull` is written — non-null is the default." Cross-module cleanup decision, not a per-module finding.
- `TagVocabulary` loads the controlled vocabulary once at startup; correct for the v1 writer set (bootstrap loaders run earlier), but the Provider-side `/add-source --tags` command (T1-F) will mint tag rows the Collector tagger then silently drops until restart — the refresh path is a cross-module contract to settle when that command lands.
- The `new_price_snapshot` NOTIFY payload key reconciliation (`source` vs `sub_verb`) between commands.md and architecture.md is handled in `PriceSnapshotStore` by comment; the architecture lens may want to confirm the Provider-side reader (M1-055c) agrees.
