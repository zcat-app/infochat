---
id: M1-189
title: "DB grants: revoke PUBLIC on quarantine procs + price_snapshot UPDATE"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 5
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/test/java/app/zcat/infochat/collector/db
  - docs/spec/security.md
  - docs/spec/schema.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - the V35 last-admin LOCK TABLE scoping — M1-190's migration (V40); keep the two migrations independent
  - the infochat_admin paper-principal decision (audit finding D5 — USAGE-only grants vs documented escape hatches) — a separate decision-tier ticket, not yet filed
  - asset_config grants — Collector INSERT/UPDATE on asset_config is spec-compliant and untouched
  - V38's dedup constraint and any data-shape change to price_snapshot rows
  - renaming the vs_currency COLUMN in code/DDL — if the naming reconciliation lands as a spec amendment (the surgical option), no code identifier changes
acceptance:
  - "Per docs/spec/security.md §DB roles — the Provider role carries \"`EXECUTE` on the `approve_quarantine` and `reject_quarantine` stored procedures\" and no other service role is granted them — migration V39 revokes the Postgres-default PUBLIC EXECUTE on both SECURITY DEFINER functions: a named IT asserts infochat_collector gets permission-denied calling either function while infochat_provider still executes them (today no REVOKE … FROM PUBLIC exists in V21/V25/V32, in contrast to V5's delete_preban_user which does revoke)"
  - "Per docs/spec/schema.md §Operational (Price snapshot) — \"**INSERT-only**; no updates.\" — V39 revokes UPDATE on price_snapshot from infochat_collector: a named IT asserts UPDATE as infochat_collector fails with permission denied while INSERT still succeeds (today V17:85 grants SELECT, INSERT, UPDATE)"
  - "docs/spec/security.md §DB roles no longer lists price_snapshot under the Collector's INSERT/UPDATE tables — the wording is reconciled to schema.md's INSERT-only contract (the audit adjudicated schema.md as the winner of this spec-internal contradiction)"
  - "docs/spec/schema.md §Operational and V17's implemented shape agree on the snapshot's currency column name (spec says `currency`, V17 says `vs_currency`) and on the `asset` referential claim (spec says FK to asset_config; V17 has none) — either V39 implements, or the spec text is amended to the implemented shape, with the choice argued in the commit message"
  - "mvn -B clean verify from the repo root exits 0 (the Flyway ITs prove V39 applies on a fresh DB and on a V38-migrated DB)"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/db
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §DB roles
  - docs/spec/schema.md §Operational
decision_refs:
  - D34
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-189: DB grants: revoke PUBLIC on quarantine procs + price_snapshot UPDATE

## Context

Three grant-layer gaps (unified findings D1, D2, D3, D4 —
`deep-code-review/v2/UNIFIED.md` §2):

1. **PUBLIC EXECUTE on SECURITY DEFINER procs (D1, high-sec).**
   `approve_quarantine` / `reject_quarantine` are SECURITY DEFINER across
   V21/V25/V32 with `GRANT EXECUTE … TO infochat_provider` but no
   `REVOKE ALL … FROM PUBLIC` — Postgres grants PUBLIC EXECUTE on functions
   by default and `CREATE OR REPLACE` preserves it. The Collector role
   (which has SELECT on users to resolve an admin UUID) can therefore call
   the definer-privileged quarantine procedures. V5:398 shows the repo's own
   correct pattern (`REVOKE ALL ON PROCEDURE delete_preban_user … FROM
   PUBLIC`).
2. **V17 UPDATE grant vs INSERT-only spec (D2).** V17:85 grants
   `SELECT, INSERT, UPDATE ON price_snapshot TO infochat_collector`; the
   spec (schema.md) and V38's own comment say the table is INSERT-only.
3. **Spec-internal contradiction (D3).** security.md §DB roles says the
   Collector has "INSERT/UPDATE on ingest-owned tables (including
   `price_snapshot` …)" while schema.md says INSERT-only — the audit
   adjudicated schema.md as correct; the security.md sentence is the one to
   fix (a spec edit bundled with the code change, per the ticket rule).
4. **FK + naming drift (D4, low).** schema.md promises "`asset` (FK to
   `asset_config`)" and a `currency` column; V17 implements `asset TEXT NOT
   NULL` with no REFERENCES and names the column `vs_currency`.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T13 under `deep-code-review/v2/` (opus-48 core F1,
  opus-47 core F2, kimi-folder arch F4 / core F1).
- Migration version: **V39** (worktrees swept 2026-06-07 at draft time —
  none in flight; re-sweep `.claude/worktrees/*/…/db/migration/` at start
  per the migration-lane rule and bump if taken).
- Once V39 revokes PUBLIC, later `CREATE OR REPLACE` of the functions
  preserves the tightened ACL — the fix is durable against future
  re-declarations.
- On the FK leg: `asset_config`'s key is `(asset, sub_verb)` — a column FK
  on `asset` alone would need a UNIQUE on `asset_config.asset` that does
  not exist, which is why the spec-amend direction is plausible; decide
  with the schema in front of you, not from the spec sentence alone.
- DbRoleMatrixIT (infochat-collector/…/db) is the existing role-assertion
  pattern to extend.
