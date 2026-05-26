---
id: M1-088
title: "RedditFetcher — subreddit JSON feed"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-086
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherTest.java
  - infochat-collector/src/main/resources/application.properties
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/** — not modified
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java — M1-086 territory
  - any other fetcher package (bluesky/, nitter/, youtube/, odysee/)
  - OAuth client-id/secret configuration — v1 targets the public .json endpoint; if Reddit requires OAuth, escalate
  - any Flyway migration
  - any pre-existing test file
acceptance:
  - "RedditFetcher is an @ApplicationScoped CDI bean implementing Fetcher with kind discriminator \"reddit\""
  - "fetch() appends `.json` to the identifier URL (subreddit) and calls SsrfGuardedHttpClient.get() with a descriptive User-Agent header (Reddit blocks default JDK User-Agent)"
  - "Pagination: `after` parameter (Reddit fullname cursor); fetcher paginates within a single tick up to the profile-driven page cap (design §1.6: 5 on laptop/vps/remote-llm, 2 on pi) or until `after` is null"
  - "NormalizedPost field mapping: upstreamIdentifier = data.name (fullname, e.g. t3_abc123), title = data.title, body = data.selftext (empty string for link-only posts), url = https://www.reddit.com + data.permalink, publishedAt = data.created_utc, rawMetadata includes author + score + num_comments + subreddit"
  - "All posts from one fetch() call share the same fetchedAt timestamp (captured once before the first HTTP call)"
  - "Non-2xx HTTP status throws an unchecked exception caught by FetchScheduler's per-tick error handler"
  - "Empty listing (no children, null after) returns an empty list"
  - "`infochat.fetch.reddit.interval` property is added to collector application.properties (default 15m)"
  - RedditFetcherTest.fetchReturnsParsedPosts passes
  - RedditFetcherTest.fetchPaginatesUpToCap passes
  - RedditFetcherTest.fetchMapsFieldsCorrectly passes
  - RedditFetcherTest.fetchThrowsOnNon2xx passes
  - RedditFetcherTest.fetchHandlesEmptyListing passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D38
---

# M1-088: RedditFetcher — subreddit JSON feed

## Context

This ticket adds the Reddit polled Fetcher implementation. Reddit
exposes subreddit content as JSON by appending `.json` to the
subreddit URL. The identifier is the subreddit URL (e.g.
`https://www.reddit.com/r/security/new`). Depends on M1-086
(polymorphic scheduler dispatch).

## Acceptance

1. **CDI bean.** `@ApplicationScoped`, implements `Fetcher`, kind
   discriminator `"reddit"`.

2. **HTTP via SSRF guard.** Appends `.json` to the identifier URL and
   calls `SsrfGuardedHttpClient.get()`. Sends a descriptive
   `User-Agent` header (Reddit blocks requests with the default JDK
   User-Agent).

3. **Pagination.** `after` parameter (Reddit fullname cursor); the
   fetcher paginates within a single tick up to the page cap or until
   `after` is null in the response.

4. **Field mapping.** JSON listing parsed into `NormalizedPost`:
   - `upstreamIdentifier` → `data.name` (fullname, e.g. `t3_abc123`)
   - `title` → `data.title`
   - `body` → `data.selftext` (empty string for link-only posts)
   - `url` → `https://www.reddit.com` + `data.permalink`
   - `publishedAt` → `data.created_utc` (Unix epoch → Instant)
   - `rawMetadata` → author, score, num_comments, subreddit

5. **fetchedAt invariant.** Captured once before the first HTTP call.

6. **Error handling.** Non-2xx → unchecked exception. Same pattern as
   RssFetcher.

7. **Config.** `infochat.fetch.reddit.interval` added (default 15m).

8. **Tests.** Five test methods. Full `mvn verify` green.

## Out-of-scope

- FetchScheduler dispatch logic (M1-086).
- Other fetcher packages.
- **OAuth configuration** — v1 targets the public `.json` endpoint.
  If Reddit requires OAuth at implementation time, escalate; do not
  silently add OAuth client-id/secret properties.

## Notes

- **Risk: Reddit API access.** Reddit has tightened API access. The
  public `.json` endpoint may return 429 or require OAuth. If the
  endpoint is inaccessible without auth at implementation time,
  escalate — the fetcher's config surface would grow (OAuth
  client-id/secret properties) and the acceptance criteria would need
  revision. The test uses canned JSON fixtures and a mock HTTP server,
  so the test suite is not affected by Reddit's API availability.
- **User-Agent header.** Reddit returns 429 for requests with the
  default JDK `Java-http-client/<version>` User-Agent. Set a
  descriptive header like `infochat/<version> (news aggregator)`.
  Pass via `SsrfGuardedHttpClient.get(URI, Map.of("User-Agent", ...))`.
- **Test pattern.** Follow RssFetcherTest: plain JUnit 5, in-process
  HttpServer, canned JSON under
  `src/test/resources/fixtures/reddit/`.
- **Design reference:** `docs/design/01-architecture.md` §1.6
  (pagination cap: 5 on laptop/vps/remote-llm, 2 on pi).
