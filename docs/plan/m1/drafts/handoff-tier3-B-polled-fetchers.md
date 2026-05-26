# Session handoff — Tier 3 Group B: polled fetchers (Bluesky, Reddit, Nitter, YouTube, Odysee)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T3-B ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0, Tier 1, and Tier 2 implementation tickets are done and
  merged on main. T3-A (production adapters) may or may not be done —
  T3-B has NO dependency on T3-A and can be authored in parallel.
- The Fetcher SPI (M1-007a), RssFetcher reference implementation
  (M1-023), bootstrap-sources loader (M1-022), collector outbox
  (M1-028), and FetchScheduler are all on main.
- Deferred: M1-019, M1-020, M1-021, M1-031, M1-034, M1-042.
- Branch is main, otherwise clean.

**Verify at authoring time:**

  - Next free ticket ID:
    `ls docs/plan/m1/tickets/ | sort -V | tail`
  - Fetcher SPI shape:
    `cat infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java`
  - NormalizedPost shape:
    `cat infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java`
  - RssFetcher reference impl:
    `cat infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFetcher.java`
  - RssFeedParser (parse pattern):
    `cat infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java`
  - RssFetcherTest (test pattern):
    `cat infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/rss/RssFetcherTest.java`
  - FetchScheduler (how fetchers are invoked):
    `find . -name "FetchScheduler*" -path "*/main/*" | head -5`
  - SsrfGuardedHttpClient (how HTTP is issued):
    `find . -name "SsrfGuarded*" -path "*/main/*" | head -5`
  - Source kinds already in schema/bootstrap:
    `grep -r "kind" infochat-core/src/main/resources/db/migration/ | grep -i "rss\|bluesky\|nostr\|reddit\|nitter\|youtube\|odysee"`

## What T3-B creates

Five polled Fetcher implementations — one per feed source kind. All
implement the same `Fetcher` SPI and follow the RssFetcher pattern:

  Fetcher.fetch(long sourceId, String identifier) → List<NormalizedPost>

Each fetcher:
- Is an `@ApplicationScoped` CDI bean
- Issues HTTP GET via `SsrfGuardedHttpClient`
- Parses the response into `List<NormalizedPost>`
- Implements pagination within a single tick (up to per-source max-page
  cap, profile-driven per spec §Ingest SPIs)
- Throws an unchecked exception on failure (FetchScheduler's per-tick
  error handler catches it per D42 threshold-based `active→failed`)

### Per-fetcher specifics

**1. BlueskyFetcher** (`kind = "bluesky"`)
  - Endpoint: Bluesky AT Protocol public API (`app.bsky.feed.getAuthorFeed`
    or `app.bsky.feed.getTimeline`). The identifier is the DID or handle
    of the account to follow.
  - Pagination: cursor-based (`cursor` parameter); paginate within tick
    up to cap.
  - Response: JSON; each feed item has `post.record.text` (body),
    `post.uri` (stable upstream identifier), `post.indexedAt` (timestamp).
  - Auth: public endpoint, no auth needed for public feeds in v1.
  - Parser: JSON → NormalizedPost.

**2. RedditFetcher** (`kind = "reddit"`)
  - Endpoint: Reddit JSON API (append `.json` to subreddit URL:
    `https://www.reddit.com/r/<subreddit>/new.json`). The identifier
    is the subreddit URL.
  - Pagination: `after` parameter (Reddit `fullname` cursor); paginate
    within tick up to cap.
  - Response: JSON listing; each item has `data.selftext` or
    `data.url` (body), `data.name` (upstream identifier: `t3_<id>`),
    `data.created_utc` (timestamp).
  - Auth: public JSON endpoint; no auth targeted in v1. **Risk:**
    Reddit has tightened API access — verify current rate limits and
    whether OAuth is now required for the `.json` endpoint. If auth
    is needed, the fetcher's config surface grows (OAuth client-id /
    secret properties).
  - Parser: JSON → NormalizedPost.

**3. NitterFetcher** (`kind = "nitter"`)
  - Endpoint: Nitter RSS feed (Nitter instances expose RSS at
    `/<username>/rss`). The identifier is the full Nitter RSS URL.
  - Pagination: no pagination (RSS feed, single request per tick).
  - Response: RSS/Atom XML — delegate to RssFeedParser or a similar
    XML→NormalizedPost path. If the identifier is always an RSS URL,
    NitterFetcher may simply wrap RssFeedParser with a Nitter-specific
    normalization step (stripping HTML artifacts, resolving t.co links).
  - Auth: none (Nitter is self-hosted, public).
  - Parser: reuse RssFeedParser or variant.

**4. YouTubeFetcher** (`kind = "youtube"`)
  - Endpoint: YouTube channel RSS feed
    (`https://www.youtube.com/feeds/videos.xml?channel_id=<id>`).
    The identifier is the channel RSS URL.
  - Pagination: no pagination (RSS feed, single request per tick).
  - Response: Atom XML — same parse path as Nitter/RSS.
  - Parser: reuse RssFeedParser (Atom 1.0 support already exists).
  - Normalization: video title as NormalizedPost title, description as
    body, video URL as `url`, Atom `<id>` as upstream identifier.

**5. OdyseeFetcher** (`kind = "odysee"`)
  - Endpoint: Odysee/LBRY API (`https://odysee.com/$/rss/@<channel>`)
    or the LBRY SDK claim_search endpoint. Prefer RSS if available (the
    identifier is the RSS URL).
  - Pagination: per RSS: none. Per API: `page` parameter; paginate
    within tick up to cap.
  - Response: RSS/Atom XML or JSON depending on endpoint choice.
  - Parser: reuse RssFeedParser for RSS path, or JSON for API path.

### Package layout

Each fetcher lives under its own subpackage of
`infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/`:

  - `rss/`      (existing: RssFetcher, RssFeedParser)
  - `bluesky/`  (new: BlueskyFetcher, BlueskyResponseParser)
  - `reddit/`   (new: RedditFetcher, RedditResponseParser)
  - `nitter/`   (new: NitterFetcher — may reuse RssFeedParser)
  - `youtube/`  (new: YouTubeFetcher — may reuse RssFeedParser)
  - `odysee/`   (new: OdyseeFetcher — may reuse RssFeedParser)

### FetchScheduler refactoring (REQUIRED — current shape is RSS-only)

**The current FetchScheduler is hardcoded to RSS.** It `@Inject`s
`RssFetcher` directly and queries `WHERE kind = 'rss'`. There is NO
polymorphic dispatch — it is a single-kind scheduler.

T3-B must address this. Two options:

  **Option A (polymorphic refactor).** Refactor FetchScheduler into a
  kind-agnostic dispatcher. Discover all `Fetcher` CDI beans; each
  bean carries a `kind()` discriminator (or a CDI qualifier). The
  scheduler iterates source kinds, resolves the matching Fetcher, and
  ticks each enabled source. This is a single cross-cutting change
  that all 5 fetcher tickets benefit from.

  **Option B (per-kind schedulers).** Each fetcher ticket creates its
  own scheduler (e.g. `BlueskyFetchScheduler`) following the same
  pattern as the RSS scheduler. More files, no cross-cutting refactor,
  but each is standalone.

  Recommend **Option A** — it avoids 5 copies of the enumerate-sources
  + tick-once loop. The refactoring can be the first T3-B ticket
  (prerequisite for the others) or folded into the first fetcher
  ticket (Bluesky). Either way, the authoring session must decide
  and wire `blocked_by` accordingly.

  The Fetcher SPI itself does NOT change — only the scheduler dispatch.

### Dedup mechanism (outbox-side, not fetcher-side)

Fetchers are **stateless** — they return ALL items from the current
feed/page. "What's new since last time" is NOT the fetcher's
responsibility. `PostPersister` deduplicates via
`ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING`.
A duplicate post produces an empty `Optional` from `persist()`, and
the enqueue is skipped. Fetchers do NOT track last-seen state.

### What T3-B does NOT create

  - No new Flyway migrations.
  - No changes to the Fetcher SPI.
  - No changes to existing RssFetcher or RssFeedParser.
  - No StreamSource work (T3-C).
  - No adapter or LLM work.

## Key seams in the current code

### Fetcher SPI

Location: `infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java`

Signature: `List<NormalizedPost> fetch(long sourceId, String identifier)`
- sourceId: stamped onto every returned post
- identifier: the source-side URL or filter spec
- Returns empty list if no new items

### RssFetcher (reference implementation)

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFetcher.java`

Pattern:
- `@ApplicationScoped` CDI bean
- Injected `SsrfGuardedHttpClient` (constructor injection for test seam)
- Captures `fetchedAt = Instant.now()` BEFORE the HTTP call
- Issues `client.get(URI.create(identifier))`
- Checks HTTP status (non-2xx throws `RssFetchException`)
- Delegates to parser: `RssFeedParser.parse(sourceId, body, fetchedAt)`
- Exception handling: IOException → RssFetchException,
  InterruptedException → Thread.currentThread().interrupt() + throw

### RssFeedParser

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java`

- Stateless StAX-based RSS 2.0 / Atom 1.0 parser
- Routes on root element: `<rss>` → RSS, `<feed>` → Atom
- Item-count cap: 1000 items per feed
- HTML stripping and NFKC normalization happen downstream in Stage 1,
  NOT in the parser

### SsrfGuardedHttpClient

Location: find via `find . -name "SsrfGuardedHttpClient*" -path "*/main/*"`

- Wraps JDK HttpClient with SSRF protection (M1-024..M1-026)
- Method: `get(URI)` returns `HttpResponse<byte[]>`
- Test seam: constructor accepts blocklist override for loopback-
  permitting tests

### FetchScheduler (RSS-only — refactoring required)

Location: `infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java`

- **Currently hardcoded to RSS**: `@Inject RssFetcher rssFetcher` and
  `WHERE kind = 'rss'` in the SQL query.
- `@Scheduled(every = "{infochat.fetch.rss.interval}")` — single tick
  interval for all RSS sources.
- `tickOnce(SourceRow)` is public (IT-callable); it calls
  `rssFetcher.fetch(row.dispatchKey(), row.identifier())`.
- Error handler: WARN-log only; D42 `consecutive_failures` wiring was
  noted as "T2-B's work" in the javadoc (verify whether it landed).
- @Startup @Priority(400), after Flyway (100), BootstrapLoader (200),
  OutboxRehydrator (300).
- SourceRow uses a monotonic `dispatchKey` (NOT the UUID) as the
  `long sourceId` passed to the Fetcher SPI.

T3-B's refactoring (§FetchScheduler refactoring above) must preserve
this public API for the RSS path while adding per-kind dispatch.

### NormalizedPost

Location: `infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java`

Fields (verify at authoring time): `sourceId`, `upstreamIdentifier`,
`title` (nullable), `body` (non-null), `url` (nullable),
`publishedAt` (nullable), `fetchedAt` (never null),
`rawMetadata` (non-null map, possibly empty).

## Spec sections T3-B cites

- `docs/spec/architecture.md` §Ingest SPIs (line 139) — Fetcher
  shape, pagination cap, per-kind tick cadence, pagination cap
  saturation alert
- `docs/spec/architecture.md` §Source identity (D38) — `(kind,
  identifier)` unique key
- `docs/spec/security.md` §SSRF and outbound connections (line 120)
  — all HTTP goes through SSRF guard
- `docs/spec/security.md` §Ingest pipeline (line 56) — Stage 1
  runs downstream, not in the fetcher
- `docs/spec/verification.md` — fetcher-related test items
- `docs/design/01-architecture.md` §1.3.1 (polled Fetcher flow),
  §1.6 (per-source pagination caps per kind per profile)

## Recommended ticket split

5 fetcher tickets plus the FetchScheduler refactoring work.

If the scheduler refactoring is a separate prerequisite ticket: 6
tickets total (1 scheduler + 5 fetchers, each fetcher `blocked_by`
the scheduler ticket). If the refactoring is folded into the first
fetcher ticket (Bluesky): 5 tickets, with the remaining 4
`blocked_by` the Bluesky ticket.

Each fetcher is small (~3-5 files: fetcher + parser + test + possibly
a fixture file).

The session-grouping-plan notes: "Could be one session or split 3+2
if context budget is tight." For ticket authoring (not implementation),
all 5 can be authored in one session.

Each ticket:
  - complexity: low (template-heavy, follows RssFetcher pattern)
  - risk: low (isolated per-kind impl, no cross-cutting changes)
  - files_budget: 4-5 per ticket
  - security_relevant: false (HTTP goes through SSRF guard; parsing
    untrusted HTML is Stage 1's job, not the fetcher's)

For fetchers that reuse RssFeedParser (Nitter, YouTube, Odysee), the
file count is lower (~3 files: fetcher + test + fixture). For fetchers
with their own JSON parsers (Bluesky, Reddit), ~4-5 files.

### Shared base consideration

The design doc mentions "shared base class likely." Evaluate at
authoring time whether a `JsonFetcher` or `PaginatedFetcher` base
reduces boilerplate across Bluesky + Reddit. But three similar lines
beats a premature abstraction — if the fetchers are each ~100 lines,
a base class may not be justified. Keep each fetcher standalone unless
the duplication is obvious and mechanical.

## Dependencies and ordering

- All tickets depend on the Tier 2 completion. For `blocked_by`, use
  the last done M1 ticket at authoring time (verify via
  `ls docs/plan/m1/tickets/ | sort -V | tail`).
- If the scheduler refactoring is a separate ticket, all 5 fetcher
  tickets `blocked_by` it.
- Recommended implementation order: Bluesky first (most complex
  pagination and JSON parsing; carries the scheduler refactoring if
  folded), then Reddit, then Nitter/YouTube/Odysee (RSS-reuse).
- T3-B is independent of T3-A, T3-C, and T3-D.
- Profile-driven values use Quarkus config profiles:
  `%laptop.infochat.fetch.bluesky.interval=10m`,
  `%pi.infochat.fetch.bluesky.interval=30m`, etc.

## Existing tests to not break

- RssFetcherTest — must stay green; T3-B does not touch RssFetcher
- RssFeedParser tests (if separate) — must stay green
- Stage1WatchdogIT — known marginal (50ms cap, see memory
  `project_stage1watchdogit_flake.md`); unrelated but in `mvn verify`
- Full `mvn verify` from repo root

## Task

Author the T3-B ticket files (5 tickets, one per fetcher kind) in
`docs/plan/m1/tickets/`. Follow the ticket template at
`docs/process/ticket-template.md`. Each ticket must have correct
frontmatter. Use 5 sequential free IDs at the tail.

After authoring, run `scripts/lint-ticket.py` on each new ticket
file and fix any errors. Do NOT run `/m1-tick start` — only author.
```
