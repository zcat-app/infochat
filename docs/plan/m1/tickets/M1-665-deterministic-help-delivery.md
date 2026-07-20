---
id: M1-665
title: "Deterministic delivery of matched command usage in chat"
status: done
created: 2026-07-19
last_updated: 2026-07-19
blocked_by:
  - M1-663
  - M1-664
files_budget: 15
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/HelpLookupTool.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/help/CommandIntentIndex.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/HelpCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentAuditActorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatProvenanceTest.java
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
    Any model-elected delivery path. No collectHelpBlock equivalent, no
    reply accretion keyed on the model's tool calls, no "the bytes are
    deterministic so the append is fine" reasoning — that is the torn-down
    M1-648 design, rejected as a design defect (not sloppiness) by
    docs/plan/m1/redteam/M1-648-2026-07-19-r2.md and now outlawed by the
    amended docs/spec/security.md §LLM output sanitizer (M1-663). If the
    deterministic trigger proves unsatisfiable, escalate; a model-elected
    fallback is never the answer.
  - >-
    The retrieval/index surface — DocEmbeddingDao's queries,
    CommandIntentIndexBuilder, the doc_embedding schema or grants, the
    ChatToolRegistry/allowlist/parity surface, and helpLookup's registered
    tool contract (name-returning, tier-filtered). All M1-664. This ticket
    may add a shared entry point for the trigger (see Notes) but must not
    change what the tool returns to the model.
  - >-
    LlmOutputSanitizer — its CLOSED_LIST, patterns, match-set derivation, or
    audit logging. This ticket changes where composed help text enters the
    reply, never what the sanitizer does to LLM output.
  - >-
    M1-649's conceptual topic answers. They inherit the M1-663 contract and
    should reuse this ticket's delivery mechanism, but topic content is a
    separate corpus with its own threat review.
  - >-
    TranslationProvider involvement for the delivered block. Composition via
    the /help runtime path is already bundle-localized per the scope's /lang;
    the block does not pass through translation (see Notes).
acceptance:
  - >-
    WHETHER is deterministic: the decision to deliver a usage block is made
    by deterministic code from the caller's own inbound chat text (the
    parsed user request), independent of the model's tool elections. A
    model-elected helpLookup call can never cause delivery:
    ChatAgentTest.modelElectedHelpLookupNeverTriggersDelivery passes by
    scripting a model turn that calls helpLookup while the caller's own text
    matches no intent, and asserting the delivered reply contains no usage
    block.
  - >-
    THE INJECTION REPRO IS DEAD: reproducing the r2 finding's scenario — an
    attacker-injected instruction in retrieved post content steers the model
    into calling helpLookup for a privileged command, with a bot-admin
    caller — yields a delivered reply with NO privileged usage block,
    because the caller's own text did not request it.
    ChatAgentTest.injectedToolCallCannotDeliverAdminUsage passes.
  - >-
    WHAT is deterministic: when the caller's text does match a command above
    the M1-664 threshold, the delivered block is composed at delivery time
    via the same runtime path /help <cmd> uses, for a command visible to the
    caller's tier (reusing M1-664's tier-filter-before-return predicate),
    and interpolates no inbound-derived bytes.
    ChatAgentTest.deliveredUsageBodyEqualsHelpComposition and
    ChatAgentTest.adminUsageNeverDeliveredToNonAdmin pass.
  - >-
    End-to-end feature proof (carried from M1-648): asking "how do I stop
    seeing posts from this source" in chat yields /unfollow-source with its
    real usage and examples. A test covers at least three phrasings that
    share no prefix with their target command.
  - >-
    At most ONE usage block is delivered per reply, regardless of how many
    intents match or how many tool calls the model makes — the deliberate
    decision the r2 audit's reply-amplification note asked for.
    ChatAgentTest.atMostOneUsageBlockPerReply passes.
  - >-
    Sanitizer-ordering conformance with the amended
    docs/spec/security.md §LLM output sanitizer: every LLM-authored byte in
    the delivered reply still passes through the sanitizer; the only
    post-sanitize accretion is the deterministically-triggered,
    deterministically-composed usage block, which qualifies for the
    tightened exemption because both the emission decision and the bytes are
    deterministic. No other post-sanitize accretion path exists.
  - >-
    AUTHORIZED PRE-EXISTING TEST CHANGE —
    ChatAgentTest.noToolDerivedTextIsAppendedAfterSanitize (added by M1-664
    to pin this boundary while it was still closed) is amended to the new
    contract: nothing MODEL-ELECTED is appended after sanitize; the
    deterministically-triggered usage block is the single authorized
    exception. The test keeps asserting that a model-elected helpLookup
    result never reaches the reply un-sanitized.
  - >-
    AUTHORIZED PRE-EXISTING TEST CHANGES (constructor-signature orphans,
    round 1 review) — ChatAgent's new (EmbeddingProvider,
    HelpCommandHandler) constructor signature orphaned the pre-existing
    constructor calls in four other test files, which are mechanically
    updated to satisfy the new arity:
    ChatAgentAuditActorTest passes 2 additional nulls (the test does not
    exercise the trigger or composeUsageBlock); ChatAgentProvenanceTest
    and ChatAgentRefusalInterceptTest pass 2 nulls AND override
    lookupIntentForDelivery() to return Optional.empty() (preserving
    pre-M1-665 behaviour: the null EmbeddingProvider/HelpCommandHandler
    the tests pass are never dereferenced, and the trigger is inert on
    these paths); InboundRouterChatProvenanceTest passes 2 nulls (its
    StubChatAgent overrides handleTurn outright and never reaches the
    trigger). No assertions weakened, no @Disabled, no @MockBean
    replacing real wiring, no catch-and-swallow, no Testcontainers→H2
    substitution. Round 1 review identified these as SCOPE-DRIFT-CHECK
    FAIL + TEST-INTEGRITY-CHECK FAIL; this entry authorizes them
    retroactively per §8 Test-modification authorization.
  - >-
    Decision D67 in docs/spec/decisions.md records the deterministic
    delivery mechanism: trigger derived from the caller's inbound text,
    composition via the /help runtime path, one-block-per-reply cap, and the
    M1-663 amendment as the governing contract. (Re-verify D67 is still free
    immediately before writing the row:
    `grep -rn 'D6[0-9]' docs/plan/m1/tickets/*.md docs/spec/decisions.md`.)
    docs/spec/commands.md §Chat mode and docs/design/03-commands.md describe
    the delivery behavior; no doc reasserts a model-elected append.
  - mvn verify from the repo root is green
test_plan:
  modifies:
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentTest.java
      change: >-
        ADDS modelElectedHelpLookupNeverTriggersDelivery,
        injectedToolCallCannotDeliverAdminUsage,
        deliveredUsageBodyEqualsHelpComposition,
        adminUsageNeverDeliveredToNonAdmin, atMostOneUsageBlockPerReply.
        AMENDS noToolDerivedTextIsAppendedAfterSanitize per the authorized
        change in acceptance. Everything else byte-for-byte unchanged.
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentAuditActorTest.java
      change: >-
        Constructor call gains 2 null params for the new
        (EmbeddingProvider, HelpCommandHandler) signature. The test does
        not exercise the trigger or composeUsageBlock; the nulls are
        never dereferenced. Round 1 review paperwork.
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentProvenanceTest.java
      change: >-
        Constructor call gains 2 null params AND TestChatAgent overrides
        lookupIntentForDelivery() to return Optional.empty() (preserving
        pre-M1-665 behaviour: the null EmbeddingProvider/HelpCommandHandler
        are never dereferenced, the trigger is inert on the provenance
        paths under test). Round 1 review paperwork.
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentRefusalInterceptTest.java
      change: >-
        Same shape as ChatAgentProvenanceTest: 2 null constructor params
        + lookupIntentForDelivery() override returning Optional.empty().
        Round 1 review paperwork.
    - path: infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterChatProvenanceTest.java
      change: >-
        StubChatAgent super() call gains 2 null params. The StubChatAgent
        overrides handleTurn outright and never reaches the trigger.
        Round 1 review paperwork.
  preserves:
    - all tests currently green on main
    - >-
      M1-664's retrieval-surface tests — the tool's name-returning contract,
      tier filter, and threshold behavior are unchanged by this ticket.
spec_refs:
  - docs/spec/security.md §LLM output sanitizer
  - docs/spec/commands.md §Chat mode
decision_refs:
  - D28
  - D43
  - D66
decomposed_from: M1-648
reviews:
  - round: 1
    date: 2026-07-19
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: FAIL
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
      assertion_adequacy: PASS
    diff_stats:
      files: 18
      added: 777
      removed: 104
    rework_items:
      - "Add the 4 pre-existing test files the diff modifies to files_scope and bump files_budget 11 → 15 (constructor-call orphans from the new ChatAgent(EmbeddingProvider, HelpCommandHandler) signature)."
      - "Authorize those 4 test file modifications in the ticket body per §8 Test-modification authorization (amend acceptance item 7's terminal 'No other pre-existing test changes' sentence + add the 4 paths under test_plan.modifies)."
  - round: 2
    date: 2026-07-19
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
      assertion_adequacy: PASS
    diff_stats:
      files: 18
      added: 868
      removed: 108
    note: >
      Paperwork-only round over round 1: files_budget 11 → 15, +4 paths
      in files_scope, acceptance item 8 + test_plan.modifies entries
      authorizing the constructor-signature test orphans. No production
      or test code touched since round 1's green verify (M1-272 reuse
      rule applies; round-2 log reuses round-1 green log).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-19
    verdict: CLEAN
    base: 705f0721
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-665-2026-07-19.md
    out_of_model_count: 0
    note: |
      Pre-review redteam gate at the /m1-tick run step 4 checkpoint.
      Audited the uncommitted branch tip (zero-commit working-tree diff
      against fork point 705f0721, the files-scope-defect refine commit
      on main). CLEAN — 0 findings, 0 out-of-model items. The diff
      implements the deterministic delivery trigger (D67) the M1-663
      spec amendment governs; the adversary found no gap between the
      threat model's commitments and what the diff delivers.
clarity_check:
  date: 2026-07-19
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-665: Deterministic delivery of matched command usage in chat

## Context

Ticket B of the M1-648 decomposition — the part that killed the original.
M1-648's implementation appended composed help text to the chat reply
after the sanitizer ran, with the LLM electing whether the append fired
and which command's syntax it carried. `/redteam` flagged it as a medium
INJECTION (`docs/plan/m1/redteam/M1-648-2026-07-19-r2.md`): an
attacker-influenced model could make the bot emit a genuine, copy-pasteable
`/grant-admin` usage block, framed by attacker-chosen prose, with no
sanitizer audit row. A remediation patch failed a second audit; the
conclusion on record is that the delivery model cannot be patched safe —
it must be deterministic end-to-end.

This ticket builds that delivery model, honoring the M1-663 amendment to
docs/spec/security.md §LLM output sanitizer: deterministic code decides
both WHETHER usage text is delivered (driven by the caller's parsed
request, never a model-elected tool call) and WHAT it contains (composed
at delivery time from the /help runtime path). M1-664 supplies the intent
index, the tier predicate, and the name-returning tool; this ticket closes
the loop from "the user asked how" to "the user sees the real usage" with
no model election anywhere on the delivery path.

## Acceptance

See `acceptance`. The two named boundary tests
(modelElectedHelpLookupNeverTriggersDelivery,
injectedToolCallCannotDeliverAdminUsage) are the falsification of the r2
finding; the composition tests pin tier safety and match-not-assert at the
delivery layer; the one-block cap discharges the audit's amplification
note; and the authorized amendment of M1-664's negative pin re-opens the
boundary in exactly one deterministic place.

## Out-of-scope

See `out_of_scope`. The retrieval surface, the sanitizer, translation, and
M1-649's topics are all untouched. The hard rule mirrors M1-664's: if the
deterministic trigger cannot be made to work, escalate — never fall back
to letting the model decide.

## Notes

**Recommended trigger mechanism (non-binding).** The D28 pre-fetch shape:
before (or alongside) the model turn, deterministically embed the caller's
inbound text and query the intent index — the same
tier-filtered-inside-the-query lookup HelpLookupTool runs — and let THAT
result, not the model's tool elections, drive delivery. The chat turn
already embeds the caller's text once for the semantic post pre-fetch;
the same 768-vector serves both corpora queries, so the trigger costs one
extra indexed query, not an extra embed round-trip. A shared entry point
in CommandIntentIndex (used by both HelpLookupTool and the trigger) keeps
the two paths from drifting.

**Threshold semantics differ from the tool's.** The tool answers the
model's question "which command matches this phrase"; the trigger answers
"did the CALLER ask how to do something". A conservative trigger threshold
(possibly higher than the tool's) avoids bolting usage blocks onto chat
turns that merely mention a topic. Pin it as a named constant next to
M1-664's, with the same recalibrate-as-follow-up posture.

**Where the block enters the reply.** After sanitize is legal under the
amended exemption (both decision and bytes deterministic) and is the shape
that keeps admin usage intact for admin callers — the entire point of the
feature. The block is composed bundle-localized per the scope's /lang
(the /help path's existing behavior), so it does not pass through
TranslationProvider; the model's prose portion keeps its existing
sanitize→translate pipeline unchanged.

**Group scope.** The block follows /help's existing tier resolution per
scope (M1-664's predicate). Note the r2 REPRO's group variant: a reply in
group scope is delivered to every member, which is why
adminUsageNeverDeliveredToNonAdmin must cover the group-tier path, not
just DM.

**Redteam-first.** security_relevant: true — `/m1-tick run` step 4 audits
this diff BEFORE review. Expect the auditor to attack the trigger
(can retrieved content influence it?), the composition (any inbound bytes?),
and the ordering (any other post-sanitize accretion?). The ticket is
designed so each attack has a named test already standing in its way.

## Round 1 rework

Round 1 review returned REWORK on two paperwork items (ACCEPTANCE-CHECK,
OUT-OF-SCOPE-CHECK, NEGATIVE-SPACE-CHECK, SPEC-CONFORMANCE-CHECK,
ASSERTION-ADEQUACY-CHECK all PASS; no code changes required):

1. **SCOPE-DRIFT-CHECK FAIL** — the new `ChatAgent(EmbeddingProvider,
   HelpCommandHandler, ...)` constructor signature orphaned pre-existing
   constructor calls in four test files outside `files_scope`. Fix:
   added the four paths to `files_scope` and bumped `files_budget`
   11 → 15.
2. **TEST-INTEGRITY-CHECK FAIL** — acceptance item 7's terminal sentence
   "No other pre-existing test changes." was contradicted by the four
   test-file modifications. Fix: amended item 7's terminal sentence,
   added a new acceptance item documenting the constructor-signature
   orphans + the `lookupIntentForDelivery()` overrides that preserve
   pre-M1-665 behavior, and added the four paths under
   `test_plan.modifies` with per-file change descriptions.

Both fixes are ticket-file paperwork only — no production code, no test
code, no Java/config/DB surface touched since round 1's green
`mvn verify`. The round-2 verify log is the inert-N/A note per the
SKILL.md `mvn verify` scope rule.
