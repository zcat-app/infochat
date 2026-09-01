---
id: M1-965
title: "Make the provenance notice count only admitted grounding"
status: pending
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction:
  parked: .scratch/parked-tests/ChatProvenanceContradictionReproTest.java —
  ChatAgentProvenanceTest.loopGroundedTurnMustNotClaimGeneralKnowledge
  (the parked test's first @Test, to land under that name at start; run
  RED 2026-08-31, .scratch/tick-repro-red-run1.log: expected grounded(2),
  actual "general-knowledge"). The wrong behavior it states: a turn whose
  pre-fetch block the ladder dropped but whose reply IS grounded in
  model-initiated post-corpus tool results (fitted and folded back into
  the conversation) ships the general-knowledge notice — observed live on
  the prod instance 2026-08-31 00:54 UTC (a reply citing four real feed
  posts carried "Not based on your feed posts; answered from general
  knowledge."; live identifiers stay in the gitignored analysis doc and
  .scratch, never in committed artifacts).
analysis_ref: docs/plan/m1/tick-analysis/chat-provenance-notice-contradiction-and-ladder-priority.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/spec/commands.md
  - docs/design/05-llm-and-embeddings.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The ladder itself (sibling M1-966): which unit step 1 fits history in,
    when the block drops. This ticket consumes CompactionReport
    .semanticBlockDropped() as given; its rig stubs the builder.
  - >-
    The refinement-directive stripping when the block drops
    (ChatAgent.java:719-721) — pinned by ChatAgentTest:2617-2619 and
    :2637-2638; the AFFORDANCE directive references folded posts and must
    not ride without them. No live evidence against it.
  - >-
    POST_CORPUS_TOOLS membership (recallMemory/listSaves stay excluded —
    sibling M1-967 owns the user-state notice wording), any bundle-key
    changes (the two provenance keys exist and do not change), any tool
    SQL or security.md tool-table edit.
  - >-
    Counting posts quoted only in KEPT history (latent gap 2 in the
    analysis) — deferred; turn-scoped count is specced and no live
    evidence exists.
acceptance:
  - "REPRODUCTION closed: ChatAgentProvenanceTest.loopGroundedTurnMustNotClaimGeneralKnowledge (test_plan.adds; the parked drive lands under this name, its rig stubbing CompactionReport with semanticBlockDropped=true and a model-initiated searchPosts Success) passes — asserts the notice is grounded(2) (the two DISTINCT loop-admitted uids), never the general-knowledge wording."
  - "ADMITTED-SET ACCOUNTING (P1): the notice's grounding set counts pre-fetch uids only when the block was NOT dropped, plus loop uids collected from the FITTED fold-back (ChatAgent.java:1188-1190, unchanged); ChatAgentTest.droppedRetrievalBlockKeepsGeneralKnowledgeProvenance's MAIN arm passes unchanged — dropped block with no loop call still yields the general-knowledge notice, never a count for posts the model never saw."
  - "CORNER PIN AMENDED (P2, §8-authorized in test_plan.modifies): the same test's corner arm (post-drop model-elected searchPosts result folded in) now asserts the notice is the grounded count of admitted uids (grounded(1) on the current fixture: fold-1), replacing the dispositive-drop expectation at ChatAgentTest:2675-2677; the arm's other assertions (big-0 absent from the first prompt, fold-1 present in the second) pass unchanged."
  - "CACHE RE-ENTRY (P4, failure-mode): a new ChatAgentProvenanceTest drive passes — after a dropped block, a model-elected IDENTICAL semanticSearch call (served from the shared per-turn TurnContext cache) folds its result and grounds the turn via the loop set; the drive asserts the notice is the grounded count and that the dispatcher served the call from the cache (single execution)."
  - "CLARIFY RULE UNCHANGED (P3): ChatAgentTest.droppedRetrievalBlockKeepsGeneralKnowledgeProvenance's MARGINAL arm passes unchanged (dropped marginal block, no loop grounding -> general-knowledge notice, no clarify directive in the prompt), and the pre-existing clarify null-notice pins (clarifyTurn && !semanticDropped -> null) pass unchanged."
  - "DEGRADE NULL-NOTICE PATHS UNCHANGED: each of ChatAgentProvenanceTest's breaker-open, llmFailure, inFlight, ceilingGated and cancelledTurn tests passes unchanged; memoryToolResultsDoNotGroundTheTurn and duplicateUidsAcrossPreFetchAndLoopCountOnce pass unchanged (a kept-block turn still counts the DISTINCT pre-fetch+loop union)."
  - "SPEC AMENDMENT rides the diff (P6; engineering-rules §12 — exact wording approved by the user at implementation; rule-text draft in Approach): docs/spec/commands.md §Chat mode's dropped-block sentence is amended so the notice reflects what the conversation actually carries — later model-initiated retrieval results folded into the conversation ground the turn with the admitted count; when nothing was folded in, the notice is the not-grounded one. Probe: the amended §Chat mode paragraph states the admitted-count rule and grep of the paragraph for ticket IDs or dates returns nothing (spec states rules only)."
  - "DESIGN RECORD: docs/design/05-llm-and-embeddings.md §5.4.6 D58 notice section records the admitted-set rule (pre-fetch counted only when its block was admitted; loop collected from fitted text — the M1-923 honesty posture extended to the pre-fetch/drop interaction); git diff --stat docs/ shows exactly docs/spec/commands.md and docs/design/05-llm-and-embeddings.md."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
      — loopGroundedTurnMustNotClaimGeneralKnowledge (the parked family
      reproduction, landed; its builder stub reports
      CompactionReport(…, semanticBlockDropped=true)) and the cache
      re-entry drive (identical post-drop semanticSearch grounds via the
      loop set, single cached execution). The landed javadoc carries NO
      prod identifiers (scrubbed per the analysis P16).
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      — AUTHORIZED (§8): droppedRetrievalBlockKeepsGeneralKnowledgeProvenance
      corner arm (:2640-2677) flips its notice expectation from
      CHAT_PROVENANCE_GENERAL_KNOWLEDGE to the grounded count of admitted
      uids (grounded(1) on the current fixture), with the failure message
      updated to state the amended rule; the arm's prompt-shape
      assertions and the test's main/marginal arms are unchanged. This
      ticket changes the spec'd notice behavior (acceptance item 7), which
      requires updating exactly this pinned expectation.
  preserves:
    - all tests currently green on main, explicitly the main and marginal
      arms of droppedRetrievalBlockKeepsGeneralKnowledgeProvenance, every
      ChatAgentProvenanceTest assertion (its rig stubs
      semanticBlockDropped=false and is unaffected), and
      InboundRouterChatModeIT's empty-retrieval outbound pin
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D58
  - D31
  - D43
  - D19
---

# M1-965: Make the provenance notice count only admitted grounding

## Context

Live on the prod instance 2026-08-31 00:54 UTC (DM scope; identifiers in
the analysis doc, never here): a reply citing four real feed posts —
recovered by a model-initiated post-corpus tool call after the
compaction ladder had dropped the pre-fetch retrieval block ("dropped
historyTurns=92 … semanticBlock=true; estimated 15648 -> 4462 tokens
(budget 6144)") — shipped the notice "Not based on your feed posts;
answered from general knowledge." Both clauses were false about the
reply the user read. Long sessions hit this path on most turns. The
spec sentence that pins the wrong behavior (commands.md §Chat mode, the
M1-918 dispositive-drop clause) was a 2026-08-23 user decision whose
premise the live turn falsified; this ticket reverses it openly (see
Approach). Analysis: `analysis_ref:`.

## Root cause

`retrievedPostUids` (ChatAgent.java:574) is the turn-wide union of the
pre-fetch uids (collected at `:969`, BEFORE the drop decision exists)
and the loop uids (collected from the FITTED fold-back at `:1188-1190`).
The notice decision (`:877-888`) computes `groundedInPrompt =
!retrievedPostUids.isEmpty() && !semanticDropped` — the `!semanticDropped`
conjunct discounts the ENTIRE union, including loop uids that WERE
admitted into the conversation, so a loop-grounded dropped-block turn
ships a not-grounded notice. The project already decided (M1-923, user
decision 2026-08-23: "grounding accounting matches what the prompt
actually carries") the equivalent rule for the fold-back: collectPostUids
runs on the fitted text. This ticket extends that rule to the
pre-fetch/drop interaction: pre-fetch uids count only when their block
was admitted.

## Pitfalls

Carries P1-P6, P16, P17 of the analysis.

- P1: inverted over-claim — dropping the conjunct without splitting the
  sets counts pre-fetch uids the model never saw (the M1-923 over-claim
  mirrored). Violates the M1-923 honesty posture and the D58 "always
  tell" promise in the other direction.
- P2: the corner pin at ChatAgentTest:2675-2677 states the dispositive-
  drop expectation verbatim; amending it without §8 authorization in
  test_plan.modifies is an engineering-rules §8 violation.
- P3: clarify ambiguity — the null notice must keep meaning "the clarify
  directive rode the prompt" (`clarifyTurn && !semanticDropped`); on a
  dropped-block turn the directive was stripped (ChatAgent.java:719-721)
  so the clarify decision is moot for the notice.
- P4: cache re-entry — the identical model-initiated semanticSearch is
  served from the shared TurnContext cache (M1-589 pin) and its result
  IS admitted; the two-set split must not exclude it.
- P5: notice prose discipline — bundle path in scope language, count-only
  interpolation, never the translator (D31/D43); no bundle keys change.
- P6: the spec amendment REVERSES a user-approved sentence; it must be
  rule-text only (no dates/IDs), user-approved at implementation, and the
  reversal stated — never slipped in as a doc sync (engineering-rules
  §12).
- P16: the landed tests and this ticket carry no prod identifiers
  (user id, seq numbers, log excerpts stay in the gitignored analysis/
  .scratch stores).
- P17: same module as M1-966/M1-967 — land serially.

## Approach

Derived from `spec_refs:` — commands.md §Chat mode's primary D58
commitment ("count of distinct posts consulted … pre-fetch plus
model-initiated post-corpus tool calls"; "the user can always tell") is
the behavior being restored; llm.md §Determinism boundary keeps the
notice decision pure Java.

- **Files to touch:** `files_scope`.
- **Pre-decided shapes (implementation is execution):**
  1. Split the uid sets in `ChatAgent.doHandle`: `buildSemanticRetrievalBlock`
     collects into a pre-fetch set (today's `:969` call); the tool loop
     keeps collecting into the loop set from the FITTED text (`:1189`
     unchanged). The notice's admitted set is
     `semanticDropped ? loopSet : (preFetchSet ∪ loopSet)`; `clarifyTurn`
     derivation (`:604-605`) reads the pre-fetch set (equivalent today).
  2. Amend the ChatAgentTest corner arm per `test_plan.modifies`.
  3. Land the two ChatAgentProvenanceTest drives (reproduction + cache
     re-entry), scrubbing the parked rig's javadoc of prod identifiers.
  4. Spec + design amendments last.
- **Spec amendment rule-text draft (§12 — wording approved by the user
  at implementation; replaces the sentence "A turn whose retrieval block
  was dropped takes the general-knowledge path: its provenance notice is
  the not-grounded one even when later model-initiated retrieval results
  were folded into the conversation (D58)." in commands.md §Chat mode):**
  "Grounding follows what the conversation actually carries: a turn
  whose retrieval block was dropped counts none of that dropped set —
  later model-initiated retrieval results folded into the conversation
  ground the turn and carry the grounded notice with the admitted
  count; when nothing was folded in, the notice is the not-grounded
  one (D58)." This reverses the 2026-08-23 dispositive-drop decision on
  the ground of the live falsification; the user decides the final
  wording.
- **Steps, in order:** land the reproduction RED-confirmed at `/tick
  start` → set split (shape 1) → corner-arm amendment (shape 2) → new
  drive (shape 3) → spec + design (shape 4) → module test run →
  `mvn verify`.
- **Controls to preserve (§10):** enumerated in the analysis — notice
  bundle path/count-only interpolation (D31/D43); the six null-notice
  degrade paths; sanitize→strip→refusal order (untouched, request-side
  only); the M1-923 fitted-collection semantics (this ticket's
  foundation, not to be reverted); breaker-open → general-knowledge
  (empty block is never dropped, so the path is unchanged).
- **Pitfall→mitigation:** P1→shape 1 + main arm green; P2→test_plan
  .modifies; P3→acceptance item 5; P4→the cache drive; P5→rig asserts
  exact keys; P6→shape 4's explicit record; P16→scrubbed javadocs;
  P17→serial landing.

## Definition of done

Every acceptance item verified by its named test/probe: the landed
reproduction passes with the grounded count; the main/marginal arms and
all ChatAgentProvenanceTest assertions pass unchanged; the corner arm
asserts the admitted count under §8 authorization; the cache re-entry
drive passes; the spec paragraph and design section carry the amendment
(probe-clean of dates/IDs); `mvn verify` green from the repo root.

## Verification

- P1 → ChatAgentTest.droppedRetrievalBlockKeepsGeneralKnowledgeProvenance
  main arm (hostile shape: block dropped, no loop grounding — asserts
  the notice must NOT claim a count) + the landed reproduction
  (loop-grounded turn must NOT claim general knowledge).
- P2 → the amended corner arm asserts grounded(1); authorization lives
  in test_plan.modifies, so a reviewer can distinguish spec-mandated
  flip from test-bending.
- P3 → marginal arm green (dropped marginal block, no directive in
  prompt, general-knowledge notice); pre-existing clarify null-notice
  pins green.
- P4 → ChatAgentProvenanceTest cache re-entry drive (failure-mode: the
  drop must not leak into the cached loop path; asserts single cached
  execution AND the grounded notice).
- P5 → drives assert the exact BundleKeys constants and the {0} count
  interpolation (the rig's MessageFormat pattern).
- P6 → acceptance item 7's probes (paragraph states the rule; no
  dates/IDs).
- P16 → probe: grep the landed test files and this ticket for the prod
  user id / chat seq numbers returns nothing.
- P17 → serial landing: the module test run names this ticket's suites
  only; no sibling ticket is in flight in this module at start.
- acceptance items 1-6, 8, 9 → the named tests, the diff-stat probe,
  `mvn verify`.

## Out-of-scope

Named in `out_of_scope`: the ladder and its unit (M1-966); the
refinement-directive stripping (pinned, no live evidence against it);
POST_CORPUS_TOOLS membership and any bundle-key or tool change
(M1-967's lane); history-quoted-post counting (deferred — the analysis
Census in M1-967 disposes it). This ticket modifies ONE pre-existing
test file (§8 authorization in `test_plan.modifies`): the ChatAgentTest
corner arm. Any other pre-existing assertion that conflicts is a
start-hurdle escalation, not a silent edit.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-965-chat-provenance-notice-truthfulness.md
```
