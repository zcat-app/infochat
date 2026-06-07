---
id: M1-192
title: "LLM per-task config completion (configFor, remote-llm, guard)"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 8
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/application.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
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

## Notes

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
