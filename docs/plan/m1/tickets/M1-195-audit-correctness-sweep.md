---
id: M1-195
title: "Audit correctness: auto-promote guard, /unban no-op, intent-row parity"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupAutoPromoteService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/UnbanCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/VouchCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/PromoteCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/DemoteCommandHandler.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/AuditAction.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - the SET LOCAL infochat.actor_id string-concat sites in these same handlers — M1-206's mechanical sweep; do not parameterize them here
  - group-scope caller resolution (the M1-044c DM-only convention vs the design matrix) — M1-198's; /unban and /vouch keep their current caller-resolution shape here
  - ConfirmStateService sweep, admin-handler structural dedup, V5 verb-catalogue comment drift — UNIFIED.md T33 (lows batch, not yet filed)
  - DIGEST_SLOT_MISSED false positives — M1-196's
  - the one_admin_per_group partial unique index itself and InboundRouter's step-4.1 call ordering — unchanged
acceptance:
  - "A standing group admin's subsequent group messages write zero additional PROMOTE_GROUP_ADMIN audit rows: a named DB-backed test routes multiple messages from the current admin through tryAutoPromote and asserts exactly one PROMOTE_GROUP_ADMIN row exists for that (group, user) (today AUTO_PROMOTE_SQL's ON CONFLICT DO UPDATE WHERE clause checks only removed_at IS NULL — no is_group_admin = false guard — so the true→true UPDATE returns 1 and a fresh audit row is written per message)"
  - "A non-admin member's group message while another user holds the admin slot returns false from tryAutoPromote without writing an audit row, exercised by a named DB-backed test; the steady-state path no longer relies on catching 23505 from the one_admin_per_group partial index (the catch may remain as a genuine race guard for concurrent first-insert promotions)"
  - "The AUTO_PROMOTE_SQL comment describes the occupied-slot rejection as a 23505 unique violation raised by the one_admin_per_group partial index (the genuine race-guard leg) and no longer claims the case surfaces as executeUpdate returning 0"
  - "/unban of a registered, non-banned, non-preban user writes no UNBAN audit row and its reply claims no group-admin restoration: a named test asserts the audit table gains no UNBAN row for the no-op call (today the handler pre-writes the UNBAN audit row with the restored_group_admin list and runs the UPDATE regardless of is_banned state)"
  - "Per docs/spec/security.md §Authorization model — step 8 \"Audit-log the intent.\" precedes step 9 \"Execute.\" — /vouch, /promote, /demote, and /unban each write an audit-on-intent row (a distinct action verb from the effect row) before their mutation executes, with the same refusal-leg semantics as the existing GRANT_ADMIN_INTENT / REVOKE_ADMIN_INTENT rows: the intent row is written only after the caller's permission gate passes and before every execution-semantics check (the GrantAdminCommandHandler step-3→step-4 placement — unknown-contact, banned-target, and no-op probes leave a surviving intent row, while non-admin-caller refusals remain audit-silent), and a named test per handler asserts the intent row is present on a post-permission refusal leg (today only Ban/BanConfirm/GrantAdmin/RevokeAdmin/Invite/RejectGroup/RemoveSource/SourceEnable write *_INTENT rows)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Authorization model
  - docs/spec/commands.md §Permission model
decision_refs: []
reviews: []
escalations:
  - date: 2026-06-07
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED FAIL: test_plan.modifies names the group test
      directory but the ticket body lacks an "Authorized test changes"
      section. Add a section that lists: (a) which specific existing test
      class(es) in infochat-provider/src/test/java/app/zcat/infochat/provider/group
      are modified; (b) which test method(s) change; and (c) the new expected
      behavior (e.g., "GroupAutoPromoteServiceTest.existingAdminReceivesNoSpuriousAuditRow
      — was: asserts row written per message; becomes: asserts exactly one row
      total for (group, user)"). Without this, the implementer has no
      authorized contract for modifying pre-existing tests and the reviewer
      cannot verify compliance.
revisions:
  - date: 2026-06-07
    reason: clarity-fail rework — add §Authorized test changes naming the one existing test whose assertions change (TEST-CHANGES-AUTHORIZED blocker), list the command test directory under test_plan.modifies, pin acceptance item 5's refusal-leg semantics to the post-permission intent placement, fold in both warnings (risk high + round_cap 3, item-3 mechanism named)
    prior_values: |
      acceptance item 3: "The AUTO_PROMOTE_SQL comment no longer claims the
        occupied-slot case surfaces as executeUpdate returning 0 — it
        describes the actual adjudicated mechanism (without the guard, the
        occupied-slot rejection arrives as a 23505 unique violation from the
        partial index, not a zero update count)"
      acceptance item 5: refusal-leg clause read "a named test per handler
        asserts the intent row is present even when the mutation is refused"
        (no post-permission placement clause distinguishing audit-silent
        non-admin refusals from intent-surviving execution-semantics probes)
      risk: medium
      round_cap: 2
      test_plan.modifies: only the group test directory
      body: no §Authorized test changes section
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-195: Audit correctness: auto-promote guard, /unban no-op, intent-row parity

## Context

Three audit-integrity defects (unified findings P4, P7, P8 —
`deep-code-review/v2/UNIFIED.md` §2):

1. **Auto-promote audit spam + steady-state exception cycle (P4,
   med-high).** `GroupAutoPromoteService.AUTO_PROMOTE_SQL`
   (infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupAutoPromoteService.java:43-49)
   runs on every registered sender's group message
   (InboundRouter.java step 4.1, ~:500-514). The `ON CONFLICT DO UPDATE`'s
   WHERE clause lacks an `is_group_admin = false` guard, so a standing
   admin's every message re-runs the true→true UPDATE (returns 1) and
   writes a spurious PROMOTE_GROUP_ADMIN audit row; a non-admin member's
   message while the slot is held trips the one_admin_per_group partial
   index → caught 23505 + rollback per message. **Adjudicated (Tier-A
   binding):** the defect mechanism is the missing guard — mimo's
   "verified correct" was wrong, and the in-code comment claiming
   "executeUpdate returns 0" is wrong about the mechanism.
2. **/unban no-op writes a false audit trail (P7, med).**
   UnbanCommandHandler.java:149-180 pre-writes the UNBAN audit row
   (with restored_group_admin list) and runs
   `UPDATE … WHERE id = ?` with no `is_banned` check — unbanning a
   never-banned user fabricates an UNBAN event and a restoration claim.
3. **Intent-row asymmetry (P8, med).** Eight handlers write
   audit-on-intent rows; /vouch, /promote, /demote, /unban do not —
   refused probes by those four leave no audit trace
   (probe-enumeration audit evasion). The GRANT_ADMIN_INTENT /
   REVOKE_ADMIN_INTENT comments in AuditAction document the established
   semantics to mirror (intent row survives refusal legs; written on a
   separate auto-commit connection before the mutation transaction).

## Acceptance

See frontmatter. The spec sentence for the intent leg is
security.md §Authorization model steps 8-9 ("Audit-log the intent." /
"Execute.").

## Authorized test changes

Ground-truthed against the test tree on 2026-06-07:

- `UnbanCommandHandlerTest.unbanUnknownContactReturnsContactNotRegistered`
  — the ONLY existing test whose assertions change. Was: asserts the
  action-agnostic prefix count (`countAuditUnderTargetPrefix(PREFIX +
  "unknown-")`) is unchanged ("/unban against unknown contact must not
  write any audit row"). Becomes: the admin's unknown-contact probe gains
  exactly one UNBAN_INTENT row (the intent write precedes target
  resolution, per the GrantAdmin unknown-contact semantics) and still
  zero UNBAN / UNBAN_PREBAN_DELETE rows; the users-row count assertion
  is unchanged.
- `UnbanCommandHandlerTest.unbanByNonAdminReturnsAdminOnly` — untouched:
  the non-admin refusal fires at the permission pre-check, before the
  intent write, so its zero-audit-rows assertion stays valid.
- `GroupAutoPromoteServiceTest` — modified by ADDING test methods
  (acceptance items 1-2) and at most a row-counting helper. None of the
  six existing test methods' assertions change: none exercises a
  standing admin's repeat promotion or asserts audit-row counts, and
  `tryAutoPromote_rePromotesExistingNonAdminMember`'s false→true leg
  remains permitted by the new `is_group_admin = false` guard.
- `VouchCommandHandlerTest`, `PromoteCommandHandlerTest`,
  `DemoteCommandHandlerTest` — modified by ADDING the named intent tests
  only; existing refusal assertions count action-specific rows
  (`action = 'VOUCH'`) or assert reply text only, so new *_INTENT rows
  do not collide.
- No other test references the affected verbs (repo-wide sweep:
  AuditCommandHandlerTest seeds its own rows; PromoteRevokeConcurrencyIT
  and DemoteRevokeConcurrencyIT assert no audit counts).

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T19 under `deep-code-review/v2/` (kimi-folder
  prov F2/F3/F4).
- New AuditAction enum entries (e.g. VOUCH_INTENT) need no migration —
  audit_log.action is TEXT; the V5 verb-catalogue comment drift is
  accepted-low and stays untouched (T33).
- The intent-row write pattern (separate auto-commit connection, before
  the mutation tx, survives refusals) is established in
  GrantAdminCommandHandler / RevokeAdminCommandHandler — follow it.
