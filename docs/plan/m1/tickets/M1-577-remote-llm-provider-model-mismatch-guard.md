---
id: M1-577
title: "Startup guard for remote-LLM provider/base-url/model mismatch (catch silent 400s)"
status: pending
created: 2026-07-06
last_updated: 2026-07-06
blocked_by: []
files_budget: 4
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Auto-correcting or rewriting operator config. The guard WARNs (or optionally
    fails); it never mutates base-url, provider, or model. Config stays the
    operator's.
  - >-
    Provider auto-detection from base-url (e.g. inferring provider=anthropic from
    an anthropic.com host). Inferring intent is out of scope; the guard only flags
    an internally inconsistent triple.
  - >-
    The wizard prompt change (having prod/scripts/4-llm.sh capture provider+model
    when a non-Ollama remote is chosen). That is a worthwhile companion but is
    ops tooling in a separate ticket; this ticket is the RUNTIME guard only.
  - >-
    Changing the embedding provider/config. EmbeddingProvider is a separate SPI
    and correctly points at local Ollama; untouched.
acceptance:
  - >-
    At startup, for each LLM ModelTask, the guard detects an internally
    inconsistent (provider, base-url, model) triple and emits a distinct,
    actionable WARN naming the task, provider, base-url host, model, and the
    likely fix. At minimum it flags: (a) provider=anthropic with a base-url whose
    host is not the Anthropic API host, and (b) provider=openai-compatible whose
    model name is a local-runtime name (llama*/nomic*/qwen*/mistral* families)
    while the base-url host is a non-loopback remote — the exact shape that made
    DeepSeek 400 on every call this session.
  - >-
    The guard is ADVISORY by default (it does not block boot). A single config
    flag (documented) opts into fail-fast for operators who want a misconfig to
    stop startup rather than silently degrade ingest.
  - >-
    No false positives on the three supported shapes: (1) local Ollama
    (loopback base-url, llama/nomic models); (2) an Anthropic remote
    (provider=anthropic, anthropic.com base-url, claude-* models); (3) a
    correctly-configured OpenAI-compatible remote (provider=openai-compatible,
    remote base-url, a provider-native model such as deepseek-chat). Each must
    pass the guard cleanly.
  - >-
    LlmRouterStartupGuardTest asserts: the DeepSeek-misconfig triple
    (provider=anthropic OR model=llama3.1:8b against api.deepseek.com) is flagged;
    each of the three supported shapes is NOT flagged; fail-fast mode aborts on a
    flagged triple and advisory mode does not.
  - "`mvn verify` is green from the repo root (new tests pass; full suite passes)."
test_plan:
  adds:
    - "infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuardTest.java — misconfig triple flagged; three supported shapes not flagged; advisory vs fail-fast."
  modifies:
    - "infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java — mismatch detection + message; docs/design/05-llm-and-embeddings.md — guard/flag docs."
  preserves:
    - "existing 'post bodies leave the host' WARNs; all tests green on main."
spec_refs:
  - "docs/design/05-llm-and-embeddings.md §5.3 Provider implementations"
  - "docs/spec/llm.md §Per-task routing rules"
decision_refs: []
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-577: Guard against remote-LLM provider/base-url/model mismatch

## Context

This session, the live deployment pointed the LLM `base-url` at DeepSeek
(OpenAI-compatible) but left the `remote-llm` profile's Anthropic defaults in
place: `provider=anthropic` and Claude model names for chat/summarizer/
translator, and Ollama model names (`llama3.1:8b`) for tagger/entity/security.
The result was **HTTP 400 on every LLM call** — 3883 of them — because the app
either spoke the Anthropic wire dialect at DeepSeek or asked DeepSeek for models
it does not have. This silently degraded tagging/entity extraction AND broke
`/summary` (the summarizer 400'd), while `LlmRouterStartupGuard` logged only a
benign "post bodies will leave the host" WARN — it never flagged the mismatch.

The config is now fixed live (provider=openai-compatible + model=deepseek-chat;
see the `deepseek-remote-llm-config` session note). This ticket adds the guard
so the next operator gets a loud, actionable signal at boot instead of a silent
400 storm at ingest.

## Approach

- Strengthen `LlmRouterStartupGuard` to check each task's (provider, base-url,
  model) for the obvious inconsistencies above and emit a targeted WARN with the
  task, the offending values, and the fix.
- Add an opt-in fail-fast config flag; default advisory so a partial misconfig
  doesn't harden into a boot failure for operators who prefer degrade-and-warn.
- Keep the check conservative — only flag triples that cannot work — so the three
  supported shapes never false-positive.

## Notes

- **Never rewrite operator config.** The guard only observes and reports. The
  companion "wizard captures provider+model" improvement is deliberately a
  separate (ops-tooling) ticket, kept out of scope here so this stays a small,
  well-tested runtime change.
- Reference: session memory `deepseek-remote-llm-config` records the exact
  misconfig and the fix, and the boot-log evidence (3883 → 0 non-2xx).
