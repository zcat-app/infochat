---
id: M1-143
title: "MembershipEventHandler audit-before-effect (Invariant 7)"
status: done
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider
  - infochat-provider/src/test/java/app/zcat/infochat/provider
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - other admin command handlers (they already follow audit-before-effect)
  - the AuditLogWriter consolidation (deferred under M1-041)
  - redteam findings 2-4 of docs/plan/m1/redteam/M1-143-2026-06-05.md (FOR-UPDATE locking of the was_group_admin read, membership-event redelivery/retry on transient DB failure, membership-event rate cap) — remediation-ticket material, not in-branch fixes
acceptance:
  - "MembershipEventHandler wraps the audit row and the state mutation in one transaction (mirroring BanCommandHandler) so MEMBER_LEFT/BOT_REMOVED audit-before-effect holds (Invariant 7), instead of mutating then auditing then swallowing failure"
  - "GroupMembershipRepository.isGroupAdmin / markMemberRemoved gain Connection-accepting overloads for the spanning transaction"
  - "A test asserts an audit-write failure rolls back the mutation (the was_group_admin flag is not silently lost)"
  - "On audit-write or mutation failure inside the spanning transaction, the exception thrown out of MembershipEventHandler carries neither the original SQLException as cause nor its message text — only the failure's exception class name is preserved (the SafeLog convention) — so that 'Exception messages and stack traces emitted via the application logger MUST NOT contain user-authored prose' (security.md §User content in exceptions) holds even when the propagated exception is printed by a generic uncaught-exception logger above the adapter dispatch path"
  - "'Contact IDs are logged in redacted form (prefix + ellipsis + suffix) outside the audit log' (security.md §Secrets handling) is preserved on the failure path: no contact id and no audit-row content appears anywhere in the thrown exception chain"
  - "A test asserts the exception thrown on audit-write failure contains the failing exception's class name but neither the SQLException message text nor the contact id"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §User content in exceptions
  - docs/spec/security.md §Secrets handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 176
      removed: 43
  - round: 2
    date: 2026-06-05
    verdict: MANUAL
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 386
      removed: 45
  - round: 2
    date: 2026-06-05
    verdict: OVERRIDE-APPROVE
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    override_ref: 0
revisions:
  - date: 2026-06-05
    reason: redteam-finding rework (finding 1, INFO-LEAK medium)
    snapshot:
      status: escalated
      escalation_reason: redteam-finding
      acceptance_at_snapshot:
        - "MembershipEventHandler wraps the audit row and the state mutation in one transaction (mirroring BanCommandHandler) so MEMBER_LEFT/BOT_REMOVED audit-before-effect holds (Invariant 7), instead of mutating then auditing then swallowing failure"
        - "GroupMembershipRepository.isGroupAdmin / markMemberRemoved gain Connection-accepting overloads for the spanning transaction"
        - "A test asserts an audit-write failure rolls back the mutation (the was_group_admin flag is not silently lost)"
        - "mvn -B clean verify from the repo root exits 0"
      out_of_scope_at_snapshot:
        - other admin command handlers (they already follow audit-before-effect)
        - the AuditLogWriter consolidation (deferred under M1-041)
overrides:
  - date: 2026-06-05
    objection: |
      SCOPE-DRIFT-CHECK: FAIL — "Mechanical must-shrink failure only. Round 2
      stats (files 8, added 386, removed 45) grew along ALL THREE dimensions
      vs round 1 (files 7, added 176, removed 43), and the round-1 verdict
      was APPROVE with zero REWORK items, so the engineering-rules-verbatim.md
      §Round-N must-shrink exception ('cite the round-(N-1) REWORK item in
      the commit message') is structurally unavailable."
    user_justification: |
      Override selected per the in-chat recommendation the user adopted: the
      round-2 growth is entirely mandated by the formal redteam-finding
      escalation (redteam verdict file, escalation/revisions/redteam_findings
      frontmatter, and the sanitization fix + tests the three NEW acceptance
      items require); every substantive check passed (test integrity,
      out-of-scope, negative space, acceptance 7/7, spec conformance) and the
      reviewer itself assessed the diff as APPROVE-quality with must-shrink
      structurally unsatisfiable. M1-131 precedent: redteam-mandated growth
      on a post-APPROVE branch resolves by override, not shrinking.
escalations:
  - date: 2026-06-05
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      RED-TEAM VERDICT: FINDINGS (verdict file:
      docs/plan/m1/redteam/M1-143-2026-06-05.md). 4 findings: 1 medium
      INFO-LEAK (SafeLog removal lets raw SQLException text with unredacted
      actorContactId propagate uncaught to the generic logger), 3 low
      (AUDIT-EVASION: no FOR UPDATE on the was_group_admin read;
      DOS: audit-failure abort on one-shot membership events can strand a
      phantom admin and drop sibling memberLeft entries;
      DOS: per-leave audit rows attacker-repeatable with no rate cap,
      pre-existing surface). Most recent code review: APPROVE (round 1).
  - date: 2026-06-05
    reason: manual-verdict
    reviewer_verdict_excerpt: |
      VERDICT: MANUAL (round 2). SCOPE-DRIFT-CHECK: FAIL — mechanical
      must-shrink failure only: round 2 (8 files, +386, -45) grew along all
      three dimensions vs round 1 (7, +176, -43), and round 1 was APPROVE
      with zero REWORK items, so the codified exception (citing a
      round-(N-1) REWORK item) is structurally unavailable. All other
      checks PASS; acceptance 7/7 PASS; "the substantive diff is
      APPROVE-quality". Growth traces entirely to the redteam-finding
      escalation: redteam verdict file (+58), escalation/revisions
      frontmatter (+~110), and the sanitization fix + tests the three NEW
      acceptance items require. Reviewer-suggested resolutions: (1) user
      override of the must-shrink FAIL; (2) process amendment baselining
      redteam-escalation rounds against the revised ticket.
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-05
    category: INFO-LEAK
    severity: medium
    promise: |
      "Exception messages and stack traces emitted via the application logger MUST NOT contain user-authored prose ... The application provides a `SafeLog` utility that drops the exception message body, retains only the exception class name ... The original `Throwable` is never passed to the underlying SLF4J logger." (§User content in exceptions) and "Contact IDs are logged in redacted form (prefix + ellipsis + suffix) outside the audit log." (§Secrets handling)
    gap: |
      The diff removes the only SafeLog usage in MembershipEventHandler.java (the old audit-failure catch used SafeLog.error(log, ..., e); the import is deleted) and replaces it with raw propagation: the SQLException is wrapped as the cause of an IllegalStateException thrown out of handle(). The dispatch lambda at AdapterRegistry.java:281 has no catch, and SignalGroupHandler.dispatchMembership (SignalGroupHandler.java:179-188) also has none — the raw Throwable chain reaches the generic logger above the adapter reader. Postgres SQLException messages routinely embed bound row values in constraint-violation DETAIL lines; the failed statement is the audit_log INSERT, whose row carries the full unredacted actorContactId (redaction happens only at audit_log_view). The previous code guaranteed only class names could reach the log; the new code guarantees nothing.
    repro: |
      A registered group member leaves a group at a moment when the audit INSERT fails with a constraint/length violation (e.g., request_id collision, column-width violation, or any error whose DETAIL line echoes the inserted tuple). The SQLException message — containing the full contact id and details_json — is wrapped and thrown out of handle(), propagates uncaught through the adapter dispatch path, and is printed verbatim by the default exception logger, putting an unredacted contact id (and potentially audit payload) in the non-audit log stream the spec promises keeps them redacted.
    suggested_fix_class: other
  - date: 2026-06-05
    category: AUDIT-EVASION
    severity: low
    promise: |
      "Audit-log the intent" (§Authorization model step 8) together with "On `/unban`, restored group-admin roles are explicitly disclosed ... Without this disclosure, an admin ... can silently re-grant group-admin powers" (§User ban) — the spec treats accurate group-admin provenance in the audit trail as a security signal, and the diff's own stated goal is that "the was_group_admin flag is never silently lost."
    gap: |
      MembershipEventHandler.java:91-95 reads isGroupAdmin and then runs markMemberRemoved inside one transaction, but the SELECT (GroupMembershipRepository.java:71-80, plain SELECT is_group_admin ... WHERE removed_at IS NULL, no FOR UPDATE) takes no row lock under default READ COMMITTED. A /promote or /demote committing between the SELECT and the UPDATE makes the audited was_group_admin value wrong: the V5 trigger then clears the just-granted admin bit while the MEMBER_LEFT audit row records was_group_admin:false. The admin-status transition vanishes from the audit trail — the "silently lost" outcome the transaction was built to prevent, surviving via a race instead of a write failure.
    repro: |
      Attacker who is being promoted times their group departure against the bot admin's /promote (departure events are fully under the leaving user's control). Window: SELECT returns false → concurrent /promote commits is_group_admin=true → markMemberRemoved fires the V5 trigger clearing it. Result: the user held group admin, but no audit row anywhere says so; a later /unban-style forensic reconstruction of "was this user ever a group admin" comes up empty.
    suggested_fix_class: audit-log-coverage
  - date: 2026-06-05
    category: DOS
    severity: low
    promise: |
      "One group admin per group at any time. ... The auto-promote path applies whenever the group has **zero** is_group_admin rows — covering both newly-approved groups and groups left without an admin due to demotion or ban." (§Authorization model) — the spec's design keeps the single admin slot recoverable.
    gap: |
      The diff converts the UserLeft mutation from "always applied, audit best-effort" to "atomically aborted on any audit/DB failure" (MembershipEventHandler.java:85-105) with no retry, no outbox, and no compensating path — adapter membership events are one-shot deliveries. A transient DB error during the window now permanently loses the removal: the departed member keeps removed_at IS NULL and, if they were group admin, keeps is_group_admin=true, occupying the partial-unique-index slot so the auto-promote refill path (which requires zero admin rows) can never fire. Additionally, the thrown exception propagates through the uncaught dispatch lambda (AdapterRegistry.java:281) and aborts the dispatchMembership loop in SignalGroupHandler.java:179-188 mid-array, so one failed event also drops the remaining memberLeft ACIs in the same group update and can kill the adapter's reader path.
    repro: |
      Signal group update arrives with memberLeft: [adminACI, memberB, memberC] during a brief DB hiccup. The first handle() throws; memberB and memberC are never processed and the event is never redelivered. The group is left with a phantom admin who is no longer a member; only a bot-admin /promote can recover the slot, and stale active-membership rows persist silently (the old code at minimum applied the mutation).
    suggested_fix_class: other
  - date: 2026-06-05
    category: DOS
    severity: low
    promise: |
      "The drop is counted but not individually audit-logged (a hostile actor can trigger many drops)." (§Invite-code registration) — the spec's stated principle that attacker-repeatable events must not each mint an audit row; plus §Rate limiting's commitment that adversary-driven volume is bucket-bounded.
    gap: |
      The membership-event path (pre-existing, but squarely the code this diff hardens) writes one MEMBER_LEFT audit row per UserLeft event (MembershipEventHandler.java:92-94) with no rate cap anywhere on the membership-event surface — the transport cap (step 1.5) keys on inbound messages, and membership events enter via setMembershipEventHandler (AdapterRegistry.java:281), bypassing the router entirely. Leave events are attacker-repeatable at will.
    repro: |
      A registered member of an approved Signal group with a join link scripts leave/rejoin cycles. Each leave produces a DB transaction plus an INSERT-only audit row, unbounded and unthrottled — inflating audit_log and burying genuine admin-relevant rows in /audit output, at zero cost to the attacker and with no counter or throttle to surface it.
    suggested_fix_class: rate-limit
redteam_audits:
  - date: 2026-06-05
    verdict: FINDINGS
    base: de9fe528f3fcc1e93b0b289941d3c399f4c0fb9c
    head: working tree (pre-commit, branch m1/M1-143-membership-audit-before-effect)
    verdict_file: docs/plan/m1/redteam/M1-143-2026-06-05.md
    findings_count: 4
    out_of_model_count: 1
    note: |
      In-progress audit run between review APPROVE (round 1) and commit, per
      user opt-in. One medium INFO-LEAK: removing the SafeLog catch lets raw
      SQLException text (which can embed the unredacted audit_log tuple,
      including actorContactId) propagate uncaught to the generic logger.
      Three low findings: was_group_admin read takes no FOR UPDATE lock
      (promote/leave race erases admin provenance); audit-failure abort plus
      one-shot membership delivery can strand a phantom admin and drop
      sibling memberLeft entries; per-leave audit rows are attacker-repeatable
      with no rate cap (pre-existing surface). Disposition pending
      /m1-tick escalate M1-143 redteam-finding.

clarity_check:
  date: 2026-06-05
  verdict: PASS
  warnings: []
---

# M1-143: MembershipEventHandler audit-before-effect (Invariant 7)

## Context

`MembershipEventHandler.java:105-127` mutates state, then opens a fresh
connection to audit, then logs-and-continues on failure — inverting Invariant 7
(audit-before-effect) for `MEMBER_LEFT`/`BOT_REMOVED`, unlike every other admin
handler. The `was_group_admin` flag loss has a real downstream effect on
`/unban` group-admin restoration.

## Acceptance

See frontmatter. Wrap audit + mutation in one transaction (BanCommandHandler
pattern); add Connection-accepting overloads.

## Out-of-scope

See frontmatter. **security_relevant** → run `/redteam` after.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §B-MEMBERSHIP-AUDIT;
  `opus-47-full-handout.md` §F-MAINT-67; `opus-47-only-handout.md` §M20.
