---
id: M1-110
title: "D47 migration — groups.approval_status + activated_by (additive)"
status: pending
created: 2026-05-27
last_updated: 2026-05-28
blocked_by: []
files_budget: 2
files_scope:
  - infochat-core/src/main/resources/db/migration/V26__d47_group_authorization.sql
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/D47MigrationIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-collector/** — no collector changes
  - infochat-provider/src/main/java/** — no Java production code changes (pure migration + IT)
  - users.registration_state CHECK alteration, group_only→invited backfill, audit_log entry for the transition — moved to M1-111 (atomic with the code/test consumer updates)
  - any change to InboundRouter, AutoRegisterService, VouchCommandHandler — M1-111
  - any change to GroupApprovalService — M1-112
  - any change to command handlers — M1-113..M1-114
acceptance:
  - "V26__d47_group_authorization.sql adds `approval_status VARCHAR NOT NULL DEFAULT 'pending' CHECK (approval_status IN ('pending','approved','rejected'))` to the `groups` table"
  - "V26 adds `activated_by UUID REFERENCES users(id)` (nullable, no DEFAULT) to the `groups` table"
  - "V26 sets `approval_status = 'approved'` for all pre-existing groups rows (grandfathered under the pre-D47 model). `activated_by` is left NULL (the information is not recoverable; no UPDATE statement)"
  - "D47MigrationIT verifies the post-V26 schema shape via plain INSERT/SELECT against the test datasource (no Flyway callback, no two-phase migration, no programmatic Flyway reconfiguration): (a) INSERTing a groups row without specifying approval_status succeeds and the row reads back with approval_status='pending' (column exists + default applied); (b) INSERTing a groups row with `approval_status='maybe'` raises a CHECK violation — the literal 'maybe' is chosen because it is non-NULL, so the CHECK failure is isolated from the NOT NULL violation that a NULL value would raise first; (c) INSERTing a groups row with `activated_by=NULL` succeeds (column exists + nullable); (d) `grep -E '@Test' D47MigrationIT.java` returns ≥3 matches"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/D47MigrationIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Identity and access
  - docs/spec/security.md §Authorization model
decision_refs:
  - D47
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-110: D47 migration — groups.approval_status + activated_by (additive)

## Context

D47 (Group authorization gate) introduces two new columns on the
`groups` table: `approval_status` (gates whether a group's messages
are processed) and `activated_by` (records the user who first vouched
or first @mentioned the bot into an approved group). This ticket
delivers the **additive** portion of the migration only — adding the
two columns and grandfathering pre-existing groups to `approved`.

The complementary removal of `registration_state='group_only'` from
the `users.registration_state` enum (CHECK alteration + backfill UPDATE
+ audit_log entry) is **out of scope here** and lives in M1-111. That
split is deliberate: the CHECK alteration cannot land cleanly without
its code/test consumers (`AutoRegisterService.resolveOrRegisterGroup`,
`VouchCommandHandlerTest.seedUser`, `AutoRegisterServiceTest`) updated
in the same change, and those updates are M1-111's scope. Splitting
the additive piece off into this ticket avoids a broken intermediate
state in M1-110's worktree.

No Java production code is changed by this ticket — the InboundRouter,
AutoRegisterService, GroupApprovalService, and command handler
changes land in subsequent tickets (M1-111..M1-114).

`security_relevant: true` — the columns are read by the group
authorization gate that M1-112 implements; the additive surface here
is the foundation for that gate.

## Acceptance

See frontmatter.

## Out-of-scope

- `users.registration_state` CHECK constraint alteration — M1-111.
- `group_only`→`invited` backfill UPDATE on users — M1-111.
- audit_log entry recording the group_only transition — M1-111.
- Java code changes to InboundRouter, AutoRegisterService, etc. — M1-111.
- GroupApprovalService (the consumer of `approval_status`) — M1-112.
- New command handlers (`/approve-group`, `/reject-group`, `/list-groups`) — M1-113.

## Notes

- **Additive-only scope, no DROP / no ALTER.** V26 contains only
  `ALTER TABLE groups ADD COLUMN ...` and one backfill `UPDATE
  groups SET approval_status='approved'`. There is no CHECK
  alteration, no DROP CONSTRAINT, and no statement that touches
  the `users` table. Flyway wraps the script in a transaction on
  PostgreSQL; the additive shape means the script is atomic by
  construction without ordering subtleties.
- **D47MigrationIT design — pragmatic schema-shape test.** The IT
  does not seed a pre-V26 row to assert the `approval_status='approved'`
  backfill transition directly. The backfill UPDATE is trivially
  correct (a single unparameterized `UPDATE` over one column), and
  Flyway's checksum invariant prevents V26 from being mutated after
  merge — so the protective value of a behavioral backfill assertion
  is theoretical. The IT instead asserts the runtime contract the
  application sees: columns exist, DEFAULT applies, CHECK rejects
  invalid values, `activated_by` is nullable. This avoids the
  extra Flyway-callback class (or two-phase programmatic Flyway
  reconfiguration) that a pre-V26 seed would require.
- **CHECK assertion uses 'maybe', not NULL.** Inserting NULL into
  `approval_status` would raise a NOT NULL violation before the
  CHECK is evaluated, masking the CHECK from the test. The IT uses
  the literal `'maybe'` (or any non-NULL out-of-set value) so the
  CHECK failure is the observed cause.
- **Pre-existing group_only test fixtures are M1-111's concern.**
  Some existing ITs (e.g. `VouchCommandHandlerTest.seedUser`,
  `AutoRegisterServiceTest`) seed users with
  `registration_state='group_only'`. Those fixtures continue to
  work after V26 because V26 does not touch the
  `users_registration_state_chk` constraint. They become broken
  only when M1-111 lands the CHECK alteration, and M1-111 owns
  updating them in the same change.
