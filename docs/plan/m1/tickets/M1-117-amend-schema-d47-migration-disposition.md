---
id: M1-117
title: "Amend docs/spec/schema.md §Identity and access"
status: pending
created: 2026-05-29
last_updated: 2026-05-29
blocked_by: []
files_budget: 2
files_scope:
  - docs/spec/schema.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
spec_amend_for: docs/spec/schema.md:§Identity and access
spec_amend_parent: M1-116
out_of_scope:
  - infochat-core/src/main/resources/db/migration/V27__d47_remove_group_only.sql — the migration SQL is M1-116's territory; this ticket only corrects the spec text. M1-116 (deferred on this ticket) conforms the SQL once the spec is amended.
  - docs/spec/security.md — already correct (§Invite-code registration already guarantees no group-side registration bypass); this ticket only cross-references it, does not edit it.
  - docs/spec/decisions.md D47 entry — unchanged; it is silent on legacy-row disposition (which is fine), so no amendment is needed there.
  - The runtime registration-state transition set in schema.md §Identity and access (the "(none)→preban / →invited / →vouched / preban→(deleted)" list) — NOT modified; the D47 data migration is a one-time path documented in its own "Migration (D47)" note, not a v1 runtime transition.
  - Any infochat-provider/**, infochat-collector/**, or infochat-core Java/test code — doc-only spec edit, no code.
  - Other schema.md entities (Group approval_status, group_membership, audit_log, etc.) — untouched; only the "Migration (D47)" note within §Identity and access changes.
acceptance:
  - "The 'Migration (D47):' note in docs/spec/schema.md §Identity and access no longer states that group_only rows are transitioned to 'invited'. Verify: grep -nE \"transitioned to .?'invited'|preserves their access\" on the §Identity and access block returns ZERO matches."
  - "The amended 'Migration (D47):' note prescribes mapping legacy registration_state='group_only' rows to 'preban' with is_banned=TRUE (the canonical pre-ban shape, equivalent to a /ban-minted row), before the CHECK constraint is altered, in the same migration script."
  - "The amended note states the rationale: pre-D47 group_only users had group-scope access only and were explicitly denied DM (the removed step-4.7 invite-required gate), so transitioning them to 'invited' would GRANT DM access they never held and constitute a group-side registration bypass that security.md §Invite-code registration forbids. The note cross-references security.md §Invite-code registration for that guarantee."
  - "The amended note states the recovery path: an admin re-admits a contact via /unban (which deletes the pre-ban row, per the existing 'preban → (deleted)' transition in the same section) followed by a fresh invite — consistent with the existing preban definition and the preban→(deleted) /unban transition already documented in §Identity and access."
  - "The note preserves the unchanged facts: the data migration precedes the CHECK constraint alteration in the same script, and an audit_log entry records the bulk transition."
  - "No schema.md section other than the 'Migration (D47):' note within §Identity and access is modified. Verify: git diff docs/spec/schema.md shows changes confined to that note."
  - "Doc-only change: no .java, .sql, or test file is modified. Verify: git diff --name-only lists only docs/spec/schema.md (plus lifecycle byproducts — this ticket file, STATUS.md)."
  - "mvn -B clean verify from the repo root exits 0 (trivially — a spec-text edit does not affect the build; this confirms no accidental code touch)."
test_plan:
  modifies: []
  preserves:
    - all tests currently green on main (a docs/spec/ text edit affects no build or test)
spec_refs:
  - docs/spec/schema.md §Identity and access
  - docs/spec/security.md §Invite-code registration
  - docs/spec/security.md §User ban
decision_refs:
  - D44
  - D45
  - D47
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-117: Amend docs/spec/schema.md §Identity and access — D47 migration disposition

## Context

This amendment ticket was raised from M1-116's escalation
(`manual-verdict`, 2026-05-29). M1-116's code reviewer found that
M1-116's migration fix (map legacy `group_only` rows to `preban`)
contradicts a cited spec section that prescribes the opposite, and that
the spec is internally inconsistent:

- `security.md` §Invite-code registration: "there is no group-side
  registration bypass"; §What's intentionally NOT in v1: "the
  auto-registration path is permanently closed."
- `schema.md` §Identity and access "Migration (D47)": legacy
  `group_only` rows are "transitioned to `'invited'`… preserves their
  access."

The `schema.md` note is a **factual oversight**, not a deliberate
grandfathering policy. Ground truth (the step-4.7 DM-gate that M1-111
removed): pre-D47 `group_only` users had **group-scope access only and
were explicitly denied DM** (`error.invite.required`). `invited`
(schema.md §Identity and access) grants DM access. So
`group_only → invited` does not "preserve" access — it **expands** it,
granting DM access these un-invited users never held, which is exactly
the group-side bypass `security.md` forbids. D47's model has no
"group-only access" state to preserve (that is why `group_only` is
removed), so the only disposition consistent with both D47 and
`security.md` is to block these rows until an admin re-admits them via
an invite — i.e. the canonical `preban` shape.

This ticket lands ONLY the spec correction. M1-116 (deferred on this
ticket) conforms the V27 migration SQL once this is `done`.

## The amendment

Replace the "Migration (D47):" note in `schema.md` §Identity and access.

**Current (wrong):**

> **Migration (D47):** existing `users` rows with
> `registration_state = 'group_only'` are transitioned to `'invited'`
> before the CHECK constraint is altered. These users were
> auto-registered under the pre-D47 model; transitioning to `'invited'`
> preserves their access. The data migration precedes the CHECK
> constraint alteration in the same migration script. An `audit_log`
> entry records the bulk transition.

**Proposed (corrected) — final wording is the implementer's call within
the acceptance items, but in substance:**

> **Migration (D47):** existing `users` rows with
> `registration_state = 'group_only'` are transitioned to `'preban'`
> with `is_banned = TRUE` (the canonical pre-ban shape — equivalent to a
> `/ban`-minted row) before the CHECK constraint is altered, in the same
> migration script. These users were auto-registered under the pre-D47
> group-`@mention` path **without ever passing the DM invite gate**, and
> held group-scope access only (DM was explicitly denied). Transitioning
> them to `'invited'` would grant DM access they never held and would
> constitute a group-side registration bypass, which `security.md`
> §Invite-code registration forbids ("there is no group-side
> registration bypass"). The pre-ban disposition blocks them at intake
> in both DM and group scope; an admin re-admits a contact via `/unban`
> (which deletes the pre-ban row, per the `preban → (deleted)`
> transition above) followed by a fresh invite. The data migration
> precedes the CHECK constraint alteration in the same script. An
> `audit_log` entry records the bulk transition.

## Out-of-scope

See frontmatter. Doc-only; the migration SQL and its enforcement are
M1-116's.

## Notes

- **Pre-filled beyond the escalate skeleton.** The escalate→spec-amend
  procedure normally leaves `out_of_scope`/`acceptance` empty as a
  clarity-FAIL forcing function. I pre-filled them start-ready from the
  M1-116 review analysis; review and adjust before `/m1-tick start
  M1-117` if the boundaries are off.
- **No security.md edit.** `security.md` is already correct; this
  amendment only cross-references it. Reconciliation means making
  `schema.md` agree with `security.md`, not the reverse.
- **Merge ordering.** This is independent (`blocked_by: []`) and can
  land anytime. M1-116 unblocks once this is `done`. M1-111 (done,
  unmerged, still →invited in its V27) merges separately; M1-116 then
  corrects V27 to the preban disposition this amendment prescribes.
