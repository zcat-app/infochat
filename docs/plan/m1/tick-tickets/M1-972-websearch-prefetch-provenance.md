---
id: M1-972
title: "Deterministic KB-miss web pre-fetch + web-grounded provenance"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  ChatAgentWebGroundingTest#kbMissTurnInjectsWebBlockAndShipsWebGroundedNotice
  (to-be-written; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/websearch-grounding-lane.md; converted at
  /tick start: written first, run RED — grep -n 'websearch\\|WebSearch'
  infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  returns NO match (verified 2026-09-01): the turn flow at
  ChatAgent.java:555-589 runs the semantic pre-fetch and nothing else,
  so a KB-miss turn (both arms empty) folds in NO web block, spends no
  web budget, and ships the general-knowledge notice (:877-888) — the
  spec'd fallback (security.md:329) with no way to go online). The
  wrong behavior it states: on a KB-miss the agent cannot ground the
  reply on the live web even when the lane's client, fusion, and spec
  authorization (M1-969..971) are all in place.
analysis_ref: docs/plan/m1/tick-analysis/websearch-grounding-lane.md
blocked_by: [M1-965, M1-971]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/bundles/es.properties
  - infochat-provider/src/main/resources/bundles/ru.properties
  - infochat-provider/src/main/resources/bundles/tr.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentWebGroundingTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBudgetTest.java
  - docs/spec/commands.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The tool surface (analysis P2/P3): NO ChatToolRegistry, catalog,
    dispatcher, or TOOL_INSTRUCTIONS change — the lane stays a
    dispatch-layer service; the byte-pinned instruction table does not
    move and the closed allowlist stays eight names. The model-elected
    arm (T1) is a separately decided later ticket.
  - >-
    Any client/fusion behavior — consumed from M1-970/971 through their
    public seams (gate, composer, fusion); this ticket wires the TURN
    only: trigger, injection, ladder slot, notice.
  - >-
    The corpus lane's provenance accounting — M1-965's admitted-set
    split (landed, blocked_by) is consumed, never re-derived here; the
    corpus-grounded and web-grounded sets stay SEPARATE counters fused
    only at the notice decision.
  - >-
    The "both" compositional state's bundle key (analysis P17): the
    deterministic-arm trigger is exclusive (web fires only on KB-miss), so
    corpus-and-web-in-one-turn is structurally unreachable — adding a
    key for an unreachable state is dead code (§7). The composition
    RULE is stated in the spec/design text; the key lands with T1.
  - >-
    The eval lane, digest, /summary, and every non-chat surface; the
    sanitizer/strip/refusal output regime (the block is prompt-side
    only); any translation change (the notice takes the bundle path,
    never the translator — D43).
  - >-
    Snippet-to-typed laundering (analysis P20's ban): NO tool parses
    web-snippet text into a typed structured value — the fallback
    ladder lives entirely at the agent/dispatch layer, and the diff
    names no tool-file hunk that consumes snippet bytes; K1's
    typed-output promise stays structural.
  - >-
    Timezone/hint changes to the corpus lane (M1-937/938's windowHint
    and M1-941's synthesis sentences pass UNMODIFIED — contains-based
    pins absorb the new block; rewording any of them is a hurdle, not
    scope).
acceptance:
  - "REPRODUCTION closed: ChatAgentWebGroundingTest.kbMissTurnInjectsWebBlockAndShipsWebGroundedNotice passes — a scripted KB-miss turn (empty semantic pre-fetch) with the web seam stubbed to a two-source fused block asserts: the web lane fired EXACTLY once (one gate check, one composer call), the assembled user prompt carries the web block INSIDE a per-call-random-marker <<<UNTRUSTED_CONTENT id=…>>> wrapper plus the citation instruction (cite each source by its bare URL exactly as the block provided, never invent or modify — the M1-857/M1-941 posture), and the shipped notice is the web-grounded key with the count of DISTINCT admitted web sources. Mutations failing it: firing on a grounded turn, injecting without the wrapper, or shipping the general-knowledge notice."
  - "TRIGGER DISCIPLINE (analysis P5/P17 — the corpus-miss leg of the fire condition is the spec'd KB-miss condition, no free variable): ChatAgentWebGroundingTest.groundedTurnNeverFiresTheWebLane and ChatAgentWebGroundingTest.breakerOpenOrDisabledTurnSkipsTheWebLane pass — a turn whose semantic pre-fetch is non-empty AND whose typed tools all succeeded issues ZERO web-seam calls (grounded and marginal/clarify turns alike); a turn with the chat breaker wouldShortCircuit (stubbed, the ChatAgent.java:584-589 gate) or a disabled/opted-out gate issues ZERO calls; every such skip degrades to today's behavior byte-identically (general-knowledge notice, no block)."
  - "INTENT GATE (analysis P19, failure-mode — the waste asymmetry: skipping search on a real question is a mild failure, firing on a non-question is pure spend and pure egress, so the gate errs toward NOT searching): ChatAgentWebGroundingTest.nonQuestionTurnNeverFiresTheWebLane passes — a greeting/small-talk corpus-miss turn (scripted empty pre-fetch, below the substance minimum or in the small-talk lexicon, classified with the clarify-turn signal reuse) issues ZERO web-seam calls and ships today's general-knowledge behavior; the companion arms ChatAgentWebGroundingTest.shortAnaphoricFollowUpPassesTheGate and ChatAgentWebGroundingTest.rhetoricalQuestionPassesTheGate pass — 'and what about Brno?' and a rhetorical question DO fire the lane (the classes length heuristics wrongly kill; the M1-968 confusion matrix is the calibration record, and the thresholds land as fixed code constants)."
  - "FALLBACK LADDER, AGENT SIDE (analysis P20, failure-mode): ChatAgentWebGroundingTest.typedToolNoDataOutcomeExtendsTheFireCondition passes — a scripted typed-structured tool degraded/no-data outcome observed at the dispatch layer fires the lane exactly once even though the semantic pre-fetch was non-empty (the P20 extension; the tool itself parses nothing — probe: git diff names no chat/tool hunk consuming snippet bytes); ChatAgentWebGroundingTest.laneFailureShipsHonestRefusalNotice passes — with the web seam stubbed to typed failure OR an exhausted budget on a turn that FIRED, the reply ships the honest-refusal bundle key ('cannot answer from available sources, try asking differently') resolvable in all five locales, carries NO provenance notice (a degrade reply), and the fallback sub-cap is drawn, not the primary counter (a hard-down primary must not drain the credit)."
  - "EGRESS QUERY DISCIPLINE (analysis P4, failure-mode): ChatAgentWebGroundingTest.egressQueryCarriesCurrentMessageOnlyNeverHistory — a conversation whose earlier turns and memory carry distinctive marker strings, and whose current message carries its own marker, asserts the composer received ONLY the current message's text (truncated at the corpus lane's 500-char bound): neither history nor memory markers appear in the composed query; the search_lang is the scope's declared language. A history-wrapping mutation fails on the marker."
  - "LADDER SLOT + BUDGET (analysis P11): ChatPromptBudgetTest (with §8-AUTHORIZED fixture recalibration only, assertions unchanged — the M1-941 precedent) and a new ChatPromptBuilderTest arm pin the web block's compaction position: dropped WHOLE immediately after the deterministic retrieval block and before memory; the instruction/untrusted scaffolding never drops; a ladder-dropped web block produces the general-knowledge notice (never a web count the model did not see — the M1-965 admitted-set discipline extended to the web set); a ladder-dropped web block on a KB-miss turn is indistinguishable in the notice from a lane that never fired."
  - "NOTICE TRUTHFULNESS (analysis P10; D31/D43): ChatAgentWebGroundingTest.webGroundedNoticeInterpolatesCountOnlyInEveryLocale — the notice interpolates the DISTINCT admitted source count only (never a title, URL, or snippet byte); BundleKeys gains the web-grounded key resolved in ALL FIVE locales (en, cs, es, ru, tr — the LanguageRegistry set; MessageFormat choice pattern mirroring reply.chat.provenance.grounded, en.properties:641); a scope on /lang cs receives the cs bundle string untranslated by the pipeline; clarify turns and every degrade/rejection path (unavailable, in-flight, ceiling-gated, refusal, prompt-exceeded, /stop) carry NO notice."
  - "CORPUS GROUNDING UNCHANGED (the M1-965 seam, post-landing semantics): probe: mvn -pl infochat-provider -am test -Dtest='ChatAgentProvenanceTest,ChatAgentTest' is green with git diff naming NO hunk in ChatAgentProvenanceTest and no provenance-arm hunk in ChatAgentTest — the corpus-grounded(N) notice, the admitted-set split, the null-notice clarify path, and the six degrade null-notices all behave exactly as M1-965 left them; the web lane only ADDS the mutually exclusive third state (KB-miss turns)."
  - "ONE-CALL-PER-TURN + SHARED CONTEXT (the M1-589 redteam lesson applied): ChatAgentWebGroundingTest.webLaneRunsOncePerTurn — a turn whose model loop elects tools mid-conversation does not re-fire the web lane (the deterministic web call is step-3-side, not loop-side); the web call sits OUTSIDE the tool dispatcher's TurnContext (it is not a tool — P2) and its own once-per-turn bound is structural (the single step-3 invocation), asserted by a counter stub."
  - "SPEC AMENDMENT rides the diff (engineering-rules §12; rides-the-diff shape — behavior the M1-969 authorization supports, recorded where it lives): docs/spec/commands.md §Chat mode gains the web-grounding record — the deterministic web pre-fetch behind the question-intent gate, firing only on the deterministic fire condition (both retrieval arms empty, OR a typed-structured tool's degraded/no-data outcome) with the lane enabled/budgeted/breaker-closed, a fired-but-failed or exhausted lane ending at the honest refusal; the compositional notice states (feed-grounded with count / web-grounded with count / not-grounded); and the web block's ladder position (dropped whole after the retrieval block, before memory; scaffolding never drops). Probes: the amended §Chat mode paragraphs state gate, fire condition, refusal rung, notice states, and ladder position; grep of the added prose for dates/ticket IDs returns nothing; the provenance paragraph's count-only interpolation rule (:1867-1869) survives verbatim."
  - "DESIGN LEDGER: docs/design/05-llm-and-embeddings.md §5.4.6 records the lane's prompt posture (block shape, wrapper idiom, citation instruction, ladder slot, byte ledger entry, the once-per-turn bound) — probe: grep -n 'web-grounded' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mention."
  - "OWNER-RUN live probe (verification ceiling, the M1-916/M1-931 posture — no unit test proves the model grounds well; an owner-executed probe with a recorded outcome, not a CI gate): after landing and enabling, the owner asks the motivating queries ('how did Sparta Praha play the last match?', 'why is metro in Prague closed today?', 'who won Cesko hleda SuperStar in 2005?') on the deployment and captures the provider-log slice plus the replies — probe: the slice shows one web call per KB-miss turn and the reply carries the web-grounded notice with cited URLs drawn from the block; a reply from memory with the general-knowledge notice on a KB-miss turn FAILS. The record goes to the ticket/commit."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentWebGroundingTest.java
      — kbMissTurnInjectsWebBlockAndShipsWebGroundedNotice (the
      reproduction), groundedTurnNeverFiresTheWebLane,
      breakerOpenOrDisabledTurnSkipsTheWebLane,
      nonQuestionTurnNeverFiresTheWebLane,
      shortAnaphoricFollowUpPassesTheGate,
      rhetoricalQuestionPassesTheGate,
      typedToolNoDataOutcomeExtendsTheFireCondition,
      laneFailureShipsHonestRefusalNotice,
      egressQueryCarriesCurrentMessageOnlyNeverHistory,
      webGroundedNoticeInterpolatesCountOnlyInEveryLocale,
      webLaneRunsOncePerTurn, and the compaction-ladder and notice
      acceptance items' arms.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
      — the web-block ladder-position arm.
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBudgetTest.java
      — AUTHORIZED (§8), the M1-941 precedent shape: IF the web block's
      fixture bytes cross a budget-literal boundary, the affected
      literals are recalculated from the test's own logged estimates
      with ZERO assertion changes; if no boundary is crossed, no edit
      lands (the authorization is conditional and says so).
  preserves:
    - >-
      all tests currently green on main — explicitly every
      ChatAgentProvenanceTest assertion (post-M1-965 semantics), the
      ChatAgentTest clarify/affordance/window-hint/synthesis pins
      (M1-618/938/941), renderedInstructionTableIsByteIdentical and
      everyRegisteredToolIsAdvertised (no tool added),
      ChatToolAllowlistSpecParityTest, and the M1-970/971 websearch
      suites.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Failure handling
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D21
  - D28
  - D31
  - D43
  - D58
---

# M1-972: Deterministic KB-miss web pre-fetch + web-grounded provenance

## Context

With the lane authorized (M1-969) and built (M1-970/971), the chat turn
still cannot use it: `ChatAgent.doHandle` step 3 runs the semantic
pre-fetch and nothing else, so a KB-miss turn folds in no web block and
ships the general-knowledge notice. This ticket wires the lane into the
turn — the deterministic fire condition (corpus-miss OR a typed tool's
degraded/no-data outcome, both behind a conservative question-intent
gate — P19/P20), the wrapped block injection with its citation
instruction, a defined compaction-ladder slot, the count-only
"web-grounded (N sources)" notice class alongside the existing
corpus-grounded one, and the fallback ladder's agent side with its
honest-refusal terminal rung. It lands AFTER M1-965 (same seam: the
notice's admitted-set accounting is its foundation) and M1-971 (the
fused block is its input). Shared analysis: `analysis_ref:` (this
ticket carries P4, P5, P6, P10, P11, P12, P17, P19, P20).

## Root cause

Verified: the turn flow (`ChatAgent.java:555-589`) has exactly one
pre-fetch (semantic), whose EMPTY outcome (`:962-968`) flows into
nothing but the general-knowledge path; the notice decision
(`:877-888`, post-M1-965 the admitted-set shape) knows only corpus
grounding; `ChatPromptBuilder.build` (`:703-706`) accepts one
`semanticBlock` and the ladder drops it whole (`commands.md:1874-1887`);
no websearch symbol exists in `ChatAgent` (grep, 2026-09-01). The wiring
points are therefore fixed: a second step-3-side lane behind the SAME
breaker gate and the P19 question-intent gate, one new ladder slot, one
new notice branch, and the P20 honest-refusal rung.

## Pitfalls

Carried from the analysis: P4 (egress discipline: the query is the
current message only; opt-out/kill-switch/breaker gates; locale from
the declared language), P5 (the corpus-miss leg of the trigger is the
spec'd empty-result condition; no new tunable; once per turn
structurally), P6 (the block rides the per-call-marker wrapper;
snippet-only bounded content; the citation instruction mirrors
POST_TOOL_RESULT_INSTRUCTION's verbatim-URL rule), P10 (notice
truthfulness: admitted-set discipline for the web set; count-only;
five locales; bundle path never the translator; degrade paths carry no
notice; the notice promises grounding, never verification), P11 (ladder
slot defined; scaffolding never drops; authorized budget-fixture
recalibration only), P12 (serial landing with the M1-965/966/967
family — same file; contains-based pins absorb additions, rewording is
a hurdle), P17 (no unreachable "both" key; no minute-freshness promise
in any steering text — the block's page-age-derived dates serve C6/C7
honesty), P19 (the question-intent gate: conservative, measured
confusion matrix, unsure → no search, the anaphoric/rhetorical classes
must pass), P20 (the fallback ladder's agent side: the typed
degraded/no-data extension, the laundering ban, the fallback sub-cap
draw, and the honest-refusal terminal rung — a disabled/opted-out lane
is the feature OFF and keeps today's behavior).

## Approach

Derived from `spec_refs:` — security.md §Prompt-injection defenses (the
M1-969 enumerated class this wiring instantiates: wrapped, capped,
budget-gated, dispatch-layer-only), §Rate limiting (one web query per
turn, breaker-gated), §Failure handling (the doomed-turn bounded-cost
record), commands.md §Chat mode (the pre-fetch/notice/ladder rules this
ticket's rides-the-diff amendment records), llm.md §Determinism boundary
(the LLM only writes prose over the deterministically fused block).

- **Files to touch:** `files_scope` (two production classes, bundle
  keys + five locale files, three test files, one spec, one design
  doc).
- **Pre-decided shapes (implementation is execution):**
  1. **Trigger (step 3, after the semantic pre-fetch):** fire the web
     lane iff `enabled && !wouldShortCircuit(CHAT_AGENT) &&
     questionIntentGate.passes(message) && fireCondition` where
     `fireCondition` = `preFetch.promptBlock().isEmpty()` (the same
     EMPTY condition the spec's KB-miss clause names,
     `security.md:329`) OR a typed-structured tool's degraded/no-data
     outcome observed at the dispatch layer this turn (the P20
     fallback extension). The gate is P19's conservative classifier —
     a substance minimum, a greeting/small-talk lexicon, reuse of the
     clarify-turn classification; unsure → do NOT search; thresholds
     calibrated from M1-968's confusion matrix and landed as fixed
     code constants. Budget: a primary-counter draw on the corpus-miss
     leg, the fallback sub-cap on the degraded/no-data leg (P20); a
     fired turn whose draw is refused (exhaustion) or whose call fails
     takes the honest-refusal rung. One invocation per turn,
     structural (it lives at the single step-3 site; the tool loop
     never re-fires it).
  2. **Compose + fuse:** hand the CURRENT message text (500-char
     truncation), the declared language, and the scope coordinates to
     M1-971's composer/fusion through M1-970's gate; on a typed empty/
     failure result, the turn proceeds exactly as today.
  3. **Injection:** wrap the fused block in the
     `UNTRUSTED_CONTENT` open/close formats with a fresh UUID marker
     (`ChatAgent.java:971-982` idiom) plus a one-line citation
     instruction (bare URLs verbatim from the block, never invented);
     thread it into `ChatPromptBuilder.build` as a NEW parameter with
     ladder position AFTER the semantic block (dropped whole at that
     rung), before memory; the scaffolding-never-drops rule is
     untouched.
  4. **Notice:** a separate admitted web-source set collected from the
     ADMITTED (post-ladder) block, mirroring M1-965's discipline; the
     notice decision gains the mutually exclusive web-grounded branch
     (KB-miss turn + admitted web set non-empty → web-grounded(N);
     empty/dropped → general-knowledge); new BundleKeys constant +
     five locale files, MessageFormat count pattern mirroring the
     grounded key; plus the honest-refusal key (P20's terminal rung)
     shipped by the fired-but-failed/exhausted path — a degrade reply,
     so it carries no provenance notice.
  5. **commands.md + design-05** records per the spec-amendment and
     design-ledger acceptance items, with the user's wording approval
     at implementation (§12).
- **Steps, in implementation order:** (1) confirm M1-965/971 landed
  (the seam preconditions); (2) write the reproduction + failure-mode
  drives RED; (3) the trigger + injection + ladder slot; (4) the
  notice + bundles; (5) the conditional ChatPromptBudgetTest
  recalibration; (6) spec + design records with the user's approval;
  (7) full `mvn verify`; (8) hand the owner-run probe over.
- **Controls to preserve (§10):** the ENTIRE corpus-lane notice
  accounting and its tests (post-M1-965) pass unchanged; the six
  degrade null-notice paths; the breaker gate ordering (audit row →
  breaker check → pre-fetch work); the wrapper regime and the output
  sanitize/strip/refusal pipeline (untouched — prompt-side only); the
  M1-938 window hint and M1-941 synthesis sentences (contains-pins
  green); the tool surface (eight names, byte-pinned table).
- **Pitfall→mitigation:** P4→item 3's marker drive; P5→item 2's
  trigger drives; P6→item 1's wrapper+citation assertions; P10→item
  5-6; P11→item 4; P12→blocked_by + serial landing; P17→no both-key
  (out_of_scope) + the page-age dates riding the block.

## Definition of done

The reproduction and every failure-mode drive pass; the corpus-lane
provenance suites pass unchanged; the five-locale notice resolves
count-only; the ladder slot and scaffolding rules are pinned; the
conditional budget-fixture recalibration (if any) is authorized and
assertion-free; the commands.md and design-05 records carry the
user-approved rule text; the owner-run probe is handed over with its
record obligation; `mvn verify` green from the repo root.

## Verification

- P4 → `egressQueryCarriesCurrentMessageOnlyNeverHistory` (a
  history/memory-wrapping mutation fails on the marker; a derived
  locale fails the search_lang assertion).
- P5 → `groundedTurnNeverFiresTheWebLane` +
  `breakerOpenOrDisabledOrExhaustedTurnSkipsTheWebLane` (any stray
  web-seam call on a gated turn fails).
- P6 → the reproduction's wrapper + citation-instruction assertions (an
  unwrapped injection or a missing instruction fails).
- P10 → `webGroundedNoticeInterpolatesCountOnlyInEveryLocale` (a
  snippet-byte interpolation or a missing locale fails) + item 6's
  unchanged-suite fence.
- P11 → item 4's ladder arms (a wrong-slot or mid-block drop fails; a
  dropped block claiming a web count fails).
- P12 → blocked_by wiring + the reviewer's same-file diff fence against
  the M1-965/966/967 records.
- P17 → out_of_scope's no-both-key rule + the notice wording's
  no-verification promise (reviewer wording check at implementation).
- P19 → `ChatAgentWebGroundingTest.nonQuestionTurnNeverFiresTheWebLane`
  + `…shortAnaphoricFollowUpPassesTheGate` and
  `…rhetoricalQuestionPassesTheGate` (a gate that kills the
  length-heuristic-vulnerable classes fails; one that fires on
  greetings fails).
- P20 → `ChatAgentWebGroundingTest.typedToolNoDataOutcomeExtendsTheFireCondition`
  + `…laneFailureShipsHonestRefusalNotice` (a silent general-knowledge
  fallback on a fired-but-failed turn, a primary-counter draw for a
  fallback call, or any tool-file snippet parsing fails).
- FAILURE-MODE coverage → items 2-5 each feed hostile/edge input (a
  grounded turn, a doomed turn, a disabled gate, a marker-laden
  history, a ladder-overflowing block) to this diff's own production
  code and assert the protected behaviors.
- the spec/design probes, the diff fence, the owner-run protocol, mvn
  verify → their named acceptance items (the spec-amendment,
  design-ledger, owner-run, and full-verify items).

## Out-of-scope

Named in `out_of_scope`: the tool surface (T1 later); client/fusion
behavior; M1-965's accounting (consumed); the unreachable both-state
key; eval/digest//summary surfaces; the sanitizer and translation
regimes; the corpus lane's hint/synthesis sentences. ONE pre-existing
test file may be modified under the conditional §8 authorization in
`test_plan.modifies` (ChatPromptBudgetTest fixture literals only,
assertions unchanged); every other pre-existing suite must pass
unmodified.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-972-websearch-prefetch-provenance.md
```
