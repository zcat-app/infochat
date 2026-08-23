---
id: M1-918
title: "Chat prompt token budget + deterministic compaction ladder"
status: done
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  ChatPromptBudgetTest.overBudgetTurnCompactsUnderTheConfiguredBudget
  (converted from to-be-written at /tick start 2026-08-23: written against
  the pre-ticket API first and run RED — assembled estimate 8082 tokens vs
  the 6,144 budget with every per-part cap satisfied — then GREEN after the
  ladder landed; child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md).
  The wrong behavior it states: nothing bounds the ASSEMBLED chat prompt
  against any serving context — the per-part caps sum past every shipped
  shape. Verified arithmetic on this checkout (2026-08-23): the history
  budget alone is infochat.context-window=16384
  (infochat-provider/src/main/resources/application.properties:601) while
  the shipped GPU-class wizard write (prod/scripts/4-llm.sh:649-650:
  parallel=3, ctx=32768) yields 11,008 tokens/slot — history alone
  overflows the slot before the system prompt, retrieval block, or user
  message is counted. Live corroboration: prod's first real chat turn
  400d at 11,477 tokens vs 11,008 available (llama-server log, quoted in
  .agents/memory-local/prod-state-post-upgrade-20260823.md).
analysis_ref: docs/plan/m1/tick-analysis/chat-context-budget-and-serving-defaults.md
blocked_by: []
clarity_check: >-
  start 2026-08-23 pass — tick-lint 0 findings; every file:line citation
  re-verified in-tree (ChatPromptBuilder build :94 / memory :110-121 /
  history :128-142 / newestTurnsWithinBudget :157-169, context-window=16384
  at application.properties:601, ChatAgent collectPostUids :838 /
  isMarginalGrounding :865-888 / runToolLoop :905+, semantic append :637,
  MeteredLlmProvider :82-92); no §Census (multi-file diff); analysis
  P1-P10+P14 all present; blocked_by empty. One mechanical finding: direct
  ChatPromptBuilder constructor call sites exist in NINE test files, not
  the two test_plan.modifies names — the same assertion-free call-site
  update extends to the other seven, authorized via the commit message per
  engineering-rules §8 ("ticket body OR the commit message").
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBudgetTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
  - docs/spec/commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The named user-facing notice + operator signal for a prompt that still
    exceeds the backend (sibling M1-923, blocked by this ticket). This
    ticket's ladder always compacts the FIRST call to under budget by
    construction (fixed scaffolding ~2k + capped 2048-char message ≪ the
    6,144 floor); the residual estimate-error surface is M1-923's lane.
  - >-
    infochat.context-window / context-compress-at VALUES and the
    ceiling-gate/auto-compress machinery (M1-264) — the session-accounting
    ceilings stay; the prompt budget re-parameterizes the history suffix
    selection, it does not retune session thresholds.
  - >-
    The documented-but-unimplemented infochat.context-hard-limit design row
    (docs/design/05-llm-and-embeddings.md:1076-1079) and its
    documented-config-key-exemptions.txt:74 entry — stale per-profile
    values (15360 laptop) that do not fit the serving floor; left as-is.
  - >-
    semantic-limit tuning (M1-917's lane), tool-routing text (M1-916),
    tool byte budgets (M1-329/M1-407 shipped), and any tool
    implementation — the ladder truncates what the tools already bounded;
    it never edits the tools.
  - >-
    Any serving-shape change (compose keys, wizard ctx/parallel writes) —
    the serving lane is M1-920/M1-921; this ticket's floor (6,144) is the
    number M1-921 derives from.
  - >-
    The estimateTokens(chars/4) heuristic itself — reused as-is for unit
    consistency with persisted session tokens; its error band is absorbed
    by the budget headroom (analysis P2), not re-estimated per call.
acceptance:
  - "REPRODUCTION closed: ChatPromptBudgetTest.overBudgetTurnCompactsUnderTheConfiguredBudget (test_plan.adds) passes — a turn whose naive assembly (16384-budget history + memory hits + semantic block + message) estimates over the configured budget compacts to an assembled first-call prompt (system + user + semantic remainder + directive) whose chars/4 estimate is AT OR UNDER the budget; the mutation that no-ops the ladder fails it (non-vacuity)."
  - "KEY + DRIFT PIN (P4): the new infochat.chat.prompt-token-budget is declared in application.properties (base, value 6144) AND mirrored by the @ConfigProperty defaultValue, with a comment recording the derivation: shipped GPU-class slot 11,008 − 600 reply budget = 10,408; 6,144 estimate tolerates ~1.7x chars/4 underestimation (6,144 x 1.7 ≈ 10,400). ChatPromptBudgetTest.budgetDefaultIsDeclaredOnceAndMatchesProperties passes (the M1-917 item-1 reflective pattern) AND probe: grep -n 'prompt-token-budget' infochat-provider/src/main/resources/application.properties returns exactly ONE line, the unprefixed base declaration (no profile override)."
  - "LADDER ORDER + DETERMINISM (P3, P10): the ladder applies in the FIXED order (1) history oldest-first against min(infochat.context-window, budget remainder after fixed parts), re-parameterizing newestTurnsWithinBudget — never a second competing truncation; (2) the WHOLE semantic pre-fetch block (never mid-JSON, P6); (3) memory hits oldest-first, then the block. ChatPromptBudgetTest.ladderDropsHistoryThenRetrievalThenMemory passes (a fixture needing only step 1 keeps retrieval+memory; one needing step 2 drops the whole retrieval block and keeps one history turn; one needing step 3 has neither) AND ChatPromptBudgetTest.sameInputsCompactByteIdentically passes (two builds, byte-identical prompts; no time/GUC/random input beyond the pre-existing per-call markers)."
  - "SCAFFOLDING INTACT (P1, failure-mode): ChatPromptBudgetTest.compactionNeverDropsScaffolding passes — the maximally-compacted prompt still contains CHAT_SYSTEM_PROMPT_TEMPLATE's full injection-defence half verbatim, the complete rendered TOOL_INSTRUCTIONS table, the language directive, and BALANCED UNTRUSTED_CONTENT open/close pairs around every surviving untrusted block; ChatPromptBuilderTest.systemPromptInjectionDefenseHalfIsPreservedVerbatim and historyWrappedInUntrustedContentDelimiters pass UNCHANGED."
  - "PROVENANCE HONESTY (P6, P7, failure-mode): ChatAgentTest.droppedRetrievalBlockKeepsGeneralKnowledgeProvenance passes — a drive whose pre-fetch returned posts but whose block the ladder dropped asserts the prompt carries NO partial JSON fragment of the retrieval result (no orphan '[{' tail) AND the turn's provenance notice is the general-knowledge bundle string, never a grounded count; the clarify/affordance directive selection sees the pre-drop signal but the folded content is all-or-nothing. THE DROP IS DISPOSITIVE (spec amendment 2026-08-23): the same drive also exercises the corner where a post-drop model-elected semanticSearch result IS folded into the conversation — the notice STAYS the general-knowledge bundle string (grounding carries a semanticDropped term; folded hits do not resurrect the count)."
  - "OBSERVABILITY (P5): a compacting turn logs at INFO the per-step dropped counts and the before/after estimates (the M1-728 'a truncated LLM input is never silent' posture); ChatPromptBudgetTest.compactionLogsWhatWasDropped passes (log-capture assertion)."
  - "TOOL-LOOP BOUND (P8, failure-mode): ChatAgentTest.overBudgetToolLoopTruncatesAtEntriesAndTakesFinalCall passes — a multi-iteration drive with oversized tool results asserts each fold-back is truncated at ENTRY granularity (every surviving entry keeps its uid/url lines intact; never a mid-entry cut) to a per-result share, AND that a conversation still over budget at the next iteration takes the EXISTING iteration-cap final call (base system prompt, no tool instructions) instead of growing."
  - "TRANSPORT UNIFORMITY (P9): the same budgeted prompt reaches generate / generateStreaming / generateWithTools — ChatAgentReplyModeTest and the pre-existing chat suites (ChatToolCatalogTest, ChatToolDispatcherTest, ChatAgentRefusalIntercept*, ChatAgentProvenanceTest, AutoCompressTriggerTest) pass UNCHANGED; probe: mvn -pl infochat-provider -am test -Dtest='ChatPromptBudgetTest,ChatPromptBuilderTest,ChatAgentTest,ChatAgentReplyModeTest,ChatAgentProvenanceTest,AutoCompressTriggerTest' is green."
  - "SPEC AMENDMENT rides the diff (analysis P16; engineering-rules §12 — exact wording to the user at implementation; rule-text draft in Approach): docs/spec/commands.md §Chat mode records that the chat prompt is assembled under a deterministic token budget with the fixed compaction order, that the instruction/wrapper scaffolding is never dropped, and that the provenance notice reflects what was actually folded — number-free, no dates/IDs. Probe: grep -n 'budget' docs/spec/commands.md returns the §Chat mode mention."
  - "DOCS: docs/design/05-llm-and-embeddings.md §5.4.6/§5.7 document the key, the 6,144 derivation (incl. the M1-916 +~90-token and M1-917 +400-800-token ledger entries it absorbs, P14), the ladder order, and the estimator-unit choice; the §5.7 context-hard-limit staleness note is UNTOUCHED (out_of_scope). Probe: git diff --stat docs/ shows exactly docs/spec/commands.md and docs/design/05-llm-and-embeddings.md."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBudgetTest.java — overBudgetTurnCompactsUnderTheConfiguredBudget, budgetDefaultIsDeclaredOnceAndMatchesProperties, ladderDropsHistoryThenRetrievalThenMemory, sameInputsCompactByteIdentically, compactionNeverDropsScaffolding, compactionLogsWhatWasDropped
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java — AUTHORIZED (§8; the M1-552 budget-breach precedent): direct ChatPromptBuilder constructor call sites gain the new budget argument; assertions unchanged
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java — direct ChatPromptBuilder construction (:412-era site, per M1-552's revision record) gains the new argument; PLUS the two new drives droppedRetrievalBlockKeepsGeneralKnowledgeProvenance and overBudgetToolLoopTruncatesAtEntriesAndTakesFinalCall (the acceptance items above)
  preserves:
    - all tests currently green on main — explicitly the byte-identity pins
      (renderedInstructionTableIsByteIdentical: scaffolding text does not
      change), the mode/live-text suites, and the tool ITs
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/llm.md §Hardware profile contract
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D19
  - D58
reviews:
  - round: 1
    date: 2026-08-23
    verdict: APPROVE-WITH-FIXES
    checks: "SPEC-TRUTHNESS WARN (low — spec-amendment approval record owed before commit), SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "16 files changed, 1017 insertions(+), 139 deletions(-)"
    rework_items: 0
    fix_items: 1
    verdict_file: .scratch/tick-review-M1-918-r1.txt
    fixes_applied: >-
      User amended the wording (evaluation 2026-08-23: bold lead-in,
      "token estimate" mechanism drop, dispositive-drop final clause);
      approved text landed via the user's reviewer session, ported to
      this worktree byte-identical; ticket item 5 extended with the
      dispositive-drop corner drive + Approach records the approved
      wording verbatim as do-not-reword. Approval carries to the commit
      body as the "Spec-amendment approval:" line.
  - round: 2
    date: 2026-08-23
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "round-2 fix hunks: 4 files, +106/-29 (spec wording swap, dispositive-drop corner arm in ChatAgentTest, ticket records); round-1 full diff: 16 files, +1017/-139"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-918-r2.txt
---

# M1-918: Chat prompt token budget + deterministic compaction ladder

## Context

Prod's first real chat turn after the serving-shape rollout died on
llama-server's HTTP 400 — the 11,477-token request exceeded the
11,008-token slot of the shipped GPU-class default (3 × 32768) — and a
later 12,907-token turn cost 18.9 s of prefill before the first word
(`.agents/memory-local/prod-state-post-upgrade-20260823.md`). Verified
cause shape: ChatAgent assembles the prompt from per-part caps whose SUM
(~16,384 history + 16 KiB retrieval + 10 × 16 KiB tool results +
scaffolding) exceeds every shipped serving shape; nothing measures the
assembled whole and nothing compacts. Note the analysis's falsification:
the prompt is NOT dominated by fixed scaffolding (~1.5-2k tokens, and no
help corpus is injected at all) — the unbounded terms are history and
tool-result accretion. Analysis: `analysis_ref:`.

## Root cause

`ChatPromptBuilder.build` (ChatPromptBuilder.java:94-149) sizes history
against the standalone `infochat.context-window` (16,384 base —
application.properties:601) and adds memory hits with no size term at
all; `ChatAgent.doHandle` appends the ≤16 KiB semantic block
(ChatAgent.java:520, :637) and `runToolLoop` (:905-979) accretes up to
10 × (reply + 16 KiB result). No component knows the backend's per-slot
context; the only whole-prompt bound anywhere is MeteredLlmProvider's
3×-chars impossible-magnitude check on REPORTED usage
(metrics/MeteredLlmProvider.java:82-92), which is observability, not
budgeting.

## Pitfalls

Numbered with the analysis document; this ticket carries P1-P10, P14.

- P1: never compact the security scaffolding (injection-defence text,
  wrappers, tool table, directives) — the M1-694 relocated-control
  class; §10.
- P2: chars/4 understates CJK/emoji token counts — the 6,144 default
  carries the ~1.7× error band against the 10,408 fit ceiling; do not
  add a tokenizer call (D19 + latency).
- P3: the ladder is a pure function — fixed order, fixed thresholds, no
  LLM/time/GUC inputs (llm.md §Determinism boundary).
- P4: the key is declared twice (properties + defaultValue) — pin both
  to one value, no profile override (M1-917's dual-declaration lesson;
  DocumentedConfigKeyParityTest posture).
- P5: compaction is never silent — INFO log with dropped counts +
  estimates (the M1-728 posture).
- P6: the semantic block drops WHOLE or truncates at entry granularity —
  never mid-JSON (`isMarginalGrounding` is fail-open on unparsable
  input, ChatAgent.java:865-888).
- P7: `retrievedPostUids` is collected before the drop decision
  (ChatAgent.java:838) — a dropped retrieval block must yield the
  general-knowledge notice, never a grounded count (M1-617/D58 honesty).
- P8: a first-call-only budget is decoration — bound each tool-result
  fold-back at entry granularity (uid/url intact, the citation contract)
  and take the existing iteration-cap final call when still over budget.
- P9: one budgeted prompt for all three transports (generate /
  generateStreaming / generateWithTools) — M1-848/M1-849 must not
  regress.
- P10: re-parameterize `newestTurnsWithinBudget`
  (ChatPromptBuilder.java:157-169) to min(context-window, budget
  remainder) — not a second competing truncation.
- P14: the ledger counts M1-916 (+~90 tok/turn) and M1-917 (+400-800
  tok/injection at limit 16); at the 6,144 floor a 16-entry injection is
  ~13% of budget and drops under ladder step 2 before history — no
  separate window bound is added (discharges M1-917's deferred P7).

## Approach

Derived from `spec_refs:`: the determinism boundary requires the ladder
be pure code; the prompt-injection defenses fix the never-drop set; the
hardware-profile contract makes the value design-tier.

- **Files to touch:** `files_scope`.
- **Pre-decided shapes (implementation is execution):**
  1. New key `infochat.chat.prompt-token-budget`, base declaration only,
     value **6144**, `@ConfigProperty` defaultValue mirrored, derivation
     comment per acceptance item 2.
  2. `ChatPromptBuilder` gains the budget parameter (constructor —
     direct constructions in tests updated per the authorized
     `test_plan.modifies`, the M1-552 lesson applied prospectively) and
     returns the compaction report (per-step dropped counts + before/
     after estimates) alongside `BuiltPrompt`; history suffix selection
     runs against min(context-window, budget remainder) (P10).
  3. `ChatAgent.doHandle` applies ladder steps 2-3 to the assembled
     semantic block / memory block BEFORE `runToolLoop`, feeds the
     drop outcome into the provenance-notice choice (P7), and logs the
     report at INFO (P5).
  4. `runToolLoop` truncates each tool-result fold-back at entry
     granularity to a per-result share of the budget, and routes a
     still-over-budget conversation to the existing final-call path
     (base system prompt) at the next iteration (P8).
- **Spec amendment rule-text (§12 — wording approved by the user
  2026-08-23; landed in docs/spec/commands.md §Chat mode between the
  provenance paragraph and the D66 paragraph; verbatim, do not reword
  at implementation):** "**The assembled prompt is budget-bounded and
  compacts deterministically.** The chat prompt is assembled under a
  configured token budget measured in the same token estimate as session
  accounting. When the naive assembly exceeds it, a deterministic
  compaction ladder applies in fixed order: oldest history turns first,
  then the deterministic retrieval block whole, then memory pre-fetch
  hits oldest-first, then any remaining memory block. The instruction
  and untrusted-content scaffolding is never dropped. The ladder is a
  pure function of the assembled parts — identical inputs compact to an
  identical prompt (D19). A turn whose retrieval block was dropped takes
  the general-knowledge path: its provenance notice is the not-grounded
  one even when later model-initiated retrieval results were folded
  into the conversation (D58). Budget value, derivation and
  truncation posture live in design notes (05 §5.4.6, §5.7)."
- **Steps, in order:**
  1. Write ChatPromptBudgetTest RED (reproduction + ladder/scaffolding/
     determinism/observability pins).
  2. Add the key + builder/Agent/doHandle/runToolLoop changes (shapes
     1-4).
  3. Update the authorized test call sites; add the two ChatAgentTest
     drives named in `test_plan.modifies`.
  4. Land the user-approved spec wording + design 05 updates.
  5. Module test run + `mvn verify`.
- **Controls to preserve (§10):** enumerated in the analysis — the
  injection-defence half and wrappers byte-identical; the sanitize →
  strip → refusal-intercept reply order untouched (request-side change
  only); ceiling gate/auto-compress semantics unchanged (no session row
  is deleted or altered by compaction — it is assembly-time only);
  provenance-notice honesty extended, not weakened; every byte-identity
  pin green unchanged.
- **Pitfall→mitigation:** P1→acceptance item 4; P2→item 2's derivation;
  P3→item 3's byte-identity pin; P4→item 2's reflective pin + grep;
  P5→item 6; P6→items 3 and 5; P7→item 5; P8→item 7; P9→item 8;
  P10→shape 2 + item 3; P14→the ledger in item 10's docs.

## Definition of done

Every acceptance item verified by its named test/probe: the reproduction
test passes; the key is single-declared and pinned at 6144 with its
derivation; the ladder order, determinism, scaffolding-intactness,
provenance honesty, observability, and tool-loop bound each have their
named test; all pre-existing chat suites pass unchanged (only the two
authorized test modifications); the spec amendment (user-approved) and
design docs land; `mvn verify` green from the repo root.

## Verification

- P1 → ChatPromptBudgetTest.compactionNeverDropsScaffolding — feeds a
  fixture that forces all three ladder steps; asserts the full
  scaffolding + balanced wrappers survive.
- P2 → acceptance item 2's derivation comment + default pin; residual
  error is M1-923's named surface.
- P3 → ChatPromptBudgetTest.sameInputsCompactByteIdentically — two
  builds from identical inputs are byte-identical.
- P4 → ChatPromptBudgetTest.budgetDefaultIsDeclaredOnceAndMatchesProperties
  (a one-sided edit fails it) + the base-only grep probe.
- P5 → ChatPromptBudgetTest.compactionLogsWhatWasDropped (log capture).
- P6/P7 → ChatAgentTest.droppedRetrievalBlockKeepsGeneralKnowledgeProvenance
  — no partial JSON in the prompt; general-knowledge notice after a
  retrieval drop.
- P8 → ChatAgentTest.overBudgetToolLoopTruncatesAtEntriesAndTakesFinalCall
  — entry-granular truncation (uid/url intact) + early final-call routing.
- P9 → item 8's named module run; the three transport selections each
  exercised by the new tests.
- P10 → ladderDropsHistoryThenRetrievalThenMemory's step-1 arm pins
  min(context-window, remainder) sizing.
- P14 → the design-doc ledger (item 10); no code verification owed
  (honesty constraint, the M1-856 P8 pattern).
- FAILURE-MODE coverage (beyond the reproduction) → items 4, 5, 7
  (maximal compaction keeps scaffolding; dropped retrieval stays honest;
  hostile oversized tool results cannot grow the loop).
- acceptance items 9, 10, 11 → the grep probes, the diff-stat probe,
  `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: the named-refusal failure surface (M1-923);
session-ceiling retuning and the stale context-hard-limit design row;
semantic-limit/tool-routing/tool-implementation surfaces (M1-916/917,
M1-329/407); all serving-side compose/wizard changes (M1-920/921); any
change to the chars/4 estimator. This ticket modifies TWO pre-existing
test files (§8 authorization in `test_plan.modifies`): constructor call
sites gain the budget argument (assertions unchanged), and ChatAgentTest
gains the two new drives — any other pre-existing assertion that
conflicts is a start-hurdle escalation, not a silent edit. Sibling
calibration: M1-916/917 share the module and application.properties —
sequence, never `--parallel`; M1-912 (digest/**) is verified disjoint.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-918-chat-prompt-budget-compaction.md
```

## Review observations

- r2 (2026-08-23, APPROVE) RECOMMENDED-NEW-TICKET (user's call,
  TOUCHED-BY-THIS-DIFF: yes — created by r1's fold code; DECIDE-BEFORE:
  M1-923): truncated-to-zero fold-backs can over-claim grounding —
  collectPostUids runs on the FULL tool result before fitWithinBudget
  (ChatAgent.java:981 vs :999), so a 30-entry result with zero entries
  admitted still yields "grounded in 30 posts" while the model's next
  prompt carries an empty wrapper. Fix (count admitted entries / collect
  after fit) or spec-sanction belongs with M1-923's notice/failure-surface
  lane. Relayed to the user 2026-08-23. Driver decision (2026-08-23):
  fix, not spec-sanction — folded into M1-923's ticket (acceptance item
  10, approach shape 4).

- r1 (2026-08-23, APPROVE-WITH-FIXES) RECOMMENDED-NEW-TICKET (user's call,
  TOUCHED-BY-THIS-DIFF: yes): post-drop tool-elected grounding under-claims
  in the provenance notice — when the ladder drops the pre-fetch retrieval
  block but the model later elects semanticSearch/getPost calls whose
  results ARE folded in, the notice still reads the general-knowledge
  wording. Options: a notice reflecting the actually-folded set, or a
  spec-level confirmation sentence that a dropped-pre-fetch turn always
  takes the general-knowledge wording regardless of later tool grounding.

## Post-approval repair (§8 authorization, user-mandated)

- 2026-08-23, user mandate ("fix it and finish it"): the merge gate's
  full `mvn verify` failed twice on
  SemanticSearchToolDiversityIT.windowAtTheNewDefaultStaysUnderTheByteBudget
  (M1-917's test, NOT in this ticket's modifies list and untouched by
  this diff) — an intermittent fixture defect, not a regression: the 18
  fat posts tie on ts_rank, so the lexical arm's rank tie-breaks on
  post_id ASC = random UUIDs per run, shuffling the fused head the
  assertion demands (1 failure in 6 isolated pre-fix runs; two failed
  full verifies). User authorized repairing the test here (engineering
  rules §5 escalation resolved by the owner). Fix: identical fat titles
  (structural ts_rank tie) + byte-ordered explicit post ids via a
  seedPost overload — both arms rank by angle, the truncation head is
  fat-1 by construction (D19). No assertion weakened; the
  head-preservation assertion becomes deterministic. Post-fix: 8/8
  isolated runs green + full serialized verify on the rebased tree.
