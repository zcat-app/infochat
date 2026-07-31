---
id: M1-733
title: "Add /digest brief|normal|full and the DIGEST_MODE_SET audit verb"
status: done
created: 2026-07-30
last_updated: 2026-07-31
blocked_by:
  - M1-732
files_budget: 10
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - "the render shape of the digest body and the groups.digest_mode column itself (M1-732)"
  - "delivery batching (M1-734)"
  - "per-group slot hours, groups.timezone and /group-timezone — still global per deployment; this ticket adds no scheduling behaviour"
  - "any infochat-core or infochat-collector edit beyond the TWO named carve-outs (AuditAction.java + its docs/design/02-schema.md catalogue row; the V68 digest_mode UPDATE-grant migration)"
acceptance:
  - "/digest brief|normal|full sets the mode; /digest on|off keeps its exact current meaning against digest_enabled and is NOT folded into the mode column. A test pins that /digest off then /digest brief leaves the group paused."
  - "Permissions, the DM rejection and the no-op-when-unchanged branch all match /digest on|off exactly, by reusing its existing checks (DigestCommandHandler.java:99 is the no-op branch)."
  - "Each mode change writes ONE audit_log row with AuditAction.DIGEST_MODE_SET, in the shape the handler already emits for digest_enabled (DigestCommandHandler.java:109-120) — audit-before-effect, same transaction as the UPDATE, targetKind=GROUP, targetId/scopeId = the group id — with detailsJson carrying the old and new mode. A no-op writes NO row."
  - "A test pins that a mode change writes DIGEST_MODE_SET and leaves the group's DIGEST_ENABLE row set untouched."
  - "docs/design/02-schema.md §audit-action catalogue gains the matching row (| DIGEST_MODE_SET | /digest brief|normal|full | group |)."
  - "An unknown verb yields the localized usage error; a non-admin group member and a DM caller are both rejected."
  - "docs/spec/commands.md §Conversation control /digest entry documents the brief|normal|full sub-verbs alongside on|off — its 'usage error naming the sub-verbs' sentence must stay true (all five sub-verbs)."
  - "A Flyway migration grants UPDATE (digest_mode) ON groups TO infochat_provider — V62 narrowed the provider role to column-level grants (timezone, digest_enabled, removed_at) and V67 added the column without extending them, so the mode UPDATE fails with 'permission denied for table groups' without the grant (discovered at implementation, 2026-07-31). The audit verb itself still needs no migration: audit_log.action is TEXT with no CHECK."
  - "docs/spec/security.md §DB roles' column-scoped UPDATE enumeration gains groups.digest_mode (attributed to V68 alongside V62) — the V68 grant otherwise makes the spec's 'and nothing else' literally false (redteam 2026-07-31, low PERM-ESCAL)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
decomposed_from: M1-722
reviews:
  - round: 1
    date: 2026-07-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 498
      removed: 78
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-31
    category: PERM-ESCAL
    severity: low
    promise: |
      docs/spec/security.md §DB roles — "per V62 the Provider holds SELECT on
      users, groups, group_membership and invite_code plus a column-scoped
      UPDATE (users.probation_until, users.save_count; groups.timezone,
      groups.digest_enabled, groups.removed_at; group_membership.removed_at)
      ... and nothing else". The spec presents this exact column enumeration
      as the least-privilege trust path bounding what a Provider SQL-injection
      foothold can reach.
    gap: |
      V68__group_digest_mode_update_grant.sql adds GRANT UPDATE (digest_mode)
      ON groups TO infochat_provider, widening the Provider role's writable
      column set beyond the spec-enumerated list, but the diff does not amend
      docs/spec/security.md §DB roles. The divergence is documentary, not a
      new privilege channel — digest_mode is a CHECK-constrained config column
      in the same non-privilege class as the already-granted digest_enabled.
    repro: |
      An auditor reasoning from security.md §DB roles concludes the Provider
      role cannot write any groups column beyond
      timezone/digest_enabled/removed_at; the live role can also write
      digest_mode. Any downstream control keyed to the stale enumeration
      silently misses the new writable column.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-07-31
    verdict: FINDINGS
    base: b6f13f968d886c0550d4f2e423c7e0b212fc865e
    head: working tree (uncommitted, branch m1/M1-733-digest-mode-command)
    verdict_file: docs/plan/m1/redteam/M1-733-2026-07-31.md
    findings_count: 1
    out_of_model_count: 0
    note: |
      One low PERM-ESCAL finding: the V68 digest_mode UPDATE grant widened
      the Provider role's writable column set without amending the exact
      enumeration in docs/spec/security.md §DB roles. Documentary divergence,
      negligible blast radius; fix is a spec-text amendment. Surfaced to the
      user with the escalate redteam-finding recommendation.
  - date: 2026-07-31
    verdict: CLEAN
    base: b6f13f968d886c0550d4f2e423c7e0b212fc865e
    head: working tree (uncommitted, branch m1/M1-733-digest-mode-command)
    verdict_file: docs/plan/m1/redteam/M1-733-2026-07-31-r2.md
    out_of_model_count: 0
    note: |
      Re-audit after the refine remediation (security.md §DB roles
      enumeration gained groups.digest_mode, attributed to V68). The r1
      finding is verified closed; no new findings on the remediated diff.
clarity_check:
  date: 2026-07-31
  verdict: PASS
  warnings:
    - "self-check: added one acceptance item — the commands.md /digest entry must document the new sub-verbs, else its 'usage error naming the two sub-verbs' sentence goes false (doc-truth)"
  blockers: []
escalation_reason:
---

# M1-733: the /digest mode verb

> **Skeleton from the M1-722 decompose (2026-07-30); frontmatter authored at
> `start` (bounded self-refine of the OUT-OF-SCOPE-PRESENT lint blocker,
> 2026-07-31).** `acceptance`/`out_of_scope` transcribed from the §Acceptance /
> §Out-of-scope drafts below; `security_relevant: true` per the decompose's
> sizing note (adds an `audit_log` verb on a group-admin-gated command surface;
> the §Notes blocker is a real audit-suppression hazard) and the M1-227
> precedent.

## Context

M1-732 adds `groups.digest_mode` and makes the digest body render in the
group's mode. This ticket makes the mode user-settable: `/digest
brief|normal|full`, alongside the existing `/digest on|off`, with an audit row
per change.

## The audit verb, and why reuse is forbidden

`audit_log.action` is a closed enum in `infochat-core`, so "write an audit row
for the mode change" is not implementable without touching that module. That
is one of the two module carve-outs this ticket needs:
`infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java`
plus its catalogue row in `docs/design/02-schema.md` — one added enum constant
and one added table row. Verified 2026-07-30: `audit_log.action` is `TEXT` with
no CHECK constraint (`V5__identity_audit.sql:256,272-275`), so
`DIGEST_MODE_SET` needs **no migration**.

The second carve-out was discovered at implementation (2026-07-31): the mode
UPDATE itself needs a migration. V62 narrowed `infochat_provider` to
column-level grants (`timezone, digest_enabled, removed_at`) and V67 added
`digest_mode` without extending them, so the runtime `UPDATE groups SET
digest_mode` fails with `permission denied for table groups`. V68 grants
`UPDATE (digest_mode)` — the same direct-column-grant posture
`digest_enabled` has (the closed value set is V67's CHECK constraint's job).

Reusing `DIGEST_ENABLE` was considered and rejected on evidence, not taste.
`DigestScheduler.latestDigestEnableTime` derives the paused-through-window
carve-out from `WHERE action = 'DIGEST_ENABLE'` (`DigestScheduler.java:248`,
and `AuditAction.java:229-236` calls that convention load-bearing). A `/digest
brief` emitting `DIGEST_ENABLE` would move that boundary forward and silently
suppress `digest_slot_missed` rows for every earlier window — a silent,
spec-visible regression. `DIGEST_MODE_SET` is a new verb precisely so no
existing reader's `WHERE` clause changes meaning.

## Acceptance

Core commitments the decompose carried forward (now in frontmatter):

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

The render shape and the `digest_mode` column itself
(M1-732); delivery batching (M1-734); per-group slot hours, `groups.timezone`
and `/group-timezone` — still global per deployment, this ticket adds no
scheduling behaviour.

Any `infochat-core` or `infochat-collector` edit beyond the two named
carve-outs (`AuditAction.java` + its `docs/design/02-schema.md` catalogue row;
the V68 `digest_mode` UPDATE-grant migration) is out.

## Notes

Adding a new bot-admin-adjacent command has historically tripped three
under-scoped couplings worth checking at author time: the IT naming guard, the
`LlmOutputSanitizer.CLOSED_LIST` parity check, and audit-before-effect ordering.

D43 bilateral keyset: any new `en.properties` key needs its `cs.properties`
twin or `BundleLoaderTest` fails.
