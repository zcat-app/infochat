---
id: M1-323
title: "Provider group-row reuse: Outcome.Approved carries groups.id, drop router step-4.1 re-read"
status: done
created: 2026-06-13
last_updated: 2026-06-13
blocked_by: [M1-306]
files_budget: 24
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupApprovalCheck.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupApprovalService.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The U-32 / U-42 / U-66 fixes — shipped in M1-306 (this ticket was split out of M1-306 during a budget-breach refine).
  - Consolidating the TWO findApprovalRow reads in the approval path (GroupApprovalCheck.check + GroupApprovalService.evaluate) — only the router's step-4.1 re-read is in scope; the approval-path reads are a separate optimization.
acceptance:
  - "U-67: GroupApprovalCheck.Outcome.Approved is no longer an empty record — it carries the groups.id (UUID) that the approval read already resolved. Every construction and match site is updated (production + test doubles RecordingGroupApprovalCheck / NoopGroupApprovalCheck + GroupApprovalCheckTest / GroupApprovalServiceTest)."
  - "InboundRouter's step-4.1 lookupGroupId re-read is dropped: the group-scope dispatchScopeId is taken from the carried Outcome.Approved.groupId() resolved at step 3.5, so an approved-group inbound resolves the groups row fewer times (was three: check.findApprovalRow + evaluate.findApprovalRow + router.lookupGroupId). The now-unused InboundRouter.lookupGroupId method and its SELECT_GROUP_SQL constant are removed (they become dead once the only production caller is gone)."
  - "REMOVED-GROUP DROP PRESERVED: today a removed-but-approved group (approval_status='approved' AND removed_at IS NOT NULL) is dropped at step 4.1 because lookupGroupId filters removed_at IS NULL. After dropping that re-read, the approval layer must preserve this: an approved row whose removed_at is set must NOT yield Outcome.Approved — it dispatches to a silent drop (no reply, no membership write), matching today's behaviour. findApprovalRow already returns removed_at, so dispatchByStatus has the data. A named test pins removed-but-approved → silent drop (no reply)."
  - "TIMING-ORACLE PRESERVED (docs/spec/security.md §Authorization model): the removed/vanished-group path stays a silent drop, never a reply or a throw, so an attacker cannot distinguish removed-group state by response shape. The router's pre-existing groupChatMessageWithVanishedGroupRowIsSilentlyDroppedNotThrown intent is preserved (reworked onto the new mechanism, since the lookupGroupId-empty seam it used is gone)."
  - "The router vanished-group race narrowing is acceptable and documented in-code: a group removed BETWEEN the step-3.5 approval read and the step-4.1 membership write now passes (the approval read is authoritative); the benign membership INSERT for a just-removed group is tolerated. A code comment records the trade."
  - "All pre-existing router/group tests stay green after the call-site sweep (InboundRouterAcquisitionCountTest, InboundRouterChatModeIT, InboundRouterIntakeOrderingTest, InboundRouterProbationOrderingTest, GroupAuthorizationRoundtripIT, CountingDispatchDataSource)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-13
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 199
      removed: 145
overrides: []
escalations: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-13
    category: PERM-ESCAL
    severity: low
    promise: |
      docs/spec/security.md §Authorization model timing-oracle commitment
      (step 3.5 silent-drop rule): the pre-diff router re-read groups WHERE
      removed_at IS NULL at step 4.1, so a group removed between the step-3.5
      approval read and the membership write produced no auto-promote, no
      membership row, and no command/chat processing.
    gap: |
      InboundRouter step-4.1 now uses Objects.requireNonNull(approvedGroupId)
      and calls tryAutoPromote / ensureGroupMembership with no re-validation
      of removed_at (the former lookupGroupId WHERE removed_at IS NULL is
      deleted). dispatchByStatus only catches the already-removed-at-read
      case (approved + removed_at != null -> SilentDrop); it cannot see a
      removal that lands after its own SELECT.
    repro: |
      An attacker @mentions the bot in an approved group at the same instant
      an admin soft-removes that group. If the removal commits after the
      step-3.5 findApprovalRow SELECT but before step 4.1, the router writes
      an is_group_admin auto-promote row and a membership row against a
      removed group. Window is microseconds inside one synchronous handler;
      attacker cannot reliably trigger or observe it; resulting rows are
      dormant (every subsequent @mention resolves to SilentDrop). Net effect
      is a defense-in-depth reduction, not a reachable privilege gain.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-06-13
    verdict: FINDINGS
    base: f012c146e7cc4bad5b4859e6c67ef1d6955ea791
    head: working-tree (uncommitted, branch m1/M1-323-provider-group-row-reuse)
    verdict_file: docs/plan/m1/redteam/M1-323-2026-06-13.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Ran on the in-review branch tip before /m1-tick commit (security_relevant
      ticket). One LOW finding (PERM-ESCAL vanished-group race) — it is the exact
      trade-off acceptance item 5 authorized and required to be documented in-code;
      verified the InboundRouter step-4.1 race-trade comment and the
      approved+removed_at -> SilentDrop mapping exist on disk. Threat-actor itself
      rates it a defense-in-depth reduction with dormant rows, not a reachable
      privilege gain. No remediation required; the gap was accepted by design.
      One OUT-OF-MODEL advisory (in-flight command across a concurrent removal) —
      outside the documented soft-delete threat model; user may decide whether to
      constrain it in spec.
outline_file: target/m1-tick-outline-M1-323.md
clarity_check:
  date: 2026-06-13
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 5: 'A code comment records the trade' is a by-inspection assertion, checkable in the diff but weaker than a behavioral test assertion."
    - "FILES-BUDGET-PLAUSIBLE: files_budget 24 is ~85% above the author's own estimate of ~13 files; could be tightened after grounding confirms the call-site count."
  blockers: []
---

# M1-323: Provider group-row reuse — Outcome.Approved carries groups.id

## Context

Split out of **M1-306** during a budget-breach refine (2026-06-13). The
deep-review v5 finding **U-67** (LOW; sources `fable-5/07#F8` +
`gpt-55#L-04`) observed that the `groups` row is read three times per
approved-group inbound message: `GroupApprovalCheck.check`'s
`findApprovalRow` (for the per-group reply bucket key),
`GroupApprovalService.evaluate`'s `findApprovalRow` (for the approval
decision), and `InboundRouter`'s step-4.1 `lookupGroupId` (to resolve the
`groups.id` for the membership write + rate caps). `GroupApprovalCheck.Outcome.Approved`
is today an empty record; carrying the `groups.id` it already read lets the
router drop its step-4.1 re-read.

This was pulled out of M1-306 because the change is larger and riskier than
the other M1-306 items:

- The `Outcome.Approved` signature change fans out to every construction
  and match site — ~13 files (3 production + ~10 test doubles/ITs) — versus
  M1-306's remaining ~10-file budget.
- It touches a **security-relevant timing-oracle protection**. The router's
  step-4.1 `lookupGroupId` (which filters `removed_at IS NULL`) is what
  currently drops a removed-but-approved group, as a *silent* drop so the
  response shape never reveals removed-group state (see the step-4.1 comment:
  "throwing here was a timing oracle distinguishing removed-group state").
  Dropping that re-read forces moving `removed_at` handling into the approval
  layer's dispatch, and the drop must stay silent.

## Implementation notes (carried from M1-306 grounding, verified 2026-06-13)

- `GroupRepository.GroupApprovalRow` already carries `id`, `approvalStatus`,
  `activatedBy`, `removedAt` — `dispatchByStatus` has everything it needs;
  no `GroupRepository` change is required.
- `dispatchByStatus(String approvalStatus)` is `static`, called from
  `GroupApprovalService.evaluate` (existing-row branch + race-loser
  re-read). Change it to take the row (or status + id + removedAt) and:
  approved + `removedAt == null` → `Approved(id)`; approved + `removedAt != null`
  → `SilentDrop`; `pending` → `FixedReply(GROUP_PENDING)`; `rejected` →
  `FixedReply(GROUP_REJECTED)`. (pending/rejected ignore `removed_at`, as
  today.) The `SilentDrop` variant already exists for bucket exhaustion; the
  router returns with no reply for it — same observable as today's step-4.1
  removed-group drop.
- `InboundRouter`: in step 3.5 capture `Approved.groupId()` into a
  `@Nullable UUID` that survives to step 4.1; in step 4.1 use it (guaranteed
  non-null for any group reaching that point — pending/rejected/silent-drop
  returned earlier) instead of `lookupGroupId`. Remove `lookupGroupId` +
  `SELECT_GROUP_SQL`. Add `import java.util.Objects` for the `requireNonNull`
  on the carried id, or restructure to avoid the @Nullable→nonnull assign.
- **Call-site sweep (recorded rule — grep before finalizing):**
  `new Outcome.Approved()` and `case Approved` appear in
  `RecordingGroupApprovalCheck`, `NoopGroupApprovalCheck`,
  `GroupApprovalCheckTest`, `GroupApprovalServiceTest`, and `InboundRouter`.
  The plain-JUnit router tests (`InboundRouterIntakeOrderingTest`,
  `InboundRouterProbationOrderingTest`) ALSO override `lookupGroupId` (those
  overrides become dead — remove them) and wire `CountingDispatchDataSource`
  with a `groupDbId`; make the Recording/Noop check return
  `Approved(<that same groupDbId>)` so the group dispatchScopeId is unchanged
  and downstream assertions stay green. `InboundRouterChatModeIT` has a direct
  `lookupGroupIdReturnsEmptyForUnknownGroupInsteadOfThrowing` test that must
  be removed/re-homed (the method is gone).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-323-*.md
```
