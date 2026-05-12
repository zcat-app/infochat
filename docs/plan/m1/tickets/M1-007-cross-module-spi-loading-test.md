---
id: M1-007
title: Cross-module SPI loading test
status: done
created: 2026-05-11
last_updated: 2026-05-12
blocked_by:
  - M1-007a
  - M1-007b
  - M1-007c
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java
  - infochat-provider/pom.xml
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any change under infochat-core/, infochat-llm-adapter/, or infochat-messaging-adapter/ (their SPIs were authored by the three subtickets and are FROZEN at the umbrella round; if a defect surfaces here, file a follow-up rather than amending the subticket commits)
  - any change under infochat-collector/ (the umbrella verification lives in Provider because Provider transitively depends on all three new modules; Collector's view of the SPIs is verified per-module by the subtickets' own smoke tests)
  - any new SPI type, supporting record, or supporting enum (the three subtickets defined the full type set; the umbrella loads them, it does not extend them)
  - any concrete implementation (no SimpleXAdapter, no OllamaLlmProvider, no RssFetcher — those are Tier-3 tickets)
  - any Quarkus extension addition beyond a maven-failsafe-plugin configuration block needed to execute the IT-named test class (the wiring stub the handoff anticipated; if Provider's pom already has failsafe configured, the test file is the only edit needed and the budget shrinks to 1)
  - any modification to the M1-003 @QuarkusTest stubs in either Collector or Provider (they continue to pass unchanged)
  - any per-SPI behavior assertion (the umbrella IT verifies LOADABILITY across modules, not behavior; behavior verification per SPI is the impl-ticket's job)
acceptance:
  - "infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java exists, contains at least one @Test annotation, and reflectively loads via Class.forName ALL of: io.infochat.core.ingest.Fetcher, io.infochat.core.ingest.StreamSource, io.infochat.core.ingest.NormalizedPost, io.infochat.llm.LlmProvider, io.infochat.llm.EmbeddingProvider, io.infochat.llm.ModelTask, io.infochat.llm.LlmResponse, io.infochat.llm.EmbeddingResult, io.infochat.messaging.MessagingAdapter, io.infochat.messaging.TranslationProvider, io.infochat.messaging.ProgressNotifier, io.infochat.messaging.ProgressStage, io.infochat.messaging.MessageHandle, io.infochat.messaging.CapabilityFlags (grep -E 'Class.forName' returns at least 14 matches across the file, OR a single loop over a fully-qualified-name list of length 14 — either shape acceptable)"
  - "the IT asserts via Class.isInterface() that the seven SPI interfaces are interfaces (Fetcher, StreamSource, LlmProvider, EmbeddingProvider, MessagingAdapter, TranslationProvider, ProgressNotifier)"
  - "the IT asserts via Class.isRecord() that the four supporting records are records (NormalizedPost, LlmResponse, EmbeddingResult, MessageHandle, CapabilityFlags) — five records, one assertion each"
  - "the IT asserts via Class.isEnum() that the two supporting enums are enums (ModelTask, ProgressStage)"
  - "the IT is named with the *IT suffix (file name AllSpisLoadIT.java) and is picked up by either maven-failsafe-plugin (preferred for *IT) OR maven-surefire-plugin if Provider's surefire is already configured to include *IT files; the wiring stub in Provider's pom.xml authorizes whichever plugin selection the implementer makes — the umbrella ticket may modify infochat-provider/pom.xml only to add or activate that wiring"
  - "mvn -B clean verify from the repo root exits 0; the IT executes and is counted in surefire-reports OR failsafe-reports (grep -rE 'AllSpisLoadIT' infochat-provider/target/surefire-reports infochat-provider/target/failsafe-reports returns at least one match in either directory)"
  - "the existing M1-003 @QuarkusTest stubs in BOTH modules continue to pass (no regressions)"
test_plan:
  adds:
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (one plain-JUnit @Test that walks a list of fourteen fully-qualified SPI / supporting-type names from all three new modules and asserts each is loadable via Class.forName plus has the expected kind — interface / record / enum)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
spec_refs:
  - docs/spec/architecture.md §Architectural principles
  - docs/design/01-architecture.md §1.2 Module layout (Maven)
decision_refs: []

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
      spec_conformance: PASS
    diff_stats:
      files: 4
      added: 132
      removed: 21
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-12
  verdict: PASS
  warnings:
    - "Acceptance item 3 labels the records as 'four supporting records' but names five (NormalizedPost, LlmResponse, EmbeddingResult, MessageHandle, CapabilityFlags). The Definition of Done body correctly says 'five supporting records.' The typo in the acceptance item's label does not affect testability — the list of five names is authoritative — but a developer reading only the acceptance header could initially be confused. Consider fixing the label from 'four' to 'five' for consistency."
  blockers: []
---

# M1-007: Cross-module SPI loading test

## Context

Umbrella commit for the M1-007 group (per `docs/process/workflow.md`
§Ticket-ID placeholder convention — the umbrella + subticket idiom).
M1-007a, M1-007b, and M1-007c each introduce one of the three new
Maven modules (`infochat-core`, `infochat-llm-adapter`,
`infochat-messaging-adapter`) and the SPI types those modules carry.
Each subticket's per-module smoke test verifies its own SPIs compile
and load on its own classpath. This umbrella commit verifies the
cross-module property the subtickets cannot verify in isolation: that
all fourteen SPI types and supporting records/enums from all three
new modules are simultaneously loadable from the same classpath.

The whole-topic verification is meaningfully different from any
single subticket's per-module test. A subticket-only verification
would miss two failure modes: (a) a transitive dependency wiring bug
where `infochat-llm-adapter` is on Collector's classpath but not on
Provider's (or vice versa), and (b) a same-named-package collision
across modules (e.g., two modules claim `io.infochat.spi`). Shipping
the cross-module assertion as its own reviewable unit is exactly the
umbrella + subticket idiom's reason to exist.

The IT lives in `infochat-provider/` because Provider transitively
depends on ALL three new modules (Provider → infochat-core, Provider
→ infochat-llm-adapter, Provider → infochat-messaging-adapter).
Collector depends on the first two but NOT on
infochat-messaging-adapter (per `docs/design/01-architecture.md` §1.2
— Collector has no user-facing messaging surface), so a single IT in
Provider is sufficient. The alternative (one IT in Collector for
core+llm and a second in Provider for messaging) would split the
cross-module assertion across two test files with no benefit.

## Definition of Done

- A single plain-JUnit `@Test` lives at
  `infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java`.
- The test reflectively loads, via `Class.forName`, all fourteen
  fully-qualified type names introduced by the three subtickets:
  - From `infochat-core` (M1-007a): `io.infochat.core.ingest.Fetcher`,
    `io.infochat.core.ingest.StreamSource`,
    `io.infochat.core.ingest.NormalizedPost`.
  - From `infochat-llm-adapter` (M1-007b): `io.infochat.llm.LlmProvider`,
    `io.infochat.llm.EmbeddingProvider`, `io.infochat.llm.ModelTask`,
    `io.infochat.llm.LlmResponse`, `io.infochat.llm.EmbeddingResult`.
  - From `infochat-messaging-adapter` (M1-007c):
    `io.infochat.messaging.MessagingAdapter`,
    `io.infochat.messaging.TranslationProvider`,
    `io.infochat.messaging.ProgressNotifier`,
    `io.infochat.messaging.ProgressStage`,
    `io.infochat.messaging.MessageHandle`,
    `io.infochat.messaging.CapabilityFlags`.
- For each loaded class, the test asserts the expected kind:
  `Class.isInterface()` true for the seven SPI interfaces;
  `Class.isRecord()` true for the five supporting records;
  `Class.isEnum()` true for the two supporting enums.
- The test is named with the `*IT` suffix (Failsafe convention for
  integration tests) and is picked up by `mvn verify`. If
  `infochat-provider/pom.xml` does not already have a Failsafe
  configuration (or surefire `<include>**/*IT.java</include>`), this
  ticket adds the minimum wiring to either plugin to execute the
  IT during `mvn verify`. The wiring is the only acceptable
  modification to `infochat-provider/pom.xml`.
- `mvn -B clean verify` from the repo root exits 0. The IT executes
  and is counted in surefire-reports or failsafe-reports. The two
  M1-003 @QuarkusTest stubs and the three subticket smoke tests
  continue to pass.

## Implementation notes

- **Plain JUnit, not @QuarkusTest.** `Class.forName` works on the
  bare classpath; spinning up the Quarkus context just to verify
  type loadability would slow the test for no benefit and would
  blur what the test is actually proving (classpath visibility,
  not bean wiring).
- **Walk a list, don't unroll.** A single `for`-loop over a list
  of fourteen fully-qualified names with three `assert` cases
  inside (interface / record / enum) is shorter, clearer, and
  easier to extend than fourteen unrolled `Class.forName` calls.
  Either shape meets acceptance — pick the loop unless there is
  a reason not to.
- **One IT, in Provider.** Provider transitively depends on all
  three new modules. A single test in Provider is sufficient.
  Adding a second test in Collector for the core + llm-adapter
  subset would duplicate coverage without finding a failure mode
  the Provider IT misses.
- **Failsafe vs surefire.** Maven Failsafe is the conventional
  plugin for `*IT` tests; surefire runs `*Test`. If
  `infochat-provider/pom.xml` already configures one of them to
  include `*IT`, the test file alone suffices and the budget
  shrinks to 1. If neither does, add a minimal Failsafe plugin
  block to Provider's pom (the M1-003 baseline did not need it
  because all M1-003 tests are `*Test`-named). The wiring stub
  is the only authorized non-test change.
- **Why the IT name uses `IT`, not `Test`.** The cross-module
  assertion is integration-shaped (exercises classpath visibility
  across three sibling modules) even though the assertions
  themselves are local. The `*IT` suffix is the Maven convention
  for that shape; it also keeps the file from running during
  `mvn -pl infochat-provider test` (where its purpose — checking
  the umbrella's cross-module wiring — would be misleading) and
  reserves it for `mvn verify` from the repo root, which is the
  scope where "all three modules are on the classpath" is the
  meaningful question.
- **No behavior assertions.** The IT verifies LOADABILITY only.
  Behavior verification per SPI is the impl-ticket's job (e.g.,
  the `OllamaLlmProvider` ticket asserts that calling `generate`
  with `SECURITY_JUDGE` returns a structured response). Adding
  per-SPI behavior here would force this ticket to depend on
  test doubles for every SPI, which the subtickets did not
  provide and the umbrella does not author.
- **No new SPI types here.** The three subtickets defined the
  type set in full. If the umbrella reviewer notices a missing
  type, file a follow-up subticket; do not extend the set in
  this commit.

## Big-picture notes

- **The subticket commits are FROZEN at the umbrella round.**
  M1-007a, M1-007b, and M1-007c each landed as their own
  reviewable commit on `main` before M1-007 became runnable. If
  this ticket's IT exposes a defect in one of the subticket
  outputs (e.g., a record mistakenly declared as a class, a
  package-name typo), the fix is a NEW ticket against the
  affected module — never an amendment to the subticket commit.
  The "never amend a passed commit" invariant in `CLAUDE.md` §M1
  workflow applies here verbatim.
- **The umbrella unblocks downstream impl tickets.** Once M1-007
  ships, all Tier-3 tickets that need an SPI to bind to (Ollama
  provider, RSS fetcher, SimpleX adapter, etc.) can start. They
  each depend on the relevant subticket; the umbrella commit
  proves the SPI surface is reachable cross-module before any
  impl is written.
- **Future SPI evolutions.** When an impl ticket needs to grow
  one of the SPIs (add a method, add a parameter), the impl
  ticket touches both the SPI module and its own impl in one
  diff. The cross-module IT here does not need to change unless
  the type list grows or shrinks. New SPIs introduced in later
  milestones (M2+) get their own umbrella + subticket pattern;
  do not expand this IT's type list to track them.
- **What the IT does NOT prove.** It does not prove that the SPIs
  are well-shaped (the subtickets' Javadoc, acceptance, and
  reviewer feedback do that), that the impls correctly satisfy
  the contracts (impl-ticket tests do that), or that the wiring
  layer dispatches correctly (router/wiring tickets do that).
  It proves only that all fourteen types are simultaneously
  visible on Provider's classpath. That is a small but
  load-bearing claim — when it fails, the dependency graph is
  broken.

## Out-of-scope expansion

- **Changes under the three new modules.** The three subticket
  commits define the SPI types. The umbrella loads them. If a
  defect surfaces, it becomes a new ticket against the affected
  module — not a touch in this commit.
- **Changes under `infochat-collector/`.** The cross-module
  verification lives in Provider; Collector's per-module SPI
  visibility is verified by the subtickets' own smoke tests.
- **New SPI types, records, or enums.** The subticket type set
  is complete.
- **Concrete implementations.** Tier-3 tickets.
- **Quarkus extension additions.** Only a Failsafe (or
  surefire-include) wiring stub is authorized. No new
  `quarkus-*` dependency.
- **Modifications to the M1-003 stubs or the three subticket
  smoke tests.** Those continue to pass unchanged; modifying any
  of them would be a test-integrity violation per
  `engineering-rules-verbatim.md` §8.
- **Per-SPI behavior assertions.** Loadability only.

## Authorized test changes

- (none — this ticket adds one new test class
  `infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java`
  and modifies no pre-existing tests.)

## Alternatives considered

- **Two ITs: one in Collector for core + llm-adapter, one in
  Provider for messaging.** Rejected: Provider already
  transitively sees all three modules' types, so a single IT
  there covers everything. The two-IT shape would split the
  cross-module assertion across two files for no diagnostic
  benefit — when one of them fails, the other still has to be
  checked manually.
- **Make it `@QuarkusTest` so it boots the Quarkus context.**
  Rejected: spinning up the Quarkus context to verify
  `Class.forName` works is overkill, slows the test, and could
  mask a classpath issue behind a slower (but unrelated)
  Quarkus startup failure. Plain JUnit isolates the assertion.
- **Inline the SPI list into per-module subticket smoke tests
  and skip the umbrella.** Rejected: each subticket can only
  see its own module's classpath at test time. Cross-module
  visibility is exactly the property a per-module test cannot
  prove. The umbrella exists for this property.
- **Use the `ServiceLoader` SPI mechanism so loadability is
  verified by Java's standard discovery instead of
  `Class.forName`.** Rejected: `ServiceLoader` requires
  `META-INF/services/` registration files, which would force
  the SPI modules to commit to a particular discovery shape
  before any impl exists. The plain-`Class.forName` shape is
  the lightest possible verification and leaves the discovery
  mechanism to the impl tickets (CDI `@Qualifier`, manual
  registry, ServiceLoader — each fine).
- **Add behavior assertions (e.g., construct a `NormalizedPost`
  with the canonical constructor and check field accessors).**
  Rejected: the subticket smoke tests cover per-type instance
  construction within their own module. The umbrella's job is
  cross-module visibility, not redundant per-type behavior.
