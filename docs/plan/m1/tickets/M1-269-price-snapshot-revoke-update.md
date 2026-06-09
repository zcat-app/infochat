---
id: M1-269
title: "Pin the price_snapshot role-privilege matrix in DbRoleMatrixIT"
status: done
created: 2026-06-09
last_updated: 2026-06-10
escalations:
  - date: 2026-06-09
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise verified false at implementation start: V39__db_grants_revocations.sql:27
      already contains "REVOKE UPDATE ON price_snapshot FROM infochat_collector" (landed via
      M1-189, commit 3b9112d); DbGrantsRevocationIT already pins both acceptance behaviors
      (collectorUpdateOnPriceSnapshotIsDenied asserts 42501, collectorInsertOnPriceSnapshotStillSucceeds
      asserts INSERT works). No migration after V39 touches price_snapshot. Deep-review v4 H8
      cited V17:85 in isolation and missed V39's revoke.
revisions:
  - date: 2026-06-09
    reason: premise-fail rework — the revoke migration and behavioral test pins already exist on main (V39 + DbGrantsRevocationIT, M1-189); refined to the residual gap, a declarative pin of the full price_snapshot role-privilege matrix (provider legs and DELETE cells are unpinned anywhere)
    prior_values: |
      title: "Revoke UPDATE on price_snapshot from collector role"
      files_budget: 3
      files_scope:
        - infochat-core/src/main/resources/db/migration
        - infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java
      migration_touch: true
      out_of_scope:
        - PriceSnapshotStore — its INSERT … ON CONFLICT DO NOTHING writer is already conformant; no code change.
        - Any other grant in V17 (SELECT and INSERT stay).
        - The asset-command pipeline and snapshot schema.
      acceptance:
        - "A new Flyway migration (next free version; V49 at drafting time) revokes UPDATE on price_snapshot from infochat_collector; it applies cleanly on a fresh DB and on a DB migrated through the current head."
        - "DbRoleMatrixIT asserts infochat_collector can INSERT into price_snapshot but an UPDATE is rejected with a permission error."
        - "mvn -B clean verify from the repo root exits 0."
blocked_by: []
files_budget: 1
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - Any new migration — V39 already revokes UPDATE on price_snapshot from infochat_collector; the grants end-state is correct on main.
  - DbGrantsRevocationIT — its behavioral 42501 probes for the collector legs stay as-is; this ticket adds the declarative matrix, not duplicate behavioral probes.
  - PriceSnapshotStore and the asset-command pipeline/schema — no production code change.
acceptance:
  - "DbRoleMatrixIT gains a named test asserting, via has_table_privilege, the full price_snapshot privilege matrix for both service roles: infochat_collector SELECT=true, INSERT=true, UPDATE=false, DELETE=false; infochat_provider SELECT=true, INSERT=false, UPDATE=false, DELETE=false."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db/DbRoleMatrixIT.java
  preserves:
    - all tests currently green on main
spec_refs: []
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
      added: 115
      removed: 35
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-10
    verdict: CLEAN
    base: cccc741cc91026767c5cff36cd994308394f0066
    head: m1/M1-269-revoke-update-on-pricesnapshot (working tree, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-269-2026-06-10.md
    out_of_model_count: 0
    note: |
      Pre-commit audit after round-1 APPROVE. Test-only diff (DbRoleMatrixIT
      privilege-matrix pin on price_snapshot) — CLEAN, no findings, nothing
      feeds future tickets.
clarity_check:
  date: 2026-06-09
  verdict: WARN
  warnings: ["risk: low is borderline given the ticket includes a Flyway migration; the actual impact is minimal (pure REVOKE, no data at risk) but implementers should re-verify V49 is still the next free version at implementation time (the ticket's own Notes already remind them to do this)."]
---

# M1-269: Pin the price_snapshot role-privilege matrix in DbRoleMatrixIT

## Context

Deep-review v4 HIGH **H8** (`deep-code-review/v4/UNIFIED-REPORT.md` §1; source
`deep-code-review/v4/opus-47/01-architecture.md#F1`) claimed
`V17__price_snapshot.sql:85` leaves `infochat_collector` with an UPDATE grant
contradicting the immutability commitment (`docs/spec/schema.md:605`
"snapshots are immutable history"). The premise is false on main:
`V39__db_grants_revocations.sql:27` (M1-189) already revokes that grant, and
`DbGrantsRevocationIT` already pins the collector legs behaviorally
(UPDATE → 42501, INSERT → succeeds).

The residual gap this refined ticket closes: only 2 of the 8
role×privilege cells on `price_snapshot` are pinned anywhere. The provider
legs (SELECT-only per V17:86) and the DELETE cells (revoked from both
service roles per V17:87-88, retention is operator-driven partition drop,
Invariant 6) have no test. A declarative `has_table_privilege` matrix in
`DbRoleMatrixIT` pins the grants end-state and fails loudly if a future
migration widens either role's write surface.

## Acceptance

See frontmatter: one matrix test in DbRoleMatrixIT.

## Out-of-scope

See frontmatter — no migration, no production code; DbGrantsRevocationIT's
behavioral probes are untouched.

## Notes

- Query shape: `SELECT has_table_privilege(<role>, 'price_snapshot',
  <privilege>)` per role×privilege pair, executed on the `@SeedDataSource`
  owner seam (no SET ROLE needed — `has_table_privilege` takes the role as an
  argument).
- Follow the existing DbRoleMatrixIT style (single test method asserting a
  set/matrix with a descriptive failure message naming the migration that
  established the expectation: V17 grants, V39 revoke).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-269-*.md
```
