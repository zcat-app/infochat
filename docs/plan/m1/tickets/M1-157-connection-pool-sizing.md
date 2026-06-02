---
id: M1-157
title: "Explicit connection-pool sizing per profile"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
