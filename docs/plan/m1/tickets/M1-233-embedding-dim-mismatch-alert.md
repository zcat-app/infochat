---
id: M1-233
title: "Embedding dimensionality mismatch: alert operator, stop spamming"
status: done
created: 2026-06-08
last_updated: 2026-06-09
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerDimensionMismatchTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The startup dimensionality guard (EmbeddingMetadataStartupGuard) — the boot-time refusal is correct and unchanged; this ticket only fixes the RUNTIME per-vector mismatch path.
  - The re-embed procedure itself — unchanged; the alert points operators at it.
  - The narrow INSERT/UPDATE transaction boundary and the no-partial-state guarantee — preserved (no wrong-dimension vector is ever inserted).
  - Other eval workers' notification behavior — unchanged (this ticket aligns EmbeddingWorker WITH their existing notifyOnce pattern).
acceptance:
  - "A runtime per-vector dimensionality mismatch no longer throws IllegalStateException out of the @Scheduled tick on every poll forever; instead it fires one coalesced operator alert via ThrottledAdminNotifier.notifyOnce (keyed on a canonical embedding-dimension-mismatch error class) and skips the batch, leaving the affected posts embedding_done=FALSE."
  - "No wrong-dimension vector is ever inserted (the existing pre-INSERT validation/return-before-INSERT safety property is preserved)."
  - "A named test asserts that on a mismatch the worker calls notifyOnce exactly once across repeated ticks (coalesced) and does NOT insert an embedding row for the affected post."
  - "A named test asserts the repeated mismatch does not propagate an exception out of onTick (the tick completes; no stack-trace-per-poll loop)."
  - "EmbeddingWorkerIT's existing dimension-mismatch scenario (previously dimensionMismatchThrowsImmediatelyAndLeavesPostsInFlight, which asserted assertThrows(IllegalStateException.class)) is rewritten to assert the reversed contract: processBatch does NOT throw on a per-vector mismatch, inserts no post_embedding row for the affected posts, and leaves embedding_done=FALSE. This is the one pre-existing test that pinned the old throw behavior; the rewrite is the authorized consequence of the behavior reversal, not new scope."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerDimensionMismatchTest.java
  modifies:
    - file: infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
      why: >-
        Test #4 (dimensionMismatchThrowsImmediatelyAndLeavesPostsInFlight) pins
        the OLD throw behavior via assertThrows(IllegalStateException.class, () ->
        processBatch(...)). This ticket reverses that behavior to notify+skip
        (Option A), so the scenario is rewritten to assert no-throw + no-insert +
        embedding_done=FALSE. Authorized modification of an existing test whose
        assertion the acceptance criteria deliberately invert — not a weakening.
  preserves:
    - all OTHER tests currently green on main (only the single dimension-mismatch
      scenario in EmbeddingWorkerIT is rewritten; the other six IT scenarios and
      every test in other files stay unchanged and green)
spec_refs:
  - docs/spec/llm.md §Embedding pipeline
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 371
      removed: 43
escalations:
  - date: 2026-06-08
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation escalation. The Option A fix (notify + skip,
      `return` instead of `throw`, per the source finding and this ticket's
      out_of_scope adoption) makes EmbeddingWorker.processBatch no longer
      throw on a per-vector dimension mismatch. The existing green test
      EmbeddingWorkerIT.dimensionMismatchThrowsImmediatelyAndLeavesPostsInFlight
      (infochat-collector/src/test/java/app/zcat/infochat/collector/eval/
      embedding/EmbeddingWorkerIT.java:174-199) pins the OLD behavior via
      assertThrows(IllegalStateException.class, () -> processBatch(...)).
      That file is NOT in files_scope and test_plan.preserves claims all
      currently-green tests stay green — both unsatisfiable as written.
overrides: []
revisions:
  - date: 2026-06-08
    reason: >-
      budget-breach refine (round 1) — authorize rewriting the one pre-existing
      test that pins the throw behavior this ticket reverses. Added
      EmbeddingWorkerIT.java to files_scope, added a test_plan.modifies entry for
      it, and added an acceptance item making the IT rewrite explicit. files_budget
      unchanged (4 covers the 3 touched files).
    snapshot:
      files_scope:
        - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
        - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerDimensionMismatchTest.java
      test_plan:
        adds:
          - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerDimensionMismatchTest.java
        preserves:
          - all tests currently green on main
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-08
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-233: Embedding dimensionality mismatch: alert operator, stop spamming

## Context

Deep-review finding `deep-code-review/v2.5/opus-48/06-module-infochat-collector.md#F2`
(medium MAINTAINABILITY-RULES-DRIFT / spec-drift). `docs/spec/llm.md`
§Embedding pipeline calls a runtime dimensionality mismatch *fatal*: "The
only safe recovery is a full re-embed." The implementation does the
opposite of fatal: `EmbeddingWorker` throws `IllegalStateException`
(`EmbeddingWorker.java:241-256`) out of the `@Scheduled` tick (`onTick`
calls `processBatch` with no surrounding catch), Quarkus logs and swallows
it, the idempotent pickup query re-selects the same batch next poll, and
the worker throws again — forever, at the poll cadence. No admin
notification fires (every other eval worker uses
`ThrottledAdminNotifier.notifyOnce` for operator-action conditions; this
one does not), so affected posts sit at `embedding_done=FALSE` and never
reach READY while nobody is told.

## Acceptance

See frontmatter. In prose: convert the silent infinite throw/log loop into
one coalesced `notifyOnce` operator alert plus a batch skip, preserving the
no-wrong-vector-inserted safety property; named tests pin alert-once
coalescing, no-insert, and that the tick no longer throws; `mvn verify` is
0.

## Out-of-scope

See frontmatter. The startup guard, the re-embed procedure, the
transaction boundary, and other workers' behavior are unchanged. This is
the recommended Option A (notify + skip) from the source finding; the
harsher Option B (process exit) is rejected because one transient
wrong-shaped vector should not take down all ingest stages.

## Notes

- Recommended fix and error-class-constant shape are in the source
  finding. Inject `ThrottledAdminNotifier` mirroring `TaggerWorker` /
  `EntityExtractorWorker` / `ReEvaluationJob`.
- The pipeline stays "soft-stalled" (affected posts wait) and resumes
  automatically after the operator re-embeds — matching the spec's
  operator-action intent without halting unrelated stages.
