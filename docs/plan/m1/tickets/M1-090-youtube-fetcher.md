---
id: M1-090
title: "YouTubeFetcher — channel Atom feed"
status: done
created: 2026-05-26
last_updated: 2026-05-26
clarity_check:
  date: 2026-05-26
  verdict: PASS
  warnings: []
  blockers: []
blocked_by:
  - M1-086
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/youtube/YouTubeFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/youtube/YouTubeFetcherTest.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/resources/fixtures/youtube/youtube-atom-sample.xml
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/** — not modified; RssFeedParser consumed but not changed
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java — M1-086 territory
  - any other fetcher package (bluesky/, reddit/, nitter/, odysee/)
  - any Flyway migration
  - any pre-existing test file
acceptance:
  - "YouTubeFetcher is an @ApplicationScoped CDI bean implementing Fetcher with kind discriminator \"youtube\""
  - "fetch() calls SsrfGuardedHttpClient.get() with the identifier (YouTube channel Atom feed URL, e.g. https://www.youtube.com/feeds/videos.xml?channel_id=UC...) and delegates the response body to RssFeedParser.parse()"
  - "No pagination — YouTube channel Atom feeds are single-request per tick"
  - "NormalizedPost fields are produced by RssFeedParser's Atom 1.0 path: upstreamIdentifier = Atom <id>, title = Atom <title>, body = Atom <content> (video description), url = Atom <link rel=alternate> (video watch URL), publishedAt = Atom <published>"
  - "All posts from one fetch() call share the same fetchedAt timestamp"
  - "Non-2xx HTTP status throws an unchecked exception"
  - "`infochat.fetch.youtube.interval` property is added to collector application.properties (default 30m)"
  - YouTubeFetcherTest.fetchReturnsParsedPosts passes
  - YouTubeFetcherTest.fetchMapsVideoFieldsCorrectly passes
  - YouTubeFetcherTest.fetchThrowsOnNon2xx passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/youtube/YouTubeFetcherTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D38
reviews:
  - round: 1
    date: 2026-05-26
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 321
      removed: 7
  - round: 2
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 341
      removed: 7
---

# M1-090: YouTubeFetcher — channel Atom feed

## Context

This ticket adds the YouTube polled Fetcher implementation. YouTube
exposes channel feeds as Atom 1.0 XML at
`https://www.youtube.com/feeds/videos.xml?channel_id=<id>`. The
identifier is the full feed URL. Since `RssFeedParser` already handles
Atom 1.0, this fetcher delegates parsing directly. Depends on M1-086
(polymorphic scheduler dispatch).

## Acceptance

1. **CDI bean.** `@ApplicationScoped`, implements `Fetcher`, kind
   discriminator `"youtube"`.

2. **HTTP via SSRF guard + parser delegation.** Same pattern as
   RssFetcher and NitterFetcher: GET the identifier URL, delegate to
   `RssFeedParser.parse()`.

3. **No pagination.** YouTube channel Atom feeds are single-request.

4. **Field mapping.** RssFeedParser's Atom 1.0 path produces the
   correct fields: video title in `<title>`, description in
   `<content>`, watch URL in `<link rel="alternate">`, Atom `<id>` as
   upstream identifier.

5. **Config.** `infochat.fetch.youtube.interval` added (default 30m —
   video uploads are infrequent).

6. **Tests.** Three test methods. Full `mvn verify` green.

## Out-of-scope

- RssFeedParser — consumed, not modified.
- Other fetcher packages.
- FetchScheduler dispatch logic (M1-086).

## Notes

- **YouTube Atom specifics.** YouTube's Atom `<id>` is typically
  `yt:video:<videoId>`, which is a stable upstream identifier. The
  `<link rel="alternate">` points to the watch page
  (`https://www.youtube.com/watch?v=<videoId>`). RssFeedParser's
  existing Atom handling covers this without YouTube-specific code.
- **Test fixture.** Use a YouTube-style Atom fixture under
  `src/test/resources/fixtures/youtube/`. Capture a real feed sample
  for fixture realism.
- **Structural near-duplicate.** YouTubeFetcher, NitterFetcher, and
  OdyseeFetcher all follow the same GET → RssFeedParser.parse()
  pattern. Keep each standalone; the duplication is ~30 lines per
  fetcher, well below the abstraction threshold.

## Round 1 rework

1. **SCOPE-DRIFT**: Test fixture `youtube-atom-sample.xml` was outside `files_scope`. Added fixture path to `files_scope` frontmatter (reviewer-directed correction; the ticket body already anticipated this fixture).
2. **PARAMETER-CONTRACT**: Add `@NonNull` annotations to `YouTubeFetchException` constructor parameters (`message`, `cause`).
