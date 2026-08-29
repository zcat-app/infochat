---
id: M1-939
title: "Pin the native reply language per-turn and after fold-backs"
status: done
created: 2026-08-26
last_updated: 2026-08-29
flow: tick
reproduction: >-
  ChatAgentReplyModeTest#nativeTurnCarriesTheScopeLanguagePinOnThePerTurnTail,
  RED against unmodified main (14bad7dd) on 2026-08-29 along with its four
  siblings — all five fail on pin presence, the rest of the module green
  (2066 run, 5 failures; log .scratch/tick-red-M1-939.log) (child of a 2+
  decomposition, analysis
  docs/plan/m1/tick-analysis/answer-synthesis-language-pinning.md). The
  wrong behavior
  it states: on a NATIVE-mode turn the scope-language contract appears
  ONLY in the system prompt — the assembled user prompt carries NO
  language pin anywhere in its tail. Verified in-tree today: the native
  directive is appended to the system prompt alone
  (ChatAgent.java:651-653 selects it, :662 appends it —
  `baseSystemPrompt = prompt.systemPrompt() + languageDirective`), the
  per-turn tail assembles only `prompt.userPrompt() + semanticBlock +
  effectiveDirective` (:687-690) with effectiveDirective ∈
  {CLARIFY, AFFORDANCE, DETERMINISTIC_DELIVERY, ""} (:567-638, :668-671)
  — none mentions language — and the tool-loop fold-back appends only
  `POST_TOOL_RESULT_INSTRUCTION` after the UNTRUSTED_CONTENT close
  (:1030-1049). Observed live (user, multiple cases, 2026-08-26, on the
  prod native-mode deployment): Czech-scope replies arrive in English —
  the entire context (system prompt, tool instructions ":120-122", the
  retrieved-posts JSON) is English, and the one Czech-contract sentence
  is the furthest-from-generation, weakest position; no language
  verification exists downstream (translate leg at :731-736 is
  TRANSLATE-only; spec-stated residual, docs/spec/llm.md:328-333).
analysis_ref: docs/plan/m1/tick-analysis/answer-synthesis-language-pinning.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyModeTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    TICKET A (deferred, binding user decision): mechanical drift detect +
    repair — marker-char detector, TranslationPipeline fallback leg, any
    D29 clarifying sentence, any ChatLiveTextStreamer interaction. NOT
    implemented here; the design wrinkles live in the analysis Appendix A
    and the gate opens only on THIS ticket's owner-run probe record
    showing persistent leaks.
  - >-
    TRANSLATE-MODE anything: no pin, no byte change — translate mode keeps
    the pipeline leg and its pins (ChatAgentReplyLanguageTest:129/:149,
    aTranslateScopeKeepsTodaysBehaviourExactly, M1-938's
    nonTemporalTurnIsByteIdenticalToThePreChangeShape) byte-identical.
  - >-
    The streamer (ChatLiveTextStreamer) — pins apply BEFORE generation;
    no live-text change of any kind.
  - >-
    The tool-instruction table, ChatToolCatalog, wire declarations, and
    the system-prompt TEMPLATE (ChatPromptBuilder) — the pin is a
    ChatAgent-assembled per-turn/fold-back sentence, not scaffolding
    text; renderedInstructionTableIsByteIdentical and every
    ChatPromptBuilderTest pin pass UNMODIFIED.
  - >-
    Any spec edit (docs/spec/**) — llm.md §Translation flow's residual
    sentence ("no mechanical language net") stays TRUE: a static prompt
    sentence is not a runtime check. No amendment rides this diff.
  - >-
    Bundle keys (the pin is bot instruction prose, not user-facing
    output — D43 has nothing to cover) and any sanitizer change (the pin
    carries no command token; echo-of-pin rides the existing output
    path).
acceptance:
  - "REPRODUCTION closed: ChatAgentReplyModeTest.nativeTurnCarriesTheScopeLanguagePinOnThePerTurnTail passes — a cs NATIVE turn's first-call user prompt (user-prompt capture added to the stub, item 8) contains the native pin sentence naming \"Czech\" (LANGUAGE_NAMES-resolved), positioned in the trusted tail AFTER the effective directive (and after the untrusted block's close); the companion TRANSLATE arm on the same rig asserts the pin is ABSENT from the per-turn prompt (the compose discriminator: a mutation deleting the native branch fails the first arm, a mode-agnostic pin fails the second)."
  - "Fold-back reassertion: ChatAgentReplyModeTest.nativePinIsReappendedAfterEveryToolResultFoldBack passes — one tool iteration (stub replies TOOL_CALL then final text); the SECOND call's user prompt contains the fold-back scaffold AND the pin ordered AFTER POST_TOOL_RESULT_INSTRUCTION (indexOf ordering asserted); the translate-mode arm asserts the fold-back present with NO pin. A multi-iteration variant (two TOOL_CALL replies) shows the pin after EVERY fold-back."
  - "FAILURE-MODE (ladder survival, analysis P3): ChatAgentReplyModeTest.nativePinSurvivesCompactionDrops passes — a compaction-forcing budget (the ChatAgentTest:2266-2270 corner-builder pattern) on a native turn whose semantic block the ladder DROPS still carries the per-turn pin: the pin is instruction text, never a compaction candidate (commands.md:1845-1857), and is composed into the builder's directive sizing so the estimate counts it (reviewer diff check: the build() call's turnDirective argument carries the pin bytes, and the append uses effectiveDirective + pin)."
  - "FAILURE-MODE (trusted region, analysis P4): ChatAgentReplyModeTest.nativePinSitsOutsideEveryUntrustedWrapper passes — the pin's index in the assembled prompt is GREATER THAN the index of the last <<<END id= close preceding it, on both the first call and the post-fold-back call (a mutation appending the pin inside the scaffold before the close fails it)."
  - "Uniformity (analysis P5): ChatAgentReplyModeTest.enNativeTurnCarriesThePinToo passes — an en NATIVE turn carries the English-named pin; the pin's condition is replyMode == NATIVE, never a language branch (no en special-case, no per-language byte drift)."
  - "Translate mode byte-identical: ChatAgentReplyModeTest.aTranslateScopeKeepsTodaysBehaviourExactly, ChatAgentReplyLanguageTest's system-prompt pins (:129/:149), and ChatAgentTest's directive/fold pins (:336-349, :1025, :1112-1152, :1178, :2300-2366) pass UNMODIFIED — probe: git diff names no test file outside ChatAgentReplyModeTest, and no production file outside ChatAgent.java."
  - "Seam shape (analysis P6/P8): exactly TWO injection sites exist — the per-turn tail and the fold-back scaffoldTail; runToolLoop gains the pin as a @Nullable parameter (fitWithinBudget accounts it via scaffoldTail, so the M1-918 corner tests pass unmodified); the ChatAgent constructor is unchanged; ChatLiveTextStreamer is untouched — probes: grep -c 'nativeTurnPin\\|Always write your reply' over infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java shows the pin constant(s) defined exactly once and referenced at exactly the two sites; git diff --name-only shows no ChatLiveTextStreamer.java hunk."
  - "§8-AUTHORIZED test-infra modification (engineering-rules §8; plain language): ChatAgentReplyModeTest's StubLlmProvider (:377-391) gains lastUserPrompt/allUserPrompts capture exactly as ChatAgentTest's stub already has — recording fields only, no existing assertion changes; every pre-existing test in the file passes with its intent unchanged."
  - "Design ledger (analysis P3; the M1-916 absorbed-by-headroom precedent): docs/design/05-llm-and-embeddings.md §5.4.6's prompt-budget ledger records the native-only pin cost (per-turn tail ~1 sentence on NATIVE turns + ~1 sentence per tool fold-back, both inside never-drop instruction text and the fold-back budget accounting) — probe: grep -n 'native' docs/design/05-llm-and-embeddings.md returns the ledger sentence; the §5.4.6 citation-discipline paragraph's \"REPLY_LANGUAGE_DIRECTIVE (the single source)\" clause is updated to state the native per-turn/fold-back pin relationship truthfully."
  - "OWNER-RUN live re-probe (verification ceiling, the M1-916/M1-927 posture — no unit test can prove model language behavior; phrased owner-run with a recorded outcome, never claimed as a unit result): after landing, the owner re-asks the EXACT Czech queries that failed live plus a handful of new cs-scope questions on the deployment; the before/after leak tally is recorded in the ticket/commit; ANY cs-scope reply arriving in English FAILS. The record is ticket A's gate input: A proceeds only if leaks persist (the user's binding decision, analysis Appendix A)."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentReplyModeTest.java
      — nativeTurnCarriesTheScopeLanguagePinOnThePerTurnTail (the
      reproduction, with its translate-mode companion arm),
      nativePinIsReappendedAfterEveryToolResultFoldBack (translate arm
      included), nativePinSurvivesCompactionDrops,
      nativePinSitsOutsideEveryUntrustedWrapper,
      enNativeTurnCarriesThePinToo.
  modifies:
    - >-
      ChatAgentReplyModeTest's StubLlmProvider — gains user-prompt capture
      (§8-authorized, acceptance item 8; recording fields only).
  preserves:
    - >-
      all tests currently green on main — explicitly
      ChatAgentReplyLanguageTest, ChatAgentTest's directive/fold/budget
      pins, ChatPromptBuilderTest, and (once landed) M1-938's
      nonTemporalTurnIsByteIdenticalToThePreChangeShape, unmodified.
spec_refs:
  - docs/spec/llm.md §Translation flow
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D19
  - D21
  - D29
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
    date: 2026-08-29
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "5 files, +257/-22 (1 prod ChatAgent, 1 test ChatAgentReplyModeTest, 1 design doc, ticket+board flow artifacts; owner memory edit excluded+disclosed)"
    notes: >-
      Zero rework/fix items, zero critical/high. Falsified-and-dropped:
      /lang injection into pin (LangCommandHandler closed registry gate),
      fold-back entry squeeze (fitWithinBudget prices full scaffoldTail,
      whole-entry admission), pin-comment history retelling (stable-pointer
      idiom, same shape as the M1-778 comment), sizing over-estimate on
      dropped turns (directive slot fixed in ladder remainder, mirrors the
      window-hint composition). Post-landing obligation: owner-run Czech
      leak re-probe with recorded tally (acceptance item 10, ticket A's
      gate input).
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  2026-08-29: passed — all Root cause/Approach citations re-verified on main
  post-M1-938 (line drift only, semantics exact); P1-P8+P20 all present;
  preserves traced (ChatAgentTest rig defaults TRANSLATE, InboundContext:70;
  M1-918 corner tests ride scaffoldTail in translate mode, byte-identical).
  Parallel mode refused: M1-936 in flight in infochat-provider (dirty
  .worktree/M1-936, worktree ticket status in-progress) — module overlap.
escalation_reason:
---

# M1-939: Pin the native reply language per-turn and after fold-backs

## Context

Prod runs chat in native reply mode (D79), and Czech-scope replies have
arrived in English live (user, multiple cases, 2026-08-26). The
scope-language contract rides ONE system-prompt sentence
(`nativeReplyDirective`, ChatAgent.java:251-257) against an
entirely-English context — tool instructions, retrieved JSON, scaffold
prose — and nothing re-states it at the positions nearest generation,
and nothing verifies it downstream (the spec's own accepted residual,
llm.md:328-333). The user's binding decision: strengthen the static
pinning FIRST (this ticket, zero cost, D19-safe), and defer mechanical
detect+repair (ticket A) behind this ticket's owner-run leak re-probe.
Full evidence and the falsified alternatives (system-only rewording,
back-translation, an always-translate leg) are in the analysis,
`analysis_ref:`.

## Root cause

Verified end to end (analysis Ground truth): the native directive is
appended to the system prompt only (:651-663); the per-turn tail
(:687-690) and the fold-back scaffold (:1030-1049) carry no language
bytes; the translate-mode verification leg (:731-736) does not run in
native mode. The mechanism is recency: drift pressure peaks immediately
after the model ingests a large English tool-result JSON mid-loop —
exactly where the contract is silent today. What is NOT proven: that
static re-pinning suffices behaviorally — that is the owner-run probe's
question (P7), which is also A's gate.

## Pitfalls

Numbered per the analysis document; this ticket carries P1-P8 (P20's
landing-order context applies).

- P1: recency beats a distant pin — the fix must pay the LAST position
  before generation (per-turn tail) and the post-fold-back position, not
  a longer system sentence (the rejected O-B3).
- P2: translate-mode byte identity — pins are native-only; the sibling
  and mode pins listed in acceptance item 6 stay green unmodified.
- P3: budget honesty — the per-turn pin composes into the builder's
  directive sizing (never a compaction candidate,
  commands.md:1845-1857); the fold-back pin rides scaffoldTail through
  fitWithinBudget; the design-05 ledger records the cost.
- P4: trusted-region placement — after the UNTRUSTED_CONTENT close, like
  CLARIFY/AFFORDANCE (ChatAgent.java:181-189 hygiene); no instruction
  byte inside a wrapper.
- P5: no language special-case — pin on replyMode == NATIVE uniformly
  (en-native included); a language branch is a §7 defensive shape.
- P6: exactly two injection sites — the iteration-cap final call is
  already covered (base system prompt + accumulated conversation); the
  streamer is untouched; resist a third site (§1).
- P7: verification ceiling — RED tests pin PRESENCE; the behavioral fix
  is the owner-run probe with a recorded before/after tally; A's gate is
  the user's decision on that record.
- P8: seam shape — runToolLoop takes the pin as a @Nullable parameter;
  constructor unchanged; all existing tests reach the loop via
  handleTurn (verified ChatAgentTest:2300-2366).

## Approach

Derived from `spec_refs:` — llm.md §Translation flow (:324-333) states
the mode-conditional reply-language contract this ticket implements more
robustly (the residual sentence stays true: no runtime check is added);
commands.md §Chat mode (:1845-1858) governs the budgeted,
never-drop-instruction prompt this pin rides.

- **Files to touch:** `files_scope` — one production file, one test
  file, one design doc.
- **Pre-decided shapes (implementation is execution):**
  1. `ChatAgent` gains a one-sentence native pin builder,
     `nativeTurnPin(String scopeLanguage)` — static, LANGUAGE_NAMES
     resolved (reuse the map's getOrDefault posture), wording of the
     shape: "\n\nReminder: write your reply in <name>, whatever language
     the user writes in and whatever language the posts or tool results
     are in." (EXACT wording rides the diff; the semantic elements the
     tests assert: the language name, the reply-scope demand, and the
     explicit non-English-context tolerance).
  2. `doHandle` (after step 5's build, at the :687-690 assembly): for
     NATIVE mode, append the pin to the per-turn user prompt AFTER
     effectiveDirective (and after M1-938's window hint when present),
     and compose the same pin bytes into the `turnDirective` argument
     passed to `promptBuilder.build(...)` so the ladder's estimate
     counts them (P3). TRANSLATE: byte-identical, no pin.
  3. `runToolLoop` signature gains `@Nullable String nativePin`; the
     scaffoldTail (:1035-1038) becomes `... + POST_TOOL_RESULT_INSTRUCTION
     + (nativePin == null ? "" : nativePin)` — fitWithinBudget already
     prices scaffoldTail, so the M1-918 corner behavior is unchanged in
     mechanics (fewer entries fit per fold; whole-entry admission
     intact). doHandle passes the pin only for NATIVE.
  4. Tests per `test_plan.adds`; stub capture per item 8.
  5. Design-05 §5.4.6: ledger sentence + the citation-discipline
     paragraph's single-source clause updated truthfully.
- **Steps, in order:** (1) write the five tests RED; (2) the pin builder
  + doHandle wiring; (3) the runToolLoop parameter + scaffoldTail; (4)
  stub capture; (5) design-05 sync; (6) full `mvn verify`; (7) hand the
  owner-run probe protocol to the user with its record obligation.
- **Controls to preserve (engineering-rules §10):** the reply pipeline
  is untouched beyond prompt bytes — sanitize/sanitizeStreamed, strip,
  refusal intercept, translate leg, help-block accretions, degrade,
  provenance, audit rows, deferred commit (ChatAgent.java:692-831) all
  byte-identical; D43 two-path (the pin is never bundle/translator
  surface); the wrappers and their random markers unchanged.
- **Alternatives considered (rejected, for the commit message):**
  O-B2 A-now (user-rejected: gated), O-B3 system-only rewording (pays
  the weakest position), O-B4 back-translation of posts (no gain,
  verified), O-B5 always-translate leg (that IS translate mode).

## Definition of done

The reproduction and its four siblings pass (per-turn pin present native
/ absent translate; pin after every fold-back ordered after
POST_TOOL_RESULT_INSTRUCTION; pin survives a ladder-dropped retrieval
block; pin outside every wrapper; en-native uniform); translate mode and
every pre-existing pin green unmodified; exactly two injection sites and
no streamer/constructor/catalog change (greps); the design-05 ledger and
single-source clause updated; the owner-run leak re-probe handed over
with its record obligation (A's gate input); mvn verify green from the
repo root.

## Verification

- P1 → acceptance items 1-2 (presence + ordering; a no-pin or
  system-only mutation reds them; the translate arms red a mode-agnostic
  mutation).
- P2 → the translate arms + item 6's unmodified-pin list.
- P3 → item 3's compaction arm + the M1-918 corner tests unmodified +
  the reviewer diff check on the build() sizing argument.
- P4 → item 4's wrapper-order assertion.
- P5 → item 5's en-native arm.
- P6 → item 7's grep/fence probes.
- P7 → item 10's owner-run protocol with its named FAIL condition.
- P8 → item 7's seam probes (constructor unchanged; tests via
  handleTurn).
- P20 → landing-order CONTEXT only (this ticket lands after
  M1-932/934/935/937/938, per the analysis Decomposition): no test of
  its own — its verification is the §8 fence (item 8's stub capture is
  the ONLY test-infra modification; probe: git diff names no test file
  outside ChatAgentReplyModeTest and no production file outside
  ChatAgent.java, item 6) plus test_plan.preserves keeping M1-938's
  nonTemporalTurnIsByteIdenticalToThePreChangeShape green unmodified
  once it lands.
- FAILURE-MODE coverage → items 3 (hostile budget), 4 (wrapper
  adjacency), and the translate arms of items 1-2 (edge mode) feed
  non-happy inputs and assert the protected behaviors.
- acceptance items 9/11 → the design-doc grep and mvn verify.

## Out-of-scope

Named in `out_of_scope`: ticket A in full (detector, pipeline repair,
D29 sentence, streamer interaction — deferred behind this ticket's probe
record by binding user decision); any translate-mode change; the
streamer; the tool-instruction table/catalog/system template (no
scaffolding bytes move — the pin is ChatAgent-assembled); any spec edit
(the llm.md residual stays true); bundle keys and sanitizer changes.
One test-infra modification is authorized (item 8: stub user-prompt
capture, recording fields only); every other pre-existing test passes
unmodified.

## Census

Not class-scoped: this ticket adds two prompt-injection SITES, it does
not fix or guard a class of existing defect sites. (The class it
prevents — native-mode language drift — has exactly one producer: the
prompt assembly this diff edits.)

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-939-native-reply-language-pinning.md
```
