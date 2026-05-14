# Session handoff — Tier 1 Group B: ingest sources (bootstrap loader + RSS Fetcher)

Paste the body below into a fresh Claude Code session as the opening
message. The session will author the T1-B ticket files and stop. Do
NOT include this preamble paragraph when pasting — only the fenced
block that follows.

---

```
We're continuing M1 ticket-driven work on the infochat repo. Fresh
session — read this brief instead of re-deriving from the codebase.

## State at handoff

- All Tier 0 tickets are done and merged on main (M1-001..M1-007 +
  M1-009).
- Tier 1 Group A (T1-A schema) is done and merged on main:
    M1-008 (umbrella per-(user, scope) isolation IT)
    M1-008a (identity + audit + last-admin trigger, V5 migration)
    M1-008b (sources + tags catalogues, V6 migration)
    M1-008c (joins + scope_preferences + post, V7 migration)
- M1-019 (stdout API-key redaction) and M1-020 (exception-message
  sanitization) are `status: deferred` with `deferred_reason:
  post-mvp-hardening` and empty `deferred_on`. They will run at the
  end of T1 once their guarded code paths (LLM call sites for M1-019;
  messaging adapter intake for M1-020) exist.
- M1-021 (identity/audit redteam remediation, V8+ migration) is
  `status: pending` and runnable, but per the operator's plan it
  runs at the end of T1 alongside M1-019/M1-020 — its application-
  side counterpart (the `SET LOCAL infochat.actor_id` call site
  discipline) only matters once T1-E's command handlers exist. The
  GUC-unset path is backward-compatible, so T1-B and onward can
  proceed without M1-021 being landed first.
- Flyway migrations on disk under
  infochat-core/src/main/resources/db/migration/:
    V1__init.sql, V2__roles.sql, V3__heartbeat.sql, V4__nologin.sql,
    V5__identity_audit.sql, V6__sources_tags.sql, V7__joins_post.sql.
  The bootstrap loader ticket below adds a new migration for
  `bootstrap_meta` (docs/design/02-schema.md §2.9.5 — operational
  helper, not yet migrated). At the authoring time this session
  runs, the next free integer is V8 IFF M1-021 has not landed; if
  M1-021 has been started/committed first it will own V8 and the
  bootstrap_meta migration takes V9. Re-grep the migration directory
  at /m1-tick start time and pick the next free integer; the file
  name suggested below is the EXPECTED slot when only V1..V7 exist.
- Branch is main, otherwise clean.

## What you do this session

Author ticket files in docs/plan/m1/tickets/ for the T1-B group.
The group is two tickets ("bootstrap-sources loader" + "RSS Fetcher"
per docs/plan/m1/drafts/session-grouping-plan.md §Tier 1) PLUS an
explicit open question about whether infochat-ssrf lands here or
as a third sibling ticket (see "Open question for the authoring
session" below). Default is two tickets; flip to three only with
the rationale documented inline.

These two tickets share heavy context:
  - docs/spec/architecture.md §Ingest SPIs (Fetcher SPI shape and
    bootstrap-sources discipline)
  - docs/design/07-deployment.md §7.6.1 (bootstrap-sources.json
    schema, loader behavior)
  - docs/design/01-architecture.md §1.4.2 (BootstrapLoader pseudocode)
  - docs/design/01-architecture.md §1.4.3 (Collector @Startup
    ordering — BootstrapLoader at priority 200; FetchScheduler at
    priority 400)

The fetcher impl is the first concrete binding to the M1-007a
Fetcher SPI; the bootstrap loader is the first @Startup bean on the
Collector that writes source rows (M1-008b's V6 schema) plus the
audit_log + bootstrap_meta operational pair.

When you finish, leave the new files UNTRACKED on main (workflow
rule: drafts ride untracked through /m1-tick start). Do NOT commit.

## ID allocation (LOCKED at the tail)

Per session-grouping-plan §"ID allocation": T1-B gets fresh IDs at
the tail at authoring time. M1-019/020 are deferred and M1-021 is
pending-but-end-of-T1, so the next free integer slots at this
session's start are M1-022 onwards. The IDs you author are:

  M1-022 — Bootstrap-sources loader (Collector @Startup bean +
           bootstrap_meta migration)
  M1-023 — RSS Fetcher (impl of Fetcher SPI for kind='rss')

If the authoring session decides to land infochat-ssrf as a sibling
(see open question), it becomes M1-023 and the RSS Fetcher slides
to M1-024.

Re-grep the tickets directory at /m1-tick start time to confirm
M1-022/M1-023 are still the next free slots before committing. If
a new ticket has been authored in the interim, take the next free
slot — the slug → file-name mapping is the only invariant; the
numeric ID is allocated mechanically.

## Where you are in the milestone

Tier 1 (MVP vertical slice) is in flight. T1-A (schema) is done.
This session opens T1-B (the first concrete ingest code). Tier 1
has six groups:
  T1-A schema (done)
  T1-B ingest sources (this session — 2 tickets, possibly 3)
  T1-C outbox/NOTIFY (outbox sink + LISTEN/NOTIFY + FetchScheduler
        wiring + provider_state + rehydrator)
  T1-D eval pipeline (Stage 1, LLM + Stage 2, tagger + embedding)
  T1-E adapter + router (umbrella + InMemoryAdapter + router + /help)
  T1-F first commands (/add-source, /summary)

After T1-B, the next session authors T1-C's detailed handoff JIT.
See docs/plan/m1/drafts/session-grouping-plan.md for the full plan.

## Open question for the authoring session

**Does infochat-ssrf land in this group?** Per docs/spec/security.md
§SSRF and outbound connections, every outbound connection from the
Collector MUST run through a fail-closed allowlist (the
`infochat-ssrf` shared Maven module enumerated in
docs/design/01-architecture.md §1.2). M1-007a explicitly listed
infochat-ssrf in its out_of_scope as "a separate Tier-0/Tier-1
ticket; not introduced here." The first caller of infochat-ssrf is
the RSS Fetcher in this group.

Two viable shapes:

- **Option A (default — 2 tickets):** Author M1-022 (loader) and
  M1-023 (RSS Fetcher) only. RSS Fetcher initially uses a minimal
  in-fetcher URL validation (scheme allowlist, no private-IP
  blocklist), with `infochat-ssrf` deferred to a separate ticket
  authored just before T1-C or T1-E begins. Acceptable IFF you
  flag the gap explicitly in the RSS Fetcher's Big-picture notes
  AND the ticket's `deferred_on` (or follow-up `blocks`) points at
  the future infochat-ssrf ticket. This option ships a v1
  spec-violating Fetcher temporarily — the operator decides whether
  that's tolerable for the dev workflow.

- **Option B (3 tickets):** Author M1-022 (loader), M1-023
  (infochat-ssrf module — IP blocklist + DNS-rebind defense +
  redirect cap + timeout caps + reusable HttpClient wrapper),
  M1-024 (RSS Fetcher impl that consumes infochat-ssrf from day
  one). T1-B becomes 3 tickets but the spec commitment is satisfied
  end-to-end with no temporary carve-out. RECOMMENDED for spec
  fidelity.

Pick at the top of the session; document the choice in the first
ticket's "Implementation notes." Do NOT split the difference (a
half-baked SSRF gate inside the Fetcher is the worst of both).

## Locked decisions for the two-ticket shape (Option A)

If Option A is picked, the following are LOCKED. Don't re-debate.

### M1-022 — Bootstrap-sources loader

- blocked_by: [M1-008a, M1-008b]
  (audit_log from V5 + source/tag from V6; the loader does not
  touch identity tables, joins, or post — independent of M1-008c
  and M1-021)
- complexity: medium
- risk: medium
- security_relevant: TRUE
  (writes to source — a privileged catalogue surface — and writes
  the BOOTSTRAP_SOURCE_LOAD audit row that establishes the
  loader's audit trail; a buggy loader can mint or shadow sources
  the operator did not intend)
- migration_touch: TRUE
  (adds Flyway V<N> for bootstrap_meta — single-row operational
  helper per docs/design/02-schema.md §2.9.5)
- round_cap: 2
- files_budget: 8
- files_scope:
    - infochat-core/src/main/resources/db/migration/V<N>__bootstrap_meta.sql
    - infochat-collector/src/main/java/io/infochat/collector/bootstrap/BootstrapSourcesEntry.java
    - infochat-collector/src/main/java/io/infochat/collector/bootstrap/BootstrapSourcesParser.java
    - infochat-collector/src/main/java/io/infochat/collector/bootstrap/BootstrapLoader.java
    - infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapSourcesParserTest.java
    - infochat-collector/src/test/java/io/infochat/collector/bootstrap/BootstrapLoaderIT.java
    - src/test/resources/bootstrap-sources-fixture.json (or wherever
      the @QuarkusTest fixture conventionally lives)
- Scope:
  * Flyway V<N> migration creating `bootstrap_meta` per
    docs/design/02-schema.md §2.9.5:
      - `id SMALLINT PK DEFAULT 1 CHECK (id = 1)` (single-row guard)
      - `last_loaded_sha256 TEXT NOT NULL`
      - `last_loaded_at TIMESTAMPTZ NOT NULL`
      - `last_entry_count INT NOT NULL`
      - `last_loader_version TEXT NOT NULL`
      - Per-table GRANTs: SELECT + INSERT + UPDATE to
        infochat_collector (the loader writes); SELECT to
        infochat_provider (admin /status reads); REVOKE DELETE from
        both service roles (operational helper, never deleted).
  * `BootstrapSourcesEntry` Java record matching the JSON schema in
    docs/design/07-deployment.md §7.6.1 (kind, identifier, name,
    category, tags, optional config). Strict parsing: any unknown
    top-level field is rejected; tags array must be non-empty.
  * `BootstrapSourcesParser` parses the JSON file, validates the
    schema, canonicalizes Nostr filter-spec identifiers BEFORE
    upsert (sort JSON object keys lexicographically, compact
    whitespace — per docs/spec/architecture.md §Ingest SPIs Source
    identity), and rejects rss/bluesky/nitter/reddit/youtube/odysee
    entries whose `config` is non-null (per §7.6.1 Per-kind config
    shape).
  * `BootstrapLoader` Quarkus `@Startup` bean at @Priority(200) (per
    docs/design/01-architecture.md §1.4.3 Collector table). Steps:
      1. Resolve infochat.bootstrap.sources-file (default
         "bootstrap-sources.json", relative to working dir; absolute
         path also accepted).
      2. Read + SHA-256 the file contents BEFORE parsing.
      3. Call BootstrapSourcesParser.
      4. In a single transaction:
           - For each entry, upsert into `source` keyed by (kind,
             identifier) using ON CONFLICT (kind, identifier) DO
             UPDATE — update name, category, bootstrap_tags, config
             in place. Never delete; admin uses `/remove-source`.
             SKIP entries where the existing row has `deleted_at IS
             NOT NULL` (operator intent not silently overridden, per
             §2.2.1 Soft-delete semantics).
           - Union all `tags` across entries and upsert into `tag`
             with `source_origin = 'bootstrap'`.
           - INSERT one `audit_log` row with action =
             'BOOTSTRAP_SOURCE_LOAD', target_kind = 'system',
             target_id = the file SHA-256, details_json including
             the file path and entry count.
           - Upsert `bootstrap_meta` (single row) with the SHA,
             timestamp, entry count, and loader version
             (Quarkus build-info-style constant or
             `@ConfigProperty(name="infochat.version")` — pick one
             and document).
      5. Log a one-line summary at INFO: "BootstrapLoader: loaded N
         sources from <path> (sha=...)."
  * Idempotency contract: re-running the loader against the same
    file is a no-op for `source` (every UPDATE row has identical
    column values), produces a new `audit_log` row (each run
    records its own audit trail), and updates `bootstrap_meta`
    (last_loaded_at + sha + count refresh). The IT below exercises
    the no-op behavior.
- Out-of-scope MUST list:
    - any /add-source / /remove-source / /source-enable /
      /source-disable command handler (Provider-side; T1-F territory)
    - any infochat-ssrf module work (its own ticket per Option A;
      see Open question)
    - any bootstrap-assets.json loader code (asset commands are
      Tier 2 T2-H, decision D39 — out of scope here even though
      design §1.4.2 step 6-8 enumerates the asset path on the
      same @Startup bean; THIS ticket only does the sources half)
    - any FetchScheduler / per-kind tick logic (T1-C territory at
      @Priority(400))
    - any change to V1..V7 migrations already on disk
    - any Java entity class for source/tag (raw JDBC INSERTs in
      the loader; the entity layer for /list-sources etc. lands
      with the T1-F Provider-side handlers)
- Spec_refs (verify all anchors before citing):
  * docs/spec/architecture.md §Ingest SPIs
  * docs/spec/deployment.md §Operator inputs (bootstrap-sources.json
    is an operator input)
  * docs/spec/schema.md §Sources and tags
  * docs/design/07-deployment.md §7.6.1 bootstrap-sources.json
  * docs/design/01-architecture.md §1.4.2 Bootstrap loader
  * docs/design/01-architecture.md §1.4.3 Startup-bean ordering
  * docs/design/02-schema.md §2.9.5 bootstrap_meta
  * docs/spec/security.md §DB roles (per-table GRANT discipline)
- decision_refs: D7, D38, D42

### M1-023 — RSS Fetcher implementation

- blocked_by: [M1-007a]
  (the Fetcher SPI must exist; otherwise independent of M1-022 —
  the loader and fetcher are parallel work, the fetcher's tests
  use in-memory fixture sources, not bootstrap-loaded rows)
- complexity: medium
- risk: medium
- security_relevant: TRUE under Option A (the v1 spec mandates
  SSRF gating on every outbound connection from the Collector;
  shipping a Fetcher without infochat-ssrf is a deliberate
  carve-out and the ticket must call this out explicitly). Under
  Option B (infochat-ssrf landed as M1-023, this becomes M1-024),
  security_relevant is FALSE — the SSRF gate is in the dependency
  module.
- migration_touch: FALSE
- round_cap: 2
- files_budget: 6
- files_scope:
    - infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFetcher.java
    - infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFeedParser.java
    - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFetcherTest.java
    - infochat-collector/src/test/java/io/infochat/collector/fetcher/rss/RssFeedParserTest.java
    - infochat-collector/src/test/resources/fixtures/rss/atom-sample.xml
    - infochat-collector/src/test/resources/fixtures/rss/rss20-sample.xml
- Scope:
  * `RssFetcher implements io.infochat.core.ingest.Fetcher` — the
    `fetch(long sourceId, String identifier)` method issues a
    single HTTP GET against `identifier`, parses the response body
    as RSS 2.0 or Atom 1.0, and returns one `NormalizedPost` per
    feed item. Per docs/design/01-architecture.md §1.6 RSS has no
    pagination — one request per tick. The fetcher is stateless
    between ticks; "what's new since last time" is the post-table
    dedup query and lives on the OutboxRehydrator side (T1-C).
  * Library choice: Rome (`com.rometools:rome`) is the obvious
    JVM RSS+Atom parser. Pick at authoring time; if the BOM doesn't
    already pin it, add the dep via the BOM (M1-001 invariant: no
    per-pom `<version>` elements). Alternatives — Apache Abdera
    (Atom only), or raw `javax.xml.stream` — should be documented
    in "Alternatives considered" with the rejection reason.
  * `NormalizedPost` field mapping (from docs/spec/schema.md
    §Posts and derivatives + spec §UID derivation + the
    NormalizedPost record contract):
      - sourceId: the `long sourceId` argument
      - upstreamIdentifier: the RSS `<guid>` if present, else the
        item's `<link>`. Never null (the SPI contract); if neither
        is present, raise — the entry is malformed.
      - title: the RSS `<title>` (nullable per SPI contract — RSS
        items may legitimately have no title)
      - body: the RSS `<description>` or Atom `<content>`. The
        spec mandates plain text (`docs/spec/schema.md`
        §Posts — "always plain text (HTML stripped at ingest)");
        Stage 1's HTML sanitizer is T1-D's responsibility, NOT this
        fetcher's, so RssFetcher passes the raw HTML through and
        relies on Stage 1 to sanitize. Document this hand-off
        boundary in the ticket body.
      - url: the RSS `<link>` (nullable per SPI contract; some
        feeds omit a per-item link)
      - publishedAt: the RSS `<pubDate>` or Atom `<published>`,
        parsed as `Instant`; nullable per SPI contract.
      - fetchedAt: `Instant.now()` at fetch time.
      - rawMetadata: empty map for RSS (no per-item richer metadata
        worth preserving in v1; per-source metadata like author and
        category can be carried on the spec's `post.author` /
        `post.tags[]` columns separately — but those columns are
        populated by the tagger / spec-driven post-write paths,
        not by the Fetcher SPI).
  * Tests:
      - `RssFeedParserTest` exercises the parser against the two
        XML fixtures (`atom-sample.xml`, `rss20-sample.xml`) and
        asserts: correct upstream identifier extraction (guid
        preferred over link, link fallback), correct title and
        body extraction, correct publishedAt parsing for both
        feed formats, correct handling of items with no link
        and no guid (raise), correct handling of items with no
        title (null in NormalizedPost).
      - `RssFetcherTest` uses a Quarkus WireMock test resource or
        an in-process HTTP server (e.g., Vert.x `HttpServer` or
        Sun `HttpServer`) to serve a fixture RSS feed, then calls
        `RssFetcher.fetch(1L, "http://localhost:<port>/feed.xml")`
        and asserts the returned `List<NormalizedPost>` matches
        the fixture row-for-row.
  * Under Option A only: a top-level "SSRF GATE TODO" comment at
    the top of `RssFetcher.java` plus the same note in the ticket's
    "Big-picture notes," with the ticket adding a follow-up
    placeholder for "wire RssFetcher to infochat-ssrf when the
    module lands." Under Option B, this carve-out does not exist
    — the gate is plumbed from day one.
- Out-of-scope MUST list:
    - any FetchScheduler / per-tick @Scheduled wiring (T1-C
      territory at @Priority(400) per design §1.4.3)
    - any outbox sink (T1-C — RssFetcher returns
      List<NormalizedPost> to its caller; the caller persists
      RAW rows)
    - any Stage 1 HTML sanitization (T1-D)
    - any Bluesky / Nitter / Reddit / YouTube / Odysee / Nostr
      fetcher impl (each is its own Tier-3 T3-B / T3-C ticket
      binding to the same Fetcher SPI)
    - any source-row UPDATE for last_fetch_at / last_success_at
      / consecutive_failures (D42 failure-counter model is the
      FetchScheduler's responsibility, not the Fetcher's)
    - any pagination cap counter or admin notification (also
      FetchScheduler in T1-C)
    - any retry / backoff / Retry-After honoring logic
      (microprofile-faulttolerance integration lives with the
      FetchScheduler)
- Spec_refs (verify all anchors before citing):
  * docs/spec/architecture.md §Ingest SPIs (Fetcher contract)
  * docs/spec/schema.md §Posts and derivatives (NormalizedPost
    field mapping)
  * docs/design/01-architecture.md §1.6 Concurrency and rate
    limiting (RSS no-pagination commitment)
  * docs/design/00-mvp.md §Fetcher (RSS-only in MVP)
  * docs/spec/security.md §SSRF and outbound connections
    (Option A only — for the explicit carve-out note)
- decision_refs: D38

## Spec anchors verified (use ONLY these; others MUST be re-verified)

These were confirmed by `grep -n '^## \|^### ' <file>` at this
session's authoring time. Any spec_ref you cite that ISN'T in this
list, verify the anchor exists by reading the cited file before
using it. The clarity-preflight subagent will FAIL the ticket if a
spec_ref doesn't resolve.

  docs/spec/architecture.md §Inter-service communication        (line 33)
  docs/spec/architecture.md §Ingest SPIs                        (line 138)
  docs/spec/architecture.md §Pipelines                          (line 316)
  docs/spec/architecture.md §Architectural principles           (line 334)
  docs/spec/security.md §Threat model                           (line 12)
  docs/spec/security.md §Trust boundaries                       (line 38)
  docs/spec/security.md §Ingest pipeline (security side)        (line 56)
  docs/spec/security.md §SSRF and outbound connections          (line 120)
  docs/spec/security.md §Per-source trust boundaries            (line 157)
  docs/spec/security.md §DB roles                               (line 943)
  docs/spec/schema.md §Identity and access                      (line 13)
  docs/spec/schema.md §Sources and tags                         (line 175)
  docs/spec/schema.md §Posts and derivatives                    (line 245)
  docs/spec/schema.md §Invariants                               (line 554)
  docs/design/01-architecture.md §1.2 Module layout (Maven)     (line 89)
  docs/design/01-architecture.md §1.4.2 Bootstrap loader        (line 400)
  docs/design/01-architecture.md §1.4.3 Startup-bean ordering   (line 433)
  docs/design/01-architecture.md §1.6 Concurrency and rate limiting (line 568)
  docs/design/02-schema.md §2.2 Sources & tags                  (line 456)
  docs/design/02-schema.md §2.2.1 source                        (line 458)
  docs/design/02-schema.md §2.2.2 tag                           (line 504)
  docs/design/02-schema.md §2.3 Posts (ingest)                  (line 592)
  docs/design/02-schema.md §2.3.1 post                          (line 594)
  docs/design/02-schema.md §2.9.5 bootstrap_meta                (line 1457)
  docs/design/07-deployment.md §7.6 Bootstrap files             (line 316)
  docs/design/07-deployment.md §7.6.1 bootstrap-sources.json    (line 318)
  docs/design/00-mvp.md §Fetcher (RSS-only)                     (re-verify)

## Style requirements

Match M1-008a + M1-008b + M1-008c in docs/plan/m1/tickets/ — those
are the closest structural analogues for ticket-frontmatter shape
and runnable acceptance criteria. M1-007a/b/c are the closest
analogues for SPI-binding wiring and module-layer scope. Read those
once for style. Read docs/process/ticket-template.md once for the
canonical schema. Then write.

Length per ticket: M1-022 ~260-320 lines (BootstrapLoader has real
parsing + transactional discipline). M1-023 ~220-280 lines (the
Fetcher impl is straightforward but the NormalizedPost mapping
needs careful per-field documentation).

Style points to preserve:
- Frontmatter follows docs/process/ticket-template.md schema exactly.
- Acceptance criteria are RUNNABLE grep / test / SQL assertions, not prose.
- spec_refs cite real §anchors that resolve.
- out_of_scope is specific and concrete, not generic.
- Body sections: Context, Definition of Done, Implementation notes,
  Big-picture notes, Out-of-scope expansion, Authorized test changes,
  Alternatives considered.

Use today's date for `created:` and `last_updated:`.

## Token-budget discipline

- DO read M1-008a + M1-008b once for style.
- DO read M1-007a once (the Fetcher SPI shape — required for M1-023).
- DO read docs/process/ticket-template.md once.
- DO read docs/spec/architecture.md §Ingest SPIs once (single pass).
- DO read docs/design/07-deployment.md §7.6 once.
- DO read docs/design/01-architecture.md §1.4.2 + §1.4.3 + §1.6
  in one pass (they're contiguous-ish).
- DO read docs/design/02-schema.md §2.2 + §2.3 + §2.9.5 in one pass.
- DO NOT spawn Explore or any other subagent.
- DO NOT pre-load the full docs/spec/ tree.
- DO NOT re-read sections you already loaded.

## After authoring both tickets

1. Eyeball each frontmatter parses cleanly.
2. Confirm both tickets' `out_of_scope` correctly punts the
   FetchScheduler, outbox sink, and Stage 1 sanitization to T1-C /
   T1-D. The reviewer's negative-space check is sharper when the
   exclusions are concrete.
3. Confirm M1-022's migration filename matches the next free
   integer at this moment (re-grep `infochat-core/src/main/resources
   /db/migration/` and pick).
4. If Option B (3 tickets), also confirm infochat-ssrf's ticket
   files_scope lists the shared Maven module's pom.xml + Java
   sources + the IP-blocklist test resource, and that M1-024
   (RSS Fetcher) lists infochat-ssrf in `blocked_by`.
5. Print a one-paragraph summary: "T1-B ingest sources drafted as
   M1-022 (BootstrapLoader) and M1-023 (RssFetcher) [+ M1-024 if
   Option B] under docs/plan/m1/tickets/. Untracked on main. The
   user runs /m1-tick start M1-022 (or M1-023 first if running
   in parallel — both are independent runnable-now once committed)
   when ready."
6. STOP. Do NOT commit. Do NOT run /m1-tick start.

## What you do NOT do

- Do NOT commit any ticket file.
- Do NOT run /m1-tick start or any other /m1-tick subcommand.
- Do NOT begin authoring T1-C, T1-D, T1-E, or T1-F tickets. Those
  are separate sessions with their own JIT handoffs.
- Do NOT touch M1-019 / M1-020 / M1-021. Their `deferred_on` (or
  `blocks`) fields get updated by the T1-D / T1-E sessions or by
  the operator at end-of-T1, not by this session.
- Do NOT add a FetchScheduler, outbox sink, OutboxRehydrator, or
  any LISTEN/NOTIFY wiring. All of those are T1-C.
- Do NOT add Stage 1 HTML sanitization or Unicode normalization.
  That is T1-D.
- Do NOT spawn Explore or any other subagent.

## Workflow ground rules

- One ticket = one file under docs/plan/m1/tickets/M1-NNN-<slug>.md.
- Slug per docs/process/workflow.md §Naming conventions: lowercased
  ASCII [a-z0-9-], truncated to 30 chars, trailing hyphen trimmed.
- Drafts ride UNTRACKED through /m1-tick start.
- "M" prefix → /m1-tick flow; "process:" prefix → direct commit on
  main. This handoff itself is a `process:` commit; the tickets
  it authors are M-prefix commits later.

## Your immediate task when the user says "go"

1. Re-grep `infochat-core/src/main/resources/db/migration/` to
   confirm the next free integer for M1-022's bootstrap_meta
   migration (V8 if M1-021 hasn't landed, V9 if it has).
2. Re-grep `docs/plan/m1/tickets/` for `^id: M1-` to confirm the
   next free numeric ID (M1-022 expected; bump if a new ticket
   was authored since this handoff).
3. Decide Option A vs. Option B for infochat-ssrf and document
   the choice in the first ticket's "Implementation notes."
4. Read M1-008a + M1-008b in docs/plan/m1/tickets/ once for style.
5. Read M1-007a in docs/plan/m1/tickets/ once (Fetcher SPI shape).
6. Read docs/process/ticket-template.md once.
7. Read docs/spec/architecture.md §Ingest SPIs once.
8. Read docs/design/07-deployment.md §7.6 once.
9. Read docs/design/01-architecture.md §1.4.2 + §1.4.3 + §1.6 once.
10. Read docs/design/02-schema.md §2.9.5 once (bootstrap_meta).
11. Write M1-022-bootstrap-sources-loader.md.
12. Write M1-023-rss-fetcher.md (or M1-023-infochat-ssrf.md +
    M1-024-rss-fetcher.md under Option B).
13. Print the summary. STOP.
```
