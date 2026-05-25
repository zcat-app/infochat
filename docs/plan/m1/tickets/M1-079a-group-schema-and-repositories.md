---
id: M1-079a
title: V20 groups/group_membership migration + repositories
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration/V20__groups_group_membership.sql
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupMembershipRepository.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupRepositoryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-messaging-adapter/** — InMemoryAdapter group SPI is M1-079b
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/** — handler unwinding is M1-079d/e
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java — group dispatch rewiring is M1-079c
  - any /promote, /demote, /group-timezone handler — M1-079c
  - any auto-promote service logic — M1-079c
  - any handler group short-circuit change — M1-079d/e
  - any modification to ScopeRef.java — the existing InboundContext.senderContactId() already carries the actor identity; no ScopeRef widening needed
  - any modification to CommandHandler.java — the SPI signature stays at handle(ScopeRef, String)
  - any modification to any pre-existing test
acceptance:
  - Flyway migration V20__groups_group_membership.sql applies cleanly on a fresh DB
  - "V20 creates table `groups` with columns: id (bigserial PK), adapter (text NOT NULL), upstream_group_id (text NOT NULL), timezone (text NOT NULL DEFAULT 'UTC'), removed_at (timestamptz nullable), created_at (timestamptz NOT NULL DEFAULT NOW()), display_name (text nullable); UNIQUE constraint on (adapter, upstream_group_id)"
  - "V20 creates table `group_membership` with columns: id (bigserial PK), group_id (bigint NOT NULL FK to groups), user_id (bigint NOT NULL FK to users), is_group_admin (boolean NOT NULL DEFAULT false), removed_at (timestamptz nullable), created_at (timestamptz NOT NULL DEFAULT NOW()); UNIQUE constraint on (group_id, user_id)"
  - "V20 creates partial unique index on group_membership enforcing at most one row per group_id WHERE is_group_admin = true AND removed_at IS NULL"
  - "V20 GRANTs SELECT, INSERT, UPDATE on groups and group_membership to the infochat_provider role (matching the V5/V8 precedent for provider-owned tables)"
  - GroupRepositoryTest.findOrCreateByAdapterAndUpstreamId_insertsNewGroup passes
  - GroupRepositoryTest.findOrCreateByAdapterAndUpstreamId_returnsExistingOnDuplicate passes
  - GroupRepositoryTest.markRemoved_setsRemovedAtTimestamp passes
  - GroupRepositoryTest.clearRemoved_unsetsRemovedAtOnRejoin passes
  - GroupMembershipRepositoryTest.addMember_insertsRow passes
  - GroupMembershipRepositoryTest.isGroupAdmin_returnsTrueForAdminRow passes
  - GroupMembershipRepositoryTest.isGroupAdmin_returnsFalseForNonAdminOrAbsent passes
  - GroupMembershipRepositoryTest.promoteToAdmin_setsFlag passes
  - GroupMembershipRepositoryTest.demoteAdmin_clearsFlag passes
  - GroupMembershipRepositoryTest.partialUniqueIndex_rejectsSecondAdmin passes
  - GroupMembershipRepositoryTest.markMemberRemoved_clearsGroupAdminInSameTransaction passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupRepositoryTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Identity and access
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §Authorization model
decision_refs:
  - D9
---

# M1-079a: V20 groups/group_membership migration + repositories

## Context

T2-F (group support) requires the `groups` and `group_membership`
tables as its schema foundation. Every subsequent T2-F ticket depends
on this schema: the InMemoryAdapter group SPI (M1-079b) needs the
tables for lifecycle events, the admin commands (M1-079c) write
membership rows, and the handler unwinding (M1-079d/e) queries
`is_group_admin` to decide permission. This ticket ships the DDL and
the repository classes that encapsulate all SQL access to these tables.

The spec contract is `docs/spec/schema.md` §Identity and access
(Groups, Group membership) + §Invariants (one_admin_per_group partial
unique index).

## Acceptance

1. Flyway migration `V20__groups_group_membership.sql` applies on a
   fresh database and creates both tables with correct columns, types,
   constraints, and grants.
2. The `groups` table has a `(adapter, upstream_group_id)` UNIQUE
   natural key, a `timezone` column defaulting to `'UTC'`, a nullable
   `removed_at` for soft-delete, and a nullable `display_name`.
3. The `group_membership` table has a `(group_id, user_id)` UNIQUE
   constraint, an `is_group_admin` boolean, and a nullable
   `removed_at`.
4. A partial unique index enforces at most one `is_group_admin = true`
   row per group (WHERE `is_group_admin = true AND removed_at IS NULL`).
5. `GroupRepository` supports find-or-create by natural key, mark
   removed, and clear removed.
6. `GroupMembershipRepository` supports add member, is-group-admin
   check, promote (set flag), demote (clear flag), mark member removed
   (with admin-flag clearing in the same transaction), and the partial
   unique index violation surfaces as a predictable exception/return.
7. All repository tests pass; `mvn verify` is green.

## Out-of-scope

- InMemoryAdapter group primitives (M1-079b).
- /promote, /demote, /group-timezone commands (M1-079c).
- GroupAutoPromoteService and InboundRouter group-dispatch changes (M1-079c).
- Handler group short-circuit changes (M1-079d/e).
- Any change to ScopeRef or CommandHandler SPI — the actor identity is
  already available through `InboundContext.senderContactId()`.
- Modification of any pre-existing test.

## Notes

- The V20 migration follows the V5/V7/V8 pattern for grants: the
  Provider role gets SELECT + INSERT + UPDATE (no DELETE on membership
  — soft-delete via `removed_at`).
- `GroupRepository.findOrCreateByAdapterAndUpstreamId` uses
  INSERT...ON CONFLICT DO NOTHING + SELECT pattern (race-safe upsert)
  matching the AutoRegisterService precedent.
- `GroupMembershipRepository.markMemberRemoved` clears `is_group_admin`
  in the same UPDATE statement as setting `removed_at`, per spec
  (schema.md §Group membership — "soft-clear also clears
  is_group_admin in the same transaction").
- Tests use Testcontainers Postgres with Flyway — the same pattern as
  GroupRepositoryTest/GroupMembershipRepositoryTest in M1-008
  schema tests.
