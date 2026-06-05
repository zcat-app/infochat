---
id: M1-157
title: "Explicit connection-pool sizing per profile"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - the structural intake-path connection-threading refactor (CONN-CHURN — WATCH, deliberate per-step isolation, not ticketed)
acceptance:
  - "Both services declare an explicit quarkus.datasource.jdbc.max-size with %laptop/%vps overrides, sized for the collector's scheduled workers + lock connection and the provider's lock + NewPostListener (rather than relying on the Agroal default 20)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Configuration surface (spec level)
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 55
      removed: 9
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "ACCEPTANCE item 1 states the pool should be 'sized for the collector's scheduled workers + lock connection' but does not give a concrete value or formula. The reviewer can confirm the property key and profile overrides exist, but cannot verify numeric correctness without independently knowing the worker counts from design notes. Consider adding a concrete expected value (e.g., 'max-size=8 for laptop, 12 for vps') so the acceptance criterion is fully self-contained."
    - "files_budget is 3 but files_scope lists only 2 files. Either reduce files_budget to 2 to match files_scope, or add the third file to files_scope."
  blockers: []
---

# M1-157: Explicit connection-pool sizing per profile

## Context

Neither service sets `quarkus.datasource.jdbc.max-size` (Agroal default 20). The
collector has 5+ scheduled workers + 1 long-lived lock connection; the provider
has the lock connection + `NewPostListener`. Explicit profile-aware sizing is
cheap and removes a latent saturation surprise. This is the actionable part of
the CONN-CHURN observation; the structural intake-path refactor is a deliberate
per-step-isolation design (fresh-ban-check TOCTOU closure) and stays WATCH-only.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §B-CONN-POOL-SIZE, §D-CONN-CHURN;
  `opus-47-full-handout.md` §F-PERF-15, §F-PERF-01 (WATCH).
