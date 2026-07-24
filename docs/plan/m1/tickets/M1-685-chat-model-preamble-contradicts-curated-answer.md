---
id: M1-685
title: "Chat model text can contradict the appended curated answer"
status: pending
created: 2026-07-24
last_updated: 2026-07-24
blocked_by: []
files_budget: 5
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/HelpTopicCorpus.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/HelpCommandHandler.java
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The deterministic-delivery mechanism itself (M1-665 command-usage block,
    M1-666 curated topic-answer block) and the D69 "at most one authorized
    post-sanitize accretion per reply" rule. What the blocks CONTAIN and the
    fact that they are appended stays; this ticket only stops the model's
    free text from presenting a COMPETING answer alongside them.
  - >-
    General model-answer quality for chat turns with NO deterministic match.
    A wrong model answer to a question the corpus does not cover is a
    separate, open-ended concern and is not addressed here.
  - >-
    The reply.chat.provenance.* footer wording and the LLM output sanitizer's
    closed-list redaction (M1-676 / M1-680). Untouched.
acceptance:
  - >-
    For a chat turn that matches a curated topic answer (M1-666 delivery
    path), the curated topic block is the authoritative answer the user sees
    and the model's free-text segment does not deliver a competing/
    contradictory answer to the same question. A named test in the M1-666
    delivery suite pins the "probation" turn: the reply carries the curated
    answer and does not also carry a model-authored substitute answer.
  - >-
    The same holds for a matched command-usage delivery (M1-665): the
    deterministic usage block is authoritative and the model's free text does
    not emit a competing usage description.
  - >-
    A chat turn with NO deterministic topic/command match is UNCHANGED — the
    model answers normally with the existing reply.chat.provenance.* footer,
    and the D69 "at most one authorized post-sanitize accretion" invariant is
    preserved. A regression test covers this no-match path.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-685: Chat model text can contradict the appended curated answer

## Context

Follow-up from the 2026-07-24 live-test of the M1-620…684 fixes
(report `/home/infochat/M1-620-PLUS-LIVE-TEST-REPORT-20260724.md`, Finding
D2). The user has acknowledged this as known and non-blocking; this ticket
records it for the backlog.

Under decision D69, chat replies append at most one authorized
post-sanitize block — the M1-665 command-usage block or the M1-666 curated
topic-answer block — AFTER the model's own free text
(`en.properties` `reply.chat.help_delivery.header` /
`reply.chat.topic_delivery.header`; composed in `ChatAgent`). The
deterministic block is authoritative, but the model's free-text answer that
PRECEDES it is not constrained to defer, so it can state a substitute answer
that CONTRADICTS the block.

Observed live (DM, `user4`, "what is probation?"): the model answered
"Probation … is a temporary restriction … their messages are hidden from
other users by default — only moderators can see them" (false — infochat
probation is a limited-command-set / chat-off window), immediately before
the correct appended curated answer under "Here's how that works:". The
user is shown two conflicting answers in one reply, the wrong one first.

The deterministic delivery itself works (M1-666 verified PASS live). The
defect is that the model is still invited to answer a question the system
already answers authoritatively.

## Census

Two delivery paths append an authoritative block after model free text and
share the same "model may contradict the block" shape:

    grep -rn "reply.chat.\(help\|topic\)_delivery.header" infochat-provider/src/main/resources/bundles/en.properties

| Site | Disposition |
|---|---|
| M1-666 curated topic-answer delivery (`reply.chat.topic_delivery.header`) | fix — reproduced live (probation) |
| M1-665 command-usage delivery (`reply.chat.help_delivery.header`) | fix — same shape (a matched command has an authoritative usage body; the model must not emit a competing one) |

## Acceptance

Mirrors the YAML `acceptance:` list. When a chat turn matches a curated
topic answer or a command-usage block, the user-visible reply presents a
single authoritative answer to the matched question; the model's free text
does not deliver a competing/contradictory answer. No-match chat turns are
unchanged and D69's one-accretion invariant is preserved. The implementer
selects the mechanism (see §Notes) and the M1-665 / M1-666 delivery test
class encodes it. `mvn -pl infochat-provider -am verify` is green.

## Out-of-scope

See the YAML `out_of_scope:` list. In short: do not change what the
deterministic blocks contain, the D69 one-accretion rule, the provenance
footer, or the output sanitizer; and do not attempt to improve model
accuracy on chat turns that have no curated answer.

## Notes

- Mechanism is the implementer's call and should be settled at `start`.
  Candidates, cheapest first: (a) when a topic/command match is detected
  before generation, steer the model (system-prompt / turn instruction) to
  a brief lead-in and NOT a full substitute answer, letting the curated
  block carry the answer; (b) suppress the model's free-text segment
  entirely for a deterministic-match turn and deliver only a short framing +
  the authoritative block; (c) keep generating but drop the model prose at
  compose time when a block is present. Option (a) keeps the reply
  conversational; (b) is the most robust against contradiction. Pick per
  what `ChatAgent`'s current match-timing allows (is the topic/command match
  known BEFORE the model call or only at compose time?) — census that at
  start rather than assuming.
- Match source: `infochat-provider/src/main/java/app/zcat/infochat/provider/help/HelpTopicCorpus.java`
  and `infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java`;
  usage body via `infochat-provider/src/main/java/app/zcat/infochat/provider/help/HelpCommandHandler.java`;
  accretion + model call in `infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java`.
  Census the exact test class at start (the M1-666 delivery suite) rather
  than assuming its path.
- Decision D69 authorizes exactly the two post-sanitize accretions and the
  "at most one per reply" rule; this ticket refines what the model is
  allowed to say ALONGSIDE them, not the accretion rule.
- Severity is low: it is a chat-quality/UX contradiction on matched
  conceptual/how-to turns, not a security-control failure (authorization
  stays deterministic). Flagged `security_relevant: true` only because the
  change edits the chat output-composition boundary that the sanitizer
  exemption (security.md §LLM output sanitizer) lives on, so the /redteam
  gate should see it.
