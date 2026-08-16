---
id: M1-856
title: "Tool prompt: worked example + native-dialect bridge"
status: done
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  ChatAgentTest#aNativeToolCallEmissionIsBridgedIntoDispatch (written and
  run RED at start; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/tool-loop-hardening.md). Probe of today's
  wrong behavior: grep -n 'TOOL_CALL_PATTERN'
  infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  shows the single-grammar pattern (:64-65) whose runToolLoop matcher
  returns unmatched text verbatim (:795-797) — so gemma's observed native
  emission '<|tool_call>call:searchPosts {…}'
  (docs/measurement/direct-chat-e2e.md:197-200, the tr t02 G1-HARD+G6
  collapse; same shape in
  .bench/direct-chat-e2e/results/ab-english-query/baseline/{en,tr}.jsonl
  t02 and spike-h1h2/h1/en.jsonl t02/t08) is delivered to the user
  instead of dispatched, and gemma lands 0/7 expected calls in every
  language (direct-chat-e2e.md:169-171).
analysis_ref: docs/plan/m1/tick-analysis/tool-loop-hardening.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    REPLY_LANGUAGE_DIRECTIVE and every mode/direct-mode concept — M1-845
    (spec amendment + wording review) and M1-848 (wiring + registry) own
    that surface; the English-plane sentence added here names TOOL
    ARGUMENTS and TOOL RESULTS only, never reply language (P7).
  - >-
    SemanticSearchTool, QueryAnchorTranslator, QueryTranslationCache and
    the whole M1-746 anchor leg — the prompt AGRES with the tool-side
    anchor (queries are anchored at the tool, D58); it never duplicates it
    and the boundary files are untouched (P9).
  - >-
    The tool allowlist in ANY form: ChatToolRegistry.TOOL_NAMES, the
    dispatcher map, the security.md §Prompt-injection defenses table. No
    tool is added, renamed, or re-described beyond the existing parameter
    shapes; the allowlist is closed at spec level (P1).
  - >-
    LlmOutputSanitizer and its pass set — the sanitizer is untouched; the
    dialect strip lands in ChatAgent's own stripToolCalls (the
    security.md :772-777 protocol-token detector site), preserving the
    sanitize→strip→refusal order (P5).
  - >-
    Epistemic-stance prompt work (t05 multi-step chaining, t06
    check-before-claiming-absence, t08 two-fetch comparison) — the residual
    class that stayed at zero in every spike arm; it is measured by
    M1-858, not prompt-engineered here.
  - >-
    Any docs/spec/** edit (design-tier change only) and any re-test of the
    ab-english-query negative (a prompt-only sentence does not unlock
    calling — 0 calls in both arms; do not re-test, P8).
acceptance:
  - "ChatAgentTest.aNativeToolCallEmissionIsBridgedIntoDispatch (the reproduction, written and run RED at start) passes — a stub provider emitting the OBSERVED native shapes (no-closer, '<tool_call|>' closer, and the spoofed '<<<END id=\"bench-turn\">>>' closer, per the spike data) for a registered tool dispatches through ChatToolDispatcher (the stub tool records execution), the tool result is fed back into the conversation, and the final delivered text carries no dialect marker."
  - "The bridged call rides the unchanged dispatch boundary — ChatAgentTest.aRepeatedBridgedNativeCallIsServedFromThePerTurnCache passes: an identical repeat native call within one turn executes the tool exactly once (the TurnContext cache), and an over-cap or unknown-name native call returns the dispatcher's typed ValidationError to the model exactly as the shipped dialect does."
  - "ChatAgentTest.residualNativeDialectIsStrippedFromFinalReplies passes — FAILURE-MODE (P5): a final reply (both the no-match return path and the iteration-cap final call) containing a balanced native fragment has it removed exactly, an unbalanced fragment drops through end-of-text, and the strip still evaluates POST-SANITIZE text (ordering pinned: sanitize → strip → refusal intercept, ChatAgent.java:537-557)."
  - "ChatAgentTest.proseQuotingTheDialectOpenerIsNotDispatched passes — FAILURE-MODE (P6): prose mentioning the opener without a balanced '{…}' args fragment is returned as ordinary text (no dispatch; no strip beyond genuine fragments)."
  - "TOOL_INSTRUCTIONS gains (a) one worked example call using a real registry tool with real argument names (e.g. searchPosts {\"tags\": [\"zcash\"], \"window\": \"P7D\"}) that the SHIPPED matcher parses — asserted by ChatAgentTest.workedExampleLineParsesWithTheShippedMatcher — and (b) one sentence stating that tool arguments (queries, tags) are always written in English and tool results come back in English, whatever language the conversation is in; the sentence names NO reply language (P7) and agrees with the query-anchoring posture of docs/spec/security.md §Prompt-injection defenses (LLM call sites) — the retrieval plane the M1-746 anchor already enforces at the tool (P9)."
  - "Every pre-existing instruction/loop test passes UNCHANGED — toolInstructionsMatch*Params, the registry-completeness walk, finalCallOmitsToolInstructions, the ChatToolDispatcher/Registry suites (§8: this ticket authorizes NO pre-existing test modification; P2, P10)."
  - "ChatAgentTest.unknownToolInNativeDialectYieldsValidationErrorNotLeak passes — FAILURE-MODE (P3): a native call naming an unregistered tool produces the dispatcher's 'Unknown tool' ValidationError for the model and never reaches the delivered text."
  - "REPLY_LANGUAGE_DIRECTIVE is byte-unchanged — probe: grep -n 'Always write your reply in English' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java returns the untouched :201-205 block; the anchor files are untouched — probe: git diff --stat shows no SemanticSearchTool/QueryAnchorTranslator/QueryTranslationCache path."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 documents the two accepted emission dialects, the opener+balanced-brace grammar (closers unreliable), the earliest-match precedence, and the strip carry — probe: grep -n 'tool_call' docs/design/05-llm-and-embeddings.md shows the dialect description in the chat-agent section."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java (the six new named cases above)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
decision_refs:
  - D58
  - D21
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
    date: 2026-08-16
    verdict: APPROVE-WITH-FIXES
    checks: {SPEC-TRUTHNESS: PASS, SECURITY: PASS, TEST-ADEQUACY: PASS, MAINTAINABILITY: WARN, SCOPE: PASS}
    diff_stats: "5 files, +257/-22 (ChatAgent.java +70, ChatAgentTest.java +170, design 05 +15)"
    fix: "comment-only reword of the stale iteration-cap comment (ChatAgent.java:850-851)"
    fix_probes: "grep 'cannot emit tool-call patterns' → no match; grep 'spontaneous native-dialect' → :852; mvnw -pl infochat-provider -am test-compile → BUILD SUCCESS"
    fixes_tree: 281e8a1820d7eee8d007ce41249878331b4c2478
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-16
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-856: Tool prompt: worked example + native-dialect bridge

## Context

The M1-844 campaign measured gemma-4-26b-a4b at 0/7 expected tool calls in
every language (docs/measurement/direct-chat-e2e.md:169-171) while the
model demonstrably TRIES to call: the ab spike's untouched baseline arm
shows spontaneous native-dialect attempts (`<|tool_call>call:searchPosts
{…}`, ab-english-query/baseline/{en,tr}.jsonl t02), and the campaign's one
protocol collapse (tr t02) is exactly such an emission delivered to the
user (:197-200). The spike-h1h2 arms proved the two levers this ticket
ships: a worked example in TOOL_INSTRUCTIONS unlocked 0→4 working calls in
the shipped format; additionally accepting the native dialect converted
format slips into working calls (combined arm 5/15, one verified
`"native": true` execution) with ZERO false calls on the t07 control in
every arm. Shared analysis: `analysis_ref:`.

## Root cause

The shipped text protocol is a foreign dialect for gemma. `TOOL_CALL_PATTERN`
(ChatAgent.java:64-65) recognizes exactly one grammar; `runToolLoop` returns
unmatched text verbatim (:795-797), so native attempts either leak (final
replies) or are silently abandoned — 0/7 expected calls, mean iterations
0.0. The model is not incapable: when the spike's harness accepted its
native emission, tool choice, argument correctness (args English), result
consumption, and second-call chaining were all right. Proven levers:
teach the format by example; accept the observed native opener
`<|tool_call>call:NAME {json}` anchored on the opener + balanced braces —
the closer is UNRELIABLE in observed data (`<tool_call|>`, a spoofed
harness delimiter, or nothing; analysis Ground truth).

## Pitfalls

Numbered per the analysis document; this ticket carries P1–P10, P15.

- P1: wrong-tier edit — the emission grammar and instruction wording are
  design-tier; no docs/spec/** edit rides this diff (the allowlist is
  untouched).
- P2: pre-existing test pins — toolInstructionsMatch*Params, the
  registry-completeness walk, finalCallOmitsToolInstructions keep the
  instruction block honest; none is modified.
- P3: the bridge must feed (name, args) into the SAME
  ChatToolDispatcher.dispatch path (allowlist, caps, cache, per-turn
  budget) — a bespoke execution path re-opens the M1-589 redteam class.
- P4: the native grammar comes from OBSERVED emissions (opener + balanced
  braces; no closer requirement) — a symmetric-closer grammar misses the
  spoofed/no-closer cases the spike recorded.
- P5: §10 control carry — stripToolCalls must strip the native dialect
  from FINAL replies (the tr t02 G1-HARD+G6 leak class), same
  balanced/unbalanced semantics, post-sanitize evaluation.
- P6: dual-dialect precedence — earliest match position wins; quoting
  prose is not a call; no-match return stays byte-identical.
- P7: the English-plane sentence states TOOL-PLANE language only;
  REPLY_LANGUAGE_DIRECTIVE stays the single reply-language source
  (M1-845/848 own its wording).
- P8: the sentence is NOT a calling lever (ab negative result: 0→0 both
  arms) — it rides as anchor agreement; never claimed or measured as a
  lever.
- P9: agree with the M1-746 tool-side anchor, never duplicate it; the
  anchor files are untouched.
- P10: the worked example uses a real tool with real argument names the
  shipped matcher parses (the M1-070 lesson).
- P15: tests pin only this ticket's end state — nothing a sibling is
  mandated to change (M1-857's wording sites, M1-858's no-code campaign).

## Approach

- **Files to touch:** `files_scope` — ChatAgent.java (TOOL_INSTRUCTIONS
  constant; a native-dialect pattern beside TOOL_CALL_PATTERN with
  earliest-match integration in runToolLoop; stripToolCalls extension),
  ChatAgentTest.java (new cases only), design 05 §5.4.6 (two-dialect
  tool-loop description).
- **Steps, in implementation order:**
  1. Write the reproduction RED (native emission → no dispatch today; the
     strip test red on the unstripped dialect).
  2. Add the native pattern (`<\|tool_call>\s*call:(\w+)\s*(\{)` + the
     existing matchBrace scan) and integrate earliest-match-wins into
     runToolLoop; the extracted (toolName, argsJson) goes through the
     UNCHANGED dispatch call and result wrapping (P3).
  3. Extend stripToolCalls to the native opener with identical
     balanced/unbalanced semantics (P5); keep the sanitize→strip→refusal
     order.
  4. Add the worked example line and the tool-plane English sentence to
     TOOL_INSTRUCTIONS (P7, P10); keep every existing tool line verbatim
     (P2).
  5. Update design 05 §5.4.6.
- **Controls to preserve (§10):** the dispatch boundary in full
  (ChatToolDispatcher.java:137-205), the TurnContext sharing between
  pre-fetch and loop, the [REFUSAL: intercept, the emptied-reply degrade,
  the CHAT_MODE audit row, the sanitizer pass set — all untouched; the
  pre-existing suites pinning them stay green unchanged.
- **Pitfall→mitigation:** P1→step 4/5 touch constants + design only;
  P2→acceptance item 6; P3→step 2's unchanged dispatch call + item 2's
  cache/ValidationError cases; P4→item 1's three observed closer variants;
  P5→step 3 + item 3; P6→item 4; P7→item 5's wording constraint + item 8's
  grep; P8→ticket text (no lever claim); P9→item 8's diff probe;
  P10→item 5's real-tool example + shipped-matcher parse test; P15→test_plan
  scope.

## Definition of done

The reproduction and all six new named tests pass; the pre-existing
instruction/loop/dispatcher suites pass UNCHANGED; REPLY_LANGUAGE_DIRECTIVE
and the anchor files are byte-untouched (probes green); design 05 §5.4.6
describes the two-dialect loop; mvn verify is green from the repo root.

## Verification

- P1 → acceptance item 9's grep probe, plus git diff --stat naming
  exactly ChatAgent.java, ChatAgentTest.java, and
  docs/design/05-llm-and-embeddings.md — no docs/spec/** path in the
  diff, so the closed allowlist of docs/spec/security.md
  §Prompt-injection defenses (LLM call sites) is untouched.
- P2 → acceptance item 6 — the pre-existing tests are the pin; no
  modification is authorized.
- P3 → ChatAgentTest.aRepeatedBridgedNativeCallIsServedFromThePerTurnCache
  (one execution on repeat) and .unknownToolInNativeDialectYieldsValidationErrorNotLeak
  (typed error, no leak) — feeds the boundary hostile inputs and asserts
  the protected behavior.
- P4 → the reproduction's three closer variants — an implementation keyed
  on a symmetric closer fails the spoofed/no-closer feeds.
- P5 → ChatAgentTest.residualNativeDialectIsStrippedFromFinalReplies —
  feeds balanced and unbalanced native fragments in final replies and
  asserts removal semantics on post-sanitize text.
- P6 → ChatAgentTest.proseQuotingTheDialectOpenerIsNotDispatched — quoting
  prose returns as text.
- P7/P9 → acceptance item 8's grep/diff probes.
- P8 → no verification owed in code (an honesty constraint on ticket and
  record text; M1-858's pre-registration carries it).
- P10 → ChatAgentTest.workedExampleLineParsesWithTheShippedMatcher.
- P15 → test_plan.adds lists only this ticket's end-state pins.
- acceptance items 8–10 → the named grep probes; item 10 → mvn verify.

## Out-of-scope

Named in `out_of_scope`: the reply-language/mode surface (M1-845/848), the
M1-746 anchor leg, the tool allowlist in any form, LlmOutputSanitizer,
epistemic-stance prompt work, any spec edit, and any re-test of the
ab-english-query negative. No pre-existing test is modified (§8): if an
existing test appears to conflict, escalate — do not edit it.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-856-toolprompt-and-bridge.md
```

## Review observations

- Round 1 (recorded, TOUCHED-BY-THIS-DIFF, no DECIDE-BEFORE): the worked
  example teaches `searchPosts {"tags": ["zcash"], …}`, but "zcash" is not
  in the shipped bootstrap vocabulary (prod/config/bootstrap-sources.json
  seeds AI/Development/Claude/Security/Java/Video/Nostr;
  SearchPostsTool.validateTagsKnown rejects unknown tags with a typed
  error). On a fresh deployment whose tagger never added "zcash", the
  model's first copied example call returns "Error: Unknown tag: zcash"
  instead of results. One-line version carried into the commit body.
- Round 1 DECIDE-BEFORE disposition (user, 2026-08-16): the brace-less
  native-call residual (final reply carrying
  `<|tool_call>call:NAME` with no argument brace anywhere is delivered
  verbatim, marker included) is NOT accepted as a residual — the user
  wants the strip. Disposition: a refinement ticket (strip
  opener+`call:`+name while preserving a bare opener in prose) is to be
  filed via /tick analyze BEFORE M1-858 runs, so M1-858's G6 gate
  definition sees the decided shape. The user additionally raised the
  structured-communication idea (predefined schemas / native LLM
  tool-calling instead of a text protocol) as a candidate for its own
  analysis.
