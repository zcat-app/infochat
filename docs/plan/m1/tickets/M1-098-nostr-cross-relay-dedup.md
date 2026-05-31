---
id: M1-098
title: "Nostr cross-relay dedup"
status: done
created: 2026-05-26
last_updated: 2026-05-31
blocked_by:
  - M1-096
  - M1-097
files_budget: 7
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrDedupFilter.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupFilterTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrDedupIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
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
  modifies:
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
      why_safe: |
        M1-098 threads NostrDedupFilter into NostrStreamSource via ctor
        injection, mirroring M1-097's NostrEventVerifier addition (Registrar
        remains the single per-source collaborator construction site). The
        four `new NostrStreamSource(...)` call sites (lines 58, 72, 95, 124)
        gain a 7th argument; no assertion weakened, no test disabled, no
        gate bypassed. Test intent (connectsToAllConfiguredRelays,
        receivesAndDeliversEvents, reconnectsWithSinceOnDisconnect,
        stopDrainsAndClosesConnections) preserved verbatim.
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceIT.java
      why_safe: |
        Same root cause: endToEndWithFakeRelay's single ctor call site
        (line 86) gains the 7th argument. End-to-end persist + eval-queue
        emit assertions unchanged.
    - path: infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceVerificationIT.java
      why_safe: |
        Same root cause: VerificationIT's single ctor call site (line 168)
        gains the 7th argument. Verification assertions (failed-sig
        counter, kind allowlist behavior) unchanged.
  preserves:
    - all tests currently green on main, with the three modified files
      updated per the modifies block above (only the ctor argument list
      changes)
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D38
reviews:
  - round: 1
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 347
      removed: 19
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-31
  verdict: PASS
  warnings: []
  blockers: []
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
- **Constructor integration.** NostrDedupFilter is constructor-injected
  by Registrar, one per source (dedup state is per-publisher),
  mirroring M1-097's NostrEventVerifier injection. The asymmetry
  between the verifier (shared, stateless — Registrar field) and the
  filter (per-source, stateful — constructed inside the source-row
  loop) lives at the Registrar construction sites, not in the
  NostrStreamSource ctor shape. The 7th ctor argument is the price of
  that symmetry, and cascades mechanically into the three existing
  test ctor call sites authorized in `test_plan.modifies`.
- **Window configuration.** The dedup window size is a hardcoded
  constant on NostrDedupFilter for v1 (~10K event ids, ~640KB — well
  under any profile's RAM budget). Profile-driven configuration via
  `@ConfigProperty` would require a property key in
  `application.properties` (not in `files_scope`); defer to a
  follow-up ticket if a profile ever needs a different bound.
