---
id: M1-707
title: "Operator-settable default timezone for new groups"
status: pending
created: 2026-07-27
last_updated: 2026-07-27
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupRepository.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupRepositoryTest.java
  - infochat-core/src/main/resources/db/migration/V66__provider_groups_timezone_insert_grant.sql
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    `/group-timezone` and its handler. The per-group runtime override
    already works; this ticket sets the value a group STARTS with and
    must not change the command's validation, permission tier, audit row
    or reply.
  - >-
    The `groups.timezone` DDL default (`NOT NULL DEFAULT 'UTC'`, V5). It
    stays as the last-resort default for any writer that omits the
    column; the new key sets what the Provider writes, it does not
    replace the column default.
  - >-
    Digest slot arithmetic — `DigestScheduler`'s window computation,
    stagger and missed-slot handling all read `groups.timezone` and are
    unaffected by where the initial value came from.
  - >-
    Widening the Provider's grants beyond the single `timezone` column
    on `groups`. V62 deliberately narrowed the Provider to
    column-scoped INSERT/UPDATE; this ticket adds exactly one column to
    the INSERT list and touches no other table, column or role.
  - infochat-collector/**
acceptance:
  - >-
    `infochat.groups.default-timezone` exists in the Provider's
    application.properties with `UTC` as its committed default, and is
    the value a newly-created group row receives.
  - >-
    GroupRepositoryTest proves a group created while the key is set to a
    non-UTC IANA zone is persisted with that zone, and that a group
    created under the default is still `UTC`.
  - >-
    A value that is not a resolvable IANA zone id refuses boot with a
    message naming the key, rather than writing an unusable zone that
    would surface later as a digest scheduling failure. Config parsing
    is a system boundary, so this validation belongs here.
  - >-
    Migration V66 widens the Provider's column-scoped INSERT grant on
    `groups` to include `timezone` and nothing else; it applies cleanly
    on a fresh database and the existing V62 grant tests stay green.
  - >-
    docs/design/07-deployment.md §7.4's commented-out
    `infochat.groups.default-timezone` line and its GAP note are
    replaced by the real key and value.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupRepositoryTest.java
      — new group carries the configured zone; default remains UTC.
  preserves:
    - >-
      Every existing GroupRepository test — the natural-key upsert,
      the D47 race-safe `INSERT ... ON CONFLICT DO NOTHING RETURNING id`
      path, the activation-cap count and the removed/restored paths.
      The INSERT column list grows by one column; its conflict semantics
      and return contract must not change.
    - >-
      The V62 grant-surface tests. The Provider's least-privilege
      posture is a security control (M1-672); a test that pins which
      columns the Provider may write is load-bearing beyond its name and
      must be updated to the new expected column set, not deleted or
      retargeted.
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Configuration surface (spec level)
decision_refs:
  - D47
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-707: Operator-settable default timezone for new groups

## Context

`docs/spec/deployment.md` §Configuration surface commits, under Groups:
"Default group timezone for newly-created groups (`UTC` by default; **an
operator may override**)." The override half does not exist.

Verified: `infochat.groups.default-timezone` is read by nothing.
`GroupRepository`'s two creation statements —
`INSERT INTO groups (adapter, upstream_group_id)` (`:33`) and the D47
`INSERT INTO groups (adapter, upstream_group_id, activated_by) …
RETURNING id` (`:58`) — both omit the column, so every group starts on
the DDL default (`groups.timezone TEXT NOT NULL DEFAULT 'UTC'`, V5).
`/group-timezone` mutates it afterwards, so the per-group half works and
only the deployment-wide default is missing.

An operator running a single-region deployment currently has to run
`/group-timezone` in every group by hand, and the digest for a group's
first window fires on UTC regardless of where its members are.

Recorded in the doc-drift audit 2026-07-27 (`.scratch/doc-audit.md` §A6);
`docs/design/07-deployment.md` §7.4 carries the key commented out with a
GAP note.

## Acceptance

- `infochat.groups.default-timezone` exists with `UTC` as the committed
  default and supplies the timezone a new group row is created with.
- `GroupRepositoryTest` proves a group created under a non-UTC setting
  persists that zone, and that the default path still yields `UTC`.
- A non-resolvable zone id refuses boot with a message naming the key.
- Migration V66 adds `timezone` — and only `timezone` — to the
  Provider's column-scoped INSERT grant on `groups`, applies cleanly on
  a fresh database, and leaves the rest of the V62 grant surface intact.
- `docs/design/07-deployment.md` §7.4 carries the real key instead of
  the GAP note.
- `mvn verify` from the repo root is green.

## Out-of-scope

`/group-timezone` keeps its current validation, tier, audit row and
reply. The `groups.timezone` DDL default stays as the column-level
last resort. Digest window/stagger arithmetic is untouched. The grant
change adds one column to one table for one role — no other widening.

## Notes

- **The hidden coupling that makes this a migration ticket.** V62 line
  587 is `GRANT INSERT (adapter, upstream_group_id, activated_by) ON
  groups TO infochat_provider`. Adding `timezone` to the INSERT column
  list without widening that grant fails at runtime under the real
  Provider role — and passes under any test that runs as a superuser, so
  the failure would ship. That is the reason for `migration_touch: true`
  and `risk: medium` on an otherwise three-line change.

- **The alternative that avoids the migration**, weighed and not
  preferred: the Provider already holds `GRANT UPDATE (timezone,
  digest_enabled, removed_at)` (V62:586), so creation could INSERT as
  today and then UPDATE the zone. It needs no migration but costs a
  second statement on the group-creation path, splits "what a new group
  is" across two writes, and leaves a window where the row exists with
  the wrong zone. Widening the INSERT grant by one column is the
  narrower change. If the implementer prefers the UPDATE shape, say so
  at `start` rather than switching silently — `files_scope` and
  `migration_touch` both assume the grant path.

- **Adjacent pattern.** `InterruptibleDispatcher.java:154` is the
  existing example of a Provider config key validated at injection with
  a message naming the key; match that shape rather than inventing a new
  one. `GroupTimezoneCommandHandler.java:108-110` shows the `ZoneId.of`
  validation the command already applies.
