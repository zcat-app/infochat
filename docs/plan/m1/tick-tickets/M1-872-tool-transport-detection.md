---
id: M1-872
title: "Native tool-call transport behind detected capability"
status: done
created: 2026-08-16
last_updated: 2026-08-17
flow: tick
reproduction: >-
  OpenAiCompatibleProviderToolCallTest#aToolsBearingCallParsesStructuredToolCalls
  (written and run RED at start 2026-08-17 — compile-failure shape, the
  SPI members do not exist, per the M1-847 precedent; child of a 2+
  decomposition, analysis
  docs/plan/m1/tick-analysis/tool-transport-model-independence.md). Probe of
  today's wrong posture (absence, grep-verified): grep -rn
  '"tools"\|tool_choice\|tool_calls\|response_format'
  infochat-llm-adapter/src/main/java returns NO match —
  the wire never carries tool declarations and never reads structured
  tool calls back; parseChoiceText reads exactly
  choices[0].message.content and throws on an absent/non-textual content
  (OpenAiCompatibleProvider.java:342-372), so a tools-speaking endpoint's
  structured emission is unusable and the only transport is the
  model-family-dependent text dialect.
analysis_ref: docs/plan/m1/tick-analysis/tool-transport-model-independence.md
blocked_by: [M1-871, M1-873]
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/LlmResponse.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderToolCallTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmProviderToolCallDecoratorTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterToolTransportTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The tool allowlist and the dispatch boundary in ANY form — structured
    calls funnel (name, args) into the SAME ChatToolDispatcher.dispatch
    path with the shared TurnContext (P3; ChatToolDispatcher.java:137-205
    unchanged; its suites green unchanged).
  - >-
    Any docs/spec/** edit — the transport wording is M1-873's amendment,
    landed first (blocked_by); this ticket implements under it.
  - >-
    ENABLING any model — the cleared-set lands EMPTY (P11: no measured
    (model, transport) pair exists; the only measured chat model, gemma,
    is a text-dialect emitter by the M1-844/M1-858 records). Production
    behavior is byte-identical to today, pinned by test; clearance for a
    real pair is a future measurement campaign's verdict (the extended-G6
    yardstick), never a config flip.
  - >-
    AnthropicProvider native tools (top-level tools / tool_use content
    blocks) — it keeps the honest cannot-serve default (the
    supportsStreaming posture, LlmProvider.java:86-88); an additive
    later ticket. DeepSeekProvider inherits the OpenAI-compat leg
    (subclass posture).
  - >-
    STREAMING + tools interaction — the tools-bearing shape is
    single-shot; streamed structured calls are a separate later decision
    (the M1-847 streaming shape stays untouched).
  - >-
    Any TEXT-transport change — TOOL_INSTRUCTIONS, the two accepted
    dialects, earliest-match precedence, and the M1-870 strip are
    byte-untouched (P12: the fallback is the permanent surface, not a
    legacy path).
acceptance:
  - "OpenAiCompatibleProviderToolCallTest.aToolsBearingCallParsesStructuredToolCalls (the reproduction, written and run RED at start) passes — against a fake endpoint (the mock-server test seam, OpenAiCompatibleProvider.java:129-133): the request carries the catalog-rendered 'tools' array and 'tool_choice':'auto' ONLY on the tools-bearing shape (the single-string body is byte-identical to today's); a canned response with choices[0].message.tool_calls[] yields the structured call list (name + raw args JSON) with finish-reason; a response carrying tool_calls with absent content does NOT throw (the parse condition is narrowed to content-absent-AND-no-tool-calls); DeepSeekProvider inherits the leg and a DeepSeek-flavored fake passes the same shape."
  - "The SPI shape is declared ON the interface with an honest default (P10, the M1-847 pattern) — a provider that does not override reports cannot-serve and a caller gating on the signal never reaches the refusal; the to-be-written LlmProviderToolCallDecoratorTest passes, proving the CircuitBreaking/Metered/Budgeted wrapper chain forwards the tools-bearing shape with breaker classification (transport vs application), per-call metrics with the OPERATOR-configured model label, and the budget surface unchanged, and the pre-existing decorator suites pass UNCHANGED — probe: mvn -pl infochat-llm-adapter -am test -Dtest='LlmProviderToolCallDecoratorTest,BudgetedLlmProviderTest,CircuitBreakingLlmProviderStreamingTest,MeteredLlmProviderStreamingTest,LlmObservabilityTest' is green."
  - "The response-boundary controls carry (security.md trust boundary 9, :98-132) — new cases in OpenAiCompatibleProviderToolCallTest pass, asserting an over-cap body is discarded, an impossible usage report (negative / over the generation cap / over the input ceiling) is discarded whole, and no metric label is wire-derived, with the pre-existing boundary suites passing UNCHANGED — probe: mvn -pl infochat-llm-adapter -am test -Dtest='OpenAiCompatibleProviderToolCallTest,StreamingBodyCapTest,LlmObservabilityTest' is green."
  - "LlmRouterToolTransportTest.resolutionIsFailSafeAndSticky passes — FAILURE-MODE (P8): a fake endpoint rejecting the tools field (4xx), unreachable, or returning an unparseable body resolves the TEXT transport; the resolution is computed ONCE per (task, endpoint) beside the startup-scan posture (LlmRouter.java:290-330), logged naming task/endpoint/outcome; no endpoint error string is matched (any doubt downgrades); a mid-call wire failure is a failed call under the existing per-task posture, never a silent transport switch (P9)."
  - "LlmRouterToolTransportTest.emptyClearedSetMeansTextEverywhere passes — FAILURE-MODE (P11): with the cleared-set empty (the shipped constant), every endpoint — including a tools-ACCEPTING fake — resolves TEXT; the native leg is reachable only through cleared-set membership AND a successful probe."
  - "ChatAgentTest.structuredToolCallDispatchesThroughTheBoundary passes — a stub provider serving a structured call for a registered tool dispatches through ChatToolDispatcher (the stub tool records execution), the result is wrapped UNTRUSTED_CONTENT with a per-call random marker, POST_TOOL_RESULT_INSTRUCTION appends, and the final delivered text carries no protocol fragment; an identical repeat is served from the per-turn cache (one execution); an over-cap or unknown-name structured call returns the dispatcher's typed ValidationError to the model (P3)."
  - "ChatAgentTest.textTransportBehaviorIsByteIdenticalToday passes — with the resolution on TEXT (the only resolvable state at landing), every tool-loop behavior is byte-identical: the full pre-existing ChatAgentTest suite passes UNCHANGED (§8: no modification authorized; P12)."
  - "The catalog is the single rendering source (P7 carry) — the wire 'tools' array renders from the M1-871 catalog's JSON-Schema parameters; ChatToolCatalogTest passes unchanged."
  - "docs/design/05-llm-and-embeddings.md documents the transport layer: the two text dialects (universal fallback), the detected native leg, the resolution rule (probe AND cleared-set, fail-safe to text), and the empty cleared-set posture — probe: grep -n 'cleared' docs/design/05-llm-and-embeddings.md returns the §5.4.6/§5.3 mention."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderToolCallTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/metrics/LlmProviderToolCallDecoratorTest.java
    - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterToolTransportTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java (the structured-dispatch + byte-identity cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §Failure handling
decision_refs:
  - D32
  - D56
  - D79
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-17
    verdict: REWORK
    checks:
      SPEC-TRUTHNESS: FAIL
      SECURITY: PASS
      TEST-ADEQUACY: FAIL
      MAINTAINABILITY: WARN
      SCOPE: PASS
    diff_stats: "16 files, +1282/-57"
  - round: 2
    date: 2026-08-17
    verdict: APPROVE
    checks:
      SPEC-TRUTHNESS: PASS
      SECURITY: PASS
      TEST-ADEQUACY: PASS
      MAINTAINABILITY: WARN
      SCOPE: PASS
    diff_stats: "16 files, +1386/-58 (rebased onto 16e2d028 M1-866 between rounds)"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-17
  verdict: PASS
  warnings:
    - >-
      Post-sibling line drift only: M1-870/871 landed after the
      analysis, so ChatAgent citations shifted (runToolLoop :868,
      dispatch :906, UNTRUSTED wrap :916-923) — every claim re-verified
      at the new lines. Execution note: MeteredLlmProvider's
      prompt-derived input bound includes the rendered tools length on
      the tools-bearing shape (its slack javadoc assumed no schemas on
      the wire).
  blockers: []
escalation_reason:
---

# M1-872: Native tool-call transport behind detected capability

## Context

The chat tool loop's emission grammar is model-family knowledge: gemma's
`<|tool_call>` is its chat template leaking, a model swap brings a new
dialect, and the deployment cannot pin the model. The dispatcher contract
is already transport-agnostic (ChatToolDispatcher.java:137-205 — name +
args map, all validation spec-committed), but the transport layer has only
the text leg: no wire surface carries tool declarations and none reads
structured calls back (grep-verified absent across the adapter). The
analysis (analysis_ref:) verified the layered framing survives
falsification: the text protocol is the permanent universal fallback;
observed-dialect bridges (M1-856) are the cheap per-model adaptation; a
native tools wire shape is legitimate ONLY where the serving stack AND
model support it — DETECTED, never assumed, degrading to text without
ever breaking. This ticket is that third leg, gated so that production
behavior is byte-identical today. Shared analysis: `analysis_ref:`;
spec wording landed first by M1-873; tool declarations render from the
M1-871 catalog.

## Root cause

Not a defect — a verified absence. `LlmProvider.generate` is single-string
by design (LlmProvider.java:76, javadoc :6-18 keeps schema wiring off the
SPI surface); `LlmResponse` carries text only (LlmResponse.java:22, with
finish-reason/structured parsing named "impl-side concerns for the ticket
that needs them" :12-13 — this is that ticket); the OpenAI-compat request
body carries model/max_tokens/messages only
(OpenAiCompatibleProvider.java:256-318) and the response parse reads
exactly `choices[0].message.content` (:342-372). The serving stack
capability question (does the pinned llama-server accept `tools` for a
gemma-template GGUF?) is NOT answerable from the repo — ASSUMPTION, made
non-load-bearing by the fail-safe design: any probe doubt resolves text
and the cleared-set is empty at landing.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P3, P7, P8,
P9, P10, P11, P12, P13.

- P1: wrong-tier edit — the SPI Java surface is design-tier (llm.md
  :660; the M1-847 precedent); the spec wording is M1-873's, already
  landed; no docs/spec/** path rides this diff.
- P3: every transport funnels the SAME dispatch boundary with the shared
  TurnContext — a bespoke structured-call execution path re-opens the
  M1-589 redteam class (cap+1 / duplicate execution on cache miss).
- P7: the wire tools array renders from the M1-871 catalog — no second
  description source (the M1-070 class).
- P8: detection is fail-safe — any doubt, error, non-2xx, or unparseable
  probe resolves TEXT; no endpoint error-string matching (the llama-server
  ASSUMPTION stays non-load-bearing); detection may only DOWNGRADE.
- P9: single resolution per (task, endpoint), logged — not a per-call
  fallback chain (llm.md :211-218); a mid-call failure is a failed call
  under the existing per-task posture.
- P10: the new SPI members are declared on the interface so the
  breaker/metered/budget chain forwards them; body cap, usage boundary
  checks, and the wire-label ban apply unchanged (the M1-847 controls).
- P11: capability is measurement-gated — the cleared-set is a code
  constant, EMPTY at landing (the D79 registry posture); no config knob
  asserts capability; production behavior byte-identical, pinned.
- P12: tests pin the wire shape, the resolution, and the
  byte-identical-today guarantee — nothing re-pins text-transport
  semantics owned by M1-856/M1-870 suites.
- P13: lands after M1-871 (catalog) and M1-873 (wording); ChatAgent.java
  is shared with 870/871 — sequential, never `--parallel`.

## Approach

- **Files to touch:** `files_scope` (adapter: SPI + response + provider +
  router; provider: the ChatAgent loop seam; tests; design 05).
- **Pre-decided shapes (implementation is execution, not discovery):**
  1. **SPI** — `LlmProvider` gains
     `generateWithTools(ModelTask, String systemPrompt, String userPrompt,
     List<ToolDeclaration> tools)` and `supportsToolCalls(ModelTask)`
     with honest cannot-serve defaults, declared on the interface for the
     decorator-forwarding reason `generateStreaming` documents
     (LlmProvider.java:25-39). `ToolDeclaration` carries the catalog's
     name/description/JSON-Schema parameters. `LlmResponse` gains a
     `@Nullable List<ToolCallRequest>` (record: tool name + raw args
     JSON string) — args stay a JSON STRING so the existing
     parseToolArgs/Jackson path stays the single decoder into the
     dispatcher's Map.
  2. **Wire (OpenAI-compat)** — the tools-bearing request adds
     `"tools"` (rendered from the catalog) and `"tool_choice": "auto"`
     via the existing body-assembly path; the single-string body stays
     byte-identical (a build-time-style test pins the unchanged body).
     Response parse: read `choices[0].message.tool_calls[]`
     (function.name + function.arguments) and `finish_reason`; the
     absent-content throw narrows to absent-content-AND-no-tool-calls.
     No streaming variant.
  3. **Resolution** — one bounded probe (a minimal tools-bearing request)
     per (CHAT_AGENT, endpoint), computed at startup/first-use beside
     the assertAllTasksResolve posture (LlmRouter.java:290-330), ANDed
     with membership in the code-constant cleared-set (empty at
     landing). Outcome logged once (task, endpoint host, resolved
     transport). Fail-safe on every doubt (P8); sticky (P9).
  4. **ChatAgent seam** — when the resolved transport is native,
     runToolLoop's per-iteration emission source is the response's
     structured tool calls (args JSON → parseToolArgs → the SAME
     dispatch call at ChatAgent.java:832-833); otherwise the text parse
     exactly as today. UNTRUSTED wrapping, POST_TOOL_RESULT_INSTRUCTION,
     iteration cap, base-prompt final call, strip, sanitize ordering,
     TurnContext sharing: identical on both transports.
- **Controls to preserve (§10):** the dispatch boundary and TurnContext
  sharing; the decorator chain over the new shape; body-cap/usage/
  wire-label rules; the sanitize → strip → refusal ordering; the
  LLM-unreachable degrade posture (no model-initiated tool call on the
  failed path); the streamed-surface never-transmit rule.
- **Pitfall→mitigation:** P1→diff probe; P3→acceptance item 6's cache/
  cap/ValidationError cases; P7→item 8; P8→item 4; P9→item 4's
  stickiness + failed-call posture; P10→item 2-3; P11→item 5; P12→
  items 6-7; P13→blocked_by + sequencing note.

## Definition of done

The reproduction and every named failure-mode case pass; the decorator/
boundary-control tests pass over the new shape; the resolution is
fail-safe, sticky, logged, and — with the shipped empty cleared-set —
resolves TEXT everywhere (full pre-existing ChatAgentTest suite green
UNCHANGED); design 05 documents the layer; mvn verify green from the
repo root.

## Verification

- P1 → git diff --stat names exactly files_scope — no docs/spec/**
  path.
- P3 → ChatAgentTest.structuredToolCallDispatchesThroughTheBoundary —
  one-execution cache pin, over-cap and unknown-name typed errors, no
  leak to delivered text.
- P7 → ChatToolCatalogTest unchanged + item 8.
- P8 → LlmRouterToolTransportTest.resolutionIsFailSafeAndSticky — 4xx /
  unreachable / unparseable fakes all resolve TEXT; no error-string
  branch in the diff (reviewer check).
- P9 → the same test's stickiness arm + the failed-call posture pinned
  by the extended breaker tests (item 2).
- P10 → LlmProviderToolCallDecoratorTest (decorator forwarding over
  the tools shape) + item 3's body-cap / usage-boundary / wire-label
  cases (items 2-3).
- P11 → LlmRouterToolTransportTest.emptyClearedSetMeansTextEverywhere —
  a tools-ACCEPTING fake still resolves TEXT.
- P12 → ChatAgentTest.textTransportBehaviorIsByteIdenticalToday + the
  unchanged pre-existing suites.
- P13 → blocked_by frontmatter resolves (tick-lint); same-module
  sequencing note.
- FAILURE-MODE coverage → the fail-safe arms of item 4 (4xx,
  unreachable, and unparseable fakes all resolve TEXT), item 5's
  empty-cleared-set arm (a tools-ACCEPTING fake still resolves TEXT),
  and item 6's structured-boundary arms (over-cap, unknown-name, and
  malformed tool_calls → a failed call, never a synthetic dispatch).
- acceptance item 9 → the named grep probe; item 10 → mvn verify.

## Out-of-scope

Named in `out_of_scope`: the allowlist/dispatch boundary, any spec edit
(M1-873 owns it), enabling any model (cleared-set stays empty),
Anthropic native tools, streaming+tools, and any text-transport change
(the fallback is permanent, not legacy). A future (model, transport)
clearance is a measurement campaign's verdict recorded as its own
ticket — never a mid-implementation config flip here.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-872-tool-transport-detection.md
```

## Round 1 rework

1. Finding 1: make LlmRouter.resolveToolTransport (:318-341) log one line
   naming task, endpoint host and outcome on every resolution exit —
   cleared-set miss (:320-321), cannot-serve (:324-325), and probe verdict
   (:337-340) — evaluated via the new
   LlmRouterToolTransportTest.resolutionLogsTaskEndpointAndOutcome arm
   asserting the captured line names task, mock host:port and outcome on a
   TEXT-downgrade resolution.
2. Finding 2: add `assertEquals(1, receivedBodies.size(), ...)` after the
   sticky re-query at LlmRouterToolTransportTest.java:107-109, evaluated
   via `mvn -pl infochat-llm-adapter -am test -Dtest='LlmRouterToolTransportTest'`
   green with the new assertion present in the diff.
