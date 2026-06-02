---
id: M1-141
title: "LLM adapter robustness (body cap, Retry-After) + router decoupling"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 11
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Anthropic header names / extractErrorMessage (covered by M1-124)
  - EmbeddingResult value semantics (covered by M1-140)
  - the local-only guard (covered by M1-136)
acceptance:
  - "OpenAiCompatibleProvider, AnthropicProvider, and OpenAiCompatibleEmbeddingProvider bound the response body (custom BodySubscriber or Content-Length guard, configurable 1–8 MiB) instead of unbounded BodyHandlers.ofString()"
  - "429/503 responses parse Retry-After and carry retryAfterMs so the caller sleeps before its single retry instead of immediately re-hitting the limit"
  - "LlmRouter.providerName no longer couples to concrete impls via an instanceof chain (default method on the SPI or an annotation read at startup); the task-key segment lives on the ModelTask enum rather than triplicated; the MicroProfileConfigReader 'null'-string sentinel is documented or removed"
  - "A startup-guard scan asserts every routed ModelTask has a serving provider (so a misrouted TAGGER fails at startup, not at the call site)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/llm.md §SPI shape
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-02
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 478
      removed: 85
  - round: 2
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 11
      added: 478
      removed: 85
escalations:
  - date: 2026-06-02
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation budget analysis. Acceptance items 1-4 require
      8 production files at minimum (LlmProvider SPI default method, ModelTask
      segment, the 3 providers, LlmRouter, LlmRouterStartupGuard, plus a new
      shared bounded-body/Retry-After util), leaving zero of the 8-file budget
      for the mandatory new tests (body-cap overflow, Retry-After parse,
      routed-task startup scan). Realistic total 10-11 files. Clarity
      pre-flight WARNed the same.
revisions:
  - date: 2026-06-02
    reason: "budget-breach rework — files_budget 8 was infeasible. The 4 functional acceptance items require 8 production files at the irreducible minimum (LlmProvider SPI default providerName(), ModelTask segment, the 3 providers, LlmRouter, LlmRouterStartupGuard, plus one new shared bounded-body/Retry-After util), leaving zero of the 8-file budget for the mandatory new tests (body-cap overflow, Retry-After parse, routed-task startup scan in AnthropicProviderTest / OpenAiCompatibleEmbeddingProviderTest / LlmRouterTest). Raise files_budget 8→11 to the honest ceiling. Scope, files_scope, acceptance semantics, complexity, and risk are unchanged — only the count was under-estimated. Clarity pre-flight WARNed the same."
    prior_values: |
      files_budget: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: WARN
  warnings:
    - "FILES-BUDGET-PLAUSIBLE: budget of 8 is tight given ~7-8 production files implied by the acceptance criteria plus expected test files; hitting 9-11 files is likely and may need an overage note. Not a blocker if aggressive consolidation is feasible."
  blockers: []
---

# M1-141: LLM adapter robustness + router decoupling

## Context

Module-scoped `infochat-llm-adapter` robustness + cleanup bundle:

- **B-LLM-OOM** — providers call `BodyHandlers.ofString()` unbounded; a multi-GB
  response OOMs the JVM (these do not go through the SSRF `readBounded`).
- **B-LLM-RETRY** — all non-2xx throw identically; the caller retries once
  immediately, re-hitting a 429/503.
- **C-LLMROUTER-INSTANCEOF / C-TASKKEY-DUP / C-MICROPROFILE-NULL / configFor** —
  `LlmRouter.providerName` instanceof cascade, triplicated task-key segment, an
  undocumented `"null"`-string sentinel, and a provider that throws for every
  task but one with no startup coverage.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. The Anthropic header fix and EmbeddingResult work are separate
LLM-lane tickets touching different files.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §B-LLM-OOM, §B-LLM-RETRY,
  §C-LLMROUTER-INSTANCEOF, §C-TASKKEY-DUP, §C-MICROPROFILE-NULL; `opus-47-full-handout.md`
  §F-PERF-09/19, F-MAINT-43/46, F-SIM-07; `opus-47-only-handout.md` §M15/M17, obs.1/2.
- LLM endpoint is operator-configured (semi-trusted), so the body cap is hygiene,
  not an SSRF-grade target.

## Round 1 rework

Reviewer verdict round 1: REWORK (2 items). PARAMETER-CONTRACT-CHECK FAIL on two
new public constructors. Address only these:

1. Annotate the `String message` parameter on the new constructor
   `LlmCallFailedException(String message, long retryAfterMs)` in
   `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java`
   with `@NonNull` from `org.jspecify.annotations` (message is never null at any
   call site), per engineering-rules-verbatim.md §7a / PARAMETER-CONTRACT-CHECK.
2. Annotate the `String message` parameter on the new constructor
   `EmbeddingCallFailedException(String message, long retryAfterMs)` in
   `infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleEmbeddingProvider.java`
   with `@NonNull` from `org.jspecify.annotations`, per
   engineering-rules-verbatim.md §7a / PARAMETER-CONTRACT-CHECK.
