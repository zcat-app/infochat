---
id: M1-335
title: "ChatToolDispatcher: deterministic per-turn cache key for nested args"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 2
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatToolDispatcher.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The per-turn call cap and the cache's hit/miss semantics — unchanged; this ticket only makes the key deterministic, it does not change what is cached or when.
  - The tool-arg parser (ChatAgent.toJavaValue) that materializes args — unchanged.
acceptance:
  - "The per-turn cache key is built from a deterministic serialization of args, not new TreeMap<>(args).toString(). TreeMap sorts only the top-level keys; nested HashMap/List values render in implementation-defined iteration order, so two semantically identical tool invocations can currently produce different keys (cache misses). The fix canonicalizes args (e.g. an ObjectMapper configured with ORDER_MAP_ENTRIES_BY_KEYS) so the key for {\"a\":1,\"b\":2} and {\"b\":2,\"a\":1}, including nested objects, is identical."
  - "A test pins determinism: two args maps that are logically equal but differ in nested-map insertion order produce the SAME cache key (a cache hit on the second dispatch). The existing same-turn identical-call caching behavior is preserved."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat (deterministic-key case)
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
---

# M1-335: ChatToolDispatcher — deterministic per-turn cache key

## Context

Deep-review v5.5 (opus-47, `07-module-infochat-provider.md` F3) found that the
per-turn cache key concatenates `new TreeMap<>(args).toString()`, which sorts
only the top-level keys. **Verified at source 2026-06-14:** ChatToolDispatcher.java:
129-130 builds `cacheKey = ... + "|" + new TreeMap<>(args)`. The Jackson tool-arg
parser materializes nested objects as `HashMap` and arrays as `List`; a nested
`HashMap.toString()` iterates in implementation-defined order, so two
semantically identical tool calls can produce different cache keys.

This is a **simplification / correctness-of-intent** issue, not a correctness
defect: the consequence is a cache miss (a wasted DB round-trip), never a wrong
result. No current tool accepts nested-object args, but the parser supports them,
so a future tool would be silently affected. Fixing it now keeps the cache doing
its job.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` is the standard fix for
  hash-map iteration-order non-determinism in cache keys. `ChatAgent` already
  keeps an `ObjectMapper`, so a small static here is consistent.
