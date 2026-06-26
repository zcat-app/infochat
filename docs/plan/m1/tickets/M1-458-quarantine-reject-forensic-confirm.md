---
id: M1-458
title: Confirm-gate /quarantine reject on the forensic (BENIGN_CLOSED) path
status: pending
created: 2026-06-26
last_updated: 2026-06-26
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineRejectConfirm.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BundleKeys.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  - docs/spec/commands.md
  - docs/design/03-commands.md
  - ADMIN_GUIDE.md
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the PENDING (routine) reject path                # stays no-confirm, unchanged
  - /quarantine approve and /quarantine list          # untouched
  - the reject_quarantine / approve_quarantine stored procedures  # the execute + its QUARANTINE_REJECT audit row already live in-proc
  - any Flyway migration                              # audit_log.action is plain TEXT (no CHECK); target_kind='quarantine' already allowed by V5
acceptance:
  - QuarantineCommandHandlerTest.rejectBenignClosedFirstCallPromptsAndWritesIntentOnly passes
  - QuarantineCommandHandlerTest.rejectBenignClosedConfirmTransitionsToRejected passes
  - QuarantineCommandHandlerTest.rejectBenignClosedConfirmWithoutPendingReturnsNoPending passes
  - QuarantineCommandHandlerTest.rejectPendingStillTransitionsDirectlyNoConfirm passes
  - "First `/quarantine reject <id>` on a BENIGN_CLOSED row returns the confirm prompt, writes ONE QUARANTINE_REJECT_INTENT audit row, and leaves the row BENIGN_CLOSED (no reject_quarantine call)"
  - "`/quarantine reject <id> confirm` on a pending forensic intent executes reject_quarantine (row → REJECTED, the in-proc QUARANTINE_REJECT audit row written) and returns success"
  - "`/quarantine reject <id>` on a PENDING row transitions directly to REJECTED with NO confirm prompt (routine path unchanged)"
  - docs/spec/commands.md documents the forensic-path confirm requirement for /quarantine reject (spec becomes the source of truth, not just the design note)
  - ADMIN_GUIDE.md §"Commands that require confirmation" lists /quarantine reject (forensic / BENIGN_CLOSED path)
  - docs/design/03-commands.md §3.10 is verified consistent (it already states the requirement)
  - mvn verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  preserves:
    - all tests currently green on main except the named reject_benignClosedToRejected rewrite below
spec_refs:
  - docs/spec/commands.md §Admin (bot admin)
  - docs/spec/commands.md §Surface conventions
decision_refs: []
---

# M1-458: Confirm-gate /quarantine reject on the forensic (BENIGN_CLOSED) path

## Context

`docs/design/03-commands.md` §3.10 states that `/quarantine reject` of a
`BENIGN_CLOSED` row (the "forensic path" — an admin overriding the system's
own benign verdict to keep a post redacted permanently) **requires confirm**.
The shipped code does not: `QuarantineCommandHandler.handleReject` calls the
`reject_quarantine` stored procedure directly with no confirm gate, and the
implementing ticket (M1-081b, acceptance `reject_benignClosedToRejected`)
asserted a direct transition. The spec (`docs/spec/commands.md`) is silent.
So a documented safety prompt was never built.

We are reconciling **toward the design intent** (option b): the forensic
reject is effectively one-way — once a quarantine row is `REJECTED` there is
no bot command to undo it (`approve_quarantine` accepts only `PENDING` or
`BENIGN_CLOSED`) — and overriding an automated all-clear to re-hide a post is
exactly the kind of lasting, surprising admin action this project already
confirms (cf. reviving a soft-deleted source, M1-053). This ticket adds the
confirm gate, promotes the requirement into the spec, and updates the admin
guide. The routine path (rejecting a `PENDING` row) stays no-confirm — that is
the expected review outcome, not a surprise.

## Acceptance

- `handleReject` reads the quarantine row's `review_status` before acting:
  - `PENDING` → execute `reject_quarantine` directly, no confirm (unchanged).
    Test: `rejectPendingStillTransitionsDirectlyNoConfirm`.
  - `BENIGN_CLOSED`, first call (body does not end in ` confirm`) → write a
    `QUARANTINE_REJECT_INTENT` audit row (audit-on-intent — the first-call leg
    of the confirm pattern, per the M1-051 precedent) in a transaction,
    register a `QuarantineRejectConfirm`
    (package-private top-level record implementing `ConfirmStateService.PendingConfirm`,
    `commandName()` → `"quarantine-reject"`) via `confirmStateService.remember`,
    and return `REPLY_CONFIRM_PROMPT_QUARANTINE_REJECT` (interpolating the
    timeout). Do NOT call `reject_quarantine`. Test:
    `rejectBenignClosedFirstCallPromptsAndWritesIntentOnly`.
  - `BENIGN_CLOSED`, confirm call (body ends in ` confirm`) →
    `takeMatching(actorId, scope, "quarantine-reject")`; empty →
    `ERROR_CONFIRM_NO_PENDING`; present → execute `reject_quarantine` (the
    in-proc `QUARANTINE_REJECT` execute audit row is written by the procedure)
    and return success. Test: `rejectBenignClosedConfirmTransitionsToRejected`
    and `rejectBenignClosedConfirmWithoutPendingReturnsNoPending`.
- `AuditAction` gains `QUARANTINE_REJECT_INTENT` (mirrors the M1-051
  `BAN_INTENT` / `INVITE_*_INTENT` precedent; no migration — `audit_log.action`
  is unconstrained TEXT, `target_kind='quarantine'` already in the V5 CHECK).
- `BundleKeys` + `bundles/en.properties` + `bundles/cs.properties` gain
  `REPLY_CONFIRM_PROMPT_QUARANTINE_REJECT` (the `BundleLoaderTest` reflective
  alignment check must stay green across en/cs).
- `docs/spec/commands.md` is amended: `/quarantine reject` documents that the
  forensic (`BENIGN_CLOSED → REJECTED`) path requires `confirm`, and the
  command is added to the §Confirmation enumeration. The spec — not just the
  design note — is now the contract.
- `ADMIN_GUIDE.md` §"Commands that require confirmation" adds `/quarantine
  reject` (forensic / BENIGN_CLOSED path).
- `docs/design/03-commands.md` §3.10 already states the requirement — verify it
  reads consistently with the implemented behavior; tighten wording only if
  needed.
- `mvn verify` is green.

## Out-of-scope

The **PENDING routine reject** path is unchanged (no confirm). `/quarantine
approve` and `/quarantine list` are untouched. The `reject_quarantine` /
`approve_quarantine` stored procedures are not modified — the execute
transition and its `QUARANTINE_REJECT` audit row already live in-proc; this
ticket only adds the handler-side INTENT row and the confirm gate. No Flyway
migration.

**Pre-existing test modified (test-integrity disclosure):**
`QuarantineCommandHandlerTest.reject_benignClosedToRejected` (added by M1-081b)
currently asserts that a single `/quarantine reject` on a BENIGN_CLOSED row
transitions directly to REJECTED. That behavior is deliberately changing — the
single call now returns a confirm prompt. Rewrite/replace it with the two-call
scenarios named in §Acceptance (`rejectBenignClosedFirstCallPromptsAndWritesIntentOnly`
+ `rejectBenignClosedConfirmTransitionsToRejected`). This is an authorized,
ticket-scoped test edit, not a weakening — the new tests assert strictly more.

## Notes

- Confirm-flow precedent to mirror exactly: M1-051 `ConfirmStateService`
  (stateless two-call `… confirm` shape, in-memory pending, lazy timeout) and
  M1-053's `RemoveSourceConfirm` / `SourceEnableConfirm` package-private
  records + the `*_INTENT` audit-on-intent verbs.
- Why state-dependent (confirm only on `BENIGN_CLOSED`): rejecting a freshly
  flagged `PENDING` item is the routine, expected review decision; rejecting a
  system-cleared item is the override. The handler must therefore read the
  row's status first to decide which path to take. Terminal states
  (`APPROVED`/`REJECTED`) continue to surface `ERROR_QUARANTINE_INVALID_STATE`
  (today via the stored-proc error mapping; the pre-read may surface it earlier
  — implementer's choice, keep the message identical).
- `security_relevant: true` — this is an admin destructive-action gate plus a
  new audit verb in the quarantine (security-pipeline) surface; it should go
  through `/redteam`. The audit-on-intent row exists precisely so an
  un-confirmed forensic-reject attempt still leaves a trace (M1-051 rationale).
- Adjacent code: `QuarantineCommandHandler.handleReject:237-277`,
  `ClearCommandHandler:88-100` (simplest confirm-gate reference),
  `SourceEnableCommandHandler` (state-dependent confirm + INTENT reference).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-458-quarantine-reject-forensic-confirm.md
```
