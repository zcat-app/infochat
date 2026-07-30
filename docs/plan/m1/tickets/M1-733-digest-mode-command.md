---
id: M1-733
title: "Add /digest brief|normal|full and the DIGEST_MODE_SET audit verb"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by:
  - M1-732
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
decomposed_from: M1-722
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-733: the /digest mode verb

> **Skeleton from the M1-722 decompose (2026-07-30).** `acceptance`,
> `out_of_scope` and the sizing fields still need authoring.
>
> **Sizing this ticket almost certainly needs before `start`:**
> `security_relevant: true` — it adds an `audit_log` verb, changes a
> group-admin-gated command surface, and the §Notes blocker below is a real
> audit-suppression hazard.

## Context

M1-732 adds `groups.digest_mode` and makes the digest body render in the
group's mode. This ticket makes the mode user-settable: `/digest
brief|normal|full`, alongside the existing `/digest on|off`, with an audit row
per change.

## The audit verb, and why reuse is forbidden

`audit_log.action` is a closed enum in `infochat-core`, so "write an audit row
for the mode change" is not implementable without touching that module. That
is the one module carve-out this ticket needs:
`infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java`
plus its catalogue row in `docs/design/02-schema.md` — one added enum constant
and one added table row. Verified 2026-07-30: `audit_log.action` is `TEXT` with
no CHECK constraint (`V5__identity_audit.sql:256,272-275`), so
`DIGEST_MODE_SET` needs **no migration**.

Reusing `DIGEST_ENABLE` was considered and rejected on evidence, not taste.
`DigestScheduler.latestDigestEnableTime` derives the paused-through-window
carve-out from `WHERE action = 'DIGEST_ENABLE'` (`DigestScheduler.java:248`,
and `AuditAction.java:229-236` calls that convention load-bearing). A `/digest
brief` emitting `DIGEST_ENABLE` would move that boundary forward and silently
suppress `digest_slot_missed` rows for every earlier window — a silent,
spec-visible regression. `DIGEST_MODE_SET` is a new verb precisely so no
existing reader's `WHERE` clause changes meaning.

## Acceptance

*To author.* Core commitments the decompose carried forward:

- `/digest brief|normal|full` sets the mode; `/digest on|off` keeps its exact
  current meaning against `digest_enabled` and is NOT folded into the mode
  column. A test pins that `/digest off` then `/digest brief` leaves the group
  paused.
- Permissions, the DM rejection and the no-op-when-unchanged branch all match
  `/digest on|off` exactly, by reusing its existing checks
  (`DigestCommandHandler.java:99` is the no-op branch).
- Each mode change writes ONE `audit_log` row with
  `AuditAction.DIGEST_MODE_SET`, in the shape the handler already emits for
  `digest_enabled` (`DigestCommandHandler.java:109-120`) — audit-before-effect,
  same transaction as the UPDATE, `targetKind=GROUP`, `targetId`/`scopeId` =
  the group id — with `detailsJson` carrying the old and new mode. A no-op
  writes NO row.
- A test pins that a mode change writes `DIGEST_MODE_SET` and leaves the
  group's `DIGEST_ENABLE` row set untouched.
- `docs/design/02-schema.md` §audit-action catalogue gains the matching row
  (`| DIGEST_MODE_SET | /digest brief|normal|full | group |`).
- An unknown verb yields the localized usage error; a non-admin group member
  and a DM caller are both rejected.

## Out-of-scope

*To author.* At minimum: the render shape and the `digest_mode` column itself
(M1-732); delivery batching (M1-734); per-group slot hours, `groups.timezone`
and `/group-timezone` — still global per deployment, this ticket adds no
scheduling behaviour.

Any `infochat-core` or `infochat-collector` edit beyond the named carve-out
(`AuditAction.java` + its `docs/design/02-schema.md` catalogue row) is out.

## Notes

Adding a new bot-admin-adjacent command has historically tripped three
under-scoped couplings worth checking at author time: the IT naming guard, the
`LlmOutputSanitizer.CLOSED_LIST` parity check, and audit-before-effect ordering.

D43 bilateral keyset: any new `en.properties` key needs its `cs.properties`
twin or `BundleLoaderTest` fails.
