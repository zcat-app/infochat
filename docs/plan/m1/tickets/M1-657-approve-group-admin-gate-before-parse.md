---
id: M1-657
title: "Gate /approve-group: admin check before group-id parse"
status: done
created: 2026-07-18
last_updated: 2026-07-18
blocked_by:
  - M1-656
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandlerTest.java
  - docs/spec/commands.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    RejectGroupCommandHandler. It already has the correct ordering —
    lookupActor + isAdmin at RejectGroupCommandHandler.java:129-132 ("Admin
    gate has precedence" comment) BEFORE parseGroupId — and is the pattern
    this ticket copies. Do not touch it.
  - >-
    The error.group_not_found template and its {0} echo. After this ticket
    the key is genuinely bot-admin-only reachable, which is the tier
    M1-656's spec paragraph documents as deliberately left echoing. Removing
    the echo is not this ticket's job and would contradict that recorded
    decision.
  - >-
    The in-transaction SELECT FOR UPDATE admin re-check inside
    executeApprove (ApproveGroupCommandHandler.java:146-151, the
    M1-046-redteam TOCTOU closure). It stays exactly as is; this ticket
    ADDS a pre-parse gate in handle(), it does not move or replace the
    in-tx gate. RejectGroupCommandHandler carries the same two-gate shape.
  - >-
    Router-level or CommandPermissions-level bot-admin tiering. Bot-admin
    enforcement is per-handler in v1 (CommandPermissions is
    probation-scoped only); introducing a central tier gate is a design
    change far beyond this defect.
  - >-
    The M1-658 reflection guard. This ticket fixes the one mis-ordered
    handler; the guard that prevents recurrence is its own ticket.
acceptance:
  - >-
    ApproveGroupCommandHandlerTest.approveByNonAdminMalformedIdReturnsAdminOnlyWithoutEcho
    passes: a registered NON-admin sending `/approve-group /grant-admin`
    receives exactly error.admin_only; the reply text does not contain the
    substring "grant-admin"; no APPROVE_GROUP audit row is written. (Before
    this ticket the same call returns error.group_not_found with the token
    reflected — the r2 audit REPRO on M1-656, verified 2026-07-18.)
  - >-
    The admin gate in handle() precedes parseGroupId, mirroring
    RejectGroupCommandHandler.java:129-132 (plain lookupActor + isAdmin →
    error.admin_only). The existing in-tx FOR UPDATE re-check in
    executeApprove is retained unchanged.
  - >-
    Admin-caller behaviour is unchanged: every existing test method in
    ApproveGroupCommandHandlerTest passes without modification — in
    particular approveUnknownGroupIdReturnsGroupNotFound (admins still get
    the group_not_found reply, echo included, per the bot-admin-only tier
    decision) and approveByNonAdminReturnsAdminOnly (same reply as before,
    now produced by the pre-parse gate).
  - >-
    The stale comment at ApproveGroupCommandHandler.java:112-115 ("Step 1+2
    — actor resolution + admin gate. Fail fast before parsing") stops being
    false: after the diff the code under it actually resolves the actor and
    gates on isAdmin before parsing, or the comment is corrected to match
    what the code does — whichever leaves comment and code in agreement.
  - >-
    docs/spec/commands.md §Discovery: the parenthetical M1-656 added noting
    error.group_not_found is reachable below bot admin via /approve-group
    (tracked as M1-657) is REMOVED, and error.group_not_found is restored to
    the named bot-admin-only examples list — the claim this ticket makes
    true.
  - >-
    No pre-existing test is modified. The one new test method is the only
    test change.
  - mvn verify is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ApproveGroupCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/commands.md §Discovery
decision_refs:
  - D47
reviews:
  - round: 1
    date: 2026-07-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 63
      removed: 18
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-18
    verdict: CLEAN
    base: d07b2a95
    head: working tree on m1/M1-657 (r1)
    verdict_file: docs/plan/m1/redteam/M1-657-2026-07-18.md
    out_of_model_count: 0
    note: |
      Pre-commit audit of the gate-ordering fix. Confirms the is_admin
      gate precedes parseGroupId, closing the M1-656 r2 finding; the in-tx
      FOR UPDATE re-check is retained. No findings, no out-of-model items.
clarity_check:
  date: 2026-07-18
  verdict: WARN
  warnings:
    - >-
      COMPLEXITY-RISK-CALIBRATED: risk: low claimed on a ticket whose
      subject is a bot-admin authorization-ordering defect
      (security_relevant: true, from a redteam FINDINGS verdict).
      Accepted: risk raised to medium before start.
  blockers: []
---

# M1-657: Gate /approve-group: admin check before group-id parse

## Context

The M1-656 r2 red-team audit (`docs/plan/m1/redteam/M1-656-2026-07-18-r2.md`)
found an authorization-ordering defect in a handler M1-656 never touched:
`ApproveGroupCommandHandler.handle` interpolates `parseGroupIdRaw(rawText)`
into `error.group_not_found` and returns (`:124-128`) BEFORE the admin gate,
which lives inside `executeApprove` (`:146-151`) and is only reached when the
UUID parse SUCCEEDS. The only check preceding the reflection is a null-guard
(`:116-118`), despite the comment above it claiming "actor resolution + admin
gate. Fail fast before parsing". So `/approve-group /grant-admin` from any
registered, non-probation, NON-admin user returns a reply reflecting the
attacker-chosen token — into a group, on the deterministic output channel
`security.md` §LLM output sanitizer deliberately leaves unfiltered. The
sibling `RejectGroupCommandHandler` orders it correctly (`:129-132` gate,
"Admin gate has precedence", before `parseGroupId` at `:149`), proving this
is an oversight, not a design choice. The class's own javadoc (steps 1-3)
documents gate-before-parse; the code violates its own contract. Fixing the
ordering makes `error.group_not_found` genuinely bot-admin-only reachable,
which in turn makes the tier claim in `docs/spec/commands.md` §Discovery
(added by M1-656) true — this ticket removes the interim tracked-defect note
M1-656's spec text carries.

## Acceptance

- New test `approveByNonAdminMalformedIdReturnsAdminOnlyWithoutEcho`:
  non-admin + non-UUID argument (`/grant-admin`) → `error.admin_only`, no
  "grant-admin" substring in the reply, no audit row.
- Gate ordering mirrors `RejectGroupCommandHandler.java:129-132`; the in-tx
  FOR UPDATE re-check stays.
- All existing `ApproveGroupCommandHandlerTest` methods pass unmodified;
  admin-caller behaviour is byte-identical.
- The `:112-115` comment and the code agree after the diff.
- `docs/spec/commands.md` §Discovery: interim violation note removed,
  `error.group_not_found` restored to the bot-admin-only examples.
- `mvn verify` green.

## Out-of-scope

Reject handler (already correct — the model to copy), the
`error.group_not_found` echo itself (bot-admin-only tier keeps its echo per
M1-656's recorded decision), the in-tx TOCTOU gate (unchanged), any
router/permissions-level tiering, and the M1-658 guard. If the fix seems to
require touching any of those, escalate — the premise is that a ~10-line
pre-gate plus one test suffices.

## Notes

- Copy shape: `RejectGroupCommandHandler.lookupActor` (`:264-267`) is a
  4-line wrapper over `userRepository.findByAdapterAndContactId` mapping to
  the handler-local `UserRow`. `ApproveGroupCommandHandler` already injects
  `UserRepository` and defines `lookupActorForUpdate`; the plain variant is
  the only addition.
- Behavioural delta is confined to non-admin callers with a non-UUID (or
  missing) argument: `error.group_not_found` + echo → `error.admin_only`.
  Non-admin callers with a valid UUID already got `error.admin_only` (from
  the in-tx gate); they now get it from the pre-gate — same bytes, one
  fewer transaction.
- The r2 audit verified the other five bot-admin reflect sites
  (audit/quarantine/invite/recover-pool/reject) all gate before reflecting;
  approve-group is the only inversion. No sibling needs the same fix.
