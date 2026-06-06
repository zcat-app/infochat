---
id: M1-173
title: "Revoke-admin intent-row coverage (M1-151 redteam findings)"
status: pending
created: 2026-06-06
last_updated: 2026-06-06
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
remediates: M1-151
out_of_scope:
  - BanCommandHandler and the BAN_INTENT pattern (any parity change to /ban intent coverage is a separate ticket)
  - setting the infochat.actor_id GUC on the intent-row auto-commit connection (M1-151 audit out-of-model advisory; it equally affects the pre-existing BAN_INTENT path and lands as its own advisory ticket if pursued)
  - UrlProbe / SsrfGuardedHttpClient and the BLOCKED_SSRF reply-class oracle (M1-151 audit out-of-model advisory, accepted residual)
  - V5/V35 last-admin trigger functions and any db/migration change
  - weakening, moving, or bypassing the in-transaction SELECT ... FOR UPDATE admin gate (M1-046 PERM-ESCAL closure) — it stays the authoritative authorization check
  - moving the intent write inside the revoke transaction (forbidden by the documented deadlock: the audit_log.actor_user_id FK takes FOR KEY SHARE on the actor row, which deadlocks application-side against the transaction's FOR UPDATE admin gate)
  - the self-revoke and parse-failure short-circuits (steps 2-3 of handle) — they return before the permission check, so spec step 8 is never reached and they stay row-less
  - any change to user-visible replies or bundle keys
acceptance:
  - "RevokeAdminCommandHandlerTest.revokeUnknownContactWritesIntentRow passes: an admin caller's /revoke-admin against an unregistered contact still replies error.contact_not_registered, and exactly one REVOKE_ADMIN_INTENT audit row survives (zero REVOKE_ADMIN effect rows) — closing M1-151 redteam finding 1's target-unknown leg"
  - "RevokeAdminCommandHandlerTest.revokeTargetNotAdminWritesIntentRow passes: an admin caller's /revoke-admin against a registered non-admin contact still replies error.revoke_admin.not_admin, and exactly one REVOKE_ADMIN_INTENT audit row survives (zero REVOKE_ADMIN effect rows) — closing finding 1's target-not-admin leg (supersedes revokeTargetNotAdminReturnsNotAdminNoAudit; see §Out-of-scope for the authorized modification)"
  - "The intent-write gate in RevokeAdminCommandHandler.handle reads ONLY actor-side state (actor row present, is_admin, probation) — target state no longer influences whether the intent row is written, so the pre-check/in-transaction MVCC divergence window of M1-151 redteam finding 2 (intent row skipped when the target's is_admin flips between the non-locking pre-check and the FOR UPDATE read) is structurally eliminated; per docs/spec/security.md §Authorization model the row coverage matches the spec ordering verbatim: '7. **Permission check** against the matrix.' then '8. Audit-log the intent.' then '9. Execute.'"
  - "RevokeAdminCommandHandlerTest.revokeByNonAdminReturnsAdminOnly passes extended with the assertion that the non-admin caller's dispatch writes zero audit rows of any action (permission check fails at step 7, step 8 never reached)"
  - "RevokeAdminCommandHandlerTest.revokeLastAdminTriggerFiresAndRollsBack still passes unmodified: the trigger-refused attempt's REVOKE_ADMIN_INTENT row survives the rollback"
  - "RevokeAdminCommandHandlerTest.revokeOneOfTwoAdminsSucceedsAndLeavesOtherAdapterUntouched still passes: the committed REVOKE_ADMIN effect row shares its request_id with exactly one REVOKE_ADMIN_INTENT row"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java
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

# M1-173: Revoke-admin intent-row coverage (M1-151 redteam findings)

## Context

Remediates both low AUDIT-EVASION findings from M1-151's post-commit
redteam audit (`docs/plan/m1/redteam/M1-151-2026-06-06-2.md`). M1-151
introduced the `REVOKE_ADMIN_INTENT` row (separate auto-commit
connection, survives the last-admin trigger rollback) but gated it on
non-locking target-side pre-checks
(`RevokeAdminCommandHandler.java:214-218`:
`targetPre.isPresent() && targetPre.get().isAdmin`). Two residual gaps:

1. **Finding 1 — probe enumeration with zero audit trace.** A
   fully-authorized bot admin who runs `/revoke-admin` against unknown
   or non-admin contacts passes the permission check, reaches the
   execution attempt, receives a distinguishing reply (steps 5c/5d roll
   back), and leaves zero `audit_log` rows — letting an admin enumerate
   which contacts are registered and which hold the admin bit
   invisibly.
2. **Finding 2 — inverse MVCC race.** The pre-checks are plain MVCC
   reads (`lookupUser`) while authoritative state is read later under
   `FOR UPDATE`. If the target's `is_admin` flips between the two
   (concurrent `/grant-admin`), the intent row is skipped and a
   trigger-refused attempt leaves NO surviving record — the exact
   failure mode M1-151 set out to eliminate; an executed revoke in the
   same window commits an effect row with no paired intent row,
   breaking the `request_id` correlation the class javadoc promises.

Both close with one change: gate the intent write on **actor-side
permission only** (actor row present, `is_admin`, not in probation —
the spec step-7 permission matrix), never on target state. Spec
contract: `docs/spec/security.md` §Authorization model steps 7→8→9
("Permission check" → "Audit-log the intent" → "Execute") — the intent
row covers every dispatch that passes step 7, regardless of step 9's
outcome.

## Acceptance

See frontmatter. In prose: (1)+(2) the target-unknown and
target-not-admin refusal paths now leave a surviving
`REVOKE_ADMIN_INTENT` row while replies stay unchanged; (3) the intent
gate reads only actor-side state, structurally removing the
target-state race window; (4) a non-admin caller still writes no rows
(step 7 fails, step 8 unreached); (5) the last-admin
rollback-survival test passes unmodified; (6) intent↔effect
`request_id` correlation holds on the success path; (7) full suite
green.

## Out-of-scope

See frontmatter. Authorized pre-existing-test modification:
`RevokeAdminCommandHandlerTest.revokeTargetNotAdminReturnsNotAdminNoAudit`
pins the old no-audit expectation this ticket deliberately reverses —
it is replaced by `revokeTargetNotAdminWritesIntentRow` (same
reply/no-effect assertions, new intent-row expectation).
`revokeUnknownContactReturnsContactNotRegistered` may likewise be
extended or renamed to `revokeUnknownContactWritesIntentRow`, and
`revokeByNonAdminReturnsAdminOnly` extended with the zero-audit-rows
assertion. No other pre-existing test changes are authorized; in
particular `revokeLastAdminTriggerFiresAndRollsBack` must pass
unmodified.

## Notes

- Source: `docs/plan/m1/redteam/M1-151-2026-06-06-2.md` (verbatim
  PROMISE/GAP/REPRO); ticket frontmatter `redteam_findings:` entries
  dated 2026-06-06 on M1-151.
- Design pointer: the minimal diff drops the two `targetPre` conjuncts
  from the gate at `RevokeAdminCommandHandler.java:214-218` (the
  `targetPre` lookup itself can then go if nothing else reads it). The
  actor-side conjuncts stay: a permission-failing probe writes no row,
  which is spec-conformant (step 8 sits after step 7) and prevents
  unregistered/non-admin contacts from growing `audit_log`.
- The actor-side pre-check keeps its own benign race (actor granted
  admin between pre-check and transaction → refused once with
  `error.admin_only`-class semantics): the in-tx `FOR UPDATE` gate
  remains authoritative for authorization and replies, exactly as
  today's comment at `RevokeAdminCommandHandler.java:197-210` states.
  Re-deriving full race-freedom for the actor side would require
  moving the intent write into the transaction, which the documented
  FK-lock deadlock forbids — hence out of scope.
- Intent rows record *intent*, not outcome — no details-JSON change is
  needed for the refusal paths; the absent effect row is the outcome
  signal, mirroring the BAN_INTENT prompt-leg pattern.
