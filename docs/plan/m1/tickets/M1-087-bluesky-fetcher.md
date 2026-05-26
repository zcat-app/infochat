---
id: M1-087
title: "BlueskyFetcher — AT Protocol polled feed"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-086
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcherTest.java
  - infochat-collector/src/main/resources/application.properties
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/** — not modified
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java — M1-086 territory
  - any other fetcher package (reddit/, nitter/, youtube/, odysee/)
  - any Flyway migration
  - any pre-existing test file
acceptance:
  - "BlueskyFetcher is an @ApplicationScoped CDI bean implementing Fetcher with kind discriminator \"bluesky\""
  - "fetch() calls the Bluesky public API (`app.bsky.feed.getAuthorFeed`) via SsrfGuardedHttpClient, using the identifier as the actor parameter"
  - "Pagination: cursor-based (`cursor` query parameter); fetcher paginates within a single tick up to the profile-driven page cap (design §1.6: 5 on laptop/vps/remote-llm, 2 on pi) or until no cursor is returned"
  - "NormalizedPost field mapping: upstreamIdentifier = post.uri (AT URI), title = null, body = post.record.text, url = constructed Bluesky web URL, publishedAt = post.indexedAt, rawMetadata includes handle + displayName + likeCount + repostCount"
  - "All posts from one fetch() call share the same fetchedAt timestamp (captured once before the first HTTP call)"
  - "Non-2xx HTTP status throws an unchecked exception caught by FetchScheduler's per-tick error handler"
  - "Empty feed (no posts, no cursor) returns an empty list"
  - "`infochat.fetch.bluesky.interval` property is added to collector application.properties (default 10m)"
  - BlueskyFetcherTest.fetchReturnsParsedPosts passes
  - BlueskyFetcherTest.fetchPaginatesUpToCap passes
  - BlueskyFetcherTest.fetchMapsFieldsCorrectly passes
  - BlueskyFetcherTest.fetchThrowsOnNon2xx passes
  - BlueskyFetcherTest.fetchHandlesEmptyFeed passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcherTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/security.md §SSRF and outbound connections
decision_refs:
  - D38
---

# M1-087: BlueskyFetcher — AT Protocol polled feed

## Context

This ticket adds the Bluesky polled Fetcher implementation. Bluesky
exposes public feeds via the AT Protocol
(`app.bsky.feed.getAuthorFeed`); the identifier is the DID or handle
of the account to follow. This is one of five polled fetchers in T3-B,
each following the RssFetcher pattern. Depends on M1-086 (polymorphic
scheduler dispatch).

## Acceptance

1. **CDI bean.** `@ApplicationScoped`, implements `Fetcher`, kind
   discriminator `"bluesky"`.

2. **HTTP via SSRF guard.** Calls `SsrfGuardedHttpClient.get()` to
   fetch the Bluesky public API endpoint. No authentication required
   for public feeds in v1.

3. **Pagination.** Cursor-based: each response may include a `cursor`
   field; the fetcher re-requests with `cursor=<value>` until no
   cursor is returned or the per-tick page cap is reached
   (profile-driven per design §1.6).

4. **Field mapping.** JSON response parsed into `NormalizedPost`:
   - `upstreamIdentifier` → `post.uri` (AT URI)
   - `title` → null (Bluesky posts have no title)
   - `body` → `post.record.text`
   - `url` → constructed web URL
     (`https://bsky.app/profile/<handle>/post/<rkey>`)
   - `publishedAt` → `post.indexedAt`
   - `rawMetadata` → handle, displayName, likeCount, repostCount

5. **fetchedAt invariant.** Captured once before the first HTTP call;
   shared across all posts in the batch.

6. **Error handling.** Non-2xx → unchecked exception. IOException →
   unchecked exception. Same pattern as RssFetcher.

7. **Config.** `infochat.fetch.bluesky.interval` added to collector
   `application.properties` (default 10m).

8. **Tests.** Five test methods (happy path, pagination cap, field
   mapping, non-2xx error, empty feed). Full `mvn verify` green.

## Out-of-scope

- FetchScheduler dispatch logic (M1-086).
- Other fetcher packages.
- RssFetcher / RssFeedParser — not modified.

## Notes

- **Test pattern.** Follow RssFetcherTest: plain JUnit 5 with
  in-process `HttpServer` on loopback, `LoopbackPermittingBlocklist`,
  constructor-injected `SsrfGuardedHttpClient`. Test fixture: a canned
  JSON response file under
  `src/test/resources/fixtures/bluesky/`.
- **JSON parsing.** Use Jackson `ObjectMapper` (already on the
  classpath via Quarkus). BlueskyResponseParser is a stateless class
  with a static `parse()` method, parallel to `RssFeedParser.parse()`.
- **Bluesky public API base URL.** The canonical public endpoint is
  `https://public.api.bsky.app/xrpc/`. The identifier is passed as
  the `actor` query parameter to `app.bsky.feed.getAuthorFeed`.
- **Design reference:** `docs/design/01-architecture.md` §1.3.1
  (polled Fetcher flow), §1.6 (pagination cap: 5 on
  laptop/vps/remote-llm, 2 on pi).
