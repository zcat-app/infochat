---
id: M1-080b
title: DigestWorker + degraded fallback + subscription-version cache
status: done
created: 2026-05-25
last_updated: 2026-05-26
reviews:
  - round: 1
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 1165
      removed: 10
clarity_check:
  date: 2026-05-26
  verdict: WARN
  warnings:
    - "SELF-CONTAINED-CHECK: Acceptance item 1 references 'BundleLoader 2-arg form' without identifying the class's module or package"
    - "SELF-CONTAINED-CHECK: Acceptance item 7 conflates the full cache contract (read + write) with this ticket's scope (write only)"
blocked_by:
  - M1-080a
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DegradedDigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
outline_file: target/m1-tick-outline-M1-080b.md
out_of_scope:
  - infochat-core/src/main/resources/db/migration/** — no migration (V21 is M1-080a)
  - infochat-messaging-adapter/** — digest delivery uses the existing adapter.sendToGroup SPI
  - any modification to DigestScheduler.java — M1-080a territory
  - any modification to SummaryCacheRepository.java — M1-080a; DigestWorker calls it but doesn't modify the class
  - any /retry --digest command — M1-080c
  - any ThrottledAdminNotifier integration — M1-080c
  - M1-080 umbrella's DigestRoundtripIT.java
  - any modification to any pre-existing test
acceptance:
  - "DigestWorker.execute(DigestSlot slot) collects eligible posts for the group (posts matching the group's active tag subscriptions + source subscriptions, arrived since the last digest), generates prose via the LLM summarizer (using the group's configured language via BundleLoader 2-arg form), writes the result to summary_cache with the current tag_subscription_version and source_subscription_version, and sends the digest message to the group via the adapter"
  - "DigestPostCollector.collectForGroup(long groupId, Instant since) returns the list of posts matching the group's active subscriptions since the given timestamp; uses the per-scope subscription versions from scope_preferences to determine active tags and sources"
  - "When no eligible posts exist (zero-eligible-posts case), DigestWorker sends the 'no posts yet' fixed reply from the localization bundle (same key as /summary empty-window) and writes a cache row with is_degraded=false and the fixed content"
  - "DigestRenderer.render(List<Post> posts, String langCode) calls the LLM summarizer (via the M1-037 SummaryService SPI or equivalent) with the group's language code, producing localized digest prose"
  - "DegradedDigestRenderer.render(List<Post> posts) produces headlines + source attribution only (no LLM call) — the overload fallback per spec"
  - "When the slot window-end is reached and the LLM call has not returned, DigestWorker falls back to DegradedDigestRenderer, writes the degraded result to summary_cache with is_degraded=true, and sends it to the group"
  - "subscription-version-keyed cache: the cache row stores tag_subscription_version and source_subscription_version at write time; a /summary request hitting the cache validates that the cached versions match the current scope_preferences versions (cache miss if versions differ) — the validation logic lives in the find method (M1-080a's SummaryCacheRepository.findByGroupAndSlot returns Optional.empty() when versions mismatch)"
  - DigestWorkerTest.execute_generatesProseAndCaches passes
  - DigestWorkerTest.execute_sendsDigestToGroup passes
  - DigestWorkerTest.execute_zeroPosts_sendsFixedReply passes
  - DigestWorkerTest.execute_llmTimeout_fallsToDegraded passes
  - DigestWorkerTest.execute_writesSubscriptionVersions passes
  - DigestRendererTest.render_producesLocalizedProse passes
  - DegradedDigestRendererTest.render_producesHeadlinesOnly passes
  - DigestPostCollectorTest.collectForGroup_filtersOnActiveSubscriptions passes
  - DigestPostCollectorTest.collectForGroup_returnsEmptyWhenNoSubscriptions passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DegradedDigestRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/schema.md §Operational
decision_refs:
  - D16
  - D17
---

# M1-080b: DigestWorker + degraded fallback + subscription-version cache

## Context

The DigestWorker is the engine that generates periodic digest content.
When the DigestScheduler (M1-080a) fires a slot, the worker collects
eligible posts for the group, generates LLM prose in the group's
language, caches the result (keyed by subscription versions), and
delivers the digest message. If the LLM doesn't respond within the
slot window, the worker falls back to a degraded headlines-only
rendering per spec.

The spec contract is `docs/spec/commands.md` §Periodic group digests
(degraded-fallback exit, zero-eligible-posts, subscription-version
cache) + `docs/spec/llm.md` §Per-task routing rules (summarizer is
language-aware).

## Acceptance

1. `DigestWorker.execute(DigestSlot)` is the complete pipeline:
   collect posts → render → cache → deliver.
2. `DigestPostCollector` queries posts matching the group's active
   subscriptions since the last digest.
3. Zero-eligible-posts sends a fixed "no posts yet" reply.
4. `DigestRenderer` calls the LLM with the group's language.
5. `DegradedDigestRenderer` produces headlines + sources without an
   LLM call.
6. On LLM timeout (window-end reached), the worker falls back to
   degraded rendering.
7. The cache row stores subscription versions; a subsequent cache
   lookup with different versions produces a miss.
8. All tests pass; `mvn verify` is green.

## Out-of-scope

- DigestScheduler firing logic (M1-080a).
- /retry --digest (M1-080c).
- ThrottledAdminNotifier for failed digests (M1-080c).
- The umbrella IT (M1-080).
- Any pre-existing test modification.

## Notes

- The LLM call uses the same `SummaryService` SPI (or equivalent)
  from M1-037's summary command, but with the group's language code
  from `scope_preferences.language` (read via the 2-arg
  `BundleLoader.get(key, langCode)` form for bundle strings, and
  the language-aware summarizer prompt per `docs/spec/llm.md`).
- The degraded fallback per spec "does not affect any subsequent
  slot" — each slot decides its mode independently.
- The subscription-version check ensures that if an admin changes
  tag/source subscriptions between the digest and a subsequent
  `/summary` cache-hit attempt, the stale cache is not served.
- `DigestWorker` does NOT use `ThrottledAdminNotifier` directly —
  that wiring is M1-080c's scope. The worker reports success/failure
  via return value or CDI event.
- Tests mock the LLM and adapter layers; `DigestWorkerTest` drives
  the full pipeline with a fake LLM returning deterministic prose.
