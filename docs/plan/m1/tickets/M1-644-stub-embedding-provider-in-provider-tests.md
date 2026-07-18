---
id: M1-644
title: "Stub the provider-module EmbeddingProvider so the suite stops calling a real ollama"
status: pending
created: 2026-07-18
last_updated: 2026-07-18
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/testing/StubEmbeddingProvider.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/testing/TestDoubleWiringIT.java
  - infochat-provider/src/main/resources/application.properties
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Promoting the collector's nested EmbeddingWorkerIT.StubEmbeddingProvider to a
    top-level class. The collector is ALREADY hermetic — its nested stub is
    @Alternative @Priority(Integer.MAX_VALUE) @ApplicationScoped, so ArC enables
    it module-wide and no collector test contacts a real endpoint. Promoting it
    would refactor working code and drag in the four sibling tests that downcast
    to EmbeddingWorkerIT.StubEmbeddingProvider. Fragility of that placement is
    real but is a separate follow-up, not this fix.
  - >-
    The three provider ITs that construct SemanticSearchTool by hand with a local
    stub embedder (SemanticSearchToolIT, SemanticSearchToolHybridIT,
    RetrievalWorldPredicateIT). They never resolve EmbeddingProvider from CDI, so
    the new bean is invisible to them. Leave them untouched.
  - >-
    Changing LlmCircuitBreakerRegistry's endpoint keying, or any production
    breaker behavior. That chat and embeddings share one breaker whenever they
    share a base-url is a PRODUCTION concern (an embedding-side outage can
    short-circuit healthy chat) and needs its own investigation ticket. This
    ticket only stops the test suite from tripping it.
  - >-
    The missing registeredContactSet.markRegistered(...) in
    InboundRouterConcurrentDispatchIT (its three siblings have it). Real latent
    order/load-sensitive flake, independent cause, separate ticket.
  - >-
    MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal, which failed once
    on a 2000 ms FakeSignalCli await during Phase-1 diagnosis under heavy machine
    load and did not fail in four earlier red runs. Unrelated intermittent.
  - >-
    Reclassifying the M1-589 unconditional semanticSearch pre-fetch. That it runs
    on every chat turn is the amplifier here, not the defect; it stays as-is.
acceptance:
  - >-
    A new test-scope bean StubEmbeddingProvider in
    app.zcat.infochat.provider.testing, annotated @Alternative
    @Priority(Integer.MAX_VALUE) @ApplicationScoped, implements EmbeddingProvider
    and is therefore selected over OpenAiCompatibleProvider's embedding twin for
    the whole provider test classpath — mirroring how TestLlmProvider already
    shadows the chat provider in the same package.
  - >-
    Its embed(List<String>) returns one EmbeddingResult per input text with a
    fixed 768-float vector (V11__post_embedding.sql:65 declares vector(768) for
    the default profile), and requires NO per-test queue setup. This LENIENT
    default is load-bearing and deliberately differs from the collector's
    strict queue-driven StubEmbeddingProvider, which throws when its queue is
    empty: the M1-589 pre-fetch calls semanticSearch on EVERY provider chat
    turn without queueing anything, so a strict port would throw on every turn.
  - >-
    %test.infochat.embeddings.base-url is set to the repo's existing unreachable
    sentinel http://localhost:9 (the value OpenAiCompatibleEmbeddingProviderTest
    and AnthropicProviderTest already use). The bean is the fix; this line is the
    tripwire, so any FUTURE unstubbed embedding path fails loudly with connection
    refused instead of silently succeeding against whatever daemon happens to
    hold port 11434.
  - >-
    A new TestDoubleWiringIT asserts the CDI-resolved EmbeddingProvider is a
    StubEmbeddingProvider, and that llmRouter.forTask(ModelTask.CHAT_AGENT, null)
    returns the TestLlmProvider. The second assertion guards a live landmine:
    LlmRouter reaches the stub only by MISSING the name "openai-compatible" and
    falling through to the silent priority-3 entries.get(0) branch. If the real
    provider ever re-entered the bean set, the name lookup would hit it exactly
    AND the case-insensitive sort puts "openai-compatible" before
    "TestLlmProvider" — both mechanisms fail toward real HTTP with no log line.
  - >-
    The 15 router-concurrency failures (InboundRouterConcurrentDispatchIT 4,
    InboundRouterPerUserCapIT 4, InboundRouterQueuedFeedbackIT 4,
    QueuedTurnCancellationIT 3) are GREEN with NO process listening on port
    11434. This is the acceptance evidence: the suite must no longer depend on an
    external endpoint being up.
  - >-
    Zero EmbeddingProviderUnreachableException and zero "circuit breaker OPEN for
    http://localhost:11434/v1" lines appear in the verify log — the direct
    counter-signature of the root cause (green gate logs had 0 of each; red runs
    had 29-30 and 2).
  - mvn verify from the repo root is green with nothing listening on 11434
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/testing/StubEmbeddingProvider.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/testing/TestDoubleWiringIT.java
  preserves:
    - all tests currently green on main
    - >-
      the three direct-construction embedder ITs, which must keep passing
      untouched because they bypass CDI
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
---

# M1-644: Stub the provider-module EmbeddingProvider so the suite stops calling a real ollama

## Context

Fifteen router-concurrency ITs went red on clean `main` on 2026-07-18 with no
functional code change (the only commit in the green→red window, `d376100c`,
edits one word inside a javadoc block). Phase-1 diagnosis is recorded in
`.scratch/HANDOFF-2026-07-18-router-it-baseline.md` §Findings.

Root cause, verified end to end:

1. `%test` sets `infochat.llm.default.base-url=http://localhost:11434/v1`
   (`application.properties:357`) and `infochat.embeddings.base-url` is the same
   string unprofiled (`:422`) — byte-identical.
2. `LlmCircuitBreakerRegistry` keys breakers by endpoint URL string.
   `endpointForTask(CHAT_AGENT)` falls back to `default.base-url` because
   `chat.base-url` is unset, and `embeddingsEndpoint()` reads
   `embeddings.base-url`. Same key ⇒ **chat and embeddings share one breaker.**
3. Chat IS stubbed (`TestLlmProvider` is the sole `LlmProvider` bean). Embeddings
   are NOT — `OpenAiCompatibleEmbeddingProvider` makes real HTTP calls, and
   M1-589 made the `semanticSearch` pre-fetch unconditional on every chat turn.
4. With nothing on 11434 the embedding calls fail;
   `CircuitBreakingEmbeddingProvider:56` records them; after 3 consecutive
   transport failures the shared breaker trips OPEN.
5. `CircuitBreakingLlmProvider` is a `@Decorator` on `@Any LlmProvider`, so it
   wraps the stub too. An OPEN breaker throws **without invoking the delegate**,
   so `TestLlmProvider.generate()` is never entered — and that is where the
   tests' latch lives. Hence `latched=0 of 4, llmCalls=0` and a 15 s timeout on
   every precondition await.

Only the 2 of 17 tests that do not require a worker latched inside `generate()`
survive.

## Why this was green until now

These four ITs never passed standalone. `docker inspect` shows something served
port 11434 continuously from 2026-07-15 06:41 until 2026-07-17 23:14 (prod
`infochat-ollama-1` until 19:33, then `infochat-test-ollama-1`). All eight green
gate runs fall at or before 18:49; all red runs fall after 23:14. Green gate logs
contain **zero** `EmbeddingProviderUnreachableException` and zero breaker trips;
red runs contain 29-30 and 2. The operator stopping prod postgres+ollama to stand
up the test instance is what exposed it — not a clock rollover, and not any
commit.

The suite was reaching into a running production ollama whenever one happened to
be up. Blast radius was empty (the DB was always an ephemeral Testcontainers
instance — zero references to port 5432 in any run, and `/embeddings` is
stateless inference), but the green results were environmental accidents.

## Escape vector

Neither the injectable-Clock rule nor `ScanWindowFixtureGuardTest` could have
caught this: there is no date logic in the path, and the dispatch path is clean
of absolute-calendar comparisons.

The rule that DOES cover it is spec-level and predates the bug:
`docs/spec/verification.md` §Test layers defines layer 3 as "Integration tests. A
running Collector and Provider against an in-memory messaging adapter and **a
fake LLM**." Every provider chat IT ran against a REAL LLM backend on port 11434
for its embedding leg. So this is a spec violation, not merely a missing
convention — the requirement existed and was simply never enforced by anything
executable.

That is the actual escape vector: a spec commitment with no machine check behind
it, invisible to a reviewer whose rule list is the engineering-rules file rather
than the spec's test-layer definition, and actively masked by a green suite.
`LlmCircuitBreakerRegistry`'s own javadoc anticipates a "stub-provider test
topology" bypass when no base-url is set, but `%test` DOES set one, so that
bypass never engages.

The collector already solved this problem correctly; the provider module did not
follow the in-repo precedent.

## Notes for the implementer

- Model the new bean on `TestLlmProvider` for placement and annotations, but on
  nothing for behavior: the collector's queue-driven double is the WRONG shape
  here (see acceptance item 2).
- Adding a CDI `EmbeddingProvider` alternative changes what provider chat ITs see
  from "pre-fetch throws, swallowed as `answering without retrieval`" to
  "pre-fetch returns a canned vector". Assertions that depend on retrieval
  results may shift; the full-suite green is the check.
- Verify with nothing listening on 11434. `ss -ltn | grep 11434` should be empty
  before the run.
