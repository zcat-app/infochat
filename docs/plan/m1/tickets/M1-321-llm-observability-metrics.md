---
id: M1-321
title: "LLM observability: call context and Micrometer metrics"
status: done
created: 2026-06-12
last_updated: 2026-06-12
blocked_by: []
files_budget: 12
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "/status last-15-min LLM aggregate reporting (design 05 §5.9 last line) — a thin follow-up once the metrics exist; file it when this ticket lands."
  - AdapterMetrics and any infochat-messaging-adapter metrics (M1-322).
  - Tracing-backend integration (OpenTelemetry export, span propagation) — the call context is an in-process correlation surface (ids in logs and metric labels), not a distributed-tracing rollout.
  - Dashboards, exporter endpoints, or Prometheus configuration beyond what the Micrometer extension provides by default.
acceptance:
  - "A per-call context object carrying trace id, scope id, task, and language (docs/spec/llm.md §SPI shape 'Call context' bullet) is constructed for every LlmProvider and EmbeddingProvider call and is observable at the provider boundary; a named test asserts the same trace id stitches an LlmProvider call and an EmbeddingProvider call issued under one context."
  - "LlmMetrics emits the design 05 §5.9 catalogue via Micrometer: llm.calls.total{task, provider, model, outcome}, llm.tokens.in{task, provider, model}, llm.tokens.out{task, provider, model}, llm.latency.ms{task, provider, model}, llm.concurrency.inflight{task, provider}, llm.queue.wait.ms{task, provider}, embedding.calls.total{provider, model, outcome}, embedding.dimension{provider, model}, with outcome ∈ {ok, retry, fallback, fail}; named tests assert counter/outcome increments for an ok call and a fail call against a stub provider."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmObservabilityTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Bounded concurrency and observability
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 14
      added: 708
      removed: 25
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-12
  verdict: WARN
  warnings:
    - "test_plan.adds is [] while acceptance items 1 and 2 each require new named test files. The implementer should populate test_plan.adds at start time with the actual test file paths; leaving it empty makes the test plan structurally inconsistent with the acceptance criteria."
  blockers: []
---

# M1-321: LLM observability: call context and Micrometer metrics

## Context

Filed by M1-305 (deep-review v5 finding U-69, user decision 2026-06-12:
schedule, not defer). docs/spec/llm.md commits to a per-call context
(trace id, scope id, task, language) carried through every
`LlmProvider`/`EmbeddingProvider` call, and to per-task latency and
token-count metrics (§Bounded concurrency and observability). Design
05 §5.9 pins the exact Micrometer catalogue. None of it exists: as of
2026-06-12 no `LlmCallContext`/`LlmMetrics` class and no Micrometer
dependency appear anywhere in the build.

## Acceptance

See frontmatter. In prose: (1) a call context with trace/scope/task/
language reaches the provider boundary on every LLM and embedding
call, with a test stitching one trace id across both SPI surfaces;
(2) `LlmMetrics` emits the eight-metric §5.9 catalogue with the
documented label sets and outcomes, tested against a stub provider;
(3) the full suite stays green.

## Out-of-scope

See frontmatter. The `/status` aggregate view stays out so this
ticket's surface is exactly "emit the committed telemetry" — the
read-side command surface (localization bundles, formatting) is a
separately sized follow-up. Adapter metrics are M1-322's; this ticket
ends at the LLM/embedding SPI.

## Notes

- **⚠ Dependency approval at start.** This ticket introduces
  Micrometer (the `quarkus-micrometer` extension) into the build —
  the first metrics dependency in the project. Per the recorded
  dependency-approval flow, propose the dependency (artifact, scope,
  which poms) and obtain explicit user approval before adding it; do
  not start the implementation without that approval on record.
- The SPI lives in `infochat-llm-adapter`
  (`LlmProvider`, `LlmRouter`, provider impls); consumers in
  `infochat-collector` (eval workers) and `infochat-provider`
  (chat agent, summary, translation). A decorator/wrapper around the
  router-resolved provider keeps metric emission out of each provider
  impl — prefer that over widening every impl, but the implementer
  decides.
- Existing javadoc in the provider's `AdapterReadinessCheck` health
  check already names the "Micrometer per-adapter metrics lift" as
  future work — that surface is M1-322, not this ticket.
- Design catalogue: `docs/design/05-llm-and-embeddings.md` §5.9
  (annotated as scheduled-by-this-ticket by M1-305).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-321-*.md
```
