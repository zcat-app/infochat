---
id: M1-377
title: "collector: replace the re-eval one-element-array transaction workaround and unify the two fail-closed test-seam idioms"
status: done
created: 2026-06-14
last_updated: 2026-06-19
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/TransactionHelper.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The TransactionHelper API surface — if it lacks a value-returning lambda form, use the smallest addition needed; do not redesign the helper.
  - The fail-closed quarantine behavior the seams exercise — unchanged; only the injection mechanism is unified, not the tested outcome.
acceptance:
  - "ReEvaluationJob.applyNonBenignReEval (ReEvaluationJob.java:263-274) no longer uses the boolean[] reHidden = {false} one-element-array to smuggle a value out of the transaction lambda; it returns the value through a value-returning transaction form instead. Behavior is unchanged."
  - "The two divergent fail-closed-injection test seams (Stage1Pipeline.java and EmbeddingWorker.java) are unified onto the single safer idiom; the risky static-mutable-field seam is removed in favor of the instance-injected form. The Stage 1 and embedding fail-closed tests that use the seams stay green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1 (seam-idiom update)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding (seam-idiom update)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 156
      removed: 98
escalations:
  - date: 2026-06-19
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      FILES-BUDGET-PLAUSIBLE: FAIL — files_scope omits
      infochat-collector/src/main/java/app/zcat/infochat/collector/eval/TransactionHelper.java,
      which acceptance item 1 requires modifying (only void inTransaction exists;
      the value-returning form must be added, called in-scope by the Notes section).
revisions:
  - date: 2026-06-19
    reason: clarity-fail rework — files_scope omitted TransactionHelper.java, which acceptance item 1 requires modifying (value-returning transaction form must be added)
    prior_values: |
      files_scope:
        - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
        - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1
        - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding
      (TransactionHelper.java absent)
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-06-19
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-377: re-eval array workaround + fail-closed seam unification

## Context

Deep-review v7 (opus-48) collector findings **F4** (simplification) and **F5**
(test-seam drift), bundled — both low, both collector eval-path tidy-ups.
Verified at source 2026-06-14:

- **F4:** `ReEvaluationJob.applyNonBenignReEval`
  (`.../eval/reeval/ReEvaluationJob.java:263-274`) uses `boolean[] reHidden = {false}`
  to return a value from a `TransactionHelper.inTransaction` void lambda — a
  workaround for the lambda not returning a value.
- **F5:** two divergent test-seam idioms for the same fail-closed-injection
  purpose across `Stage1Pipeline` and `EmbeddingWorker`, one of which is a risky
  static-mutable field. Unify onto the safer instance-injected idiom.

Both are pure maintainability tidy-ups with no behavior change. **Not a beta
blocker.**

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- If `TransactionHelper` has no value-returning form, the minimal addition of one
  is in scope for F4; keep it surgical.
