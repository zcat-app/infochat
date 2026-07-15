---
id: M1-631
title: "Default /invite create to --open; fix/retire --contact"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 8
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The adapter-resolution default and the empty-backtick error copy — already
    delivered by M1-626 (merged). This ticket does not re-touch the
    --adapter inference logic.
  - >-
    The confirm-gate mechanics (M1-051), the per-adapter open cap, and the
    audit-before-effect transaction shape. Only the DEFAULT invite type
    (which flag a bare `/invite create` implies) and the disposition of
    --contact change.
acceptance:
  - >-
    OPEN DECISION (operator resolves at /m1-tick start, recorded as a new D-number
    in docs/spec/decisions.md): (1) does a bare `/invite create` with no
    --open/--contact default to --open (with --contact behind an explicit flag),
    and (2) is --contact kept (given a usable in-band contactId-sourcing surface)
    or retired/demoted. Both sub-decisions are documented before code is written.
  - >-
    docs/spec/commands.md §Admin (bot admin) is amended to match the decision —
    specifically the line currently reading "Providing neither returns a hint
    listing both flags and their trade-offs; no invite is created"
    (commands.md:1103) — and the en/cs bundle copy + InviteCommandHandler behavior
    match the amended spec.
  - >-
    InviteCommandHandlerTest pins the decided behavior: the bare-`/invite create`
    path (defaults-to-open or still-hints, per the decision) and, if --contact is
    kept, a test for whatever contactId-sourcing surface is added; if retired, a
    test that --contact is rejected/absent. mvn verify is green.
spec_refs:
  - docs/spec/commands.md §Admin (bot admin)
decision_refs:
  - D44
spec_amend_for: docs/spec/commands.md §Admin (bot admin)
---

# M1-631: Default /invite create to --open; fix/retire --contact

## Context

Surfaced in the M1-626 design discussion (2026-07-15, isolated live test). Two
coupled findings about the `/invite create` onboarding flow:

1. **`--open` is the only practically-usable path, yet it is not the default.**
   A bare `/invite create` (no `--open`/`--contact`) returns a "choose a flag"
   hint and creates nothing (`commands.md:1103`). The operator asked whether
   `--open` should be the default (with `--contact` behind an explicit flag),
   since onboarding a brand-new person — who has no `contactId` yet — always
   uses `--open`.

2. **`--contact` is practically unusable in v1.** `--contact <id>` binds an
   invite to a specific `(adapter, contactId)`, but an admin has **no in-band
   way to obtain that `contactId`** at the moment it would be useful:
   - Before the person connects, the `contactId` does not exist yet (it is the
     SimpleX queue address / Signal ACI assigned when the connection forms —
     `SimpleXMessageCodec.java:84`, a bot-internal identifier, not a shareable
     address the person can read off their own app).
   - While connected-but-unregistered (the only useful window), nothing surfaces
     the id to an admin: `ProductionAdapterBeans.java:64` — *"v1 has no unified
     admin-notification surface"*; the intake bounce records an internal
     `invite_code_attempt` row but no command or notification exposes the id.
   - `/pending` shows full copy-pasteable `contactId`s but only for
     **already-registered** probation/awaiting-vouch users
     (`PendingCommandHandler.java:31`) — too late; they are already in.

   So `--contact`'s theft-proof binding (`InviteCodeConsumer.java:103` checks
   `expected_contact_id = <redeemer's own id>`) is real in principle but
   unreachable in practice.

M1-626 fixed the narrower adapter-resolution bug and deliberately left these two
questions for a deliberate spec decision. This ticket carries that decision.

## Acceptance

**This ticket is DECISION-GATED.** The operator resolves two open decisions at
`/m1-tick start` and records them as a new decision (D-number) in
`docs/spec/decisions.md`:

1. Does a bare `/invite create` default to `--open` (with `--contact` behind an
   explicit flag), or keep the current "hint, create nothing" behavior?
2. Is `--contact` **kept** — which requires adding a usable in-band
   contactId-sourcing surface (e.g. surfacing connected-but-unregistered
   contacts, or an admin notification carrying the id) — or **retired/demoted**?

Then:

- `docs/spec/commands.md` §Admin (bot admin) is amended to match the decision
  (notably the `commands.md:1103` "providing neither" line), and the en/cs
  bundle copy + `InviteCommandHandler` behavior match the amended spec.
- `InviteCommandHandlerTest` pins the decided behavior (bare-create default; and
  either the new contactId surface or the removal of `--contact`). `mvn verify`
  is green.

Because the shape depends on the decision, the implementer should expect this may
**decompose** at start (e.g. a spec-amend child + a code child, or split the
default-flag change from the `--contact` disposition).

## Out-of-scope

- The adapter-resolution default and the empty-backtick error message — those
  shipped in M1-626 (merged, commit on main). Do not re-touch the `--adapter`
  inference block.
- Confirm-gate mechanics (M1-051), the per-adapter open cap, and the
  audit-before-effect transaction shape. Only the default invite type and the
  `--contact` disposition change.

## Notes

- Security posture: making `--open` (broader blast radius, confirm-gated per
  `commands.md:1099-1102`) the implicit default is a deliberate posture change —
  the confirm gate is the backstop. `security_relevant: true` set accordingly.
- If `--contact` is kept, the contactId-sourcing surface exposes a
  connection-identifier that is redacted elsewhere (`ContactIds.redact`); the
  design must weigh that disclosure against the anti-theft benefit, and stay
  bot-admin-only + DM-only like `/pending` (D55).
- Adjacent code: `InviteCommandHandler.handleCreate`
  (`infochat-provider/.../command/InviteCommandHandler.java`), the missing-flag
  branch (`error.invite.missing_flag`); `PendingCommandHandler` (the D55
  precedent for a bounded admin roster with full ids).
- Relevant spec: `docs/spec/commands.md` §Admin, `docs/spec/security.md`
  §Invite-code registration. Decision family: D44 (per-adapter invite),
  D45 (probation), D55 (no general /list-users).
