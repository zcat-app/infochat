---
id: M1-966
title: "Fit ladder history by rendered estimate before block drop"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction:
  parked: .scratch/parked-tests/ChatProvenanceContradictionReproTest.java —
  ChatPromptBudgetTest.ladderMustNotDropRetrievalBlockWhenHistoryCouldCloseTheGap
  (the parked test's second @Test, to land under that name at start; run
  RED 2026-08-31, .scratch/tick-repro-red-run1.log: expected
  semanticBlockDropped=false, actual true — 100 turns x 10 stored tokens,
  block ~205 tokens, budget 900: the ladder logged "dropped
  historyTurns=70 … semanticBlock=true; estimated 1867 -> 834 tokens
  (budget 900)", dropping the whole retrieval block to close a
  scaffold-scale overflow while more oldest turns remained). Live
  corroboration 2026-08-31 (brief-carried, read-only): a ~70-token
  post-step-1 overflow cost a ~1700-token block and finished 1682
  tokens under the 6144 budget.
analysis_ref: docs/plan/m1/tick-analysis/chat-provenance-notice-contradiction-and-ladder-priority.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBudgetTest.java
  - docs/spec/commands.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The notice decision that consumes semanticBlockDropped (sibling
    M1-965) and any ChatAgent.java change: this ticket is builder-level;
    the ChatAgent drives that pin the dropped-block notice/directive
    behavior use empty-history fixtures and stay green unchanged.
  - >-
    The ladder's step order (history, then the whole retrieval block,
    then memory) and step-2's whole-block semantics — unchanged; the fix
    changes WHEN step 2 fires (only after history cannot close the gap),
    never what it drops or how (never mid-JSON).
  - >-
    The chars/4 estimator itself, the 6144 budget value and its
    derivation, and any serving/compose/wizard change (M1-918
    out-of-scope precedent; the value is pinned by
    ChatPromptBudgetTest.budgetDefaultIsDeclaredOnceAndMatchesProperties
    and must not move).
  - >-
    infochat.context-window semantics, auto-compress/ceiling-gate
    machinery (M1-264), and the tool-loop fold-back budget
    (ChatAgent.fitWithinBudget) — the ladder's first call only.
acceptance:
  - "REPRODUCTION closed: ChatPromptBudgetTest.ladderMustNotDropRetrievalBlockWhenHistoryCouldCloseTheGap (test_plan.adds; the parked drive lands under this name) passes — asserts semanticBlockDropped=false, historyTurnsDropped > 0, AND that the rendered assembly WITH the block estimates at or under the budget (keeping the block by trimming more history fits)."
  - "ONE UNIT (P7, P8): step 1 selects the history suffix by the same RENDERED chars/4 estimate the budget verdict computes — charging each turn's rendered cost plus the rendered history-block header and wrapper — so a suffix step 1 kept fits min(infochat.context-window, budget remainder) by construction; a new ChatPromptBudgetTest unit-consistency drive passes on a fixture whose ONLY overflow is scaffold-scale (per-turn + wrapper): more oldest history is dropped, the block survives, and the final rendered estimate sits at or under the budget."
  - "STEP 2 ONLY AFTER HISTORY IS EXHAUSTED (P9, §8-authorized in test_plan.modifies): ladderDropsHistoryThenRetrievalThenMemory's arm 2 is re-authored onto a discriminating fixture — history trims to EMPTY and the assembly still overflows with the block present (grow the memory block / shrink the budget so fixed parts + block + memory exceed the budget even with zero history) — and passes asserting semanticBlockDropped=true, zero history turns kept, memory untouched, and no post-1/URL fragment of the dropped block; arms 1, 3 and 4 pass unchanged."
  - "STEP-2 SEMANTICS UNCHANGED (P11): the block still drops WHOLE — ChatAgentTest.droppedRetrievalBlockKeepsGeneralKnowledgeProvenance's main and marginal arms (empty-history fixtures) pass unchanged: no JSON fragment, no retrieval header, refinement directive stripped, general-knowledge notice for the nothing-re-grounded turn."
  - "DETERMINISM (P10): ChatPromptBudgetTest.sameInputsCompactByteIdentically passes unchanged — two builds from identical inputs are byte-identical modulo the per-call markers, and the CompactionReport is part of the equality."
  - "WINDOW TERM KEPT (P12): the step-1 cap stays min(infochat.context-window, budget remainder) in the same rendered unit — a fixture whose context-window binds below the budget remainder keeps its window-bound behavior (the suffix never claims more than the window in rendered tokens); asserted inside the re-authored arm-2 fixture or a sibling drive."
  - "OBSERVABILITY UNCHANGED (P5 of M1-918): ChatPromptBudgetTest.compactionLogsWhatWasDropped passes unchanged — the compacting build still logs per-step dropped counts and before/after estimates, and historyTurnsDropped still means turns dropped from the prompt."
  - "SPEC AMENDMENT rides the diff (engineering-rules §12 — exact wording approved by the user at implementation; rule-text draft in Approach): docs/spec/commands.md §Chat mode's ladder paragraph records that history trimming is measured in the same token estimate the budget verdict uses and continues while it can bring the assembly to budget — the retrieval block drops whole only when no further oldest history turn remains to trim. Probe: grep -n 'same token estimate' docs/spec/commands.md returns the §Chat mode ladder sentence."
  - "DESIGN RECORD: docs/design/05-llm-and-embeddings.md §5.4.6's prompt-budget paragraph records the step-1 unit (per-turn rendered cost + header/wrapper constant; step 2 fires only after history is exhausted as a fit lever); git diff --stat docs/ shows exactly docs/spec/commands.md and docs/design/05-llm-and-embeddings.md."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBudgetTest.java
      — ladderMustNotDropRetrievalBlockWhenHistoryCouldCloseTheGap (the
      parked family reproduction, landed) plus the unit-consistency drive
      (scaffold-scale-only overflow closes via more history, block
      survives) and, if the implementer separates it, the window-term
      drive named in acceptance item 6.
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBudgetTest.java
      — AUTHORIZED (§8; the M1-941 budget-literal precedent):
      ladderDropsHistoryThenRetrievalThenMemory arm 2 is re-authored onto
      the discriminating fixture described in acceptance item 3 — under
      the corrected rule the CURRENT fixture's history can close the
      gap, so it no longer exercises step 2; the re-authored arm keeps
      the step-2 assertions (whole-block drop, memory untouched, no
      fragment) and updates the kept-turn count to zero; arms 1, 3, 4
      and their assertions are unchanged.
  preserves:
    - all tests currently green on main, explicitly
      overBudgetTurnCompactsUnderTheConfiguredBudget,
      budgetDefaultIsDeclaredOnceAndMatchesProperties (the 6144 value
      does not move), sameInputsCompactByteIdentically,
      compactionNeverDropsScaffolding, compactionLogsWhatWasDropped, and
      every ChatAgentTest dropped-block drive (empty-history fixtures)
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/security.md §Prompt-injection defenses
decision_refs:
  - D19
  - D58
---

# M1-966: Fit ladder history by rendered estimate before block drop

## Context

Live on the prod instance 2026-08-31 00:54 UTC (identifiers in the
analysis doc): a long DM session (126 messages) hit budget pressure, the
ladder dropped 92 oldest history turns, and a residual ~70-token
overflow then cost the WHOLE ~1700-token retrieval block — finishing
1682 tokens UNDER budget — although one or two more oldest-history drops
would have kept the turn's grounding in the prompt. The dropped-block
condition is also what sibling M1-965's mislabeled turn rode on. Long
sessions hit this path on most turns. Analysis: `analysis_ref:`.

## Root cause

Ladder step 1 selects the history suffix with `newestTurnsWithinBudget`
(ChatPromptBuilder.java:169-170 -> :286-298), which sums STORED per-turn
token counts; the budget verdict (`estimateAfter`, :176-185) is computed
from RENDERED chars/4 estimates, and the rendered history block
exceeds the stored sum by per-turn scaffolding (`role: ` + newline per
turn, renderHistoryBlock :254-271) plus the block header/wrapper
(~100 chars). A step-1-"fitted" suffix therefore still overflows the
verdict by a scaffold-scale delta, and step 2 (:182-185) then drops the
whole retrieval block whenever the estimate exceeds the budget —
regardless of overflow size and regardless of history that remains
available to trim. Verified mechanically by the RED unit mirror
(.scratch/tick-repro-red-run1.log: 1867 -> 834 on budget 900 with
semanticBlock=true and 30 turns still kept).

## Pitfalls

Carries P7-P12, P16, P17 of the analysis.

- P7: two competing truncations — keeping the stored-sum walk and adding
  a rendered-correction second pass recreates the M1-918 P10 shape; the
  selection unit must BE the verdict unit.
- P8: the wrapper constant — charging per-turn rendered cost but not the
  header+wrapper leaves the same bug at smaller scale. Invariant: a
  suffix step 1 kept fits by `estimateTokens(renderHistoryBlock(suffix))`.
- P9: the arm-2 fixture stops discriminating under the corrected rule
  (its history CAN close the gap) — a non-discriminating fixture is the
  M1-785 failure class; re-author it so history empties and the
  assembly still overflows with the block present.
- P10: determinism/byte-identity (llm.md §Determinism boundary; the
  spec's "identical inputs compact to an identical prompt (D19)") — the
  re-parameterized selection stays a pure function; no time/GUC/random
  input beyond the per-call markers.
- P11: step-2 drift — the fix changes WHEN step 2 fires, never WHAT it
  does (whole block, never mid-JSON, the M1-918 mid-JSON pitfall;
  directive stripping and the notice consequence live in ChatAgent and
  are M1-965's lane).
- P12: window-cap unit mixing — the cap stays
  min(infochat.context-window, budget remainder); do not drop the
  window term (session-accounting ceiling; M1-264 machinery out of
  scope).
- P16: no prod identifiers in landed artifacts (the analysis/.scratch
  stores carry them).
- P17: same module as M1-965/M1-967 — land serially.

## Approach

Derived from `spec_refs:` — commands.md §Chat mode's ladder paragraph
fixes the ORDER (oldest history first, then the block whole); llm.md
§Determinism boundary requires the ladder stay a pure function;
security.md §Prompt-injection defenses fixes the never-drop set. The fix
makes step 1 actually deliver "oldest history turns first": history is
exhausted as a fit lever, in the verdict's own unit, before the block is
sacrificed.

- **Files to touch:** `files_scope` (one production file, one test
  file, two docs).
- **Pre-decided shapes (implementation is execution):**
  1. Step 1 selects the maximal newest-contiguous suffix whose RENDERED
     history block (header + wrapper + per-turn rendered cost, chars/4)
     fits min(`infochat.context-window`, budget remainder) — e.g. by
     walking newest->oldest charging rendered cost against
     (remainder − rendered header/wrapper). The estimateAfter arithmetic
     (:176-178) then computes from the same rendered block, so a
     suffix step 1 kept closes the gap by construction.
  2. Step 2 fires only when the post-step-1 estimate still exceeds the
     budget (history exhausted or empty suffix); its body is untouched:
     whole block, never mid-JSON.
  3. Re-author arm 2 per `test_plan.modifies`; land the parked
     reproduction + unit-consistency drive.
  4. Spec + design records last.
- **Spec amendment rule-text draft (§12 — wording approved by the user
  at implementation; appended to the fixed-order sentence in
  commands.md §Chat mode's ladder paragraph):** "History is trimmed in
  the same token estimate the budget verdict uses, and continues while
  it can bring the assembly to budget; the retrieval block drops whole
  only when no further oldest history turn remains to trim."
- **Steps, in order:** land the reproduction RED-confirmed at `/tick
  start` → shape 1-2 → shape 3 (authorized test edits) → spec + design
  (shape 4) → module test run → `mvn verify`.
- **Controls to preserve (§10):** enumerated in the analysis — the
  never-drop scaffolding and balanced wrappers
  (compactionNeverDropsScaffolding, ChatPromptBuilderTest verbatim
  pins); the INFO compaction log with truthful dropped counts; the
  whole-block/no-mid-JSON drop; the loop fold-back budget
  (ChatAgent.fitWithinBudget — untouched); the 6144 key declaration pin;
  session rows untouched (assembly-time only).
- **Pitfall→mitigation:** P7/P8→shape 1's invariant + the
  unit-consistency drive; P9→shape 3; P10→acceptance item 5; P11→
  acceptance item 4; P12→acceptance item 6; P16→scrubbed artifacts;
  P17→serial landing.

## Definition of done

Every acceptance item verified by its named test/probe: the landed
reproduction and unit-consistency drives pass; the re-authored arm 2
exercises step 2 on a discriminating fixture; the determinism,
scaffolding, logging and default-value pins pass unchanged; the spec
and design records land probe-clean; `mvn verify` green from the repo
root.

## Verification

- P7/P8 → ChatPromptBudgetTest.ladderMustNotDropRetrievalBlockWhenHistoryCouldCloseTheGap
  + the unit-consistency drive (scaffold-scale-only overflow: asserts
  semanticBlockDropped=false AND the with-block rendered estimate fits —
  the assertion that fails under the current two-unit design and under
  a wrapper-less rendered charge alike).
- P9 → the re-authored arm 2 (hostile shape: history drained to empty,
  fixed parts + block + memory alone overflow — asserts the whole-block
  drop, zero kept turns, memory untouched, no post-1/URL fragment).
- P10 → sameInputsCompactByteIdentically unchanged.
- P11 → ChatAgentTest.droppedRetrievalBlockKeepsGeneralKnowledgeProvenance
  main + marginal arms green unchanged (no fragment, no header,
  directive stripped).
- P12 → the window-term drive/assertion (a window below the remainder
  still bounds the suffix in the rendered unit).
- P16 → grep of the landed test file for prod identifiers returns
  nothing.
- P17 → serial landing: the module test run names this ticket's suites
  only; no sibling ticket is in flight in this module at start.
- acceptance items 7-10 → compactionLogsWhatWasDropped, the grep and
  diff-stat probes, `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: the notice decision and any ChatAgent.java
change (M1-965); the ladder's step order and step-2 drop mechanics; the
estimator, the 6144 value and its derivation; context-window semantics,
auto-compress/ceiling machinery, and the tool-loop fold-back budget.
This ticket modifies ONE pre-existing test file (§8 authorization in
`test_plan.modifies`): the ChatPromptBudgetTest arm-2 re-authoring (the
M1-941 budget-literal recalibration is the precedent for authorized
edits to this file). Any other pre-existing assertion that conflicts is
a start-hurdle escalation, not a silent edit.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-966-chat-prompt-ladder-retrieval-priority.md
```
