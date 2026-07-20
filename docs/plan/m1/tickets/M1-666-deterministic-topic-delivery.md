---
id: M1-666
title: "Deterministic delivery of conceptual topic answers in chat"
status: pending
created: 2026-07-20
last_updated: 2026-07-20
blocked_by:
  - M1-649
  - M1-665
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/03-commands.md
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    ANY model-elected topic path / HelpLookupTool change. The M1-648 r2 lesson
    stands: curated content is delivered deterministically, never as a
    model-paraphrased tool result. For topics the model path additionally fails
    functionally — the sanitizer CLOSED_LIST redacts the user-tier commands
    topics must name. If the deterministic trigger seems unsatisfiable,
    escalate; a model-elected fallback is never the answer.
  - >-
    The topic corpus, its guards, intent-shaped matching, lookupTopic, and the
    two USER_GUIDE fixes — all M1-649. This ticket CONSUMES lookupTopic and the
    bundle-localized answers; it builds no corpus.
  - >-
    The doc_embedding read-path hardening (hnsw.iterative_scan + transaction) —
    M1-660 (transitive blocker via M1-649).
  - >-
    LlmOutputSanitizer — its CLOSED_LIST, patterns, or audit logging. The topic
    block is appended AFTER the sanitizer, exactly like the command usage block;
    the sanitizer is unchanged.
  - >-
    docs/spec/security.md §LLM output sanitizer — NO amendment. M1-663's path-(a)
    exemption already governs "any command-usage OR help text … deterministic
    end-to-end"; a deterministic topic block is a second instance of that
    already-decided contract, not a new exemption category. If authoring
    suggests otherwise, escalate rather than editing that section.
  - >-
    The command usage-block delivery (M1-665) beyond the minimal precedence
    coordination in acceptance. Do not change WHETHER/WHAT a command block is
    delivered; only arbitrate command-vs-topic when both match.
acceptance:
  - >-
    WHETHER deterministic — the decision to deliver a topic answer is made by
    deterministic code from the caller's own inbound text (embed → the reused
    per-turn 768-vector → CommandIntentIndex.lookupTopic at a pinned topic
    threshold), independent of the model's tool elections. No model-elected path
    can cause topic delivery.
    ChatAgentTest.modelCannotTriggerTopicDelivery passes.
  - >-
    WHAT deterministic + VERBATIM — on a match, the delivered answer is the
    bundle-localized (scope /lang) curated text from HelpTopicCorpus, emitted
    verbatim, NOT paraphrased by the model. It is appended AFTER sanitize AND
    translate at the existing step-9b site, so it does NOT pass through the
    sanitizer CLOSED_LIST and does NOT pass through TranslationPipeline (it is
    already localized). A topic naming a user-tier command in the CLOSED_LIST
    (/add-source, /follow-tag, …) is delivered intact.
    ChatAgentTest.deliveredTopicEqualsCorpusVerbatim and
    ChatAgentTest.topicNamingUserTierCommandNotRedacted pass.
  - >-
    SANITIZER-ORDERING conformance — the topic block qualifies for M1-663's
    path-(a) exemption (both the emission DECISION and the BYTES are
    deterministic), exactly as the command usage block does. Every LLM-authored
    byte still passes the sanitizer; the command block and the topic block are
    the only post-sanitize accretions, and never both in one reply (see
    precedence).
  - >-
    PRECEDENCE — when the caller's text matches BOTH a command intent and a
    topic above their thresholds, exactly one rule governs delivery: the topic
    answer wins (a conceptual question wants the explanation, not a bare usage
    block), and AT MOST ONE help block total is delivered per reply.
    ChatAgentTest.topicAndCommandMatch_deliversTopicOnly and
    ChatAgentTest.atMostOneHelpBlockPerReply pass. (If review prefers
    command-wins or context-dependent precedence, that is the one design knob to
    settle here — but it MUST be a single deterministic rule, pinned by test.)
  - >-
    BELOW THRESHOLD — no topic block is delivered and the model's own answer
    stands, unchanged from today's behavior. Stated as a deliberate consequence:
    dropping the model-elected tool means there is no new "do-not-guess" guard
    for conceptual questions the corpus misses; recall (M1-649's intent-shaped
    matching) is what keeps that tail small.
    ChatAgentTest.belowTopicThresholdDeliversNoBlock passes.
  - >-
    INJECTION REPRO STAYS DEAD — an attacker-injected instruction in retrieved
    post content cannot cause a topic block (or a command block) to be
    delivered, because delivery is decided from the caller's own parsed text and
    never from tool-loop state.
    ChatAgentTest.injectedContentCannotDeliverTopic passes.
  - >-
    END-TO-END — "what is probation" and "why can't I post in the group" yield
    the curated probation topic verbatim; a test covers ≥3 phrasings sharing no
    content word with the topic. (Chat remains closed to probation users; this
    serves non-probation callers asking about the system.)
  - >-
    docs/spec/commands.md §Chat mode and docs/design/03-commands.md describe the
    deterministic topic delivery; Decision D69 records the trigger (caller-text,
    lookupTopic), the verbatim post-sanitize composition, the topic-over-command
    precedence, and the one-block cap. (Re-verify D69 is free immediately before
    writing the row — decision_refs is validated by nothing.)
  - mvn verify from the repo root is green
test_plan:
  modifies:
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      change: >-
        ADDS modelCannotTriggerTopicDelivery, deliveredTopicEqualsCorpusVerbatim,
        topicNamingUserTierCommandNotRedacted, topicAndCommandMatch_deliversTopicOnly,
        atMostOneHelpBlockPerReply, belowTopicThresholdDeliversNoBlock,
        injectedContentCannotDeliverTopic. Existing tests unchanged.
  preserves:
    - all tests currently green on main
    - >-
      M1-665's command-delivery tests — the command usage-block path is
      unchanged except for the shared one-block-per-reply precedence.
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D43
  - D66
  - D67
  - D69
decomposed_from: M1-648
---

# M1-666: Deterministic delivery of conceptual topic answers in chat

## Context

M1-649 indexes conceptual topics and serves nothing. This ticket closes the
loop from "the caller asked a conceptual question" to "the caller sees the
curated answer" — deterministically, the way M1-665 delivers command usage.

The delivery mechanism is NOT negotiable, and the reasons are verified, not
stylistic:

1. **The sanitizer would break the answer.** `LlmOutputSanitizer.CLOSED_LIST`
   redacts user-tier state-changing command tokens (`/add-source`,
   `/unfollow-source`, `/follow-tag`, `/unfollow-tag`, `/lang`). Topics like
   "why /add-source requires tags" and "unfollow vs delete" MUST name those
   commands. Routed through the model → sanitizer, the answer comes out as
   "[redacted command] requires tags…". The read-only chat model is
   deliberately forbidden from emitting write-command syntax — so a feature
   that explains write-commands in chat is achievable ONLY by deterministic,
   bot-authored delivery (the same reason `/help` may name every command while
   the chat model may not).
2. **Paraphrase defeats curation.** M1-649 curates the answer as reviewed
   product copy. A model that rewrites it can reintroduce the hallucination the
   feature exists to remove.
3. **M1-663 already permits it.** The path-(a) exemption covers "any … help
   text" that is deterministic end-to-end; a topic block is a second instance,
   needing no §LLM output sanitizer amendment.

## Acceptance

See `acceptance`. The named boundary tests
(modelCannotTriggerTopicDelivery, injectedContentCannotDeliverTopic) falsify
the r2 finding for topics; the composition tests pin verbatim delivery and the
non-redaction of user-tier command names; the precedence tests settle the one
new interaction (command intent vs topic on the same inbound text).

## Notes

**Where the block enters the reply.** Reuse M1-665's step-9b append. The turn
already embeds the caller's text once (semantic pre-fetch + the command-intent
trigger); the same 768-vector drives the topic probe — one extra indexed query,
no extra embed round-trip.

**Precedence, stated.** Topic-over-command when both match: a caller asking
"how do I forget my data" who trips both the /forget usage intent and the
/forget-erasure topic wants the explanation. One block total keeps replies from
stacking. This is the single design knob; pin whichever rule review lands on.

**Localization.** The answer is bundle-localized by M1-649, so delivery picks
the scope-language value (like composeUsageBlock) and does not re-translate —
a fixed cs bundle value is reviewed copy, unlike a model-translated answer.

**Redteam-first.** security_relevant: true — the /m1-tick run step-4 gate
audits this diff before review. The auditor will attack the trigger (can
retrieved content influence it?), the composition (any inbound bytes? verbatim?),
and the ordering (a second post-sanitize accretion — is it path-(a) clean?).
Each attack has a named test standing in its way.
