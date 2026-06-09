---
id: M1-265
title: "LLM routing guard closure + provider hygiene"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 14
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/ModelTask.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/application.properties
  - docs/spec/llm.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - The routing priority order (per-task override > language > default) — unchanged; only its validation coverage widens.
  - Per-call local-only enforcement — spec-conformant by explicit decision ("checked once at startup, not per call", docs/spec/llm.md); do not add it.
  - Adding new providers or new languages.
  - Per-profile default model choices.
acceptance:
  - "Startup task-resolution validation covers the priority-2 language branch: every (task, configured language) pair must resolve to a provider whose per-task config is complete (model, max-tokens, base-url as applicable); a deployment whose languages config routes TRANSLATOR to a provider missing infochat.llm.translator.max-tokens fails at startup, not at the first /lang translation. Named test."
  - "LlmRouterStartupGuard's local-only check covers language-capability selection: under local-only=true, a cloud-only provider reachable via the languages keys fails startup. Named test."
  - "The shipped %remote-llm profile passes the widened validation: a router test with %remote-llm-shaped config asserts forTask(TRANSLATOR, \"cs\") resolves to a provider whose TRANSLATOR config does not throw on missing keys (fix the profile properties as needed)."
  - "AnthropicProvider omits the system field entirely when the system prompt is blank; a named test asserts no empty text block is serialized."
  - "Per-task config keys (PER_TASK_BASE_URL_KEYS and providerKeyFor) are derived from ModelTask.keySegment() instead of hand-spelled literals and string replace; a named test pins the derived key for every ModelTask."
  - "All LLM provider HttpClients are built with an explicit connectTimeout."
  - "The startup guard's Collector-only claims (class doc and docs/spec/llm.md) are reconciled with the fact that the guard also runs on the Provider — documented as intentional, since chat/summarizer/translator run there."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  modifies:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-265: LLM routing guard closure + provider hygiene

## Context

Deep-review v4 verified HIGH **H4** plus mediums **M-L1..M-L4**
(`deep-code-review/v4/UNIFIED-REPORT.md` §1/§2; sources
`deep-code-review/v4/fable5/04-module-infochat-llm-adapter.md#F1/#F2/#F3`,
`deep-code-review/v4/opus-47/04-module-infochat-llm-adapter.md#F1/#F2/#F6`,
`deep-code-review/v4/gpt-55/report.md` M-05):

- **H4:** `LlmRouter.assertAllTasksResolve` (:232-236) calls
  `forTask(task, "en")` only — the priority-2 language-capability branch
  (:163-169) is never validated; `LlmRouterStartupGuard` inspects the default
  provider and per-task overrides but never language selection. Concretely:
  `%remote-llm` sets `infochat.llm.anthropic.languages=en,cs` plus anthropic
  overrides for chat/summarizer only; TRANSLATOR keeps the base Ollama config
  and no `infochat.llm.translator.max-tokens` exists anywhere — so the first
  `/lang cs` translation routes to AnthropicProvider, whose
  `configFor(TRANSLATOR)` throws on the missing key. A cloud-only provider can
  also be selected via the languages key under `local-only=true`, unseen by
  the guard.
- **M-L1:** AnthropicProvider always builds the system array, sending
  `text: ""` when the system prompt is empty — the API rejects empty text
  blocks; translation passes an empty system prompt, so the trigger is real.
- **M-L2:** `PER_TASK_BASE_URL_KEYS` hand-spells six literals that
  `ModelTask.keySegment()` derives; `providerKeyFor` does
  `replace(".base-url", ".provider")`.
- **M-L3:** the guard is documented Collector-only (class doc + spec) but the
  provider's CDI index includes the jar, so it runs there too — which is
  arguably desirable given H4; reconcile the docs rather than gating the bean.
- **M-L4:** all three providers use `HttpClient.newHttpClient()` with no
  connectTimeout.

## Acceptance

See frontmatter. The core is closing both startup guards over the
priority-2 branch and making the shipped `%remote-llm` profile actually boot
and translate; the provider hygiene legs (M-L1/M-L2/M-L4) ride along because
they share the same files and test surface.

## Out-of-scope

See frontmatter — notably do NOT add per-call local-only enforcement; the
once-at-startup posture is an explicit spec decision.

## Notes

- `complexity: high` → plan-writer outline at start. The guard-closure leg
  needs a design choice: enumerate (task × configured-language) pairs at
  startup, or validate each provider's full per-task config whenever its
  languages key makes it reachable. The outline should pick one and audit the
  real config surface first.
- The `%remote-llm` fix likely means adding the missing translator
  override(s) to the profile or defining a base `translator.max-tokens` —
  decide in the outline; the acceptance only pins "boots and resolves".
- docs/spec/llm.md is touched (Collector-only claim) — spec edit coordinated
  with code, so the ticket flow (not a bare spec: commit) is correct.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-265-*.md
```
