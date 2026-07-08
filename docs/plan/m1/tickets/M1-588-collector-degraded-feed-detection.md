---
id: M1-588
title: "Collector: a degraded placeholder feed (xcancel 'not whitelisted' stub) is a Fetcher failure, not a successful ingest"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcherTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Generalizing degraded-feed detection to the other fetch kinds (rss, bluesky,
    youtube, odysee) or into the shared RssFeedParser. This ticket scopes to the
    OBSERVED xcancel/nitter placeholder sentinel only; a generic degraded-feed
    framework is a separate design decision. Do NOT touch RssFeedParser (shared
    with rss sources) — inspect the parsed result inside NitterFetcher instead.
  - >-
    Operator-side xcancel whitelisting (emailing rss@xcancel.com with the reader
    ID to restore real content) and any X-bridge swap — external/operational, not
    code.
  - >-
    Purging the already-ingested placeholder posts. The 27 stubs were deleted
    out-of-band on 2026-07-08 (post + post_embedding + post_entity); this ticket
    prevents re-ingestion, it does not do data cleanup.
  - >-
    The D42 failure-counter mechanism and FetchScheduler itself. They are reused
    UNCHANGED — this ticket only makes NitterFetcher SIGNAL a failure (throw) for
    a degraded feed so the existing D42 per-source counter fires.
  - >-
    Retroactive source-health repair / auto re-enable of sources already marked
    'failed'. D42 keeps re-enable an explicit admin action.
acceptance:
  - >-
    When a nitter fetch yields the xcancel "RSS reader not yet whitelisted!"
    placeholder — a feed whose (sole) item is xcancel's whitelist sentinel (item
    title equals "RSS reader not yet whitelisted!" / body carries the "send an
    email rss@xcancel.com ... to get your RSS feed reader whitelisted" notice) —
    NitterFetcher.fetch() treats it as a Fetcher failure per D42: it throws
    NitterFetchException instead of returning the stub as a NormalizedPost. So
    FetchScheduler runs the D42 per-source failure-counter update: NOTHING is
    ingested, last_success_at is NOT advanced, consecutive_failures increments,
    and after the profile's N consecutive failures the source transitions to
    status='failed' (existing D42 mechanism, unchanged) — surfacing the dead feed
    instead of silently reporting it healthy.
  - >-
    A normal nitter feed (real tweets) is unaffected: NitterFetcher returns the
    parsed NormalizedPosts exactly as today. The sentinel match is EXACT (the
    xcancel stub title / notice), so a legitimate post that merely mentions the
    word "whitelist" is never dropped.
  - >-
    NAMED TEST: NitterFetcherTest gains (a) a case feeding the xcancel placeholder
    RSS fixture and asserting fetch() throws NitterFetchException (degraded — no
    NormalizedPost returned); and (b) a normal-feed case asserting real posts are
    returned unchanged (no regression). Red-before/green-after on (a).
  - >-
    mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcherTest.java
      — add the xcancel-placeholder-degraded case (throws) and a normal-feed
      no-regression case (posts returned).
  preserves:
    - all tests currently green on main
    - the existing NitterFetcher transport/parse tests (identifier redaction, RSS 2.0 parse)
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
decision_refs:
  - D42
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-588: a degraded placeholder feed is a Fetcher failure, not a successful ingest

## Context

Found 2026-07-08 during live testing. Every `nitter` source (`https://rss.xcancel.com/<acct>/rss`) — 27 of them, incl. vxunderground, karpathy, AnthropicAI, OpenAI, DeepSeek — is returning xcancel's anti-scraping placeholder instead of tweets:

```
<title>RSS reader not yet whitelisted!</title>
<description>RSS reader not yet whitelist! Please send an email rss [AT] xcancel [DOT] com
  with this ID to get your RSS feed reader whitelisted: <hex-id></description>
```

xcancel now gates RSS behind a manual per-reader whitelist. Verified by User-Agent: a bare `curl` UA gets `403`, but the collector's UA (`infochat/1.0.0-SNAPSHOT`, via `SsrfGuardedHttpClient`) gets **HTTP 200 + a well-formed RSS document** whose single item is the placeholder. Because the response is a valid 200 that parses cleanly, the collector records a **successful fetch** (`last_success_at` advanced, `consecutive_failures=0`), ingests the stub as a post, and runs the full eval pipeline (security → tag → entity → embed) over the error text. Net effect: **27 dead X/Twitter sources look perfectly healthy**, contribute zero real content, and pollute the corpus with a stub each.

D42 (Fetcher failure policy) already counts a "feed parse failure" as a Fetcher failure that increments the per-source counter and eventually flips `status='failed'`. A feed that parses but carries only a service-degradation sentinel is the same class of problem — it is NOT delivering content — and should count the same way, so the operator sees the source as failing rather than healthy.

## The fix

In `NitterFetcher.fetch()`, after `SingleGetFetch.fetchAndParse` returns, inspect the parsed items: if the feed is the xcancel "not whitelisted" placeholder (exact sentinel match on the item title / whitelist notice), throw `NitterFetchException` instead of returning it. That routes through `FetchScheduler`'s existing D42 per-source failure handler — no ingest, no `last_success_at` bump, counter increments, source eventually marked `failed`. Keep the match EXACT so a real tweet mentioning "whitelist" is never dropped, and keep it inside `NitterFetcher` (do not touch the shared `RssFeedParser`, which rss sources also use).

## Out-of-scope

See frontmatter. Notably: no generic degraded-feed framework (nitter/xcancel sentinel only), no `RssFeedParser` change, no data cleanup (the 27 stubs were purged out-of-band 2026-07-08), and no change to the D42 counter / `FetchScheduler` (reused as-is). The operator-side remediation (whitelist with xcancel, or swap the X bridge) is external.

## Notes

- **Provenance.** Live-test finding 2026-07-08 (SimpleX test-user walkthrough). Not a red-team finding.
- **Detection point.** `NitterFetcher` currently returns `SingleGetFetch.fetchAndParse(...)` directly; the change captures that result, checks for the sentinel, and throws on match — a localized, nitter-only change. The `NitterFetchException` path is exactly what D42's per-source failure handler already consumes (see the class javadoc).
