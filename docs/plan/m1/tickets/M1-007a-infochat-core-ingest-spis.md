---
id: M1-007a
title: infochat-core + ingest SPIs
status: done
created: 2026-05-11
last_updated: 2026-05-12
blocked_by:
  - M1-001
files_budget: 10
files_scope:
  - pom.xml
  - infochat-core/pom.xml
  - infochat-core/src/main/java/io/infochat/core/ingest/Fetcher.java
  - infochat-core/src/main/java/io/infochat/core/ingest/StreamSource.java
  - infochat-core/src/main/java/io/infochat/core/ingest/NormalizedPost.java
  - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java
  - infochat-collector/pom.xml
  - infochat-provider/pom.xml
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (the umbrella M1-007's whole-topic integration test — reserved for the umbrella commit; per docs/process/workflow.md §Ticket-ID placeholder convention, subtickets must not pre-empt the umbrella's verification surface)
  - any concrete Fetcher implementation (RssFetcher, BlueskyFetcher, NostrStreamSource, etc. — those land in Tier 1 and Tier 3 implementation tickets)
  - any output-type discriminator on Fetcher (asset Fetchers need the discriminator per docs/spec/architecture.md §Ingest SPIs, but the asset path is its own later ticket; M1-007a's Fetcher returns List<NormalizedPost> only)
  - any Fetcher / StreamSource registry, factory, or @Qualifier wiring (lives with the implementation tickets that need to look impls up by `kind`)
  - any pagination, retry, or backoff logic in the SPI (all spec-level "implementations MUST" rules become impl-side responsibilities; the SPI is interface-only)
  - any change under infochat-core/src/main/resources/db/migration/ (M1-005 owns the V1 migration in Collector for now; the migration-move-into-core follow-up is a SEPARATE ticket filed once M1-007a lands — see M1-005 §Big-picture notes)
  - any Quarkus extension dependency in infochat-core/pom.xml (this module is a plain library jar — no quarkus-arc, no quarkus-jdbc-postgresql, no quarkus-flyway here; downstream Quarkus apps pull extensions, infochat-core only carries types)
  - any LLM, embedding, messaging, translation, or progress-notifier SPI (those live in M1-007b and M1-007c)
  - any infochat-ssrf module (the design enumerates it at docs/design/01-architecture.md §1.2, but it is a separate Tier-0/Tier-1 ticket; not introduced here)
acceptance:
  - "infochat-core/pom.xml exists and declares <packaging>jar</packaging> (or omits <packaging>, which defaults to jar) — grep -E '<packaging>(jar)?</packaging>' infochat-core/pom.xml returns either zero matches (omitted, default jar) or a `<packaging>jar</packaging>` match; explicitly NOT `<packaging>pom</packaging>`"
  - "grep -E '<module>infochat-core</module>' pom.xml returns at least one match (the parent registers the new module)"
  - "infochat-core/pom.xml has no explicit <version> element inside any <dependency> block (BOM supplies all dependency versions, M1-001 invariant preserved). Verify: `awk '/<dependency>/,/<\\/dependency>/' infochat-core/pom.xml | grep -E '<version>'` returns zero matches. The awk line-range filter scopes grep to <dependency>...</dependency> contents only, so the mandatory <parent><version> block and any <build><plugins> versions are correctly excluded."
  - "grep -rE '<artifactId>quarkus-' infochat-core/pom.xml returns ZERO matches (the module is a plain library jar — no Quarkus extensions)"
  - "grep -E '<artifactId>infochat-core</artifactId>' infochat-collector/pom.xml returns at least one match (Collector depends on the new module)"
  - "grep -E '<artifactId>infochat-core</artifactId>' infochat-provider/pom.xml returns at least one match (Provider depends on the new module)"
  - "infochat-core/src/main/java/io/infochat/core/ingest/Fetcher.java exists, declares `public interface Fetcher` in package io.infochat.core.ingest, and has a method whose return type is `java.util.List<NormalizedPost>` (grep -E 'List<NormalizedPost>' returns at least one match)"
  - "infochat-core/src/main/java/io/infochat/core/ingest/StreamSource.java exists, declares `public interface StreamSource` in package io.infochat.core.ingest, and contains both a `start` and a `stop` lifecycle method (grep -E '\\bstart\\s*\\(' AND grep -E '\\bstop\\s*\\(' each return at least one match)"
  - "infochat-core/src/main/java/io/infochat/core/ingest/NormalizedPost.java exists and declares `public record NormalizedPost` (grep -E 'public record NormalizedPost' returns at least one match)"
  - "infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java exists, contains at least one @Test annotation, and asserts via Class.forName (or equivalent reflective load) that all three of Fetcher, StreamSource, NormalizedPost are loadable on the infochat-core classpath; grep -E 'Class.forName' returns at least one match in this file"
  - "mvn -B -pl infochat-core test exits 0; surefire reports show at least one test executed in infochat-core (grep -rE 'Tests run: [1-9]' infochat-core/target/surefire-reports returns at least one match)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003 @QuarkusTest stubs and any newly-added test in infochat-core all pass"
test_plan:
  adds:
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (one plain-JUnit @Test that reflectively loads Fetcher, StreamSource, and NormalizedPost and asserts each is non-null and has the expected kind — interface for Fetcher and StreamSource, record for NormalizedPost)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
spec_refs:
  - docs/spec/architecture.md §Ingest SPIs
  - docs/spec/architecture.md §Architectural principles
  - docs/design/01-architecture.md §1.2 Module layout (Maven)
decision_refs:
  - D38

reviews:
  - round: 1
    date: 2026-05-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 238
      removed: 18
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-007a: infochat-core + ingest SPIs

## Context

First subticket of the M1-007 umbrella (per docs/process/workflow.md
§Ticket-ID placeholder convention — the umbrella + subticket idiom).
M1-007 splits "introduce the seven SPI interfaces v1 needs" into three
substantively-disjoint Maven-module-introduction subtickets plus a
whole-topic integration test on the umbrella. This subticket lands the
foundational `infochat-core` module and the two ingest-side SPIs
(`Fetcher` for polled sources, `StreamSource` for event-driven sources).
The two LLM SPIs land in M1-007b; the three messaging SPIs land in
M1-007c; the cross-module load test ships as the M1-007 commit.

`infochat-core` is the foundation every other module pulls from. It is
deliberately a plain library jar (no Quarkus extensions) so that adding
Quarkus-aware logic later does not retroactively turn `infochat-core`
into a Quarkus app. Consumers that need Quarkus features (CDI,
Panache, Flyway) layer those extensions in their own `pom.xml`.

This is an interfaces-only ticket. No `Fetcher` impl, no `StreamSource`
impl, no `FetchScheduler`, no Outbox wiring. Those land in Tier 1 and
Tier 3 implementation tickets that depend on M1-007a.

## Definition of Done

- A new Maven module `infochat-core/` lives at the repo root, declared
  under `<modules>` in the parent `pom.xml`. Its own `pom.xml` is a
  plain library-jar shape: groupId/artifactId/version inherited from
  the parent, no Quarkus extensions, no explicit `<version>` elements
  on `<dependency>` entries (the BOM still supplies versions for any
  test dependencies the module needs).
- `infochat-core` is added as a `<dependency>` in BOTH
  `infochat-collector/pom.xml` and `infochat-provider/pom.xml`. Both
  Quarkus apps now pull the SPI types from a shared module rather
  than duplicating them.
- Two SPI interfaces exist under `io.infochat.core.ingest`:
  - `Fetcher` — the polled, request/response ingest SPI per
    `docs/spec/architecture.md` §Ingest SPIs. Returns a
    `List<NormalizedPost>` from a `fetch(...)` call against a source
    descriptor. The exact parameter shape (the source-descriptor
    type, the call context) is an implementation choice for this
    ticket — keep it minimal; later tickets will add what they need.
  - `StreamSource` — the long-lived, event-driven ingest SPI per the
    same spec section. Has `start(...)` and `stop(...)` lifecycle
    methods plus a delivery hook the impl uses to push normalized
    posts to the outbox. Same minimality posture: don't pre-build
    a registry or supervisor here; lifecycle ownership belongs to
    the impl-side ticket.
- One supporting record exists under `io.infochat.core.ingest`:
  - `NormalizedPost` — Java record (immutable by design). Field set:
    `long sourceId`, `String upstreamIdentifier`, `String title`
    (nullable), `String body`, `String url` (nullable),
    `java.time.Instant publishedAt` (nullable), `java.time.Instant
    fetchedAt`, `java.util.Map<String, String> rawMetadata` (non-null,
    possibly empty — defensive copy in the canonical constructor is
    optional but if the implementer chooses to enforce it, do it
    once in the canonical constructor; not at every call site).
- One smoke test under `infochat-core/src/test/java/io/infochat/core/ingest/`:
  - Plain-JUnit `@Test` that reflectively loads `Fetcher`,
    `StreamSource`, and `NormalizedPost` via `Class.forName` and
    asserts each is non-null. Verifies for `Fetcher` and
    `StreamSource` that `Class.isInterface()` is true, and for
    `NormalizedPost` that `Class.isRecord()` is true. This is
    interface-shape verification, not behavior verification.
- `mvn -B clean verify` from the repo root exits 0. The two M1-003
  @QuarkusTest stubs continue to pass; the new infochat-core
  smoke test runs and passes.

## Implementation notes

- **Method-shape fidelity to spec.** `docs/spec/architecture.md` §Ingest
  SPIs is the contract. The spec describes Fetcher as polled
  request/response and StreamSource as event-driven; the exact Java
  signatures are an implementation choice within those constraints.
  Keep the signatures minimal — every parameter the SPI grows here is
  a parameter every later impl has to thread through. Implementation
  tickets will add what they actually need.
- **No call-context type yet.** A trace-id / observability call-context
  parameter is mentioned in `docs/spec/llm.md` §SPI shape for the LLM
  side; ingest does not yet require one in spec text. Don't add one
  preemptively — if it turns out to be needed, the implementation
  ticket that needs it can add the parameter (and update the SPI in
  one focused diff).
- **`NormalizedPost` field minimum.** The handoff field set
  (`sourceId`, `upstreamIdentifier`, `title`, `body`, `url`,
  `publishedAt`, `fetchedAt`, `rawMetadata`) is the v1 minimum. Do
  not add fields beyond this set — derived columns like normalized
  language tag, sanitized HTML body, redaction markers etc. are
  produced *downstream* of the Fetcher (Stage 1 sanitizer; tagger;
  embedding) and live on the eventual `posts` row, not on the
  outbox-input shape.
- **`raw_metadata` shape.** Keep it `Map<String, String>` for v1.
  Some sources will want richer per-element metadata (Bluesky reposts
  with separate counts; Nostr tag arrays). The map-of-string shape
  forces those impls to serialize structured values (e.g. a JSON
  string in one entry) rather than letting `Map<String, Object>`
  smuggle uncontrolled types through. The serialization choice is
  an impl concern; the SPI just commits to a flat string-string map.
- **No `package-info.java` required.** The package documentation can
  live on the type Javadoc; a separate `package-info.java` adds a
  file without adding clarity at this stage.
- **Module-path coordinates.** Use group `infochat`, artifact
  `infochat-core`, version inherited from parent (no `<version>`
  element on the module's own `<groupId>/<artifactId>` block — the
  parent supplies it). This matches `infochat-collector` /
  `infochat-provider`.
- **Test framework.** Plain JUnit 5 is enough. The test does not need
  Quarkus runtime context — `Class.forName` works on the bare
  classpath. Pull in `junit-jupiter-api` (BOM-managed via the
  Quarkus platform import) with `<scope>test</scope>`.

## Big-picture notes

- **`infochat-core` will eventually own the Flyway migrations** (per
  `docs/design/01-architecture.md` §1.2). The migration-move follow-up
  filed against M1-005's §Big-picture notes is exactly this:
  once M1-007a lands, file a separate ticket that moves
  `infochat-collector/src/main/resources/db/migration/` into
  `infochat-core/src/main/resources/db/migration/`, adds
  `quarkus-flyway` to a Quarkus-aware sibling of `infochat-core` (or
  to `infochat-core` itself if the Flyway extension can be safely
  added without otherwise turning it into a Quarkus app), and
  removes `quarkus-flyway` from `infochat-collector/pom.xml`. **Do
  NOT do the move in this ticket** — bundling it would balloon the
  diff and cross the migration-touch boundary, which would force
  serial execution against any other in-flight ticket.
- **The asset-Fetcher output-type discriminator** is a known followup.
  `docs/spec/architecture.md` §Ingest SPIs says the Fetcher SPI
  carries an output-type discriminator so the per-tick dispatch
  routes asset rows to `price_snapshot` and post rows to the outbox.
  M1-007a deliberately keeps Fetcher returning `List<NormalizedPost>`
  only because there is no asset-side caller in Tier 1; the asset
  command tickets (M1's asset slice) will add the discriminator in
  the same diff that introduces the asset Fetcher impl. Adding the
  discriminator now without a caller would be speculative SPI
  surface that the impl ticket would have to re-shape anyway.
- **Subticket isolation.** This ticket touches no LLM-related types
  and no messaging-related types. It can run in parallel with M1-007b
  and M1-007c only after each ticket's `files_scope` is provably
  disjoint from the others'; in practice the umbrella M1-007 is
  blocked on all three subtickets so the practical execution order
  is sequential or fan-out. Either is fine; the round-cap on each
  subticket is independent.
- **No SPI is enough by itself.** A reader looking only at
  `infochat-core` after this ticket will see two interfaces with
  no implementations and one supporting record. That is the intended
  state at end-of-ticket. The smoke test exists *because* there is
  no behavior to verify yet — it asserts the interfaces compile and
  load. The richer cross-module verification (all SPIs from all
  three modules visible from the same classpath) is what M1-007's
  whole-topic integration test provides.

## Out-of-scope expansion

- **The umbrella's whole-topic integration test
  (`infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java`)**
  is reserved for M1-007's commit. The umbrella + subticket idiom
  exists exactly so that whole-topic verification ships as its own
  reviewable unit. Pre-empting it here (e.g., by writing a test in
  `infochat-core` that loads classes from `infochat-llm-adapter` or
  `infochat-messaging-adapter`) would erase the umbrella's reason
  to exist and create a circular module dependency
  (`infochat-core` does not, and will not, depend on
  `infochat-llm-adapter` or `infochat-messaging-adapter`).
- **Concrete Fetcher / StreamSource implementations.** RssFetcher,
  BlueskyFetcher, RedditFetcher, NostrStreamSource, etc. are each
  their own Tier-1 / Tier-3 ticket. The reviewer treats any
  `*Fetcher`/`*StreamSource` class added under
  `infochat-collector/src/main/java/` here as scope drift.
- **The output-type discriminator.** See Big-picture notes — defer to
  the asset-Fetcher ticket.
- **Registry / factory / @Qualifier wiring.** Looking up a Fetcher
  impl by `source.kind` is the FetchScheduler's job (a separate
  ticket). The SPI is interface-only here; the lookup mechanism is
  not.
- **Pagination, retry, backoff, per-relay degradation, drain-on-
  shutdown.** All are spec-level "implementations MUST" commitments
  (`docs/spec/architecture.md` §Ingest SPIs). They are *impl*
  responsibilities; the interface does not need to encode them as
  method shapes. Any default-method scaffolding here would be
  speculative.
- **Flyway migration files / `db/migration/`.** None move in this
  ticket. M1-005's V1 migration stays in Collector.
- **Quarkus extensions in `infochat-core/pom.xml`.** None. The
  module is a plain library jar.
- **LLM, embedding, messaging, translation, progress-notifier SPIs.**
  M1-007b and M1-007c.
- **`infochat-ssrf` module.** Mentioned in
  `docs/design/01-architecture.md` §1.2 but a separate ticket.

## Authorized test changes

- (none — this ticket adds one new test class in `infochat-core` and
  modifies no pre-existing tests. The two M1-003 @QuarkusTest stubs
  are unchanged.)

## Alternatives considered

- **Put `Fetcher` and `StreamSource` in `infochat-collector`.**
  Rejected: Provider also needs to know the SPI types (`/add-source`
  validates a source descriptor against the Fetcher contract; future
  admin commands surface SPI-shaped state). Duplicating types in
  Collector and Provider would diverge the moment one side adds a
  field. `infochat-core` is the natural home per
  `docs/design/01-architecture.md` §1.2.
- **Bundle all seven SPIs into one ticket.** Rejected: the diff would
  be 25+ files and three module introductions in one commit. The
  umbrella + subticket idiom (workflow §Ticket-ID placeholder
  convention) exists for exactly this case — split into substantive
  slices, ship each as its own reviewable unit, and reserve the
  whole-topic verification for the umbrella commit.
- **Add a `FetcherKind` enum here naming `RSS`, `BLUESKY`, etc.**
  Rejected: the per-kind enum is design-tier (`docs/design/01-
  architecture.md` covers the kind list) and changes whenever a
  source is added. Encoding it in the SPI module would force a
  bump of `infochat-core` for every new ingest type. The
  `source.kind` column carries strings; the FetchScheduler's
  kind→impl lookup is a registry pattern in Collector, not an enum
  in core.
- **Add the asset-Fetcher output-type discriminator now.**
  Rejected: no caller in Tier 1. See Big-picture notes.
- **Use a `sealed interface` for Fetcher / StreamSource so impls
  are statically known.** Rejected: the spec explicitly calls these
  pluggable adapters (`docs/spec/architecture.md` §Architectural
  principles, principle 6). Sealing them would prevent operator-
  authored or third-party impls without a core-module change,
  which is exactly the property the SPI is meant to avoid.
