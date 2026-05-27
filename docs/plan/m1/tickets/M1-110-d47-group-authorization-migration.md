---
id: M1-110
title: "D47 migration — approval_status, activated_by, group_only removal"
status: pending
created: 2026-05-27
last_updated: 2026-05-27
blocked_by: []
files_budget: 4
files_scope:
  - infochat-core/src/main/resources/db/migration/V26__d47_group_authorization.sql
  - infochat-core/src/main/resources/db/migration/V5__identity_audit.sql
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/D47MigrationIT.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-collector/** — no collector changes
  - infochat-provider/src/main/java/** — no Java production code changes (pure migration)
  - any change to InboundRouter, AutoRegisterService, VouchCommandHandler — M1-111
  - any change to GroupApprovalService — M1-112
  - any change to command handlers — M1-113..M1-114
acceptance:
  - "V26__d47_group_authorization.sql adds `approval_status VARCHAR NOT NULL DEFAULT 'pending' CHECK (approval_status IN ('pending','approved','rejected'))` to the `groups` table"
  - "V26 adds `activated_by UUID REFERENCES users(id)` (nullable) to the `groups` table"
  - "V26 sets `approval_status = 'approved'` for all pre-existing groups rows (they were implicitly approved under the pre-D47 model)"
  - "V26 sets `activated_by = NULL` for all pre-existing groups rows (the information is not recoverable)"
  - "V26 executes `UPDATE users SET registration_state = 'invited' WHERE registration_state = 'group_only'` BEFORE altering the CHECK constraint"
  - "V26 inserts an audit_log entry recording the bulk group_only→invited transition (action='D47_MIGRATION', details_json carries the count of affected rows)"
  - "V26 alters the users_registration_state_chk CHECK constraint to `registration_state IN ('preban','invited','vouched')` — the 'group_only' value is removed"
  - "D47MigrationIT verifies: a pre-seeded users row with registration_state='group_only' transitions to 'invited' after migration; a pre-seeded groups row acquires approval_status='approved' and activated_by=NULL; the CHECK constraint rejects an INSERT with registration_state='group_only'"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/D47MigrationIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Identity and access — User entity
  - docs/spec/schema.md §Identity and access — Group entity
  - docs/spec/security.md §Authorization model step 3
decision_refs:
  - D47
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-110: D47 migration — approval_status, activated_by, group_only removal

## Context

D47 (Group authorization gate) introduces two new columns on `groups`
(`approval_status`, `activated_by`) and removes the `group_only` value
from the `users.registration_state` enum. This ticket delivers the
Flyway migration and a smoke-test IT. No Java production code is
changed — the InboundRouter, AutoRegisterService, and command handler
changes land in subsequent tickets.

`security_relevant: true` — the CHECK constraint change is a security
boundary (removing a registration path).

## Acceptance

See frontmatter.

## Out-of-scope

- Java code changes to InboundRouter, AutoRegisterService, etc. — M1-111.
- GroupApprovalService — M1-112.
- New command handlers — M1-113.

## Notes

- **Migration ordering.** The `UPDATE users` must precede the
  `ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT` to avoid a
  CHECK violation during the migration window. Both statements run
  in the same migration script; Flyway wraps each script in a
  transaction by default on PostgreSQL.
- **V5 reference.** V5__identity_audit.sql is listed in files_scope
  because the reviewer needs to verify the existing CHECK constraint
  name to confirm V26 targets the right constraint. V5 itself is NOT
  modified — only read for reference.
- **Existing group_only rows in test data.** Some existing ITs seed
  users with `registration_state='group_only'`. Those tests still
  pass because they seed AFTER migration (Flyway runs before test
  fixtures). But the M1-111 ticket will need to update those
  fixtures to use 'invited' instead.
