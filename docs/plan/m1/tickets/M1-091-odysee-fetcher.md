---
id: M1-091
title: "OdyseeFetcher — Odysee/LBRY RSS feed"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-086
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/odysee/OdyseeFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/odysee/OdyseeFetcherTest.java
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
  - any other fetcher package (bluesky/, reddit/, nitter/, youtube/)
  - LBRY SDK claim_search API path — v1 uses the RSS endpoint only
  - any Flyway migration
  - any pre-existing test file
acceptance:
  - "OdyseeFetcher is an @ApplicationScoped CDI bean implementing Fetcher with kind discriminator \"odysee\""
  - "fetch() calls SsrfGuardedHttpClient.get() with the identifier (Odysee channel RSS URL, e.g. https://odysee.com/$/rss/@ChannelName) and delegates the response body to RssFeedParser.parse()"
  - "No pagination — Odysee RSS feeds are single-request per tick"
  - "NormalizedPost fields are produced by RssFeedParser (RSS 2.0); OdyseeFetcher adds no post-parse normalization"
  - "All posts from one fetch() call share the same fetchedAt timestamp"
  - "Non-2xx HTTP status throws an unchecked exception"
  - "`infochat.fetch.odysee.interval` property is added to collector application.properties (default 30m)"
  - OdyseeFetcherTest.fetchReturnsParsedPosts passes
  - OdyseeFetcherTest.fetchDelegatesToRssFeedParser passes
  - OdyseeFetcherTest.fetchThrowsOnNon2xx passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/odysee/OdyseeFetcherTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D38
---

# M1-091: OdyseeFetcher — Odysee/LBRY RSS feed

## Context

This ticket adds the Odysee polled Fetcher implementation. Odysee
exposes channel feeds as RSS 2.0 at
`https://odysee.com/$/rss/@ChannelName`. The identifier is the full
RSS URL. Since `RssFeedParser` handles RSS 2.0, this fetcher delegates
parsing directly. Depends on M1-086 (polymorphic scheduler dispatch).

## Acceptance

1. **CDI bean.** `@ApplicationScoped`, implements `Fetcher`, kind
   discriminator `"odysee"`.

2. **HTTP via SSRF guard + parser delegation.** Same pattern as
   RssFetcher, NitterFetcher, YouTubeFetcher: GET the identifier URL,
   delegate to `RssFeedParser.parse()`.

3. **No pagination.** Odysee RSS feeds are single-request.

4. **Field mapping.** RssFeedParser's RSS 2.0 path produces the
   fields. Video title in `<title>`, description in `<description>`,
   video URL in `<link>`, RSS `<guid>` as upstream identifier.

5. **Config.** `infochat.fetch.odysee.interval` added (default 30m —
   video uploads are infrequent).

6. **Tests.** Three test methods. Full `mvn verify` green.

## Out-of-scope

- RssFeedParser — consumed, not modified.
- Other fetcher packages.
- FetchScheduler dispatch logic (M1-086).
- **LBRY SDK `claim_search` API path** — v1 uses the RSS endpoint.
  A future ticket may add the JSON API path for richer metadata or
  pagination.

## Notes

- **Odysee RSS availability.** The `$/rss/@<channel>` endpoint is
  publicly accessible. If Odysee changes the URL scheme, the operator
  updates the identifier in bootstrap-sources.json — the fetcher is
  URL-agnostic.
- **Test fixture.** Use an Odysee-style RSS 2.0 fixture under
  `src/test/resources/fixtures/odysee/`.
- **Structural near-duplicate.** Same pattern as NitterFetcher and
  YouTubeFetcher. Keep standalone per CLAUDE.md §Simplify
  aggressively.
