---
id: M1-151
title: "Typed SSRF / error signals (UrlProbe + last-admin SQLSTATE)"
status: done
created: 2026-06-02
last_updated: 2026-06-06
revisions:
  - date: 2026-06-06
    reason: premise-fail rework (the redteam-refine's intent-row placement — separate connection AFTER the in-transaction guards — self-deadlocks via the audit_log.actor_user_id FK's FOR KEY SHARE against the admin gate's FOR UPDATE; reword to the true BAN pattern of intent BEFORE the locking transaction, gated by non-locking pre-checks)
    snapshot:
      status: escalated
      escalation_reason: premise-fail
      files_budget_at_snapshot: 11
      acceptance_item_at_snapshot: |
        RevokeAdminCommandHandler writes one REVOKE_ADMIN_INTENT audit row on
        a separate auto-commit connection (BAN_INTENT pattern) after the
        in-transaction guards pass (admin gate, probation, target lookup,
        target-is-admin) and BEFORE the is_admin=FALSE UPDATE, so a
        last-admin trigger rollback cannot erase the record of the refused
        attempt (spec §Authorization model step 8 'Audit-log the intent'
        precedes step 9 'Execute')
  - date: 2026-06-06
    reason: redteam-finding rework (AUDIT-EVASION low — refused last-admin /revoke-admin attempts leave zero audit rows because the only REVOKE_ADMIN row rolls back with the trigger; fold the BAN_INTENT separate-connection pattern into the revoke handler)
    snapshot:
      status: escalated
      escalation_reason: redteam-finding
      files_budget_at_snapshot: 8
      files_scope_at_snapshot:
        - infochat-provider/src/main/java/app/zcat/infochat/provider
        - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
        - infochat-provider/src/test/java/app/zcat/infochat/provider
        - infochat-core/src/main/resources/db/migration
        - infochat-core/src/test/java/app/zcat/infochat/core/schema
      migration_touch_at_snapshot: true
      risk_at_snapshot: medium
      security_relevant_at_snapshot: true
  - date: 2026-06-05
    reason: budget-breach rework (acceptance item 2 required RAISE … USING ERRCODE, but no migration uses USING ERRCODE — the V5 trigger raises bare P0001; the trigger-side change lives in infochat-core, outside files_scope, contradicting migration_touch false and the V5 out_of_scope entry)
    snapshot:
      status: escalated
      escalation_reason: budget-breach
      files_budget_at_snapshot: 6
      files_scope_at_snapshot:
        - infochat-provider/src/main/java/app/zcat/infochat/provider
        - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
        - infochat-provider/src/test/java/app/zcat/infochat/provider
      migration_touch_at_snapshot: false
      risk_at_snapshot: low
      security_relevant_at_snapshot: false
escalations:
  - date: 2026-06-05
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation scope conflict found during code survey.
      Acceptance item 2 requires "RAISE … USING ERRCODE + getSQLState()",
      but no migration uses USING ERRCODE: the V5 trigger raises bare
      (SQLSTATE P0001, shared by every plpgsql RAISE in V21/V25/V32 and
      the audit append-only trigger). Satisfying the item requires a new
      migration in infochat-core/src/main/resources/db/migration/ —
      outside files_scope, contradicting migration_touch: false and the
      out_of_scope entry "the V5 last-admin trigger definition".
  - date: 2026-06-06
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Redteam 2026-06-06 (verdict FINDINGS, 1 finding): AUDIT-EVASION, low.
      RevokeAdminCommandHandler.java:239-266 — the only REVOKE_ADMIN audit row
      is inserted inside the same transaction as the is_admin=FALSE UPDATE;
      when the last-admin trigger fires, the catch block rolls the audit row
      back and returns the friendly reply with no audit record of the refused
      attempt. No intent row on a separate connection. Pre-existing behavior;
      the diff modified only the detection key in these catch blocks.
  - date: 2026-06-06
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — mvn verify hang, not a reviewer verdict. The 2026-06-06 refine's
      intent-row acceptance item prescribes a placement that self-deadlocks:
      "separate auto-commit connection AFTER the in-transaction guards pass"
      — but audit_log.actor_user_id (V5 line 253: UUID REFERENCES users(id))
      makes the intent INSERT take FOR KEY SHARE on the actor row, which the
      in-tx admin gate holds FOR UPDATE (M1-046). conn 1 waits on conn 2 in
      application code, PostgreSQL sees no lock cycle, the suite hung 10+
      minutes in RevokeAdminCommandHandlerTest (Quarkus hang-detection dump:
      main thread blocked in insertIntentAudit → socket read). BAN_INTENT
      never hits this because ban writes its intent row BEFORE any
      transaction opens, with no locks held.
  - date: 2026-06-06
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      VERDICT: MANUAL. SCOPE-DRIFT-CHECK: FAIL — must-shrink violation,
      mechanical (12/576/81 vs 9/280/58 on all three dimensions); "the
      exception clause is unsatisfiable here by construction: round 1 was
      APPROVE with zero REWORK items, so there is no REWORK item to cite."
      All other checks PASS (test_integrity, out_of_scope, acceptance 8/8,
      spec-conformance; negative_space WARN informational — stale
      BundleKeys.java:471-473 comment for the follow-up advisory ticket).
      Reviewer: "the growth ... is not scope creep: it is exactly the work
      mandated by the ticket's own post-round-1 revisions ... Resolution
      options: (a) user override of the must-shrink FAIL via the
      escalation/override channel (the project's recorded precedent for
      redteam-refine-trips-must-shrink, e.g. M1-131) ... every other check
      passes on the merits."
blocked_by:
  - M1-144
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf
  - infochat-provider/src/test/java/app/zcat/infochat/provider
  - infochat-core/src/main/resources/db/migration
  - infochat-core/src/test/java/app/zcat/infochat/core/schema
  - infochat-core/src/main/java/app/zcat/infochat/core/audit
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - the broader SSRF bundle (M1-135) — this is only the typed-signal vs string-match angle
  - the V5 trigger's locking, counting, and message logic — V35 changes only the RAISE clauses to add USING ERRCODE; V5 itself is not edited in place
  - migrating test-side trigger-message assertions (MultiAdapterIsolationIT, MultiAdapterProductionIT, CannotBanSelfTriggerTest, LastAdminConcurrentRevocationTest) to SQLSTATE — those assert the trigger surface, not handler detection, and the message text is unchanged
  - BanCommandHandler's confirm-leg outcome record (the BAN row rolling back with a refused confirm-leg last-admin ban) — its prompt-leg BAN_INTENT row already survives on a separate committed connection, so the operator-visible audit gap the redteam finding names exists only in the revoke handler
  - confirm-gating /revoke-admin — the intent row closes the audit gap; whether the command should also prompt-then-confirm is a separate UX/spec question
acceptance:
  - "UrlProbe maps SSRF failure modes by typed SsrfPolicyException reason (subclass or enum), not by message.startsWith(...) string prefixes"
  - "A new migration V35 (infochat-core) CREATE OR REPLACEs trg_last_admin_protection_update and trg_last_admin_protection_delete so each RAISE EXCEPTION carries USING ERRCODE = 'IC001'; message text, locking, and counting logic are unchanged"
  - "BanCommandHandler / RevokeAdminCommandHandler detect the last-admin trigger by SQLSTATE 'IC001' via SQLException.getSQLState(), not by SQLException message substring"
  - "LastAdminTriggerTest additionally asserts getSQLState() returns 'IC001' (direct trigger-level proof against real PostgreSQL); the existing last-admin paths in RevokeAdminCommandHandlerTest (revokeLastAdminTriggerFiresAndRollsBack) and BanCommandHandlerTest remain green, proving handler-side SQLSTATE detection end-to-end against real PostgreSQL"
  - "AuditAction gains REVOKE_ADMIN_INTENT (no migration needed — audit_log.action is TEXT with no CHECK constraint; the enum is the application-layer closure per the AuditAction javadoc)"
  - "RevokeAdminCommandHandler writes one REVOKE_ADMIN_INTENT audit row on a separate auto-commit connection (BAN_INTENT pattern) BEFORE the locking transaction opens, gated by non-locking pre-checks mirroring the in-transaction guards (actor exists, is admin, not in probation; target exists, is admin) so guard-failing probes and no-op paths write no row; the in-transaction guards remain authoritative for authorization and replies (spec §Authorization model step 8 'Audit-log the intent' precedes step 9 'Execute'; in-transaction placement is forbidden — the audit_log.actor_user_id FK's FOR KEY SHARE deadlocks against the admin gate's FOR UPDATE)"
  - "revokeLastAdminTriggerFiresAndRollsBack additionally asserts that after the refused attempt exactly one REVOKE_ADMIN_INTENT row survives in audit_log and zero REVOKE_ADMIN rows exist (mirrors BanCommandHandlerTest's BAN_INTENT survival assertion)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core/schema/LastAdminTriggerTest.java — add the SQLSTATE 'IC001' assertion alongside the existing message assertion
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source/UrlProbeTest.java — only if the typed-reason mapping needs new cases
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java — REVOKE_ADMIN_INTENT survival assertion in revokeLastAdminTriggerFiresAndRollsBack
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Failure handling
  - docs/spec/security.md §SSRF and outbound connections
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: WARN
      acceptance: PASS
    diff_stats:
      files: 9
      added: 280
      removed: 58
  - round: 2
    date: 2026-06-06
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: WARN
      acceptance: PASS
    diff_stats:
      files: 12
      added: 576
      removed: 81
  - round: 2
    date: 2026-06-06
    verdict: OVERRIDE-APPROVE
    checks:
      # carried through from the overridden MANUAL verdict; they remain
      # as the reviewer reported them. The verdict alone carries the
      # override.
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: WARN
      acceptance: PASS
    diff_stats:
      files: 12
      added: 576
      removed: 81
    override_ref: 0
overrides:
  - date: 2026-06-06
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — Must-shrink violation, mechanical: round 2
      grew along ALL THREE dimensions vs round 1 (files 12 > 9, lines added
      576 > 280, lines removed 81 > 58). The exception in
      engineering-rules-verbatim.md §8 Round-N must-shrink requires the
      developer to cite a round-(N-1) REWORK item that authorized growth —
      but round 1's verdict was APPROVE with zero REWORK items, so no such
      citation can exist.
    user_justification: |
      Must-shrink misfire on an unmodeled case: the growth is not rework
      divergence but new scope mandated through two formal escalate→refine
      cycles after a round-1 APPROVE (redteam-finding AUDIT-EVASION fix +
      premise-fail deadlock correction), both snapshotted in revisions: and
      committed as refine commits on the branch. Shrinking would require
      deleting work acceptance items 5-7 demand (rules-vs-rules trade,
      forbidden). All substantive checks pass (test_integrity, out_of_scope,
      acceptance 8/8, spec-conformance); the reviewer itself recommended the
      override channel. Precedent: M1-131, M1-156
      (redteam-refine-trips-must-shrink → override). User confirmed after an
      adversarial verification of all five resolution options.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-06
    category: AUDIT-EVASION
    severity: low
    promise: |
      "Authorization evaluation order on every inbound message ... 8. Audit-log
      the intent. 9. Execute." — the audit trail of a privileged-command intent
      is supposed to precede (and therefore survive) execution.
    gap: |
      RevokeAdminCommandHandler.java:239-266 — the only REVOKE_ADMIN audit row
      is inserted inside the same transaction as the is_admin=FALSE UPDATE;
      when the last-admin trigger fires, the catch block rolls the audit row
      back and returns the friendly reply with no audit record of the refused
      attempt. No intent row on a separate connection. BanCommandHandler has
      the same rollback shape but its prompt-leg BAN_INTENT row (separate
      committed connection) survives, so the gap there is only the confirm-leg
      outcome record. Pre-existing behavior; the diff modified only the
      detection key in these catch blocks.
    repro: |
      A compromised bot-admin session repeatedly issues /revoke-admin
      <last-admin-contact> to probe whether the deployment can be locked out of
      admin. Each attempt is refused by the trigger with the friendly error —
      but audit_log contains zero rows for any attempt, so an operator
      reviewing /audit never learns the session was probing the last-admin
      invariant.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-06
    category: AUDIT-EVASION
    severity: low
    promise: |
      "Authorization evaluation order on every inbound message. ... 7.
      **Permission check** against the matrix. ... 8. Audit-log the intent. 9.
      Execute." (security.md §Authorization model) — the diff itself cites this
      step-8 promise as the rationale for the new REVOKE_ADMIN_INTENT row.
    gap: |
      The intent row is gated by non-locking pre-checks that deliberately
      exclude two refusal paths that occur AFTER the permission check passes:
      target-unknown (step 5c) and target-not-admin (step 5d).
      RevokeAdminCommandHandler.java:214-218 writes the intent only when
      targetPre.isPresent() && targetPre.get().isAdmin; the in-transaction
      5c/5d branches (~265-277) roll back with no audit insert. A
      fully-authorized bot admin probing /revoke-admin against unknown or
      non-admin contacts passes step 7, reaches step 9, receives a
      distinguishing reply, and leaves zero rows in audit_log — codified as
      intentional in the comment at RevokeAdminCommandHandler.java:205-208.
    repro: |
      As a bot admin on adapter A, issue /revoke-admin <c1>, <c2>, ... across
      many contact ids. The reply distinguishes "unknown contact" from "not an
      admin" from "last admin", letting the admin enumerate which contacts are
      registered and which hold the admin bit, with no REVOKE_ADMIN_INTENT or
      REVOKE_ADMIN row ever written. An operator later auditing admin behavior
      sees nothing for the probing session.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-06
    category: AUDIT-EVASION
    severity: low
    promise: |
      Same step-8 commitment ("Audit-log the intent" precedes "Execute"), plus
      the diff's own stated invariant that a trigger-refused /revoke-admin
      "still leaves an operator-visible audit record" (AuditAction.java:14-17;
      RevokeAdminCommandHandlerTest.java intent-row-survives-rollback
      assertion).
    gap: |
      The intent pre-checks are plain MVCC reads outside the transaction
      (lookupUser, RevokeAdminCommandHandler.java:382-385) while authoritative
      state is read later under FOR UPDATE inside executeRevoke. The comment at
      209-212 acknowledges races only in the spurious-row direction. Inverse
      race: target's is_admin FALSE at pre-check but TRUE by step 5c/5f
      (concurrent /grant-admin) → intent row skipped, transaction proceeds, a
      trg_last_admin_protection_update refusal (IC001) rolls back the in-tx
      REVOKE_ADMIN row — refused attempt leaves NO surviving audit record,
      exactly the failure mode this diff exists to eliminate. An executed
      revoke in that window also commits a REVOKE_ADMIN effect row with no
      paired REVOKE_ADMIN_INTENT, breaking the intent+effect request_id
      correlation the class javadoc promises (245-247).
    repro: |
      Admin A issues /revoke-admin C while C is not yet admin; concurrently
      admin B issues /grant-admin C and admin-state elsewhere shifts so C
      becomes the revoke target whose removal would trip the last-admin guard.
      A's pre-check sees C.isAdmin=false → no intent row; A's transaction then
      sees C.isAdmin=true, proceeds, trigger raises IC001, everything rolls
      back. The audit log carries no trace of A's refused revocation attempt.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-06-06
    verdict: FINDINGS
    base: 067d43b
    head: m1/M1-151-typed-error-signals (working tree, pre-commit --in-progress audit)
    verdict_file: docs/plan/m1/redteam/M1-151-2026-06-06.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Single low AUDIT-EVASION finding: the REVOKE_ADMIN audit row rolls back
      with the refused last-admin attempt (pre-existing transaction shape; this
      diff changed only the detection key, message substring → SQLSTATE IC001).
      Fix requires the BAN_INTENT separate-connection pattern in the revoke
      handler — outside M1-151 scope/budget. Two out-of-model advisories:
      stale BundleKeys.java:471-473 comment (doc rot, joins the reviewer's
      negative-space WARN for a follow-up advisory ticket) and missing
      search_path pin on trigger functions (inside DB trust boundary, parity
      note only).
  - date: 2026-06-06
    verdict: FINDINGS
    base: 96b766c^ (519a796)
    head: 96b766c
    verdict_file: docs/plan/m1/redteam/M1-151-2026-06-06-2.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Post-commit re-run on the committed range, which includes the in-branch
      REVOKE_ADMIN_INTENT fix for the morning audit's finding. The adversary
      confirms the fix closes the last-admin-rollback audit gap and that V35's
      trigger bodies are byte-equivalent to V24/V5 apart from the added
      ERRCODE. Two residual low AUDIT-EVASION findings on the intent-row
      design: (1) target-unknown / target-not-admin refusals still write no
      row (contact/admin-bit enumeration with zero audit trace); (2)
      pre-check vs. FOR-UPDATE MVCC race can skip the intent row in the
      inverse direction, reopening the rollback gap in a narrow concurrent
      /grant-admin window. Two out-of-model advisories: intent rows bypass the
      V24 actor-integrity GUC check (BAN_INTENT parity), and the typed
      BLOCKED_SSRF reply is a low-rate internal-range DNS oracle. M1-151 is
      done; fixes would land as a follow-up remediation ticket.
  date: 2026-06-05
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 3: the last-admin IT names neither test class nor method; name the class (e.g. LastAdminProtectionIT) so the reviewer has a specific artifact"
    - "COMPLEXITY-RISK-CALIBRATED: risk: low slightly under-calibrated for last-admin handler changes; risk: medium would be more accurate"
    - "SECURITY-FLAG-CONSISTENT: security_relevant: false on a ticket touching last-admin protection (BanCommandHandler, RevokeAdminCommandHandler) is inconsistent with the security surface definitions; consider flipping to true"
---

# M1-151: Typed SSRF / error signals

## Context

Two fragile string-sniffing patterns: `UrlProbe.java:95-96` branches on
`message.startsWith("body read timeout"/"body read deadline")` from
`SsrfPolicyException`; `BanCommandHandler`/`RevokeAdminCommandHandler` detect the
V5 last-admin trigger by `e.getMessage().contains("last_admin_protection")`.
A reword of either message silently breaks the mapping.

The V5 trigger raises with no `USING ERRCODE`, so its SQLSTATE is the generic
`P0001` — shared by every plpgsql `RAISE` in V21/V25/V32 and the audit
append-only trigger. Handler-side SQLSTATE detection therefore needs a new
migration (V35) that re-`CREATE OR REPLACE`s the two V5 trigger functions with
`USING ERRCODE = 'IC001'` (custom SQLSTATE; class `IC` is unused by the SQL
standard and PostgreSQL). V34 is claimed by M1-139 (in-flight worktree).

## Acceptance

See frontmatter. Match on type (typed `SsrfPolicyException` reason) and on
SQLSTATE, not on text.

## Out-of-scope

See frontmatter. `blocked_by: M1-144` — the last-admin handlers are heavily
edited by the UserRepository sweep; rebase onto it.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-URLPROBE-MSG, §C-LASTADMIN-MSG;
  `opus-47-full-handout.md` §F-MAINT-82. Note: opus-47-full dropped C-URLPROBE-MSG;
  the master handout recovered it (`UrlProbe.java` lives under provider/source).
- 2026-06-05 refine (budget-breach): original acceptance demanded
  `RAISE … USING ERRCODE + getSQLState()` while excluding the V5 trigger
  definition and claiming `migration_touch: false` — internally contradictory.
  Refined to include migration V35, widen `files_scope` to the migration dir
  and core schema tests, and fold in the clarity warnings (named test
  artifacts, `risk: medium`, `security_relevant: true`). No new IT: the
  last-admin branch already fires against real PostgreSQL in
  `RevokeAdminCommandHandlerTest` / `BanCommandHandlerTest`.
- 2026-06-06 refine (redteam-finding): the 2026-06-06 redteam audit found
  refused last-admin `/revoke-admin` attempts leave zero audit rows (the only
  REVOKE_ADMIN row rolls back with the trigger). Folded in the fix: new
  `AuditAction.REVOKE_ADMIN_INTENT` + separate-auto-commit-connection intent
  row in the revoke handler (BAN_INTENT pattern), written after the
  in-transaction guards pass so non-admin probes and no-op replies don't spam
  audit rows. No migration: `audit_log.action` is TEXT, unconstrained.
  Widened `files_scope` to the core audit package; `files_budget` 8 → 11.
- 2026-06-06 refine (premise-fail): the redteam-refine's placement clause
  ("separate connection after the in-transaction guards") self-deadlocked —
  `audit_log.actor_user_id` (V5:253, `REFERENCES users(id)`) makes the intent
  INSERT take FOR KEY SHARE on the actor row the in-tx admin gate holds FOR
  UPDATE; conn 1 waits on conn 2 in application code, PostgreSQL sees no
  cycle, the suite hung in RevokeAdminCommandHandlerTest. Reworded to the
  true BAN pattern: intent row BEFORE the locking transaction, gated by
  non-locking pre-checks (gating only — in-tx guards stay authoritative).
  Alternatives considered and rejected: FOR NO KEY UPDATE downgrade in the
  shared UserRepository (weakens a shared lock primitive, keeps hold-and-wait
  topology), catch-block compensating write (inverts step-8/step-9 ordering,
  covers only the IC001 branch, crash window).
