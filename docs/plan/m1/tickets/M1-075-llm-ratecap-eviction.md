---
id: M1-075
title: InboundRouter LLM rate-cap map eviction + body-size optimization
status: done
created: 2026-05-25
last_updated: 2026-05-25
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
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
  modifies:
    - "InboundRouterTest.resetAdapterState @BeforeEach — upsert alice instead of DELETE+INSERT to avoid audit_log FK violation exposed by adding @Scheduled to InboundRouter (changes CDI bean graph → shifts surefire test class ordering → audit_log entries from prior test classes now precede InboundRouterTest)"
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs: []
reviews:
  - round: 1
    date: 2026-05-25
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: FAIL
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 111
      removed: 16
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
      added: 128
      removed: 14
---

## Context

`InboundRouter.llmCallTimestamps` (ConcurrentHashMap<UUID, Deque<Long>>) grows unboundedly — entries are never removed, only their timestamps are pruned. Over time this leaks memory proportional to total unique chat-mode users. The analogous `RateCapBucket` has a `@Scheduled` eviction sweep that this map lacks.

Additionally, the inbound body-size check allocates a full `byte[]` via `String.getBytes(UTF_8)` on every message passing the rate cap, creating GC pressure under sustained load. A counting loop with early exit avoids the allocation entirely.

## Fix approach

1. Add a `@Scheduled` sweep (same pattern as `RateCapBucket.evictIdleBuckets`) that removes entries whose deque is empty after pruning timestamps older than 2x the window.
2. Replace `raw.getBytes(StandardCharsets.UTF_8).length > maxInboundBodyBytes` with a `utf8ByteLength(String)` counting method that short-circuits on exceeding the cap.

## Round 1 rework

1. Revert the modifications to the pre-existing `@BeforeEach resetAdapterState()` method in `InboundRouterTest.java`. The comment shortening, DELETE query split, and `ON CONFLICT DO NOTHING` → `ON CONFLICT DO UPDATE SET` change are both a §1 (surgical changes) violation and a §8 (test-modification authorization) violation. If the latent test-setup bug (audit_log FK preventing alice deletion) needs fixing, file a follow-up ticket.
