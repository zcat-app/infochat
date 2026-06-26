---
id: M1-462
title: "Deep-review low-severity cleanup sweep: dead scanWindow(), brittle quarantine error mapping, Reddit bare permalink, two SSRF doc/dedup nits"
status: done
created: 2026-06-26
last_updated: 2026-06-26
blocked_by: []
clarity_check:
  date: 2026-06-26
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE F4: acceptance item 4 (F4) is verified by inspection only — could be sharpened to name the specific comment text or location."
    - "ACCEPTANCE-RUNNABLE F5: acceptance item 5 (F5) is verified by inspection only with an OR branch — same sharpening opportunity as F4."
    - "TEST-CHANGES-AUTHORIZED: EmbeddingWorkerPickupFloorIT is modified (comment-only per F1) but not listed in test_plan.modifies."
    - "SECURITY-FLAG-CONSISTENT: SsrfGuardedHttpClient is a security enforcement file; security_relevant: false is acceptable here because F4/F5 are comment-only with no functional change."
reviews:
  - round: 1
    date: 2026-06-26
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 178
      removed: 33
  - round: 2
    date: 2026-06-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 216
      removed: 33
files_budget: 9
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/PartitionScan.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/PartitionScanSharedSourceTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerPickupFloorIT.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParserPermalinkTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "The SQLSTATE-based refactor of the quarantine stored procedure (giving its two RAISE EXCEPTION statements distinct ERRCODEs so the Java side can switch on SQLSTATE like GroupAutoPromoteService does). That is the heavier, migration-touching alternative for finding F3; this ticket takes the no-migration path (a test that anchors the existing substring coupling so a wording drift fails the build loudly). The SQLSTATE refactor is recorded as the Alternative below."
  - "Any production behaviour change to PartitionScan.scanWindowFloor(Instant) — only the dead String-INTERVAL scanWindow() overload is removed."
  - "Any behaviour change in SsrfGuardedHttpClient's resolve/validate path or its redirect handling — findings F4 and F5 are documentation/dedup only, no functional change."
  - "Re-opening the §9 injectable-Clock migration — out of scope here (LlmRateCap is M1-460; the partition-scan workers were M1-448)."
  - "Findings the review verified as NOT actionable and deliberately dropped: the InboundRouter.formatTimeUntilUnlock display-clock nit (§9-exempt display site, M1-451 left it intentionally) and the SignalGroupHandler.stripBotMentions getInt nit (false positive — Parsson's getInt(name,default) returns the default for a wrong-typed value, it does not throw)."
acceptance:
  - "F1 (dead code): PartitionScan no longer declares the String-returning scanWindow() overload; every production caller already uses scanWindowFloor(Instant) (the §9-migrated path), so the removal is behaviour-neutral. The class javadoc no longer documents the retired now() - ?::INTERVAL shape. PartitionScanSharedSourceTest's assertion on scanWindow() is removed (the method it covered is gone) and EmbeddingWorkerPickupFloorIT's stale scanWindow() comment is updated to scanWindowFloor."
  - "F3 (brittle error mapping): a test drives both QuarantineCommandHandler stored-procedure error paths (not-found and not-PENDING/BENIGN_CLOSED) and asserts each maps to its specific bundle reply (ERROR_QUARANTINE_NOT_FOUND / ERROR_QUARANTINE_INVALID_STATE), NOT the generic ERROR_INTERNAL fallback — so a future edit to the procedure's RAISE wording fails this test loudly instead of silently degrading the user reply."
  - "F2 (Reddit permalink): an empty/missing upstream permalink is handled deliberately rather than silently producing the content-free bare URL https://www.reddit.com — either guarded (mirroring the adjacent created_utc branch's logged substitution) or documented with a WHY-comment stating the bare-domain fallback is tolerated and why. A test pins the chosen behaviour for an empty-permalink item."
  - "F4 (SSRF previousResponse): BoundedByteArrayResponse.previousResponse() carries a comment explaining it returns Optional.empty() by design even though the wrapper may have followed redirects (the wrapper does not expose its internal hop chain). No behaviour change."
  - "F5 (SSRF UNKNOWN_HOST dup): the relationship between defaultResolve (throws UNKNOWN_HOST on getAllByName failure, never returns null/empty) and resolveAndValidate's null/empty re-check is documented as a resolver-seam contract (the null/empty branch guards a test/alternate seam, not the production resolver), or the duplication is collapsed behind that documented contract. No behaviour change."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "QuarantineCommandHandler error-mapping test (unit or Testcontainers IT, per whether a real DB is needed to raise the procedure errors) asserting both stored-proc error paths map to their specific bundle replies, not ERROR_INTERNAL."
    - "RedditResponseParser test asserting the chosen empty-permalink behaviour."
  modifies:
    - "PartitionScanSharedSourceTest — remove the assertEquals on the deleted scanWindow() overload (the only change; the scanWindowFloor coverage stays)."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
---

# M1-462: Deep-review low-severity cleanup sweep

## Context

The 2026-06-26 `/deep-code-review full` run surfaced a set of low-severity
findings. After per-finding verification, five are real and actionable and are
batched here (the project already batches deep-review lows — cf. M1-218
"provider-lows", M1-412 "low-parity-comment-sweep"). Two further findings were
verified as non-actionable and are deliberately dropped (see `out_of_scope`).

The five in scope:

- **F1 — dead `PartitionScan.scanWindow()`.** After the injected-Clock
  migration (the `scanWindowFloor(Instant)` path), the String-INTERVAL
  `scanWindow()` overload in
  `infochat-collector/src/main/java/app/zcat/infochat/collector/eval/PartitionScan.java`
  has no production caller (verified: only the class's own javadoc, one test
  assertion in `PartitionScanSharedSourceTest`, and a stale comment in
  `EmbeddingWorkerPickupFloorIT` mention it). Its javadoc still documents the
  retired `now() - ?::INTERVAL` shape, inviting reintroduction of the exact §9
  regression the migration fixed.
- **F3 — brittle quarantine error mapping.**
  `QuarantineCommandHandler.mapStoredProcError` distinguishes the stored
  procedure's two errors with `e.getMessage().contains("not found")` /
  `contains("expected PENDING or BENIGN_CLOSED")`. A wording edit in the
  procedure silently collapses a specific friendly reply into the generic
  ERROR_INTERNAL with no compile-time or test anchor — a user-visible
  regression that ships unnoticed across a cross-service SECURITY DEFINER
  contract.
- **F2 — Reddit bare permalink.** `RedditResponseParser` builds the post URL as
  `"https://www.reddit.com" + permalink.asText()`. An empty/missing permalink
  yields a content-free bare-domain URL that is stored and emitted to users,
  unguarded and uncommented — unlike the adjacent `created_utc` field, which is
  guarded with a logged substitution.
- **F4 — SSRF `previousResponse()`.** `BoundedByteArrayResponse.previousResponse()`
  hard-returns `Optional.empty()`, reading as "no redirect happened" even when
  the wrapper manually followed hops. Documentation-only.
- **F5 — SSRF `UNKNOWN_HOST` duplication.** `defaultResolve` throws
  `UNKNOWN_HOST` (and `InetAddress.getAllByName` never returns null/empty), yet
  `resolveAndValidate` re-checks null/empty and throws `UNKNOWN_HOST` again. In
  the production path only one branch is reachable; the null/empty check guards
  the resolver seam. Documentation/dedup only.

## Acceptance

See the YAML `acceptance:` list — one item per finding, plus a green full suite.
Every change is behaviour-neutral in production except F2's deliberate
empty-permalink handling.

## Out-of-scope

See the YAML `out_of_scope:` list. Notably: the SQLSTATE-migration alternative
for F3, any functional change to the SSRF resolve/redirect path, the §9
migration, and the two dropped findings.

## Notes

- F1: removing `scanWindow()` also trims the class javadoc's INTERVAL wording
  and updates the one-line `scanWindowFloor` mirror reference. The
  `PartitionScanSharedSourceTest` assertion on the removed overload is deleted;
  the `scanWindowFloor` coverage stays. `EmbeddingWorkerPickupFloorIT`'s comment
  referencing `scanWindow()` is updated to `scanWindowFloor`.
- F3: the no-migration fix is a loud test, not a refactor — see the Alternative
  for the SQLSTATE route. `GroupAutoPromoteService` is the in-repo example of
  SQLSTATE-based dispatch if the Alternative is ever taken.
- F2: prefer the smallest deliberate handling — match the `created_utc` branch's
  logged-substitution idiom, or a WHY-comment if the bare-domain fallback is
  genuinely acceptable.

## Alternatives considered

- **F3 via SQLSTATE (deferred).** Give the quarantine stored procedure's two
  `RAISE EXCEPTION` statements distinct `ERRCODE`s and switch on
  `SQLException.getSQLState()` in Java (the `GroupAutoPromoteService` pattern).
  This is the structurally cleaner fix but requires a Flyway migration to the
  procedure (`migration_touch`), changing this ticket's risk profile. Deferred
  to a dedicated ticket if the test-anchor proves insufficient; recorded here so
  the choice is explicit.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-462-deep-review-low-severity-cleanup-sweep.md
```

## Round 1 rework

Reviewer verdict round 1: REWORK (1 item). Only SCOPE-DRIFT-CHECK failed; all
other checks (test-integrity, out-of-scope, negative-space, acceptance,
spec-conformance) PASS, and the implementation code is confirmed correct.

1. `files_scope` is non-empty but omits the two test files the diff touches, so
   under the strict files_scope membership rule both count as out-of-scope.
   Both are already authorized by `test_plan.adds` (the QuarantineCommandHandler
   error-mapping test) and the natural home of the Reddit empty-permalink test;
   the total stays at the same file count, within `files_budget: 9`. The fix is
   a frontmatter scope correction — add these two paths to `files_scope`:
     - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParserPermalinkTest.java
     - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
   No source/test code change is required. Because this is a `files_scope`
   change, it is routed through `escalate → refine` per the cross-cutting rule
   rather than edited silently in-band.

   **Resolution (user-approved refine, 2026-06-26):** both test paths added to
   `files_scope` (now 8 paths, within `files_budget: 9`). No code change. Re-review
   as round 2.
