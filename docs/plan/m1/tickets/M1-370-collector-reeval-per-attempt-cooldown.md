---
id: M1-370
title: "collector: add a per-attempt re-eval cooldown so the fail-open backlog is not re-judged each tick"
status: done
created: 2026-06-14
last_updated: 2026-06-19
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - The per-tick providerDown latch (M1-342) — unchanged; it already bounds the LLM-outage case to one call/tick. This ticket targets the steady-recovery re-judge spacing, not the outage path.
  - The infra-failure-cap / unknown-cap values and the NEEDS_REVIEW transition — unchanged.
  - The UNKNOWN (QUARANTINED) disjunct of enumerateCandidates — the cooldown applies to the infra-failure disjunct that re-selects fail-open posts; leave the UNKNOWN second-opinion cadence as-is unless trivially shared.
acceptance:
  - "A new nullable column post.last_reeval_at is added by a forward-only migration. enumerateCandidates' infra-failure disjunct gains an AND (last_reeval_at IS NULL OR last_reeval_at < now() - ?::INTERVAL) predicate; every re-eval attempt stamps last_reeval_at = now() in the SAME transaction as the attempt-counter / verdict update so the cooldown and the progress record cannot diverge."
  - "A new config knob infochat.reeval.cooldown (default a multiple of infochat.reeval.poll-interval) supplies the interval, present in application.properties for every profile."
  - "A test in infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval asserts a just-attempted candidate is excluded on the immediately-following tick and re-enumerated after the cooldown elapses, while still advancing toward its cap."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-core/src/main/resources/db/migration/V52__post_last_reeval_at.sql (forward-only migration adding nullable post.last_reeval_at)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobCooldownTest.java (new cooldown enumeration + stamp test; no existing test modified — resolves the clarity WARN)
  modifies:
    - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java (cooldown predicate on the infra-failure disjunct + last_reeval_at stamp on the three counter-increment UPDATEs)
    - infochat-collector/src/main/resources/application.properties (infochat.reeval.cooldown knob, base + four profile overrides)
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
      files: 7
      added: 332
      removed: 31
escalations:
  - date: 2026-06-19
    reason: scope-path-stale
    reviewer_verdict_excerpt: |
      N/A — surfaced at /m1-tick start grounding (start.md step 0): the
      files_scope migration path infochat-collector/src/main/resources/db/migration
      does not exist on disk. All Flyway migrations are centralized in
      infochat-core/src/main/resources/db/migration (collector
      application.properties comment: "migrations move to infochat-core
      after M1-007a"). The post-table migration adding last_reeval_at must
      land in infochat-core; the stale path would force a write outside
      files_scope (an escalation trigger). User chose refine.
revisions:
  - date: 2026-06-19
    reason: refine ticket spec (scope-path-stale rework) — migration path correction
    prior_values: |
      files_scope (line 11) and test_plan.adds (line 30) both pointed the
      forward-only migration at infochat-collector/src/main/resources/db/migration,
      which does not exist. Corrected both to
      infochat-core/src/main/resources/db/migration (the centralized Flyway
      location; next free version V52). No other frontmatter changed;
      files_budget (5) still covers ReEvaluationJob.java + the migration +
      application.properties + the test.
overrides: []
aborted_attempts: []
reopens:
  - date: 2026-06-19
    prior_deferred_reason: blocked-on-new-ticket
    prior_deferred_on: M1-400
    reason: |
      M1-400 (the EntityExtractorWorkerIT time-bomb that blocked only the
      full-suite gate, not this ticket's own tests) merged to main as
      bdfad69a. Resumed on the existing branch per the recorded defer path:
      the branch already holds the complete impl (WIP acd67fa2), the clarity
      check, and in-progress status, so it rebases onto fresh main and goes
      straight to verify → review rather than re-running /m1-tick start.
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-06-19
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: test_plan.modifies lists the test directory but not the specific file being modified. Name the exact file (e.g., ReEvaluationJobTest.java or a new class in test_plan.adds) so the reviewer can confirm no existing assertion is silently changed."
  blockers: []
---

# M1-370: re-eval per-attempt cooldown

## Context

Deep-review v7 (opus-48) collector finding **F2** (PERFORMANCE). Verified at
source 2026-06-14 — **premise partly overstated, severity adjusted down**:

`ReEvaluationJob.enumerateCandidates`
(`infochat-collector/.../eval/reeval/ReEvaluationJob.java:527-558`) selects
`stage2_failed = TRUE AND status != 'NEEDS_REVIEW'` (`LIMIT batchSize`,
`ORDER BY fetched_at, id`) and `onTick` (146-164) issues a fresh
`Stage2Worker.judgeBody` per candidate, contending with live first-pass Stage 2
on the shared semaphore.

The report framed this as "re-judges the entire backlog every tick." That is an
overstatement: a BENIGN re-judge calls `clearStage2FailedAndRequeueIfQuarantined`
and the post drops out next tick, so a **benign-dominated backlog drains in one
pass (each post judged once)**, and the `providerDown` latch already bounds an
LLM outage to one call/tick. The genuine residual is narrower: non-benign
fail-open posts are re-judged up to `infraFailureCap` (12) times by design, and
`ORDER BY fetched_at, id LIMIT 16` lets a non-benign front-of-queue slice delay
tail posts during a large recovery. A per-attempt cooldown spaces those
re-judges and bounds LLM cost to the *rate* of new fail-open posts rather than
the standing backlog. **This is a low-priority efficiency improvement, not a
beta blocker** — captured because the durable fix needs a schema column.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- **Migration version:** grab the next free `Vnn` at implementation time by
  sweeping ALL in-flight worktrees for `db/migration/V*.sql` (see
  `docs/plan/m1/parallelization.md` MIG-lane order and the
  migration-version-grab rule); current max on `main` is V51 but a worktree may
  hold a higher number. Do not hard-code a version from this ticket.
- **No-migration alternative (Option B, recorded, not chosen):** cap the
  infra-failure fan-out per tick to a small constant independent of `batchSize`.
  It avoids the migration but does not bound total work over time and lets
  low-`fetched_at` posts monopolize the slice. The column-backed cooldown is the
  durable form for single-instance v1 (an in-memory map is lost across restarts).
