---
name: green-suite-can-be-environmental-accident
description: "A passing test suite can be passing for an environmental reason (a real service happening to occupy a port), not because the code is right — the tell is a stub whose call count is zero."
metadata:
  type: project
---

On 2026-07-18 fifteen provider ITs went red at once. The obvious hypothesis —
a date/time-bomb in fixtures — was WRONG. Root cause: `%test` pointed
`infochat.llm.default.base-url` and `infochat.embeddings.base-url` at the same
string (`http://localhost:11434/v1`), and `LlmCircuitBreakerRegistry` keys
breakers by endpoint URL, so chat and embeddings **shared one breaker**. Chat
was stubbed; embeddings were not, and the per-turn `semanticSearch` pre-fetch
runs on every chat turn. With nothing listening on that port the embedding
calls failed, tripping the shared breaker OPEN; because
`CircuitBreakingLlmProvider` is a `@Decorator` on `@Any LlmProvider` it wraps
the stub too and throws WITHOUT entering `TestLlmProvider.generate()`, where
the ITs' latch lives. The suite had only ever been green because a real Ollama
happened to hold that port; stopping it exposed the gap.

**Why:** an unstubbed SPI in a test profile is invisible while something real
answers on the configured address. The suite is then measuring the
environment, not the code — and `docs/spec/verification.md` §Test layers
already required layer-3 ITs to run against a fake LLM, a spec commitment with
no machine check behind it (invisible to a reviewer whose rule list is the
engineering-rules file rather than the spec).

**How to apply:**
- The diagnostic tell is **`llmCalls=0` with a stub installed** — that means
  the decorator chain short-circuited, not that routing missed the stub. Check
  breaker/decorator state before suspecting a clock or a fixture date.
- Two SPIs sharing a base-url string share anything keyed by that string.
  Give test doubles distinct, deliberately-dead addresses so a real local
  service can never silently satisfy them.
- The in-repo fix shape is reusable: a lenient `StubEmbeddingProvider`
  (`@Alternative @Priority(MAX)`), a `TestDoubleWiringIT` asserting both SPIs
  resolve to doubles, and a `%test` base-url sentinel pointed at a dead port as
  a loud tripwire. Canned vectors must be the unit vector, NOT zeroes —
  pgvector's cosine `<=>` is undefined for a zero vector and yields NaN.

Related: [[scan-window-fixture-timebombs]], [[full-suite-timing-flakes]].
