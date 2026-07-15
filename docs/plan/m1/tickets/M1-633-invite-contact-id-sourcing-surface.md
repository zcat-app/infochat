---
id: M1-633
title: "In-band contactId sourcing for --contact invites (D60)"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: [M1-632]
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/security.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
decomposed_from: M1-631
decision_refs:
  - D44
spec_refs:
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/security.md §Invite-code registration
spec_amend_for: docs/spec/commands.md §Admin (bot admin)
out_of_scope:
  - >-
    The bare-`/invite create` default-to-open change and the D60 decision
    record — those are M1-632 (this ticket blocks on it). Do not re-touch the
    no-flag / --open dispatch or re-edit decisions.md; D60 already exists when
    this ticket starts.
  - >-
    The --open confirm gate (M1-051), the per-adapter open cap, the global
    --contact cap, and the audit-before-effect transaction shape for
    create/revoke. This ticket ADDS a read-only sourcing surface; it does not
    change how invites are minted. No Flyway migration — invite_code_attempt
    already exists and audit_action is free-text (no enum-constrained column).
acceptance:
  - >-
    A new read-only in-band admin surface (a `/invite` subcommand) lists the
    connected-but-unregistered contacts an admin can bind a `--contact` invite
    to — sourced from the existing invite_code_attempt table (adapter,
    contact_id, attempted_at) — showing the FULL copy-pasteable contact_id that
    `/invite create --adapter <name> --contact <id>` accepts. The surface is
    bot-admin-only, DM-only, and scoped to the inbound adapter so every listed
    id resolves against the same (adapter, contact_id) key create matches on
    (D55 posture, matching /pending).
  - >-
    The read is audit-before-effect: a new AuditAction value records the
    privileged contactId disclosure (the same posture as AuditAction.PENDING_LIST
    for /pending), written before the ids are returned. audit_action is free-text
    so no migration is needed.
  - >-
    docs/spec/commands.md §Admin (bot admin) documents the new subcommand and
    reframes `/invite create --contact` as the advanced-but-now-usable path
    (contactId obtained via the new surface); docs/spec/security.md
    §Invite-code registration notes the deliberate contactId disclosure and its
    bot-admin-only + DM-only + inbound-adapter bound (weighed against the
    anti-theft binding benefit, ContactIds.redact elsewhere).
  - >-
    InviteCommandHandlerTest pins: the new surface returns full contact_ids for
    connected-but-unregistered contacts on the inbound adapter, writes the new
    audit action before disclosure, and is rejected for a non-admin caller and
    in group scope. mvn verify is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-633: In-band contactId sourcing for --contact invites (D60)

## Context

Decomposed from M1-631 (operator decision-gate resolved 2026-07-15). The
operator decided to **keep** `/invite create --contact` — whose theft-proof
binding (`InviteCodeConsumer.java:103` checks `expected_contact_id =
<redeemer's own id>`) is real in principle — but it is unusable in v1 because
an admin has no in-band way to obtain the target `contactId` at the moment it
matters:

- Before the person connects, the `contactId` does not exist (it is the SimpleX
  queue address / Signal ACI assigned when the connection forms).
- While connected-but-unregistered (the only useful window), nothing surfaces
  the id: `ProductionAdapterBeans` has "no unified admin-notification surface
  yet", and the intake bounce writes an `invite_code_attempt` row but no command
  exposes it.
- `/pending` shows full copy-pasteable ids but only for **already-registered**
  probation / awaiting-vouch users — too late.

This ticket closes that gap by exposing the `invite_code_attempt` contacts
(connected, attempted to redeem, not yet registered) to an admin, mirroring the
`/pending` D55 pattern. M1-632 lands D60 first; this ticket references it.

## Acceptance

**Surface.** A new read-only `/invite` subcommand lists connected-but-
unregistered contacts from the `invite_code_attempt` table with their FULL
`contact_id`s (what `--contact` accepts), bot-admin-only, DM-only, scoped to the
inbound adapter (D55 posture, exactly like `/pending`).

**Audit.** The privileged read is audit-before-effect: a new `AuditAction`
value records the disclosure before the ids are returned, the same posture as
`AuditAction.PENDING_LIST`. `audit_action` is free-text — no migration.

**Spec.** `docs/spec/commands.md` §Admin documents the subcommand and reframes
`/invite create --contact` as the advanced, now-usable path.
`docs/spec/security.md` §Invite-code registration records the deliberate
contactId disclosure and its bounds.

**Tests.** `InviteCommandHandlerTest` pins full-id disclosure for connected-
unregistered contacts on the inbound adapter, the audit-before-disclosure order,
and rejection for non-admin callers and group scope. `mvn verify` is green.

## Out-of-scope

- The bare-create default-to-open change and the D60 record (M1-632, this
  ticket's blocker). D60 already exists at start; do not re-edit `decisions.md`.
- Invite-minting machinery: the `--open` confirm gate (M1-051), the per-adapter
  open cap, the global `--contact` cap, and the create/revoke audit-before-
  effect transaction shape. This ticket adds a read-only surface only.
- No Flyway migration: `invite_code_attempt` exists and `audit_action` is
  free-text.
- `InviteCommandHandlerTest`: existing tests preserved; only additions.

## Notes

- Security posture: the sourcing surface exposes a connection identifier that is
  `ContactIds.redact`'d elsewhere (e.g. in `/invite list`'s CONTACT_BOUND
  rows). The design must weigh that disclosure against the anti-theft benefit
  and stay bot-admin-only + DM-only + inbound-adapter-scoped like `/pending`
  (D55). `security_relevant: true`; the redteam gate audits before commit.
- A new bot-admin PII-read command historically trips a small set of couplings
  (see the project note on new-admin-command couplings): the audit-before-effect
  `AuditAction` for the privileged read (covered above). Because this adds a
  SUBcommand to the existing `/invite` command — not a new top-level command —
  `LlmOutputSanitizer.CLOSED_LIST` (top-level command names) should be
  unaffected; confirm during implementation rather than assuming.
- Adjacent code: `PendingCommandHandler` (the D55 precedent — bounded admin
  roster with full ids, audit-before-effect via `AuditAction.PENDING_LIST`);
  `InviteCodeConsumer` (writes `invite_code_attempt`); `InviteCommandHandler`
  (the `/invite <sub>` dispatch this extends). The subcommand name is the
  implementer's call (e.g. `/invite pending-contacts`) — pin it in the spec
  amendment and the test.
- Decision family: D44 (per-adapter invite), D45 (probation), D55 (no general
  /list-users), D60 (created by M1-632).
