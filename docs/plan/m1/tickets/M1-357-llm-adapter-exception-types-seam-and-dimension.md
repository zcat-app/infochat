---
id: M1-357
title: "llm-adapter: SPI-owned task-config exception, one no-providers exception type, symmetric HttpClient seam, non-cloning EmbeddingResult.dimension()"
status: done
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
clarity_check:
  date: 2026-06-14
  verdict: PASS
  warnings: []
  blockers: []
files_budget: 9
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/EmbeddingResult.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/metrics/MeteredEmbeddingProvider.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The config-key VALUES / operator property names — unchanged.
  - The EmbeddingResult value-semantics (vector() still returns a defensive clone) — unchanged; dimension() is an additive non-cloning accessor.
  - The startup-scan invariant "misconfigured route fails boot" — unchanged in behaviour; only the exception TYPE the test pins changes.
acceptance:
  - "A router/SPI-owned exception type (e.g. LlmProvider.TaskConfigUnresolvableException) is introduced; the providers' configFor wraps the underlying config-read failure into it, and the startup-scan tests (OpenAiCompatibleProviderTest, LlmRouterTest) assert that type instead of the SmallRye-Config NoSuchElementException, so no test reaches through the public API into a third-party exception class."
  - "Both 'no providers registered' sites in LlmRouter (the test-seam ctor at entries.isEmpty() and the CDI factory at out.isEmpty()) throw the same exception type with one message; the test-seam ctor no longer throws IllegalArgumentException where the rest of the router uses IllegalStateException for config-shape misconfiguration."
  - "OpenAiCompatibleProvider gains the same package-private HttpClient-accepting test-seam constructor AnthropicProvider already exposes, so the two chat providers have a symmetric construction surface; production wiring is unchanged."
  - "EmbeddingResult gains a non-cloning dimension() accessor and MeteredEmbeddingProvider reads results.get(0).dimension() instead of .vector().length, removing the per-batch full-vector clone on the ingest hot path."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl (exception-type + provider-seam assertions)
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing (no-providers type assertion)
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics (dimension accessor assertion)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 117
      removed: 31
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-357: llm-adapter API/seam consistency + dimension accessor

## Context

Four low/medium deep-review v6 findings on `infochat-llm-adapter`, grouped
because they are the same "make the SPI surface consistent and allocation-clean"
theme in one module:

- **opus-47 F2** (medium) — startup-scan test asserts `NoSuchElementException`,
  leaking the SmallRye-Config impl type through `LlmRouter.assertAllTasksResolve`.
  **Verified 2026-06-14:** `LlmRouterTest.java:354` /
  `OpenAiCompatibleProviderTest.java:140` per the report; the type is never named
  in the SPI/router javadoc.
- **opus-47 F3** (low) — two exception types for "no providers registered".
  **Verified 2026-06-14:** `LlmRouter.java:108-109` throws
  `IllegalArgumentException`; `LlmRouter.java:321-322` throws
  `IllegalStateException`; other misconfig sites (150-153, 248-251) use
  `IllegalStateException`.
- **opus-47 F4** (low) — asymmetric `HttpClient` test seam: `AnthropicProvider`
  has a package-private `HttpClient`-accepting ctor, `OpenAiCompatibleProvider`
  does not.
- **opus-48 F2** (low, PERFORMANCE) — `MeteredEmbeddingProvider` clones the full
  first vector (768–1536 floats) per batch just to read `.length`.
  **Verified 2026-06-14:** `EmbeddingResult.vector()` returns a clone; the
  decorator reads `results.get(0).vector().length` on the ingest hot path.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- `dimension()` is a natural accessor independent of this fix and consistent with
  the record's documented "future expansion lands as additional components".
