---
id: M1-069
title: Chat-mode group-scope session isolation
status: done
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (07-module-infochat-provider.md#F1, 01-architecture.md#F2)
out_of_scope:
  - any group-scope chat feature enablement beyond fixing the scope_id parameter — group chat may remain feature-gated, but the routing must be correct when it activates
  - any ChatToolDispatcher or tool implementation change — this ticket fixes scope wiring in InboundRouter, not tool internals
  - any new migration — the chat_session table already keys on (user_id, scope_kind, scope_id)
  - any changes to /clear, /compress, or other command handlers
acceptance:
  - "InboundRouter chat-mode dispatch passes the correct scopeId for group scope: the group's UUID (resolved from ScopeRef.Group.adapterGroupId) rather than actorId. Verify: InboundRouterChatModeIT.groupScopeUsesGroupIdNotActorId passes"
  - "For DM scope, scopeId remains actorId (unchanged behavior). Verify: existing InboundRouterChatModeIT tests remain green"
  - "ChatAgent.handle receives distinct (userId, scopeKind, scopeId) tuples for the same user in different groups — sessions do not merge. Verify: ChatAgentTest.distinctGroupsProduceDistinctSessions passes"
  - "If group-scope chat is currently unreachable (gated), an explicit guard returns a friendly error rather than silently routing with wrong scopeId. Verify: InboundRouterChatModeIT.groupChatReturnsNotSupportedIfGated passes"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - InboundRouterChatModeIT.groupScopeUsesGroupIdNotActorId (new)
    - ChatAgentTest.distinctGroupsProduceDistinctSessions (new)
    - InboundRouterChatModeIT.groupChatReturnsNotSupportedIfGated (new, if gated path chosen)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Architectural principles
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §Authorization model
decision_refs: []
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
revisions:
  - date: 2026-05-25
    reason: clarity-fail
    changes: "Fixed spec_refs anchors: §Principle 4 (per-scope isolation) → §Architectural principles; §Invariant 1 (scope-tuple keying) → §Invariants"
reviews:
  - round: 1
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 177
      removed: 12
escalations:
  - date: 2026-05-25
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      SPEC-REFS-VALID: FAIL
        - docs/spec/architecture.md §Principle 4 (per-scope isolation): ANCHOR-NOT-FOUND
        - docs/spec/schema.md §Invariant 1 (scope-tuple keying): ANCHOR-NOT-FOUND
redteam_findings:
  - date: 2026-05-25
    category: INFO-LEAK
    severity: medium
    promise: |
      Per-(user, scope) isolation for state, memory, saves. Never leak across users or between DM and group.
    gap: |
      InboundRouter.java:479 — summaryAnchorRepository.clear(anchorActorId, anchorActorId) passes actorId as both userId and scopeId. After M1-069 fixes chat-mode dispatch to use the group UUID as scopeId, the anchor-clear at line 479 remains inconsistent: a group-scope non-/retry message clears the DM-scope anchor instead of the group-scope anchor.
    repro: |
      (1) User issues /summary in DM, creating a DM-scope anchor. (2) User sends a chat message in a group. (3) Step 4.6 fires clear(actorId, actorId), deleting the DM-scope anchor. (4) User returns to DM, /retry fails — anchor was silently destroyed by a group-scope action.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-05-25
    verdict: FINDINGS
    base: main
    head: m1/M1-069-chat-mode-group-scope-session
    verdict_file: docs/plan/m1/redteam/M1-069-2026-05-25.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      1 medium INFO-LEAK finding: summaryAnchorRepository.clear() at step 4.6 still uses actorId as scopeId for group scope, creating a cross-scope side effect after M1-069's partial fix. Pre-existing bug made more visible by the fix. Recommend a new remediation ticket.
---

## Context

The chat-mode dispatch in InboundRouter (line ~500) passes `actorId` as both `userId` and `scopeId` to `ChatAgent.handle()`. For DM scope this is correct (DM scope_id = user_id by convention). For group scope, this conflates all groups for the same user into one session and causes tools to query the user's DM subscriptions instead of the group's subscriptions.

This violates per-(user, scope) isolation — a fundamental spec invariant. The same user in two groups would share one chat session. Tools (SearchPostsTool, GetPostTool, GetReferencesTool) filter by `scope_id`, returning wrong results in group context.

## Fix approach

Pass the group UUID (resolved from the adapter's `adapterGroupId` via the groups table) as `scopeId` when `msg.scope()` is `ScopeRef.Group`. If group-scope chat is not yet enabled in M1, add an explicit guard that returns a friendly error rather than silently using the wrong ID.
