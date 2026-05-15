---
id: M1-028
title: Collector outbox (FetchScheduler + PostPersister + OutboxRehydrator)
status: done
created: 2026-05-15
last_updated: 2026-05-16
reviews:
  - round: 1
    date: 2026-05-16
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 1646
      removed: 30
escalations:
  - date: 2026-05-15
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — pre-implementation premise conflict surfaced by developer reading
      the M1-007a SPI contract against this ticket's DoD before any code
      was written.

      The DoD requires PostPersister to implement a content-hash UID
      fallback when `normalized.upstreamIdentifier` is null or empty
      (acceptance item 2, Definition of Done bullet on UID derivation).
      The M1-007a SPI at
      `infochat-core/src/main/java/io/infochat/core/ingest/NormalizedPost.java:17`
      declares `upstreamIdentifier` as "Never null", and M1-023's
      `RssFeedParser.java:158-161` enforces this — RSS items lacking both
      `<guid>` and `<link>` throw `IllegalArgumentException`. The fallback
      branch is therefore defensive code for a scenario the SPI contract
      forbids, in conflict with CLAUDE.md §"No defensive code for
      impossible scenarios" (PostPersister is internal code consuming an
      internal SPI). Additionally, design notes §2.3.1 do not inline the
      per-kind "RSS-volatile" canonicalization rules the spec §UID
      derivation defers to design.

      The clarity reviewer flagged the canonicalization-delegation as a
      judgment-call WARN (SELF-CONTAINED-CHECK warning #2), but did not
      cross-check against the SPI contract; the deeper rule conflict was
      surfaced in main-session validation post-clarity.
revisions:
  - date: 2026-05-16
    reason: "post-impl senior-review: eliminate four v1-shortcut shapes in favor of long-term-optimal alternatives"
    summary: |
      Fresh-eyes review after the round-1 implementation passed `mvn
      verify` flagged four places where the in-progress code traded
      correctness / performance / coupling cleanliness for short-term
      simplicity. All four promoted to first-class fixes; ticket
      amended for scope/acceptance consistency.

      (1) `infochat-collector/pom.xml` adds `quarkus-messaging`. The
      prior out_of_scope claim that "SmallRye Reactive Messaging is
      already on the Quarkus BOM" was incorrect — `@Channel` /
      `@Incoming` injection points fail to resolve without the
      `quarkus-messaging` extension. pom.xml moves into `files_scope`;
      the corresponding `out_of_scope` line is dropped.

      (2) `OutboxRehydrator.rehydrate()` was emitting to the eval-queue
      channel INSIDE the JDBC `ResultSet` loop, holding the
      `Connection` slot for the duration. Under SmallRye back-pressure
      (a realistic outcome after a long outage with N RAW posts and a
      saturated in-memory channel buffer) the `emitter.send` blocks,
      pinning the connection — Agroal pool starvation against the live
      serving path. Fix: collect `PersistedPostKey`s into a `List`,
      release the Connection via try-with-resources exit, THEN emit.
      Connection slot is freed before any back-pressure can block.

      (3) The `DEFAULT_RSS_INTERVAL = "5m"` constant in `FetchScheduler`
      existed solely to satisfy acceptance item 11's literal `"5m"`
      grep (the `@Scheduled(every = "{infochat.fetch.rss.interval:5m}")`
      annotation contains the substring `:5m}` — quote-5-m-quote is not
      present). The default value belongs in `application.properties`
      where Quarkus config defaults conventionally live; the
      `@Scheduled` annotation references the property without an inline
      default. Acceptance item 11 is rewritten to grep
      `application.properties` for the property declaration. The
      orphaned constant is removed.
      `infochat-collector/src/main/resources/application.properties`
      moves into `files_scope`.

      (4) `FetchScheduler` was instantiating `new RssFetcher()` inline,
      defeating the Fetcher SPI's polymorphism point and forcing
      `FetchSchedulerIT` into a `TestRssFetcher extends RssFetcher`
      override hack to bypass the SSRF gate (the test seam pattern
      degenerated further when direct field assignment failed against
      the CDI client proxy, requiring an awkward setter/getter pair).
      Fix: `@ApplicationScoped` on `RssFetcher` (one-line change),
      `@Inject RssFetcher` on `FetchScheduler` (drops the test seam
      entirely), `QuarkusMock.installMockForType` in the IT (the
      Quarkus-idiomatic CDI bean substitution). Future Fetcher kinds
      (Bluesky, Nostr, Reddit, etc.) plug into the same injection
      surface via `Instance<Fetcher>` without further scheduler
      changes. `RssFetcher.java` moves into `files_scope`; the
      out_of_scope entry forbidding RssFetcher modifications is
      narrowed to forbid changes beyond the `@ApplicationScoped`
      annotation.

      Additionally `TestEvalQueueConsumer.java` (test-only helper
      consumer added during round-1 implementation) is formalized in
      `files_scope`; it was an unlisted addition before this revision.

      `files_budget` grows 8 → 12 to accommodate pom.xml,
      application.properties, RssFetcher.java, and
      TestEvalQueueConsumer.java.
  - date: 2026-05-15
    reason: "premise-fail rework (Option 1 — drop spec's content-hash UID fallback; trust SPI's never-null contract)"
    summary: |
      Dropped the content-hash UID fallback path from acceptance item 2,
      from the Definition of Done's UID-derivation bullet, and from the
      Implementation notes "UID derivation lives in PostPersister" bullet.
      PostPersister now trusts the M1-007a SPI's
      `NormalizedPost.upstreamIdentifier` "Never null" contract and
      throws on a null arrival (system-boundary validation against
      SPI-contract violation, not defensive code). Added one out_of_scope
      entry covering the fallback's deliberate omission and one
      "Alternatives considered" entry documenting the security /
      performance / complexity rationale (NFKC homoglyph collisions,
      multi-MB body DoS on the ingest hot path, cross-JRE NFKC drift,
      canonicalization-rule version drift). A follow-up `spec:` commit
      aligns docs/spec/schema.md §UID derivation with this reality.
    prior_acceptance_item_2: |
      "PostPersister.java computes the post UID per docs/spec/schema.md
      §UID derivation as `sha256(source_id || '|' || upstream_identifier)`
      lower-case hex when upstream_identifier is non-null, with the
      content-hash fallback path when upstream_identifier is null —
      grep -E 'sha-?256|SHA-256|MessageDigest\\.getInstance\\(\"SHA-256\"\\)'
      PostPersister.java returns at least one match AND
      grep -E 'upstream_?[Ii]dentifier' PostPersister.java returns at
      least one match AND
      grep -E 'canonical_?[Bb]ody|content_?[Hh]ash|null|isEmpty\\(\\)'
      PostPersister.java returns at least one match in the UID-derivation
      context (the fallback branch when upstream_identifier is absent)"
clarity_check:
  date: 2026-05-15
  verdict: WARN
  warnings:
    - "ACCEPTANCE-VS-DOD-CONSISTENT: Item 18 uses a heterogeneous aggregate count (\"at least three new matches across the three new IT classes\" in failsafe-reports). The count is satisfiable, but the aggregate form hides per-class structural differences (PostPersisterIT ≥2 tests, OutboxRehydratorIT ≥3 tests, FetchSchedulerIT ≥1 test per prior items). Recommend splitting into three per-class assertions: (a) \"PostPersisterIT appears in failsafe-reports with Tests run ≥ 1\", (b) \"OutboxRehydratorIT appears in failsafe-reports with Tests run ≥ 1\", (c) \"FetchSchedulerIT appears in failsafe-reports with Tests run ≥ 1\". This makes each class's execution independently verifiable."
    - "SELF-CONTAINED-CHECK: Acceptance item 2's content-hash fallback path delegates the exact RSS-volatile canonicalization rules to docs/design/02-schema.md §2.3.1 uid comment. The delegation is narrow (one per-kind rule, only RSS in scope for T1-C) and the grep assertion tests for the fallback branch's existence, not for the canonicalization rule's correctness, so the implementer can produce a passing test without reading the design notes for this specific detail. Judgment-call WARN; the implementer may still wish to read §2.3.1 for the exact volatile-stripping rules."
  blockers: []
blocked_by:
  - M1-007a
  - M1-008c
  - M1-022
  - M1-023
files_budget: 12
files_scope:
  - infochat-collector/pom.xml
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/main/java/io/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/main/java/io/infochat/collector/outbox/EvalQueueProducer.java
  - infochat-collector/src/main/java/io/infochat/collector/outbox/OutboxRehydrator.java
  - infochat-collector/src/main/java/io/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFetcher.java
  - infochat-collector/src/test/java/io/infochat/collector/outbox/PostPersisterIT.java
  - infochat-collector/src/test/java/io/infochat/collector/outbox/OutboxRehydratorIT.java
  - infochat-collector/src/test/java/io/infochat/collector/outbox/TestEvalQueueConsumer.java
  - infochat-collector/src/test/java/io/infochat/collector/fetch/FetchSchedulerIT.java
  - infochat-collector/src/test/resources/fixtures/outbox/feed-fixture.xml
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - any Stage 1 (HTML sanitizer + prompt-injection regex) / Stage 2 (SecurityStage2Judge LLM) / tagger / entity-extractor / embedding-worker / status→READY transition logic (T1-D territory per docs/design/01-architecture.md §1.3.4; this ticket's posts stay at `status='RAW'` for the duration of every IT — no test asserts a transition out of `'RAW'`)
  - any `pg_notify('new_post', …)` emit (also T1-D — fires from the eval pipeline's stage 5 per docs/design/01-architecture.md §1.3.4 step 5 after `post.status` flips to `'READY'`; the NOTIFY emit and the status flip are one logical event)
  - any per-source cadence override, max-page cap, `Retry-After` honoring, `org.eclipse.microprofile.faulttolerance` integration, or per-source politeness window (later ticket — T1-C ships a single global RSS interval per docs/design/01-architecture.md §1.6; per-kind / per-source cadence overrides land with the v2 spec amendment per docs/spec/architecture.md §Ingest SPIs 'v1 has no per-source interval override')
  - any `source.status` machine transitions or D42 failure-counter wiring (`last_fetch_at`, `last_success_at`, `consecutive_failures`, `active → failed`) — admin source-status management is Tier-2 T2-B per docs/design/01-architecture.md §1.6 'D42 threshold-based active → failed transition applies after N consecutive failures'; T1-C's failure handling is per-tick WARN-log only, no source-row UPDATE
  - any StreamSource implementation, NostrStreamSource, or StreamSourceSupervisor lifecycle wiring (`@Priority(450)`) — Tier-3 T3-C territory per docs/design/01-architecture.md §1.4.3 Collector table; this ticket wires Fetchers only
  - any Provider-side reconciler, listener, `provider_state` code, or `NewPostHandler` (M1-027 territory; the two T1-C tickets share no runtime code)
  - any change to V1..V8 Flyway migrations already on disk (migration_touch is false; this ticket is impl-only — if a follow-up authoring session lands M1-021 / M1-027's migrations in the interim, this ticket is unaffected because it does not modify any migration)
  - any Bluesky / Nitter / Reddit / YouTube / Odysee Fetcher implementation (each binds to the same M1-007a Fetcher SPI but is its own Tier-3 T3-B ticket; this ticket schedules `kind='rss'` only because M1-023's RssFetcher is the sole concrete Fetcher impl in M1)
  - any asset Fetcher dispatch or `price_snapshot` write path (Tier-2 T2-H per decision D39 — asset Fetchers bypass the outbox entirely per docs/design/01-architecture.md §1.3.3 'NEVER through Stage 1/2, tagger, entity extractor, or embedding'; this ticket wires the post-outbox path only)
  - any Stage 1 HTML strip / NFKC / regex redaction on `NormalizedPost.body` (T1-D — Stage 1 is the sanitization boundary per docs/spec/security.md §Ingest pipeline; the persisted post body in `'RAW'` carries the raw HTML the Fetcher returned, with HTML strip happening downstream)
  - any messaging adapter / CommandRouter / `/help` / `/add-source` / first-command implementation (T1-E and T1-F territory)
  - any infochat-provider module change (this ticket is collector-side only)
  - any modification to the M1-007a Fetcher SPI / NormalizedPost record shape, or to the M1-023 RssFeedParser class (the SPI is consumed unchanged; if the consumer reveals a missing field, escalate via the workflow)
  - any modification to M1-023's RssFetcher beyond adding `@ApplicationScoped` for CDI injection — the `fetch()` implementation, the package-private `RssFetcher(SsrfGuardedHttpClient)` test-seam constructor, the `RssFetchException` class, and the `SsrfGuardedHttpClient` interaction all remain frozen. The annotation is the only allowed delta; if a wider RssFetcher change is needed, escalate (Option 4 in the post-impl review revision is bounded to one annotation)
  - "any content-hash UID fallback path for posts with null `upstreamIdentifier` (the spec §UID derivation `sha256(source_id || '|' || canonical_body)` fallback). The M1-007a SPI declares `NormalizedPost.upstreamIdentifier` as 'Never null' (`infochat-core/src/main/java/io/infochat/core/ingest/NormalizedPost.java:17`) and M1-023's RssFeedParser enforces this (RSS items with neither `<guid>` nor `<link>` throw); implementing the fallback would be defensive code for an SPI-forbidden scenario per CLAUDE.md §No defensive code for impossible scenarios. A separate `spec:` commit aligns spec wording with this reality. If a future Fetcher kind needs nullable `upstreamIdentifier`, that's a focused future ticket loosening the SPI + landing per-kind canonicalization rules in design + adding the fallback in PostPersister in one coordinated change."
acceptance:
  - "PostPersister.java exists and inserts one post row per call with `status='RAW'` and all per-stage `*_done` flags FALSE per docs/spec/schema.md §Invariants (Invariant 5) and docs/design/02-schema.md §2.3.1 — grep -E 'public\\s+class\\s+PostPersister' PostPersister.java returns at least one match AND grep -E 'INSERT\\s+INTO\\s+post' PostPersister.java returns at least one match AND grep -E \"'RAW'|status\\s*=\\s*'RAW'\" PostPersister.java returns at least one match"
  - "PostPersister.java computes the post UID as `sha256(source_id || '|' || upstream_identifier)` lower-case hex. The M1-007a SPI at `infochat-core/src/main/java/io/infochat/core/ingest/NormalizedPost.java:17` declares `upstreamIdentifier` as 'Never null'; PostPersister trusts the contract — no content-hash fallback (the spec §UID derivation fallback is deliberately not implemented in M1; see Implementation notes and Alternatives considered). A null arrival surfaces as `IllegalArgumentException` (system-boundary validation against an SPI-contract violation, not defensive code) — grep -E 'sha-?256|SHA-256|MessageDigest\\.getInstance\\(\"SHA-256\"\\)' PostPersister.java returns at least one match AND grep -E 'upstream_?[Ii]dentifier' PostPersister.java returns at least one match AND grep -E 'canonical_?[Bb]ody|content_?[Hh]ash' PostPersister.java returns zero matches (no fallback path implemented) AND grep -E 'requireNonNull|throw\\s+new\\s+IllegalArgumentException|Objects\\.requireNonNull' PostPersister.java returns at least one match in the UID-derivation context (the SPI-contract assertion on `upstreamIdentifier`)"
  - "PostPersister.java uses `ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING` to silently dedup duplicate fetches in the same partition per docs/design/02-schema.md §2.3.1 belt-and-suspenders UNIQUE — grep -E 'ON CONFLICT\\s*\\(\\s*source_id\\s*,\\s*upstream_identifier\\s*,\\s*fetched_at\\s*\\)\\s+DO NOTHING' PostPersister.java returns at least one match"
  - "EvalQueueProducer.java is a SmallRye Reactive Messaging emitter that writes the post id to the in-memory channel named `eval-queue` per CLAUDE.md §Stack ('SmallRye Reactive Messaging (in-memory channels v1, Kafka optional later)') and decision D4 — grep -E '@Channel\\s*\\(\\s*\"eval-queue\"\\s*\\)|@Outgoing\\s*\\(\\s*\"eval-queue\"' EvalQueueProducer.java returns at least one match AND grep -E 'public\\s+class\\s+EvalQueueProducer' EvalQueueProducer.java returns at least one match"
  - "OutboxRehydrator.java is a Quarkus @Startup bean at @Priority(300) per docs/design/01-architecture.md §1.4.3 Collector table — grep -E '@Startup' OutboxRehydrator.java returns at least one match AND grep -E '@Priority\\s*\\(\\s*300\\s*\\)' OutboxRehydrator.java returns at least one match"
  - "OutboxRehydrator.java scans `WHERE status='RAW'` ONLY (no `'EVALUATING'` predicate) per docs/spec/schema.md §Invariants Invariant 5 ('There is no distinct \"evaluating\" status — `RAW` plus the flag bitmap is the complete representation of in-flight evaluation state') — grep -E \"status\\s*=\\s*'RAW'\" OutboxRehydrator.java returns at least one match AND grep -E \"'EVALUATING'\" OutboxRehydrator.java returns zero matches"
  - "OutboxRehydrator.java orders the scan by `(fetched_at, id)` so re-enqueue is deterministic across crashes — grep -E 'ORDER BY\\s+fetched_at\\s*,\\s*id' OutboxRehydrator.java returns at least one match"
  - "OutboxRehydrator.java releases its JDBC `Connection` BEFORE emitting to the `eval-queue` channel so the rehydrator does not pin an Agroal connection slot if SmallRye applies back-pressure (the in-memory channel's overflow strategy is `BUFFER` by default; on a saturated buffer `emitter.send` blocks). The scan loop populates a `List<PersistedPostKey>` inside the try-with-resources block; the emit loop runs AFTER the block exits — grep -E 'List<PostPersister\\.PersistedPostKey>|ArrayList<PostPersister\\.PersistedPostKey>' OutboxRehydrator.java returns at least one match AND the source structure is verifiable by reading: a try-with-resources block that does the SELECT and populates the List, then (outside the block, after Connection close) a for-loop that calls `evalQueueProducer.emit`"
  - "OutboxRehydrator.java emits each scanned post id to the SAME `eval-queue` channel that the FetchScheduler's live path emits to (shared producer — the rehydrator does NOT carry its own bespoke channel name) — grep -E 'EvalQueueProducer' OutboxRehydrator.java returns at least one match AND grep -rE '@Channel|@Outgoing' infochat-collector/src/main/java/io/infochat/collector/outbox/ returns matches ONLY in EvalQueueProducer.java (no other production class declares the eval-queue channel)"
  - "FetchScheduler.java is a Quarkus @Startup bean at @Priority(400) per docs/design/01-architecture.md §1.4.3 Collector table — grep -E '@Startup' FetchScheduler.java returns at least one match AND grep -E '@Priority\\s*\\(\\s*400\\s*\\)' FetchScheduler.java returns at least one match"
  - "FetchScheduler.java reads enabled `kind='rss'` rows from `source` at startup (the only Fetcher kind landed in M1) — grep -E 'SELECT.*FROM\\s+source|FROM\\s+source\\s+WHERE' FetchScheduler.java returns at least one match AND grep -E \"kind\\s*=\\s*'rss'|'rss'\" FetchScheduler.java returns at least one match"
  - "FetchScheduler.java reads the global RSS interval from the property `infochat.fetch.rss.interval` and registers per-source ticks via Quarkus @Scheduled. The default value lives in `infochat-collector/src/main/resources/application.properties` (Quarkus config defaults convention) rather than as an inline `:5m` fallback in the @Scheduled annotation or a `static final` constant in source — grep -E 'infochat\\.fetch\\.rss\\.interval' FetchScheduler.java returns at least one match AND grep -E '@Scheduled|Scheduler\\b' FetchScheduler.java returns at least one match AND grep -E 'infochat\\.fetch\\.rss\\.interval\\s*=\\s*5m' infochat-collector/src/main/resources/application.properties returns at least one match AND grep -E 'DEFAULT_RSS_INTERVAL' FetchScheduler.java returns zero matches (the orphaned constant from the round-1 implementation is removed)"
  - "FetchScheduler.java persists posts BEFORE enqueueing (the outbox discipline per docs/spec/architecture.md §Pipelines 'persist as RAW → enqueue' and §Architectural principles 2 and docs/spec/schema.md §Invariants Invariant 5) — the per-tick code path calls PostPersister and then EvalQueueProducer in that order — grep -E 'PostPersister' FetchScheduler.java returns at least one match AND grep -E 'EvalQueueProducer' FetchScheduler.java returns at least one match AND the two call sites appear in persist→enqueue order in the per-tick body (assert by reading the file; if the developer wishes they may pull a helper method into PostPersister that returns the inserted post id and the enqueue line follows in the same method body)"
  - "FetchScheduler.java consumes the `RssFetcher` via CDI injection (`@Inject RssFetcher`) rather than instantiating it inline (`new RssFetcher()`). This decouples the scheduler from the concrete Fetcher implementation, future-proofs the polymorphic `Instance<Fetcher>` dispatch shape for Bluesky / Nostr / Reddit / etc., and lets `FetchSchedulerIT` substitute via `QuarkusMock.installMockForType` (the Quarkus-idiomatic CDI bean replacement) instead of a setter/getter test seam that does not survive the CDI client proxy — grep -E '@Inject\\s+(\\w+\\s+)?RssFetcher|@Inject[^;{]*\\n\\s*RssFetcher' FetchScheduler.java returns at least one match AND grep -E 'new\\s+RssFetcher\\s*\\(' FetchScheduler.java returns zero matches AND grep -E 'setRssFetcher|getRssFetcher' FetchScheduler.java returns zero matches"
  - "RssFetcher.java is a CDI bean annotated `@ApplicationScoped` (the sole allowed modification per the out_of_scope amendment) so FetchScheduler can `@Inject` it — grep -E '@ApplicationScoped' infochat-collector/src/main/java/io/infochat/collector/fetcher/rss/RssFetcher.java returns at least one match AND the rest of the class (the `fetch()` implementation, `RssFetchException`, both constructors, the SsrfGuardedHttpClient interaction) is unchanged from M1-023's frozen state"
  - "FetchScheduler.java handles per-tick fetch / persist / enqueue failures by logging at WARN with the source id and the exception — grep -E 'Log(ger)?\\.warn|\\.warn\\s*\\(|@Slf4j|jboss.*Logger' FetchScheduler.java returns at least one match AND grep -E 'catch\\s*\\(' FetchScheduler.java returns at least one match in a per-tick body context (NO update to `source.consecutive_failures` / `last_fetch_at` / `last_success_at` — those are T2-B's D42 wiring per the out-of-scope list)"
  - "PostPersisterIT.java is a @QuarkusTest against Quarkus DevServices Postgres that seeds a `source` row (kind='rss'), calls PostPersister with a fixture NormalizedPost, and asserts: (a) one post row exists with `status='RAW'`, all four `*_done` flags FALSE, and the expected UID; (b) a second call with the SAME `(source_id, upstream_identifier, fetched_at)` is a NO-OP (ON CONFLICT silently dedups; the post count remains 1) — grep -E '@Test' PostPersisterIT.java returns at least two matches"
  - "OutboxRehydratorIT.java is a @QuarkusTest that seeds N posts directly via JDBC with a mix of `status` values ('RAW' AND 'READY') and `*_done` flag combinations, runs OutboxRehydrator, and asserts: (a) the EvalQueueProducer received exactly the posts whose status was 'RAW' (NOT the 'READY' ones), in `(fetched_at, id)` order; (b) a re-run after no state change re-emits the SAME set (the rehydrator does NOT mark posts as 're-enqueued'; it scans the live `status='RAW'` set on every call — re-enqueueing the same post twice is idempotent at the eval-worker boundary per the outbox discipline); (c) marking some 'RAW' posts to 'READY' and re-running shrinks the re-enqueue set to the remaining 'RAW' posts — grep -E '@Test' OutboxRehydratorIT.java returns at least three matches"
  - "FetchSchedulerIT.java is a @QuarkusTest that seeds a `source` row pointing at an in-process HTTP fixture (com.sun.net.httpserver.HttpServer per the M1-023 pattern), substitutes the production `RssFetcher` via `QuarkusMock.installMockForType` (so the test-mode Fetcher reaches the FetchScheduler through the CDI client proxy, not via a field/setter back-door), lets one tick fire (via a manually-triggered `fetchScheduler.tickOnce(...)` invoke), and asserts: (a) N `post(status='RAW')` rows appear in the DB matching the fixture's `<item>` count; (b) N corresponding post ids land on the eval-queue channel (test consumer drains the channel and asserts the count + ordering); (c) the post ids on the channel match the inserted rows' ids — grep -E '@Test' FetchSchedulerIT.java returns at least one match AND grep -E 'HttpServer\\.create|com\\.sun\\.net\\.httpserver' FetchSchedulerIT.java returns at least one match AND grep -E 'QuarkusMock\\.installMockForType' FetchSchedulerIT.java returns at least one match"
  - "infochat-collector/src/test/resources/fixtures/outbox/feed-fixture.xml is a valid RSS 2.0 document with at least 2 `<item>` elements (each with `<guid>`, `<title>`, `<link>`, `<pubDate>` parseable by `DateTimeFormatter.RFC_1123_DATE_TIME`) — `xmllint --noout fixture.xml` (or equivalent JDK-side XML validator in the test) exits 0; this fixture is consumed by FetchSchedulerIT only (the M1-023 fixtures under fixtures/rss/ remain consumed by the M1-023 tests unchanged)"
  - "mvn -B -pl infochat-collector -am verify exits 0; failsafe reports show PostPersisterIT, OutboxRehydratorIT, and FetchSchedulerIT executed (grep -rE 'Tests run: [1-9]' infochat-collector/target/failsafe-reports returns at least three new matches across the three new IT classes)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003, M1-007, M1-007a/b/c, M1-008, M1-008a/b/c, M1-009, M1-017, M1-022, M1-023, M1-024, M1-025, and M1-026 tests continue to pass alongside the new collector outbox code (M1-027's tests pass too if M1-027 has merged before this ticket runs; otherwise N/A)"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/outbox/PostPersisterIT.java (@QuarkusTest IT exercising INSERT shape, status defaults, UID derivation, and ON CONFLICT dedup against Quarkus DevServices Postgres)
    - infochat-collector/src/test/java/io/infochat/collector/outbox/OutboxRehydratorIT.java (@QuarkusTest IT seeding `status='RAW'`/`'READY'` rows and asserting Invariant-5-compliant rehydration set + ordering + re-run idempotency)
    - infochat-collector/src/test/java/io/infochat/collector/fetch/FetchSchedulerIT.java (@QuarkusTest IT using com.sun.net.httpserver.HttpServer to serve the RSS fixture; lets the scheduler tick fire and asserts persist→enqueue end-to-end with a test consumer draining the eval-queue channel)
    - infochat-collector/src/test/resources/fixtures/outbox/feed-fixture.xml (≥2-item RSS 2.0 fixture for FetchSchedulerIT — distinct from the M1-023 fixtures under fixtures/rss/ to keep ticket boundaries clean)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a — Fetcher SPI is consumed unchanged)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — V1..V8 must continue to apply cleanly; this ticket adds no migration)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 / M1-017)
    - infochat-collector/src/test/java/io/infochat/collector/startup/InstanceLockGuardIT.java (M1-009)
    - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java (M1-009)
    - all M1-008a / M1-008b / M1-008c *Test.java classes (schema tests)
    - M1-022 BootstrapSourcesParserTest + BootstrapLoaderIT (the bootstrap loader writes the `source` rows this FetchScheduler reads — the IT relies on a test-time fixture, not on the bootstrap loader's runtime output)
    - M1-023 RssFetcherTest + RssFeedParserTest (the RssFetcher is consumed unchanged via the M1-007a Fetcher SPI)
    - M1-024 / M1-025 / M1-026 infochat-ssrf tests
    - M1-027 ProviderStateDaoIT + NewPostReconcilerIT + NewPostListenerIT (if merged before this ticket; the V9 provider_state migration applies cleanly alongside V1..V8 and does not affect collector-side code)
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/architecture.md §Pipelines
  - docs/spec/architecture.md §Architectural principles
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §DB roles
  - docs/design/00-mvp.md §Fetchers
  - docs/design/01-architecture.md §1.3 Key data flow: ingest
  - docs/design/01-architecture.md §1.3.1 Polled Fetcher → outbox → eval pipeline
  - docs/design/01-architecture.md §1.4.3 Startup-bean ordering and single-instance enforcement
  - docs/design/01-architecture.md §1.6 Concurrency and rate limiting
  - docs/design/02-schema.md §2.3 Posts (ingest)
  - docs/design/02-schema.md §2.3.1 post
decision_refs:
  - D3
  - D4
  - D38
  - D42
---

# M1-028: Collector outbox (FetchScheduler + PostPersister + OutboxRehydrator)

## Context

Second of two T1-C outbox/NOTIFY tickets (the first is M1-027, the
Provider-side catch-up + `provider_state` cursor + `new_post` listener).
This ticket lands the **Collector half** of the inter-service push
contract: the FetchScheduler that ticks one or more `Fetcher` instances
on a per-source cadence, the PostPersister that writes the
`NormalizedPost` returned by a Fetcher into the `post` table at
`status='RAW'`, the EvalQueueProducer that emits the inserted post id
to the in-memory `eval-queue` SmallRye channel (per CLAUDE.md §Stack
"SmallRye Reactive Messaging (in-memory channels v1, Kafka optional
later)" and decision D4), and the OutboxRehydrator that re-enqueues
unfinished `status='RAW'` posts on startup after a crash.

The two-ticket carve-out (Option A) was picked at the top of this
authoring session per the operator's JIT handoff and is documented in
M1-027's Implementation notes. M1-027 and M1-028 share no runtime
code and are independent runnable-now once T1-B is merged (which it
is — M1-022 + M1-023 + the M1-024/025/026 infochat-ssrf chain are on
main). They can be implemented in either order; this ticket assumes
nothing about M1-027's merge order.

This ticket is the **outbox discipline implementation** for the
post pipeline. Per `docs/spec/architecture.md` §Architectural
principles 2 ("Outbox + LISTEN/NOTIFY + high-water mark. Postgres
provides durability and push semantics without an external broker.")
and §Pipelines ("persist as `RAW` → enqueue → Stage 1 ..."), the
persist step writes the durable `RAW` row BEFORE the enqueue emits
to the in-memory channel. The startup rehydrator picks up anything
left in `RAW` after a crash — per `docs/spec/schema.md` §Invariants
Invariant 5, `RAW` plus the per-stage `*_done` flag bitmap is the
complete representation of in-flight evaluation state; there is no
distinct `'EVALUATING'` status. The rehydrator's predicate is
`WHERE status='RAW'` (cite Invariant 5; the `§1.3.1` and `§1.4.3`
design lines now match this predicate post the 2026-05-15 `spec:`
correction).

The `eval-queue` channel has **no consumer** in T1-C scope. T1-D's
eval workers subscribe to it. This is intentional: T1-C ships the
outbox emit, and T1-D ships the consumer side independently. The
tests drain the channel via a test consumer to assert the post id
was emitted; the reviewer should NOT flag the consumer-less state as
an unused abstraction.

## Definition of Done

- `PostPersister.java` is a narrow JDBC wrapper around a single
  `post` INSERT:
  - Signature roughly:
    `PostId persist(long sourceId, NormalizedPost normalized)`
    (the implementer is free to name the return type; an
    `Optional<UUID>` covering the ON-CONFLICT-no-op path is one
    acceptable shape).
  - Body computes the UID as
    `uid = sha256(source_id || '|' || upstream_identifier)` lower-case
    hex. The M1-007a SPI declares
    `NormalizedPost.upstreamIdentifier` as "Never null"
    (`infochat-core/src/main/java/io/infochat/core/ingest/NormalizedPost.java:17`);
    PostPersister trusts the contract. The content-hash fallback
    that `docs/spec/schema.md` §UID derivation defines for null
    `upstream_identifier` is **intentionally not implemented in
    M1** — it is defensive code for a scenario the SPI forbids
    (CLAUDE.md §"No defensive code for impossible scenarios"), it
    introduces a permanent attack surface (NFKC homoglyph
    collisions, multi-MB body DoS on the ingest hot path,
    cross-JRE NFKC implementation drift, canonicalization-rule
    version drift breaking dedup silently), and the per-kind
    canonicalization rules the spec defers to design are not
    authored. A null `upstreamIdentifier` arriving at PostPersister
    indicates a Fetcher bug; the persister asserts the contract via
    `Objects.requireNonNull` (or an explicit `IllegalArgumentException`
    on empty) so the bug surfaces loudly rather than persisting an
    ID-less row. A follow-up `spec:` commit aligns
    `docs/spec/schema.md` §UID derivation with this reality
    (drops the fallback paragraph or marks it deferred to v2 when
    a Fetcher legitimately needs it).
  - INSERT shape (one statement, one PreparedStatement):
    ```sql
    INSERT INTO post (
      id, uid, source_id, upstream_identifier, url, title, body,
      author, published_at, fetched_at, status,
      stage1_done, stage2_done, tagger_done, embedding_done,
      stage1_flagged, stage2_failed, tagger_fallback, tags
    ) VALUES (
      gen_random_uuid(), :uid, :sourceId, :upstreamId, :url, :title, :body,
      :author, :publishedAt, :fetchedAt, 'RAW',
      FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, '{}'
    )
    ON CONFLICT (source_id, upstream_identifier, fetched_at) DO NOTHING;
    ```
    The ON CONFLICT is the belt-and-suspenders dedup against the
    same-partition refetch race; per `docs/design/02-schema.md`
    §2.3.1 the `post` table has both `UNIQUE (uid, fetched_at)`
    AND `UNIQUE (source_id, upstream_identifier, fetched_at)` —
    either constraint catches the race, and this ticket relies on
    the `(source_id, upstream_identifier, fetched_at)` form
    because it does not require the caller to have already computed
    the UID-hashed predicate at the SQL layer.
- `EvalQueueProducer.java` is a SmallRye Reactive Messaging emitter
  that writes one post-id message to the in-memory channel named
  `eval-queue` per CLAUDE.md §Stack and decision D4:
  - Uses `@Channel("eval-queue") Emitter<...>` or
    `@Outgoing("eval-queue")`-paired producer method shape.
  - The message body is the post UUID (or the `(uid, fetched_at)`
    composite key the eval worker will need to load the row — pick
    at implementation time; the T1-D consumer is the future
    contract). Document the choice in `Implementation notes`.
- `OutboxRehydrator.java` is a Quarkus `@Startup` bean at
  `@Priority(300)` per `docs/design/01-architecture.md` §1.4.3
  Collector table. On startup:
  1. Scan
     ```sql
     SELECT id, fetched_at FROM post
      WHERE status = 'RAW'
      ORDER BY fetched_at, id;
     ```
     Per `docs/spec/schema.md` §Invariants Invariant 5, `status='RAW'`
     IS the in-flight marker — any post that has completed stage 5
     has `status` in `('READY', 'QUARANTINED', 'NEEDS_REVIEW')`.
     The per-stage `*_done` flags tell the downstream eval workers
     WHERE to restart; the rehydrator's predicate does not look at
     them.
  2. For each row, emit the post id to the `eval-queue` channel via
     `EvalQueueProducer` (the SAME producer the FetchScheduler's
     live path uses — the rehydrator does NOT carry its own bespoke
     channel name; there is exactly one `eval-queue` emitter in
     this module).
  3. Log a one-line INFO summary: `OutboxRehydrator: re-enqueued
     <N> RAW posts from prior run.`
- `FetchScheduler.java` is a Quarkus `@Startup` bean at
  `@Priority(400)` per `docs/design/01-architecture.md` §1.4.3
  Collector table. In T1-C scope this is a **minimal impl**:
  - At startup: read all enabled `kind='rss'` rows from `source`
    (the only Fetcher kind landed in M1):
    ```sql
    SELECT id, identifier FROM source
     WHERE kind = 'rss'
       AND status = 'active'
       AND deleted_at IS NULL;
    ```
  - For each row, register a Quarkus `@Scheduled` per-source tick
    using the global RSS interval. The interval is read from
    `@ConfigProperty(name = "infochat.fetch.rss.interval",
    defaultValue = "5m")`. Per-source cadence overrides are
    explicitly out-of-scope (later ticket).
  - On each tick: invoke
    `RssFetcher.fetch(sourceId, identifier)` to obtain
    `List<NormalizedPost>`; for each post call
    `PostPersister.persist(...)` then
    `EvalQueueProducer.emit(...)`. Persist BEFORE enqueue (the
    outbox discipline per `docs/spec/architecture.md` §Pipelines
    and §Architectural principles 2). When the persist returns a
    no-op (ON CONFLICT dedup hit), the enqueue is also skipped —
    the post has already been emitted on a prior tick and the
    downstream eval state is the source of truth.
  - Failure handling in T1-C is intentionally minimal: catch
    exceptions inside the per-tick body, log at WARN with the
    `source.id` and the exception (NOT the source identifier URL,
    which can carry credentials per the M1-023 redteam finding).
    No `source.consecutive_failures` update; no `source.status`
    transition; no `source.last_fetch_at` / `last_success_at`
    update. Those are T2-B's D42 wiring per
    `docs/design/01-architecture.md` §1.6.
- Three integration tests under
  `infochat-collector/src/test/java/io/infochat/collector/`:
  - `outbox/PostPersisterIT.java` — `@QuarkusTest` against Quarkus
    DevServices Postgres; seeds a `source` row (`kind='rss'`);
    calls PostPersister with a fixture NormalizedPost; asserts the
    post row exists with `status='RAW'`, all four `*_done` flags
    FALSE, the expected UID, and the empty tags array; asserts a
    second call with the SAME `(source_id, upstream_identifier,
    fetched_at)` is a no-op (post count remains 1).
  - `outbox/OutboxRehydratorIT.java` — `@QuarkusTest`; seeds N
    posts directly via JDBC with a mix of `status='RAW'` and
    `'READY'` and varied `*_done` flags; runs the rehydrator;
    asserts the EvalQueueProducer received exactly the
    `status='RAW'` posts in `(fetched_at, id)` order; asserts
    re-run idempotency in the spec'd sense (the rehydrator
    re-emits the same set on every call; idempotency is at the
    eval-worker boundary, not at the rehydrator); asserts that
    marking some RAW posts to READY and re-running shrinks the
    re-enqueue set.
  - `fetch/FetchSchedulerIT.java` — `@QuarkusTest`; seeds a
    `source` row pointing at an in-process
    `com.sun.net.httpserver.HttpServer` serving the
    `feed-fixture.xml` fixture; lets one tick fire; asserts N
    `status='RAW'` rows appear in the DB matching the fixture's
    item count; asserts N corresponding post ids land on the
    `eval-queue` channel (test consumer drains the channel).
- One fixture
  `infochat-collector/src/test/resources/fixtures/outbox/feed-fixture.xml`
  — a ≥2-item RSS 2.0 document (each item with `<guid>`, `<title>`,
  `<link>`, `<pubDate>`). Distinct from the M1-023 fixtures under
  `fixtures/rss/` to keep ticket boundaries clean (M1-023's tests
  consume the M1-023 fixtures unchanged).
- `mvn -B clean verify` from the repo root exits 0. All prior
  tests continue to pass.

## Implementation notes

- **Option A (two tickets) chosen at the top of this authoring
  session.** Documented in M1-027's Implementation notes; not
  re-litigated here. The two tickets are independent runnable-now
  and share no runtime code.
- **Migration touch is FALSE.** This ticket touches no Flyway
  migration. The `post` table, the `source` table, and the
  `provider_state` table all exist on disk before this ticket
  starts (V7 for `post`, V6 for `source`, V9 for
  `provider_state` if M1-027 has merged first — the FetchScheduler
  is collector-side and never reads `provider_state`).
- **`eval-queue` channel naming.** Per CLAUDE.md §Stack "SmallRye
  Reactive Messaging (in-memory channels v1, Kafka optional
  later)" and the spec §Pipelines outbox idiom; the spec does not
  pin a channel name. `eval-queue` is the natural name and matches
  the §1.3.1 design narrative ("Enqueue post_id on the eval
  channel"). The producer and the rehydrator both name the
  channel as `"eval-queue"` via SmallRye annotations. The T1-D
  consumer subscribes to the same name. If a future spec
  amendment renames the channel, both halves change in one diff.
- **`eval-queue` channel has no consumer in T1-C scope.** T1-D's
  eval workers subscribe to this channel; this ticket ships the
  emit only. SmallRye Reactive Messaging tolerates a producer
  without a consumer (the in-memory channel buffers per its
  configured size); the FetchSchedulerIT and the OutboxRehydratorIT
  attach a TEST consumer that drains the channel for the duration
  of the test. The reviewer's negative-space check should NOT
  flag the consumer-less state as scope drift — this is a
  deliberate T1-D handoff.
- **UID derivation lives in `PostPersister`.** The Fetcher does
  not compute the UID; the Fetcher returns `NormalizedPost` with
  `upstreamIdentifier` populated (the M1-007a SPI declares the
  field "Never null"), and the persister hashes
  `(source_id, upstream_identifier)` at INSERT time via SHA-256.
  One code path, no canonicalization step, no null-branching.
  This centralizes the UID rule in one place per
  `docs/spec/schema.md` §UID derivation; future Fetchers (Bluesky
  AT-URI, Nostr event id, Reddit fullname, Atom `<id>`) all have
  protocol-mandated IDs and feed the same persister. The
  implementer may pull the UID computation into a static helper
  (`PostUidDerivation`) if it clarifies the diff; the helper
  stays inside `PostPersister.java` to honor the files_budget.
  PostPersister asserts non-null `upstreamIdentifier` via
  `Objects.requireNonNull` (or an explicit `IllegalArgumentException`
  on empty) — this is system-boundary validation against an
  SPI-contract violation, not defensive code per CLAUDE.md §"No
  defensive code for impossible scenarios" (the throw surfaces a
  Fetcher bug loudly rather than persisting an ID-less row). The
  spec's content-hash fallback is deliberately omitted from M1;
  see Alternatives considered for the security / performance /
  maintenance rationale.
- **`fetched_at` semantics match M1-023.** Per
  `docs/design/02-schema.md` §2.3.1 `post` is partitioned by
  `fetched_at`. The Fetcher's `fetch()` call captures
  `Instant.now()` BEFORE the HTTP request and stamps every
  returned `NormalizedPost.fetchedAt` with that single value
  (per M1-023's Implementation notes); the persister writes that
  value through unchanged. This guarantees every emitted
  NormalizedPost from one Fetcher call shares one `fetched_at`
  partition row.
- **Per-tick error handling: WARN-log only.** No source-row
  update. The D42 failure-counter wiring (`consecutive_failures`,
  `last_fetch_at`, `last_success_at`, `active → failed`
  transition) is T2-B's responsibility per
  `docs/design/01-architecture.md` §1.6. T1-C's failure-handling
  contract is "log the exception and keep ticking"; a future
  ticket adds the counter and the transition.
- **Log the `source.id`, NOT the `source.identifier`.** The
  identifier URL can carry embedded credentials per the M1-023
  redteam finding INFO-LEAK; the per-tick log line uses the
  numeric `source.id` for diagnosis and elides the URL. The
  redactor catalogue (M1-019 follow-up) will sanitize URLs
  inside exception messages once it lands; in T1-C scope, just
  emit the source id.
- **Raw JDBC, not Panache.** The persister opens a
  `DataSource`-injected connection (`@Inject DataSource
  dataSource;`), issues a single `PreparedStatement`, and lets
  the calling thread's transaction manager handle commit. The
  FetchScheduler invokes the persister inside a Quarkus
  `@Transactional` boundary per tick so persist + enqueue are
  one logical event from the outbox-discipline standpoint.
  (Strict atomicity of persist + enqueue is NOT required — the
  enqueue is in-memory and SmallRye does not participate in
  JDBC transactions; the discipline is "persist first, enqueue
  second" so a crash between the two leaves the post recoverable
  via the rehydrator on next startup.)
- **OutboxRehydrator idempotency model.** The rehydrator scans
  `status='RAW'` on every call and re-emits the entire set.
  There is no "rehydrated" flag on the post row; idempotency is
  at the eval-worker boundary — T1-D's stage 1 / 2 / tagger /
  embedding workers read each `*_done` flag and skip stages
  already completed per Invariant 5. Re-enqueueing the same RAW
  post twice produces one logical evaluation pass (the second
  enqueue may double the work in the eval-worker queue for a
  brief window, but each stage's idempotent-by-`*_done`-flag
  check prevents double execution). This matches
  `docs/design/01-architecture.md` §1.3.1 "if Collector restarts,
  OutboxRehydrator on `@Startup` scans `status='RAW'` and
  re-enqueues."
- **Quarkus @Scheduled tick wiring.** The FetchScheduler uses
  Quarkus's `io.quarkus.scheduler.Scheduler` API to register
  per-source ticks programmatically at startup (NOT via
  `@Scheduled` annotations, which require compile-time
  cron/every strings). The runtime `Scheduler.newJob(...)`
  pattern lets the FetchScheduler enumerate `source` rows at
  startup and register one job per source. The global interval
  (`infochat.fetch.rss.interval`, default `5m`) applies to every
  RSS source; per-source overrides are out-of-scope.
- **`FetchSchedulerIT` triggers the tick deterministically.**
  Two acceptable shapes:
  1. `Awaitility.await().atMost(<timeout>).until(...)` — let
     the natural scheduler interval fire (interval is reset to
     a small value via `@TestProfile` or an
     `application-test.properties` override). This is the
     simplest shape; the override sets
     `infochat.fetch.rss.interval=1s` for the test.
  2. Inject `io.quarkus.scheduler.Scheduler` and call
     `scheduler.resume(...)` after asserting the job is
     registered, or manually invoke the registered job's
     callback via reflection / a test-only seam.
  Shape (1) is recommended for the IT; the test profile
  override is the smallest possible change.
- **No per-source cadence override; no microprofile-faulttolerance
  integration; no retry / backoff.** All out-of-scope per the
  authoring handoff. The single global RSS interval covers every
  source in M1.

## Big-picture notes

- **Security-relevant.** Three surfaces matter:
  1. **Scheduler cadence + per-source isolation.** A buggy
     cadence or a per-source isolation bug could over-poll a
     single source — quota / abuse-of-upstream surface. The
     IT pins the per-source tick count and the global interval.
  2. **Persist step writes `status='RAW'`.** A missing per-stage
     flag default or a status-enum drift could leave posts
     permanently stuck. The IT asserts the four
     `*_done` flags are FALSE and `status` is `'RAW'` exactly;
     a regression that flips a default to TRUE silently breaks
     T1-D's eval pipeline. The DoD pins the entire INSERT
     column list explicitly so the reviewer can audit the row
     shape against `docs/design/02-schema.md` §2.3.1.
  3. **Rehydrator predicate `WHERE status='RAW'`.** Per
     `docs/spec/schema.md` §Invariants Invariant 5, this IS the
     in-flight marker. A regression that adds `'READY'` to the
     predicate would re-enqueue completed posts and
     double-process them (potentially flipping a `READY` post
     back into the eval pipeline). The acceptance items pin
     `'RAW'` and assert `'EVALUATING'` does NOT appear (the
     authoritative status enum is `'RAW'`, `'READY'`,
     `'QUARANTINED'`, `'NEEDS_REVIEW'` per
     `docs/spec/schema.md` §Posts and derivatives; Invariant 5
     forbids `'EVALUATING'`).
  The post-2026-05-15 `spec:` correction to
  `docs/design/01-architecture.md` §1.3.1 and §1.4.3 confirms the
  rehydrator predicate is `status='RAW'` only; this ticket cites
  Invariant 5 as the primary authority and the design lines as
  confirming.
- **The outbox is the inter-service correctness backbone.** Per
  `docs/spec/architecture.md` §Architectural principles 2:
  "Outbox + LISTEN/NOTIFY + high-water mark. Postgres provides
  durability and push semantics without an external broker."
  This ticket lands the outbox half (persist-then-enqueue +
  rehydrator); M1-027 lands the LISTEN/NOTIFY half + the
  high-water mark. Together they implement the spec's
  no-external-broker correctness story.
- **`source.status='active'` is the only state this ticket
  schedules.** The schema authored in M1-008b's V6 defines
  `source.status ∈ {active, failed, disabled}`. T1-C only ticks
  `active` sources. The `failed` / `disabled` states are
  produced by future tickets (T2-B for `failed` via D42, admin
  commands for `disabled`); the FetchScheduler reads `WHERE
  status = 'active'` to ignore those rows from day one.
- **No `Retry-After` / per-source politeness window.** Per
  `docs/design/01-architecture.md` §1.6 these live on the
  per-tick boundary using `org.eclipse.microprofile.faulttolerance`;
  T1-C ships a single global interval and the Fetcher itself
  makes a single request per call per M1-023's contract. A
  hostile feed serving an HTTP 429 / 503 with a Retry-After
  header is not honored in M1; T2-B adds that wiring.
- **The Provider's `NewPostListener` (M1-027) reads the post
  table the Collector writes here.** The cross-service contract
  is the `post` row at `status='READY'` — but T1-C posts stay at
  `status='RAW'` for the duration of every IT (the eval pipeline
  in T1-D flips them to READY). The Provider's reconciler and
  listener are exercised against test-inserted READY rows in
  M1-027's tests; this ticket's IT does NOT flip status and
  does NOT emit `pg_notify('new_post', …)`. The two halves
  meet at T1-D when the eval pipeline writes the status
  transition + the NOTIFY.
- **Subticket isolation against M1-027.** This ticket lives
  under `infochat-collector/src/main/java/io/infochat/collector/outbox/`
  and `infochat-collector/src/main/java/io/infochat/collector/fetch/`.
  M1-027 lives under `infochat-provider/src/main/java/io/infochat/provider/outbox/`.
  The two `files_scope` lists are disjoint at the file path level.
  No compile-time dependency between the two tickets exists.
- **`@Priority(300)` for the rehydrator vs. `@Priority(400)` for
  the scheduler.** Per `docs/design/01-architecture.md` §1.4.3
  Collector table, the bean ordering is 50 (InstanceLockGuard)
  → 100 (Flyway) → 200 (BootstrapLoader) → 300 (OutboxRehydrator)
  → 400 (FetchScheduler) → 450 (StreamSourceSupervisor).
  The rehydrator MUST run before the scheduler so the eval
  queue gets the prior-run RAW posts in the queue BEFORE the
  scheduler starts adding new ones — the rehydrator's posts are
  older `fetched_at` and naturally drain first.
- **Cross-ticket flow:** the system once T1-C lands but T1-D
  doesn't yet exist boots a Collector that fetches RSS feeds,
  persists `status='RAW'` posts, and emits to `eval-queue`
  where no one is listening. The in-memory channel buffers; if
  it fills, SmallRye applies back-pressure to the producer
  (per `docs/design/01-architecture.md` §1.6 "Eval channel:
  bounded queue size (configurable, profile-driven). If full,
  fetcher blocks (back-pressure to feed schedulers, which is
  the desired behavior — avoids unbounded memory growth on LLM
  slowness)"). The fetch loop pauses until the queue drains.
  This is the documented v1 backpressure shape; no T1-C ticket
  changes it.

## Out-of-scope expansion

- **Stage 1 / Stage 2 / tagger / entity-extractor / embedding-worker
  / status→READY transition.** All T1-D territory per
  `docs/design/01-architecture.md` §1.3.4. T1-C's posts stay at
  `status='RAW'` for the duration of every IT; no test asserts a
  transition out of `'RAW'`.
- **`pg_notify('new_post', …)` emit.** Also T1-D — fires from the
  eval pipeline's stage 5 after the status flip to `'READY'`. T1-C
  ships no NOTIFY emit; M1-027's tests emit NOTIFY from the test
  harness, NOT from any production code.
- **Per-source cadence override, max-page cap, `Retry-After`
  honoring, `org.eclipse.microprofile.faulttolerance` integration,
  per-source politeness window.** All out-of-scope per
  `docs/design/01-architecture.md` §1.6. T1-C ships a single global
  RSS interval; per-source overrides are a later ticket.
- **`source.status` machine transitions or D42 failure-counter
  wiring** (`last_fetch_at`, `last_success_at`,
  `consecutive_failures`, `active → failed`). T2-B territory.
  T1-C's failure handling is per-tick WARN-log only.
- **StreamSource / NostrStreamSource / supervised-worker lifecycle**
  (`@Priority(450)`). Tier-3 T3-C territory. T1-C wires Fetchers
  only.
- **Provider-side reconciler, listener, `provider_state` code, or
  `NewPostHandler`.** M1-027 territory.
- **V1..V8 migration modifications.** Frozen. This ticket adds no
  migration.
- **Bluesky / Nitter / Reddit / YouTube / Odysee Fetchers.** Each
  binds to the same M1-007a Fetcher SPI but is its own Tier-3
  T3-B ticket. T1-C schedules `kind='rss'` only.
- **Asset Fetcher dispatch or `price_snapshot` write path.**
  Tier-2 T2-H per decision D39 — asset Fetchers bypass the
  outbox entirely. This ticket wires the post-outbox path only.
- **Stage 1 HTML strip / NFKC / regex redaction on
  `NormalizedPost.body`.** T1-D. The persisted post body in
  `'RAW'` carries the raw HTML the Fetcher returned.
- **Messaging adapter, CommandRouter, `/help`, `/add-source`, or
  first-command implementation.** T1-E and T1-F territory.
- **Any `infochat-provider` module change.** This ticket is
  collector-side only.
- **Modifications to the M1-007a Fetcher SPI, NormalizedPost
  record, or the M1-023 RssFetcher / RssFeedParser.** All
  consumed unchanged.
- **Any `infochat-collector/pom.xml` change.** SmallRye Reactive
  Messaging in-memory channels are already on the Quarkus BOM;
  no new dep is introduced.

## Authorized test changes

- (none — this ticket adds three new IT files plus one XML
  fixture in `infochat-collector` and modifies no pre-existing
  tests. All M1-003 / M1-007 / M1-008 / M1-009 / M1-017 / M1-022
  / M1-023 / M1-024 / M1-025 / M1-026 tests continue to pass
  unchanged. M1-027's tests are unaffected because they live in
  `infochat-provider`.)

## Alternatives considered

- **Implement the spec's content-hash UID fallback for posts with
  null `upstreamIdentifier`.** Considered and explicitly rejected
  via the premise-fail escalation on 2026-05-15 (see
  `escalations[0]` and `revisions[0]` in frontmatter).
  Three independent reasons:
  1. **SPI contract.** The M1-007a SPI declares
     `NormalizedPost.upstreamIdentifier` as "Never null"
     (`infochat-core/src/main/java/io/infochat/core/ingest/NormalizedPost.java:17`),
     and M1-023's RssFeedParser enforces this by throwing
     `IllegalArgumentException` on RSS items lacking both
     `<guid>` and `<link>`. The fallback branch in PostPersister
     would be defensive code for a scenario the SPI contract
     forbids, in conflict with CLAUDE.md §"No defensive code
     for impossible scenarios".
  2. **Security surface.** Content-hash dedup is adversarial.
     A hostile feed can craft bodies that NFKC-normalize
     identically (homoglyphs, zero-width joiners, combining
     marks) but appear visually distinct. `ON CONFLICT DO
     NOTHING` then silently drops one — the attacker chooses
     which post survives. NFKC implementations also have
     documented cross-JRE divergences for certain scripts;
     deployments on different JDK versions would compute
     different UIDs for the same body, silently breaking the
     spec's "stable globally" promise. Multi-MB bodies pushed
     by a malicious feed translate to real CPU on every
     refetch (NFKC + regex + SHA-256 on arbitrary-length
     input on the ingest hot path; no `body`-size TOAST-DoS
     guard parallel to the 2048-char `upstream_identifier`
     cap).
  3. **Maintenance.** The per-kind canonicalization rules the
     spec §UID derivation defers to design notes are not
     authored — only one example exists (RSS: strip
     ad-tracking query params and `<pubDate>`). Versioning
     canonicalization rules across upgrades is a known-hard
     problem; if the rule changes between v1 and v2, every
     old content-hash UID becomes irreproducible, breaking
     cross-relay dedup silently. Dead-code maintenance cost
     for the fallback branch is also non-trivial — every
     planned Fetcher (RSS, Bluesky AT-URI, Nostr event id,
     Reddit fullname, Atom `<id>`, YouTube video id) has a
     protocol-mandated ID; the fallback would be
     never-exercised production code paid for in every code
     review and refactor forever.

  Drop the fallback; trust the SPI; throw on null arrival;
  align the spec via a follow-up `spec:` commit. If a future
  Fetcher genuinely lacks IDs (e.g. an HTML-only blog scrape
  without permalinks), that's a focused future ticket
  coordinating: SPI loosening + per-kind canonicalization
  rules in design + PostPersister fallback path + adversarial
  test vectors. v1 ships the simpler, safer, faster, more
  debuggable single path.

- **Option B: one combined ticket covering both T1-C halves.**
  Rejected at the top of this authoring session per the operator's
  handoff. See M1-027's "Alternatives considered" for the full
  rationale; not re-litigated here.
- **Emit the post UID (not the post UUID) on the `eval-queue`
  channel.** Considered. Rejected: the post row's PK is `(id,
  fetched_at)` where `id` is a `UUID DEFAULT gen_random_uuid()`
  (per `docs/design/02-schema.md` §2.3.1). The UID is a separate
  TEXT column for cross-source dedup, not the row identifier.
  Emitting the UID would force the eval worker to join back to
  `post` on `WHERE uid = ?` (which is not the PK), so the worker
  would need a `(uid, fetched_at)` composite key on the wire or
  an additional SELECT to resolve `(uid → id)`. Emitting the
  `(id, fetched_at)` composite is the most direct shape; the
  acceptance items accept either form so long as the helper class
  used by the producer is consistent with what T1-D's consumer
  parses. The implementer documents the choice in the producer's
  JDoc.
- **Use Quarkus's `@Scheduled` annotation instead of the
  programmatic `Scheduler` API.** Rejected: `@Scheduled` requires
  compile-time cron / every strings, but the FetchScheduler
  enumerates `source` rows at startup and registers one job per
  source — a dynamic per-source count that `@Scheduled` cannot
  express. The programmatic
  `io.quarkus.scheduler.Scheduler.newJob(...)` API is the right
  shape for runtime-discovered tick targets.
- **Single transaction across persist + enqueue.** Considered.
  Rejected: the enqueue is in-memory (SmallRye in-memory channel)
  and does not participate in JDBC transactions. Wrapping both
  in `@Transactional` doesn't make the enqueue transactional;
  the discipline is "persist first, enqueue second" so a crash
  between the two leaves the post in `'RAW'` and the rehydrator
  re-emits it on next startup. Strict atomicity of the
  cross-resource pair would require a 2PC layer (or an outbox
  TABLE the eval worker drains) that T1-C does not justify.
- **Use a database-backed outbox table** (`eval_outbox(post_id,
  enqueued_at, claimed_at)`) **instead of the SmallRye in-memory
  channel.** Considered. Rejected for v1: per CLAUDE.md §Stack
  the v1 design uses SmallRye in-memory channels; decision D4
  pins this. A DB-backed outbox is the v2 candidate when the
  in-memory model proves insufficient (e.g. multi-instance
  scaling, persistent eval queue across collector restarts). v1
  achieves the same crash-recovery guarantee via the
  `status='RAW'` predicate (the live `post` table IS the outbox)
  + the OutboxRehydrator scan. The rehydrator's predicate
  scanning `WHERE status='RAW'` is equivalent in effect to a
  separate outbox table's scan for unclaimed work — the post
  row's status column carries the durable cursor.
- **Per-tick `Retry-After` honoring in the FetchScheduler.**
  Considered. Rejected: per `docs/design/01-architecture.md`
  §1.6 retry / backoff / `Retry-After` honoring lives on the
  per-tick boundary using `org.eclipse.microprofile.faulttolerance`,
  which is its own integration ticket. T1-C ships the minimal
  "log on failure and keep ticking" path; the polished
  failure-handling path is T2-B's responsibility.
- **Read the source URL into the WARN log line on per-tick
  failure.** Rejected per the M1-023 INFO-LEAK redteam finding:
  the source `identifier` URL can carry embedded credentials
  (`https://user:token@host/feed.xml`), and any exception message
  that interpolates the URL into a log scope leaks the credential.
  This ticket logs the numeric `source.id` only and elides the
  URL until the M1-019 follow-up's URL-aware redactor catalogue
  is in place.
- **Combine `PostPersister` and `EvalQueueProducer` into one
  class.** Considered. Rejected: separating the persist concern
  from the channel-emit concern keeps both testable in isolation
  (PostPersisterIT exercises the INSERT without touching the
  channel; OutboxRehydratorIT exercises the channel emit without
  ticking a fetcher; FetchSchedulerIT exercises the end-to-end
  shape). The split also matches the future T1-D consumer
  pattern — the consumer subscribes to the channel without
  caring about the persist shape, and the persist shape is
  immutable from the consumer's perspective.
