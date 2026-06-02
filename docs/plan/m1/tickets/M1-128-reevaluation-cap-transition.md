---
id: M1-128
title: "ReEvaluationJob enumerate filter + cap-exhaustion transition + IT"
status: done
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
security_relevant: true
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
reviews:
  - round: 1
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 159
      removed: 16
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-02
    verdict: CLEAN
    base: 03f78b84197fd0681c907c98c4f1754bfc58342b
    head: de4db544dcc874ee99bf37d125b51bd33347845c
    verdict_file: docs/plan/m1/redteam/M1-128-2026-06-02.md
    out_of_model_count: 1
    note: >
      CLEAN. Diff makes the spec-mandated cap-exhaustion → NEEDS_REVIEW
      transition reachable; separate caps, throttled notification, and
      RE_EVAL_RELEASED audit preserved. One OUT-OF-MODEL advisory
      (overlapping @Scheduled ticks → benign idempotent double-transition,
      throttle-coalesced notification, no audit duplication); no remediation
      ticket warranted — spec makes no concurrency commitment for this job.
clarity_check:
  date: 2026-06-02
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: security_relevant was false, but the ticket fixes a spec-mandated security commitment in the quarantine/admin-notification surface (docs/spec/security.md §Re-evaluation job); flipped to true at start so /redteam is triggered post-merge."
  blockers: []
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
