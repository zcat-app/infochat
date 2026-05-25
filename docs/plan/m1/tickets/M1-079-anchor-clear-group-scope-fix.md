---
id: M1-079
title: "Summary anchor clear: use group scopeId for group scope"
status: done
created: 2026-05-25
last_updated: 2026-05-25
escalations:
  - date: 2026-05-25
    reason: budget-breach
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — InboundRouterIntakeOrderingTest.java
      touched but not in files_scope. Valid orphan from lookupGroupId
      visibility widen.
revisions:
  - date: 2026-05-25
    reason: "refine after budget-breach: add orphan test file to files_scope"
reviews:
  - round: 1
    date: 2026-05-25
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 51
      removed: 4
  - round: 2
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 80
      removed: 4
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: [M1-069]
remediates: M1-069
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterIntakeOrderingTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
source: redteam M1-069-2026-05-25 (Finding 1, INFO-LEAK medium)
out_of_scope:
  - any change to SummaryAnchorRepository internals — this ticket fixes the caller, not the repository
  - any change to the /retry or /summary command handlers
  - any change to ChatAgent or chat-mode dispatch (already fixed by M1-069)
acceptance:
  - "summaryAnchorRepository.clear() at step 4.6 passes the correct scopeId for group scope: the group UUID (via resolveChatScopeId) rather than actorId. Verify: InboundRouterChatModeIT.groupScopeAnchorClearUsesGroupId passes"
  - "For DM scope, the anchor clear still passes actorId as scopeId (unchanged). Verify: existing InboundRouterChatModeIT tests remain green"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - InboundRouterChatModeIT.groupScopeAnchorClearUsesGroupId (new)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
  - docs/spec/architecture.md §Architectural principles
decision_refs: []
redteam_findings: []
redteam_audits:
  - date: 2026-05-25
    verdict: CLEAN
    base: main
    head: m1/M1-079-anchor-clear-group-scope-fix
    verdict_file: docs/plan/m1/redteam/M1-079-2026-05-25.md
    out_of_model_count: 0
    note: |
      Clean audit. The diff fixes a per-(user, scope) isolation bug in
      summary anchor clearing without introducing new security gaps.
      The lookupGroupId visibility widen is test-infrastructure only.
---

## Context

Redteam finding on M1-069: `summaryAnchorRepository.clear(anchorActorId, anchorActorId)` at InboundRouter step 4.6 passes `actorId` as both userId and scopeId. For group scope, the second argument should be the group UUID. After M1-069 fixed the chat-mode dispatch to use the correct group UUID as scopeId, the anchor-clear became inconsistently wrong — a group-scope message silently clears the DM-scope anchor and never clears the group-scope anchor.

## Fix approach

Replace the second argument to `summaryAnchorRepository.clear()` with `resolveChatScopeId(msg.scope(), anchorActorId, adapterName)`, reusing the method M1-069 added.

## Round 1 rework

1. SCOPE-DRIFT-CHECK: FAIL — `InboundRouterIntakeOrderingTest.java` touched but not in `files_scope`. The change is a valid orphan (visibility widen of `lookupGroupId` to package-private broke the unit test's anonymous subclass). Resolution: amend `files_scope` to include the test file (escalate → refine).
