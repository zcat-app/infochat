---
id: M1-518
title: "Remove vestigial SimpleX bot-queue-address derivation"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
  - docs/design/06-messaging.md
  - docs/spec/decisions.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
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
    decode, no isWellFormed-at-adoption gate that nothing feeds). SimpleXIdentity
    is removed or reduced to only what other callers still use (audit its remaining
    references first; sender identity and the bootstrap-admin id validation use
    static isValidQueueAddressId / isWellFormed, not the derived bot self-address).
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
  preserves:
    - all tests green on main after M1-514
spec_refs:
  - "docs/design/06-messaging.md §6.4 SimpleX Chat adapter"
  - "docs/design/06-messaging.md §6.4.4 Event decoding"
decision_refs:
  - D51
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
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
authorization): `SimpleXAdapterIdentityDerivationTest` — the self-address codec
round-trips (`encodeShowMyAddressCommandCarriesCorrIdAndCommand`,
`decodeUserContactLink*`) are deleted along with the code they cover; the memberId
end-to-end routing and restart-resilience tests are kept (and may be renamed/moved
to a plainer `SimpleXAdapterLifecycleTest` if the derivation framing no longer
fits the class name).

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
