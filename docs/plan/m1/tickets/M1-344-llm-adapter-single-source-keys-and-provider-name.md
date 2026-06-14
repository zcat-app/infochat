---
id: M1-344
title: "llm-adapter: single-source per-task config keys and the embedding provider name"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 9
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/MeteredEmbeddingProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The config-key VALUES and the operator-facing property names — unchanged; this consolidates how the keys are SPELLED in code (same strings), it does not rename any property.
  - The proxy-suffix unwrap heuristic itself — kept; this ticket gives the embedding side a stable operator-visible providerName() and removes the duplicate walk, it does not change the unwrap algorithm.
  - LlmProvider.providerName()'s existing default — unchanged.
acceptance:
  - "The per-task config-key shape infochat.llm.<keySegment>.<suffix> is owned by ModelTask, not hand-spelled in five places across four classes. ModelTask gains accessors (e.g. configPrefix(), baseUrlKey(), providerKey(), and a static languagesKey(providerName)); LlmRouter.perTaskOverrideKey, LlmRouterStartupGuard's *KeyFor helpers, both providers' configFor prefixes, and supportedLanguagesFor all read through them. The operator-facing literals are unchanged, and LlmRouterStartupGuardKeyDerivationTest still pins each derivation by literal."
  - "The embedding-metric provider tag is the stable operator-visible identifier, matching the LLM side. EmbeddingProvider gains a providerName() default (symmetric to LlmProvider.providerName()); OpenAiCompatibleEmbeddingProvider pins a stable name (e.g. \"openai-compatible-embedding\"); MeteredEmbeddingProvider reads delegate.providerName() instead of the bare class simple-name. This removes the duplicated proxy-suffix class-name walk in MeteredEmbeddingProvider (now provided by the SPI default) and makes the embedding metric's provider label renaming-safe and joinable with the LLM-side metrics."
  - "Tests are updated: the pinned provider=\"CapturingEmbeddingProvider\" / class-name assertions in LlmObservabilityTest follow the new stable name; key-derivation tests stay green against the unchanged literals."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm (provider-name + key-derivation assertions)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations:
  - date: 2026-06-14
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      FILES-BUDGET-PLAUSIBLE: FAIL — files_scope is missing
      OpenAiCompatibleEmbeddingProvider
      (infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java).
      Acceptance item 2 requires it to pin a stable name, but it is not in
      files_scope; files_budget 8 would need adjustment to admit it.
revisions:
  - date: 2026-06-14
    reason: clarity-fail rework — acceptance item 2 names OpenAiCompatibleEmbeddingProvider (it pins the stable embedding provider name) but that file was absent from files_scope; add it and bump files_budget 8→9. No acceptance/out_of_scope text changes; the gap was scope-list-only.
    prior_values: |
      status: escalated
      files_budget: 8
      files_scope:
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingProvider.java
        - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/MeteredEmbeddingProvider.java
        - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-344: llm-adapter — single-source config keys + embedding provider name

## Context

Three low-severity deep-review v5.5 findings on `infochat-llm-adapter`, grouped
because they are the same "single-source the shape, not just the leaf" theme in
one module:

- **opus-47 `04-module-infochat-llm-adapter.md` F2** — `ModelTask.keySegment()`
  single-sources the segment, but the surrounding `"infochat.llm." + … +
  ".<suffix>"` shape is hand-spelled in five places across four classes.
  **Verified at source 2026-06-14:** `"infochat.llm." +` concatenations at
  LlmRouterStartupGuard.java:287,297,307; AnthropicProvider.java:121;
  LlmRouter.java:295,347; OpenAiCompatibleProvider.java:155.

- **opus-47 `04-module-infochat-llm-adapter.md` F3** + **opus-48
  `04-module-infochat-llm-adapter.md` F2** — `MeteredEmbeddingProvider.providerLabel`
  returns the bare class simple-name via a proxy-suffix walk that duplicates
  `LlmProvider.providerName()`'s default, while the LLM side uses the stable
  operator-visible name. **Verified at source 2026-06-14:** the walk at
  MeteredEmbeddingProvider.java:85-92 with the javadoc admitting it is "the same
  walk as LlmProvider.providerName()'s default"; `EmbeddingProvider` has no
  `providerName()`. Dashboards filtering `provider="anthropic"` miss the embedding
  side, and the label is tied to the class file name rather than a documented id.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Promoting the whole key shape onto `ModelTask` makes a future namespace move a
  one-line edit; the embedding `providerName()` default mirrors the LLM SPI so
  adding a new embedding impl follows the same pattern as a new LLM provider.
