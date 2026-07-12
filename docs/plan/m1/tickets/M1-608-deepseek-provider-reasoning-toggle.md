---
id: M1-608
title: "DeepSeek provider subclass with per-task reasoning toggle (v4-flash thinking-mode control)"
status: done
created: 2026-07-12
last_updated: 2026-07-12
clarity_check:
  date: 2026-07-12
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 12
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
provenance: >-
  M1-606 discussion + live smoke test 2026-07-12. DeepSeek is deprecating the
  deepseek-chat / deepseek-reasoner model IDs on 2026-07-24, merging them into
  deepseek-v4-flash with a thinking-mode toggle. Smoke test findings: our exact
  request shape works against v4-flash; requesting model=deepseek-v4-flash
  DEFAULTS TO THINKING ON (burns reasoning tokens, slower, risks truncating the
  answer under max_tokens); the current model=deepseek-chat already resolves to
  v4-flash NON-thinking server-side. The only way to force non-thinking is the
  DeepSeek-specific body field "thinking":{"type":"disabled"} (confirmed: 0
  reasoning tokens; reasoning_effort only tunes depth high|low|medium|max|xhigh
  and cannot turn thinking off; thinking:false as a boolean is a 400). A naive
  config swap to deepseek-v4-flash would therefore regress every task into slow,
  costly reasoning — hence a DeepSeek-scoped adapter that controls the field.
out_of_scope:
  - >-
    Flipping SECURITY_JUDGE (or any task) to reasoning-ON in production. This
    ticket delivers the toggle DEFAULTED OFF for every task; whether reasoning
    actually improves injection detection is an untested hypothesis to settle by
    a separate eval (measuring detection quality AND the fail-open rate) before
    enabling it. No task's effective behaviour changes when this lands.
  - >-
    The runtime deployment config switch (provider=deepseek,
    model=deepseek-v4-flash in prod/runtime/application.properties). That file is
    operator-owned / gitignored — the switch is a DEPLOY step gated on this code
    landing, documented in the deployment notes, to be performed on/before the
    2026-07-24 deepseek-chat sunset.
  - >-
    Embeddings. They stay on the local backend (nomic-embed-text, D54);
    DeepSeekProvider is a chat-completion LlmProvider only, never an
    EmbeddingProvider.
  - >-
    Moving the judge / tagging to LOCAL models. That cost-vs-fragility eval is a
    separate future spike, not this ticket.
  - >-
    Changing the shared OpenAiCompatibleProvider behaviour for the generic
    (real-OpenAI / Ollama) case. The parent gains ONE protected no-op body-
    customization seam; its existing request shape is byte-identical when the
    seam is not overridden (the AnthropicProvider and generic paths are
    untouched).
acceptance:
  - >-
    A DeepSeekProvider (LlmProvider) exists as a subclass of
    OpenAiCompatibleProvider registered under provider name "deepseek",
    inheriting the shared HTTP send / non-2xx / parse / per-task config
    resolution logic and overriding ONLY the request-body assembly via a new
    protected seam on the parent (a no-op hook the generic path leaves
    untouched). A per-task route with provider=deepseek resolves to
    DeepSeekProvider through LlmRouter.forTask exactly as provider=openai-
    compatible resolves today.
  - >-
    Per-task reasoning control via an optional config key
    infochat.llm.<task>.reasoning-effort. Its OFF sentinel (the DEFAULT for
    every task when unset) makes DeepSeekProvider inject
    "thinking":{"type":"disabled"} into the request body — confirmed non-thinking
    (0 reasoning tokens). A depth value (one of DeepSeek's high|low|medium|max|
    xhigh) enables thinking at that depth. Default OFF everywhere, so the
    migration preserves the current deepseek-chat non-thinking behaviour and
    token cost with no per-task config required.
  - >-
    max_tokens coupling is documented and guarded. Because reasoning tokens
    consume the completion budget, a task with reasoning ENABLED must carry a
    max_tokens large enough for reasoning + the answer or the answer truncates;
    for SECURITY_JUDGE a truncated/empty verdict is an infra failure that
    fail-opens. The ticket documents this coupling at the config surface, and a
    test asserts that with reasoning OFF (the default for every task) the
    assembled body disables thinking so no phantom reasoning field can crowd out
    the verdict/label.
  - >-
    The startup guard treats "deepseek" as a REMOTE provider: DeepSeekProvider's
    PROVIDER_NAME is added to LlmRouterStartupGuard.REMOTE_PROVIDER_NAMES so the
    local-only conflict check and the language-route disclosure check cover it,
    matching the existing AnthropicProvider handling. A deepseek route under
    infochat.llm.local-only=true fails boot loudly.
  - >-
    Request-structure parity with the live API (verified 2026-07-12): the body
    carries {model, max_tokens, messages:[{role,content}]} plus the thinking
    field, POSTs to <base>/chat/completions, and the response is parsed as
    choices[0].message.content (the extra reasoning_content field is ignored).
  - >-
    NAMED TESTS. A DeepSeekProviderTest asserts the assembled request body
    carries "thinking":{"type":"disabled"} when reasoning-effort is unset/OFF and
    a thinking-enabled body at the configured depth when a level is set (parse
    the JSON body the provider builds — do NOT hit the network). A
    router/registration test asserts provider=deepseek resolves to
    DeepSeekProvider. A startup-guard test asserts deepseek is treated as remote
    (local-only conflict fires). Red-before/green-after on the thinking-field
    assembly (a body without the disable field defaults to thinking-on).
  - mvn verify is green from the repo root.
test_plan:
  adds:
    - >-
      DeepSeekProviderTest (NEW file; parse-the-body, no network): (a)
      reasoning-effort unset/OFF -> assembled body carries
      "thinking":{"type":"disabled"}; (b) reasoning-effort set to a DeepSeek
      depth (high|low|medium|max|xhigh) -> body carries thinking ENABLED at that
      depth; request-structure parity ({model, max_tokens,
      messages:[{role,content}]} + thinking, POST <base>/chat/completions,
      response parsed as choices[0].message.content); provider=deepseek resolves
      to DeepSeekProvider via a hand-built LlmRouter Entry; a bare
      OpenAiCompatibleProvider assembles NO thinking field (pins the parent seam
      as a no-op when not overridden). Red-before/green-after on the
      thinking-disable assembly.
    - >-
      LlmRouterStartupGuardLocalOnlyTest.java: ADD one new @Test method (net-new
      coverage; existing methods untouched) mirroring
      localOnlyTrueWithRemoteProviderOverrideRefusesStartup — set
      infochat.llm.chat.provider=deepseek under infochat.llm.local-only=true and
      assert validateLocalOnlyConfiguration throws LocalOnlyConflictException
      naming the deepseek route (deepseek treated as remote, exactly as
      anthropic is).
  modifies:
    - >-
      LlmRouterStartupGuardLanguageRouteDisclosureTest.java: COMMENT-ONLY update
      to the class Javadoc line "anthropic is the only member of
      REMOTE_PROVIDER_NAMES" so it names both anthropic and deepseek. No
      assertion or fixture change — the three test bodies stay green because
      deepseek's languages key is absent from their hand-built snapshots, so the
      guard's REMOTE_PROVIDER_NAMES iteration emits no additional warning. This
      is the ONLY pre-existing test whose content changes: no test asserts
      REMOTE_PROVIDER_NAMES size/membership, router-resolution tests use
      hand-built List<Entry>/ListInstance so a new @ApplicationScoped bean never
      reaches them, and nothing @Injects the concrete OpenAiCompatibleProvider
      type so the subclass creates no CDI ambiguity.
  preserves:
    - all tests currently green on main
    - >-
      the generic OpenAiCompatibleProvider and AnthropicProvider request shapes
      (the parent's new seam is a no-op unless overridden).
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Per-task routing rules
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
      files: 8
      added: 409
      removed: 13
escalations: []
overrides: []
revisions:
  - date: 2026-07-12
    reason: >-
      clarity-fail refine (bounded self-refine via /m1-tick run; prose-only,
      within existing scope — no files_budget/files_scope/out_of_scope change).
      Cleared the TEST-CHANGES-AUTHORIZED blocker: test_plan.modifies deferred
      the required file enumeration ("enumerate at start"). A ground-truth pass
      over the llm-adapter test tree replaced the deferral with the verified
      impact — exactly ONE pre-existing test's comment changes; every other
      assertion is net-new (adds). Also cleared the non-blocking WARNING:
      acceptance item 3's undefined "metadata-task setting" jargon became "the
      default for every task" (the concrete assertion is unchanged).
    snapshot: |
      test_plan.modifies (verbatim, pre-refine): "Router/registration and
        startup-guard tests to the extent the new provider name and
        REMOTE_PROVIDER_NAMES entry change their fixtures — enumerate at start."
      test_plan.adds (pre-refine): "DeepSeekProviderTest (body-assembly: thinking
        disabled by default, enabled+depth when configured; parse-the-body, no
        network)." + "Startup-guard test asserting deepseek is a remote provider."
      acceptance item 3 tail (verbatim, pre-refine): "... a test asserts that with
        reasoning OFF (the default and the metadata-task setting) the assembled
        body disables thinking so no phantom reasoning field can crowd out the
        verdict/label."
      clarity_check 2026-07-12: FAIL, 1 blocker (TEST-CHANGES-AUTHORIZED) + 1
        warning (ACCEPTANCE-RUNNABLE item 3 jargon). Verified findings behind the
        narrowed enumeration: REMOTE_PROVIDER_NAMES referenced in tests ONLY as a
        comment (LlmRouterStartupGuardLanguageRouteDisclosureTest line 32); no
        @QuarkusTest in the llm-adapter test tree; router-resolution tests use a
        hand-built ListInstance / List<Entry>; nothing @Injects the concrete
        OpenAiCompatibleProvider type (so the subclass adds no CDI ambiguity).
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-12
    verdict: CLEAN
    base: ab8f9d53b0f114cfa8e66c9304afc1a796405cd0
    head: working-tree (m1/M1-608 branch tip, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-608-2026-07-12.md
    out_of_model_count: 1
    note: |
      CLEAN, 0 findings. One out-of-model item: the reasoning-effort -> max-tokens
      coupling is a JavaDoc-promised gate not enforced in code, but operator config
      is trusted (not adversary-reachable) and the risk is dormant under the shipped
      default-OFF the diff introduces to prevent judge-verdict truncation (the diff
      strengthens the judge path). Structural coupling guard belongs with the future
      reasoning-enable eval, not M1-608. Surfaced to the user; not auto-filed.
---

# M1-608: DeepSeek provider subclass with per-task reasoning toggle

## Context

DeepSeek is retiring the `deepseek-chat` and `deepseek-reasoner` model IDs on
**2026-07-24**, folding both into `deepseek-v4-flash` — one model with a
thinking-mode switch (`deepseek-chat` = non-thinking, `deepseek-reasoner` =
thinking). Our runtime routes every LLM task (security judge, tagger, entity,
classifier, chat, summarizer, translator) to DeepSeek via the generic
`provider=openai-compatible` adapter today.

A live smoke test on 2026-07-12 (scripts under `.scratch`) established:

- Our exact request shape works against `deepseek-v4-flash` and the response
  still parses as `choices[0].message.content`.
- **`model=deepseek-v4-flash` defaults to thinking ON** — it spent 32 reasoning
  tokens to answer "pong", slower, and (critically) reasoning tokens count
  against `max_tokens`, so on a real classification prompt the JSON answer can
  be truncated → schema violation / empty verdict.
- **`model=deepseek-chat` already resolves to v4-flash NON-thinking** server-side
  — so today's behaviour is the non-thinking one, and we have runway until the
  alias sunset.
- The **only** off-switch is the DeepSeek-specific body field
  `"thinking":{"type":"disabled"}`. `reasoning_effort` only tunes depth
  (`high|low|medium|max|xhigh`, no "off"); a boolean `thinking:false` is a 400.

Because the generic OpenAI adapter must NOT send `thinking` to a real OpenAI /
Ollama endpoint (unknown field → 400), the control has to be DeepSeek-scoped —
a subclass, as proposed in the M1-606 discussion.

## Shape (refine at start / plan)

- **`DeepSeekProvider extends OpenAiCompatibleProvider`**, `@ApplicationScoped`,
  `PROVIDER_NAME = "deepseek"`. Inherits send/parse/config; overrides a new
  **protected seam** on the parent — e.g. `protected void customizeRequestBody(
  ObjectNode root, ModelTask task)` called inside `doCall` after the base body
  is assembled (a no-op in the parent, so the generic and Anthropic paths are
  byte-identical).
- **Per-task reasoning toggle**: `infochat.llm.<task>.reasoning-effort`, optional,
  default OFF. OFF → `root.set("thinking", {"type":"disabled"})`. A depth level →
  thinking enabled at that depth (verify the exact enabled-form + `reasoning_effort`
  pairing with a quick smoke call during implementation; the disable form is
  confirmed).
- **Startup guard**: add `"deepseek"` to `REMOTE_PROVIDER_NAMES` so the
  local-only / disclosure checks cover it like `anthropic`.
- **Default OFF everywhere** preserves current behaviour + cost; the judge-on
  capability exists but is not exercised (see out_of_scope).

## Notes

- **The security angle of the toggle (why security_relevant):** the toggle sits
  on the SECURITY_JUDGE's LLM call. Reasoning-ON there is plausibly better at
  injection detection, but (a) it must raise the judge's `max_tokens` or a
  reasoning-crowded-out verdict fail-opens, and (b) the benefit is unproven.
  This ticket ships the control defaulted OFF; enabling it for the judge is
  gated on a follow-up eval, not assumed here.
- **Alternatives considered — DeepSeek's Anthropic-format endpoint (rejected,
  smoke-tested 2026-07-12).** DeepSeek also serves an Anthropic-dialect API at
  `https://api.deepseek.com/anthropic`, and our existing `AnthropicProvider`
  shape (POST `/messages`, `x-api-key`, `anthropic-version`) works against it
  (both `/anthropic/v1/messages` and `/anthropic/messages` return 200). Tempting
  as a "reuse AnthropicProvider + config, no new class" path — but the smoke
  test killed it: the `/anthropic` endpoint ALSO defaults thinking-ON (content
  blocks `["thinking","text"]`), and the SAME `"thinking":{"type":"disabled"}`
  is needed to turn it off (confirmed → `["text"]`, 2 output tokens). So it does
  not avoid the thinking-control work, it just moves it to the Anthropic body;
  and because `AnthropicProvider` is shared with REAL Claude (whose thinking API
  differs — omit-to-disable, `budget_tokens` to enable), it would need its own
  DeepSeek subclass there too. Net: same structural cost as the OpenAI subclass,
  plus a bigger deviation from the current openai-compatible deployment. The
  `/anthropic` pairing stays a valid, guard-supported fallback if ever needed.
- **Rate limits + timeout interaction (DeepSeek docs, 2026-07-12).** DeepSeek
  publishes NO RPM/TPM limit — only a CONCURRENCY cap (v4-flash = 2,500
  concurrent; HTTP 429 on exceed). Our per-task `max-concurrency` is 2-8, ~3
  orders of magnitude under it — not a practical concern. Two interactions worth
  keeping: (a) a 429 is a non-2xx APPLICATION error, so the M1-606 breaker
  correctly does NOT trip on it (429 = reachable, just throttled — the task's own
  retry/degrade handles it); (b) thinking-mode responses are SLOWER, so leaving
  thinking ON for a task risks hitting the ~30s client timeout, which M1-606
  then classifies as transport-unreachable and could trip the breaker — another
  reason the toggle DEFAULTS OFF.
- **Follow-up (not this ticket):** measure the judge + tagger on LOCAL small
  models — whether local inference matches remote quality and saves paid-API
  cost, or is too fragile and we standardize on remote. A separate spike (M1-609).
