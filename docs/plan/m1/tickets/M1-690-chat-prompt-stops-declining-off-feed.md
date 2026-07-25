---
id: M1-690
title: "Chat prompt stops declining off-feed questions"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatPromptBuilder.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
  - docs/design/05-llm-and-embeddings.md
complexity: low
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Adding a /llm, /ask or /chat command. The general-knowledge path
    already exists as the empty-retrieval branch of the normal chat turn
    (ChatAgent.doHandle sets refinementDirective = "" when the pre-fetch
    returns nothing); a command would only force a path that already runs.
    Explicitly decided against on 2026-07-25.
  - >-
    ChatAgent's retrieval, band logic and thresholds —
    CONFIDENT_SIMILARITY_CUTOFF, infochat.chat.semantic-threshold,
    isMarginalGrounding, CLARIFY_DIRECTIVE, AFFORDANCE_DIRECTIVE. The
    bands were calibrated by M1-616/M1-619 and are not re-opened here.
  - >-
    The [REFUSAL: <reason>] protocol and its interceptor
    (ChatAgent.java:499). The structured refusal for prompt-injection
    attempts MUST keep working exactly as it does; this ticket must not
    make the model less willing to emit it.
  - >-
    The UNTRUSTED_CONTENT wrapper and the injection-refusal instructions
    in the same prompt template. Their wording is a security control and
    is not to be softened or reflowed while editing the adjacent framing
    sentence.
  - >-
    The retrieval-provenance notices (D58). A general-knowledge answer
    must still carry reply.chat.provenance.general_knowledge.
acceptance:
  - >-
    CHAT_SYSTEM_PROMPT_TEMPLATE no longer frames the assistant with a
    scope-declaring clause that a model can read as a topic restriction,
    and states explicitly that a question must not be declined merely
    because it is unrelated to the user's feed.
  - >-
    The prompt's injection-defense half is byte-identical to today: the
    UNTRUSTED_CONTENT wrapper description and the
    "[REFUSAL: <reason>]" instruction are unchanged. A
    ChatPromptBuilderTest assertion pins that those sentences are still
    present verbatim, so a future prompt edit cannot silently drop them.
  - >-
    A ChatPromptBuilderTest assertion pins the new never-decline-off-topic
    instruction, so the behavior this ticket buys cannot regress
    unnoticed.
  - >-
    SummaryProseInjectionTest and the existing chat prompt-injection tests
    stay green — the prompt remains injection-resistant.
  - mvn verify from the repo root is green
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatPromptBuilderTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Chat mode
  - docs/spec/llm.md §Prompt-injection-aware prompt shape
decision_refs:
  - D21
  - D58
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-690: Chat prompt stops declining off-feed questions

## Context

Filed from live SimpleX testing on 2026-07-25. Asked "what should the
weather be tomorrow in Prague", the bot declined on the grounds that the
question was outside the scope of the ingested posts.

The refusal was **model-authored prose**, not a deterministic reply. No
bundle string in `en.properties` or `cs.properties` says anything of the
kind; the only deterministic refusal is the `[REFUSAL:` interceptor at
`ChatAgent.java:499`, which emits the fixed
`error.chat.refused=I can't help with that request.` — not what the user
saw.

Nor was it a threshold decision. An off-domain query returns nothing from
the pre-fetch (the M1-619 calibration sweep measured off-domain probes at
similarity 0.48–0.59, below the 0.60 grounding floor), so
`retrievedPostUids` is empty, `refinementDirective` is set to `""`
(`ChatAgent.java:422`), and the turn is already a plain general-knowledge
LLM call with no retrieval context and no directive attached.

So the capability is present and correctly reached — the model simply
declined anyway. The deployed build is not the cause either: the
general-assistant prompt landed in `ec5b4b8f` (M1-589, 2026-07-11) and is an
ancestor of `HEAD`, and the running container was built after it.

What remains is the prompt's own framing. `CHAT_SYSTEM_PROMPT_TEMPLATE`
opens with *"You are a helpful general assistant for a news-aggregation chat
service"* (`ChatPromptBuilder.java:37`) — a clause a compliance-tuned model
can read as a topic restriction — and nothing in the template tells it not
to decline off-topic questions. The template does already say *"answer from
your own general knowledge"* when no posts are retrieved, which is why this
is a wording fix rather than a behavioral redesign.

## Acceptance

See the frontmatter. The scope-declaring framing goes, an explicit
never-decline-merely-for-being-off-topic instruction arrives, the
injection-defense half of the prompt is provably untouched, and tests pin
both halves.

## Out-of-scope

A `/llm` command (decided against — see below), the retrieval bands and
thresholds, the `[REFUSAL:` protocol, the `UNTRUSTED_CONTENT` wrapper
wording, and the D58 provenance notices. See the frontmatter.

## Notes

- **Why no `/llm` command.** It was the user's first instinct on
  2026-07-25 and was rejected on inspection: there is no "RAG, then fall
  back to the LLM" branch to bypass. Every free-text turn is an LLM turn;
  the pre-fetch only decides what context and which directive get folded
  into the prompt. A `/llm` command would force a path that already runs
  for exactly these queries, while costing a spec amendment
  (`docs/spec/llm.md` §Determinism boundary enumerates the allowed LLM
  uses and does not list open-domain Q&A), a `CommandPermissions` decision,
  its own `LlmRateCap` draw to avoid becoming a documented bypass, and a
  D58 provenance notice. The prompt fix covers every free-text turn instead
  of only the users who know to type a command.
- The one thing `/llm` would genuinely add is an escape from the marginal
  band, where `CLARIFY_DIRECTIVE` makes the bot ask a clarifying question
  instead of answering. If that turns out to be the real irritant in
  practice, file it then, on that evidence — not pre-emptively here.
- `security_relevant: true` is set because the file being edited is the
  chat system prompt, which carries the D21 injection defenses. The edit
  itself targets an adjacent framing sentence, but the surface warrants the
  `/redteam` gate: a prompt reflow that weakens the refusal instruction
  while widening the assistant's remit is exactly the mistake worth
  checking for.
- There is a second, weaker pull toward refusal the implementer should look
  at while in the file: `ChatAgent.TOOL_INSTRUCTIONS`
  (`ChatAgent.java:86`) carries a "say you do not know" directive scoped to
  `helpLookup` that a model can over-generalize. `ChatAgent.java` is
  deliberately NOT in `files_scope` — if the fix turns out to need it, that
  is an escalation, not a quiet scope expansion.
- Adjacent code: `ChatPromptBuilder.CHAT_SYSTEM_PROMPT_TEMPLATE`,
  `ChatAgent.doHandle` steps 3/3b.
- Relevant design note: `docs/design/05-llm-and-embeddings.md` §5.4.6
  carries the design-tier chat prompt, which should stay consistent with
  the code-tier template.
