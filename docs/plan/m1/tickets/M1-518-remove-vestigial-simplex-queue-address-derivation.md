---
id: M1-518
title: "Remove vestigial SimpleX bot-queue-address derivation"
status: done
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 11
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentity.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXStartIdentityValidationTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSelfAddressFixture.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  - docs/design/06-messaging.md
  - docs/spec/decisions.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
outline_file: target/m1-tick-outline-M1-518.md
out_of_scope:
  - "Group @-mention recognition logic (memberId byte-equality) — landed in M1-514; do not change recognition or span-stripping. This ticket only removes the now-unused queue-address derivation machinery around it."
  - "Signal adapter identity derivation (the ACI/mentionUuid read from the signal-cli store) — Signal still consumes its derived id for mention recognition; untouched."
  - "Group invitation auto-accept / SPI surface — that is M1-515."
acceptance:
  - >-
    The bot-queue-address self-address derivation is removed now that M1-514 made
    it consumer-less: SimpleXMessageCodec.encodeShowMyAddressCommand, the
    SelfAddress DecodedFrame variant + its decode (userContactLink), and
    SimpleXAdapter.adoptBotQueueAddress + the /show_address query in start()/the
    reconnect path are deleted. The SimpleXGroupHandler is still (re)built on
    start() and after a supervised restart via a direct lifecycle hook (no longer
    via adoptBotQueueAddress), so group-candidate dispatch is unaffected — a named
    test asserts a memberId mention still routes after start() and after a restart.
  - >-
    No dead self-address code remains (no /show_address encode, no SelfAddress
    decode, no isWellFormed-at-adoption gate that nothing feeds). Removing the
    SelfAddress DecodedFrame variant from the sealed hierarchy forces dropping its
    dispatch case in SimpleXWebSocketClient.dispatch() (sealed-switch
    exhaustiveness). SimpleXIdentity is reduced to its static survivors — the
    queueAddress record component is dropped and the static isWellFormed stays
    (audit its remaining references first; sender identity and the bootstrap-admin
    id validation use static isValidQueueAddressId / isWellFormed, not the derived
    bot self-address, so SimpleXIdentityTest needs no change).
  - >-
    SimpleXAdapterIdentityDerivationTest is reduced to the surviving behavior:
    the memberId end-to-end routing + restart-resilience cases stay; the
    self-address codec round-trips (encodeShowMyAddressCommand, decodeUserContactLink*)
    are deleted with the code they covered (authorized here). The
    SimpleXSelfAddressFixture test helper is removed if nothing else references it.
  - >-
    docs/design/06-messaging.md §6.4.1 (Bot identity) is updated to drop the
    /show_address derivation health-check description; docs/spec/decisions.md D51's
    closing sentence (which defers the removal to this ticket) is updated to record
    that the derivation has been removed.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  deletes:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXStartIdentityValidationTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSelfAddressFixture.java
  preserves:
    - all tests green on main after M1-514
spec_refs:
  - "docs/design/06-messaging.md §6.4 SimpleX Chat adapter"
  - "docs/design/06-messaging.md §6.4.4 Event decoding"
decision_refs:
  - D51
reviews:
  - round: 1
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 212
      removed: 719
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: b6afb87e2bcf1d96eb1f7d09c93753b585d519dc
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-518-2026-06-29.md
    out_of_model_count: 0
    note: |
      Pre-commit --in-progress audit of the /show_address derivation removal. CLEAN:
      no auth/authz/ban/audit surface altered; codec input-validation boundary and the
      D51 memberId mention anchor untouched. No findings, no out-of-model items.
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
revisions:
  - date: 2026-06-29
    reason: >-
      clarity-fail rework — widen files_scope/budget for the unscoped self-address
      consumers (SimpleXWebSocketClient SelfAddress dispatch case, SimpleXIdentity
      record reduction, SimpleXStartIdentityValidationTest deletion, SimpleXReconnectTest
      modification, SimpleXSelfAddressFixture deletion) and extend the test-integrity
      authorization to cover those test changes. SimpleXGroupHandler is NOT scoped —
      the lifecycle hook reuses `new SimpleXGroupHandler(this::onInbound)` unchanged;
      SimpleXIdentityTest is NOT scoped — only the static isWellFormed survives, which
      it already exercises.
    snapshot:
      files_budget: 8
      files_scope:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
        - docs/design/06-messaging.md
        - docs/spec/decisions.md
  - date: 2026-06-29
    reason: >-
      budget-breach rework — plan-writer Risk 1+2 surfaced that MultiAdapterProductionIT
      (infochat-provider) consumes SimpleXSelfAddressFixture and its one-shot answerer
      would steal the liveness-probe frame once start() stops issuing /show_address.
      Add MultiAdapterProductionIT.java to files_scope (budget 10->11) and authorize its
      modification; the fixture then becomes consumer-less and its deletion (acceptance
      item 3 conditional) fires.
    snapshot:
      files_budget: 10
      files_scope:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXIdentity.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXStartIdentityValidationTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSelfAddressFixture.java
        - docs/design/06-messaging.md
        - docs/spec/decisions.md
escalations:
  - date: 2026-06-29
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      FILES-BUDGET-PLAUSIBLE: FAIL
      Blocker 1: files_scope omits SimpleXIdentity.java and SimpleXIdentityTest.java.
      Acceptance criterion 2 explicitly requires "SimpleXIdentity is removed or
      reduced" — both files exist as separate files on disk (confirmed). Fix: add
      them to files_scope.
  - date: 2026-06-29
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer-surfaced via plan-writer Risk 1+2). Removing the /show_address
      derivation breaks MultiAdapterProductionIT (infochat-provider, OUTSIDE
      files_scope): its signalCrashDoesNotAffectSimpleX one-shot answerer thread,
      which currently exits after answering start()'s /show_address, never exits
      once start() stops issuing the query — it then steals the liveness-probe
      /_send frame the test's own awaitFrame(2s) expects, failing the assertion.
      SimpleXSelfAddressFixture also cannot be deleted while that IT references it.
      Fix requires adding MultiAdapterProductionIT.java to files_scope (budget 10->11)
      and authorizing its modification; doing so makes the fixture consumer-less so
      its already-authorized deletion then applies.
---

# M1-518: Remove vestigial SimpleX bot-queue-address derivation

## Context

M1-514 switched SimpleX group @-mention recognition from a queue-address
byte-match to a per-group `memberId` byte-match (decision D51), because
v6.5.4.1's mention payload no longer carries a queue address. Mention recognition
was the **sole behavioral consumer** of the bot's derived queue address, so after
M1-514 the whole `/show_address` self-address derivation (query at `start()`,
`SelfAddress` decode, `adoptBotQueueAddress`, restart re-derivation) produces a
value nothing reads — it survives only as an incidental startup health-check.
M1-514 retained it and deferred its removal here per the reviewer "file a
follow-up, don't fix unrelated code inline" rule.

This ticket removes that vestigial machinery.

## Acceptance

See the YAML `acceptance:` list. In short: delete the self-address encode/decode
+ `adoptBotQueueAddress` + the start/reconnect derivation; keep group-candidate
dispatch working by building the handler from a direct lifecycle hook; reduce the
derivation test to its surviving memberId-routing cases; update the design note
and D51.

## Out-of-scope

Recognition logic (M1-514) and Signal's own derivation are untouched. Signal
still derives and consumes its ACI for mention recognition, so its derivation is
NOT vestigial — only SimpleX's is.

**Pre-existing tests this ticket modifies / deletes** (test-integrity
authorization):

- `SimpleXAdapterIdentityDerivationTest` — the self-address codec round-trips
  (`encodeShowMyAddressCommandCarriesCorrIdAndCommand`, `decodeUserContactLink*`)
  are deleted along with the code they cover; the memberId end-to-end routing and
  restart-resilience tests are kept (and may be renamed/moved to a plainer
  `SimpleXAdapterLifecycleTest` if the derivation framing no longer fits the class
  name), retargeted from the removed `deriveAndAdoptIdentity` seam to the new
  direct group-handler lifecycle hook.
- `SimpleXStartIdentityValidationTest` — **deleted in full**: both tests
  (`startRejectsMalformedDerivedQueueAddressNamingTheSource`,
  `startFailsWhenContactLinkCannotBeExtractedNamingTheSource`) pin start()-time
  validation of the *derived* queue address, which no longer exists once the
  `/show_address` derivation is removed. Their subject is gone, not merely renamed.
- `SimpleXReconnectTest` — `servesOnRebuiltTransportWhenRederivedAddressMalformed`
  is **deleted**: it pins the M1-402 reconnect `IllegalStateException` arm that
  handled a malformed *re-derived* address; that arm becomes dead code once
  reconnect stops re-deriving, so it is removed with the code it covered. The
  `/show_address` branch (and its `rederivedQueueAddressId` parameter) is dropped
  from the `startSendResponder` helper. The other reconnect tests
  (`inboundDeliveredExactlyOnceAfterReconnect`, etc.) are unaffected and kept.
- `MultiAdapterProductionIT` (infochat-provider, added to files_scope at the
  budget-breach refine) — the standing `startShowAddressResponder` (static init) and
  the two one-shot `answerNextShowAddress` calls in `simpleXCrashDoesNotAffectSignal`
  / `signalCrashDoesNotAffectSimpleX` answer a `/show_address` that `start()` no
  longer issues. The one-shot in `signalCrashDoesNotAffectSimpleX` would otherwise
  never exit and would **steal the liveness-probe `/_send` frame** the test's own
  `awaitFrame(2s)` expects (the IT's own comment names this exact hazard). Remove the
  responder + both answerers + the now-unused `SimpleXSelfAddressFixture` import, and
  reword the related comments. The cross-adapter blast-radius assertions are
  unchanged — only the now-defunct identity-query plumbing is removed.
- `SimpleXSelfAddressFixture` — **deleted**, but only **after** the
  `MultiAdapterProductionIT` change above removes its last consumer. Within the
  adapter module its callers (`SimpleXAdapterIdentityDerivationTest` retarget,
  `SimpleXStartIdentityValidationTest` delete) already drop it; the provider IT was
  the remaining reference. With all gone, acceptance item 3's conditional ("removed
  if nothing else references it") fires and the fixture is deleted.

## Notes

- **Lifecycle design question (the real work).** Today `SimpleXGroupHandler` is
  built inside `adoptBotQueueAddress` (post-derivation), and `onGroupCandidate`
  drops candidates while `groupHandler == null`. Removing the derivation means the
  handler must be built directly in `start()` / `rebuildWebSocket()`. Decide
  whether to keep the "drop until built" window (build after the WS is up) or
  build eagerly in the constructor (the handler no longer needs any derived
  state — it reads the bot memberId per-frame). Keep it simple; the handler is now
  stateless w.r.t. identity.
- **Audit SimpleXIdentity's remaining consumers first.** Confirm nothing outside
  the removed derivation still needs a `SimpleXIdentity` instance before deleting
  the type. `isValidQueueAddressId` / `isWellFormed` are static and used by sender
  contactId validation and the bootstrap-admin id check — those stay.
- **Redteam defense-in-depth (advisory, from M1-514's CLEAN audit, out-of-model
  item 1).** With the anchor now read per-frame from
  `chatInfo.groupInfo.membership.memberId`, an attacker who reached an
  off-loopback WS port (already out of the threat model per trust boundary 7)
  could set both `membership.memberId` and a `mentions{}` memberId to the same
  arbitrary value and force a match. This is NOT a regression (the prior model
  fed its anchor over the same channel) and is out of the documented threat
  model. If, while removing the startup derivation, a cheap startup-derived
  memberId or queue-address cross-check is trivially available as defense-in-depth,
  weigh it — but do not add scope for it; the threat is operator-created.
- Follows M1-514 (done, commit `282a2aff`). D51 is the governing decision.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-518-*.md
```
