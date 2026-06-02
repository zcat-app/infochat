---
id: M1-128
title: "ReEvaluationJob enumerate filter + cap-exhaustion transition + IT"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the infochat.reeval.* config keys (covered by M1-122)
  - PerSourceUnknownTracker / AdminReviewTtlJob — unchanged
acceptance:
  - "enumerateCandidates no longer filters re_eval_attempts < cap, so a cap-reached row enters processOne and the >= cap branch fires"
  - "A cap-exhausted QUARANTINED row transitions to NEEDS_REVIEW and the throttled admin notification fires — covered by an IT that seeds a cap-exceeded row and drives the full scheduled path (not a hand-constructed candidate that bypasses enumerateCandidates)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Re-evaluation job
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-128: ReEvaluationJob enumerate filter + cap-exhaustion transition + IT

## Context

`enumerateCandidates` filters `re_eval_attempts < cap` (`:289,292`), so a
cap-reached row never enters `processOne`; the in-process
`reEvalAttempts() >= cap → transitionToNeedsReview` branch (`:109-110`) is
structurally unreachable from the scheduled path. Cap-exhausted rows stay
`QUARANTINED` forever, the spec-mandated `NEEDS_REVIEW` transition never happens,
and the operator-alerting commitment never fires. The unit test passes only
because it bypasses `enumerateCandidates`.

## Acceptance

See frontmatter. Drop the `re_eval_attempts < ?` predicates from enumerate; let
`processOne`'s cap check drive the transition. Add an IT exercising the full
scheduled path on a seeded cap-exceeded row.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A7 (REEVAL-CAP-UNREACHABLE, Critical, GROUNDED);
  `opus-47-full-handout.md` §F-MAINT-05.
- `security.md` §Re-evaluation job mandates the transition.
