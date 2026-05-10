---
id: M1-007b
title: infochat-llm-adapter + LLM SPIs
status: pending
created: 2026-05-11
last_updated: 2026-05-11
blocked_by:
  - M1-001
files_budget: 10
files_scope:
  - pom.xml
  - infochat-llm-adapter/pom.xml
  - infochat-llm-adapter/src/main/java/io/infochat/llm/LlmProvider.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/EmbeddingProvider.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/ModelTask.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/LlmResponse.java
  - infochat-llm-adapter/src/main/java/io/infochat/llm/EmbeddingResult.java
  - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java
  - infochat-collector/pom.xml
  - infochat-provider/pom.xml
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (the umbrella M1-007's whole-topic integration test — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention)
  - any concrete LlmProvider or EmbeddingProvider implementation (Ollama, llama.cpp, OpenAI-compatible, Anthropic adapters all land in their own Tier-3 LLM tickets)
  - any TranslationProvider interface (lives in infochat-messaging-adapter / M1-007c — translation is a presentation-layer concern per docs/spec/llm.md §Translation flow and is consumed by Provider only; placing it in the messaging module keeps the LLM module Collector-and-Provider symmetric)
  - any router / qualifier / per-task wiring (the (ModelTask, scope_language) → LlmProvider router lives in a separate Provider/Collector wiring ticket once impls exist)
  - any prompt-template or wrapper-delimiter logic (docs/spec/llm.md §Prompt-injection-aware prompt shape — design-tier; lives with the impls, not the SPI)
  - any LangChain4j extension dependency (no quarkus-langchain4j-* here; the SPI is provider-agnostic. LangChain4j shows up as a transitive when an impl ticket adds the binding)
  - any per-profile model-default property (docs/design/05-llm-and-embeddings.md owns those keys; they appear when the impl + router ticket lands)
  - any embedding model-identity guard, dimensionality-mismatch check, or singleton metadata row (docs/spec/llm.md §Embedding pipeline — those are runtime-impl concerns wired by the embedding-pipeline ticket, not SPI surface)
  - any local-only-property startup conflict check (docs/spec/llm.md §Per-task routing rules — Provider startup wiring, not SPI)
  - any Quarkus extension dependency in infochat-llm-adapter/pom.xml beyond test-only (this module is a plain library jar; consumers add Quarkus extensions as needed)
acceptance:
  - "infochat-llm-adapter/pom.xml exists; grep -E '<packaging>(jar)?</packaging>' returns either zero matches (omitted, default jar) or `<packaging>jar</packaging>`; explicitly NOT `<packaging>pom</packaging>`"
  - "grep -E '<module>infochat-llm-adapter</module>' pom.xml returns at least one match (parent registers the new module)"
  - "grep -rEn '<version>' infochat-llm-adapter/pom.xml returns zero matches inside <dependency> blocks (BOM supplies versions, M1-001 invariant preserved)"
  - "grep -rE '<artifactId>quarkus-' infochat-llm-adapter/pom.xml returns ZERO matches outside any test-scope dependency block (the module is a plain library jar; no production Quarkus extensions)"
  - "grep -E '<artifactId>infochat-llm-adapter</artifactId>' infochat-collector/pom.xml returns at least one match"
  - "grep -E '<artifactId>infochat-llm-adapter</artifactId>' infochat-provider/pom.xml returns at least one match"
  - "infochat-llm-adapter/src/main/java/io/infochat/llm/LlmProvider.java exists, declares `public interface LlmProvider` in package io.infochat.llm, and has at least one method whose first parameter is of type ModelTask (grep -E 'ModelTask\\s+\\w' returns at least one match in the file)"
  - "infochat-llm-adapter/src/main/java/io/infochat/llm/EmbeddingProvider.java exists, declares `public interface EmbeddingProvider` in package io.infochat.llm, and exposes a batch method whose parameter type contains `List<String>` (grep -E 'List<String>' returns at least one match in the file) — per docs/spec/llm.md §Embedding pipeline 'Batch SPI' rule"
  - "infochat-llm-adapter/src/main/java/io/infochat/llm/ModelTask.java exists, declares `public enum ModelTask` and contains EXACTLY the six values SECURITY_JUDGE, TAGGER, ENTITY, SUMMARIZER, CHAT_AGENT, TRANSLATOR (one match each from grep; the embedder is intentionally NOT a ModelTask per docs/spec/llm.md §SPI shape 'Scope of the enum')"
  - "infochat-llm-adapter/src/main/java/io/infochat/llm/LlmResponse.java exists and declares `public record LlmResponse`"
  - "infochat-llm-adapter/src/main/java/io/infochat/llm/EmbeddingResult.java exists and declares `public record EmbeddingResult`"
  - "infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java exists, contains at least one @Test annotation, and reflectively loads LlmProvider, EmbeddingProvider, ModelTask, LlmResponse, EmbeddingResult; grep -E 'Class.forName' returns at least one match"
  - "mvn -B -pl infochat-llm-adapter test exits 0; surefire reports show at least one test executed (grep -rE 'Tests run: [1-9]' infochat-llm-adapter/target/surefire-reports returns at least one match)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003 @QuarkusTest stubs and any newly-added test in infochat-llm-adapter all pass"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (one plain-JUnit @Test that reflectively loads LlmProvider, EmbeddingProvider, ModelTask, LlmResponse, and EmbeddingResult and asserts each is non-null and of the expected kind — interface for the two SPIs, enum for ModelTask, record for the two response types)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Embedding pipeline
  - docs/spec/architecture.md §Architectural principles
  - docs/design/01-architecture.md §1.2 Module layout (Maven)
decision_refs: []

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-007b: infochat-llm-adapter + LLM SPIs

## Context

Second subticket of the M1-007 umbrella. Lands the `infochat-llm-adapter`
Maven module and the two LLM-side SPIs that v1 needs: `LlmProvider`
(chat completion + structured-output classification, dispatched by a
closed `ModelTask` enum) and `EmbeddingProvider` (batch text → vector).
The two ingest-side SPIs ship in M1-007a; the three messaging SPIs
ship in M1-007c; the cross-module load test ships as the M1-007 commit.

`infochat-llm-adapter` is a plain library jar (no Quarkus extensions
in production scope) so the SPI surface is provider-agnostic. The
LangChain4j-backed implementations layer extensions in their own
tickets — switching providers is a property change, not a re-deploy
(spec §"Why a thin SPI on top of LangChain4j", goal 2).

This is an interfaces-only ticket. No `OllamaLlmProvider`, no
`OpenAiLlmProvider`, no router, no per-profile defaults. Those land
in Tier-3 LLM tickets that depend on M1-007b.

## Definition of Done

- A new Maven module `infochat-llm-adapter/` lives at the repo root,
  declared under `<modules>` in the parent `pom.xml`. Its own
  `pom.xml` is a plain library-jar shape (no Quarkus extensions in
  production scope; test-scope JUnit only). No explicit `<version>`
  on `<dependency>` entries — BOM supplies versions.
- `infochat-llm-adapter` is added as a `<dependency>` in BOTH
  `infochat-collector/pom.xml` (the eval pipeline calls
  `LlmProvider` for security-judge / tagger / entity stages and
  `EmbeddingProvider` for the embedding stage) and
  `infochat-provider/pom.xml` (the chat agent calls `LlmProvider`
  for `CHAT_AGENT` / `SUMMARIZER` tasks).
- Two SPI interfaces exist under `io.infochat.llm`:
  - `LlmProvider` — chat completion + structured-output
    classification per `docs/spec/llm.md` §SPI shape. Method shape
    is minimal: a single `generate(...)` (or similarly named) entry
    point that takes a `ModelTask` discriminator plus the system /
    user prompt strings and returns an `LlmResponse`. Exact
    parameter shape (call-context object, structured-output schema
    parameter, etc.) is an implementer choice within the spec
    constraints; keep it minimal.
  - `EmbeddingProvider` — batch text → vector per
    `docs/spec/llm.md` §Embedding pipeline 'Batch SPI'. Takes a
    `List<String>` and returns a `List<EmbeddingResult>` (or
    similar batch-shaped return). Per-element error mapping is an
    impl concern; the SPI just commits to a batch shape.
- One supporting enum and two supporting records exist under
  `io.infochat.llm`:
  - `ModelTask` — closed enum with EXACTLY the six values from
    `docs/spec/llm.md` §SPI shape: `SECURITY_JUDGE`, `TAGGER`,
    `ENTITY`, `SUMMARIZER`, `CHAT_AGENT`, `TRANSLATOR`. The
    embedder is deliberately NOT a `ModelTask` (the spec
    explicitly excludes it under "Scope of the enum"); future
    readers must not silently add it to the enum.
  - `LlmResponse` — record. Field set: `String text` plus optional
    `TokenUsage` (or whatever shape the implementer picks for
    per-call usage; keep it a single nested type, not a flat
    expansion). Caching, structured-output JSON parsing, etc. are
    impl-side concerns.
  - `EmbeddingResult` — record. Field set: `float[] vector`. The
    record wrapper exists so a future expansion (per-element
    metadata, multi-vector returns, dimensionality reporting) can
    happen without breaking existing call sites; do not add fields
    to it here.
- One smoke test under
  `infochat-llm-adapter/src/test/java/io/infochat/llm/`:
  - Plain-JUnit `@Test` that reflectively loads `LlmProvider`,
    `EmbeddingProvider`, `ModelTask`, `LlmResponse`,
    `EmbeddingResult` and asserts the expected kind for each
    (interface, interface, enum, record, record).
- `mvn -B clean verify` from the repo root exits 0. The two M1-003
  @QuarkusTest stubs continue to pass; the new `infochat-llm-adapter`
  smoke test runs and passes.

## Implementation notes

- **`ModelTask` is the closed enum from spec.** The exact six values
  are spec-mandated: `SECURITY_JUDGE`, `TAGGER`, `ENTITY`,
  `SUMMARIZER`, `CHAT_AGENT`, `TRANSLATOR`. Adding a seventh
  (e.g., a hypothetical `EMBEDDER`) would be a spec deviation —
  the enum's closed shape is part of the router contract
  (`docs/spec/llm.md` §SPI shape: "the router signature
  `(ModelTask, scope_language) → LlmProvider`"). The enum
  comment in code SHOULD reference §SPI shape and the "Scope of
  the enum" carve-out so the next reader does not drift.
- **`TranslationProvider` is NOT in this module.** The spec lists
  it under §SPI shape alongside `LlmProvider` and
  `EmbeddingProvider`, but the v1 module organization places it
  in `infochat-messaging-adapter` (M1-007c) because it is a
  presentation-layer concern (translates BOT prose, not user
  input — `docs/spec/llm.md` §Translation flow), used only by
  Provider, and grouped with `ProgressNotifier` and
  `MessagingAdapter` because all three are presentation-layer.
  Document this in the `LlmProvider` Javadoc (one line: "see
  `infochat-messaging-adapter` for `TranslationProvider`").
- **`LlmResponse` minimality.** Per-call token usage is the only
  field beyond `text` that the spec implies (cache-friendly call
  shapes, observability via call context). Keep usage in a single
  nested record (`TokenUsage` with `int prompt`, `int completion`,
  `int total`) so adding latency / model-id / finish-reason later
  is a one-spot diff, not a cross-call-site one. If the
  implementer prefers to omit `TokenUsage` until an impl needs it,
  that is acceptable — keep `LlmResponse` to `String text` only and
  add the usage shape in the impl ticket. Either choice meets
  acceptance.
- **`EmbeddingResult` is a wrapper.** A bare `float[]` would also
  meet the spec, but the wrapper costs one record file now and
  saves a cross-call-site signature change later when (e.g.)
  multi-vector returns or per-vector metadata become needed. The
  per-batch shape is `List<EmbeddingResult>`; the SPI commits to
  one-result-per-input-element ordering (the impl preserves
  input order; the spec's "one-failure-fails-batch retry" rule is
  the impl's escape hatch, not a per-element nullability).
- **No call-context type yet.** Spec mentions trace-id / scope-id
  carried through every call. Don't add a `CallContext` parameter
  preemptively — the impl-and-router ticket is where it gets
  threaded through; adding it here without callers would force
  every test double to thread a no-op context.
- **Module-path coordinates.** Group `infochat`, artifact
  `infochat-llm-adapter`, version inherited from parent (no
  `<version>` element on the module's own `<groupId>/<artifactId>`
  block). Mirrors `infochat-collector` / `infochat-provider` /
  `infochat-core`.
- **Test framework.** Plain JUnit 5; no Quarkus runtime needed.
  `Class.forName` works on the bare classpath.

## Big-picture notes

- **Subticket isolation.** This ticket touches no ingest-related
  types and no messaging-related types. M1-007a, M1-007b, and
  M1-007c each introduce a disjoint Maven module; their
  `files_scope` lists are disjoint by construction. Sequential or
  fan-out execution order is operator's choice.
- **The router is downstream.** A separate ticket (post-M1-007b,
  once at least one `LlmProvider` impl exists) introduces the
  `(ModelTask, scope_language) → LlmProvider` router in either
  Collector or a shared utility. M1-007b's surface is the SPI
  alone — picking which provider answers which task is router
  policy, not SPI shape.
- **`local-only` posture is a startup wiring concern.** The
  spec's "local-only is the most-restrictive posture" rule
  (`docs/spec/llm.md` §Per-task routing rules) is a Provider
  startup-fail-fast check, NOT a method on `LlmProvider`. It
  belongs to the router/wiring ticket. Don't add a `isLocal()`
  method to the SPI here.
- **Embedding model-identity guard is an impl concern.** Per
  `docs/spec/llm.md` §Embedding pipeline, the active embedding
  model's identifier and dimensionality are stored in a singleton
  metadata row on first use, validated on every startup. That
  guard runs in the embedding-pipeline wiring ticket against the
  database; it is not a method on the `EmbeddingProvider` SPI.
- **Why the wrapper modules are plain library jars.** A
  Quarkus-extension dependency in `infochat-llm-adapter/pom.xml`
  would force every consumer to inherit it, which is the wrong
  default for an SPI module that may be consumed by tests, test
  doubles, and future non-Quarkus tooling. Quarkus extensions
  belong to the consuming module that needs them
  (`infochat-collector`, `infochat-provider`, or the impl-side
  module the impl ticket introduces).

## Out-of-scope expansion

- **The umbrella's whole-topic integration test
  (`infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java`)**
  is reserved for M1-007. The umbrella + subticket idiom exists
  exactly so cross-module verification ships as its own reviewable
  unit. Pre-empting it (e.g., by writing a test in
  `infochat-llm-adapter` that loads classes from `infochat-core`
  or `infochat-messaging-adapter`) would erase the umbrella's
  reason to exist.
- **Concrete provider implementations.** OllamaLlmProvider,
  OpenAiCompatibleLlmProvider, AnthropicLlmProvider,
  OllamaEmbeddingProvider, etc. are each their own Tier-3 ticket.
- **`TranslationProvider`.** In M1-007c.
- **Router / qualifier / per-task wiring.** Separate ticket once
  impls exist.
- **Prompt templates / delimiter wrappers.** `docs/spec/llm.md`
  §Prompt-injection-aware prompt shape — design-tier templates
  live with the impls, not the SPI.
- **LangChain4j extension.** No `quarkus-langchain4j-*` dependency
  in this module. LangChain4j shows up as a transitive when an
  impl ticket adds it.
- **Per-profile model defaults.** `docs/design/05-llm-and-embeddings.md`
  owns those keys; they appear when the impl + router ticket
  lands.
- **Embedding model-identity guard / dimensionality check / singleton
  metadata row.** Runtime impl concerns, not SPI surface.
- **`local-only` startup conflict check.** Provider startup wiring,
  not SPI.

## Authorized test changes

- (none — this ticket adds one new test class in
  `infochat-llm-adapter` and modifies no pre-existing tests.)

## Alternatives considered

- **Put `TranslationProvider` here too.** Tempting because the
  spec lists it under `docs/spec/llm.md` §SPI shape alongside
  `LlmProvider` and `EmbeddingProvider`. Rejected: translation is
  a presentation-layer concern (`docs/spec/llm.md` §Translation
  flow — "purely a presentation-layer concern"). It is consumed
  only by Provider. Grouping it with `MessagingAdapter` and
  `ProgressNotifier` in `infochat-messaging-adapter` keeps the
  Provider-only / Collector-and-Provider split clean: this module
  serves both services, the messaging module serves Provider only.
  Putting `TranslationProvider` here would force Collector to
  pull a dependency it never uses.
- **Replace the `ModelTask` enum with a sealed-class hierarchy
  (one type per task).** Rejected: the spec calls it an enum
  ("`ModelTask` enum") and the closed-enum shape is the simplest
  representation of a closed set. A sealed-class hierarchy would
  be over-engineered for a discriminator that carries no per-case
  state.
- **Have `EmbeddingProvider` return `List<float[]>` directly
  (skip `EmbeddingResult`).** Rejected: the wrapper costs one
  small record now and decouples cross-call-site signatures from
  future expansion (per-vector metadata, multi-vector returns).
  The spec does not mandate the wrapper, but the future-shape
  argument tips the balance.
- **Add an `EMBEDDER` value to `ModelTask`.** Rejected: spec
  explicitly excludes it under "Scope of the enum" — the embedder
  is a distinct SPI with its own provider selection, separate
  property surface, and separate lifecycle (model-identity guard,
  dimensionality invariants). Routing it through the same enum
  would conflate two unrelated lifecycles.
- **Bundle the router into this ticket.** Rejected: the router
  needs at least one impl to be non-trivially testable. Adding
  it here would force a stub impl just to prove the router
  signature, which is dead code on landing.
