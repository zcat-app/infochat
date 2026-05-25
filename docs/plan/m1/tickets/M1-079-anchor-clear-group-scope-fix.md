---
id: M1-079
title: "Summary anchor clear: use group scopeId for group scope"
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: [M1-069]
remediates: M1-069
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
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
  - docs/spec/commands.md §/retry
  - docs/spec/architecture.md §Architectural principles
decision_refs: []
---

## Context

Redteam finding on M1-069: `summaryAnchorRepository.clear(anchorActorId, anchorActorId)` at InboundRouter step 4.6 passes `actorId` as both userId and scopeId. For group scope, the second argument should be the group UUID. After M1-069 fixed the chat-mode dispatch to use the correct group UUID as scopeId, the anchor-clear became inconsistently wrong — a group-scope message silently clears the DM-scope anchor and never clears the group-scope anchor.

## Fix approach

Replace the second argument to `summaryAnchorRepository.clear()` with `resolveChatScopeId(msg.scope(), anchorActorId, adapterName)`, reusing the method M1-069 added.
