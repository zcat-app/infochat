---
id: M1-192
title: "LLM per-task config completion (configFor, remote-llm, guard)"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 11
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/application.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the router's unknown-default fallback posture (entries.get(0) + one-shot WARN) — deliberate documented M1-042 constraint per the audit (L3 PARTIAL); revisiting it is a decision ticket, not this one
  - joinPath/preview triplication and LlmHttpSupport consolidation (audit L7) — simplification tier, UNIFIED.md T33
  - AnthropicProvider.parseContentText content[0] handling (audit L8, low/theoretical)
  - worker-side fallback behavior (TaggerWorker etc. catching RuntimeException) — correct once the provider stops throwing; do not touch the workers
acceptance:
  - "OpenAiCompatibleProvider serves all six ModelTasks: a named test exercises generate() for TAGGER, ENTITY, SUMMARIZER, CHAT_AGENT, and TRANSLATOR against a fake endpoint and asserts no UnsupportedOperationException (today configFor throws UOE for 5/6 tasks — 'M1-033 wires SECURITY_JUDGE only' — while live call sites exist for all six and the workers' RuntimeException catch turns every call into a silent permanent fallback)"
  - "The per-task config keys already shipped in collector application.properties (infochat.llm.tagger.*, infochat.llm.entity.*) actually drive the calls: a named test points a task's base-url at a fake endpoint via config and asserts the call lands there"
  - "%remote-llm declares model keys for chat and summarizer: a named test resolves AnthropicProvider's per-task config for CHAT_AGENT and SUMMARIZER under remote-llm-shaped properties and asserts it succeeds (today no llm.chat.model / llm.summarizer.model key exists anywhere while AnthropicProvider requires prefix+'model' via getValue — first remote-llm chat or summary call dies on NoSuchElementException)"
  - "No dead Retry-After machinery remains: either a consumer sleeps on retryAfterMs (with the parsed value clamped to a sane ceiling) or the machinery is deleted — the unclamped parse (3-year sleep / overflow on a hostile header) is gone either way, and a named test pins the surviving behavior"
  - "The local-only guard snapshots infochat.llm.default.provider: a named test sets local-only=true with default.provider=anthropic and asserts startup fails with the same conflict error a per-task remote provider produces (today the guard snapshot covers per-task and embedding keys only)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §Routing
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-06-07
    reason: clarity-fail rework
    snapshot:
      status: escalated
      files_budget: 8
      security_relevant: false
      clarity_check:
        date: 2026-06-07
        verdict: FAIL
        blockers:
          - "TEST-CHANGES-AUTHORIZED: test_plan.modifies declares pre-existing tests in infochat-llm-adapter/src/test/java/app/zcat/infochat/llm will be modified, but the ticket body has no 'Authorized test changes' section naming the specific pre-existing test classes/methods and the new expected behavior for each."
        warnings:
          - "FILES-BUDGET-PLAUSIBLE: files_budget of 8 is tight for 5 acceptance items each requiring a named test, plus 5 production files."
          - "SECURITY-FLAG-CONSISTENT: L5 (local-only guard gap) is a data-leakage surface characterized as a privacy commitment in the spec; security_relevant: false may be under-claimed."
escalations:
  - date: 2026-06-07
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED (FAIL): test_plan.modifies declares that
      pre-existing tests in infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
      will be modified, but the ticket body has no "Authorized test changes"
      section and names no specific pre-existing test class, method, or
      assertion being changed. Add an "Authorized test changes" subsection to
      the body listing each pre-existing test file/class that will be touched
      and the new expected behavior for each.
---

# M1-192: LLM per-task config completion (configFor, remote-llm, guard)

## Context

Four config-completion gaps in the LLM adapter (unified findings L1, L2,
L4, L5 — `deep-code-review/v2/UNIFIED.md` §2):

1. **configFor UOE (L1, high).** OpenAiCompatibleProvider.configFor
   (OpenAiCompatibleProvider.java:157-165) throws
   UnsupportedOperationException for TAGGER/ENTITY/SUMMARIZER/CHAT_AGENT/
   TRANSLATOR. Production call sites exist for all six tasks (TaggerWorker,
   EntityExtractorWorker, SummaryProseGenerator, ChatAgent,
   LlmTranslationProvider), the corresponding config keys ship in collector
   application.properties (:339-356) and are read by nothing, and the
   workers catch RuntimeException — so on any deployment routing these
   tasks to the openai-compatible provider (the local/Ollama default), the
   eval pipeline silently degrades to permanent fallback.
2. **%remote-llm missing model keys (L2, high).** The remote-llm profile
   declares chat/summarizer provider, base-url, and max-tokens but no
   `.model`; AnthropicProvider.configFor requires `prefix+"model"`
   (AnthropicProvider.java:108, getValue, no default) — first call fails.
3. **Dead Retry-After machinery (L4).** `retryAfterMs` has ~20 references
   inside the adapter and zero consumers outside; the parse is unclamped.
4. **Local-only guard gap (L5, low-sec).** The startup guard snapshots
   per-task and embedding provider keys but never
   `infochat.llm.default.provider` — `default.provider=anthropic` passes a
   local-only deployment.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Authorized test changes

Pre-existing tests that this ticket is authorized to modify, and the new
expected behavior for each (everything not listed here is preserved
verbatim):

- `OpenAiCompatibleEmbeddingProviderTest` — the Retry-After test
  (503 + `Retry-After: 3` → `retryAfterMs() == 3000`): per the acceptance
  item 4 outcome, either rewritten to assert the clamped/consumed behavior
  or deleted together with the machinery.
- `AnthropicProviderTest` — the Retry-After test (429 + `Retry-After: 2` →
  `retryAfterMs() == 2000`): same two outcomes as above.
- `AnthropicProviderTest` — additive only beyond the Retry-After test: new
  methods for the remote-llm CHAT_AGENT/SUMMARIZER config resolution
  (acceptance item 3) may join the class; no other existing assertion
  changes.
- `LlmRouterStartupGuardLocalOnlyTest` — additive only: a new test for the
  `infochat.llm.default.provider` snapshot key (acceptance item 5); all
  existing tests preserved (they pass hand-rolled snapshot maps, and an
  absent key means no conflict).

## Notes

- files_budget 11 accounting: `retryAfterMs` lives in four impl classes
  (LlmHttpSupport, OpenAiCompatibleProvider, AnthropicProvider,
  OpenAiCompatibleEmbeddingProvider) + guard + two application.properties
  = 7 production files; plus one new test class
  (OpenAiCompatibleProviderTest) and the three modified test classes
  listed under §Authorized test changes.
- security_relevant: true because the local-only guard (L5) is the
  enforcement point of the "post bodies must not leave the host" privacy
  commitment; the audit's "low-sec" label describes severity, not surface.
- Source: `UNIFIED.md` §3 T16 under `deep-code-review/v2/` (opus-48 llm
  F1/F3/F8, gpt R1/CQ2).
- AnthropicProvider's dynamic per-call MicroProfile config read is the
  in-repo pattern for the configFor fix (suggested by opus-48; Tier B —
  note opus-47 flagged per-call config lookups as a perf nit, so a cached
  variant also satisfies the acceptance; pick one and say why).
- Picking the %remote-llm default chat/summarizer model values is an
  operator-visible choice — keep them consistent with the documented
  remote-llm posture in docs/design/05 and flag the chosen models in the
  commit message.
