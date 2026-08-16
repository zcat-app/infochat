---
id: M1-870
title: "Strip brace-less native tool-call markers from final replies"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  ChatAgentTest#bracelessNativeCallMarkerIsStrippedFromFinalReplies
  (to-be-written: child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/tool-transport-model-independence.md —
  /tick start converts the marker by writing the test and running it
  RED before any fix code, workflow §0). Probe of today's wrong
  behavior: feed the
  delivery path the review-observed final-reply shape
  "Here you go.\n<|tool_call>call:searchPosts" (no '{' anywhere — the
  M1-856 round-1 review finding recorded in
  docs/plan/m1/tick-tickets/M1-856-toolprompt-and-bridge.md:273-280) and
  the reply delivers verbatim, dialect marker included:
  stripToolCalls' native branch appends the opener verbatim when no
  argument brace follows (ChatAgent.java:1148-1153) — the prose carve-out
  discriminates on the brace alone and cannot tell quoted prose from a
  brace-less call attempt.
analysis_ref: docs/plan/m1/tick-analysis/tool-transport-model-independence.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    DISPATCHING brace-less emissions — the dispatch grammar's balanced-args
    requirement stays (NATIVE_TOOL_CALL_PATTERN group 2,
    ChatAgent.java:71-72; matchBrace feeds Jackson). A brace-less call
    attempt is never executed with empty args; the recorded user decision
    names STRIP only.
  - >-
    TOOL_INSTRUCTIONS, TOOL_CALL_PATTERN, earliestToolCallMatch,
    runToolLoop, and the SHIPPED dialect's strip semantics (line-scoped
    brace search, drop-through-end on a brace-less shipped fragment,
    ChatAgent.java:1137-1156) — this ticket changes the NATIVE branch's
    no-brace arm and brace window only.
  - >-
    The tool allowlist in any form (ChatToolRegistry.TOOL_NAMES, the
    dispatcher map, the security.md table) and LlmOutputSanitizer and its
    pass set — the sanitizer is untouched; the strip stays in ChatAgent's
    own stripToolCalls (the security.md :772-777 protocol-token detector
    site), preserving the sanitize→strip→refusal order.
  - >-
    Any docs/spec/** edit (design-tier change only — P1) and any
    re-measurement: verification is unit-level against the extended G6
    definition (delivered dialect marker = defect); the
    ab-english-query negative is never re-tested (P15).
  - >-
    The transport architecture (single-source catalog, native wire leg,
    spec record) — M1-871/M1-872/M1-873 own it; nothing here constrains
    or pre-empts their shapes.
acceptance:
  - "ChatAgentTest.bracelessNativeCallMarkerIsStrippedFromFinalReplies (the reproduction, written and run RED at start) passes — a final reply 'Here you go.\n<|tool_call>call:searchPosts' (no '{' anywhere) delivers with the opener, 'call:' and name gone and the surrounding prose preserved; no dialect marker reaches the delivered or persisted text."
  - "ChatAgentTest.bracelessTokenStripRemovesExactlyOpenerCallAndName passes — FAILURE-MODE (P4): the strip span is exactly opener + optional whitespace + 'call:' + name chars; a reply 'Answer.\n<|tool_call>call:searchPosts\nMore prose here.' keeps 'More prose here.'; the truncated form '<|tool_call>call:' (opener + call: + empty name) strips too — the prose carve-out keys on NO 'call:' after the opener, per the recorded user decision."
  - "ChatAgentTest.bareOpenerInProseStaysByteIdentical passes — FAILURE-MODE (P6): prose quoting the opener with no 'call:' after it round-trips through the delivery path byte-identical (no dispatch, no strip); the pre-existing proseQuotingTheDialectOpenerIsNotDispatched passes UNCHANGED beside it."
  - "ChatAgentTest.bracelessTokenDoesNotSwallowALaterUnrelatedBrace passes — FAILURE-MODE (P6): a reply 'A <|tool_call>call:searchPosts then {json} later' strips only the token; 'then {json} later' survives — the native brace window is the grammar's own (after the name, whitespace only), not indexOf-from-marker."
  - "ChatAgentTest.bracelessTokenAssembledBySanitizationIsStripped passes — FAILURE-MODE (P5, the M1-791 family): a brace-less token present only in the SANITIZED text (canonical-form route via the sanitizerOutput test seam, mirroring ChatAgentTest.java:451) is stripped — the recognizer evaluates post-sanitize bytes like every protocol-token detector."
  - "The balanced/unbalanced semantics of BOTH dialects are unchanged — ChatAgentTest.residualNativeDialectIsStrippedFromFinalReplies passes UNCHANGED (balanced removed exactly, unbalanced drop-through-end, post-sanitize ordering pin)."
  - "Every pre-existing instruction/loop/dispatcher test passes UNCHANGED — toolInstructionsMatch*Params, everyRegisteredToolIsAdvertised, workedExampleLineParsesWithTheShippedMatcher, finalCallOmitsToolInstructions, the M1-856 native suite, the ChatToolDispatcher/Registry suites (§8: this ticket authorizes NO pre-existing test modification; P2)."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 records the three-way rule — balanced fragments removed exactly, unbalanced through end-of-text, and a brace-less opener+call:+name token stripped exactly with following prose preserved while a bare opener (no 'call:' after it) stays quoted prose — probe: grep -n 'quoted prose' docs/design/05-llm-and-embeddings.md returns the updated two-dialect paragraph (:755-768 today)."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java (the five new named cases above)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
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
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-870: Strip brace-less native tool-call markers from final replies

## Context

M1-856 accepted gemma's native emission dialect
(`<|tool_call>call:NAME {json}`, opener + balanced brace, no closer
requirement) and taught `stripToolCalls` to strip both dialects from final
replies post-sanitize — but its prose-preservation carve-out discriminates
on the ARGUMENT BRACE alone. A final reply carrying the opener + `call:` +
name with no `{` anywhere is delivered verbatim, marker included: the
review's observed probe "Here you go.\n<|tool_call>call:searchPosts" (the
M1-856 round-1 finding, dispositioned by the user as NOT a residual — strip
opener+`call:`+name while preserving a bare opener in quoted prose). The
residual never materialized in the M1-858 re-measure (brief-supplied; the
branch's record is not in this checkout — analysis Ground truth D1), so
this is insurance against a future model or prompt slip: verification is
unit-level against the extended G6 definition (delivered dialect marker =
defect), and no re-measurement is owed (P15). Shared analysis:
`analysis_ref:`.

## Root cause

Verified: `stripToolCalls`' native branch searches the args brace with
`text.indexOf('{', marker)` — anywhere after the marker
(ChatAgent.java:1137) — and with no line/window constraint on the native
arm (:1139); when NO brace exists it appends the opener verbatim and scans
on (:1148-1153), so a brace-less call attempt is indistinguishable from
quoted prose and ships verbatim. Two consequences, both fixed here: the
delivered-marker defect (the reproduction), and an over-strip — a
brace-less token followed by an unrelated balanced brace later in the reply
makes the strip remove marker-through-brace, swallowing the intervening
prose. The dispatch grammar requires the brace, so a brace-less emission
never dispatches (:816-818 returns it as final text): the strip is the
only handling it gets, and the fix is strip-side only.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P4, P5,
P6, P12, P15.

- P1: wrong-tier edit — the strip match grammar is design-tier; no
  docs/spec/** edit rides this diff (the allowlist and dispatch
  validation are untouched; the security.md :772-777 sentence names the
  detector's ORDERING, not a closed grammar — the M1-856 precedent).
- P2: pre-existing test pins — the M1-856 native suite, the instruction
  tests, and the dispatcher suites stay green UNCHANGED; none is
  modified (§8).
- P4: the recognizer mirrors the ACCEPTED grammar minus the brace:
  `<|tool_call>\s*call:\w+` (window consistent with
  NATIVE_TOOL_CALL_PATTERN :71-72). No closer, no model-card shape;
  `call:` with an empty name still strips (the carve-out keys on "no
  `call:` after the opener").
- P5: strip semantics carry + post-sanitize ordering — balanced removed
  exactly, unbalanced drop-through-end, the new token stripped exactly
  with following prose preserved; all evaluated on SANITIZED text
  (sanitize → strip → refusal intercept, ChatAgent.java:557-577, the
  M1-791 rule).
- P6: over-strip and precedence — earliest-match precedence stays; the
  native brace window tightens to the grammar's own; the bare opener
  stays byte-preserved; the shipped dialect's line-scoped semantics are
  untouched.
- P12: tests pin only this ticket's end state — nothing M1-871's
  rendering or M1-872's loop seam is mandated to change (the strip and
  its tests survive all siblings verbatim).
- P15: no re-measurement owed; the ab negative is never re-tested.

## Approach

- **Files to touch:** `files_scope` — ChatAgent.java (stripToolCalls'
  native branch only), ChatAgentTest.java (new cases), design 05 §5.4.6
  (the two-dialect paragraph's last sentence, :767-768).
- **Steps, in implementation order:**
  1. Write the reproduction RED (brace-less final reply → verbatim
     delivery today), plus the four failure-mode cases red where they
     expose the over-strip.
  2. In stripToolCalls' native branch: recognize the brace-less token
     with a compiled pattern `<\|tool_call>\s*call:\w*` anchored at the
     marker; strip exactly its span and continue scanning (following
     prose preserved). A bare opener (no `call:` after it) keeps the
     current preserve-and-scan-on behavior byte-for-byte.
  3. Tighten the native brace window: a brace counts as the call's args
     only when it sits where the grammar admits it (after the matched
     name, whitespace only). A brace outside the window does not start a
     fragment — the token is brace-less and step 2's rule applies; a
     brace inside the window keeps the balanced-removed-exactly /
     unbalanced-drop-through-end semantics unchanged.
  4. Update design 05 §5.4.6's closing sentence to the three-way rule.
- **Controls to preserve (§10):** the dispatch boundary in full
  (nothing in it changes); the sanitize → strip → refusal-intercept
  ordering; the `[REFUSAL:` intercept, emptied-reply degrade, translate
  leg, deferred commit of `approved`; the sanitizer pass set; the
  streamed-surface never-transmit rule — all untouched, pinned by the
  pre-existing suites.
- **Pitfall→mitigation:** P1→step 4 is the only doc touch (design-tier);
  P2→acceptance item 7; P4→step 2's grammar + item 2's boundary case;
  P5→step 2/3 semantics + items 1, 5, 6; P6→step 3 + items 3, 4;
  P12→test_plan scope; P15→no campaign work anywhere.

## Definition of done

The reproduction and the four new failure-mode cases pass; the
balanced/unbalanced semantics and every pre-existing suite pass UNCHANGED;
design 05 §5.4.6 carries the three-way rule; mvn verify is green from the
repo root.

## Verification

- P1 → git diff --stat names exactly ChatAgent.java, ChatAgentTest.java,
  docs/design/05-llm-and-embeddings.md — no docs/spec/** path.
- P2 → acceptance item 7 — the pre-existing tests are the pin; no
  modification is authorized (escalate if one appears to conflict).
- P4 → ChatAgentTest.bracelessTokenStripRemovesExactlyOpenerCallAndName —
  feeds the truncated-`call:` boundary and asserts the exact strip span.
- P5 → the reproduction (delivered bytes) +
  ChatAgentTest.bracelessTokenAssembledBySanitizationIsStripped (the
  canonical-form route through the sanitizerOutput seam) +
  item 6 (residualNativeDialectIsStrippedFromFinalReplies UNCHANGED).
- P6 → ChatAgentTest.bareOpenerInProseStaysByteIdentical +
  ChatAgentTest.bracelessTokenDoesNotSwallowALaterUnrelatedBrace +
  proseQuotingTheDialectOpenerIsNotDispatched UNCHANGED.
- P12 → test_plan.adds lists only this ticket's end-state pins.
- P15 → ticket text only (an honesty constraint; no campaign artifact).
- FAILURE-MODE coverage → items 2-5 feed the strip's own production
  code the hostile and edge shapes: a brace-less token assembled by a
  sanitize deleting/canonicalizing pass (item 5, the M1-791
  canonical-form route), a truncated `call:` with an empty name
  (item 2), a bare opener in quoted prose (item 3), and a later
  unrelated brace that must not be swallowed (item 4) — a recognizer
  keyed on a closer or an indexOf-from-marker brace window fails
  these red.
- acceptance item 8 → the named grep probe; item 9 → mvn verify.

## Out-of-scope

Named in `out_of_scope`: dispatching brace-less emissions, the shipped
dialect's strip semantics, TOOL_INSTRUCTIONS and the dispatch patterns,
the tool allowlist, LlmOutputSanitizer, any spec edit, any re-measurement,
and the transport architecture (M1-871/872/873). No pre-existing test is
modified (§8): if an existing test appears to conflict, escalate — do not
edit it.

## Census

The defect class is "protocol-token detector mishandling a dialect
fragment shape". Re-runnable enumeration (the M1-791 census grep):
`grep -rn '\[REFUSAL:\|TOOL_CALL\|tool_call' infochat-provider/src/main/java infochat-collector/src/main/java`.

| Site | Disposition |
|---|---|
| ChatAgent.java:1113-1159 (stripToolCalls, both dialects) | FIXED here — native branch |
| ChatAgent.java:64-72 (the two dispatch patterns) | out-of-scope — dispatch grammar requires the brace by design |
| ChatPromptBuilder.java (instruction literals) | out-of-scope — the prompt teaches, the strip guards |
| Stage1RegexSet.java (collector ingest catalogue) | out-of-scope — different surface, own boundary (§Ingest pipeline; M1-791 disposed) |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-870-braceless-native-strip.md
```
