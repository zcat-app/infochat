---
id: M1-075
title: InboundRouter LLM rate-cap map eviction + body-size optimization
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatModeIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (07-module-infochat-provider.md#F3, 01-architecture.md#F4)
out_of_scope:
  - any RateCapBucket change — that class already has correct eviction
  - any chat-mode routing change — M1-069 territory
  - any new rate-limit configuration property beyond the sweep interval
acceptance:
  - "A @Scheduled eviction sweep removes llmCallTimestamps entries whose deque is empty after pruning timestamps older than 2x the rate-cap window. Verify: InboundRouterTest.llmRateCapEvictsIdleEntries passes"
  - "The body-size check uses a counting loop (no byte[] allocation) with early exit when cap is exceeded. Verify: InboundRouterTest.bodySizeCheckDoesNotAllocateArray passes (or: inspection confirms no getBytes call)"
  - "mvn -pl infochat-provider verify is green"
test_plan:
  adds:
    - InboundRouterTest.llmRateCapEvictsIdleEntries (new)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs: []
---

## Context

`InboundRouter.llmCallTimestamps` (ConcurrentHashMap<UUID, Deque<Long>>) grows unboundedly — entries are never removed, only their timestamps are pruned. Over time this leaks memory proportional to total unique chat-mode users. The analogous `RateCapBucket` has a `@Scheduled` eviction sweep that this map lacks.

Additionally, the inbound body-size check allocates a full `byte[]` via `String.getBytes(UTF_8)` on every message passing the rate cap, creating GC pressure under sustained load. A counting loop with early exit avoids the allocation entirely.

## Fix approach

1. Add a `@Scheduled` sweep (same pattern as `RateCapBucket.evictIdleBuckets`) that removes entries whose deque is empty after pruning timestamps older than 2x the window.
2. Replace `raw.getBytes(StandardCharsets.UTF_8).length > maxInboundBodyBytes` with a `utf8ByteLength(String)` counting method that short-circuits on exceeding the cap.
