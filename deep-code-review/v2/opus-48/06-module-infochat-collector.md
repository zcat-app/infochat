# Deep code review: module infochat-collector

**Target:** module infochat-collector
**Lens:** module
**Module path:** infochat-collector/
**Date:** 2026-06-06
**Reviewer:** senior-developer (opus)

## Headline findings

- [high] SECURITY — infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:124-126,173-185 — a released-`READY` Stage-2-infra-failure post that re-evaluates to `INJECTION`/`MALWARE`/`UNKNOWN` is left user-visible (only its attempt counter is bumped); the spec requires it to return to `QUARANTINED`.
- [low] MAINTAINABILITY-RULES-DRIFT — cross-cutting (see CURRENT-CODE) — 156 hand-written `@NonNull` annotations across 37 files contradict engineering-rule §7a ("`@NonNull` is no longer written by hand") because the package is already null-marked.
- [low] MAINTAINABILITY-RULES-DRIFT — infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/QuarantineDao.java:119-124 — `span_start`/`span_end` are documented as "byte offsets" but are populated from Java `String` char (UTF-16) indices, which diverge for any non-ASCII body.

## Detail

### F1. Re-eval of a released infra-failure post does not re-hide judge-confirmed-hostile content

- **Category:** SECURITY
- **Severity:** high
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java:106-127, 173-185 (enumerate predicate at 291-297)

**Current code:**

```java
void processOne(@NonNull ReEvalCandidate candidate) {
    int cap = candidate.stage2Failed() ? infraFailureCap : unknownCap;

    if (candidate.reEvalAttempts() >= cap) {
        transitionToNeedsReview(candidate);
        return;
    }

    String originalBody = reconstructOriginalBody(candidate);
    Stage2VerdictHandler.Verdict verdict = stage2Worker.judgeBody(candidate.postId(), originalBody);

    if (verdict == Stage2VerdictHandler.Verdict.BENIGN) {
        applyBenignReEval(candidate);
    } else if (verdict == Stage2VerdictHandler.Verdict.INFRA_FAILURE) {
        // Transient LLM outage — do not consume an attempt.
        LOG.infof("ReEvaluationJob: INFRA_FAILURE for post_id=%s — skipping attempt increment",
            candidate.postId());
    } else {
        incrementAttemptCounter(candidate);
    }
}

// ...

private void incrementAttemptCounter(ReEvalCandidate candidate) {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(
             "UPDATE post SET re_eval_attempts = re_eval_attempts + 1 "
                 + "WHERE id = ? AND fetched_at = ?")) {
        ps.setObject(1, candidate.postId());
        ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
        ps.executeUpdate();
    } catch (SQLException e) { /* ... */ }
}
```

The candidate set includes the infra-failure class, which on the `release-on-stage2-failure=true` profiles (laptop/pi defaults) is `status='RAW' → READY` and **user-visible**:

```java
// enumerateCandidates()
"  (stage2_failed = TRUE AND status != 'NEEDS_REVIEW')"   // <- includes READY posts
"  OR "
"  (status = 'QUARANTINED' AND stage2_done = TRUE AND stage2_failed = FALSE)"
```

**Why this is wrong / suboptimal / risky:**

`docs/spec/security.md` §Re-evaluation job, verdict handling, states for the non-benign branch:

> `INJECTION`, `MALWARE`, or `UNKNOWN` on **either class** → post stays `QUARANTINED`, the `stage2_failed` flag is **preserved** (or set, if the prior verdict was UNKNOWN) alongside the new verdict, and the attempt counter increments.

For the UNKNOWN class the post is already `QUARANTINED`, so `incrementAttemptCounter` is sufficient — and the only test that exercises this branch (`ReEvaluationJobTest.reEvalNonBenign_staysQuarantined_incrementsCounter`, line 102) seeds an *UNKNOWN-QUARANTINED* post and passes. But for the infra-failure class the post was released to `READY` precisely because Stage 2 could not run. The re-evaluation job exists to revisit that degraded decision once the judge recovers. When the recovered judge now returns `INJECTION` or `MALWARE`, this code only bumps `re_eval_attempts` and leaves the post `READY`. The judge-confirmed-malicious content remains served to users for the full `infraFailureCap` budget of attempts before `transitionToNeedsReview` finally hides it.

`release-on-stage2-failure=true` is an accepted trade-off for the *outage window* ("injection content may reach LLM call sites with only Stage 1 redactions" — `StartupReleaseOnStage2FailureWarn`). It is not a license to keep serving content **after** the judge has recovered and explicitly classified it as hostile. The spec's "post stays `QUARANTINED`" on either class is exactly the remediation that closes that window; the code does not perform it. There is no test for `candidateFor(post, true, …)` (infra-failure, stage2Failed=true) with an `INJECTION` verdict, so the gap is also untested.

**Recommended fix:**

On a non-benign re-eval verdict, re-quarantine the post (idempotent for the already-`QUARANTINED` UNKNOWN class) in the same transaction as the counter bump:

```java
private void incrementAttemptCounter(ReEvalCandidate candidate) {
    TransactionHelper.inTransaction(dataSource, "ReEvaluationJob.nonBenign", conn -> {
        // Re-quarantine: no-op for an already-QUARANTINED UNKNOWN post,
        // but re-hides an infra-failure post that had been released READY
        // now that a recovered judge has returned a hostile verdict
        // (security.md §Re-evaluation job: "on either class → post stays
        // QUARANTINED"). status_changed_at advances so the M2
        // quarantine_review cursor sees the move.
        final String sql =
            "UPDATE post SET status = 'QUARANTINED', "
                + "       re_eval_attempts = re_eval_attempts + 1, "
                + "       status_changed_at = now() "
                + "WHERE id = ? AND fetched_at = ? AND status <> 'NEEDS_REVIEW'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, candidate.postId());
            ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
            ps.executeUpdate();
        }
    });
}
```

Add an IT/unit asserting that `candidateFor(infraFailurePost, /*stage2Failed*/ true, 0)` with an `INJECTION` stub verdict ends with `status='QUARANTINED'` and `re_eval_attempts=1`.

**Reasoning:**

The fix makes the code honor the spec's invariant that a hostile re-eval verdict hides the post regardless of which class it came from. Re-quarantining a previously-`READY` post is the correct remediation: a post the judge now calls `INJECTION` must not remain in the user-visible set. The `status <> 'NEEDS_REVIEW'` guard preserves the cap-exhaustion path's terminal state, and the move is idempotent for the UNKNOWN class that is already `QUARANTINED`.

**Trade-offs:**

A post that was promoted `READY` (and already emitted `new_post`) being pulled back to `QUARANTINED` means the Provider must observe the demotion. The `quarantine_review` channel already carries `post → NEEDS_REVIEW` transitions but not a `READY → QUARANTINED` move; the demotion currently relies on the Provider's high-water-mark re-read of the row, which is acceptable in v1 but is a contract edge worth noting to the architecture pass. The fix also widens a single-statement UPDATE into a `TransactionHelper` block — negligible cost, and consistent with the rest of this class.

---

### F2. Hand-written `@NonNull` annotations throughout the module contradict the null-marking convention

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** cross-cutting (see CURRENT-CODE) — 156 occurrences across 37 files

**Current code:**

```java
// e.g. infochat-collector/.../eval/TransactionHelper.java:22-24
public static void inTransaction(@NonNull DataSource dataSource,
                                 @NonNull String context,
                                 @NonNull TxBody body) {

// e.g. infochat-collector/.../assets/source/AssetDataSource.java:34,41,48,61,70
@NonNull String id();
@NonNull Set<String> supportedAssets();
@NonNull PriceSnapshot fetchSnapshot(@NonNull String asset, @NonNull String vs) throws FetchException;

// e.g. infochat-collector/.../eval/reeval/ReEvaluationJob.java:106
void processOne(@NonNull ReEvalCandidate candidate) { ... }
```

**Why this is wrong / suboptimal / risky:**

Engineering-rule §7a is explicit: "Non-null is the **package default** — every `app.zcat.infochat` package is null-marked (NullAway `AnnotatedPackages`), so a bare reference type means 'never null.' Only genuinely-nullable parameters, returns, and fields carry `@Nullable`; **`@NonNull` is no longer written by hand.**" Because the package is null-marked, every one of these 156 `@NonNull` annotations is semantically redundant — a bare `DataSource` already means non-null. The redundant annotations add reading noise and, worse, create a false signal: a reader who sees `@NonNull` on some parameters may infer that bare parameters elsewhere are *not* guaranteed non-null, inverting the intended default. This is a module-wide style drift from a stated rule, not a correctness defect (the green build proves nullability is enforced regardless).

**Recommended fix:**

Delete the hand-written `@NonNull` annotations (and the now-unused `import org.jspecify.annotations.NonNull;`) module-wide; keep only `@Nullable` where a value is genuinely nullable. Example:

```java
public static void inTransaction(DataSource dataSource,
                                 String context,
                                 TxBody body) {
```

**Reasoning:**

Removing the redundant annotations restores the single source of truth for nullability (bare = non-null, `@Nullable` = the only marked case), which is the entire point of the null-marked package convention. NullAway continues to enforce the contracts at compile time, so behavior is unchanged.

**Trade-offs:**

This is a large mechanical sweep touching most files in the module with no behavioral change; it should ride its own dedicated cleanup ticket rather than being folded into feature work (§1 surgical-changes). Given the rule also says "the reviewer no longer checks annotation presence," this is recorded as low — it is a style-convention cleanup, not a defect.

---

### F3. Quarantine span offsets documented as "byte offsets" are actually char indices

- **Category:** MAINTAINABILITY-RULES-DRIFT
- **Severity:** low
- **Location:** infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/QuarantineDao.java:46-52, 119-124; values produced at infochat-collector/.../eval/stage1/Stage1Pipeline.java:333-334

**Current code:**

```java
// QuarantineDao.java javadoc
//   {@code span_start}/{@code span_end} as byte offsets in the
//   original body, ...
// @param spanStart      byte offset of the matched span in the
//                       original body; {@code 0} on a watchdog abort
// @param spanEnd        byte offset (exclusive) of the matched
//                       span end; {@code body.length()} on a
//                       watchdog abort.
```

```java
// Stage1Pipeline.findAllMatchesUnderWatchdog — the values actually stored
int start = m.start();   // java.util.regex Matcher -> char (UTF-16) index
int end = m.end();
String span = body.substring(start, end);
all.add(new Match(rule.ruleId(), start, end, span));
```

**Why this is wrong / suboptimal / risky:**

`Matcher.start()`/`end()` and `String.substring`/`StringBuilder.replace` all operate on Java `char` (UTF-16 code-unit) indices, not UTF-8 byte offsets. The values written to `quarantine.span_start`/`span_end` are therefore char indices, but the DAO javadoc (and the `QuarantineRow` param docs) repeatedly call them "byte offsets." For any body containing non-ASCII content (emoji, CJK, accented Latin — common in feed bodies), a consumer that trusts the "byte offset" wording and indexes into the UTF-8 encoding of the body would land on the wrong span. The redaction itself is correct (it replaces by the same char indices it computed), and re-evaluation reconstructs via placeholder substitution rather than offsets, so this is a documentation defect, not a runtime bug today — but the quarantine row is the admin-facing audit record, and an admin or future tool that reads these columns per their documented contract will be misled.

**Recommended fix:**

Correct the wording to "char (UTF-16 code-unit) offset" wherever the DAO and `QuarantineRow` describe `span_start`/`span_end`, and add a one-line note that the offsets index the post-normalization Java `String`, not its UTF-8 encoding:

```java
 * @param spanStart  char (UTF-16) offset of the matched span in the
 *                   NFKC-normalized body String; 0 on a watchdog abort.
 * @param spanEnd    char (UTF-16) offset (exclusive); body.length() on a
 *                   watchdog abort.
```

**Reasoning:**

The columns are informational alongside `original_html` (which carries the verbatim span), so renaming the documented unit — rather than changing the stored values — is the minimal correct fix and keeps the redaction logic untouched.

**Trade-offs:**

None — the fix is strictly a doc correction.

---

## Synthesizer-relevant observations

- F1's recommended fix re-introduces a `READY → QUARANTINED` demotion that has no dedicated `quarantine_review` NOTIFY shape (the channel carries `post → NEEDS_REVIEW` but not `post → QUARANTINED`); whether the Provider's high-water-mark re-read covers a demotion of an already-`new_post`-notified row is a cross-module contract question for the architecture lens (`docs/spec/architecture.md` §Inter-service communication).
- `PriceSnapshotStore.store` (assets/store/PriceSnapshotStore.java:83-140) combines a `@Transactional` method with an explicit `dataSource.getConnection()` and an in-band `pg_notify`, relying on Agroal enlisting the manually-obtained connection into the active JTA transaction so the NOTIFY commits atomically with the INSERT. This works in Quarkus but is a non-obvious coupling between the JTA boundary and a hand-managed connection; the architecture pass may want to confirm the same pattern is used consistently (ReadyPromoter instead manages an explicit non-JTA transaction for the identical INSERT+NOTIFY shape).
