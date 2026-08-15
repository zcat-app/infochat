---
id: M1-849
title: "Stream sanitized chat replies over SimpleX live messages"
status: pending
created: 2026-08-14
last_updated: 2026-08-15
flow: tick
reproduction: >-
  to-be-written: StageProgressNotifierLiveTextTest#aLiveTextTurnStreamsSanitizedPrefixesThenFinalizes
  (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/streaming-translation-switch.md). Probe:
  grep -n 'void publish\|void complete\|void fail' infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/ProgressNotifier.java
  shows publish/complete/fail only (:51-90) — observed: a chat turn today
  publishes stage labels and one terminal
  (InboundRouter.dispatchChatSelfDelivering, InboundRouter.java:1355-1390),
  so the live-text mode the M1-846 amendment defines has no implementation:
  no publisher path, no capability flag consumer, no ChatAgent wiring.
analysis_ref: docs/plan/m1/tick-analysis/streaming-translation-switch.md
blocked_by: [M1-846, M1-847, M1-848, M1-853]
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/CapabilityFlags.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/ChatAgent.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/resources/application.properties
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    SIGNAL and GROUP scopes — the amendment scopes the feature
    SimpleX-only and DM-first (M1-566/F-live-11; group fan-out economics);
    SignalAdapter keeps the false declaration and no group fan-out path is
    built.
  - >-
    THE SPI ITSELF (M1-847) and the MODE MACHINERY (M1-848) — this ticket
    consumes both; it changes neither the streaming SPI contract nor the
    pivot/direct rules.
  - >-
    TOKEN-SMOOTHNESS pacing — the coalescing floor and the shared 5/s
    bucket stay the pacing reality (chunked ~1s updates are the ceiling,
    P10); no per-token flushing, no new rate machinery.
  - >-
    CHANGING the post-generation pipeline — the finalize text is the
    M1-789..796 pipeline's output byte-for-byte (P5); no sanitize pass, no
    closed-list entry, no translation sanity check is edited here.
  - >-
    The M1-819..843 batch's surfaces (P20): the live probe runs against
    whatever simplex-chat CLI that batch leaves bundled and records the
    version; it never asserts one.
acceptance:
  - "StageProgressNotifierLiveTextTest.aLiveTextTurnStreamsSanitizedPrefixesThenFinalizes (the reproduction, written and run RED at start) passes — a live-eligible turn (flag on + SimpleX capability + DM scope + generated language == delivered language) streams full-prefix-sanitized updates at the coalescing cadence and finalizes with the full post-pipeline text, BYTE-IDENTICAL to the non-streaming path for the same generated text (P1, P5)."
  - "The capability flag lands per the M1-846 amendment: CapabilityFlags gains the member, SimpleXAdapter declares true (SimpleXAdapter.java:86-97 block), Signal false, InMemory configurable, and an absent flag reads not-supported — the capability-contract test passes (messaging.md §Capability flags)."
  - "Each collapse failure-mode case passes (StageProgressNotifierLiveTextTest collapse cases): flag OFF, capability absent, group scope, and a pivot non-en scope EACH produce stage-label behavior byte-identical to today — the M1-846 collapse rule, and the default-config deployment changes not at all (P14)."
  - "StageProgressNotifierLiveTextTest.aRefusalPrefixedStreamPublishesNothing passes — FAILURE-MODE (P2): a stream whose trimmed prefix forms the structured-refusal marker is held back, never publishes, and finalizes the refusal degrade; a prose-prefixed stream releases the hold-back exactly once the prefix question is decidable."
  - "StageProgressNotifierLiveTextTest.aToolCallIterationRevertsToTheStageLabel passes — FAILURE-MODE (P3): a two-iteration turn (tool call, then answer) shows the localized GENERATING label between iterations and never transmits a TOOL_CALL fragment or dropped prose."
  - "ChatAgentStreamingAuditTest.midStreamOnlyMatchesAreRowedOncePerTurn passes — FAILURE-MODE (P4): the to-be-written test (folded in at start per the reproduction marker) feeds a stream whose closed-list token is redacted in a transient update and absent from the final text, and asserts exactly one aggregated LLM_OUTPUT_SANITIZED row-set for the turn (per distinct token, exact occurrence counts — counted, never throttled, the final text PLUS transient-only matches); and ChatAgentStreamingAuditTest.aFailingAuditInsertAbortsTheStreamToTheFailureTerminal passes — a failing audit INSERT aborts the stream to the failure terminal, the streamed equivalent of LlmOutputSanitizer.java:250-256's durability posture (the M1-694 lesson: this row is the first thing redteam looks for). Verify: `mvn -pl infochat-provider -am test -Dtest=ChatAgentStreamingAuditTest` exits 0, expected output 'Tests run: 2, Failures: 0' and BUILD SUCCESS."
  - "The /stop wiring test passes (P11): a cancellation mid-stream finalizes the placeholder with the D35 stopped terminal, never the stale partial answer (the InboundRouter.java:1365-1373 contract), and the deferred-persist ordering is unchanged (placeholder/finalize wrap compute+deliver, never the persist)."
  - "Each named pre-existing notifier lifecycle test passes UNCHANGED: the M1-334 abandoned-operation drain, the M1-611 per-operation keying, the M1-635 queued-placeholder seeding, the coalescing floor, the fallback_send path and its adapter.outbound.update.* metrics, and the supportsMessageEdit collapse (§10; this ticket authorizes no modification to those tests)."
  - "The OutboundDelivery `](` break and the M1-794 empty-body guard cover every live update exactly as they cover today's edits (OutboundDelivery.java:271-290 already applies breakLinkAdjacency per update — pinned by the existing tests plus one live-update case)."
  - "Host live-validation probe (layer 4, opt-in, NEVER in mvn verify — the LiveSimpleX* posture): a real-relay live-text turn is observed coalescing at the floor and finalizing live=off, against whatever bundled CLI the M1-819..843 batch left (P20), with the probe command, the CLI version, and the observed output recorded in the ticket record."
  - "mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/StageProgressNotifierLiveTextTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/ChatAgentStreamingAuditTest.java
    - the capability-contract test addition
    - the ChatAgent streaming-wiring tests (eligibility, /stop)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Progress notifications
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Failure handling
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D21
  - D31
  - D35
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

# M1-849: Stream sanitized chat replies over SimpleX live messages

## Context

The last ticket of the batch: M1-846's amendment defined the live-text
display policy, M1-847 built the streaming SPI, M1-848 built the mode
machinery — this ticket wires them together. A live-eligible chat turn
(operator flag on, SimpleX capability, DM scope, generated language ==
delivered language: every en scope, non-en direct scopes) reveals the
sanitized generated prefix in the placeholder via SimpleX live messages
instead of stage labels; everyone else gets today's exact behavior. The
transport substrate is live-proven (encodeUpdateCommand/encodeFinalizeCommand,
SimpleXMessageCodec.java:145-173; coalescing floor, shared bucket,
fallback_send). Shared analysis: `analysis_ref:`.

## Root cause

Three absences meet here: the notifier SPI has no live-text publisher
(ProgressNotifier.java:51-90), the adapter capability set has no live-text
member (messaging.md:154-224), and ChatAgent's tool loop never exposes
partial text (runToolLoop returns one string, ChatAgent.java:783-843). The
wiring is the integration of three finished pieces per the amendment's
rules — no new policy is decided in this ticket.

## Pitfalls

Numbered per the analysis document; this ticket carries P1, P2, P3, P4,
P5, P10, P11, P13, P15.

- P1: full-prefix re-sanitize per transmitted update, never a delta — the
  passes see the whole generated prefix, so pass ordering and deletion-join
  coverage hold by construction (security.md:753-777).
- P2: the refusal hold-back gates the FIRST publish; fail-closed to the
  refusal degrade.
- P3: an iteration ending in a tool call reverts the placeholder to the
  GENERATING label; protocol text and dropped prose never display.
- P4: one aggregated row-set per turn, transient-only matches retained,
  audit failure kills the stream.
- P5: the finalize carries the full post-pipeline text, byte-identical to
  the batch path — never the last streamed prefix.
- P10: pacing reality — the 600 ms floor and the shared 5/s bucket; ~1 s
  chunked ceiling; DM-first; SimpleX-only.
- P11: /stop mid-stream finalizes the stopped terminal; interrupt posture
  per M1-847's SPI half; deferred-persist ordering unchanged.
- P13: the operator flag's key joins the documented-key surface
  (DocumentedConfigKeyParityTest); any new bundle strings land in all five
  bundles (D43 completeness).
- P15: the collapse cases pin TODAY's stage-label bytes as the end state
  for ineligible scopes — nothing here retargets the M1-607/M1-794/M1-796
  pins; they run unchanged.

## Approach

- **Files to touch:** `files_scope` (plus tests and the layer-4 probe;
  the file fan-out is the integration seam — each file's change is one
  seam of the amendment's rules).
- **Steps, in order:**
  1. Write the reproduction RED against an InMemory adapter with the
     capability on.
  2. The capability flag: CapabilityFlags member + the three adapter
     declarations.
  3. The notifier live-text path: a publish method for caller-supplied
     sanitized text riding the existing placeholder/coalesce/finalize
     machinery (publishStageText's shape, StageProgressNotifier.java:132-205)
     with the lifecycle guarantees intact.
  4. ChatAgent: the eligibility gate (flag + capability + DM + language
     condition), the refusal hold-back, the per-update full-prefix
     re-sanitize with the non-auditing core transforms plus the per-turn
     audit aggregation at finalize, the tool-loop revert rule, and the
     /stop posture — the finalize then runs the UNCHANGED post-pipeline
     path (sanitize → strip → refusal → pivot display leg where it applies
     → help blocks → emptied-reply degrade → provenance).
  5. The config key with its committed `false` default (documented-key
     surface).
  6. The layer-4 live probe and its recorded output.
- **Controls to preserve (§10):** the whole post-generation pipeline and
  its audit rows (P5), the coalescing floor / shared bucket / fallback_send
  / metrics, the M1-334 drain, the M1-611 per-operation keying, the M1-635
  queued placeholder, the supportsMessageEdit collapse, the `](` break on
  every update (OutboundDelivery.java:271-290), the M1-794 empty-body
  guard, the delivery-outcome-gated deferred persist
  (InboundRouter.java:1401-1423), and the D35 stopped terminal.
- **Pitfall→mitigation:** P1→step 4's full-prefix rule + acceptance item 1;
  P2→step 4's hold-back + item 4; P3→step 4's revert + item 5; P4→step 4's
  aggregation + item 6; P5→step 4's unchanged finalize + item 1's
  byte-identity assertion; P10→step 3's reuse of the existing machinery +
  item 10's observed cadence; P11→step 4 + item 7; P13→step 5 + the build
  gates; P15→item 3's collapse pins.

## Definition of done

Live-eligible turns stream sanitized prefixes and finalize byte-identically
to the batch path; ineligible configurations collapse to today's exact
behavior; the refusal/tool-call/audit//stop failure modes each have their
named test; the lifecycle pins run unchanged; the live probe is recorded;
mvn verify is green.

## Verification

- P1 → the reproduction — a reply whose closed-list token is split across
  two chunks never transmits the raw token in any update.
- P2 → StageProgressNotifierLiveTextTest.aRefusalPrefixedStreamPublishesNothing
  — feeds a refusal-prefixed stream, asserts nothing published + the
  refusal terminal.
- P3 → StageProgressNotifierLiveTextTest.aToolCallIterationRevertsToTheStageLabel
  — a two-iteration turn; asserts no TOOL_CALL fragment ever transmitted.
- P4 → ChatAgentStreamingAuditTest.midStreamOnlyMatchesAreRowedOncePerTurn
  and ChatAgentStreamingAuditTest.aFailingAuditInsertAbortsTheStreamToTheFailureTerminal
  — transient-only matches are rowed once per turn; a failing audit INSERT
  aborts the stream (hostile DB posture).
- P5 → the reproduction's byte-identity assertion (both modes where they
  apply).
- P10 → acceptance item 10's recorded probe — the observed cadence honors
  the floor on a real relay.
- P11 → the /stop test — cancellation mid-stream finalizes the stopped
  terminal, never the partial answer.
- P13 → DocumentedConfigKeyParityTest and the D43 completeness gate run in
  `mvn verify`.
- P15 → item 3's collapse cases + the unchanged pre-existing suite.
- acceptance item 11 → `mvn verify` from repo root.

## Out-of-scope

Named in `out_of_scope`: Signal and groups, the SPI (M1-847) and the mode
machinery (M1-848), token-smoothness pacing, any post-generation pipeline
edit, and any CLI-version assertion in the probe. If the live probe
surfaces a real-relay behavior the amendment did not anticipate (e.g. a
v7.0.0 live-message behavior the batch documented), STOP and escalate —
do not widen the amendment by implementation.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-849-streaming-translation-switch-6.md
```
