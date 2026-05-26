---
id: M1-098
title: "Nostr cross-relay dedup"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-096
files_budget: 4
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrDedupFilter.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupFilterTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-provider/** — no provider changes
  - signature verification logic — M1-097 is frozen
  - per-relay degradation — M1-099
  - kind-6 linking — M1-100
  - SSRF on wss:// — M1-101
  - any change to the post table or outbox schema
acceptance:
  - "NostrDedupFilter deduplicates events by upstream_identifier (Nostr event id) before outbox delivery — same event from N relays produces 1 outbox entry"
  - "The dedup window is bounded (e.g. a time-windowed or size-bounded set of recently-seen event ids) so memory usage is bounded"
  - "Dedup runs after signature verification and kind filter, before outbox write"
  - "NostrDedupFilterTest.sameEventFromTwoRelays_onlyOneDelivered passes — an event id delivered by relay A and then relay B results in one deliver callback invocation"
  - "NostrDedupFilterTest.distinctEvents_bothDelivered passes — two events with different ids from the same relay are both delivered"
  - "NostrDedupFilterTest.windowEviction_allowsRedelivery passes — after the dedup window expires for an event id, the same id from a different relay is delivered (not suppressed)"
  - "NostrDedupIT.multiRelayDedup passes — a FakeNostrRelay cluster of 2 relays sends the same event; only one post row is created in the outbox"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupFilterTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D38
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-098: Nostr cross-relay dedup

## Context

D38 and `architecture.md` §Ingest SPIs commit to: "Cross-source dedup
is the implementation's responsibility, not the outbox's. For stream
sources where the same event can arrive from N relays (Nostr), the
implementation MUST dedup by stable upstream id before enqueue; one
event = one posts row regardless of how many relays delivered it."

## Acceptance

See frontmatter. The dedup filter sits between
verification+kind-filter and the outbox deliver callback.

## Out-of-scope

- Signature verification — M1-097.
- Per-relay degradation — M1-099.
- Kind-6 linking — M1-100.

## Notes

- **Dedup data structure.** A `LinkedHashMap` with access-order eviction
  (LRU) or a Caffeine time-windowed cache are both acceptable. The
  window size/duration is profile-driven. The key is the event id
  (hex string, 64 chars). Memory is bounded: 10K event ids at 64 bytes
  each is ~640KB.
- **Thread safety.** Multiple relay connections deliver events
  concurrently. The dedup filter must be thread-safe. A
  `ConcurrentHashMap.putIfAbsent` pattern or synchronized cache is
  sufficient.
- **Ordering.** The dedup filter is stateless across restarts. On
  restart, the `since=last_persisted_event_at` reconnect may re-deliver
  events already in the outbox. The outbox's own dedup (by
  `upstream_identifier` UNIQUE constraint on the post table) handles
  this; the in-memory filter is a performance optimization to avoid
  redundant DB writes, not a correctness requirement.
