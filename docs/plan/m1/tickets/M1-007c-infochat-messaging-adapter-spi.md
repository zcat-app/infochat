---
id: M1-007c
title: infochat-messaging-adapter SPIs
status: pending
created: 2026-05-11
last_updated: 2026-05-11
blocked_by:
  - M1-001
files_budget: 12
files_scope:
  - pom.xml
  - infochat-messaging-adapter/pom.xml
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/TranslationProvider.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/ProgressNotifier.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/ProgressStage.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessageHandle.java
  - infochat-messaging-adapter/src/main/java/io/infochat/messaging/CapabilityFlags.java
  - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java
  - infochat-provider/pom.xml
out_of_scope:
  - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (the umbrella M1-007's whole-topic integration test — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention)
  - infochat-collector/pom.xml (Collector intentionally does NOT depend on infochat-messaging-adapter per docs/design/01-architecture.md §1.2 — only Provider talks to messaging backends)
  - any concrete MessagingAdapter implementation (SimpleX, Signal, InMemory all land in their own Tier-3 messaging-impl tickets — decision D32, D46)
  - any concrete TranslationProvider implementation (the localization-bundle plus per-language LLM-backed translator land in the Tier-3 translation ticket)
  - any concrete ProgressNotifier implementation or stage-string localization-bundle wiring (lives in the Tier-3 progress-notifier wiring ticket)
  - any startup-validation logic for supportsMarkdownLinks=true rejection (docs/spec/messaging.md §Capability flags — that fail-fast check belongs to the Provider startup wiring ticket once registration logic exists, not to the SPI)
  - any per-adapter property-key surface (docs/design/06-messaging.md owns those keys — they appear when the impl ticket lands)
  - any router / qualifier / multi-adapter selection logic (decision D46 — Provider hosts the multi-adapter union; the wiring belongs to a later ticket)
  - any Quarkus extension dependency in infochat-messaging-adapter/pom.xml beyond test-only (the module is a plain library jar; consumers add Quarkus extensions as needed)
  - any signal-cli / SimpleX-CLI wire-protocol code (those bind to the SPI in the impl ticket; not here)
acceptance:
  - "infochat-messaging-adapter/pom.xml exists; grep -E '<packaging>(jar)?</packaging>' returns either zero matches (omitted, default jar) or `<packaging>jar</packaging>`; explicitly NOT `<packaging>pom</packaging>`"
  - "grep -E '<module>infochat-messaging-adapter</module>' pom.xml returns at least one match (parent registers the new module)"
  - "grep -rEn '<version>' infochat-messaging-adapter/pom.xml returns zero matches inside <dependency> blocks (BOM supplies versions, M1-001 invariant preserved)"
  - "grep -rE '<artifactId>quarkus-' infochat-messaging-adapter/pom.xml returns ZERO matches outside any test-scope dependency block (plain library jar; no production Quarkus extensions)"
  - "grep -E '<artifactId>infochat-messaging-adapter</artifactId>' infochat-provider/pom.xml returns at least one match"
  - "grep -E '<artifactId>infochat-messaging-adapter</artifactId>' infochat-collector/pom.xml returns ZERO matches (Collector does NOT depend on this module per docs/design/01-architecture.md §1.2 — Collector has no user-facing messaging surface)"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessagingAdapter.java exists, declares `public interface MessagingAdapter` in package io.infochat.messaging, and exposes a method whose return type is `CapabilityFlags` (grep -E ':\\s*CapabilityFlags|CapabilityFlags\\s+capabilities' returns at least one match — the capability-flag accessor required by spec §Required SPI surface)"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/TranslationProvider.java exists and declares `public interface TranslationProvider` in package io.infochat.messaging"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/ProgressNotifier.java exists, declares `public interface ProgressNotifier` in package io.infochat.messaging, and has a method whose parameter list contains ProgressStage (grep -E 'ProgressStage\\s+\\w' returns at least one match)"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/ProgressStage.java exists, declares `public enum ProgressStage`, and contains the seven stage values STARTED, RETRIEVING, GENERATING, TRANSLATING, FINALIZING, COMPLETED, FAILED (grep matches each of the seven enum constants)"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/MessageHandle.java exists and declares `public record MessageHandle`"
  - "infochat-messaging-adapter/src/main/java/io/infochat/messaging/CapabilityFlags.java exists, declares `public record CapabilityFlags`, and the file references docs/spec/messaging.md §Capability flags in a Javadoc comment (grep -E 'Capability flags' returns at least one match in the file)"
  - "infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java exists, contains at least one @Test annotation, and reflectively loads MessagingAdapter, TranslationProvider, ProgressNotifier, ProgressStage, MessageHandle, CapabilityFlags; grep -E 'Class.forName' returns at least one match"
  - "mvn -B -pl infochat-messaging-adapter test exits 0; surefire reports show at least one test executed (grep -rE 'Tests run: [1-9]' infochat-messaging-adapter/target/surefire-reports returns at least one match)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003 @QuarkusTest stubs and any newly-added test in infochat-messaging-adapter all pass"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (one plain-JUnit @Test that reflectively loads MessagingAdapter, TranslationProvider, ProgressNotifier, ProgressStage, MessageHandle, and CapabilityFlags, asserting the expected kind for each — interface, interface, interface, enum, record, record)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Message handles
  - docs/spec/messaging.md §Progress notifications
  - docs/spec/llm.md §Translation flow
  - docs/design/01-architecture.md §1.2 Module layout (Maven)
decision_refs:
  - D32
  - D46
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-007c: infochat-messaging-adapter SPIs

## Context

Third subticket of the M1-007 umbrella. Lands the
`infochat-messaging-adapter` Maven module and the three Provider-side
SPIs v1 needs: `MessagingAdapter` (the transport contract per
`docs/spec/messaging.md` §Required SPI surface), `TranslationProvider`
(the LLM-authored-prose translator per `docs/spec/llm.md` §Translation
flow), and `ProgressNotifier` (the cross-cutting stage-event notifier
per `docs/spec/messaging.md` §Progress notifications), plus the two
supporting records `MessageHandle` and `CapabilityFlags` and the
`ProgressStage` enum. The two ingest SPIs ship in M1-007a; the two
LLM SPIs ship in M1-007b; the cross-module load test ships as the
M1-007 commit.

The three SPIs are grouped in `infochat-messaging-adapter` because
they are all **presentation-layer** concerns. `MessagingAdapter` is
the transport. `TranslationProvider` translates BOT prose for the
user's scope language (`docs/spec/llm.md` §Translation flow: "purely
a presentation-layer concern"; source post bodies are never
translated). `ProgressNotifier` turns a stream of stage events into
a single visibly-evolving message via the adapter's update/finalize
calls (spec §Progress notifications). All three are consumed only by
Provider; Collector intentionally does not depend on this module.

This is an interfaces-only ticket. No `SimpleXAdapter`, no
`SignalAdapter`, no `InMemoryAdapter`, no `EnglishCzechTranslator`,
no concrete notifier. Those land in Tier-3 impl tickets that depend
on M1-007c.

## Definition of Done

- A new Maven module `infochat-messaging-adapter/` lives at the repo
  root, declared under `<modules>` in the parent `pom.xml`. Its
  `pom.xml` is a plain library-jar shape (no Quarkus extensions in
  production scope; test-scope JUnit only). No explicit `<version>`
  on `<dependency>` entries — BOM supplies versions.
- `infochat-messaging-adapter` is added as a `<dependency>` in
  `infochat-provider/pom.xml`. It is NOT added to
  `infochat-collector/pom.xml` — Collector has no user-facing
  messaging surface per `docs/design/01-architecture.md` §1.2.
- Three SPI interfaces exist under `io.infochat.messaging`:
  - `MessagingAdapter` — the transport contract per `docs/spec/messaging.md`
    §Required SPI surface. Minimum surface for this ticket: a
    capability-flag accessor returning `CapabilityFlags`, a `send`
    method that returns `MessageHandle`, an `update(handle, body)`
    method, a `finalize(handle, body)` method, and an inbound-handler
    registration shape (callback-based). Identity assertion, typing
    indicator, membership events, transport-layer size caps are all
    spec-required but the exact method shape is impl-decision-tier
    — keep this ticket's `MessagingAdapter` surface minimal and let
    the SimpleX / Signal / InMemory tickets thread per-impl needs
    through SPI-additive evolutions. The capability-flag accessor is
    the load-bearing surface acceptance grep keys on.
  - `TranslationProvider` — `text + (from, to) → text` per
    `docs/spec/llm.md` §Translation flow. Minimum surface: one
    `translate(...)` method taking the text, source locale, and
    target locale, returning the translated string. The
    deterministic-localization-bundle path (decision D43) is a
    separate code path that does NOT go through `TranslationProvider`
    — document this in the Javadoc so the next reader doesn't try
    to route bundle keys through here.
  - `ProgressNotifier` — cross-cutting stage-event notifier per
    `docs/spec/messaging.md` §Progress notifications. Minimum
    surface: a `publish(...)` method that takes at minimum a scope
    identifier and a `ProgressStage` enum value. Coalescing,
    edit-interval enforcement, try/finally finalize, and typing-
    indicator pulses are all spec-required impl behaviors — they
    do not need to appear as separate SPI methods at this stage.
- One supporting enum, two supporting records exist under
  `io.infochat.messaging`:
  - `ProgressStage` — closed enum with the seven values from spec
    §Progress notifications: `STARTED`, `RETRIEVING`, `GENERATING`,
    `TRANSLATING`, `FINALIZING`, `COMPLETED`, `FAILED`.
  - `MessageHandle` — opaque token returned by
    `MessagingAdapter.send()` per spec §Message handles. The record
    wrapper exists so the type system tracks handle lifetime;
    callers MUST NOT inspect, persist, or pass it between service
    instances. The record's Javadoc MUST state these invariants
    explicitly (the spec section is the authority — link to it).
    The exact internal field is adapter-defined; for v1 a single
    `String opaqueValue` field is sufficient (the impl chooses
    what to put in it). Implementers must not rely on the field
    shape; readers should treat the record as a sealed token.
  - `CapabilityFlags` — closed record carrying the spec's minimum
    capability set (spec §Capability flags — minimum set). Fields:
    `TrustLevel trustLevel` (nested enum with `HIGH` and `LOW`;
    keeps file count low while preserving the closed shape),
    `boolean supportsCodeFormatting`, `boolean supportsMarkdownLinks`,
    `boolean supportsMessageEdit`, `java.time.Duration minEditInterval`,
    `boolean supportsTypingIndicator`, `boolean supportsMentionByContactId`,
    `boolean supportsMembershipEvents`. The record's Javadoc MUST
    reference §Capability flags (minimum set) so the closed shape's
    authority is visible from code.
- One smoke test under `infochat-messaging-adapter/src/test/java/`:
  - Plain-JUnit `@Test` that reflectively loads the three SPIs
    plus the three supporting types and asserts the expected kind
    for each.
- `mvn -B clean verify` from the repo root exits 0.

## Implementation notes

- **`MessagingAdapter` minimum surface.** The spec lists many
  required methods (identity assertion, receive, send, update,
  finalize, setTyping, membership events, transport-layer size
  cap, capability accessor). Encoding all of them as Java methods
  here would freeze the parameter shapes before any impl has had
  a chance to inform them. Keep the SPI to the four load-bearing
  call shapes — `capabilities()`, `send(...)`, `update(handle,
  body)`, `finalize(handle, body)` — plus the inbound-handler
  registration. Optional methods (`setTyping`, membership events)
  can be added in the impl ticket that first needs them; the
  Javadoc on `MessagingAdapter` should list every spec-required
  method as a "future-surface" note so the next reader sees the
  full obligation even though only a subset is encoded today.
- **Inbound-handler registration shape.** Spec §Required SPI surface
  says "Pushes inbound `(scope, contact_id, body)` to Provider."
  The minimal Java surface is `setInboundHandler(InboundHandler
  handler)` where `InboundHandler` is a functional interface
  (declared nested inside `MessagingAdapter` to avoid yet another
  file; or as a separate file if the implementer prefers — either
  meets acceptance). Don't pre-build a `BlockingQueue<InboundMessage>`
  here.
- **`TranslationProvider` is presentation-layer.** Translates the
  bot's LLM-authored prose for the user's scope language; source
  post bodies are NEVER translated (spec §Translation flow). The
  deterministic-localization-bundle path for `/help`, friendly-error
  templates, banned-user fixed reply, etc. is a *separate* code
  path that looks up by key in a bundle — those strings do NOT
  flow through `TranslationProvider` (spec §Translation flow,
  decision D43). The `TranslationProvider` Javadoc must call this
  out so the next reader doesn't route deterministic strings through
  here.
- **`ProgressNotifier` minimum surface.** Coalescing edits, honoring
  `max(adapterMin, system floor)` for edit cadence, the
  try/finally guarantee on `finalize`, and the "Stage strings are
  template-parameterized only with deterministic, sanitized scalar
  values" rule are all spec-mandated impl behaviors. They are NOT
  SPI methods. The interface just exposes `publish(...)`; the impl
  enforces the rules.
- **`ProgressStage` is closed.** Seven values from spec §Progress
  notifications. Adding an eighth without a spec amendment is a
  silent contract drift — the notifier renders each stage via a
  localization-bundle key, and adding a value here without a
  bundle key would either crash at runtime or silently produce an
  empty string in the user's scope. Both failure modes are bad;
  the closed enum prevents them.
- **`MessageHandle` opacity invariant.** The Javadoc MUST state:
  "Callers MUST NOT inspect, persist, or pass this handle between
  service instances. Valid only within the originating adapter,
  in-process." This is spec §Message handles verbatim-ish. The
  invariant is enforced by review, not by the type system — a
  determined caller can still `.opaqueValue()` the inside. The
  Javadoc is the single point of authority.
- **`TrustLevel` nested in `CapabilityFlags`.** The spec lists
  `trustLevel ∈ {HIGH, LOW}` as one of the capability flags. A
  nested enum inside `CapabilityFlags` keeps the file count to
  what the handoff specified (3 SPIs + enum + 2 records) while
  preserving the closed shape. A separate `TrustLevel.java` file
  would also be acceptable; either choice meets acceptance.
- **No `package-info.java` required.** Type Javadoc suffices.
- **Module-path coordinates.** Group `infochat`, artifact
  `infochat-messaging-adapter`, version inherited from parent
  (no `<version>` element on the module's own
  `<groupId>/<artifactId>` block).
- **Test framework.** Plain JUnit 5; no Quarkus runtime needed.

## Big-picture notes

- **Three v1 impls land in three separate later tickets.**
  SimpleXAdapter, SignalAdapter, and InMemoryAdapter (decision
  D32, D46). Each impl ticket evolves the SPI additively if it
  needs a method shape M1-007c left out — and that evolution is
  reviewable in the impl ticket's own diff. Decision D46 commits
  v1 to a Provider that can host any non-empty subset of the
  production adapters simultaneously (SimpleX, Signal, or both
  active in one process); the SPI is intentionally shaped to make
  multi-instance hosting natural — `MessagingAdapter` is a CDI
  bean with an adapter-type qualifier, and Provider's routing
  layer dispatches by inbound adapter.
- **Provider startup-fail-fast for `supportsMarkdownLinks = true`.**
  Spec §Capability flags mandates that Provider validates this
  flag at adapter registration and fails fast on a `true`
  declaration. That check is NOT this ticket — it belongs to the
  Provider startup wiring that introduces the adapter registry.
  The `CapabilityFlags` record here carries the flag; somebody
  else checks it.
- **`TranslationProvider` and the localization bundle are two
  paths.** Decision D43, spec §Translation flow. LLM-authored
  prose (cluster summaries, chat replies, digest headers,
  `/retry` re-rolls) goes through `TranslationProvider`.
  Deterministic strings (`/help`, friendly-error templates,
  progress-notifier stage strings, banned-user fixed reply) come
  from a localization bundle by key. Mixing the two paths is
  explicitly out of v1. M1-007c's `TranslationProvider` exposes
  ONLY the LLM-prose path; bundle-key lookup is a separate
  utility-class concern in a later ticket.
- **Subticket isolation.** This ticket touches no ingest-related
  types and no LLM-provider types. M1-007a, M1-007b, and M1-007c
  each introduce a disjoint Maven module; their `files_scope`
  lists are disjoint by construction. Sequential or fan-out
  execution order is operator's choice.
- **The umbrella's whole-topic integration test lives in Provider.**
  `infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java`
  is the M1-007 commit. Provider transitively depends on all three
  new modules (Provider → infochat-core, Provider →
  infochat-llm-adapter, Provider → infochat-messaging-adapter),
  so one cross-module test in Provider is sufficient — the
  alternative of a test-in-Collector-plus-a-test-in-Provider is
  rejected because the messaging SPI is Provider-only. This is
  the locked-in resolution from the handoff and is noted here so
  the M1-007 umbrella ticket has a clean back-reference.

## Out-of-scope expansion

- **The umbrella's whole-topic integration test
  (`infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java`)**
  is reserved for M1-007. The umbrella + subticket idiom exists
  exactly so cross-module verification ships as its own
  reviewable unit.
- **Collector dependency on `infochat-messaging-adapter`.** Forbidden
  by `docs/design/01-architecture.md` §1.2 — Collector has no
  user-facing messaging surface. Adding the dep here would create
  a confused dependency graph and let Collector accidentally
  consume messaging types.
- **Concrete impls.** SimpleXAdapter, SignalAdapter, InMemoryAdapter,
  EnglishCzechTranslator, and the concrete `ProgressNotifier`
  each land in their own Tier-3 ticket.
- **Provider startup-fail-fast for `supportsMarkdownLinks = true`.**
  Provider startup wiring, not SPI.
- **Per-adapter property keys.** `docs/design/06-messaging.md` owns
  those — they appear when the impl ticket lands.
- **Router / qualifier / multi-adapter selection.** Decision D46 — a
  later ticket wires the routing layer that dispatches by inbound
  adapter.
- **`signal-cli` / SimpleX-CLI wire-protocol code.** Bind to the
  SPI in the impl ticket; not here.
- **Localization bundle wiring.** A separate utility ticket loads
  the `en` and `cs` bundles from resource files. Independent of
  `TranslationProvider`.
- **Quarkus extensions in `infochat-messaging-adapter/pom.xml`.**
  None. Plain library jar.

## Authorized test changes

- (none — this ticket adds one new test class in
  `infochat-messaging-adapter` and modifies no pre-existing
  tests.)

## Alternatives considered

- **Group `TranslationProvider` with `LlmProvider` / `EmbeddingProvider`
  in `infochat-llm-adapter` (M1-007b).** Tempting because the
  spec lists all three under `docs/spec/llm.md` §SPI shape, and
  a translator is in some sense "an LLM call." Rejected: in v1
  the deployment surface forces translation to live with
  presentation. Translation is consumed only by Provider, runs
  ONLY against bot-authored prose (never against user input or
  post bodies — spec §Translation flow), and pairs naturally
  with `ProgressNotifier` and `MessagingAdapter` because all
  three are presentation-layer. Putting it in
  `infochat-llm-adapter` would force Collector to pull a
  dependency it never uses. The grouping in this ticket follows
  consumer-symmetry (Provider-only), not spec-section grouping.
- **Carry the FULL spec §Required SPI surface as Java methods
  in `MessagingAdapter` (every method spec-mandates).** Rejected:
  freezes parameter shapes before impls have informed them. The
  Javadoc enumerates the full obligation; the four load-bearing
  call shapes are encoded; the rest is impl-additive.
- **Make `CapabilityFlags` an interface (so each adapter declares
  its own implementation).** Rejected: the spec calls it a "static
  description" — a record with a closed field set is the right
  shape. An interface would invite per-adapter capability methods
  with no static surface, which makes Provider startup-fail-fast
  validation (`supportsMarkdownLinks = true` rejection) brittle.
- **Make `MessageHandle` a sealed interface with one impl per
  adapter.** Rejected: the spec deliberately calls it opaque —
  callers MUST NOT inspect. A sealed-interface shape lets callers
  pattern-match on the concrete type, which is exactly the
  invariant we want to forbid. The record-wrapper-with-Javadoc-
  invariant is the right shape.
- **Skip `ProgressStage` and just pass strings.** Rejected: the
  stage names are spec-closed (`docs/spec/messaging.md` §Progress
  notifications enumerates exactly the seven). A string-based
  API would invite typos that produce empty user-visible
  output, and the closed enum aligns with the localization-bundle
  key set (decision D43).
- **Bundle a `localizationKey()` method on `ProgressStage`.**
  Tempting because each stage maps to a bundle key. Rejected:
  couples the SPI module to the localization-bundle key namespace,
  which is design-tier (`docs/design/06-messaging.md` /
  decision D43). The notifier impl handles the mapping; the enum
  stays a pure discriminator.
