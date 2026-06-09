# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-08 18:42
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] PERFORMANCE — LinkingJob.java:257-296 — the semantic-link self-join `WHERE (pe1.embedding <=> pe2.embedding) < ?` cannot use the HNSW index and degrades to a per-driving-post scan of every candidate embedding row in the time window.
- [medium] MAINTAINABILITY-RULES-DRIFT — EmbeddingWorker.java:241-256 — the "fatal" dimensionality-mismatch path throws every tick forever with no admin notification and no scheduler halt, producing a silent retry/log-spam loop instead of a surfaced operator-action signal.
- [low] MAINTAINABILITY-RULES-DRIFT — QuarantineDao.java:46-52 / Stage1Pipeline.java:341-342 — quarantine `span_start`/`span_end` are documented as "byte offsets" but are actually Java char (UTF-16) offsets.
- [low] SIMPLIFICATION — AssetSnapshotFetcher.java:119-129 — three `@ConfigProperty Duration` fields are injected, `@SuppressWarnings("unused")`, and never read; they are speculative scaffolding kept only to satisfy an acceptance contract.

## Detail

### F1. Semantic-link query cannot use the pgvector HNSW index

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/linking/LinkingJob.java:257-296

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
        + "   AND NOT EXISTS ( ... ) "
        + " ORDER BY distance ASC, pe2.post_id ASC "
        + " LIMIT ?";
```

**Why this is wrong / suboptimal / risky:**

pgvector's HNSW index (`idx_post_embedding_hnsw`, `vector_cosine_ops`, declared in
`infochat-core/.../V11__post_embedding.sql:83`) accelerates exactly one query shape:
`ORDER BY embedding <=> <constant-vector> LIMIT k`. The planner uses the index only
when one side of the `<=>` operator is a parameter/constant known at plan time and the
query is a top-k ordering.

Here both sides of `<=>` are *column references* from a self-join (`pe1.embedding` and
`pe2.embedding`). `pe1.embedding` is not a constant — it is a column value resolved per
joined row — so PostgreSQL cannot probe the HNSW index with it. The plan degrades to:
for the single `pe1` row selected by `pe1.post_id = ?`, scan every `pe2` row whose
`fetched_at >= semanticCutoff` and compute the distance for each, then filter on the
`< threshold` predicate and sort. That is a sequential/partition scan of the entire
co-temporal embedding set per driving post.

The cost compounds with data growth, which is precisely the `PERFORMANCE` bar ("a perf
regression that compounds as data grows"). At steady state the job processes up to 64
driving posts per tick (`DRIVING_BATCH_SIZE`); each one scans the full
`semantic-window-hours` slice of `post_embedding`. On a busy deployment that slice is the
bulk of recent ingest. The HNSW index is paid for (build + maintenance cost on every
INSERT) but never used by the one query that exists to consume it.

This also silently contradicts the architecture's pgvector-index commitment: the profile
selects HNSW vs IVFFlat to make vector retrieval fast, but the only collector-side vector
query bypasses the index entirely.

**Recommended fix:**

Rewrite the semantic search to feed the driving vector as a constant to a top-k
index probe. Read the driving post's vector first, then issue an indexable ANN query:

```java
// Step 1: fetch the driving vector (single-row PK lookup).
String drivingVector = readDrivingEmbedding(conn, driving); // "[..]" or null → skip semantic
if (drivingVector == null) {
    return List.of();
}

// Step 2: indexable top-k ANN probe — the driving vector is now a bound
// parameter, so the HNSW index drives the ORDER BY ... LIMIT.
final String sql =
    "SELECT pe2.post_id, (pe2.embedding <=> ?::vector) AS distance "
        + "  FROM post_embedding pe2 "
        + " WHERE pe2.post_id <> ? "
        + "   AND pe2.fetched_at >= ? "
        + "   AND (pe2.embedding <=> ?::vector) < ? "
        + "   AND NOT EXISTS ( ... ) "
        + " ORDER BY pe2.embedding <=> ?::vector "
        + " LIMIT ?";
```

Bind `drivingVector` to the `?::vector` slots. The `ORDER BY embedding <=> <param>` form
is the canonical HNSW-probe shape; the `< threshold` filter still applies post-probe.

**Reasoning:**

Moving the driving vector out of the join and into a bound parameter is the one change
that lets the planner use HNSW. The two-statement approach (read vector, then probe) is
how pgvector ANN search is meant to be issued from application code; the self-join form
is a textbook anti-pattern for ANN indexes. Result quality is unchanged: the candidate
set, threshold, dedup, and cap are identical; only the access path changes from full scan
to index probe.

Note one ANN caveat: with an HNSW probe you must request enough candidates
(`LIMIT` ≥ `maxLinksPerPost`, and consider `ef_search`) because the index returns
approximate nearest neighbors. Since the job already caps at `maxLinksPerPost` per type
and applies a hard distance threshold, the approximate recall is acceptable for a linking
heuristic.

**Trade-offs:**

- Two SQL round trips per driving post instead of one (the extra read is a single-row PK
  lookup — negligible against the scan it replaces).
- HNSW returns approximate neighbors, so a borderline match near the threshold could be
  missed; for a best-effort cross-source linking signal this is an acceptable trade that
  the IVFFlat/pi profile already implies.
- The fix touches only this method; the entity query (line 202) is unaffected (it joins
  on equality, not vector distance).

---

### F2. "Fatal" embedding dimensionality mismatch throws every tick with no operator signal

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java:241-256 (and the `onTick` → `processBatch` call path)

**Current code:**

```java
for (int i = 0; i < results.size(); i++) {
    int actualDimension = results.get(i).vector().length;
    if (actualDimension != cachedDimension) {
        throw new IllegalStateException(
            "EmbeddingWorker: per-vector dimensionality mismatch at batch index " + i
                + " for post_id=" + batch.get(i).id()
                + "; expected=" + cachedDimension + " actual=" + actualDimension
                + ". Run the re-embed procedure ("
                + EmbeddingMetadataStartupGuard.REEMBED_PROCEDURE_PATH + ").");
    }
}
```

`onTick` calls `processBatch(pending)` directly with no surrounding catch, so the throw
propagates out of the `@Scheduled` method.

**Why this is wrong / suboptimal / risky:**

The class javadoc and `docs/spec/llm.md` §Embedding pipeline call this condition *fatal*:
"Dimensionality mismatch at runtime is fatal ... The only safe recovery is a full
re-embed." But the implementation does not make it fatal. It throws an
`IllegalStateException` out of the scheduled tick, which Quarkus' scheduler logs and
swallows. The pickup query (`enumeratePending`) is idempotent and re-selects the same
batch on the next poll (5s on laptop). The provider returns the same wrong-dimension
vectors, and the worker throws again — forever, every poll interval.

The result is the opposite of "fatal": the pipeline does not halt, the operator gets no
distinct admin notification (every other failure path in this module fires
`ThrottledAdminNotifier.notifyOnce` — tagger fallback, entity failure, Stage 2 infra
failure, re-eval — but this path does not), and the only symptom is a stack trace
repeating in the log stream at the poll cadence. An operator who is not watching logs
will never learn that embedding has wedged; affected posts sit at
`embedding_done=FALSE` and never reach READY, silently starving the user-visible store
of every post in the affected window.

This is a real divergence between a spec commitment ("fatal", operator-action-required)
and the code (silent infinite retry). It belongs to MAINTAINABILITY-RULES-DRIFT
(spec-drift) rather than pure performance because the user-facing harm is "posts never
become visible and nobody is told," not throughput.

**Recommended fix:**

Make the mismatch surface as a throttled, coalesced operator alert and stop re-spamming.
Since the condition cannot self-heal without operator action, fire one admin notification
(coalesced on a canonical error class) and skip the batch rather than throwing:

```java
for (int i = 0; i < results.size(); i++) {
    int actualDimension = results.get(i).vector().length;
    if (actualDimension != cachedDimension) {
        LOG.error(
            "EmbeddingWorker: per-vector dimensionality mismatch for post_id={} "
                + "(expected={} actual={}); embedding halted until re-embed (error_class={})",
            batch.get(i).id(), cachedDimension, actualDimension,
            ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH);
        adminNotifier.notifyOnce(
            ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH,
            ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH,
            "Embedding dimensionality mismatch; run the re-embed procedure ("
                + EmbeddingMetadataStartupGuard.REEMBED_PROCEDURE_PATH + ")");
        return; // do not INSERT; leave embedding_done=FALSE for the re-embed sweep
    }
}
```

(Injecting `ThrottledAdminNotifier adminNotifier` mirrors the other eval workers.)

**Reasoning:**

The spec wants an operator to know and act. A coalesced `notifyOnce` is exactly the
mechanism the rest of the module uses for "an operator must intervene" conditions, and
the throttling keys (`(channel, error_class)`) collapse the repeated detection into one
page even though the tick keeps firing. Returning instead of throwing stops the stack-
trace spam while preserving the safety property (no wrong-dimension vector is ever
inserted, posts stay `embedding_done=FALSE`). The behavior now matches the documented
intent: detection is loud and operator-directed, not a silent log loop.

**Trade-offs:**

- Adds a dependency (`ThrottledAdminNotifier`) and a new error-class constant to the
  worker — a few lines.
- The pipeline stays "soft-stalled" (affected posts wait) rather than hard-failing the
  process. If the project genuinely wants process death on this condition, the
  alternative below is closer to literal "fatal."

**Alternative options:**

- **Option A** (the recommended fix above) — notify + skip; pipeline resumes
  automatically after the operator re-embeds and re-enables.
- **Option B** — treat it as a true startup-class invariant and call
  `Quarkus.asyncExit(1)` (or pause the scheduler for the embedding worker) so the
  deployment fails loudly. Pros: matches the literal "fatal" wording; impossible to miss.
  Cons: a single transient provider glitch returning one wrong-shaped vector would kill
  the whole Collector, taking down ingest for every other stage; harsher than the data-
  corruption risk warrants since the INSERT is already guarded.

---

### F3. Quarantine span offsets are documented as bytes but are char offsets

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/QuarantineDao.java:46-52, 119-124; written from Stage1Pipeline.java:341-342, 400-402

**Current code:**

```java
// QuarantineDao javadoc:
//   {@code span_start}/{@code span_end} as byte offsets in the original body
// QuarantineRow javadoc:
//   @param spanStart byte offset of the matched span in the original body; ...
//   @param spanEnd   byte offset (exclusive) of the matched span end; ...
```

```java
// Stage1Pipeline — the values actually stored:
int start = m.start();           // java.util.regex Matcher offsets are char indices
int end = m.end();
String span = body.substring(start, end);
```

**Why this is wrong / suboptimal / risky:**

`Matcher.start()`/`end()` and `StringBuilder.replace(start, end, ...)` operate on Java
`char` (UTF-16 code unit) indices, not byte offsets. The values stored in
`quarantine.span_start` / `span_end` are therefore char offsets. The javadoc on the DAO
and the record both assert "byte offsets in the original body."

The coding-style rule "Comment ... WHY-not-WHAT, and don't let comments rot" plus the
engineering bar for accurate documentation make this a drift finding: any future reader
(or forensic admin tooling that tries to slice `original_html` by these offsets against a
byte buffer) will mis-address multi-byte content. Functionally nothing breaks today
because redaction is done by `StringBuilder.replace` using the same char offsets and
reconstruction is done by placeholder string-replace, never by re-indexing bytes — but
the contract written in the javadoc is false, and the column would silently produce wrong
slices the moment someone trusts the documented unit.

**Recommended fix:**

Correct the documentation to state the actual unit:

```java
 * @param spanStart Java char (UTF-16) offset of the matched span in the
 *                  normalized body; 0 on a watchdog abort (whole-body span).
 * @param spanEnd   Java char (UTF-16) offset (exclusive) of the matched span
 *                  end; body.length() on a watchdog abort.
```

and the matching line in the DAO class javadoc ("char offsets ... not byte offsets").

**Reasoning:**

The cheapest correct fix is to make the documentation match the implementation, because
the implementation's char-offset choice is internally consistent (record → redact →
store all use the same units). Changing the code to compute byte offsets would be a
gratuitous behavior change with no consumer that needs bytes. Fixing the words removes
the latent trap.

**Trade-offs:**

None — the fix is strictly better (accurate documentation, no behavior change).

---

### F4. AssetSnapshotFetcher injects three unused config fields as speculative scaffolding

- **Category:** SIMPLIFICATION
- **Severity:** low
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java:119-129

**Current code:**

```java
@SuppressWarnings("unused")
@ConfigProperty(name = "infochat.assets.refresh.coingecko")
Duration coingeckoRefresh;

@SuppressWarnings("unused")
@ConfigProperty(name = "infochat.assets.refresh.kraken")
Duration krakenRefresh;

@SuppressWarnings("unused")
@ConfigProperty(name = "infochat.assets.refresh.bitfinex")
Duration bitfinexRefresh;
```

The class comment concedes these are "currently informational" and "kept here as the
explicit binding the acceptance contract requires and as the hook a future runtime-tuning
ticket can read." The `@Scheduled(every = "{infochat.assets.refresh.<host>}")`
annotations resolve those property strings independently; the injected `Duration` fields
are never read by any code path.

**Why this is wrong / suboptimal / risky:**

This is dead, speculative state on a production bean. The engineering rules forbid both
unused scaffolding kept "for a future ticket" (§1 surgical changes — changed lines must
trace to a real need; §7 no speculative/"just in case" code; feature-flags-and-shims
forbidden) and the coding style's "Simplify aggressively / don't introduce abstractions
beyond what the task requires." Three injected fields plus three `@SuppressWarnings`
plus a paragraph of justification exist solely to hold values nothing consumes. The
keys are already validated by the `@Scheduled` expression at boot, so the "must fail boot
if the key is missing/typoed" property the comment implies is already provided without
these fields.

**Recommended fix:**

Delete the three fields and their `@SuppressWarnings` annotations and the paragraph of
comment that justifies them. The `@Scheduled(every = "{infochat.assets.refresh.<host>}")`
expressions remain the single binding to those keys and already fail boot on a missing or
malformed value.

```java
// (fields removed; the @Scheduled expressions are the sole, sufficient binding)
@Scheduled(every = "{infochat.assets.refresh.coingecko}",
    concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
public void onCoingeckoTick() { runHostTick(COINGECKO); }
```

**Reasoning:**

Removing them shrinks the bean to exactly what it uses, removes three NullAway-relevant
fields and three suppressions, and eliminates a comment that will rot when the "future
runtime-tuning ticket" never reads them in the shape anticipated. If a runtime-tuning
ticket later needs the durations, it adds the field then, traced to that ticket — which
is the rule the project already follows everywhere else.

**Trade-offs:**

- If the acceptance contract that mandated these fields is still considered binding, the
  honest move is to fix the contract rather than carry dead fields; that is a process
  decision, not a code one. The code-level smell stands regardless.

---

## Synthesizer-relevant observations

These are cross-module or scope notes, not numbered findings — they belong to the
architecture lens, recorded once here:

- **NOTIFY payload contracts are agreed on the collector side only.** `ReadyPromoter`
  emits `new_post` as `{"ready_at","post_id"}`, `QuarantineNotifyEmitter` emits the
  tagged `quarantine_review` shape, and `PriceSnapshotStore` emits
  `{"asset","source"}` for `new_price_snapshot`. All three match
  `docs/spec/architecture.md` §Inter-service communication. Whether the
  Provider's LISTEN parsers consume these byte shapes correctly (cursor advance,
  high-water mark, `target_kind` discrimination) is an architecture-lens cross-module
  check, not verifiable from within this module.

- **Same-transaction NOTIFY discipline is consistently correct across the module.**
  `ReadyPromoter.promoteOne` (explicit `setAutoCommit(false)` + commit because `onTick`
  self-invokes and a CDI `@Transactional` interceptor would not fire),
  `PriceSnapshotStore.store` (`@Transactional`, and the INSERT/NOTIFY share the
  connection), and every `quarantineNotifyEmitter.emit(conn, ...)` call site
  (Stage2VerdictHandler, ReEvaluationJob, QuarantineDao) emit the NOTIFY on the same JDBC
  connection inside the same transaction as the state change, so a rollback suppresses the
  phantom signal. `UPDATE ... RETURNING`-scoped emits prevent double-firing on re-runs.
  This is the outbox/NOTIFY hot-spot and it is honored.

- **SSRF boundary is uniformly routed.** Every outbound fetch (RSS, Bluesky, Reddit,
  Nitter, YouTube, Odysee, the three asset sources) and the Nostr WebSocket connect path
  go through `SsrfGuardedHttpClient` / `checkAndPinForWebSocket` with per-reconnect
  re-resolve (`peerIpDiverged`). No raw `HttpClient.send`/`java.net` fetch of a
  source-controlled URL exists in the module. `RssFeedParser` disables DTD and external
  entities (XXE closed). The Nostr trust-boundary ordering (signature verify → kind
  allowlist → dedup → outbox) matches `security.md` §Per-source trust boundaries.

- **Outbox + rehydrator + per-stage cursor invariant is sound.** `PostPersister` writes
  `status='RAW'` before enqueue, `OutboxRehydrator` keyset-paginates the RAW set at
  startup, and every eval stage gates on `status='RAW'` + its `*_done` flag and writes the
  flag in the same transaction as its side effect (Invariant 5). Re-enqueue idempotency
  is enforced at each stage's `*_done` short-circuit.
