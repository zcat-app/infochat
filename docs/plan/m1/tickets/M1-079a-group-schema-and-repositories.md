---
id: M1-079a
title: GroupRepository + GroupMembershipRepository
status: done
created: 2026-05-25
last_updated: 2026-05-25
reviews:
  - round: 1
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 499
      removed: 10
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupRepository.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupMembershipRepository.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupRepositoryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupMembershipRepositoryTest.java
complexity: medium
risk: medium
clarity_check:
  date: 2026-05-25
  verdict: WARN
  warnings:
    - "risk: low under-calibrated for authorization-adjacent is_group_admin column — bumped to medium"
round_cap: 2
security_relevant: true
migration_touch: false
redteam_findings: []
redteam_audits:
  - date: 2026-05-25
    verdict: CLEAN
    base: main
    head: m1/M1-079a-group-repositories
    verdict_file: docs/plan/m1/redteam/M1-079a-2026-05-25.md
    out_of_model_count: 0
    note: |
      Repository-only ticket with no user-facing surface. All SQL uses
      parameterized queries; partial unique index constraint handled via
      SQLSTATE 23505 return. Transactional composability for promote+demote
      deferred to M1-079c.
out_of_scope:
  - infochat-core/src/main/resources/db/migration/** — schema already exists in V5
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
  - GroupMembershipRepositoryTest.markMemberRemoved_triggersAdminFlagClear passes
  - "GroupRepository uses INSERT...ON CONFLICT DO NOTHING + SELECT pattern for race-safe upsert (matching AutoRegisterService precedent)"
  - "GroupMembershipRepository.markMemberRemoved sets removed_at; the DB trigger (V5) clears is_group_admin in the same transaction — test verifies the trigger fires"
  - "All repository methods use UUID parameters for group_id and user_id (matching V5 schema: groups.id is UUID, group_membership PK is composite (group_id, user_id))"
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

# M1-079a: GroupRepository + GroupMembershipRepository

## Context

T2-F (group support) requires repository classes that encapsulate SQL
access to the `groups` and `group_membership` tables. These tables
already exist (created in V5__identity_audit.sql with full constraints,
triggers, and grants) — this ticket provides the application-layer
repository abstraction.

Every subsequent T2-F ticket depends on these repositories: the
InMemoryAdapter group SPI (M1-079b) needs them for lifecycle events,
the admin commands (M1-079c) write membership rows, and the handler
unwinding (M1-079d/e) queries `isGroupAdmin()` to decide permission.

The schema contract is `docs/spec/schema.md` §Identity and access
(Groups, Group membership) + §Invariants (one_admin_per_group partial
unique index, enforced by V5's `one_admin_per_group` index + the
`trg_group_membership_clear_admin` trigger).

## Existing schema (V5, for reference)

```sql
-- groups: UUID PK, (adapter, upstream_group_id) UNIQUE
CREATE TABLE groups (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    adapter           TEXT NOT NULL,
    upstream_group_id TEXT NOT NULL,
    display_name      TEXT,
    timezone          TEXT NOT NULL DEFAULT 'UTC',
    removed_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (adapter, upstream_group_id)
);

-- group_membership: composite PK (group_id, user_id)
CREATE TABLE group_membership (
    group_id       UUID NOT NULL REFERENCES groups(id),
    user_id        UUID NOT NULL REFERENCES users(id),
    is_group_admin BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at     TIMESTAMPTZ,
    PRIMARY KEY (group_id, user_id)
);

-- Partial unique index: at most one admin per group
CREATE UNIQUE INDEX one_admin_per_group
    ON group_membership(group_id) WHERE is_group_admin = TRUE;

-- Trigger: clears is_group_admin when removed_at is set
CREATE TRIGGER trg_group_membership_clear_admin ...
```

## Acceptance

1. `GroupRepository` provides find-or-create by `(adapter,
   upstream_group_id)` using INSERT...ON CONFLICT DO NOTHING + SELECT
   (race-safe upsert matching AutoRegisterService precedent).
2. `GroupRepository` provides mark-removed (sets `removed_at`) and
   clear-removed (nulls `removed_at` on re-add).
3. `GroupMembershipRepository` provides add-member, is-group-admin
   check, promote (set flag), demote (clear flag), and mark-member-
   removed (sets `removed_at`; V5 trigger clears `is_group_admin`).
4. The partial unique index violation on a second promote surfaces as
   a predictable exception or boolean return (not an uncaught
   SQLException).
5. All repository methods use UUID parameters matching V5 schema types.
6. All tests pass; `mvn verify` is green.

## Out-of-scope

- DDL / schema changes — tables already exist in V5.
- InMemoryAdapter group primitives (M1-079b).
- /promote, /demote, /group-timezone commands (M1-079c).
- GroupAutoPromoteService and InboundRouter group-dispatch changes (M1-079c).
- Handler group short-circuit changes (M1-079d/e).
- Any change to ScopeRef or CommandHandler SPI.
- Modification of any pre-existing test.

## Notes

- Follows the plain-JDBC + DataSource pattern established by
  ChatSessionRepository and SummaryAnchorRepository.
- `group_membership` uses a composite PK `(group_id, user_id)` — no
  surrogate id column. Repository methods take both UUIDs.
- The `markMemberRemoved` test verifies the V5 trigger behavior
  (is_group_admin cleared when removed_at is set) — the repository
  sets removed_at and the test asserts is_group_admin became false.
- Tests use Testcontainers Postgres with Flyway — same pattern as
  existing provider repository tests.
