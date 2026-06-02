---
id: M1-147
title: "Adapter capability-flag reconciliation + cross-adapter contract test (CT5)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging
  - docs/design/06-messaging.md
complexity: high
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - SPI lifecycle (finalize→shutdown / start/stop) (covered by M1-148)
  - adapter resilience (handler isolation, hung-process) (covered by M1-132)
acceptance:
  - "A cross-adapter contract test suite (new AdapterCapabilityContractTest under infochat-messaging-adapter/src/test) asserts each adapter honours the same semantic-state classification: 'not connected' is one category (PERMANENT) across Signal and SimpleX, not TRANSIENT in one and PERMANENT in the other. The production change this forces: SignalAdapter.requireConnected (SignalAdapter.java:339) flips FailureCategory.TRANSIENT→PERMANENT for the not-connected send/update/finalize path; SimpleXAdapter already throws PERMANENT there (SimpleXAdapter.java:351). No pre-existing test pins Signal's old TRANSIENT-not-connected value (the SignalJsonRpcClientTest TRANSIENT assertions are the -32603 internal-error and response-timeout paths, both distinct from the adapter-level not-connected guard), so item 1 forces no additional test edit beyond the new contract test"
  - "Capability flags are aligned to docs/design/06-messaging.md — code conforms to the existing design, no design amendment: SimpleXAdapter.supportsTypingIndicator flips true→false (design §6.4.2: SimpleX has no first-class typing indicator); SignalAdapter.supportsCodeFormatting flips false→true (design §6.5.2: Signal renders monospace); InMemoryAdapter.supportsCodeFormatting flips false→true (design §6.6 capabilities posture: exercises the code-formatting render path). SignalAdapter.supportsTypingIndicator already equals the design (true) and stays unchanged"
  - "The pre-existing flag-value assertions that pinned the OLD values are updated to the reconciled values in the same commit (authorized via test_plan.modifies): SimpleXAdapterSkeletonTest assertTrue→assertFalse on supportsTypingIndicator; SignalAdapterSkeletonTest assertFalse→assertTrue on supportsCodeFormatting; InMemoryAdapterTest assertFalse→assertTrue on supportsCodeFormatting plus its class javadoc. No test is weakened, skipped, or deleted — only the asserted constant changes to track the reconciled flag"
  - "Outbound encode-time codec validators in SimpleXMessageCodec throw the SPI's checked MessagingException(FailureCategory.PERMANENT) instead of the unchecked exceptions that bypass the two-category retry model: requireValidQueueAddressId (was IllegalStateException, SimpleXMessageCodec.java:195) and requireWithinCap (was IllegalArgumentException, SimpleXMessageCodec.java:229). The four encode entry points (encodeSendCommand/encodeUpdateCommand/encodeFinalizeCommand/encodeTypingCommand) plus the private helpers they reach (encodeEdit, targetSelector) gain a throws MessagingException clause to propagate it. SimpleXAdapter.send/update/finalize already declare throws MessagingException, so they propagate unchanged"
  - "INBOUND decode validators are explicitly OUT of item 4's scope and stay as-is: SignalMessageCodec.decode's IllegalArgumentException (transport-corruption→disconnect per its own javadoc) and SimpleX's inbound MalformedFrameException are inbound-stream disciplines, not the outbound send retry model. Config validators (SimpleXConfig/SignalConfig), subprocess guards (Simple/SignalSubprocess), and adapter connection-state guards other than the not-connected send path (item 1) are also out of item 4's scope — they do not sit on the codec encode path"
  - "SimpleXAdapter.setTyping has a no-throw best-effort SPI contract; because encodeTypingCommand now throws checked MessagingException, setTyping (SimpleXAdapter.java:288) wraps the encode+fire in try/catch(MessagingException) and absorbs it (debug-logged), consistent with its existing ws==null best-effort absorb. setTyping does NOT gain a throws clause"
  - "The pre-existing SimpleXMessageCodecTest.encodeRejectsContactIdWithCommandInjectionChars (7 assertThrows(IllegalStateException.class) at lines 310-330) is updated in the same commit (authorized via test_plan.modifies) to assert MessagingException.class with category()==PERMANENT, and its line-303 comment is updated off 'throws IllegalStateException'. No assertion is weakened, skipped, or removed — the rejection is still asserted; only the expected exception type tracks the reconciled validator. The happy-path encode callers in this file (encodesAndDecodesMessages, line 25) and in SimpleXWebSocketClientTest (lines 123/247/278) already declare throws Exception, so they compile unchanged and need no authorization"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java (CT5 cross-adapter contract)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterSkeletonTest.java (supportsTypingIndicator assertTrue→assertFalse — tracks the design-reconciled flag, not a weakening)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java (supportsCodeFormatting assertFalse→assertTrue — tracks the design-reconciled flag)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (supportsCodeFormatting assertFalse→assertTrue + class javadoc — tracks the design-reconciled flag)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java (encodeRejectsContactIdWithCommandInjectionChars: 7× assertThrows(IllegalStateException.class)→assertThrows(MessagingException.class) with category()==PERMANENT + line-303 comment — tracks the codec-exception-type reconciliation in acceptance item 4, not a weakening)
  preserves:
    - all OTHER tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/verification.md §Messaging
decision_refs: []
reviews: {}
escalations:
  - date: 2026-06-02
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended
      Acceptance item 2 mandates that supportsTypingIndicator "flips to false" on the
      production adapters. All three adapters currently declare supportsTypingIndicator = true
      (SimpleXAdapter.java:77, SignalAdapter.java:83, InMemoryAdapter.java:71). Flipping the
      SimpleX and Signal flags to false directly breaks two pre-existing tests that are green
      on main and assert the opposite: SimpleXAdapterSkeletonTest.capabilitiesAreCorrect
      (SimpleXAdapterSkeletonTest.java:37, assertTrue(caps.supportsTypingIndicator())) and
      SignalAdapterSkeletonTest.capabilitiesAreCorrect (SignalAdapterSkeletonTest.java:36,
      assertTrue(caps.supportsTypingIndicator())). The ticket's test_plan.preserves says "all
      tests currently green on main" and neither §Out-of-scope nor §Notes authorizes editing
      either skeleton test. This makes acceptance item 4 (mvn -B clean verify exits 0)
      unsatisfiable simultaneously with the preserve-all-green contract unless those two
      pre-existing tests are modified — and that modification is unauthorized.
      Secondary unresolved item: acceptance item 2's supportsCodeFormatting disjunction is
      left open — SignalAdapter.java:73 declares supportsCodeFormatting = false while design
      §6.5.2 declares supportsCodeFormatting = true; the ticket must state which arm wins.
      SUGGESTED ESCALATION: refine
  - date: 2026-06-02
    reason: outline-fail
    reviewer_verdict_excerpt: |
      ## OUTLINE FAILED — escalation recommended
      Acceptance item 4 mandates that the codec/encoder validators throw the
      checked MessagingException(PERMANENT) "not IllegalStateException/
      IllegalArgumentException." The in-scope validator is
      SimpleXMessageCodec.requireValidQueueAddressId (SimpleXMessageCodec.java:195),
      whose own javadoc (lines 184-189) declares it as the encode-time validator
      that currently throws IllegalStateException, reached from all four encode
      entry points (encodeSendCommand/encodeUpdateCommand/encodeFinalizeCommand/
      encodeTypingCommand). Changing it to throw checked MessagingException
      directly breaks a pre-existing, green-on-main test —
      SimpleXMessageCodecTest.encodeRejectsContactIdWithCommandInjectionChars
      (lines 298-331), which has 7 assertThrows(IllegalStateException.class, ...)
      assertions against those encode entry points (lines 310-330). That test file
      (SimpleXMessageCodecTest.java) is NOT listed in test_plan.modifies (which
      names only the three capability-flag tests at ticket lines 30-33) and is not
      authorized by Out-of-scope, Reconciliation decisions, or Notes;
      test_plan.preserves explicitly pins "all OTHER tests currently green on
      main." This makes acceptance item 4 and the final mvn -B clean verify exits 0
      (acceptance item 5) unsatisfiable simultaneously with the preserve-all-green
      contract — the same unauthorized-test-edit class of blocker the first
      escalation round caught for the skeleton tests, but this round's refine only
      authorized the capability-flag edits and missed the codec-exception-type edit
      acceptance item 4 forces. Secondary unresolved item: SimpleXAdapter.setTyping
      (SimpleXAdapter.java:288) has no throws clause yet reaches
      requireValidQueueAddressId via encodeTypingCommand -> targetSelector;
      promoting that validator to a checked MessagingException will not compile on
      the best-effort typing path, and the ticket does not state how acceptance
      item 4 applies to the no-throws setTyping/encodeTypingCommand route.
      SUGGESTED ESCALATION: refine
revisions:
  - date: 2026-06-02
    reason: outline-fail rework
    note: |
      Plan-writer returned OUTLINE FAILED at start; main session
      ground-truthed the reconciliation state (code vs design vs tests)
      before refine. Verified facts the rewrite must encode:
        - SimpleX typing: code=true (SimpleXAdapter:77) vs design=false
          (§6.4.2 L481) → MISMATCH. Acceptance "flips to false" forces
          SimpleXAdapterSkeletonTest:37 (assertTrue) to change — currently
          unauthorized by test_plan.
        - Signal typing: code=true (SignalAdapter:83) vs design=true
          (§6.5.2 L680) → MATCH. No flip; SignalAdapterSkeletonTest:36
          stays green. (Plan-writer wrongly claimed design=false here.)
        - Signal codeFormatting: code=false (SignalAdapter:73) vs design=true
          (§6.5.2 L663) → MISMATCH, disjunction unresolved.
        - InMemory codeFormatting: code=false (InMemoryAdapter:61) vs design
          true (§6.6 L813/L873) → MISMATCH, disjunction unresolved;
          InMemoryAdapterTest:125 (assertFalse) at stake.
      Refine must (a) pin each of the 3 disjunctions to one arm, and
      (b) authorize the specific pre-existing test edits each pinned arm
      forces, with their new expected values.
  - date: 2026-06-02
    reason: outline-fail rework (round 2)
    note: |
      Second plan-writer pass failed fast on acceptance item 4 (codec
      exception types) before it audited item 1 (cross-adapter classify).
      Main session ground-truthed the FULL forced-change surface for both
      items before this refine — the lesson from round 1 being that a fresh
      Plan pass surfaces blockers the prior pass never reached. Verified
      facts the rewrite encodes:
        ITEM 4 (codec exception types):
        - Outbound encode-time validators in SimpleXMessageCodec that throw
          unchecked: requireValidQueueAddressId (IllegalStateException,
          :195) and requireWithinCap (IllegalArgumentException, :229). Both
          → MessagingException(PERMANENT). encode entry points
          (encodeSendCommand/Update/Finalize/Typing) + encodeEdit +
          targetSelector gain `throws MessagingException`.
        - send/update/finalize already declare throws MessagingException
          (SimpleXAdapter:250/261/274) → propagate unchanged.
        - setTyping (:288) has NO throws (best-effort) → must try/catch
          (MessagingException) around encodeTypingCommand+fire. The compile
          break the plan-writer named is resolved by catch-absorb, not a
          throws clause (preserves the no-throw SPI contract).
        - Forced test edit: SimpleXMessageCodecTest
          encodeRejectsContactIdWithCommandInjectionChars (7× assertThrows
          IllegalStateException → MessagingException, lines 310-330, comment
          303). NOW authorized in test_plan.modifies. Happy-path encode
          callers (SimpleXMessageCodecTest:25, SimpleXWebSocketClientTest
          :123/:247/:278) already declare `throws Exception` → compile
          unchanged, no authorization needed (verified method signatures).
        - INBOUND decode validators explicitly OUT of scope:
          SignalMessageCodec.decode IllegalArgumentException (:97/:111) is
          transport-corruption→disconnect, not the send retry model;
          SimpleX inbound uses MalformedFrameException. Config/subprocess
          guards also out of scope.
        ITEM 1 (cross-adapter not-connected classify):
        - SignalAdapter.requireConnected (:339) throws TRANSIENT for
          not-connected; SimpleXAdapter (:351) throws PERMANENT → drift.
          Flip Signal → PERMANENT. No pre-existing test pins Signal's old
          TRANSIENT-not-connected (SignalJsonRpcClientTest TRANSIENT
          assertions are -32603 and response-timeout, distinct paths), so
          no extra test edit forced.
        File count after refine: 4 prod + 4 test-modified + 1 test-added =
        9 (files_budget 12, fits).
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-147: Adapter capability-flag reconciliation + cross-adapter contract test (CT5)

## Context

Adapter implementations inconsistently honour their own contracts: the same
semantic state classifies differently across adapters (`SignalAdapter`/`SimpleXAdapter`
disagree on "not connected" TRANSIENT-vs-PERMANENT), capability flags drift from
design notes (`supportsTypingIndicator`, `supportsCodeFormatting`,
`InMemoryAdapter.supportsCodeFormatting`), and codec validators throw exception
types that bypass the categorised retry model. A cross-adapter contract test is
the forcing function that prevents the next instance of this drift.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. One classification per semantic state; flags are aligned to
the existing design (the reconciliation direction is pinned below — code
conforms to design, no design amendment).

## Reconciliation decisions (pinned at refine)

The original acceptance left "match the design OR amend the design" open for
three flag mismatches. The refine pins every arm to **align code to design**
(the §Out-of-scope preference; design is factually authoritative on each
platform's capability, and `supportsCodeFormatting` / `supportsTypingIndicator`
have **zero production call-sites** repo-wide, so a flag flip is purely
declarative — it cannot break a render-path or typing-path branch, only the
value-assertion tests):

| Adapter  | Flag                    | Code (before) | Design                | Action                |
|----------|-------------------------|---------------|-----------------------|-----------------------|
| SimpleX  | supportsTypingIndicator | true          | false (§6.4.2)        | flip code → false     |
| SimpleX  | supportsCodeFormatting  | false         | false (§6.4.2)        | already matches       |
| Signal   | supportsTypingIndicator | true          | true  (§6.5.2)        | already matches       |
| Signal   | supportsCodeFormatting  | false         | true  (§6.5.2)        | flip code → true      |
| InMemory | supportsCodeFormatting  | false         | true  (§6.6 posture)  | flip code → true      |

Flipping SimpleX typing to false also updates the `SimpleXAdapter` class
javadoc (currently states the flag "remains true"). The three forced
flag-value test edits are authorized in `test_plan.modifies`; they change only
the asserted constant, not the test's intent.

`supportsTypingIndicator` "pending M1-105 verification" (original wording): the
pinned values match the design today; M1-105 (multi-adapter wiring) later
verifies live typing behaviour against real transport — not a blocker for this
ticket's flag reconciliation.

## Codec exception-type & classification reconciliation (pinned at refine, round 2)

The original acceptance items 1 and 4 named the *intent* but not the exact
production sites, the `throws`-propagation consequences, or the one pre-existing
test each forces. The second plan-writer pass failed on item 4 before reaching
item 1; this refine pins both, ground-truthed against the code.

**Item 4 — outbound codec validators (SimpleXMessageCodec only).** "Codec/encoder
validator" means an *encode-time* (outbound) validator whose throw reaches the
`send`/`update`/`finalize` SPI contract, which is the two-category retry model.
The two in scope:

| Validator (site)                                  | Before                  | After                          |
|---------------------------------------------------|-------------------------|--------------------------------|
| `requireValidQueueAddressId` (SimpleXMessageCodec:195) | `IllegalStateException` | `MessagingException(PERMANENT)` |
| `requireWithinCap` (SimpleXMessageCodec:229)      | `IllegalArgumentException` | `MessagingException(PERMANENT)` |

`encodeSendCommand`/`encodeUpdateCommand`/`encodeFinalizeCommand`/`encodeTypingCommand`
plus the `encodeEdit` and `targetSelector` helpers they reach gain `throws
MessagingException`. `send`/`update`/`finalize` already declare it. `setTyping`
(SimpleXAdapter:288) keeps its no-throw best-effort contract by wrapping the
encode+fire in `try/catch(MessagingException)` (debug-logged absorb), matching
its existing `ws==null` absorb.

**Out of item 4's scope (stay unchanged):** inbound *decode* validators —
`SignalMessageCodec.decode` `IllegalArgumentException` (:97/:111) is a
transport-corruption→disconnect discipline, and SimpleX inbound uses
`MalformedFrameException`; neither is on the outbound retry path. Config
validators, subprocess guards, and adapter connection-state guards other than
the item-1 not-connected path are also out of scope.

**Item 1 — cross-adapter not-connected classification.** `SignalAdapter.requireConnected`
(:339) currently throws `FailureCategory.TRANSIENT`; `SimpleXAdapter` (:351)
throws `PERMANENT`. Flip Signal to `PERMANENT` so both agree. No pre-existing
test pins Signal's old value (the `SignalJsonRpcClientTest` `TRANSIENT`
assertions cover the -32603 internal-error and response-timeout paths, not the
adapter-level not-connected guard).

**One forced pre-existing test edit (authorized in `test_plan.modifies`):**
`SimpleXMessageCodecTest.encodeRejectsContactIdWithCommandInjectionChars` —
7× `assertThrows(IllegalStateException.class)` → `assertThrows(MessagingException.class)`
with `category()==PERMANENT`, plus the line-303 comment. Happy-path encode
callers already declare `throws Exception`, so they compile unchanged.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A23, §C-ADAPTER-CLASSIFY,
  §C-CODEC-EXC, §C-CAPABILITY-DRIFT; `opus-47-full-handout.md` §F-MAINT-25/26/27, CT5;
  `opus-47-only-handout.md` §M9/10/11/19/30, CT5.
- Plan-writer pass recommended — touches both production adapters, the
  in-memory adapter, and the codec validators together.
