---
id: M1-251
title: "LLM-adapter lows: finish embedding pipeline dedup + extract StubConfig"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 9
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/LlmHttpSupport.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - LlmRouterStartupGuard and the routing package — owned by M1-243.
  - The two chat providers (OpenAiCompatibleProvider, AnthropicProvider) — already migrated onto LlmHttpSupport by M1-237 (2bcd9af); not re-touched here.
  - The over-broad-catch finding (report T18) — DROPPED after verification: the JSON-assembly try wraps JSON.writeValueAsString(...), which throws checked JsonProcessingException, so the catch is required, not dead. Do not remove it.
  - The embedding provider's clamp bounds / request shape — unchanged; this is a pipeline-reuse refactor, not a behavior change.
acceptance:
  - "T19: OpenAiCompatibleEmbeddingProvider delegates its HTTP send / non-2xx handling / body-cap clamp to the shared LlmHttpSupport pipeline (the one M1-237 introduced and migrated the chat providers onto), removing its duplicated copy of that flow; a named test in the llm impl test package asserts a non-2xx embedding response is handled identically to the shared path (same failure type / message shape) and a 2xx embedding response still parses as before. Behavior preserved."
  - "T20: the StubConfig test double — currently re-declared as a private static inner class in at least AnthropicProviderTest, OpenAiCompatibleProviderTest, and HttpProviderSharedPipelineTest (and used in LlmRouterTest, AnthropicProviderMultiBlockContentTest) — is extracted to a single top-level package-private test class and the inner duplicates are removed; the test suite stays green (no inner-class re-duplication, per the avoid-test-inner-classes rule)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 225
      removed: 383
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-251: LLM-adapter lows

## Context

Two low-severity `infochat-llm-adapter` findings, re-grounded against current
main (post-M1-237). Source: `deep-code-review/v3/` UNIFIED-REPORT.md T19 (opus
`04#F3`), T20 (mimo `04#F2`).

- **T19.** M1-237 (`2bcd9af`) introduced a shared `LlmHttpSupport` send/non-2xx/
  clamp pipeline and migrated the two **chat** providers onto it, but left
  `OpenAiCompatibleEmbeddingProvider` with its own duplicated copy of that flow.
  This ticket finishes the dedup by migrating the embedding provider too.
- **T20.** The `StubConfig` test double is copy-pasted across ~5 llm test files
  (M1-237 added a fresh copy in `HttpProviderSharedPipelineTest`); extract one
  top-level package-private class.

**Dropped during verification — report T18 ("over-broad catch (RuntimeException)
around non-throwing JSON assembly, ×3 providers"):** falsified. The wrapped block
calls `JSON.writeValueAsString(root)`, which declares `throws
JsonProcessingException` (checked) — so the catch is *required*, not dead code.
Only the `RuntimeException |` arm of the union catch is arguably broad, a
marginal style nit not worth a tracked change. Excluded from scope.

## Acceptance

See frontmatter. In prose: migrate the embedding provider onto the shared
`LlmHttpSupport` pipeline; extract the duplicated `StubConfig`. A named test pins
the embedding non-2xx path now shares the chat path; existing tests stay green;
`mvn verify` is 0.

## Out-of-scope

See frontmatter. The routing package (M1-243), the already-migrated chat
providers, the dropped T18 catch, and the embedding clamp/request shape are
untouched.

## Notes

- T19: confirm the embedding provider's clamp/parse semantics match what
  `LlmHttpSupport` offers before reusing — embeddings differ from chat in request
  body and response parsing, so the shared piece is the send/non-2xx/clamp, not
  the JSON parse. If `LlmHttpSupport` needs a small new shared method for the
  embedding shape, that is in scope; widening it to cover embedding parsing is
  not.
- T20: extract to a top-level package-private class, NOT another inner class
  (avoid-test-inner-classes rule). Confirm the copies are equivalent before
  unifying; if they diverge, surface the divergence rather than silently merging.
</content>
</invoke>
