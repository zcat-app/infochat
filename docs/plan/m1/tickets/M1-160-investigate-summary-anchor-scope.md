---
id: M1-160
title: "[INVESTIGATE] summary_anchor scope_kind discriminator"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - implementing a migration before the collision question is decided
acceptance:
  - "Investigate whether DM and group summary anchors can collide on scope_id given the current keying (every other per-(user, scope) table carries a scope_kind discriminator that summary_anchor omits — V19__summary_anchor.sql:5-30)"
  - "Record the decision (add scope_kind vs confirm-safe) with rationale in the ticket Notes / a design note"
  - "If the decision is to add scope_kind: a successor migration adds it and a test asserts a DM anchor and a group anchor with the same scope_id no longer collide"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Per-scope state
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-160: [INVESTIGATE] summary_anchor scope_kind discriminator

## Context

`V19__summary_anchor.sql:5-30` omits the `scope_kind` discriminator every other
per-(user, scope) table carries. The handout's verdict is **FIX (verify first)** —
confirm whether DM/group anchors can actually collide on `scope_id` before adding
the column. This skeleton tracks the investigate-and-decide step the user asked
to keep separate from a committed design direction.

## Acceptance

See frontmatter. Decide first; implement the migration only if the investigation
confirms a real collision.

## Out-of-scope

See frontmatter. Migration version assigned at start (only if needed).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-SUMMARYANCHOR-SCOPE;
  `opus-47-full-handout.md` §F-MAINT-39.
