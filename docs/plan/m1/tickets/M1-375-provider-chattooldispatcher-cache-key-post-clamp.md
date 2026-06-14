---
id: M1-375
title: "provider: key the per-turn chat-tool cache on clamped args so over-cap duplicates do not double-charge the call budget"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The per-turn call cap value and the cap-exceeded ValidationError — unchanged; this only changes what counts as a duplicate call.
  - The per-tool argument clamping rules themselves (e.g. the limit cap) — unchanged; the dispatcher reuses the existing clamp, it does not redefine it.
acceptance:
  - "The per-turn tool cache key is derived from the clamped/canonical arguments (after the same cap the tool applies) rather than the raw pre-clamp args, so two calls to the same tool that differ only in an over-cap value (e.g. limit=200 vs limit=500 both clamping to the same effective limit) resolve to one cache entry and charge turn.callCount once."
  - "A test in infochat-provider/src/test/java/app/zcat/infochat/provider/chat issues two such over-cap-but-equal-after-clamp calls and asserts the second is a cache hit and turn.callCount incremented exactly once."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat (clamp-equal cache-hit test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-375: chat-tool cache key on clamped args

## Context

Deep-review v7 (opus-48) provider finding **F2** (SIMPLIFICATION). Verified at
source 2026-06-14:

`ChatToolDispatcher` (`infochat-provider/.../chat/ChatToolDispatcher.java:141-150`)
builds the per-turn cache key from `canonicalArgs(args)` — the **pre-clamp**
arguments — then increments `turn.callCount` on a cache miss before the tool
clamps an over-cap value. Two calls differing only in an over-cap `limit`
therefore miss the cache and each consume a turn-call-budget slot despite
producing identical results.

Low impact (only matters within a single chat turn where the model issues
redundant over-cap calls) and bounded by the existing call cap, so it is a
correctness-of-accounting nicety, not a security or availability concern. **Not a
beta blocker.**

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The clamp lives in the tool, not the dispatcher; reuse the tool's existing
  clamp (or expose its canonical form) rather than re-implementing the cap in the
  dispatcher.
