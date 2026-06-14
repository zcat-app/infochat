---
id: M1-327
title: "EmbeddingWorker: notify-once + skip on non-finite vector components"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The two-attempt attemptEmbed retry, the dimension-mismatch path, and the idempotent enumeratePending pickup — unchanged; this ticket adds a parallel non-finite branch with the same notify-once + skip shape as the existing dimension-mismatch path.
  - Changing formatVector's Float.toString round-trip for finite values — unchanged.
acceptance:
  - "EmbeddingWorker rejects non-finite vector components (Float.NaN / POSITIVE_INFINITY / NEGATIVE_INFINITY) BEFORE the INSERT transaction, mirroring the existing per-vector dimension-mismatch path: on a non-finite component the worker emits one coalesced operator alert via throttledAdminNotifier.notifyOnce(<error-class>, ...), logs at ERROR with post_id/index/value, and returns before any INSERT/UPDATE so the post stays embedding_done=FALSE and resumes when the provider recovers. A new error-class constant (e.g. ERROR_CLASS_EMBEDDING_NONFINITE) names the condition."
  - "No code path reaches formatVector (or the ?::vector PGobject cast) with a non-finite component, so the SQLException-every-tick wedge (pgvector rejects NaN/Infinity, the idempotent pickup re-selects the same batch forever) cannot occur."
  - "A test pins the wedge fix: a stubbed embedding provider returning a vector with one Float.NaN component produces exactly one notifyOnce call and no row in post_embedding, the post remains embedding_done=FALSE, and a subsequent tick with a finite vector completes the embedding (auto-recovery). A companion test confirms the all-finite happy path is unchanged."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding (non-finite component cases)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 352
      removed: 8
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-327: EmbeddingWorker — notify-once + skip on non-finite vector components

## Context

Deep-review v5.5 (opus-47, `06-module-infochat-collector.md` F1, the run's only
`high`) found that `EmbeddingWorker.formatVector` renders each element with
`Float.toString` and has no NaN/Infinity guard. `Float.NaN` renders as `"NaN"`
and infinities as `"Infinity"`/`"-Infinity"`; none parses as a pgvector literal,
so the `INSERT INTO post_embedding ... ?::vector` throws `SQLException` out of the
transaction, which `processBatch` does not catch.

The next scheduled tick re-runs the idempotent `enumeratePending`
(`status='RAW' AND tagger_done=TRUE AND embedding_done=FALSE` still matches) and
re-picks the same wedged batch: the two-attempt retry succeeds (the provider
returned a "valid"-length vector), the dimension check passes (a NaN vector has
the right length), and `formatVector` throws again — forever, with stack-trace
spam and no operator alert. **Verified at source 2026-06-14:** `formatVector`
(EmbeddingWorker.java:436-454) has no `isFinite`/`isNaN` check anywhere in the
file.

This is the same failure class the dimension-mismatch path was hardened against
(notify-once + skip rather than throw), bypassed because no length mismatch
occurs. A buggy/compromised remote provider, transport corruption, or a model
whose normalization underflows can all emit a non-finite component.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Follow the exact shape of the existing dimension-mismatch path: pre-INSERT
  validation, one coalesced `notifyOnce` per throttle window, `return` before any
  write, post stays `embedding_done=FALSE`, idempotent pickup auto-recovers when
  the provider output normalizes.
- pgvector REJECTS non-finite components, so storing them is not an option; the
  only choices are stall-with-alert (this fix) or stall-silently-with-stack-spam
  (current). The fix strictly improves the operator surface.
