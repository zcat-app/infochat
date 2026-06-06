---
id: M1-175
title: "Ban intent-row parity and transaction hygiene (M1-173 audit-2 findings)"
status: pending
created: 2026-06-06
last_updated: 2026-06-06
blocked_by: [M1-173]
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/BanConfirm.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
remediates: M1-173
out_of_scope:
  - GrantAdminCommandHandler (the grant-side intent-row gap is M1-174)
  - RevokeAdminCommandHandler (the pattern source after M1-173 — read-only reference, no change)
  - AuditAction (no new verb — BAN_INTENT already exists; the four findings reorder, correlate, and relocate existing writes)
  - V5/V35 trigger functions and any db/migration change
  - ConfirmStateService internals (only the BanConfirm payload record gains a field; the remember/takeMatching contract is unchanged)
  - moving the prompt-leg BAN_INTENT write inside any transaction (the audit_log.actor_user_id FK FOR KEY SHARE vs FOR UPDATE deadlock documented on RevokeAdminCommandHandler applies once executeBan takes the actor row lock)
  - the non-admin and parse-failure short-circuits (handle steps 1-2) — they return before/at the permission check, so spec step 8 is never reached and they stay row-less
  - any change to user-visible replies or bundle keys
acceptance:
  - "BanCommandHandlerTest.banSelfWritesIntentRowWithoutPrompt passes: an admin's /ban <own-contact-id> still replies error.ban.cannot_ban_self, stores no pending confirm, mutates no users row, and exactly one BAN_INTENT audit row survives (zero BAN effect rows) with target_registered=true — the first-call intent write moves BEFORE the self-ban guard, closing M1-173 audit-2 finding 1 (the /ban analog of the self-revoke leg fixed on /revoke-admin); per docs/spec/security.md §Authorization model the row coverage matches the spec ordering verbatim: '7. **Permission check** against the matrix.' then '8. Audit-log the intent.' then '9. Execute.' — supersedes banSelfReturnsCannotBanSelf's no-DB-write pin (authorized modification, see §Out-of-scope)"
  - "BanConfirm carries the prompt-leg intent request_id, and executeBan reuses it instead of minting a fresh one: BanCommandHandlerTest.banConfirmWithinWindowExecutesBanTransaction passes extended with the assertion that the committed BAN effect row shares its request_id with exactly one BAN_INTENT row (intent↔effect correlation, M1-173 audit-2 finding 2)"
  - "BanCommandHandlerTest.banAndInviteRevokeAuditRowsShareRequestId passes extended: the shared request_id of the BAN and INVITE_REVOKE rows also matches the BAN_INTENT row written on the prompt leg"
  - "executeBan opens its transaction with the authoritative actor admin gate — SELECT ... FOR UPDATE on the actor row via UserRepository.findByAdapterAndContactIdForUpdate (the GrantAdminCommandHandler step-3a / RevokeAdminCommandHandler step-6a shape) — and refuses with rollback and error.admin_only when the locked read shows is_admin=false, closing the M1-046-class TOCTOU of M1-173 audit-2 finding 3; BanCommandHandlerTest.banConfirmByDemotedAdminRefusedWithoutMutation passes: prompt as admin, flip the actor's is_admin to false via SQL, send confirm — reply is error.admin_only, no users mutation, no BAN/INVITE_REVOKE effect rows, the prompt-leg BAN_INTENT row stands"
  - "The target lookup, targetUserId resolution, and target_registered determination that feed the BAN effect row and the preban-INSERT-vs-UPDATE branch run on the transaction connection inside executeBan, after the actor gate — no out-of-transaction read remains, and the 'Reads of the target row ... happen inside the transaction' comment becomes accurate (M1-173 audit-2 finding 4)"
  - "BanCommandHandlerTest.banFirstCallReturnsPromptAndWritesIntentAuditRowOnly, banUnknownContactMintsPreban, banKnownUserSetsIsBannedTrue, banWithPendingContactBoundInviteRevokesItInSameTransaction, and banOfOnlyAdminSurfacesLastAdminError still pass (the trigger-refused attempt's prompt-leg BAN_INTENT row continues to survive the rollback)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/BanCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-175: Ban intent-row parity and transaction hygiene (M1-173 audit-2 findings)

## Context

Remediates all four findings of M1-173's second redteam audit
(`docs/plan/m1/redteam/M1-173-2026-06-06-2.md`; `redteam_findings:`
entries with `audit: 2` on M1-173). All four are pre-existing
`BanCommandHandler` behavior from M1-044c/M1-051 — the M1-173 branch
only changed `banDetailsJson`'s signature there — surfaced because the
file entered an audited diff for the first time and the adversary
measured `/ban` against the promises the post-M1-173
`RevokeAdminCommandHandler` now documents:

1. **Finding 1 (medium, AUDIT-EVASION) — self-ban leg row-less.** The
   self-ban guard (`BanCommandHandler.java:189-191`) returns before
   the prompt-leg `BAN_INTENT` write (`:216-223`). An admin's self-ban
   is a permission-passing dispatch (the step-1 admin gate at
   `:152-155` passed) refused at execution semantics with zero audit
   rows — the exact class M1-173 closed for `/revoke-admin`.
2. **Finding 2 (low, AUDIT-EVASION) — no intent↔effect correlation.**
   The prompt leg mints `intentRequestId` (`:215`); `executeBan` mints
   a fresh, unrelated `requestId` (`:249`). A `BAN` effect row cannot
   be tied to the `BAN_INTENT` row that authorized it.
3. **Finding 3 (low, PERM-ESCAL) — no authoritative in-tx actor
   gate.** `executeBan`'s transaction (`:251-321`) never re-checks
   `actor.is_admin` under lock; the M1-046 TOCTOU window closed for
   `/revoke-admin` and `/grant-admin` is open for `/ban`.
4. **Finding 4 (low, AUDIT-EVASION) — out-of-transaction target
   read.** The target lookup at `:247` runs before the transaction
   opens at `:251`, contradicting the `:243-246` comment; the stale
   `targetOpt` feeds the durable `BAN` row's `target_registered`
   (`:272`) and the preban-vs-update branch (`:284`). A target who
   registers in the window produces a unique-violation rollback
   surfacing as an internal error — ban silently not applied.

## Remediation shape

One coherent change set in `executeBan` + the first-call path:

- **First-call path:** move the `BAN_INTENT` write (with its existing
  `target_registered` lookup) BEFORE the self-ban guard, mirroring the
  M1-173 ordering on `/revoke-admin` (permission gate → intent →
  execution-semantics guards). The step-1 admin gate already precedes
  everything, so refusal-before-intent for unauthorized senders is
  preserved as-is.
- **`BanConfirm`:** gains the prompt-leg `intentRequestId` field;
  `executeBan` consumes it as the transaction's `requestId` so the
  `BAN` + `INVITE_REVOKE` rows correlate with the `BAN_INTENT` row.
- **`executeBan`:** open with `SELECT ... FOR UPDATE` on the actor row
  (`UserRepository.findByAdapterAndContactIdForUpdate`, the M1-046
  shape already used by grant and revoke); refuse + rollback with
  `error.admin_only` if `is_admin=false` under the lock. Then move the
  target lookup / `targetUserId` / `target_registered` resolution onto
  the transaction connection. Lock-order note: the `BAN` audit
  INSERT's FK FOR KEY SHARE on the actor row is taken by the SAME
  transaction that holds the FOR UPDATE lock — self-compatible, no
  deadlock; the prompt-leg `BAN_INTENT` write happens on its own
  auto-commit connection before any lock exists, so the documented
  FK-vs-FOR-UPDATE deadlock cannot occur.

## Acceptance

See frontmatter. In prose: (1) admin self-ban leaves exactly one
surviving `BAN_INTENT` row with unchanged reply and no pending
confirm; (2)+(3) intent↔effect `request_id` correlation holds across
the prompt/confirm legs, including the `INVITE_REVOKE` rows; (4) a
demoted admin's confirm is refused without mutation while the
prompt-leg intent row stands; (5) all target reads feeding durable
state run in-transaction; (6) the named pre-existing tests still pass;
(7) full suite green.

## Out-of-scope

See frontmatter. Authorized pre-existing-test modifications:
`banSelfReturnsCannotBanSelf` pins the no-DB-write expectation finding
1 deliberately reverses — replaced by
`banSelfWritesIntentRowWithoutPrompt` (same reply assertion, new
intent-row + no-pending-confirm expectations).
`banConfirmWithinWindowExecutesBanTransaction` and
`banAndInviteRevokeAuditRowsShareRequestId` are extended with the
correlation assertions. No other pre-existing test changes are
authorized; in particular `banOfOnlyAdminSurfacesLastAdminError` and
`banByNonAdminReturnsAdminOnly` must pass unmodified.

## Notes

- Source: `docs/plan/m1/redteam/M1-173-2026-06-06-2.md` (verbatim
  PROMISE/GAP/REPRO); M1-173 frontmatter `redteam_findings:` entries
  with `audit: 2`.
- The demoted-admin confirm test exercises the refusal deterministically
  (the demotion commits before the confirm dispatch); the
  sub-millisecond race variant — demotion landing between the
  dispatch-time MVCC gate and the in-tx locked read — is not
  deterministically testable, which is exactly why the locked in-tx
  read must be the authoritative gate (the same argument as M1-046 on
  revoke/grant). The test pins the behavior contract; the FOR UPDATE
  placement closes the race.
- Finding 2's fix makes the intent row the single `request_id` mint
  for a prompt→confirm pair: one `BAN_INTENT` row per prompt, and the
  confirm that consumes that prompt's pending args inherits its id —
  expired or abandoned prompts leave uncorrelated intent rows, which
  is the correct outcome signal (intent without effect).
- `/ban confirm` re-enters `handle`, so the dispatch-time admin gate
  also re-runs at confirm time; the in-tx gate is the backstop, not
  the primary path — replies are unchanged on every existing leg.
