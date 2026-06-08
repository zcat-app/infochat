---
id: M1-237
title: "infochat-llm-adapter: dedup HTTP providers, drop dead null-check, fix comment"
status: pending
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 7
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The SPI surface (LlmProvider, EmbeddingProvider, ModelTask, LlmResponse, EmbeddingResult) — reviewed as well-shaped; unchanged.
  - The prompt-injection wrapping templates and the determinism boundary — unchanged.
  - OpenAiCompatibleEmbeddingProvider's @ConfigProperty(defaultValue="8388608") — it legitimately mirrors the literal because annotation args must be compile-time constants; keep it.
  - The provider-specific wire-body assembly and response-text extraction (Anthropic system[]/cache_control/max_tokens vs OpenAI messages[]) — these stay per-provider; only the shared plumbing is hoisted.
acceptance:
  - "L-F1: the `if (task == null) throw` guard is removed from LlmRouter.forTask (task is a non-@Nullable parameter; the null-marked contract / NullAway is the enforcement per §7/§7a). The @Nullable scopeLanguage handling is unchanged."
  - "L-F2: the duplicated HTTP call pipeline shared by AnthropicProvider and OpenAiCompatibleProvider (configFor read, body-cap read + [1 MiB, 8 MiB] clamp, send, non-2xx warn+throw, I/O-vs-interrupt catch arms) is hoisted into LlmHttpSupport (or a package-private base) parameterized by the two provider-specific steps (request-body JSON, response-text extraction); each provider keeps only configFor + body assembly + response parser."
  - "L-F3: the DEFAULT_BODY_CAP_BYTES javadoc no longer claims the literal 8388608 is mirrored in EACH provider's @ConfigProperty; it states the two HTTP providers reference the constant directly via getOptionalValue(...).orElse(...) and only the embedding provider mirrors the literal."
  - "A named test asserts both HTTP providers share one cap-read/clamp + non-2xx-throw path (the response-cap and failure-surface contract cannot diverge), exercising a non-2xx and an over-cap response."
  - "Existing AnthropicProvider / OpenAiCompatibleProvider / LlmRouter tests stay green; mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/HttpProviderSharedPipelineTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Why a thin SPI on top of LangChain4j
decision_refs:
  - D48
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-237: infochat-llm-adapter — dedup HTTP providers, drop dead null-check, fix comment

## Context

Three findings in `infochat-llm-adapter`, grouped (same module):

- `deep-code-review/v2.5/opus-48/04-module-infochat-llm-adapter.md#F1`
  (DRIFT): `LlmRouter.forTask` null-checks its non-`@Nullable` `task`
  parameter — dead defensive code under §7/§7a (`scopeLanguage` is
  correctly `@Nullable`).
- `#F2` (SIMPLIFICATION): `AnthropicProvider` and `OpenAiCompatibleProvider`
  duplicate the configFor read, the body-cap read + clamp, the send,
  status-check, and I/O/interrupt catch arms nearly verbatim. The cap-read
  and failure-surface are a robustness contract that must stay identical
  across providers, yet nothing enforces that; a change has to be applied
  twice and can drift.
- `#F3` (DRIFT): `LlmHttpSupport.DEFAULT_BODY_CAP_BYTES` javadoc claims the
  literal `8388608` is mirrored "in each provider's `@ConfigProperty`" —
  true only of the embedding provider; the two HTTP providers reference the
  constant directly.

## Acceptance

See frontmatter. In prose: remove the `forTask` null-check; hoist the
shared HTTP call pipeline into `LlmHttpSupport` parameterized by the two
provider-specific steps; correct the cap-bytes javadoc; a test pins the now
single cap/clamp/non-2xx path; existing tests stay green; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The SPI surface, prompt-injection templates, determinism
boundary, the embedding provider's annotation default, and the
provider-specific wire shapes are unchanged.

## Notes

- The dedup (L-F2) is the substantive item; the report notes it is "low"
  because there are only two HTTP providers today and future providers may
  go a LangChain4j route — but it folds the contract-drift risk and the two
  trivia (L-F1, L-F3) into one coherent same-module change. If the
  reviewer/team judges the dedup not worth it, L-F1 and L-F3 still stand as
  the minimum; record that decision in the commit `Alternatives considered:`
  trailer rather than silently dropping scope.
- Recommended shared-method shape is in the source finding.
