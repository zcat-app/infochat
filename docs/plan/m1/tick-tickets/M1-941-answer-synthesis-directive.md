---
id: M1-941
title: "Answer-shaping synthesis directive in the chat prompt"
status: pending
created: 2026-08-26
last_updated: 2026-08-26
flow: tick
reproduction: >-
  ChatPromptBuilderTest#groundingFramingDemandsASynthesizedAnswerNotAList
  `to-be-written` (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/answer-synthesis-language-pinning.md; /tick
  start converts the marker: write the test, run it RED against the
  unmodified code before any fix code, workflow §0). The wrong behavior
  it states: the system prompt frames grounding as cite-and-ground but
  NEVER demands an answer synthesized from post content — verified by
  reading CHAT_SYSTEM_PROMPT_TEMPLATE end to end
  (ChatPromptBuilder.java:64-87; the grounding clause at :67-72 says
  "ground your answer in them and cite every post you rely on by its
  bare source URL" and nothing anywhere in the template,
  TOOL_INSTRUCTIONS (ChatAgent.java:114-127), or
  POST_TOOL_RESULT_INSTRUCTION (:132-135) asks the model to ANSWER the
  question from the posts' content rather than enumerate them). Live
  observation motivating the change (user, 2026-08-26, brief-carried —
  output observation): chat answers arrive as post lists; with M1-940
  landed the entries carry body_summary content, but no instruction
  tells the model to use it. RED pre-change: the synthesis-element
  assertion fails against today's template; the citation-element
  assertion (the co-survival arm) passes today and must KEEP passing.
analysis_ref: docs/plan/m1/tick-analysis/answer-synthesis-language-pinning.md
blocked_by: [M1-940]
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY new model call (binding user decision): no summarizer pass over
    the retrieved set, no re-rank, no abstractive pipeline — the existing
    ModelTask.CHAT_AGENT call stays the chat path's only call; the
    multi-document-abstractive class is out of v1's scope per the brief.
  - >-
    M1-940's emission surface (this ticket CONSUMES the body_summary
    field; it does not add or change it) and any tool/catalog change:
    ChatToolCatalog.java, the wire declarations, and the byte-pinned
    instruction table are UNTOUCHED — renderedInstructionTableIsByteIdentical
    passes UNMODIFIED.
  - >-
    The per-turn directives (CLARIFY/AFFORDANCE/DETERMINISTIC_DELIVERY)
    and M1-939's native pins / M1-938's window hint — this ticket adds
    NO per-turn bytes: non-grounded turns, clarify turns, and
    deterministic-delivery turns see the same prompt they see today
    except the two amended instruction sites that ride every turn.
  - >-
    The citation discipline's SUBSTANCE: the bare-URL demands at both
    M1-857 sites survive verbatim in meaning (the amendment extends the
    surrounding sentences; it never replaces, weakens, or rewords the
    citation demand itself — design 05:851-867).
  - >-
    Digest, /summary, rollup, and every other prose surface (the D62
    digest precedent is the PATTERN this mirrors in chat, not a surface
    to change); the sanitizer and streamer (the directive's output rides
    the existing sanitize/strip/streamed regime unchanged); the eval
    lane (M1-928/929/930 — answer quality is not CI-measured, analysis
    P19; the owner-run probe is the reference).
  - >-
    Final wording authority: the exact sentences ride the diff and the
    user approves them at implementation (the brief's engineering-rules
    §12 posture applied to prompt text); this ticket pins the SEMANTIC
    elements, not the final prose.
acceptance:
  - "REPRODUCTION closed: ChatPromptBuilderTest.groundingFramingDemandsASynthesizedAnswerNotAList passes — the built system prompt carries a synthesis element asserting ALL of: answer the user's question directly (an answer, not a list/enumeration of posts), use the retrieved posts' content (their facts, figures, quotations — available via the entries' body_summary and getPost), and keep the bare-URL citation demand; the citation co-survival arm asserts the M1-857 framing demand is still present in the SAME clause (a synthesis-only rewrite that drops citation fails it)."
  - "Fold-back site: ChatAgentTest's POST_TOOL_RESULT_INSTRUCTION pins (:336-349, :1025 — contains-based) keep passing with the amended constant, and a NEW assertion (extending the :1025 test or a sibling) pins the folded prompt carrying BOTH the synthesis clause AND the bare-URL demand after a tool result — probe: grep -n 'Cite each post' infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java returns the surviving citation sentence inside the amended constant."
  - "FAILURE-MODE (clarify precedence, analysis P18): ChatAgentTest.lowConfidenceGroundingTriggersClarifyDirective, ChatAgentTest.confidentGroundingSurfacesMoreLikeThisAffordanceAndDoesNotClarify, and ChatAgentTest.emptyRetrievalInjectsNoRefinementDirective (the clarify/affordance selection pins, assertions at :1112-1152) pass UNMODIFIED — a marginal turn still appends CLARIFY_DIRECTIVE (do-not-answer-yet) whose precedence over the always-present framing is preserved by the framing's own conditionality (the synthesis element is worded as answer-SHAPE for when the model answers, never as an instruction to answer now); the wording review at implementation confirms the two sentences can be simultaneously true."
  - "FAILURE-MODE (no per-turn drift, analysis P16): a non-grounded general-knowledge turn's user prompt is byte-identical to today (no new per-turn bytes — the directive lives only in the two every-turn instruction sites); ChatPromptBudgetTest and the M1-918 corner tests pass UNMODIFIED; renderedInstructionTableIsByteIdentical passes UNMODIFIED — probe: git diff names no ChatToolCatalog.java hunk."
  - "Budget ledger (analysis P16; the M1-916 absorbed-by-headroom precedent): the two amended sites add ~2 sentences of never-drop instruction text (~40 tokens/turn), recorded in docs/design/05-llm-and-embeddings.md §5.4.6's prompt-budget ledger, and the citation-discipline paragraph (:851-867) is extended to record the third property the two sites now carry (synthesize an answer, cite bare URLs, never invent/modify) — probe: grep -n 'synthesi' docs/design/05-llm-and-embeddings.md returns the §5.4.6 mentions."
  - "Truthness ordering (analysis P17): this ticket lands AFTER M1-940 (blocked_by) — the synthesis element's content reference (the entries' body_summary) is truthful at landing; probe: the landed M1-940 spec rows carry body_summary (grep -n 'body_summary' docs/spec/security.md returns both tool rows) before this diff's wording review."
  - "OWNER-RUN live probe (verification ceiling, the M1-916/M1-927 posture — no unit test can prove register change; phrased owner-run with a recorded outcome, never claimed as a unit result): after landing, the owner re-asks a fact-bearing question (the class that motivated the campaign — e.g. a price/number question the feed answers) on the deployment and captures the reply: an ANSWER stating the fact with its cited source URL PASSES; a bare post list (or a fact-free enumeration) FAILS; the before/after record goes to the ticket/commit. The eval gap is stated honestly: M1-928 is retrieval-focused and does not measure answer quality (analysis P19) — no CI claim is made."
  - "mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
      — groundingFramingDemandsASynthesizedAnswerNotAList (the
      reproduction, with its citation co-survival arm).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      — the fold-back dual assertion (synthesis clause + bare-URL demand
      in the folded prompt).
  modifies: []
  preserves:
    - >-
      all tests currently green on main — explicitly the clarify/
      affordance selection pins, ChatPromptBudgetTest, the M1-918 corner
      tests, renderedInstructionTableIsByteIdentical, the M1-927 header
      pins, and (once landed) M1-939's pin tests and M1-938's
      byte-identity pin, unmodified.
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D21
  - D58
  - D62
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

# M1-941: Answer-shaping synthesis directive in the chat prompt

## Context

Even with retrieval fixed (the campaign's earlier topics) and content
surfaced (M1-940, this ticket's blocker), nothing in the prompt tells
the model to ANSWER rather than enumerate: the system template frames
grounding as cite-and-ground (ChatPromptBuilder.java:67-72) and the
post-tool instruction says "provide your response based on the tool
result above" (ChatAgent.java:132-135) — both are satisfied by a post
list with URLs, which is what the user keeps receiving live. The user
wants ask-a-question → get-a-summarized-ANSWER grounded on the retrieved
set, citing URLs per the existing discipline — the digest's per-cluster
prose is the in-house precedent (D62: deterministic structure, LLM only
for prose), here carried to the chat turn with ZERO new model calls.
Evidence and the falsified alternatives are in the analysis,
`analysis_ref:`.

## Root cause

Verified: no synthesis/answer-shaping demand exists anywhere in the
prompt surfaces (template, TOOL_INSTRUCTIONS, POST_TOOL_RESULT_INSTRUCTION,
the per-turn directives — all read end to end; the reproduction's
in-tree verification). The register defect is therefore unprompted, not
blocked: the model defaults to enumeration because enumeration satisfies
every instruction it is given. What is NOT proven: that ~2 sentences
change gemma's register — the owner-run probe's question (P19), stated
honestly in the acceptance.

## Pitfalls

Numbered per the analysis document; this ticket carries P15-P19 (plus
P20's landing-order context).

- P15: citation discipline must not weaken — M1-857's two-site wording
  is pinned (design 05:851-867; ChatAgentTest:339/:1025); the synthesis
  element EXTENDS the framing clause and the fold-back instruction; the
  bare-URL demands survive verbatim at both sites, asserted by the
  co-survival arms.
- P16: placement and budget — the directive rides never-drop
  scaffolding (the system template reaches every call incl. the
  iteration-cap final call and both transports; the fold-back
  instruction reaches every model-initiated tool turn); ~2 sentences ≈
  ~40 tokens absorbed by headroom (the M1-916 ledger precedent); NO
  per-turn growth — non-grounded turns byte-identical.
- P17: truthness ordering — `blocked_by: [M1-940]`; the content
  reference (body_summary in entries) must exist before the prompt
  demands its use.
- P18: CLARIFY coexistence — marginal turns keep the do-not-answer-yet
  precedence; the synthesis element is answer-SHAPE (conditional on the
  framing's "when the prompt includes posts…" grounding clause), never
  an instruction to answer now; the clarify pins stay green unmodified.
- P19: eval honesty — M1-928 is retrieval-focused; answer quality is
  not CI-measured; acceptance is the unit pins + the owner-run probe;
  no overclaim.
- P20: landing order — last of the campaign (after M1-939/940 and the
  earlier siblings); the two amended sites are byte-coordinate with
  M1-939's pins (different sites — no overlap) and M1-938's hint
  (per-turn vs every-turn — no overlap).

## Approach

Derived from `spec_refs:` — commands.md §Chat mode's grounding and
refinement paragraphs (:1817-1931) govern the reply-shaping behavior
this directive implements (ground + cite + refine); llm.md §Determinism
boundary (:463-480) confines the LLM's role to prose (the directive
shapes PROSE, never the retrieved set — the D62 digest pattern carried
to chat).

- **Files to touch:** `files_scope` (two production prompt sites, two
  test files, one design doc).
- **Pre-decided shapes (implementation is execution):**
  1. **System template** (ChatPromptBuilder.CHAT_SYSTEM_PROMPT_TEMPLATE,
     the :67-72 grounding clause): append one sentence of the shape —
     "When you ground an answer in retrieved posts, ANSWER the user's
     question directly: state the specific facts, figures, or
     quotations the posts' content carries (each search entry's
     body_summary, or getPost for the full body), synthesizing one
     coherent answer rather than listing or enumerating posts." (EXACT
     wording rides the diff, user-approved; the semantic elements the
     tests assert: answer-not-list, use-the-content, the content's
     locations, and the surviving citation demand in the same clause.)
  2. **Fold-back instruction** (ChatAgent.POST_TOOL_RESULT_INSTRUCTION,
     :132-135): extend with one clause of the shape — "Provide your
     response based on the tool result above as a direct, synthesized
     answer to the user's question — not a list of results." — keeping
     the citation sentences intact (the constant's citation bytes are
     the pinned M1-857 substance; probe in acceptance item 2).
  3. Tests per `test_plan.adds`; no modification to any pre-existing
     test (the contains-based pins absorb the amended constants).
  4. Design-05 §5.4.6 sync: the ledger sentence + the
     citation-discipline paragraph's third property.
- **Steps, in implementation order:** (1) confirm M1-940 landed (the
  truthness probe); (2) write the reproduction + the fold-back dual
  assertion RED; (3) the two wording amendments with the user's
  approval of the exact sentences; (4) design-05 sync; (5) full `mvn
  verify`; (6) hand the owner-run probe to the user with its record
  obligation.
- **Controls to preserve (engineering-rules §10):** the output regime
  is untouched — sanitize → strip → refusal intercept → mode leg →
  accretions → degrade → provenance all byte-identical in both modes;
  more content quotation in replies is exactly the load the sanitizer's
  closed-list/scaffolding/streamed passes already carry (a quoted
  command-shaped token from a body is redacted and audited as today);
  the wrappers, the streamer, and the citation discipline's substance
  survive.
- **Alternatives considered (rejected, for the commit message):** a
  per-turn summarizer pass (O-C2 — binding user reject: no new model
  calls); directive-only without M1-940's content (O-C4 — demands what
  the emission cannot feed; superseded by the blocked_by order);
  catalog-description steering (the M1-916 lane — wrong site: it shapes
  TOOL CHOICE, not answer register); per-turn directive on grounded
  turns only (rejected on budget/P16 uniformity — the framing clause is
  already conditional, and an extra per-turn site would collide with
  the M1-938/M1-939 tail region).

## Definition of done

The reproduction passes with its citation co-survival arm; the fold-back
dual assertion passes; the clarify pins, the budget/corner tests, and
the byte-pinned instruction table pass UNMODIFIED; the design-05 ledger
and citation-discipline paragraph carry the third property; the
truthness probe confirms M1-940's rows; the owner-run probe is handed
over with its before/after record obligation; mvn verify is green from
the repo root.

## Verification

- P15 → the reproduction's co-survival arm + acceptance item 2's grep
  and dual assertion (a citation-dropping rewrite reds them).
- P16 → acceptance item 4's byte-identity and UNMODIFIED probes + the
  ledger grep (item 5).
- P17 → item 6's blocked_by + truthness probe.
- P18 → item 3's unmodified clarify pins + the implementation-time
  wording review.
- P19 → item 7's owner-run phrasing with its named PASS/FAIL conditions
  and the honest eval-gap note.
- P20 → the reviewer's diff fence (two production files' prompt sites
  only; no catalog/streamer/tool file).
- FAILURE-MODE coverage → items 3 (clarify precedence) and 4
  (non-grounded byte identity) assert the protected behaviors around
  the amended instructions; the co-survival arms feed the hostile
  mutation (citation drop) and catch it.
- acceptance items 7-8 → the owner-run protocol and mvn verify.

## Out-of-scope

Named in `out_of_scope`: any new model call (binding); M1-940's emission
surface and any tool/catalog change (the instruction table is
byte-pinned and untouched); the per-turn directives and the siblings'
pins (no per-turn bytes added); the citation discipline's substance
(extended, never weakened); digest//summary/rollup surfaces; the
sanitizer and streamer; the eval lane; final wording authority (the
user approves the exact sentences at implementation — this ticket pins
the semantic elements). No pre-existing test is modified; two new tests
are added.

## Census

Not class-scoped: two named prompt sites are amended, not a class of
defect sites. (The register defect has exactly two instruction
surfaces, both enumerated in the reproduction.)

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-941-answer-synthesis-directive.md
```
