---
id: M1-632
title: "Default bare /invite create to --open (D60)"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/InviteCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/InviteCommandHandlerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - docs/spec/commands.md
  - docs/spec/decisions.md
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
spec_amend_for: docs/spec/commands.md §Admin (bot admin)
out_of_scope:
  - >-
    The --contact disposition and the in-band contactId-sourcing surface —
    those are M1-633 (blocked on this ticket). This ticket keeps --contact
    working exactly as it is today; it only changes what a bare
    `/invite create` (no --open/--contact) does.
  - >-
    The adapter-resolution default and the empty-backtick error copy (M1-626,
    merged) — do not re-touch the --adapter inference block. The --open confirm
    gate (M1-051), the per-adapter open cap, and the audit-before-effect
    transaction shape are also unchanged: this ticket only redirects the
    no-flag branch into the existing --open path.
acceptance:
  - >-
    D60 is recorded in docs/spec/decisions.md capturing BOTH operator sub-decisions
    (2026-07-15): (1) a bare `/invite create` with no --open/--contact defaults to
    --open — the confirm gate and per-adapter open cap remain the backstop for the
    broader blast radius; (2) --contact is KEPT (not retired) and gains an in-band
    contactId-sourcing surface, delivered separately by M1-633.
  - >-
    docs/spec/commands.md §Admin (bot admin) is amended so the line currently at
    commands.md:1103 ("`--contact` and `--open` are mutually exclusive. Providing
    neither returns a hint listing both flags and their trade-offs; no invite is
    created.") instead states that providing neither defaults to --open (still
    confirm-gated); --contact and --open remain mutually exclusive.
  - >-
    InviteCommandHandler routes a bare `/invite create` (no --contact, no --open)
    through the same --open path as an explicit `--open`: adapter resolution
    (single-adapter inference / multi-adapter friendly error) and the confirm gate
    both fire exactly as they do for `--open`. The now-unreachable
    error.invite.missing_flag branch and its bundle keys (en + cs) and the
    ERROR_INVITE_MISSING_FLAG BundleKeys constant are removed as orphans this
    change creates.
  - >-
    InviteCommandHandlerTest pins the new behavior: a bare `/invite create` on a
    single-adapter deployment returns the --open confirm prompt (not the old
    missing-flag hint), and the bare + explicit-`--open` first-call replies match.
    An explicit `--contact <id>` still creates a CONTACT_BOUND invite with no
    confirm. mvn verify is green.
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

# M1-632: Default bare /invite create to --open (D60)

## Context

Decomposed from M1-631 (operator decision-gate resolved 2026-07-15, isolated
live test). `--open` is the only practically-usable `/invite create` path for
onboarding a brand-new person — who has no `contactId` yet — yet a bare
`/invite create` currently returns a two-flag trade-off hint and creates
nothing (`commands.md:1103`). The operator decided a bare `/invite create`
should default to `--open`, with the existing confirm gate and per-adapter
open cap as the backstop for the broader blast radius. This ticket carries
that half of the resolution (default-flag change + the D60 record); the
`--contact` usability half is M1-633, which blocks on this ticket landing D60.

## Acceptance

**Decision record.** D60 is added to `docs/spec/decisions.md` documenting both
sub-decisions (default-to-open; keep-and-fix `--contact`), since the operator
settled them together and M1-633 references D60.

**Spec.** `docs/spec/commands.md` §Admin (bot admin) is amended so the
"providing neither" line (`commands.md:1103`) reflects the default-to-open
behavior. `--contact` and `--open` stay mutually exclusive.

**Behavior.** A bare `/invite create` (no `--contact`, no `--open`) is treated
as `--open`: it runs the same adapter-resolution (single-adapter inference,
multi-adapter friendly error — M1-626) and the same M1-051 confirm gate as an
explicit `--open`. `--contact` alone is unchanged (immediate, confirm-free,
theft-proof binding). The `error.invite.missing_flag` copy (en + cs) and the
`ERROR_INVITE_MISSING_FLAG` `BundleKeys` constant become unreachable and are
removed as orphans this change creates (bilateral en/cs keyset per D43).

**Tests.** `InviteCommandHandlerTest` pins that a bare `/invite create` returns
the `--open` confirm prompt (not the missing-flag hint) and matches the
explicit-`--open` first-call reply; `--contact` still mints a CONTACT_BOUND
invite without a confirm. `mvn verify` is green.

## Out-of-scope

- The `--contact` disposition and the in-band contactId-sourcing surface are
  **M1-633** (blocked on this ticket). This ticket does not add, remove, or
  reframe `--contact` beyond leaving it working as-is.
- The `--adapter` inference block (M1-626, merged), the `--open` confirm-gate
  mechanics (M1-051), the per-adapter open cap, and the audit-before-effect
  transaction shape. The no-flag branch is redirected into the existing
  `--open` path; none of that machinery changes.
- `InviteCommandHandlerTest`: existing tests are preserved. Any test that
  asserted the old missing-flag hint for a bare create is updated to the new
  default-to-open expectation and named here as an intentional edit.

## Notes

- Security posture: making `--open` (broader blast radius) the implicit default
  is a deliberate change — the confirm gate (`commands.md:1099-1102`) and the
  per-adapter open cap are the backstops. `security_relevant: true` set
  accordingly; the redteam gate audits the branch before commit.
- Implementation shape: the no-flag case (`args.contact == null && !args.open`)
  currently returns `error.invite.missing_flag`. Redirect it into the `--open`
  path so adapter resolution + confirm gate run unchanged. Keep the
  mutually-exclusive check (`args.contact != null && args.open`).
- Removing a bundle key requires deleting the en AND cs entries plus the
  `BundleKeys` constant, or `BundleLoaderTest` fails the D43 bilateral-keyset
  check (see the project convention on bundle-key twins).
- Adjacent code: `InviteCommandHandler.handleCreate`
  (`infochat-provider/.../command/InviteCommandHandler.java`), the missing-flag
  branch at the `args.contact == null && !args.open` guard.
- Decision family: D44 (per-adapter invite). D60 is created here.
