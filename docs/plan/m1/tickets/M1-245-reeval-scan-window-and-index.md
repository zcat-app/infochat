---
id: M1-245
title: "Re-evaluation candidate scan: fetched_at window + partial index"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobWindowTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - PerSourceUnknownTracker — it already carries the fetched_at lower bound this ticket adds to ReEvaluationJob; it is the reference pattern, not a target.
  - The re-eval verdict/attempt-update statements (the point WHERE id=? AND fetched_at=? updates) — unchanged; only the candidate-enumeration scan gains the window bound.
  - The retention-horizon value itself — reuse the same horizon+slack PerSourceUnknownTracker documents; do not introduce a new tuning knob.
acceptance:
  - "The ReEvaluationJob candidate-enumeration query gains a fetched_at >= ? lower bound (window size = retention horizon + slack, matching PerSourceUnknownTracker's documented bound) so the RANGE(fetched_at) partitioned post table can prune partitions instead of a full multi-partition scan every tick. ReEvaluationJobWindowTest asserts the enumeration binds a fetched_at floor derived from now() - (horizon+slack) and that a candidate older than the floor is not enumerated while an in-window candidate is."
  - "A new Flyway migration (next free version — sweep all in-flight worktrees for V*.sql before assigning, per the migration-version-grab rule) adds a partial index supporting the disjunctive re-eval predicate (stage2_failed / stage2_verdict / re_eval_attempts) so the planner can use it together with the fetched_at bound; the migration applies cleanly on a fresh DB (mvn verify exercises Flyway migrate)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobWindowTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Pipelines
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
      files: 3
      added: 204
      removed: 2
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-245: Re-evaluation candidate scan — window bound + partial index

## Context

Source: `deep-code-review/v3/` UNIFIED-REPORT.md T5 (opus `06#F1`, medium perf).

`ReEvaluationJob` enumerates candidates with a disjunctive predicate carrying
**no `fetched_at` lower bound** — the outlier among the file's otherwise-point
`WHERE id=? AND fetched_at=?` statements. `post` is `RANGE (fetched_at)`
partitioned (V7) and no migration indexes `stage2_failed` / `stage2_verdict` /
`re_eval_attempts`, so the planner can prune nothing: a full multi-partition scan
every poll tick (5m). The sibling `PerSourceUnknownTracker` deliberately adds a
`fetched_at >= now() - (window+slack)` bound to get pruning; this job omits it.

The fix is two coordinated halves — the query change alone doesn't restore
partition pruning, the index alone doesn't prune partitions. Cross-module: the
query lives in `infochat-collector`, the index in an `infochat-core` migration.

## Acceptance

See frontmatter. In prose: add the `fetched_at >= ?` window bound to the
candidate scan (window = retention horizon + slack, as `PerSourceUnknownTracker`
documents) and pair it with a partial index in a new `infochat-core` migration; a
named test pins the window floor; `mvn verify` (which runs Flyway migrate) is 0.

## Out-of-scope

See frontmatter. `PerSourceUnknownTracker`, the per-row update statements, and
the horizon value are untouched.

## Notes

- `migration_touch: true` — serializes against other migration-touching tickets.
  Sweep all in-flight worktrees for the highest `V*.sql` before assigning the
  next version (the current main highest is V46; V20 is an intentional gap, see
  M1-248).
- The partial-index predicate must match the query's disjunction so the planner
  picks it; confirm with the query shape, not by guessing column order.
- Coordinate the two halves in one commit — landing the query without the index
  (or vice versa) leaves a half-fix that still scans.
</content>
</invoke>
