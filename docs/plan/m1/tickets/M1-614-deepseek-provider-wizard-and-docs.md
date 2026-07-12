---
id: M1-614
title: "Setup wizard: deepseek provider option + provider docs"
status: done
created: 2026-07-12
last_updated: 2026-07-12
blocked_by: []
files_budget: 8
files_scope:
  - prod/scripts/4-llm.sh
  - prod/switch-llm.sh
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/SwitchLlmWiringTest.java
  - docs/design/05-llm-and-embeddings.md
  - docs/design/07-deployment.md
  - SETUP_GUIDE.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
provenance: >-
  M1-608 (2026-07-12) added the dedicated DeepSeekProvider (provider=deepseek)
  so deepseek-v4-flash runs thinking-off; deepseek-chat is deprecated 2026-07-24.
  But (a) neither prod/scripts/4-llm.sh nor prod/switch-llm.sh ever sets a
  provider — both rely on the hardcoded openai-compatible default
  (LlmRouter.java:214) — nor does the remote branch set the generative model
  (it leaves the profile's baked local model names, which 400 at a remote
  endpoint), so a DeepSeek deployment's provider=deepseek + model=deepseek-v4-flash
  had to be hand-edited into the (gitignored) runtime config; and (b) M1-608
  touched no docs, so docs/design/05 documents only openai-compatible (+ ollama
  alias) and anthropic — provider=deepseek is undocumented. This ticket makes
  deepseek a first-class wizard choice and documents the provider set.
out_of_scope:
  - >-
    The DeepSeekProvider / OpenAiCompatibleProvider / AnthropicProvider Java, the
    LlmRouter provider-resolution, and the M1-577 LlmRouterStartupGuard logic
    (M1-608 / M1-577). This ticket CONFIGURES and DOCUMENTS those; it does not
    change the providers, router, or guard code.
  - >-
    Wizard support for provider=anthropic. Anthropic stays MVP-deferred
    (docs/design/00-mvp.md) and wizard-unsupported (manual per-task config as
    today); the wizard adds only `deepseek` alongside the existing implicit
    openai-compatible remote path. The docs still NAME anthropic as one of the
    three valid provider values.
  - >-
    The ollama / llamacpp local backends (they route through openai-compatible
    via the ollama alias — unchanged), infochat.embeddings.* (always local
    768-dim nomic — untouched), and the gitignored runtime
    prod/runtime/application.properties (operator config, not a repo artifact;
    already migrated by hand 2026-07-12).
acceptance:
  - >-
    prod/scripts/4-llm.sh `remote` backend lets the operator choose the remote
    provider dialect (openai-compatible | deepseek), defaulting to
    openai-compatible so the existing generic-remote behavior is unchanged when
    the operator does not pick deepseek. A `deepseek` choice writes
    infochat.llm.default.provider=deepseek and sets the seven generative task
    models to deepseek-v4-flash (the deepseek provider disables thinking by
    default, so no reasoning-effort key is written). An `openai-compatible`
    choice writes provider=openai-compatible (or leaves the default) and sets
    the seven task models to the operator-entered model name.
  - >-
    The `remote` branch now writes the generative model for every task (it
    previously left the profile's baked local model names on remote tasks, which
    the M1-577 guard flags as a provider/model mismatch), so a wizard-generated
    remote config passes LlmRouterStartupGuard with no mismatch WARN.
  - >-
    prod/switch-llm.sh offers the same remote provider-dialect selection
    symmetrically, writing provider=deepseek + deepseek-v4-flash on a deepseek
    choice, and never touching infochat.embeddings.* (unchanged invariant).
  - >-
    RemoteLlmWiringTest and SwitchLlmWiringTest gain cases asserting the generated
    config: a deepseek selection yields provider=deepseek + deepseek-v4-flash on
    the tasks; an openai-compatible selection yields provider=openai-compatible +
    the entered model. Existing wiring-test assertions stay green (cases added,
    none weakened).
  - >-
    docs/design/05-llm-and-embeddings.md documents the `deepseek` provider
    (M1-608): a specialization of openai-compatible for api.deepseek.com that
    injects the DeepSeek `thinking` toggle so a task runs non-thinking by default
    on deepseek-v4-flash (which defaults thinking-on), with an optional per-task
    reasoning-effort to enable a depth; why the generic openai-compatible adapter
    cannot send `thinking` unconditionally; and that deepseek is a recognized
    remote provider (passes the M1-577 guard, is in its remote-provider set). The
    three valid provider values — openai-compatible, deepseek, anthropic — are
    named together.
  - >-
    SETUP_GUIDE.md step 4 documents the remote provider-dialect choice, and its
    AI-backend benchmark table names the remote row deepseek-v4-flash (not the
    deprecated deepseek-chat). docs/design/07-deployment.md's remote-provider
    config example gains a DeepSeek (provider=deepseek + deepseek-v4-flash) form
    alongside the existing generic openai-compatible example.
  - >-
    mvn verify is green from the repo root (this ticket changes shell scripts +
    Java wiring tests + docs; the Java test change means the inert-diff path does
    NOT apply).
test_plan:
  modifies:
    - >-
      prod/scripts/4-llm.sh (remote branch: provider-dialect prompt + set
      provider + generative model)
    - prod/switch-llm.sh (symmetric remote provider-dialect selection)
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/RemoteLlmWiringTest.java
      (add deepseek + openai-compatible provider/model assertions)
    - >-
      infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/SwitchLlmWiringTest.java
      (add deepseek provider/model assertion)
    - docs/design/05-llm-and-embeddings.md (document provider=deepseek)
    - docs/design/07-deployment.md (DeepSeek remote-config example)
    - SETUP_GUIDE.md (step-4 provider choice + v4-flash benchmark label)
  preserves:
    - all tests currently green on main
    - the openai-compatible default when the operator does not choose deepseek
    - infochat.embeddings.* (always local 768-dim nomic)
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/deployment.md §Bootstrap
decision_refs:
  - D56
reviews:
  - round: 1
    date: 2026-07-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 314
      removed: 42
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-614: Setup wizard — deepseek provider option + provider docs

## Context

M1-608 added the dedicated `DeepSeekProvider` (`provider=deepseek`) so
`deepseek-v4-flash` runs thinking-off (it defaults thinking-on, which burns the
`max_tokens` budget). But the setup tooling never caught up: neither
`prod/scripts/4-llm.sh` nor `prod/switch-llm.sh` writes a `provider` at all —
both rely on the hardcoded `openai-compatible` default (`LlmRouter.java:214`) —
and the `remote` branch never writes the generative model either, so a DeepSeek
deployment's `provider=deepseek` + `model=deepseek-v4-flash` had to be hand-edited
into the (gitignored) runtime config. M1-608 also touched no docs, so
`docs/design/05-llm-and-embeddings.md` documents only `openai-compatible` (+ the
`ollama` alias) and `anthropic`; `deepseek` is undocumented. This ticket makes
`deepseek` a first-class, wizard-selectable remote provider and documents the
three provider values, so a fresh DeepSeek setup is correct out of the box.

## Acceptance

See the YAML `acceptance:` list. In prose: teach the `remote` branch of the
wizard (and `switch-llm.sh`) to ask which remote provider dialect the endpoint
speaks — `openai-compatible` (default, unchanged behavior) or `deepseek` — and to
write the matching `infochat.llm.default.provider` plus the generative model
(`deepseek-v4-flash` for deepseek; the operator-entered model for
openai-compatible), fixing the pre-existing gap where remote tasks kept baked
local model names. Cover both new paths in the wiring tests. Document
`provider=deepseek` in design/05, add a DeepSeek remote example to design/07, and
refresh the SETUP_GUIDE step-4 flow + benchmark label.

## Out-of-scope

No changes to the provider/router/guard Java (M1-608/M1-577 configure-and-document
only). No wizard support for `provider=anthropic` (MVP-deferred; stays manual —
but named in the docs as a valid value). No changes to the local ollama/llamacpp
backends, to `infochat.embeddings.*`, or to the gitignored runtime config.

## Notes

- **Design choice (chosen: explicit provider-dialect prompt).** The remote branch
  already asks for a base-url + API key; add one prompt — the remote provider
  dialect — defaulting to `openai-compatible` so nothing changes for a NanoGPT-
  style endpoint. Alternatives considered: (a) auto-detect `deepseek` from an
  `api.deepseek.com` base-url — rejected as too implicit / surprising, and it
  cannot cover a DeepSeek-compatible gateway on another host; (b) a distinct
  `deepseek` top-level backend alongside `ollama|llamacpp|remote` — rejected as
  redundant with `remote` (deepseek IS a remote OpenAI-family endpoint). The
  implementer may offer `https://api.deepseek.com` as the default base-url when
  the operator picks `deepseek`.
- The `deepseek` provider disables thinking by default, so the wizard writes NO
  `reasoning-effort` key — matching the M1-610 recommendation (reasoning OFF for
  every task). If a future ticket enables reasoning, the M1-610 coupling guard
  (`max-tokens ≥ 4000`) applies; out of scope here.
- Relevant code: the provider-resolution default is `LlmRouter.java:214`
  (`orElse(OpenAiCompatibleProvider.PROVIDER_NAME)`); the M1-577 mismatch shapes
  are in `LlmRouterStartupGuard.mismatchFinding` (only `anthropic` and
  `openai-compatible` shapes are scrutinized — `deepseek` passes cleanly).
- Relevant design note: `docs/design/05-llm-and-embeddings.md`
  §"Provider/base-url/model consistency guard (M1-577)" and the
  `OpenAiCompatibleProvider` / `AnthropicProvider` subsections it sits among.
- The measured evidence that `deepseek-v4-flash` + thinking-off is correct on the
  wire is `docs/plan/m1/spikes/M1-613-entity-hardening.md` (150 successful live
  calls with exactly that body).
