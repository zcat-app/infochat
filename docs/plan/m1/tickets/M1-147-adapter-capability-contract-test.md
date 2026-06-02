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
  - "A cross-adapter contract test suite (new AdapterCapabilityContractTest under infochat-messaging-adapter/src/test) asserts each adapter honours the same semantic-state classification: 'not connected' is one category (PERMANENT) across Signal and SimpleX, not TRANSIENT in one and PERMANENT in the other"
  - "Capability flags are aligned to docs/design/06-messaging.md — code conforms to the existing design, no design amendment: SimpleXAdapter.supportsTypingIndicator flips true→false (design §6.4.2: SimpleX has no first-class typing indicator); SignalAdapter.supportsCodeFormatting flips false→true (design §6.5.2: Signal renders monospace); InMemoryAdapter.supportsCodeFormatting flips false→true (design §6.6 capabilities posture: exercises the code-formatting render path). SignalAdapter.supportsTypingIndicator already equals the design (true) and stays unchanged"
  - "The pre-existing flag-value assertions that pinned the OLD values are updated to the reconciled values in the same commit (authorized via test_plan.modifies): SimpleXAdapterSkeletonTest assertTrue→assertFalse on supportsTypingIndicator; SignalAdapterSkeletonTest assertFalse→assertTrue on supportsCodeFormatting; InMemoryAdapterTest assertFalse→assertTrue on supportsCodeFormatting plus its class javadoc. No test is weakened, skipped, or deleted — only the asserted constant changes to track the reconciled flag"
  - "Codec/encoder validators throw the SPI's checked MessagingException(PERMANENT), not IllegalStateException/IllegalArgumentException that bypass the two-category retry model"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/AdapterCapabilityContractTest.java (CT5 cross-adapter contract)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterSkeletonTest.java (supportsTypingIndicator assertTrue→assertFalse — tracks the design-reconciled flag, not a weakening)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java (supportsCodeFormatting assertFalse→assertTrue — tracks the design-reconciled flag)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/inmemory/InMemoryAdapterTest.java (supportsCodeFormatting assertFalse→assertTrue + class javadoc — tracks the design-reconciled flag)
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

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A23, §C-ADAPTER-CLASSIFY,
  §C-CODEC-EXC, §C-CAPABILITY-DRIFT; `opus-47-full-handout.md` §F-MAINT-25/26/27, CT5;
  `opus-47-only-handout.md` §M9/10/11/19/30, CT5.
- Plan-writer pass recommended — touches both production adapters, the
  in-memory adapter, and the codec validators together.
