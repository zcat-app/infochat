---
id: M1-089
title: "NitterFetcher — Nitter instance RSS feed"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-086
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcherTest.java
  - infochat-collector/src/main/resources/application.properties
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/** — not modified; RssFeedParser consumed but not changed
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java — M1-086 territory
  - any other fetcher package (bluesky/, reddit/, youtube/, odysee/)
  - any Flyway migration
  - any pre-existing test file
acceptance:
  - "NitterFetcher is an @ApplicationScoped CDI bean implementing Fetcher with kind discriminator \"nitter\""
  - "fetch() calls SsrfGuardedHttpClient.get() with the identifier (full Nitter RSS URL, e.g. https://nitter.example.com/username/rss) and delegates the response body to RssFeedParser.parse()"
  - "No pagination — Nitter RSS feeds are single-request per tick"
  - "NormalizedPost fields are produced by RssFeedParser (RSS 2.0 or Atom 1.0); NitterFetcher adds no post-parse normalization beyond what the parser provides"
  - "All posts from one fetch() call share the same fetchedAt timestamp"
  - "Non-2xx HTTP status throws an unchecked exception"
  - "`infochat.fetch.nitter.interval` property is added to collector application.properties (default 10m)"
  - NitterFetcherTest.fetchDelegatesToRssFeedParser passes
  - NitterFetcherTest.fetchReturnsParsedPosts passes
  - NitterFetcherTest.fetchThrowsOnNon2xx passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcherTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D38
---

# M1-089: NitterFetcher — Nitter instance RSS feed

## Context

This ticket adds the Nitter polled Fetcher implementation. Nitter
instances expose RSS feeds at `/<username>/rss`. The identifier is the
full Nitter RSS URL. Since Nitter serves standard RSS/Atom XML, this
fetcher delegates parsing to the existing `RssFeedParser`. Depends on
M1-086 (polymorphic scheduler dispatch).

## Acceptance

1. **CDI bean.** `@ApplicationScoped`, implements `Fetcher`, kind
   discriminator `"nitter"`.

2. **HTTP via SSRF guard + parser delegation.** `fetch()` issues
   `SsrfGuardedHttpClient.get(URI.create(identifier))`, then
   delegates the response body to `RssFeedParser.parse(sourceId,
   body, fetchedAt)`. Same pattern as RssFetcher.

3. **No pagination.** Nitter RSS feeds are single-request. No cursor
   or page parameter.

4. **Field mapping.** Produced by RssFeedParser — NitterFetcher adds
   no post-parse normalization. HTML artifacts in RSS content are
   preserved for Stage 1 sanitization downstream.

5. **Config.** `infochat.fetch.nitter.interval` added (default 10m).

6. **Tests.** Three test methods. Full `mvn verify` green.

## Out-of-scope

- RssFeedParser — consumed, not modified.
- Other fetcher packages.
- FetchScheduler dispatch logic (M1-086).

## Notes

- **Pattern duplication with RssFetcher.** NitterFetcher and
  RssFetcher are structurally near-identical: both call
  SsrfGuardedHttpClient.get() and delegate to RssFeedParser. The
  difference is the kind discriminator (`"nitter"` vs `"rss"`) and the
  per-kind interval. Three similar lines beats a premature abstraction
  — keep each fetcher standalone per CLAUDE.md §Simplify aggressively.
- **Test fixture.** Use a Nitter-style RSS fixture under
  `src/test/resources/fixtures/nitter/`. The fixture can be a
  standard RSS 2.0 file since RssFeedParser handles both formats.
- **Nitter instance availability.** The operator configures the Nitter
  instance URL in bootstrap-sources.json. The fetcher is agnostic to
  which instance is used.
