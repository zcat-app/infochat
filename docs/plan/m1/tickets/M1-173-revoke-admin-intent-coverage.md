---
id: M1-173
title: "Revoke-admin intent-row coverage (M1-151 redteam findings)"
status: done
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
      files: 4
      added: 128
      removed: 48
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-06
    category: AUDIT-EVASION
    severity: low
    promise: |
      "Authorization evaluation order on every inbound message. … 7. Permission
      check against the matrix. … 8. Audit-log the intent. 9. Execute."
      (docs/spec/security.md §Authorization model) — the diff itself restates
      this as the operative rule: "per spec §Authorization model steps 7→8→9 the
      intent row covers every dispatch that passes the permission check
      regardless of the execution outcome, so target-side refusals … leave a
      surviving row" (RevokeAdminCommandHandler.java:62-69).
    gap: |
      The self-revoke refusal leg still writes no intent row.
      RevokeAdminCommandHandler.java:203-205 returns
      error.revoke_admin.cannot_revoke_self BEFORE the step-4 intent write at
      lines 226-231. A self-revoke is an admin-authorized dispatch (actor
      exists, is admin, not in probation — it passes the exact actor-side
      permission predicate the diff installs at lines 228-229) that is refused
      at an execution-semantics guard, structurally identical to the
      target-unknown and target-not-admin legs the diff just fixed (M1-151
      findings 1+2) — yet it leaves zero audit rows. The remediation closed two
      of the three refusal legs and left the third unaudited. (Secondary
      ordering wrinkle: because the guard runs before any permission check, a
      non-admin or probation actor sending /revoke-admin <self> also gets the
      self-revoke error instead of the step-7 permission refusal, deviating
      from the spec's 7-before-execution-checks ordering, though no sensitive
      state is disclosed since the target is the sender.)
    repro: |
      A bot admin (e.g., one whose session is compromised) sends /revoke-admin
      <own-contact-id>. Reply: error.revoke_admin.cannot_revoke_self. SELECT
      count(*) FROM audit_log WHERE request_id = … for that dispatch returns 0
      — no REVOKE_ADMIN_INTENT, no effect row. Under the diff's own stated
      rule, every permission-passing /revoke-admin dispatch must leave a
      surviving intent row regardless of execution outcome; this one is
      invisible to /audit and to forensic review.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-06
    category: AUDIT-EVASION
    severity: low
    promise: |
      "8. Audit-log the intent. 9. Execute." — step 8 precedes step 9
      unconditionally (docs/spec/security.md §Authorization model); the
      handler's own javadoc commits to "a successful revoke's intent + effect
      pair is correlated" (RevokeAdminCommandHandler.java:134-136), and the new
      test asserts exactly that correlation (RevokeAdminCommandHandlerTest.java,
      intent↔effect request_id assertion).
    gap: |
      The actor-side pre-check race admits a successful revoke with NO intent
      row. RevokeAdminCommandHandler.java:227-231 skips the intent write when
      the non-locking pre-check sees the actor as non-admin; if the actor is
      granted admin between the pre-check and the step-5a FOR UPDATE read
      (lines 261-263), the in-transaction guards pass and the revoke commits at
      line 308 with a REVOKE_ADMIN effect row but no REVOKE_ADMIN_INTENT row
      sharing its request_id. The diff's comment explicitly tolerates this ("or
      a missing one for an actor granted admin in the window", lines 79-81),
      but it directly violates the step-8-before-step-9 ordering for an
      executed admin mutation and breaks the intent↔effect correlation
      invariant the new test treats as load-bearing. The effect row survives,
      so the action is not fully invisible — this is a correlation/ordering
      gap, not total evasion, hence low.
    repro: |
      Actor A (non-admin) sends /revoke-admin <co-admin> while a colluding
      admin sends /grant-admin A timed into the pre-check→transaction window.
      A's pre-check fails (no intent row); the in-transaction admin gate then
      sees is_admin=true; the revoke executes and commits. Audit log now shows
      a REVOKE_ADMIN effect row whose request_id matches zero intent rows — the
      exact anomaly the new correlation assertion declares must not exist, and
      forensic tooling keying on "every effect row has a paired intent row"
      misclassifies a real revoke.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-06
    category: AUDIT-EVASION
    severity: low
    promise: |
      "The audit log records *intent* (command name, actor, scope, target)"
      (docs/spec/security.md §Secrets handling) — the target identity recorded
      must support later review; the diff's stated purpose is that "an admin
      probing for registered contacts is no longer invisible to the audit log"
      (RevokeAdminCommandHandlerTest.java, case (c) comment).
    gap: |
      For an unregistered target, RevokeAdminCommandHandler.java:384-385 writes
      target_id = UUID.randomUUID() with target_kind = "user" and no marker in
      details_json (line 442-444 carries only target_adapter) that the id is
      synthetic. The intent row is therefore indistinguishable from an intent
      row against a once-registered user whose row was later hard-deleted (the
      §User ban "pre-ban → unban deletes the row entirely" path makes
      dangling-but-real user ids a legitimate occurrence in audit_log). An
      auditor reconstructing "which probes targeted unregistered contacts"
      cannot do so from the row itself, weakening exactly the probe-visibility
      property the diff set out to deliver; only the contact_id (redacted in
      audit_log_view per §DB roles) plus absence of a matching users row
      distinguishes the cases, and that absence is ambiguous.
    repro: |
      Admin sends /revoke-admin unregistered-contact-X. The surviving
      REVOKE_ADMIN_INTENT row reads target_kind='user', target_id=<random
      UUID>. A reviewer querying /audit (through audit_log_view, full contact
      ids redacted) sees a plausible user-shaped target id and cannot tell this
      was a probe against a never-registered contact versus a revoke intent
      against a since-deleted user — the probe is logged but its salient
      property (target unregistered) is unrecoverable.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-06
    audit: 2
    category: AUDIT-EVASION
    severity: medium
    promise: |
      "Authorization evaluation order on every inbound message ... 7.
      **Permission check** against the matrix ... 8. Audit-log the intent.
      9. Execute." — the diff's own codified interpretation
      (RevokeAdminCommandHandler javadoc, lines 61–69) is that "the intent
      row covers every permission-passing dispatch regardless of the
      execution outcome, so self-revoke, target-unknown, target-not-admin
      and trigger-refused attempts all leave a surviving row."
    gap: |
      The diff brings /revoke-admin into compliance (self-revoke now writes
      a surviving REVOKE_ADMIN_INTENT row — RevokeAdminCommandHandler.java:
      231-256), and the diff touches BanCommandHandler — but leaves the
      identical gap open there: in BanCommandHandler.java:186-191 the
      self-ban guard returns error.ban.cannot_ban_self BEFORE the
      BAN_INTENT write at lines 213-223. An admin's self-ban attempt is a
      permission-passing dispatch (step-1 admin gate at lines 152-155
      passed) refused at execution semantics, yet it leaves zero audit rows
      of any action. The exact class of attempt the diff declares must
      "leave a surviving row" for /revoke-admin remains invisible for /ban.
    repro: |
      As a bot admin, send /ban <your-own-contact-id> in DM. Receive
      error.ban.cannot_ban_self. Query audit_log: no BAN_INTENT row, no row
      of any kind for the dispatch. Per spec step 7→8 ordering (and the
      diff's own M1-173-finding-1 rationale), the refused-but-authorized
      attempt should have left an intent row.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-06
    audit: 2
    category: AUDIT-EVASION
    severity: low
    promise: |
      "8. Audit-log the intent. 9. Execute." plus the correlated-rows shape
      the diff itself asserts for revoke-admin ("Both rows share one
      request id, so a successful revoke's intent + effect pair is
      correlated" — RevokeAdminCommandHandler.java:150-152, with a new test
      asserting exactly-one shared-request_id intent row per effect row).
    gap: |
      /ban has no intent↔effect correlation: the prompt leg mints
      intentRequestId (BanCommandHandler.java:215) and the confirm leg's
      executeBan mints a fresh, unrelated requestId
      (BanCommandHandler.java:249). A BAN effect row cannot be tied to the
      BAN_INTENT row that authorized it. With several pending/expired
      confirm prompts from one admin against overlapping targets, an
      auditor cannot reconstruct which intent (and which --reason) produced
      which effect — the reconstructive property the diff adds a regression
      test for on /revoke-admin (RevokeAdminCommandHandlerTest.java,
      countAuditByActionAndRequestId assertion) is structurally absent on
      /ban.
    repro: |
      Admin sends /ban target --reason "A" (intent row, request_id R1),
      lets it expire, sends /ban target --reason "B" (intent row R2), then
      confirm. The BAN effect row carries fresh R3 matching neither intent
      row; details_json.reason is the only weak join key and is
      optional/attacker(admin)-chosen.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-06
    audit: 2
    category: PERM-ESCAL
    severity: low
    promise: |
      "Authorization → execution. Permission checks run in deterministic
      Java" — and the diff's stated invariant that the in-tx FOR UPDATE
      gate "stays authoritative for EXECUTION (M1-046 PERM-ESCAL closure):
      an actor revoked between this read and the transaction is refused
      in-tx" (RevokeAdminCommandHandler.java:51-56).
    gap: |
      BanCommandHandler has only the non-locking MVCC admin gate at
      dispatch time (BanCommandHandler.java:152-155); executeBan's
      transaction (lines 251-321) contains no authoritative re-check of
      actor.is_admin (no SELECT ... FOR UPDATE on the actor row). The
      M1-046 TOCTOU window the diff explicitly keeps closed for
      /revoke-admin is open for /ban: an admin whose is_admin is
      concurrently revoked between the confirm-leg gate read and the ban
      transaction's commit still executes a ban (user-row mutation + invite
      revocation), with audit rows attributed to a no-longer-admin actor.
    repro: |
      Admin A primes /ban victim and holds the confirm. Admin B issues
      /revoke-admin A. A sends confirm so that A's step-1 MVCC read
      snapshots before B's commit; A's executeBan then commits the ban
      after A is no longer admin. Narrow race in practice, but the
      codebase's own sibling handler treats this window as a closed
      PERM-ESCAL finding.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-06-06
    audit: 2
    category: AUDIT-EVASION
    severity: low
    promise: |
      "The audit log records *intent* (command name, actor, scope, target)"
      — accurate target attribution; and the diff's new target_registered
      marker exists precisely so "a synthetic id is distinguishable from a
      real-but-since-deleted user id."
    gap: |
      executeBan's comment claims "Reads of the target row ... happen
      inside the transaction so their results are consistent with the
      writes that follow" (BanCommandHandler.java:243-246), but the target
      lookup at line 247 runs BEFORE dataSource.getConnection() /
      setAutoCommit(false) at lines 251-252 — outside the transaction. The
      diff wires this stale read into the durable BAN effect row
      (banDetailsJson(reason, targetOpt.isPresent()), line 272) and into
      the preban-INSERT-vs-UPDATE branch (line 284). A target who registers
      (invite consume) between the line-247 read and the mutation makes the
      tx write target_registered=false + a synthetic target_id for a
      now-registered user and take the insertPrebanRow branch against an
      existing (adapter, contact_id) — at best a unique-violation rollback
      surfacing as an internal IllegalStateException (ban silently not
      applied; attacker registered and unbanned until the admin notices and
      retries), at worst a duplicate users row if no unique constraint
      exists.
    repro: |
      Attacker holding a valid PENDING invite watches for the admin's /ban
      prompt window; on confirm, races the invite consume so registration
      commits between the out-of-tx lookup and insertPrebanRow. The ban
      transaction aborts with a non-IC001 SQLException; the admin receives
      an internal error, no ban lands, and the only committed trail is a
      BAN_INTENT row with target_registered=false that misdescribes the
      outcome.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-06-06
    verdict: FINDINGS
    base: main (0dcf13b)
    head: working tree of m1/M1-173-revoke-admin-intent-row-covera (pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-173-2026-06-06.md
    findings_count: 3
    out_of_model_count: 2
    note: |
      All three findings low/AUDIT-EVASION. Findings 1 (self-revoke leg
      row-less) and 2 (actor-side pre-check race) mapped to this ticket's
      out_of_scope entries; finding 3 (synthetic target_id unmarked) was
      new, inherited from the BAN_INTENT synthetic-UUID pattern.
      DISPOSITION (user decision, 2026-06-06): all three fixed in-branch
      as a follow-on commit before squash-merge — permission-first reorder
      (refusing pre-check, unconditional intent write, self-guard after;
      FOR UPDATE gate unchanged) plus a target_registered details_json
      marker on intent rows, extended for parity to BanCommandHandler
      (BAN_INTENT + BAN rows). See the verdict file's disposition block.
  - date: 2026-06-06
    verdict: FINDINGS
    base: main (0dcf13b)
    head: m1/M1-173-revoke-admin-intent-row-covera (c16d7fa — full branch)
    verdict_file: docs/plan/m1/redteam/M1-173-2026-06-06-2.md
    findings_count: 4
    out_of_model_count: 2
    note: |
      Retrigger after the c16d7fa fixes. All three audit-1 findings
      confirmed closed (none re-reported); accepted residuals held
      (reply oracle not re-raised; SET LOCAL concat returned only as
      OUT-OF-MODEL with the correct UUID-typed assessment). All four
      NEW findings (1 medium + 3 low) target BanCommandHandler —
      pre-existing behavior, not introduced by this branch, and
      explicitly excluded by this ticket's out_of_scope ("/ban intent
      parity is a separate ticket"): (1) self-ban refusal leg is
      row-less (the /ban analog of audit-1 finding 1); (2) no
      intent↔effect request_id correlation across the prompt/confirm
      legs; (3) no authoritative in-tx admin re-check in executeBan
      (M1-046-class TOCTOU); (4) out-of-tx target lookup feeds the BAN
      row's target_registered and the preban-vs-update branch,
      contradicting the adjacent comment. None block the M1-173 merge.
      DISPOSITION (user decision, 2026-06-06): all four folded into
      one remediation ticket, M1-175 (remediates: M1-173); the
      grant-side sibling gap from audit 1's disposition is M1-174.
      Both drafted on main (ac838d8, 924b514).
      Out-of-mandate: GrantAdminCommandHandler's symmetric row-less
      refusal legs — user will ticket separately.
clarity_check:
  date: 2026-06-06
  verdict: PASS
  warnings: []
  blockers: []
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
assertion. `revokeOneOfTwoAdminsSucceedsAndLeavesOtherAdapterUntouched`
may be extended (assertions added, none weakened) with the
intent↔effect `request_id`-correlation assertion that acceptance item 6
describes — user-authorized 2026-06-06, resolving the drafting gap
where item 6 named an assertion this paragraph omitted. No other
pre-existing test changes are authorized; in particular
`revokeLastAdminTriggerFiresAndRollsBack` must pass unmodified.

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
