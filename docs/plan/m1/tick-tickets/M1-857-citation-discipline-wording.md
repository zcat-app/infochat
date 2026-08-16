---
id: M1-857
title: "Citation-discipline wording on grounded and tool-result turns"
status: pending
created: 2026-08-16
last_updated: 2026-08-16
flow: tick
reproduction: >-
  to-be-written: ChatAgentTest#aToolResultTurnCarriesTheCitationDemand
  (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/tool-loop-hardening.md). Probe of today's
  wrong behavior: grep -n 'G5\|citation'
  docs/measurement/direct-chat-e2e.md shows the binding defect rows —
  gemma 13 single-cell expected-citation misses + 1 URL-hallucination
  cluster (:155-159) and the incumbent's 7-11 per cell (:159-161), the
  zero-L0 conjunct that FAILS every (gemma, language) pair (:260-273) —
  while the framing's existing "cite each post you use by its bare
  source URL" (ChatPromptBuilder.java:44-46) demonstrably does not
  produce citations, and the loop's post-result instruction line
  (ChatAgent.java:835) demands none.
analysis_ref: docs/plan/m1/tick-analysis/tool-loop-hardening.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    REPLY_LANGUAGE_DIRECTIVE and any mode/direct-mode wording — M1-845/M1-848
    own that surface; the citation wording is mode-independent reply-shaping
    prose (P7).
  - >-
    TOOL_INSTRUCTIONS, the emission grammar, and stripToolCalls — M1-856's
    constants; this ticket touches only the framing sentence and the
    post-tool-result instruction line (disjoint constants; land after or
    before M1-856, never in parallel — same Maven module).
  - >-
    CLARIFY/AFFORDANCE/DETERMINISTIC_DELIVERY semantics and trigger
    thresholds — the citation demand must not alter when those directives
    fire or what they say.
  - >-
    DETERMINISTIC CITATION ASSEMBLY — composing citations in Java from
    retrievedPostUids or interpolating URLs into replies is a different
    design (a post-sanitize accretion in the D67/D69 class, needing its own
    spec surface) and is rejected in the analysis (option O6); wording only
    here.
  - >-
    LlmOutputSanitizer and the plain-text/bare-URL rules — unchanged; the
    wording works WITH them (bare URLs, no markdown links).
  - >-
    Any docs/spec/** edit (design-tier wording) and any behavioral
    bar-clearing claim — the wording's EFFECT is measured by M1-858, never
    asserted by this ticket.
acceptance:
  - "ChatAgentTest.aToolResultTurnCarriesTheCitationDemand (the reproduction, written and run RED at start) passes — on a model tool-call turn, the prompt the provider receives after a tool result demands citing each relied-on post by its bare source URL exactly as the tool result provided it, and forbids inventing or modifying URLs."
  - "ChatPromptBuilderTest.strengthenedFramingDemandsVerbatimBareUrlCitations passes — the framing sentence (ChatPromptBuilder CHAT_SYSTEM_PROMPT_TEMPLATE) demands a bare-URL citation for EVERY post the answer relies on, states the URL is copied exactly from the retrieved post or tool result, and explicitly forbids constructing or altering a URL (the tr g12 mutated-URL cluster, P12) — the strengthened sentence rides the chat-reply surface governed by docs/spec/commands.md §Chat mode inside the prompt conventions of docs/spec/llm.md §Prompt-injection-aware prompt shape."
  - "The injection-defence block survives byte-verbatim: ChatPromptBuilderTest.systemPromptInjectionDefenseHalfIsPreservedVerbatim passes UNCHANGED — it pins the UNTRUSTED_CONTENT wrapper description and the exact '[REFUSAL: <reason>]' instruction verbatim (the M1-589/M1-690 preservation pin; the ChatAgentRefusalInterceptTest prefix intercept depends on the token) — probe: git diff infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java shows those two defence paragraphs as context lines only, never added or removed."
  - "ChatAgentTest.aClarifyTurnCarriesNoCitationDemand passes — FAILURE-MODE: a marginal-grounding CLARIFY turn's prompt does not acquire the citation demand (a narrowing question cites nothing); the CLARIFY/AFFORDANCE trigger semantics are unchanged."
  - "ChatAgentTest.citationWordingNeverQuotesPostContent passes — FAILURE-MODE (P11): the added wording contains no post title, URL, or other feed-derived literal — it refers to posts abstractly, in the trusted region (the M1-618 hygiene posture; security.md §Prompt-injection defenses)."
  - "Every pre-existing ChatPromptBuilderTest / ChatAgentTest case passes UNCHANGED (§8: no pre-existing test modification is authorized)."
  - "docs/design/05-llm-and-embeddings.md §5.4.6 records the two citation-wording sites and their rationale — probe: grep -n 'citation' docs/design/05-llm-and-embeddings.md shows the §5.4.6 entry."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java (two new cases)
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java (three new cases)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
decision_refs:
  - D30
  - D58
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

# M1-857: Citation-discipline wording on grounded and tool-result turns

## Context

G5 citation omission is the actual bar-clearing blocker for BOTH models:
gemma grounded and answered well but omitted the bare URL in 13 single-cell
expected-citation misses plus one URL-hallucination cluster (tr g12: two
mutated nitter URLs), and the incumbent fails the same conjunct at 7-11
events per cell (docs/measurement/direct-chat-e2e.md:153-163) — the
zero-L0 rule FAILS every (gemma, language) pair on this class alone
(:260-273). The record's NOTE for the amendment is explicit: the failure
class is narrow citation discipline, NOT language incapacity (:277-282).
The framing already instructs citation (ChatPromptBuilder.java:44-46) and
is demonstrably too weak; the loop's post-result instruction line
(ChatAgent.java:835) demands nothing. Shared analysis: `analysis_ref:`.

## Root cause

Wording weakness on the two sites that govern grounded turns, verified in
code: (1) the framing sentence asks for citation but does not bind the URL
to the tool-returned set, does not demand verbatim copying, and does not
forbid URL construction — the two observed failure faces (omission and
mutation); (2) the post-tool-result instruction line ("Please provide your
response based on the tool result above.") is the last trusted instruction
the model sees before answering every model-initiated tool turn and carries
no citation demand at all. Both models fail identically, so the wording is
model-agnostic reply-shaping prose (design-tier), not a model-specific
adapter.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P7, P11, P12,
P14, P15.

- P1: design-tier only — no spec edit rides this diff; the wording lives in
  Java constants + design 05 §5.4.6 (commands.md:1864-1865).
- P7: no reply-language content — the citation wording never states what
  language to reply in (REPLY_LANGUAGE_DIRECTIVE is the single source;
  M1-845/848 own it).
- P11: injection hygiene — the wording refers to posts abstractly and never
  embeds feed-derived literals; it is appended in the trusted region (the
  CLARIFY/AFFORDANCE pattern, design 05:728-735).
- P12: the wording must bind cited URLs to the retrieved/tool-returned set
  VERBATIM and forbid inventing or modifying URLs (both G5 faces), while
  keeping plain text and bare URLs (D30) — no markdown-link phrasing.
- P14: same file as M1-856 — sequential landing, never --parallel;
  constants are disjoint so order is free (856 first by allocation).
- P15: tests pin only this ticket's end state (wording presence on the wire
  prompt, hygiene, clarify-turn negative); nothing pins M1-856's blocks or
  any measurement output.

## Approach

- **Files to touch:** `files_scope` — ChatPromptBuilder.java (strengthen
  the framing sentence), ChatAgent.java (the post-tool-result instruction
  line inside runToolLoop), the two test classes (new cases only), design
  05 §5.4.6.
- **Steps, in implementation order:**
  1. Write the reproduction RED (a tool-result turn's wire prompt carries
     no citation demand today).
  2. Strengthen the framing sentence: every post the answer relies on is
     cited by its bare source URL, copied exactly from the retrieved post
     or tool result; never invent, modify, or guess a URL (P12). Keep the
     injection-defence block and [REFUSAL:] token byte-verbatim.
  3. Extend the post-tool-result instruction line with the same demand in
     one sentence (the last trusted instruction before the answer).
  4. Add the negative/hygiene tests (clarify turn; no feed-derived
     literals).
  5. Update design 05 §5.4.6.
- **Controls to preserve (§10):** the sanitize→translate pipeline, the
  D43 two-path rules, the provenance notice, the directive trigger
  semantics, and the verbatim security text in the framing — all
  untouched; their pinning tests stay green unchanged.
- **Pitfall→mitigation:** P1→constants + design only; P7→wording review in
  item 2/3 (no language clause); P11→item 4's hygiene test; P12→items 1/2
  assert the verbatim/no-construction clauses; P14→frontmatter/blocked_by
  posture + landing note; P15→test_plan scope.

## Definition of done

The reproduction and all five new named tests pass; the framing's security
block is byte-unchanged; the pre-existing suites pass UNCHANGED; design 05
§5.4.6 records the two sites; mvn verify is green from the repo root. The
wording's EFFECT on G5 is explicitly NOT claimed here — M1-858 measures it.

## Verification

- P1 → git diff --stat naming exactly the two production files, the two
  test classes, and docs/design/05-llm-and-embeddings.md — no docs/spec/**
  path in the diff; the wording stays design-tier per docs/spec/commands.md
  §Chat mode (directive wording lives in design notes).
- P11 → ChatAgentTest.citationWordingNeverQuotesPostContent — asserts the
  added constants contain no feed-derived literal (hostile-shape guard).
- P12 → the reproduction + ChatPromptBuilderTest.strengthenedFraming… —
  assert the verbatim-copy and never-invent/modify clauses on the wire
  prompt (an implementation that only says "cite your sources" fails).
- P7 → wording review in items 2-3 has no language clause (grep probe on
  the diff for 'language' in the new sentences returns nothing).
- P14 → no in-tree verification (landing-order constraint recorded in the
  analysis; the /tick start gate enforces module-uniqueness for
  --parallel).
- P15 → test_plan.adds lists only this ticket's end-state pins.
- Failure mode → ChatAgentTest.aClarifyTurnCarriesNoCitationDemand — feeds
  a marginal-grounding turn and asserts the citation demand does not fire
  where it would misrepresent a narrowing question.
- acceptance items 3/6 → the existing suites byte-unchanged; items 7/8 →
  the grep probe and mvn verify.

## Out-of-scope

Named in `out_of_scope`: the reply-language/mode surface, M1-856's
constants (TOOL_INSTRUCTIONS, grammar, strip), the refinement-directive
semantics, deterministic citation assembly (analysis O6, rejected), the
sanitizer, any spec edit, and any bar-clearing claim (M1-858's job). No
pre-existing test is modified (§8).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-857-citation-discipline-wording.md
```
