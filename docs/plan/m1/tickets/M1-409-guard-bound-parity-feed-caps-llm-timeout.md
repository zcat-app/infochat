---
id: M1-409
title: "collector+llm: close two guard-asymmetry gaps (feed item caps, timeout-ms startup validation)"
status: done
created: 2026-06-20
last_updated: 2026-06-20
blocked_by: []
files_budget: 10
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - RssFeedParser — unchanged; it is the reference cap (MAX_ITEMS) the two paginating parsers are brought into parity with.
  - The per-page pagination cap (pageCap) and the SSRF 5 MiB body cap — unchanged; this ticket adds a per-response item-count cap, it does not add a new cross-page total cap (see Notes).
  - The base-url startup validation (requireHttpBaseUrl) — unchanged; this ticket adds the sibling timeout-ms validation at the same boundary.
  - The Stage2Worker retry/INFRA_FAILURE classification — unchanged; the fix moves the timeout-ms error to startup so it never reaches that path.
acceptance:
  - "BlueskyResponseParser and RedditResponseParser enforce a per-response item-count cap consistent with RssFeedParser's MAX_ITEMS, throwing the parser's existing parse-failure exception type when a single response exceeds it."
  - "A test per parser (under infochat-collector/src/test/.../fetcher/bluesky and .../fetcher/reddit) asserts a response carrying more than the cap is rejected with that parse-failure exception, and a response at or below the cap parses normally."
  - "A non-positive timeout-ms for any LLM task (chat or embedding) is rejected at the startup resolvability scan — the same boundary that already validates base-url — with an error naming the offending property, so it can no longer reach HttpRequest.Builder.timeout on a live call."
  - "OpenAiCompatibleProvider, AnthropicProvider, and OpenAiCompatibleEmbeddingProvider all validate timeout-ms positivity at that startup boundary; a test (under infochat-llm-adapter/src/test/.../impl) asserts startup validation rejects timeout-ms <= 0, mirroring the existing base-url startup-validation test."
  - "Existing parser tests, the three provider tests, and the startup-scan tests remain green."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky (per-response item cap)
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit (per-response item cap)
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl (timeout-ms startup validation)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 230
      removed: 10
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-20
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-409: close two guard-asymmetry gaps (feed item caps, timeout-ms startup validation)

## Context

Deep-review full (2026-06-20) cross-cutting theme **CT1**: an established,
documented guard exists on one code path but is absent on a structurally parallel
sibling path that handles equal or greater untrusted input. This ticket fixes the
two **low**-severity CT1 instances (the medium CT1 instance, provider F1, is its own
ticket M1-407). Both verified at source 2026-06-20.

**(a) collector F1 (PERFORMANCE, low) — feed item-count cap parity.**
`RssFeedParser` caps items per response at `MAX_ITEMS = 1000` and throws on exceed.
The paginating `BlueskyResponseParser` and `RedditResponseParser` have no equivalent
cap — they accumulate every item in the response, bounded only by the SSRF 5 MiB
body cap. Because these fetchers paginate up to `pageCap` (default 5), the per-tick
in-memory worst case is materially larger than the single-GET RSS path the explicit
cap was written to protect. Not exploitable (the body cap bounds it absolutely), but
a defense-in-depth consistency gap.

**(b) llm-adapter F1 (RULES-DRIFT, low) — timeout-ms startup validation parity.**
`base-url` is validated at the startup resolvability scan via
`LlmHttpSupport.requireHttpBaseUrl`. The sibling `timeout-ms` property is read but
never validated: a non-positive value passes the scan (which resolves config but
never builds an `HttpRequest`) and only throws `IllegalArgumentException` from
`HttpRequest.Builder.timeout` on the first live call — where `Stage2Worker.tryOnce`
catches it as a `RuntimeException`, retries once, and resolves it to
`INFRA_FAILURE`. A boot-time misconfiguration is thus silently degraded into a
recurring "transient outage" on the live Stage 2 path, defeating the purpose of the
deliberately-added config-boundary guard on the sibling property.

## Acceptance

See frontmatter. (a) Give the two paginating parsers the same per-response item cap
RSS enforces. (b) Validate `timeout-ms` positivity at the same startup boundary that
validates `base-url`, across all three HTTP providers.

## Out-of-scope

See frontmatter. RSS parser, pagination cap, body cap, base-url validation, and the
Stage2Worker classification are all unchanged.

## Notes

- This is a deliberately themed two-module sweep, not a refactor: each half is a
  small local fix on its own path. The CT1 synthesizer note suggested a deeper
  structural fix (move each obligation into a shared seam siblings cannot bypass — a
  per-response item-cap helper used by every parser, and a full-TaskConfig boundary
  validator). That is recorded here as an `Alternatives considered:` direction; this
  ticket intentionally does the per-path fix and does not build the shared seams
  (avoids scope expansion). File a follow-up if the seams are wanted.
- Scope boundary for (a): the cap is per-response (matching RSS, which is single-GET).
  The cross-page accumulated total remains bounded by cap x pageCap; adding a new
  cross-page total cap is explicitly out of scope.
</content>
