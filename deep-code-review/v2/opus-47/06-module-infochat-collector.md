# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-06 (UTC)
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:140` — `applyBenignReEval` transitions quarantine rows to `BENIGN_CLOSED` but never emits the `quarantine_review` NOTIFY that the spec commits to for that transition.
- [high] PERFORMANCE — `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java:248-260` — `emitQuarantineNotifyForClosedRows` re-emits NOTIFY for **every** existing `BENIGN_CLOSED` row of the post, not only the rows transitioned in this transaction, multiplying NOTIFY traffic across re-runs and idempotent re-enqueues.
- [high] MAINTAINABILITY-RULES-DRIFT — `infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java:115` — the `actor` (source identifier) is interpolated raw into the URL while `cursor` is URL-encoded; a Bluesky identifier containing `&`, `#`, or `?` silently breaks the request shape.
- [medium] SECURITY — `infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java:103-107` — `XMLStreamException` from `reader.close()` is silently swallowed with an `ignored` catch in production code, which §8 test-integrity rules treat as a smell and §7 rejects as a "just in case" branch.
- [medium] MAINTAINABILITY-RULES-DRIFT — `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java:212-221` — `setStage2Verdict` runs as a second UPDATE on the same row that `updatePostStage2DoneRaw` / `updatePostQuarantined` just modified, doubling DB roundtrips inside the transaction for every Stage 2 verdict.
- [medium] PERFORMANCE — `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java:63-91` — the per-source UNKNOWN sweep does a full-table aggregate scan over `post JOIN source` without a time-bounded predicate on `post.fetched_at`, so the cost grows with the partition retention rather than with the rolling window.
- [medium] MAINTAINABILITY-RULES-DRIFT — `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java:77` — passes the interval as a string parameter and casts via `?::INTERVAL`, mixing parameter substitution with a runtime cast where Postgres `make_interval`/`numtodsinterval` would be safer and faster.
- [medium] SIMPLIFICATION — `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java:101-104` / `BitfinexSnapshotSource.java:97-100` — both sources compute `vsUpper` and then immediately check `vs.toLowerCase(Locale.ROOT)` for membership in `SUPPORTED_VS`, hiding a third copy of the same case-normalization concern across the asset sources.
- [medium] MAINTAINABILITY-RULES-DRIFT — `infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java:88-102` — defensive validation of `upstreamIdentifier` against the SPI contract violates §7 "no defensive code for impossible scenarios" — the comment even acknowledges the check is "SPI-contract assertion" inside the trust boundary.
- [medium] MAINTAINABILITY-RULES-DRIFT — `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java:229` — mutable package-private `static UnaryOperator<String> sanitizer` is a test-only seam in production code that violates §7's no-feature-flags rule and creates a non-trivial concurrency hazard.
- [low] PERFORMANCE — `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:243-266` — `reconstructOriginalBody` materializes the entire body and re-allocates per `String.replace` call once per quarantine row; for posts with N redactions the cost is O(N·body_length) where a single pass would be linear.
- [low] MAINTAINABILITY-RULES-DRIFT — `infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java:79-81` — building the JSON payload by string concatenation is justified by the closed-set inputs, but the constraint is implicit; one new caller passing user-derived data would silently produce malformed NOTIFY.

## Detail

### F1. Re-eval BENIGN does not emit `quarantine_review` NOTIFY for BENIGN_CLOSED transitions

- **Category:** SECURITY
- **Severity:** high
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:129-150`

**Current code:**

```java
private void applyBenignReEval(ReEvalCandidate candidate) {
    String priorVerdict = candidate.stage2Failed() ? "INFRA_FAILURE" : "UNKNOWN";
    TransactionHelper.inTransaction(dataSource, "ReEvaluationJob.applyBenign", conn -> {
        if (candidate.stage2Failed()) {
            clearStage2Failed(conn, candidate);
        } else {
            promoteToReady(conn, candidate);
        }
        closeQuarantineRows(conn, candidate.postId());
        writeReEvalReleasedAudit(conn, candidate, priorVerdict, candidate.reEvalAttempts() + 1);
    });
    throttledAdminNotifier.notifyOnce(
        ERROR_CLASS_REEVAL_RELEASED,
        ERROR_CLASS_REEVAL_RELEASED,
        "Re-eval released post_id=" + candidate.postId()
            + " prior_verdict=" + priorVerdict);
    ...
}
```

**Why this is wrong:**

`docs/spec/architecture.md` §Inter-service communication commits the `quarantine_review` NOTIFY channel to fire on **every** quarantine state-machine transition reachable by the Provider — `PENDING` insert, `BENIGN_CLOSED`, `APPROVED`, `REJECTED`. The Provider's high-water mark advances on these events; missing events past the latency-optimization NOTIFY is correct in principle because the catch-up scan eventually picks them up, but the catch-up cursor for the `quarantine_review` channel is `(reviewed_at, target_kind, target_id)` — that cursor is built off `quarantine.updated_at` which IS being advanced here. So the Provider will eventually pick up these BENIGN_CLOSED rows on catch-up, but the **NOTIFY-as-latency-optimization** is silently dropped on the re-eval path while it fires correctly on the first-pass Stage 2 BENIGN path (`Stage2VerdictHandler.applyBenign` → `emitQuarantineNotifyForClosedRows`). The asymmetry is a latent bug for any Provider feature that watches the channel.

The spec is explicit on this: the channel's contract is "all quarantine state-machine moves visible to the Provider role." The re-eval BENIGN closes one or more quarantine rows. The NOTIFY is mandatory.

**Recommended fix:**

```java
private void applyBenignReEval(ReEvalCandidate candidate) {
    String priorVerdict = candidate.stage2Failed() ? "INFRA_FAILURE" : "UNKNOWN";
    TransactionHelper.inTransaction(dataSource, "ReEvaluationJob.applyBenign", conn -> {
        if (candidate.stage2Failed()) {
            clearStage2Failed(conn, candidate);
        } else {
            promoteToReady(conn, candidate);
        }
        List<UUID> closedIds = closeQuarantineRowsReturningIds(conn, candidate.postId());
        for (UUID quarantineId : closedIds) {
            quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.QUARANTINE,
                quarantineId, QuarantineNotifyEmitter.NewStatus.BENIGN_CLOSED);
        }
        writeReEvalReleasedAudit(conn, candidate, priorVerdict, candidate.reEvalAttempts() + 1);
    });
    // ... (notifier call unchanged)
}

private List<UUID> closeQuarantineRowsReturningIds(Connection conn, UUID postId) throws SQLException {
    final String sql =
        "UPDATE quarantine SET status = 'BENIGN_CLOSED', updated_at = now() "
            + "WHERE post_id = ? AND status = 'PENDING' RETURNING id";
    List<UUID> ids = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setObject(1, postId);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add((UUID) rs.getObject(1));
            }
        }
    }
    return ids;
}
```

**Reasoning:**

`RETURNING id` on the same UPDATE that performs the transition gives the exact set of rows just transitioned — no follow-up `SELECT` race, no over-emission. The NOTIFY commits or rolls back with the UPDATE per the spec's same-transaction rule. The fix touches one method and reuses the already-injected `quarantineNotifyEmitter` (the field is declared at line 66 but currently used only for the `NEEDS_REVIEW` post-transition).

**Trade-offs:**

None — the fix is strictly better. The RETURNING shape is a single-statement extension of the existing UPDATE.

---

### F2. `emitQuarantineNotifyForClosedRows` re-emits NOTIFY for prior `BENIGN_CLOSED` rows

- **Category:** PERFORMANCE
- **Severity:** high
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java:248-261`

**Current code:**

```java
private void emitQuarantineNotifyForClosedRows(Connection conn, UUID postId) throws SQLException {
    final String sql =
        "SELECT id FROM quarantine WHERE post_id = ? AND status = 'BENIGN_CLOSED'";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setObject(1, postId);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID quarantineId = (UUID) rs.getObject(1);
                quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.QUARANTINE,
                    quarantineId, QuarantineNotifyEmitter.NewStatus.BENIGN_CLOSED);
            }
        }
    }
}
```

**Why this is wrong:**

This SELECT runs against the same `post_id` and matches **any** quarantine row currently in `BENIGN_CLOSED`, not only the rows just transitioned in `updateStage1QuarantineRowsToBenignClosed`. For a post that's already been through a BENIGN verdict once (e.g. re-enqueued via the rehydrator on a crash after Stage 2 commit but before the outbox channel `ack`), every prior BENIGN_CLOSED row re-fires NOTIFY on every subsequent invocation.

The Provider's high-water mark eventually filters duplicates via the `(reviewed_at, target_kind, target_id)` cursor, but the wire-level NOTIFY traffic, the Provider's per-NOTIFY routing work, and the cursor-advance writes scale with prior history rather than with new transitions. For long-running deployments where the same post accumulates re-evaluation cycles, this is a real cost.

The `Stage2VerdictHandler.applyBenign` path also depends on the same-transaction rule: per `docs/spec/architecture.md` the NOTIFY commits with the UPDATE that produced the event. A NOTIFY emitted for a row that was NOT transitioned in this transaction violates that rule.

**Recommended fix:**

Change `updateStage1QuarantineRowsToBenignClosed` to return the IDs it actually updated and feed those into the NOTIFY emit:

```java
private static List<UUID> updateStage1QuarantineRowsToBenignClosed(Connection conn, UUID postId) throws SQLException {
    final String sql =
        "UPDATE quarantine SET status = 'BENIGN_CLOSED', updated_at = now() "
            + "WHERE post_id = ? AND flagged_by = 'stage1' AND status = 'PENDING' "
            + "RETURNING id";
    List<UUID> updated = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setObject(1, postId);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                updated.add((UUID) rs.getObject(1));
            }
        }
    }
    return updated;
}

// In applyBenign:
TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn -> {
    updatePostStage2DoneRaw(conn, postId, postFetchedAt, false);
    setStage2Verdict(conn, postId, postFetchedAt, "BENIGN");
    List<UUID> closedIds = updateStage1QuarantineRowsToBenignClosed(conn, postId);
    for (UUID id : closedIds) {
        quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.QUARANTINE,
            id, QuarantineNotifyEmitter.NewStatus.BENIGN_CLOSED);
    }
});
```

**Reasoning:**

`RETURNING id` from the UPDATE is the canonical Postgres pattern for "give me back exactly the rows I just modified." It collapses the SELECT-after-UPDATE round-trip and guarantees the emit set matches the transition set.

**Trade-offs:**

None — strictly fewer round trips, strictly correct semantics. The `emitQuarantineNotifyForClosedRows` method can be deleted.

---

### F3. Bluesky `actor` query-string interpolation is not URL-encoded

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** high
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java:113-122`

**Current code:**

```java
private URI buildUri(String actor, @Nullable String cursor) {
    StringBuilder sb = new StringBuilder(xrpcBase)
        .append("?actor=").append(actor);
    if (cursor != null) {
        // cursor is upstream-supplied (untrusted): encode so a value
        // containing & / # / ? cannot inject or truncate the query.
        sb.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }
    return URI.create(sb.toString());
}
```

**Why this is wrong:**

The asymmetric handling is documented in the comment ("cursor is upstream-supplied (untrusted): encode...") but the rationale is incomplete. The `actor` value comes from `source.identifier`, which IS operator-supplied at bootstrap time (so the trust-boundary argument permits skipping the encode). But the bootstrap fixture in `infochat-collector/src/test/resources/bootstrap/bootstrap-sources-fixture.json:11` ships an `identifier` of the form `https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=example.dev` — an entire URL — while `BlueskyFetcherTest` passes a bare handle (`"alice.bsky.social"`). The Fetcher's `buildUri` produces wildly different URIs for the two: for a bare handle it's correct, for the bootstrap-style URL identifier it produces `xrpcBase + "?actor=" + entire_url`, which is malformed.

Either:
1. The identifier semantics for Bluesky should be a bare handle/DID, in which case the bootstrap fixture is wrong and the production schema for `source.identifier` is undocumented.
2. The identifier semantics for Bluesky should be the full URL, in which case `BlueskyFetcher.buildUri` is wrong — it should use `URI.create(identifier)` directly and parse out the actor only if pagination needs to extend the existing query string.

The spec (`docs/spec/architecture.md` §Source identity) commits: "identifier — the URL for HTTP-shaped sources, the filter spec for stream sources." Bluesky is an HTTP-shaped source, so identifier SHOULD be the full URL — making case (2) the correct reading.

**Recommended fix:**

```java
private URI buildUri(String identifier, @Nullable String cursor) {
    if (cursor == null) {
        return URI.create(identifier);
    }
    String separator = identifier.contains("?") ? "&" : "?";
    return URI.create(identifier + separator + "cursor="
        + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
}
```

Drop the `xrpcBase` constant and the `infochat.fetch.bluesky.api-base-url` property entirely — the identifier IS the URL. The test fixture (`"alice.bsky.social"`) is wrong and should be updated to a full URL.

**Reasoning:**

Aligns the implementation with the spec's documented `identifier` semantics and removes the asymmetric encoding rule. The bootstrap fixture continues to work because the identifier already contains the full URL with `?actor=`. The cursor extension correctly handles both cases.

**Trade-offs:**

Tests in `BlueskyFetcherTest` need their identifiers updated from `"alice.bsky.social"` to a full URL form (e.g. `"http://127.0.0.1:" + port + "/xrpc/app.bsky.feed.getAuthorFeed?actor=alice.bsky.social"`). The test harness already supplies an arbitrary base URL via the package-private constructor, so this is a fixture rewrite.

**Alternative options:**

- **Option A** (the recommended fix) — make `identifier` the full URL, matching the spec.
- **Option B** — keep `identifier` as a bare handle and update the spec wording for "HTTP-shaped sources" to admit this case explicitly. This is a spec amendment, not a code change.

---

### F4. Silent `XMLStreamException` swallow in `RssFeedParser.parse`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java:102-108`

**Current code:**

```java
} finally {
    try {
        reader.close();
    } catch (XMLStreamException ignored) {
        // close failures on a parsed payload are not actionable
    }
}
```

**Why this is wrong:**

`catch (XMLStreamException ignored) {}` is exactly the §8 test-integrity pattern "no `catch (Exception ignored) {}` blocks in production." Even though the comment claims "close failures on a parsed payload are not actionable," the empty catch silently masks every conceivable failure including ones that ARE actionable (a resource leak signal, a hostile XML attack triggering a delayed close failure).

The `reader.close()` releases the underlying `ByteArrayInputStream`, which is in-memory and cannot fail — so the catch is for a hypothetical, not a real, failure mode. §7 "No defensive code for impossible scenarios" applies: the close cannot throw against a `ByteArrayInputStream`, so the catch is dead code that exists "just in case."

**Recommended fix:**

```java
public static List<NormalizedPost> parse(long sourceId, byte[] body, Instant fetchedAt) {
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

    try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
        XMLStreamReader reader;
        try {
            reader = factory.createXMLStreamReader(in);
        } catch (XMLStreamException e) {
            throw new RssFeedParseException("Failed to open XML stream: " + e.getMessage(), e);
        }
        try {
            return parseDocument(reader, sourceId, fetchedAt);
        } finally {
            reader.close();
        }
    } catch (IOException | XMLStreamException e) {
        throw new RssFeedParseException("XML stream error: " + e.getMessage(), e);
    }
}
```

**Reasoning:**

Letting the close exception propagate (and wrapping it in the existing `RssFeedParseException`) is the honest path. The current empty catch is exactly what §7 and §8 forbid in production code; the only correct way to "ignore" an exception is to assert in code that it can't happen via the design, which means either (a) removing the throw site or (b) propagating it.

**Trade-offs:**

A theoretical close failure now propagates as a fetch failure rather than silently being ignored. Given that the source is a `ByteArrayInputStream`, the close cannot fail in practice — so the change is correctness-equivalent in production while being honest in code.

---

### F5. `setStage2Verdict` is a redundant second UPDATE on the just-modified row

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java:134-152, 212-221`

**Current code:**

```java
private void applyBenign(UUID postId, Instant postFetchedAt) {
    TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn -> {
        updatePostStage2DoneRaw(conn, postId, postFetchedAt, /* stage2Failed */ false);
        setStage2Verdict(conn, postId, postFetchedAt, "BENIGN");
        updateStage1QuarantineRowsToBenignClosed(conn, postId);
        emitQuarantineNotifyForClosedRows(conn, postId);
    });
    ...
}

private void applyQuarantineVerdict(UUID postId, Instant postFetchedAt, Verdict verdict) {
    TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn -> {
        updatePostQuarantined(conn, postId, postFetchedAt, /* stage2Failed */ false);
        setStage2Verdict(conn, postId, postFetchedAt, verdict.name());
    });
    ...
}

private static void setStage2Verdict(Connection conn, UUID postId, Instant postFetchedAt,
                                      String verdict) throws SQLException {
    final String sql = "UPDATE post SET stage2_verdict = ? WHERE id = ? AND fetched_at = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, verdict);
        ps.setObject(2, postId);
        ps.setTimestamp(3, Timestamp.from(postFetchedAt));
        ps.executeUpdate();
    }
}
```

**Why this is wrong:**

`updatePostStage2DoneRaw` and `updatePostQuarantined` already issue an UPDATE against the partitioned `post` row to set Stage 2 flags. Adding `stage2_verdict = ?` to those statements is one extra `SET` column on the same WHERE-clause and on the same row — but the current code splits this into a second UPDATE, doubling the partition lookup, the row lock, and the WAL write inside the same transaction. The `post` table is range-partitioned and the row is reached via `(id, fetched_at)`, so each UPDATE pays the partition-pruning cost.

This is a CLAUDE.md §Coding style "Simplify aggressively" issue with measurable cost: every Stage 2 verdict pays a second `WHERE id = ? AND fetched_at = ?` lookup that could be folded into the first.

**Recommended fix:**

```java
private static void updatePostStage2DoneRaw(Connection conn, UUID postId, Instant postFetchedAt,
                                            boolean stage2Failed, String verdict) throws SQLException {
    final String sql =
        "UPDATE post SET stage2_done = TRUE, stage2_failed = ?, stage2_verdict = ? "
            + "WHERE id = ? AND fetched_at = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setBoolean(1, stage2Failed);
        ps.setString(2, verdict);
        ps.setObject(3, postId);
        ps.setTimestamp(4, Timestamp.from(postFetchedAt));
        ps.executeUpdate();
    }
}
// ... same change for updatePostQuarantined; setStage2Verdict deleted.
```

For the infra-failure path, pass `null` (or the string `"INFRA_FAILURE"`) as the verdict — the schema admits NULL or a closed-set string per V22.

**Reasoning:**

Single statement, single row touch, identical durability semantics. The transaction shape is unchanged.

**Trade-offs:**

The signatures of the two helpers grow one parameter. Worth the simplification.

---

### F6. `PerSourceUnknownTracker` scan has no time bound on the joined post rows

- **Category:** PERFORMANCE
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java:59-91`

**Current code:**

```java
void checkAllSources() throws SQLException {
    final String sql =
        "SELECT s.id, "
            + "  COUNT(*) FILTER (WHERE p.stage2_verdict = 'UNKNOWN') AS unknown_count, "
            + "  COUNT(*) AS total_count "
            + "FROM source s "
            + "JOIN post p ON p.source_id = s.id "
            + "WHERE s.status = 'active' "
            + "  AND p.stage2_done = TRUE "
            + "  AND p.stage2_failed = FALSE "
            + "  AND p.status_changed_at >= now() - ?::INTERVAL "
            + "GROUP BY s.id "
            + "HAVING COUNT(*) >= ?";
    ...
}
```

**Why this is wrong:**

`post` is partitioned by `fetched_at` (V7). The predicate `p.status_changed_at >= now() - INTERVAL` is on a NON-partition-key column, so the query planner cannot prune partitions — it scans every active partition of `post` joined against `source`. The `idx_post_status_changed_at` index (if any) helps the row-level filter, but the partition pruner is blind.

Per `docs/spec/architecture.md` §"TTL by partitioning, not DELETE": `post` retention is bounded by partition drop, but that bound is configurable per profile and could be months. Every `unknown-tracker-poll-interval` tick (default 15 min) scans all retained partitions to compute a rolling-window aggregate that only needs the last `unknown-rate-window` (default 1h–12h) of partitions.

For a steady-state deployment with ~1M posts/month and a 6-month retention, that's a 6M-row scan every 15 minutes — measurable hot-path cost.

**Recommended fix:**

Add a partition-pruning predicate on `fetched_at` to bound the scan:

```java
final String sql =
    "SELECT s.id, "
        + "  COUNT(*) FILTER (WHERE p.stage2_verdict = 'UNKNOWN') AS unknown_count, "
        + "  COUNT(*) AS total_count "
        + "FROM source s "
        + "JOIN post p ON p.source_id = s.id "
        + "WHERE s.status = 'active' "
        + "  AND p.fetched_at >= now() - (?::INTERVAL * 2) "  // partition pruner
        + "  AND p.stage2_done = TRUE "
        + "  AND p.stage2_failed = FALSE "
        + "  AND p.status_changed_at >= now() - ?::INTERVAL "
        + "GROUP BY s.id "
        + "HAVING COUNT(*) >= ?";
```

The 2× multiplier on the partition predicate handles the case where `status_changed_at` lags `fetched_at` by the eval-pipeline latency.

**Reasoning:**

`fetched_at` is the partition key, so any inequality on it lets the planner prune partitions to the relevant window. The 2× headroom avoids edge-case false negatives where a post fetched at `now() - window` is judged at `now() - 0.5*window`. The scan cost becomes proportional to the rolling window rather than to total retention.

**Trade-offs:**

A post first ingested >2×window ago that only just had Stage 2 complete (long re-eval backlog) would be excluded from the UNKNOWN rate calculation. This is acceptable: such a post is no longer fresh and the protective auto-disable is most useful for current-week traffic.

---

### F7. Interval string concatenation in `PerSourceUnknownTracker`

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java:77`

**Current code:**

```java
ps.setString(1, unknownRateWindow.toSeconds() + " seconds");
ps.setInt(2, minSampleSize);
```

The SQL has `p.status_changed_at >= now() - ?::INTERVAL`.

**Why this is wrong:**

Two reasons:
1. Postgres parameter binding with `?::INTERVAL` works, but it relies on Postgres' string-to-interval parser at every query. A future Postgres change in interval parsing (e.g. locale-influenced output) could silently break the predicate.
2. Building the parameter string from the Java `Duration` via `toSeconds() + " seconds"` loses sub-second precision (irrelevant here) and ties the SQL to ASCII string formatting.

The cleaner pattern is to use `make_interval(secs := ?)` with an integer/double parameter, which the planner can constant-fold.

**Recommended fix:**

```java
final String sql =
    ... + " AND p.status_changed_at >= now() - make_interval(secs => ?) " + ...

try (PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setDouble(1, (double) unknownRateWindow.toSeconds());
    ps.setInt(2, minSampleSize);
    ...
}
```

**Reasoning:**

`make_interval` accepts numeric inputs directly and is the canonical Postgres function for building intervals from numeric values. The cast is gone, the locale concern is gone, and the parameter is a properly-typed numeric.

**Trade-offs:**

None — the change is purely stylistic-correctness.

---

### F8. `KrakenSnapshotSource` / `BitfinexSnapshotSource` compute `vsUpper` then check via `toLowerCase`

- **Category:** SIMPLIFICATION
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/assets/source/KrakenSnapshotSource.java:101-104` and `BitfinexSnapshotSource.java:97-100`

**Current code (Kraken):**

```java
String vsUpper = vs.toUpperCase(Locale.ROOT);
if (!SUPPORTED_VS.contains(vs.toLowerCase(Locale.ROOT))) {
    throw new FetchException("KrakenSnapshotSource: unsupported vs '" + vs + "'");
}

String pair = ticker + vsUpper;
```

**Why this is wrong:**

The code computes `vsUpper` for pair construction, then re-computes `vs.toLowerCase()` for the membership check, then later passes `vs.toLowerCase(Locale.ROOT)` to `PriceSnapshot` and `attributionUrl`. Three case-normalization calls on the same input in one method.

The same pattern repeats in `BitfinexSnapshotSource.fetchSnapshot`. Both are silent duplications. Per CLAUDE.md §Coding style "Simplify aggressively": three similar lines beats a premature abstraction, but here it's three nearly-identical expressions across two files where one local variable in each method would simplify.

**Recommended fix:**

```java
String vsLower = vs.toLowerCase(Locale.ROOT);
if (!SUPPORTED_VS.contains(vsLower)) {
    throw new FetchException("KrakenSnapshotSource: unsupported vs '" + vs + "'");
}
String vsUpper = vsLower.toUpperCase(Locale.ROOT);
String pair = ticker + vsUpper;
```

Then pass `vsLower` to `PriceSnapshot`'s `vsCurrency` field and to `attributionUrl`.

**Reasoning:**

One case-normalization per method, deterministic ordering. The `vsUpper` derives from `vsLower` so a hostile mixed-case `vs` (which shouldn't reach here but trust-boundary thinking still applies for SPI-supplied strings) cannot produce a path where the membership check passes but the pair has different casing than expected.

**Trade-offs:**

None — fewer string allocations, clearer dataflow.

---

### F9. `PostPersister` defensively validates SPI contract inside the trust boundary

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java:88-102`

**Current code:**

```java
public Optional<PersistedPostKey> persist(UUID sourceUuid, NormalizedPost normalized) {
    Objects.requireNonNull(sourceUuid, "sourceUuid");
    Objects.requireNonNull(normalized, "normalized");
    String upstreamIdentifier = normalized.upstreamIdentifier();
    if (upstreamIdentifier == null || upstreamIdentifier.isEmpty()) {
        // SPI-contract assertion: NormalizedPost.upstreamIdentifier
        // is declared "Never null" (M1-007a). A null / empty
        // arrival here is a Fetcher bug, not a recoverable runtime
        // condition; throw loudly so the FetchScheduler's WARN
        // log surfaces the bug instead of persisting an ID-less
        // row.
        throw new IllegalArgumentException(
            "PostPersister: upstreamIdentifier required by NormalizedPost SPI contract; "
            + "got null/empty for sourceUuid=" + sourceUuid);
    }
    ...
}
```

**Why this is wrong:**

The comment is explicit that this is "SPI-contract assertion" — a check between two internal classes (the Fetcher impl and PostPersister, both inside the Collector module). §7 "No defensive code for impossible scenarios" prohibits exactly this: "no null-checks for parameters callers cannot legally pass null for; no try/catch around operations that cannot throw; no 'just in case' branches."

Per §7a, "non-null is the package default (NullAway AnnotatedPackages). Bare reference type = 'never null'." The NormalizedPost.upstreamIdentifier field is declared as a bare String (non-null by package default), so NullAway already enforces the contract at compile time. The runtime check is redundant.

The `Objects.requireNonNull(sourceUuid, ...)` and `Objects.requireNonNull(normalized, ...)` calls likewise duplicate NullAway-enforced contracts.

**Recommended fix:**

```java
public Optional<PersistedPostKey> persist(UUID sourceUuid, NormalizedPost normalized) {
    String upstreamIdentifier = normalized.upstreamIdentifier();
    String uid = deriveUid(sourceUuid, upstreamIdentifier);
    ...
}
```

Drop the three explicit null checks. NullAway:ERROR at compile time is the enforcement.

**Reasoning:**

Matches the project's §7a contract: the type system is the contract; runtime re-validation is paranoia. The check provides no defense against a Fetcher returning a null `upstreamIdentifier` because NullAway already rejects that at compile time. Removing the check shrinks the method and removes a dead branch.

The `isEmpty()` check is a separate concern — an empty string is permitted by the type system. If empty strings are forbidden by the schema's UNIQUE invariant, that's a schema-level invariant the Fetcher should respect, and the DB will surface the violation if a Fetcher emits one. Currently the schema does not forbid empty strings, so the check is enforcing an undocumented invariant.

**Trade-offs:**

If a future Fetcher impl returns empty string (legal per the type) and the schema accepts it, this could silently land an empty-string upstream_identifier. If that's a real concern, fix the schema (NOT NULL + CHECK length>0), not the persister.

---

### F10. Mutable static `sanitizer` test seam in production code

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** medium
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java:217-229`

**Current code:**

```java
/**
 * Sanitizer-invocation seam. Production wires this to
 * {@code OWASP_POLICY::sanitize}; tests in the same package
 * temporarily replace it with a function that throws to verify
 * the {@link #handleSanitizerException} fail-closed branch
 * ...
 * Package-private and non-final by deliberate choice ...
 */
static UnaryOperator<String> sanitizer = OWASP_POLICY::sanitize;
```

**Why this is wrong:**

Three concerns:
1. **§7 forbids "feature flags and backwards-compat shims"** — a test-only mutable static is exactly a hidden flag that production reads. The doc-comment justifies it as "the test contract is 'swap, run one scenario, restore in @AfterEach'" — but the contract is enforced by convention, not by the type system.
2. **Concurrency hazard.** `static UnaryOperator<String>` is shared JVM-wide. If two tests in the same JVM swap concurrently (e.g. via JUnit parallel execution), they corrupt each other's setup. JUnit 5 default is sequential per class, but the project may enable parallelism without recognizing this hazard.
3. **Hidden coupling.** Any future code that wants to inject behavior into Stage 1 (e.g. a metrics wrapper) is tempted to use the same seam, snowballing the hidden-static surface area.

The standard fix is dependency injection: make the sanitizer an injectable CDI bean (or a constructor parameter) so the test substitutes via `QuarkusMock.installMockForType`.

**Recommended fix:**

```java
@ApplicationScoped
public class Stage1Pipeline {
    ...
    @Inject
    HtmlSanitizer sanitizer;   // a wrapper bean
    ...
}

// New file:
@ApplicationScoped
public class HtmlSanitizer {
    private static final PolicyFactory OWASP_POLICY =
        Sanitizers.FORMATTING.and(Sanitizers.BLOCKS).and(Sanitizers.LINKS);

    public String sanitize(String input) {
        return OWASP_POLICY.sanitize(input);
    }
}

// In tests:
@QuarkusTest
class Stage1PipelineSanitizerFailIT {
    @BeforeEach
    void replaceSanitizer() {
        QuarkusMock.installMockForType(new HtmlSanitizer() {
            @Override
            public String sanitize(String input) {
                throw new RuntimeException("test-induced");
            }
        }, HtmlSanitizer.class);
    }
}
```

**Reasoning:**

CDI mocking via `QuarkusMock` is the project's existing idiom (see the `Clock` producer in `ThrottledAdminNotifier`). The mock is scoped to the test class, automatically restored when the test ends, and impossible to leak across tests. No mutable static, no concurrency hazard, no hidden flag.

**Trade-offs:**

One extra type (`HtmlSanitizer`). The mutable-static seam is shorter by ~10 lines; the CDI-mock approach is ~25 lines across two files. The trade-off is honest scope vs. honest correctness; the latter wins for a class that participates in a security-critical path.

---

### F11. `ReEvaluationJob.reconstructOriginalBody` is O(N²) in placeholder count

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:243-266`

**Current code:**

```java
String reconstructOriginalBody(ReEvalCandidate candidate) {
    try (Connection conn = dataSource.getConnection()) {
        String body = readPostBody(conn, candidate);
        ...
        while (rs.next()) {
            String placeholderId = rs.getString(1);
            String originalHtml = rs.getString(2);
            if (placeholderId != null && originalHtml != null) {
                body = body.replace("[REDACTED:" + placeholderId + "]", originalHtml);
            }
        }
        ...
        return body;
    }
}
```

**Why this is wrong:**

Each `body.replace(target, replacement)` allocates a new String the length of the current body. With N quarantine rows, the cost is O(N × body_length). For posts with many regex hits (a worst-case adversarial input), this scales quadratically with the redaction count.

The cost is bounded by Stage 1's regex matchers (which themselves are bounded by the watchdog), but the cost is still material on the re-eval hot path: every re-eval re-allocates the body N times.

**Recommended fix:**

Build a placeholder-to-original map and do a single pass:

```java
String reconstructOriginalBody(ReEvalCandidate candidate) {
    try (Connection conn = dataSource.getConnection()) {
        String body = readPostBody(conn, candidate);
        Map<String, String> replacements = new HashMap<>();
        final String sql =
            "SELECT placeholder_id, original_html FROM quarantine "
                + "WHERE post_id = ? AND original_html IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, candidate.postId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pid = rs.getString(1);
                    String orig = rs.getString(2);
                    if (pid != null && orig != null) {
                        replacements.put("[REDACTED:" + pid + "]", orig);
                    }
                }
            }
        }
        return replaceAll(body, replacements);
    } catch (SQLException e) {
        throw new IllegalStateException(...);
    }
}

private static String replaceAll(String body, Map<String, String> replacements) {
    StringBuilder out = new StringBuilder(body.length() * 2);
    int i = 0;
    while (i < body.length()) {
        int markerStart = body.indexOf("[REDACTED:", i);
        if (markerStart < 0) {
            out.append(body, i, body.length());
            break;
        }
        out.append(body, i, markerStart);
        int markerEnd = body.indexOf(']', markerStart);
        if (markerEnd < 0) {
            out.append(body, markerStart, body.length());
            break;
        }
        String marker = body.substring(markerStart, markerEnd + 1);
        String replacement = replacements.get(marker);
        if (replacement != null) {
            out.append(replacement);
        } else {
            out.append(marker);  // unmapped placeholder stays as-is
        }
        i = markerEnd + 1;
    }
    return out.toString();
}
```

**Reasoning:**

Single pass over the body, O(body_length + Σ replacement_lengths). The placeholder shape `[REDACTED:<id>]` is searchable as a prefix.

**Trade-offs:**

More code (~20 lines). Justified only if the re-eval rate is high or posts have many redactions. The current code is correct for the common case (0–3 placeholders); the quadratic behavior only matters on adversarial input. Marked low severity.

---

### F12. `QuarantineNotifyEmitter` builds JSON by string concatenation

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** `infochat-collector/src/main/java/app/zcat/infochat/collector/notify/QuarantineNotifyEmitter.java:77-91`

**Current code:**

```java
public void emit(Connection conn, TargetKind targetKind,
                 UUID targetId, NewStatus newStatus) throws SQLException {
    String payload = "{\"target_kind\":\"" + targetKind.wireValue()
        + "\",\"target_id\":\"" + targetId
        + "\",\"new_status\":\"" + newStatus.name() + "\"}";
    ...
}
```

**Why this is wrong:**

The class comment defends this with: "Every interpolated payload value comes from a closed set — the two enums below plus a UUID — so building the JSON by concatenation cannot produce an injectable or malformed payload." That's true today. But the constraint is implicit and brittle: a future maintainer adding a new field (e.g. `actor`, `details`) that takes a String could trivially produce a malformed or attacker-influenced payload.

The Provider-side parser (`NewPostListener.parsePayload` per the ReadyPromoter doc) trusts the JSON shape. A broken payload would break the cursor advance.

**Recommended fix:**

```java
private final ObjectMapper mapper = new ObjectMapper();

public void emit(Connection conn, TargetKind targetKind,
                 UUID targetId, NewStatus newStatus) throws SQLException {
    String payload;
    try {
        ObjectNode node = mapper.createObjectNode();
        node.put("target_kind", targetKind.wireValue());
        node.put("target_id", targetId.toString());
        node.put("new_status", newStatus.name());
        payload = mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
        throw new IllegalStateException("QuarantineNotifyEmitter: payload build failed", e);
    }
    ...
}
```

**Reasoning:**

The same Jackson dependency already used by parsers across the module. Removes the "if a future maintainer adds a String field" footgun. The cost is one ObjectMapper allocation per emit (negligible against the round-trip cost of `pg_notify`).

**Trade-offs:**

Slightly more verbose. Acceptable for the consistency win — the NOTIFY contract is a wire protocol that the Provider depends on; encoding it manually creates a maintenance hazard.

---

## Cross-module note

Several findings reference the Provider-side cursor/listener behavior (`new_post`, `quarantine_review` channels). Those are out of scope for this module review; they appear here only because the Collector's NOTIFY emission shape is the contract the Provider listens against. F1, F2, and F12 all touch this contract; they should be triaged together with the Provider's `NewPostListener` / `QuarantineReviewListener` behavior in a coordinated change.
