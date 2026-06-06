---
id: M1-174
title: "Grant-admin intent-row coverage (probe-visibility parity)"
status: done
created: 2026-06-06
last_updated: 2026-06-06
blocked_by: [M1-173]
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - BanCommandHandler, BanConfirm, and the BAN_INTENT pattern (the /ban parity gaps are M1-175)
  - RevokeAdminCommandHandler (the pattern source after M1-173 — read-only reference, no change)
  - V5/V35 trigger functions and any db/migration change (verified: V5 deliberately pins no SQL CHECK on audit_log.action — "the application-layer audit-write helper is the closure enforcer" — and REVOKE_ADMIN_INTENT landed enum-only in M1-151, zero migration files touched)
  - weakening, moving, or bypassing the in-transaction SELECT ... FOR UPDATE admin gate (M1-046 PERM-ESCAL closure) — it stays the authoritative authorization check for execution
  - moving the intent write inside the grant transaction (forbidden by the documented deadlock: the audit_log.actor_user_id FK takes FOR KEY SHARE on the actor row, which deadlocks application-side against the transaction's FOR UPDATE admin gate)
  - the DM-only, null-caller, and parse-failure short-circuits (handle steps 1-2) — they return before the permission check, so spec step 8 is never reached and they stay row-less
  - any change to user-visible replies or bundle keys
acceptance:
  - "GrantAdminCommandHandlerTest.grantUnknownContactWritesIntentRow passes: an admin caller's /grant-admin against an unregistered contact still replies error.contact_not_registered, and exactly one GRANT_ADMIN_INTENT audit row survives (zero GRANT_ADMIN effect rows); its details_json carries target_registered=false marking the synthetic target_id (jsonb-canonical spacing: '\"target_registered\": false') — supersedes grantUnknownContactReturnsContactNotRegistered's no-audit pin (authorized modification, see §Out-of-scope)"
  - "GrantAdminCommandHandlerTest.grantBannedTargetWritesIntentRow passes: an admin caller's /grant-admin against a banned contact still replies error.grant_admin.banned_target, and exactly one GRANT_ADMIN_INTENT audit row survives (zero GRANT_ADMIN effect rows) with target_registered=true — supersedes grantBannedTargetReturnsBannedTarget's no-audit pin"
  - "GrantAdminCommandHandlerTest.grantAlreadyAdminWritesIntentRow passes: an admin caller's /grant-admin against a registered admin still replies error.grant_admin.already_admin, and exactly one GRANT_ADMIN_INTENT audit row survives (zero GRANT_ADMIN effect rows) with target_registered=true — supersedes grantAlreadyAdminReturnsAlreadyAdminNoAudit's no-audit pin"
  - "The intent write in GrantAdminCommandHandler is gated ONLY by the refusing, non-locking actor-side pre-check (actor row present, is_admin, not in probation) and runs unconditionally once that pre-check passes, BEFORE the transaction opens and BEFORE every execution-semantics check — target state never gates the write; per docs/spec/security.md §Authorization model the row coverage matches the spec ordering verbatim: '7. **Permission check** against the matrix.' then '8. Audit-log the intent.' then '9. Execute.'"
  - "GrantAdminCommandHandlerTest.grantByNonAdminReturnsAdminOnly passes extended with the assertion that the non-admin caller's dispatch writes zero audit rows of any action (permission check fails at step 7, step 8 never reached)"
  - "GrantAdminCommandHandlerTest.grantHappyPathFlipsIsAdminAndWritesAudit passes extended with the assertion that the committed GRANT_ADMIN effect row shares its request_id with exactly one GRANT_ADMIN_INTENT row (intent↔effect correlation)"
  - "AuditAction gains GRANT_ADMIN_INTENT with a comment mirroring REVOKE_ADMIN_INTENT's (single-shot command, pre-transaction auto-commit row); no migration file is added or modified"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-06
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 310
      removed: 40
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-06
    verdict: CLEAN
    base: main (69b4cc6)
    head: working tree of branch m1/M1-174-grant-admin-intent-coverage (pre-commit, --in-progress)
    verdict_file: docs/plan/m1/redteam/M1-174-2026-06-06.md
    out_of_model_count: 1
    note: |
      Pre-commit audit after round-1 APPROVE. CLEAN: intent-row
      coverage complete on all three state-disclosing refusal legs,
      fail-closed (intent write throws before any probe reply or
      UPDATE), M1-046 FOR UPDATE gate unweakened, no
      effect-without-intent path, no new injection surface. One
      out-of-model advisory: audit_log growth now includes surviving
      GRANT_ADMIN_INTENT rows per refused probe — the ticket's
      intended trade-off, operator-awareness only, no action.
clarity_check:
  date: 2026-06-06
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-174: Grant-admin intent-row coverage (probe-visibility parity)

## Context

In-session observation recorded in the M1-173 audit-1 disposition
(`docs/plan/m1/redteam/M1-173-2026-06-06.md`): `GrantAdminCommandHandler`
has NO intent row at all — only the in-transaction `GRANT_ADMIN` effect
row pre-written at step 3f (`GrantAdminCommandHandler.java:230`). Every
refusal leg rolls back row-less:

- unknown contact → `error.contact_not_registered`
  (step 3c, `GrantAdminCommandHandler.java:208-211`) — registration probe;
- banned target → `error.grant_admin.banned_target`
  (step 3d, `:215-218`) — ban-state probe;
- already-admin no-op → `error.grant_admin.already_admin`
  (step 3e, `:221-224`) — admin-bit probe.

This is the same probe-enumeration AUDIT-EVASION class as M1-151
redteam finding 1, on the mirror command: a bot admin can enumerate
registration state, ban state, and admin-bit state via `/grant-admin`
with zero audit trace. `/grant-admin` was never inside any audited
diff, so no redteam pass ever saw it.

**Asymmetry vs `/revoke-admin`:** grant has no last-admin-trigger
rollback path (the V5 trigger guards revocation, not grants), so the
intent row's rationale here is purely probe visibility plus spec
§Authorization model 7→8→9 conformance — not rollback survival.

## Remediation pattern

Mirror `RevokeAdminCommandHandler` as it stands after M1-173
(implementation commit + redteam-fix commit; this ticket is
`blocked_by: M1-173` because the pattern source must be on main first):

1. **Refusing, non-locking actor pre-check** (spec step 7) before
   anything writes: resolve the actor by `(adapter, contact_id)` with
   a plain MVCC read; absent or non-admin → `error.admin_only`; in
   probation → `error.probation_blocked`. Refusal-before-intent keeps
   unauthorized senders from growing the append-only `audit_log`.
2. **ONE unconditional `GRANT_ADMIN_INTENT` write** (spec step 8) on a
   separate auto-commit connection (BAN_INTENT pattern), BEFORE the
   transaction opens and before every execution-semantics check. The
   row's `target_id` is the target's users id when registered, a
   synthetic UUID otherwise; `target_contact_id` carries the identity
   either way; `details_json` records `target_registered` so a
   synthetic id is distinguishable from a real-but-since-deleted user
   id. Pre-transaction placement is mandatory, not stylistic: the
   `audit_log.actor_user_id` FK takes FOR KEY SHARE on the actor row,
   which deadlocks application-side against the transaction's FOR
   UPDATE admin gate if written while that lock is held.
3. **Execution** (spec step 9): the existing transaction, unchanged in
   authority — the step-3a `SELECT ... FOR UPDATE` admin gate stays
   authoritative for execution (M1-046 PERM-ESCAL closure). An actor
   revoked between the pre-check and the transaction is refused in-tx;
   one granted admin in that window is refused at the pre-check once
   and succeeds on retry — so execution can never happen without the
   intent row. The shared `requestId` correlates the intent row with
   the `GRANT_ADMIN` effect row.

## Acceptance

See frontmatter. In prose: the three target-side refusal legs (unknown
contact, banned target, already-admin) each leave exactly one surviving
`GRANT_ADMIN_INTENT` row with unchanged replies; the intent gate reads
only actor-side state; a non-admin caller still writes zero rows;
intent↔effect `request_id` correlation holds on the success path; the
enum addition is migration-free; full suite green.

## Out-of-scope

See frontmatter. Authorized pre-existing-test modifications:
`grantUnknownContactReturnsContactNotRegistered`,
`grantBannedTargetReturnsBannedTarget`, and
`grantAlreadyAdminReturnsAlreadyAdminNoAudit` pin the old no-audit
expectations this ticket deliberately reverses — each is replaced by
its `*WritesIntentRow` counterpart (same reply/no-effect assertions,
new intent-row expectation). `grantByNonAdminReturnsAdminOnly` and
`grantHappyPathFlipsIsAdminAndWritesAudit` are extended (zero-rows
assertion; request_id correlation). No other pre-existing test changes
are authorized; in particular
`grantOnSameContactIdAcrossAdaptersOnlyTouchesInboundAdapter` must
pass unmodified.

## Notes

- Migration question VERIFIED before setting `migration_touch: false`:
  `V5__identity_audit.sql` documents that the `audit_log.action` verb
  set "is NOT pinned with a SQL CHECK ... because the verb catalogue
  is open-ended for v2 additions; the application-layer audit-write
  helper is the closure enforcer." `REVOKE_ADMIN_INTENT` (M1-151) was
  added to `AuditAction` with zero migration-file changes — the same
  applies to `GRANT_ADMIN_INTENT`.
- The pre-check's benign race mirrors M1-173's documented residual: an
  actor revoked between the pre-check and the transaction leaves a
  spurious intent row for an attempt the transaction then refuses.
  Intent rows record *intent*, not outcome — the absent effect row is
  the outcome signal.
- jsonb canonicalizes `details_json` on read-back (one space after
  each colon); test assertions match that form
  (`"target_registered": false`), per the M1-173 test precedent.
- `target_registered=true` legs (banned target, already-admin) and the
  `=false` leg (unknown contact) together cover both marker values.
