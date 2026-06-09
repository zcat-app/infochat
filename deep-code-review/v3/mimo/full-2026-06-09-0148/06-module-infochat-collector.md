# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-09
**Reviewer:** senior-developer (mimo)

## Headline findings

- [low] PERFORMANCE — NostrEventVerifier.java:285 — `MessageDigest.getInstance("SHA-256")` allocated per `verify()` call instead of cached
- [low] MAINTAINABILITY-RULES-DRIFT — Stage2VerdictHandler.java:211-219 — `setStage2Verdict` issues a separate UPDATE on the same row already touched by the parent transaction's UPDATE
- [low] MAINTAINABILITY-RULES-DRIFT — EmbeddingWorker.java:224 — `InterruptedException` catch restores interrupt but returns silently with no log output, unlike every other failure path in the class

## Detail

### F1. MessageDigest.getInstance("SHA-256") allocated per verify() call

- **Category:** PERFORMANCE
- **Severity:** low
- **Location:** NostrEventVerifier.java:285

**Current code:**

```java
private static byte[] sha256(byte[] input) {
    try {
        return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 not available", e);
    }
}
```

**Why this is wrong / suboptimal / risky:**

`MessageDigest.getInstance("SHA-256")` allocates a new instance on every call. In the Nostr ingest hot path, `sha256()` is called at least twice per event (once for canonical-id computation in `nip01Canonical`, once for the BIP-340 challenge hash in `verifySchnorr`). The JCA provider lookup (`Provider.getService()`) and object allocation happen per event. `MessageDigest` is not thread-safe, but this class is shared across relays whose callbacks run on a small fixed set of platform threads (the `HttpClient` executor).

**Recommended fix:**

```java
private static final ThreadLocal<MessageDigest> SHA256_TL =
    ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    });

private static byte[] sha256(byte[] input) {
    MessageDigest md = SHA256_TL.get();
    md.reset();
    return md.digest(input);
}
```

**Reasoning:**

Caching the `MessageDigest` per thread eliminates the per-call JCA provider registry lookup and object allocation. The `ThreadLocal` is safe here because the verifier is called from the `HttpClient`'s WebSocket listener threads, each of which processes one relay's events serially. In the JDK 25 virtual-thread runtime, the `HttpClient` executor uses platform threads for I/O callbacks, so the `ThreadLocal` cardinality is bounded by the relay count, not the virtual-thread count.

**Trade-offs:**

Adds a `ThreadLocal` field. The cardinality is bounded by the number of configured relays (typically single digits), so `ThreadLocal` accumulation is not a concern.

---

### F2. Stage2VerdictHandler.setStage2Verdict issues a separate UPDATE on the same row

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** Stage2VerdictHandler.java:211-219

**Current code:**

```java
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

**Why this is wrong / suboptimal / risky:**

`setStage2Verdict` is always called inside the same `TransactionHelper.inTransaction` block as either `updatePostStage2DoneRaw` or `updatePostQuarantined`, targeting the same `(id, fetched_at)` row. This produces two UPDATE statements on the same row within the same transaction. The `stage2_verdict` column could be set in the first UPDATE, eliminating the extra JDBC round-trip. The two statements must always be paired, which is a minor maintainability concern -- a future refactor that moves one without the other would silently leave `stage2_verdict` unset.

**Recommended fix:**

Merge `stage2_verdict` into `updatePostStage2DoneRaw` and `updatePostQuarantined`:

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
```

And similarly for `updatePostQuarantined`. Remove `setStage2Verdict` entirely.

**Reasoning:**

Consolidating the writes reduces the per-verdict JDBC round-trip from 2 to 1. The methods are always called in pairs within the same transaction, so merging them is safe and simplifies the transaction body. The `applyInfraFailure` path does not set a verdict (it sets `stage2_failed` only), so the merged signature needs a `@Nullable String verdict` parameter with a conditional SET clause, or the infra-failure path uses the existing single-UPDATE shape without the verdict column.

**Trade-offs:**

Slightly larger SQL strings in the merged methods. The infra-failure path does not set `stage2_verdict`, so either (a) the merged method uses a conditional `SET stage2_verdict = COALESCE(?, stage2_verdict)` or (b) the infra-failure path continues to use the existing non-verdict UPDATE. Option (b) is cleaner.

---

### F3. EmbeddingWorker swallows InterruptedException silently

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** EmbeddingWorker.java:224-233

**Current code:**

```java
try {
    concurrencyPermits.acquire();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return;
}
```

**Why this is wrong / suboptimal / risky:**

When `acquire()` is interrupted (typically during JVM shutdown), the method restores the interrupt flag and returns silently. The posts in the batch stay at `embedding_done=FALSE` and will be re-picked on the next tick. This is functionally correct, but the silent return produces no log output, making it harder for an operator to distinguish "the batch was abandoned due to shutdown" from "the batch was processed successfully." Every other failure path in this class logs at WARN level.

**Recommended fix:**

```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    LOG.info("EmbeddingWorker: interrupted while waiting for concurrency permit; "
        + "abandoning batch of {} posts (will retry after restart)", batch.size());
    return;
}
```

**Reasoning:**

A single INFO log line on the shutdown path costs nothing and gives operators visibility into why a batch was abandoned. The INFO level (not WARN) is appropriate because interruption during shutdown is expected behavior, not an error condition. The "(will retry after restart)" hint tells the operator the posts are not lost.

**Trade-offs:**

None -- the fix is strictly better for observability.

---

After thorough review of the main source files (68 production classes) and key test files in the infochat-collector module, the codebase is well-structured and faithfully implements the spec. The security pipeline (Stage 1 regex + watchdog, Stage 2 LLM judge, quarantine workflow) is implemented correctly. The outbox discipline (persist-before-enqueue) is enforced. The Nostr signature verification uses constant-time comparison for the id check. The SSRF guard is properly wired through the CDI producer. The re-evaluation job correctly separates infra-failure and UNKNOWN classes with independent caps. The admin notification throttling is consistently applied across all failure paths. The per-stage `*_done` flag bitmap (Invariant 5) is honored by every worker.

The three findings above are all low severity. No critical, high, or medium issues were found.
