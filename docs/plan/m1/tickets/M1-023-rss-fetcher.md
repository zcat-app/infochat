---
id: M1-023
title: RSS Fetcher implementation (Fetcher SPI, kind='rss')
status: pending
created: 2026-05-14
last_updated: 2026-05-14
blocked_by:
  - M1-007a
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFetcher.java
  - infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFeedParser.java
  - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java
  - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFeedParserTest.java
  - infochat-collector/src/test/resources/fixtures/rss/atom-sample.xml
  - infochat-collector/src/test/resources/fixtures/rss/rss20-sample.xml
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any FetchScheduler / per-tick @Scheduled wiring / per-kind cadence selection (T1-C territory at @Priority(400) per docs/design/01-architecture.md §1.4.3; the FetchScheduler INSTANTIATES this Fetcher and calls fetch() — this ticket only authors the Fetcher class)
  - any outbox sink, RAW-row INSERT, or post-table write (T1-C — RssFetcher returns List<NormalizedPost> to its caller; the caller persists RAW rows in a separate transactional boundary)
  - any OutboxRehydrator, LISTEN/NOTIFY (new_post), provider_state high-water-mark, or NewPostReconciler wiring (T1-C)
  - any Stage 1 HTML sanitization, NFKC normalization, regex redaction, or canonical-body UID hashing (T1-D — this Fetcher passes the raw HTML body through; the Stage 1 sanitizer downstream of the outbox strips HTML and applies the redaction catalogue per docs/spec/security.md §Ingest pipeline (security side))
  - any Bluesky / Nitter / Reddit / YouTube / Odysee fetcher implementation (each fetcher binds to the same M1-007a Fetcher SPI but is its own Tier-3 T3-B ticket)
  - any NostrStreamSource implementation (StreamSource SPI, Tier-3 T3-C)
  - any source-row UPDATE for last_fetch_at / last_success_at / consecutive_failures (decision D42's per-source failure-counter model is the FetchScheduler's responsibility, not the Fetcher's — the SPI is stateless between ticks per docs/spec/architecture.md §Ingest SPIs)
  - any pagination cap counter, admin-notification on saturation, or single-tick page-walk (RSS has no pagination per docs/design/01-architecture.md §1.6; non-RSS kinds with pagination land with their own fetcher impls)
  - any retry / backoff / Retry-After honoring / per-source politeness window (microprofile-faulttolerance integration lives with the FetchScheduler at the per-tick boundary, not in the Fetcher per the §1.6 concurrency rules)
  - any infochat-ssrf module work, IP blocklist, DNS-rebind defense, redirect-cap enforcement, or HttpClient wrapper authored under infochat-ssrf (per the Option A two-ticket carve-out documented in Big-picture notes — infochat-ssrf is a separate ticket authored before the FetchScheduler wires this Fetcher to production traffic in T1-C; this ticket flags the gap but does NOT introduce its own minimal SSRF gate, which would be the worst of both worlds per the authoring handoff)
  - any change to the M1-007a Fetcher SPI / NormalizedPost record shape (the SPI is the contract this ticket consumes; if the impl reveals a missing field, escalate via the workflow rather than mutating the SPI in this ticket)
  - any infochat-collector/pom.xml change (parsing uses the JDK's built-in javax.xml.stream and HTTP uses java.net.http.HttpClient; no new dependency is introduced)
  - any change to V1..V8 Flyway migrations (this ticket is impl-only; migration_touch is false)
acceptance:
  - "infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFetcher.java exists, declares `public class RssFetcher`, and implements io.infochat.core.ingest.Fetcher — grep -E 'public class RssFetcher\\b' RssFetcher.java returns at least one match AND grep -E 'implements\\s+(io\\.infochat\\.core\\.ingest\\.)?Fetcher\\b' RssFetcher.java returns at least one match"
  - "RssFetcher.java overrides the SPI method `List<NormalizedPost> fetch(long sourceId, String identifier)` — grep -E 'public\\s+List<NormalizedPost>\\s+fetch\\s*\\(\\s*long\\s+\\w+\\s*,\\s*String\\s+\\w+\\s*\\)' RssFetcher.java returns at least one match"
  - "RssFetcher.java issues a single HTTP GET per call (RSS has no pagination per docs/design/01-architecture.md §1.6) and uses the JDK's java.net.http.HttpClient — grep -E 'java\\.net\\.http\\.HttpClient|HttpClient\\.newBuilder|HttpClient\\.newHttpClient' RssFetcher.java returns at least one match AND grep -E '\\.GET\\s*\\(\\s*\\)' RssFetcher.java returns at least one match"
  - "RssFetcher.java carries an SSRF-gate carve-out comment at the top of the class (per the Option A Big-picture notes commitment) — grep -E 'SSRF GATE TODO|infochat-ssrf' RssFetcher.java returns at least one match in a comment context"
  - "RssFeedParser.java exists, exposes a static or instance method that parses an XML byte payload + base URL into a `List<NormalizedPost>`, and handles both RSS 2.0 and Atom 1.0 — grep -E 'public (static )?List<NormalizedPost>\\s+\\w+\\s*\\(' RssFeedParser.java returns at least one match AND grep -E 'javax\\.xml\\.stream|XMLStreamReader|XMLInputFactory' RssFeedParser.java returns at least one match"
  - "RssFeedParser.java reads RSS 2.0 `<guid>`, `<link>`, `<title>`, `<description>`, `<pubDate>` and Atom 1.0 `<id>`, `<link href>`, `<title>`, `<content>`, `<published>` elements — grep -E '\"guid\"|\"link\"|\"title\"|\"description\"|\"pubDate\"' RssFeedParser.java returns at least four distinct element-name matches AND grep -E '\"id\"|\"published\"|\"content\"|\"entry\"' RssFeedParser.java returns at least three distinct Atom-element matches"
  - "RssFeedParser.java parses RSS 2.0 `<pubDate>` per RFC 822 and Atom 1.0 `<published>` per RFC 3339 / ISO 8601 — grep -E 'DateTimeFormatter\\.RFC_1123_DATE_TIME|RFC_1123|RFC_822|EEE,\\s*dd\\s+MMM\\s+yyyy' RssFeedParser.java returns at least one match (RSS) AND grep -E 'DateTimeFormatter\\.ISO_(OFFSET_DATE_TIME|DATE_TIME)|ISO_8601|Instant\\.parse' RssFeedParser.java returns at least one match (Atom)"
  - "RssFeedParser.java derives NormalizedPost.upstreamIdentifier as the RSS `<guid>` (preferred) falling back to `<link>` per docs/spec/schema.md §Posts and derivatives — UID derivation; raises an exception when an item has neither (the SPI contract requires upstreamIdentifier never-null) — RssFeedParserTest asserts: (a) a guid-bearing item produces upstreamIdentifier = the guid value; (b) a guid-less but link-bearing item produces upstreamIdentifier = the link value; (c) an item with neither raises a parse-rejection (grep -E 'upstreamIdentifier|guid|link' RssFeedParserTest.java returns at least three matches in distinct test methods)"
  - "RssFeedParser.java sets NormalizedPost.title to the parsed <title> element (nullable per the M1-007a SPI contract — items with no title are legal RSS) and NormalizedPost.body to the raw RSS <description> or Atom <content> WITHOUT HTML stripping (Stage 1 is the sanitization boundary, NOT this Fetcher, per docs/spec/security.md §Ingest pipeline (security side)) — RssFeedParserTest asserts: a title-less RSS item produces NormalizedPost.title = null; an HTML-bearing <description> round-trips its raw HTML into NormalizedPost.body unmodified (grep -E '<p>|<a |&lt;' RssFeedParserTest.java in the body-assertion context returns at least one match)"
  - "RssFeedParser.java parses RSS 2.0 `<pubDate>` and Atom 1.0 `<published>` as `java.time.Instant` and writes the value to NormalizedPost.publishedAt; NormalizedPost.fetchedAt is set to `Instant.now()` at fetch time (not parse time); NormalizedPost.rawMetadata is an empty `Map<String, String>` for RSS — RssFeedParserTest asserts publishedAt is non-null for an RFC-822-bearing item; RssFetcherTest asserts fetchedAt is within 5 seconds of the test's wall clock"
  - "RssFetcherTest.java is a plain JUnit 5 test (no @QuarkusTest) that spins up an in-process HTTP server via `com.sun.net.httpserver.HttpServer`, serves the rss20-sample.xml fixture on a localhost port, calls `new RssFetcher().fetch(1L, \"http://localhost:<port>/feed.xml\")`, and asserts the returned List<NormalizedPost> row count matches the fixture's `<item>` count AND every returned post has `sourceId == 1L` (grep -E 'HttpServer\\.create|com\\.sun\\.net\\.httpserver' RssFetcherTest.java returns at least one match AND grep -E 'sourceId\\s*\\(\\s*\\)\\s*==\\s*1L|assertEquals\\(\\s*1L' RssFetcherTest.java returns at least one match)"
  - "RssFeedParserTest.java is a plain JUnit 5 test (no @QuarkusTest) that loads the two fixture files via the test classloader and asserts per-field parse correctness for at least: (a) RSS 2.0 guid-bearing item; (b) Atom 1.0 entry with `<id>` + `<published>`; (c) RSS 2.0 item with no guid but with a `<link>` (link-fallback path); (d) item-with-neither (raises) — grep -E '@Test' RssFeedParserTest.java returns at least four matches"
  - "infochat-collector/src/test/resources/fixtures/rss/rss20-sample.xml is a valid RSS 2.0 document with at least 3 `<item>` elements — `xmllint --noout fixture.xml` (or equivalent JDK-side XML validator in the test) exits 0; one item has a `<guid>` element, one item has no `<guid>` but a `<link>`, all items have a `<pubDate>` parseable by RFC_1123_DATE_TIME"
  - "infochat-collector/src/test/resources/fixtures/rss/atom-sample.xml is a valid Atom 1.0 document with at least 2 `<entry>` elements — `xmllint --noout fixture.xml` exits 0; every entry has `<id>`, `<title>`, `<link href>`, `<published>`"
  - "mvn -B -pl infochat-collector -am test exits 0; surefire reports show RssFetcherTest and RssFeedParserTest executed (grep -rE 'Tests run: [1-9]' infochat-collector/target/surefire-reports returns at least two new matches across the two new test classes)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003, M1-007, M1-007a/b/c, M1-008, M1-008a/b/c, M1-009, and M1-017 tests continue to pass alongside the new Fetcher classes; M1-022's BootstrapLoaderIT continues to pass if M1-022 has merged before this ticket runs"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFeedParserTest.java (plain-JUnit unit test — parser-level shape rules against the two XML fixtures; covers guid-preferred, link-fallback, both-missing-raises, title-nullable, publishedAt parse, Atom vs RSS dialect routing)
    - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java (plain-JUnit unit test — uses com.sun.net.httpserver.HttpServer to serve the RSS fixture on a localhost port; calls fetch() and asserts the returned List<NormalizedPost> shape end-to-end)
    - infochat-collector/src/test/resources/fixtures/rss/rss20-sample.xml (3-item RSS 2.0 fixture covering guid-bearing, link-fallback, and pubDate parsing)
    - infochat-collector/src/test/resources/fixtures/rss/atom-sample.xml (2-entry Atom 1.0 fixture covering id + published + content parsing)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a — Fetcher SPI is consumed unchanged)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 / M1-017)
    - infochat-collector/src/test/java/io/infochat/collector/startup/InstanceLockGuardIT.java (M1-009)
    - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java (M1-009)
    - all M1-008a / M1-008b / M1-008c *Test.java classes
    - M1-022's BootstrapSourcesParserTest and BootstrapLoaderIT (if merged before this ticket)
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/architecture.md §Architectural principles
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/security.md §SSRF and outbound connections
  - docs/spec/security.md §Ingest pipeline (security side)
  - docs/design/00-mvp.md §Fetchers
  - docs/design/01-architecture.md §1.2 Module layout (Maven)
  - docs/design/01-architecture.md §1.6 Concurrency and rate limiting
decision_refs:
  - D38
---

# M1-023: RSS Fetcher implementation (Fetcher SPI, kind='rss')

## Context

Second of two T1-B ingest-sources tickets (the first is M1-022, the
bootstrap-sources loader). This ticket lands the **first concrete
binding** to the M1-007a `io.infochat.core.ingest.Fetcher` SPI: an
`RssFetcher` that issues a single HTTP GET against a source's
`identifier` URL, parses the response as RSS 2.0 or Atom 1.0, and
returns a `List<NormalizedPost>` in source-supplied order.

RSS is the single most common feed format in `bootstrap-sources.json`
and is the simplest Fetcher to author end-to-end. Per
`docs/design/01-architecture.md` §1.6, RSS has **no pagination** —
the per-tick pagination cap is 1 across every profile, so the Fetcher
is a one-request-per-call shape. "What's new since last time" is a
post-table query performed downstream by the FetchScheduler /
OutboxRehydrator in T1-C; the Fetcher itself is stateless between
ticks per `docs/spec/architecture.md` §Ingest SPIs.

This ticket is **impl-only**: no scheduler wiring, no outbox sink, no
post-table writes, no source-row UPDATEs for `last_fetch_at` /
`consecutive_failures` / `status`. Those all live in T1-C at
`@Priority(400)`. The Fetcher class authored here is what the
scheduler will INSTANTIATE and call `fetch()` on in T1-C; the
contract this ticket commits to is the SPI signature
(`List<NormalizedPost> fetch(long sourceId, String identifier)`)
plus the per-field mapping for `NormalizedPost`.

## Definition of Done

- `infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFetcher.java`
  is a `public class RssFetcher implements io.infochat.core.ingest.Fetcher`
  with:
  - A single `public List<NormalizedPost> fetch(long sourceId, String
    identifier)` method matching the SPI signature.
  - Body: build a `java.net.http.HttpRequest` with `.GET()` against
    `URI.create(identifier)`; submit via a `java.net.http.HttpClient`
    instance (one per RssFetcher; thread-safe per JDK contract);
    capture the response body bytes; hand to `RssFeedParser`; return
    the parser's `List<NormalizedPost>`.
  - HTTP request preserves an `Accept: application/rss+xml,
    application/atom+xml, application/xml;q=0.9, */*;q=0.8` header
    so feed servers can content-negotiate the right MIME, and a
    `User-Agent` header pinned to a project-identifying string
    (`infochat/<version>` from `quarkus.application.version` or
    equivalent; v1 placeholder `infochat/0.0.1-SNAPSHOT` is
    acceptable until the version-source helper is centralized).
  - A class-level header comment explicitly flagging the SSRF carve-out:
    `// SSRF GATE TODO: this Fetcher's outbound HTTP path is not yet
    routed through infochat-ssrf (the shared IP-blocklist +
    DNS-rebind defense module per docs/spec/security.md §SSRF and
    outbound connections). A follow-up ticket lands infochat-ssrf
    before the FetchScheduler in T1-C wires this Fetcher to
    production traffic. See M1-023 Big-picture notes.`
  - **No** retry / backoff / Retry-After handling: the spec assigns
    those to the FetchScheduler at the per-tick boundary per
    `docs/design/01-architecture.md` §1.6. A non-2xx HTTP response
    propagates as a thrown exception; the caller's per-tick error
    handler in T1-C catches it and runs D42's failure-counter
    update. The Fetcher is opinionated about a single HTTP call; the
    FetchScheduler is opinionated about retry policy.
- `infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFeedParser.java`
  is a stateless parser:
  - Exposes a `public List<NormalizedPost> parse(long sourceId,
    byte[] body, Instant fetchedAt)` method (or equivalent
    instance-method shape — keep it static-callable for simplicity).
  - Uses the JDK's `javax.xml.stream.XMLInputFactory` + `XMLStreamReader`
    to walk the document. **Dialect routing**: read the root element
    name; `<rss>` → RSS 2.0 branch; `<feed>` (with namespace
    `http://www.w3.org/2005/Atom`) → Atom 1.0 branch; anything else
    raises a parse-rejection exception (the Fetcher's HTTP caller
    decides what to do with it — propagate or count).
  - RSS 2.0 branch reads each `<item>` and emits one NormalizedPost
    per item:
    - `upstreamIdentifier` ← `<guid>` if present and non-empty, else
      `<link>` if present and non-empty, else **raise** (the SPI
      contract requires `upstreamIdentifier` never-null — an item
      with no stable identifier is a malformed feed).
    - `title` ← `<title>` text (nullable per SPI contract).
    - `body` ← `<description>` text (raw HTML preserved; Stage 1
      strips HTML downstream, not this parser).
    - `url` ← `<link>` (nullable).
    - `publishedAt` ← parse `<pubDate>` as RFC 822 / RFC 1123
      (`DateTimeFormatter.RFC_1123_DATE_TIME`) → `Instant`
      (nullable — missing or unparseable `<pubDate>` falls back to
      `null`).
  - Atom 1.0 branch reads each `<entry>`:
    - `upstreamIdentifier` ← `<id>` (required by Atom; raise if
      absent).
    - `title` ← `<title>` text (nullable per SPI contract).
    - `body` ← `<content>` text (raw; HTML preserved).
    - `url` ← the `href` attribute of `<link rel="alternate">` (or
      the first `<link>` with no `rel` attribute, fallback) —
      nullable.
    - `publishedAt` ← parse `<published>` as RFC 3339 (`Instant.parse`)
      → `Instant` (nullable).
  - `sourceId` ← the caller-supplied `long sourceId` (stamped onto
    every emitted post).
  - `fetchedAt` ← the caller-supplied `Instant fetchedAt`.
  - `rawMetadata` ← empty `Map<String, String>` (RSS has no v1
    metadata fields worth preserving; per-source author / category
    columns live on the eventual `post` row, populated by the
    tagger downstream).
- One unit test
  `infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFeedParserTest.java`
  — plain JUnit 5, parses the two fixture files via the test
  classloader and asserts the per-field mapping for at least four
  scenarios: RSS guid-bearing, RSS link-fallback (no guid),
  RSS item-with-neither raises, Atom `<id>`-bearing entry.
- One unit test
  `infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java`
  — plain JUnit 5, spins up a `com.sun.net.httpserver.HttpServer`
  on a localhost ephemeral port, serves the RSS fixture, calls
  `new RssFetcher().fetch(1L, "http://localhost:<port>/feed.xml")`,
  and asserts the end-to-end shape: row count matches the fixture's
  item count, every row carries `sourceId == 1L`, every row's
  `fetchedAt` is within 5 seconds of the wall clock.
- Two XML fixtures under
  `infochat-collector/src/test/resources/fixtures/rss/`:
  - `rss20-sample.xml` — 3 items: one with `<guid>` + `<link>` +
    `<pubDate>`, one without `<guid>` but with `<link>` (link-fallback
    test), one with HTML in `<description>` (raw-pass-through test).
  - `atom-sample.xml` — 2 entries: one with `<id>` + `<published>` +
    `<content>` + `<link rel="alternate">`, one minimal.
- `mvn -B clean verify` from the repo root exits 0. All prior tests
  continue to pass; the two new test classes execute and pass.

## Implementation notes

- **Library choice: JDK-native `javax.xml.stream` (StAX), not Rome.**
  Rome (`com.rometools:rome`) is the obvious JVM RSS+Atom parser, but
  adopting it would require adding the dep to the BOM (per the M1-001
  invariant — no per-pom `<version>` elements) AND adding the
  artifact to `infochat-collector/pom.xml`. That's two pom files
  touched, pushing `files_budget` over the locked ceiling of 6. The
  RSS 2.0 + Atom 1.0 shape is well-defined and the StAX API
  (`XMLInputFactory.newDefaultFactory().createXMLStreamReader(...)`)
  is enough for the v1 fields this Fetcher returns. If a future
  ticket reveals a real dialect-quirks maintenance burden, that
  ticket can swap StAX for Rome in a focused diff with its own
  budget — but the budget cost should be paid by the ticket that
  needs the feature, not pre-paid here. See "Alternatives
  considered" for the rejection rationale.
- **HTTP client: JDK-native `java.net.http.HttpClient` (JDK 11+).** No
  new dependency; the project's target is JDK 25 per
  `project_quarkus_jdk25` (auto-memory) and the API is mature.
  Construct one `HttpClient` per `RssFetcher` instance; the client
  is thread-safe by JDK contract and the FetchScheduler will share
  one `RssFetcher` instance across ticks (T1-C's concern, not
  ours).
- **No internal HTML / NFKC / redaction work.** Per
  `docs/spec/security.md` §Ingest pipeline (security side) and
  `docs/spec/schema.md` §Posts and derivatives, Stage 1 is the
  HTML-strip + NFKC + regex-redaction boundary; it runs downstream
  of the outbox in T1-D. This Fetcher passes the raw `<description>`
  / `<content>` body through into `NormalizedPost.body` unmodified.
  A reader who sees raw HTML in a NormalizedPost should expect it —
  that is the documented hand-off shape.
- **The Fetcher is stateless between ticks per
  `docs/spec/architecture.md` §Ingest SPIs.** No instance fields
  capture "what we returned last call"; dedup is the post-table's
  job via the `(uid, fetched_at)` UNIQUE constraint authored in
  M1-008c's V7. The FetchScheduler's per-tick caller may invoke
  this Fetcher many times against many sources; nothing carries
  over between calls.
- **In-process HTTP server for tests:
  `com.sun.net.httpserver.HttpServer`.** Built into the JDK; the
  lightest possible test server. Bind to `InetSocketAddress(0)` for
  an ephemeral port; the test reads `server.getAddress().getPort()`
  and builds the URL. WireMock or MockServer would add a test-scope
  dependency we don't need; the Sun server is exactly the right
  size for "serve one XML file on one URL once."
- **Dialect routing belongs in the parser, not the Fetcher.** The
  Fetcher's job is HTTP transport; the parser's job is XML shape
  analysis. Centralizing the routing in `RssFeedParser.parse(...)`
  keeps the Fetcher trivial and lets a future kind (e.g., a
  ContentEncoded RSS variant) extend the parser's branch logic
  without touching the Fetcher.
- **`fetchedAt` semantics.** The Fetcher captures
  `Instant fetchedAt = Instant.now()` BEFORE the HTTP call and
  passes it into the parser. This guarantees every emitted
  NormalizedPost shares one `fetchedAt` value — the per-tick
  "moment of fetch" the post partition key depends on (M1-008c's
  `post` table is `PARTITION BY RANGE (fetched_at)`). Capturing it
  per-item would let a slow parse straddle a midnight boundary and
  scatter the fetch across partitions; this is the wrong shape.
- **No CDATA-specific code path.** StAX's `XMLStreamReader` returns
  the unwrapped CDATA text when iterating `CHARACTERS` events; we
  don't need to test for the CDATA event type explicitly. The
  fixtures include at least one `<description><![CDATA[<p>HTML
  here</p>]]></description>` shape to exercise this path.
- **The Quarkus app context is not needed.** Both tests are plain
  JUnit 5 — `RssFetcher` and `RssFeedParser` have no CDI
  dependencies in v1 (no `@Inject`, no `@ConfigProperty`). When
  the FetchScheduler in T1-C wires this Fetcher via CDI, it will
  inject it via `@Inject Instance<Fetcher>` or a kind→impl registry;
  that's a T1-C concern.
- **Atom `<link rel="alternate">` parsing.** Atom feeds may have
  multiple `<link>` elements with different `rel` values; the
  user-facing URL is the one with `rel="alternate"` (or the only
  `<link>` if no `rel` is specified). The parser walks all `<link>`
  children of an entry and picks the first that satisfies the
  rule; the rest are ignored. Self-links (`rel="self"`),
  enclosures, etc., are not surfaced in NormalizedPost v1.

## Big-picture notes

- **SSRF GATE TODO — load-bearing carve-out for this ticket.** Per
  `docs/spec/security.md` §SSRF and outbound connections, every
  outbound connection from the Collector MUST run through the
  shared `infochat-ssrf` module (IP blocklist + DNS-rebind defense
  + redirect cap + timeout caps + reusable HttpClient wrapper).
  M1-007a explicitly listed `infochat-ssrf` in its `out_of_scope`
  as "a separate Tier-0/Tier-1 ticket; not introduced here." This
  ticket is the FIRST caller of the as-yet-unauthored
  `infochat-ssrf` module — and the operator's authoring decision
  for T1-B (Option A, two tickets) deliberately ships this
  Fetcher without the gate wired so the T1-B group stays at two
  tickets. **A follow-up ticket authoring `infochat-ssrf` MUST
  land before the FetchScheduler in T1-C wires this Fetcher to
  production traffic.** Until that follow-up lands:
  - The Fetcher class exists but is NOT injected into any
    scheduler; no production HTTP call routes through it.
  - The test suite exercises only localhost HTTP via
    `com.sun.net.httpserver.HttpServer`, which is not blocked by
    the future SSRF gate (the gate would treat localhost as a
    test-mode allowance or be configured off in tests — design
    decision deferred to the `infochat-ssrf` ticket).
  - The class header comment in `RssFetcher.java` flags the gap
    so a future reader cannot miss it.
  - This ticket is `security_relevant: true` so a `/redteam`
    sweep at the milestone boundary surfaces the temporary
    carve-out and operators can confirm `infochat-ssrf` landed
    before T1-C wires the scheduler.
  An alternative — landing a minimal in-Fetcher scheme allowlist
  here as a "half-baked SSRF gate" — was rejected per the
  authoring handoff: it would be the worst of both worlds (extra
  code to remove later AND a false sense of safety from a defense
  that doesn't carry the DNS-rebind / blocklist / redirect-cap
  surface the spec mandates).
- **The Fetcher SPI is consumed unchanged.** This ticket is the
  first concrete impl of `io.infochat.core.ingest.Fetcher` from
  M1-007a. The SPI signature (`List<NormalizedPost> fetch(long
  sourceId, String identifier)`) is enough for RSS; if the impl
  reveals a missing parameter (e.g., a trace-id call-context),
  the right response is to escalate via the workflow (refine the
  ticket, amend the SPI in a focused M1-007a-shaped diff). Mutating
  the SPI silently in this ticket would be scope drift.
- **`fetched_at` is the post partition key.** The post table from
  M1-008c is `PARTITION BY RANGE (fetched_at)`. The Fetcher's
  choice of `fetched_at` is load-bearing for partition
  cardinality; capturing it once per `fetch()` call (not per item)
  is the correct shape and matches `docs/spec/schema.md` §Posts
  and derivatives.
- **Cross-source dedup is the post table's job, not this Fetcher's.**
  The same item appearing in two RSS calls (a re-poll) will produce
  two NormalizedPost entries with the same `upstreamIdentifier` and
  `sourceId` but different `fetchedAt`. The downstream post-table
  INSERT uses `ON CONFLICT (uid, fetched_at) DO NOTHING` per the
  UID derivation in `docs/spec/schema.md` §UID derivation — same
  uid, different fetched_at means different partition row but same
  logical post. The cross-window dedup (different fetched_at, same
  uid) is the fetcher-caller's responsibility; the Fetcher
  itself does NOT pre-filter against prior calls.
- **Subticket isolation against M1-022.** This ticket touches the
  `fetcher/rss/` package only; M1-022 touches the `bootstrap/`
  package. Both `files_scope` lists are disjoint at the file path
  level. The tickets are runnable in parallel once both have
  started — neither depends on the other's code at compile time
  (M1-022 writes `source` rows; M1-023 reads `source` rows only
  through the SPI's `(long sourceId, String identifier)` parameter,
  which the FetchScheduler in T1-C — not the loader — supplies).
- **T1-C is the wiring boundary.** The FetchScheduler at
  `@Priority(400)` is what reads the `source` table M1-022
  populates, dispatches to this Fetcher per `source.kind`, owns the
  retry / backoff / D42 failure-counter logic, and writes the
  outbox sink that NewPostReconciler / OutboxRehydrator consume.
  This Fetcher is one of several `kind`-keyed components T1-C wires
  up; the others (BlueskyFetcher, NostrStreamSource, etc.) come
  later in Tier-3.

## Out-of-scope expansion

- **FetchScheduler / per-tick wiring / @Scheduled annotation.** T1-C
  territory at `@Priority(400)`. This Fetcher is INSTANTIATED by
  the scheduler in T1-C; this ticket only authors the class.
- **Outbox sink, RAW-row INSERT, post-table write.** T1-C — the
  Fetcher returns `List<NormalizedPost>` to its caller; the caller
  (the scheduler's per-tick handler) persists RAW rows in a
  separate transactional boundary. The Fetcher itself never
  touches the database.
- **OutboxRehydrator, NewPostReconciler, LISTEN/NOTIFY (`new_post`
  channel), provider_state high-water-mark.** All T1-C — the
  Fetcher knows nothing about post-fetch persistence.
- **Stage 1 HTML sanitization, NFKC normalization, regex
  redaction.** T1-D. This Fetcher passes the raw HTML body through
  unchanged into `NormalizedPost.body`; Stage 1 strips it
  downstream of the outbox.
- **Bluesky / Nitter / Reddit / YouTube / Odysee fetcher
  implementations.** Each binds to the same Fetcher SPI but is its
  own Tier-3 T3-B ticket.
- **NostrStreamSource.** Different SPI (StreamSource, not Fetcher).
  Tier-3 T3-C.
- **source-row UPDATE for last_fetch_at / last_success_at /
  consecutive_failures.** D42's per-source failure-counter model
  is the FetchScheduler's responsibility. The Fetcher is stateless
  between ticks per `docs/spec/architecture.md` §Ingest SPIs.
- **Pagination cap counter / admin-notification on saturation.**
  RSS has no pagination per `docs/design/01-architecture.md` §1.6
  (per-tick cap = 1 across every profile). Pagination plumbing
  lands with the first paginated Fetcher (Bluesky / Reddit /
  Nitter in Tier-3).
- **Retry / backoff / Retry-After honoring / per-source politeness
  window.** Per §1.6 these live on the FetchScheduler's per-tick
  boundary using `org.eclipse.microprofile.faulttolerance`; the
  Fetcher itself makes a single request per call.
- **infochat-ssrf module work / IP blocklist / DNS-rebind defense
  / redirect-cap enforcement / HttpClient wrapper.** Per the
  Option A two-ticket carve-out documented in Big-picture notes —
  separate ticket, lands before T1-C.
- **Changes to the M1-007a Fetcher SPI / NormalizedPost record
  shape.** The SPI is the contract this ticket consumes; if the
  impl reveals a missing field, escalate via the workflow.
- **Any change to infochat-collector/pom.xml.** The implementation
  uses JDK-built-in `javax.xml.stream` + `java.net.http.HttpClient`;
  no new dependency is introduced.
- **Any change to V1..V8 Flyway migrations.** `migration_touch:
  false`; this ticket is impl-only.

## Authorized test changes

- (none — this ticket adds two new test files plus two XML fixtures
  in `infochat-collector` and modifies no pre-existing tests. All
  M1-003 / M1-007 / M1-008 / M1-009 / M1-017 / M1-022 tests
  continue to pass unchanged.)

## Alternatives considered

- **Use Rome (`com.rometools:rome`) for parsing.** Rejected for v1:
  Rome would require adding the dep to the BOM (root pom.xml) AND
  to `infochat-collector/pom.xml` per the M1-001 BOM-supplies-versions
  invariant — two pom touches that push `files_budget` over the
  locked ceiling of 6. RSS 2.0 + Atom 1.0 are well-defined dialects
  and StAX handles them cleanly for the v1 NormalizedPost field
  set. If a future ticket hits real dialect-quirks pain (malformed
  feeds, exotic date formats, custom namespaces), that ticket can
  swap StAX for Rome in a focused diff with its own files_budget —
  the cost is paid by the ticket that needs the feature, not
  pre-paid here.
- **Use Apache Abdera.** Rejected: Atom 1.0 only — RSS 2.0 is
  out-of-scope for Abdera. v1 needs both formats since
  `bootstrap-sources.json`'s default category includes RSS feeds
  alongside Atom.
- **Use a per-Fetcher background thread + queue rather than a
  blocking `fetch(...)` return.** Rejected: the SPI signature is
  `List<NormalizedPost> fetch(...)` per M1-007a — blocking
  request/response, by spec design. The FetchScheduler runs each
  Fetcher invocation on a worker thread; backgrounding inside the
  Fetcher would double the concurrency surface for no benefit.
- **Combine RssFetcher and RssFeedParser into one class.**
  Considered. Rejected: separating transport from parsing keeps
  both testable in isolation (RssFeedParserTest doesn't need an
  HTTP server; RssFetcherTest doesn't need to assert per-field
  parse correctness). The split also matches the shape the
  Bluesky / Reddit / etc. fetchers will adopt in T3 — every
  Fetcher impl is "HTTP transport + dialect parser" so the
  per-fetcher pair is a deliberate pattern.
- **Use Quarkus Reactive (Mutiny `Uni<HttpResponse>`).** Rejected:
  the project's stack choice is virtual-threads + blocking style
  per the project_quarkus_jdk25 auto-memory, not Mutiny
  end-to-end. JDK 25's virtual threads make blocking I/O cheap
  enough that the Mutiny ergonomic tax isn't worth paying.
- **Land a minimal in-Fetcher scheme allowlist as a stopgap SSRF
  gate.** Rejected per the authoring handoff: a partial gate is
  the worst of both worlds — extra code to remove later AND a
  false sense of safety from a defense that doesn't carry the
  DNS-rebind / blocklist / redirect-cap surface the spec mandates.
  The class-header SSRF GATE TODO comment and the security-relevant
  flag are the explicit acknowledgment that the gate is deferred;
  silently shipping a half-gate would erase that acknowledgment.
- **Co-author `infochat-ssrf` in this group (Option B, three
  tickets).** Considered. Rejected per the authoring handoff —
  the operator defaulted to two tickets and authorized the
  documented carve-out (SSRF GATE TODO callout + body-prose
  pointer to the future infochat-ssrf ticket). The carve-out is
  bounded by the requirement that infochat-ssrf MUST land before
  T1-C wires the scheduler.
