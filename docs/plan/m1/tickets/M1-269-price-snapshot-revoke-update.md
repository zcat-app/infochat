---
id: M1-269
title: "Revoke UPDATE on price_snapshot from collector role"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 3
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - PriceSnapshotStore — its INSERT … ON CONFLICT DO NOTHING writer is already conformant; no code change.
  - Any other grant in V17 (SELECT and INSERT stay).
  - The asset-command pipeline and snapshot schema.
acceptance:
  - "A new Flyway migration (next free version; V49 at drafting time) revokes UPDATE on price_snapshot from infochat_collector; it applies cleanly on a fresh DB and on a DB migrated through the current head."
  - "DbRoleMatrixIT asserts infochat_collector can INSERT into price_snapshot but an UPDATE is rejected with a permission error."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-269: Revoke UPDATE on price_snapshot from collector role

## Context

Deep-review v4 verified HIGH **H8** (`deep-code-review/v4/UNIFIED-REPORT.md`
§1; source `deep-code-review/v4/opus-47/01-architecture.md#F1`):
`V17__price_snapshot.sql:85` grants `SELECT, INSERT, UPDATE` on
`price_snapshot` to `infochat_collector`. The spec commits to immutability
(`docs/spec/schema.md:605` "snapshots are immutable history"; design 10
"dropped, never updated") and the only writer, `PriceSnapshotStore`, uses
`INSERT … ON CONFLICT DO NOTHING`. The UPDATE grant is unused privilege that
contradicts the immutability commitment — pure defense-in-depth revoke.

## Acceptance

See frontmatter: one revoke migration plus the role-matrix assertion.

## Out-of-scope

See frontmatter — no code change anywhere; this is a one-line migration and a
test.

## Notes

- Migration version: V49 was free on main and across all in-flight worktrees
  at drafting time (swept per the MIG-lane rule); re-verify the next free
  version at implementation time and serialize with M1-276, which also adds a
  migration.
- Follow `V46__grant_update_summary_cache.sql` as the precedent for
  grant-shaped migrations.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-269-*.md
```
