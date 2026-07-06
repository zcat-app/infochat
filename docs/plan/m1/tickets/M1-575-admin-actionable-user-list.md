---
id: M1-575
title: "Admin visibility of actionable users (/pending): probation + awaiting-vouch, with usable contact ids"
status: pending
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PendingCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PendingUsersDao.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/PendingCommandHandlerTest.java
  - docs/spec/commands.md
  - docs/design/03-commands.md
  - docs/spec/decisions.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    A full `/list-users` directory. v1 deliberately ships none (commands.md
    §Permission model). This ticket adds only a NARROW, actionable subset
    (probation / awaiting-vouch), not a browseable roster of every user — that
    narrowness is the whole privacy argument and must not be widened here.
  - >-
    A user reporting / flagging mechanism. "Flagged for banning" is not a v1
    concept; there is no report pipeline. This ticket helps an admin RESOLVE a
    user they have already decided to act on, not surface community reports.
  - >-
    Group-scope member resolution / a `/whois` reply-to-resolve. Finding the id
    of an arbitrary group member from a message is a separate adapter-level
    concern (mention/reply payload plumbing), not this DM-only admin list.
  - >-
    The /audit render fix. That is M1-574; this ticket does not touch
    AuditCommandHandler.
  - "Changing probation timing/policy (D45). This only READS probation state."
acceptance:
  - >-
    A new bot-admin, DM-only command (proposed name `/pending`) lists users who
    are currently actionable by an admin: in slow-start probation and/or awaiting
    vouch. Each row shows the `contact_id` (the value `/vouch <contact>` /
    `/ban <contact>` accept), the adapter, the registration_state, and the
    probation-until / registered-at timestamp. Paginated with `--page N`
    (1-indexed), profile-driven page size, matching the existing list commands.
  - >-
    The listed `contact_id` is directly usable: an admin can copy it verbatim
    into `/vouch <contact>` or `/ban <contact>` and it resolves (same
    `(adapter, contact_id)` key those handlers use).
  - >-
    Permission: bot-admin only, DM-only scope (joins the closed DM-only
    privileged set alongside `/audit`, `/invite`, `/quarantine`). A non-admin
    caller gets the standard permission error; invocation in a group returns
    `error.command_dm_only`. Any decision-window/timestamp comparison used to
    compute "in probation" reads the injected Clock (per the injectable-time
    engineering rule), not an inline now().
  - >-
    A decision is recorded in docs/spec/decisions.md that scopes this against the
    deliberate no-`/list-users` choice: exposing the probation/awaiting subset to
    admins is justified because those users are the exact input to admin action
    commands, and the exposure is bounded (not a full roster). The rationale for
    keeping full enumeration out of v1 is restated, not overturned.
  - >-
    Catalogue hygiene: the new command is added to the machine-readable
    command-index marker region in docs/spec/commands.md AND to the permission
    matrix in docs/design/03-commands.md, so the M1-527 command-catalogue parity
    test stays green. New en+cs bundle keys are added (D43).
  - >-
    PendingCommandHandlerTest covers: an admin in DM sees probation users with
    their contact ids; a non-admin is refused; a group invocation returns the
    DM-only error; pagination works.
  - "`mvn verify` is green from the repo root (new tests pass; full suite passes)."
test_plan:
  adds:
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/PendingCommandHandlerTest.java — admin-DM list, non-admin refusal, group DM-only error, pagination."
  modifies:
    - "docs/spec/commands.md — command-index marker + catalogue; docs/design/03-commands.md — permission matrix; docs/spec/decisions.md — scoping decision; en/cs bundles."
  preserves:
    - "the no-/list-users posture for the full roster; all tests green on main; M1-527 parity test green."
spec_refs:
  - "docs/spec/commands.md §Permission model"
  - "docs/spec/security.md §Slow-start tier"
  - "docs/design/03-commands.md §3.2 Permission matrix"
decision_refs:
  - "D45 (slow-start probation)"
  - "D44 (invite-gated registration)"
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-575: Admin visibility of actionable users

## Context

Every `<contact>`-targeting admin command — `/vouch`, `/ban`, `/unban`,
`/promote`, `/demote`, `/grant-admin`, `/revoke-admin` — is only usable if the
admin can discover the target's `contact_id`. v1 deliberately ships no
`/list-users` (privacy), delegating discovery to `/audit`. But verified this
session: a real probation user (`contact_id=5`, `registration_state=invited`)
produced **zero** audit rows referencing their contact id, and `/audit` renders
the internal UUID for targets anyway (M1-574). So from the phone an admin has
**no reliable path** to the id of a user they want to vouch or ban — the whole
admin-targeting command family is effectively dead for a passive user.

M1-574 fixes the `/audit` render. This ticket closes the remaining gap: a
bounded, admin-only list of exactly the users an admin needs to act on
(probation / awaiting vouch), each with a usable contact id.

## Approach

- New DM-only, bot-admin `/pending` command backed by a small DAO query over
  `users` filtered to probation / awaiting-vouch state, ordered by
  registered-at, paginated.
- Render each row with the `contact_id` prominent (that is the actionable value)
  plus adapter, state, and the relevant timestamp.
- Record the scoping decision in decisions.md and wire the catalogue/index/matrix
  so the parity test (M1-527) stays green.

## Notes

- **This is the deliberate reversal of a narrow slice of the no-`/list-users`
  decision, and must be argued as such.** The privacy rationale for omitting a
  full roster stands; the justification here is that the probation/awaiting
  subset is precisely the admin-action input set and the exposure is bounded.
  The reviewer should treat an unbounded widening (listing all users, adding
  search) as scope drift.
- Adding a new command trips the M1-527 parity test unless the command-index
  marker in commands.md is updated in the same change — call it out to the
  implementer.
- Companion: M1-574 (audit render), M1-573 (`/help pending` / `/help vouch`
  examples once this lands).
