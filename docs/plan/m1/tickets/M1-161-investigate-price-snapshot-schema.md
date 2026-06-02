---
id: M1-161
title: "[INVESTIGATE] price_snapshot PK/dedup invariant + new_price_snapshot channel intent"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 5
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - docs/design
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - implementing either option before the intent is decided
acceptance:
  - "Decide intent: the surrogate PK (id, captured_at) dropped the spec's (asset, sub_verb, captured_at) dedup invariant with no replacement UNIQUE, and the new_price_snapshot channel emits with no LISTEN consumer (spec cache layer absent) — V17__price_snapshot.sql:35-52, PriceSnapshotStore.java:20-42"
  - "Record decision: Option A (amend spec to drop the channel + accept the surrogate PK) vs Option B (restore the UNIQUE dedup constraint + implement the consumer)"
  - "Implement the chosen option (migration and/or consumer and/or spec amendment) with a test covering the dedup behaviour"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Asset commands
  - docs/spec/schema.md §Operational
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-161: [INVESTIGATE] price_snapshot PK/dedup + new_price_snapshot channel intent

## Context

`price_snapshot`'s surrogate PK `(id, captured_at)` dropped the spec's
`(asset, sub_verb, captured_at)` dedup invariant with no replacement UNIQUE, and
the `new_price_snapshot` channel has a producer but no LISTEN consumer (the spec
cache layer is absent). The handout's verdict is **FIX-LOW + spec reconciliation —
decide intent first**. This skeleton tracks that decision.

## Acceptance

See frontmatter. Decide Option A vs B, then implement.

## Out-of-scope

See frontmatter. Migration version assigned at start (only if Option B).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-PRICE-SCHEMA / §C-PRICE-NOTIFY-ORPHAN.
