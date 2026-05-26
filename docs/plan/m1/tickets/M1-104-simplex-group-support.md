---
id: M1-104
title: "SimpleX group support + mention recognition"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-103
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - infochat-provider/** — no provider changes
  - any change to MessagingAdapter SPI or CapabilityFlags — the SPI is not modified
  - any change to InMemoryAdapter — unchanged
  - subprocess management or core WebSocket messaging — M1-103 is frozen
  - multi-adapter wiring — M1-105
  - Signal adapter — M1-106..M1-109
acceptance:
  - "SimpleXMentionParser recognizes @mentions by comparing the mention target's queue address (byte equality) against the bot's per-adapter contact id — display-name matching is never used"
  - "Group messages that @mention the bot are delivered to the InboundHandler with group scope; group messages without a bot @mention are silently ignored"
  - "SimpleXAdapter surfaces a stable per-group id from the SimpleX group protocol"
  - "SimpleXAdapter surfaces user_left_group events to the MembershipHandler if SimpleX exposes a native signal; if not, supportsMembershipEvents is set to false and Provider uses permanent-delivery-failure cleanup per messaging.md §Failure handling"
  - "SimpleXGroupHandlerTest.mentionByQueueAddress_delivered passes — a group message with a mention matching the bot's queue address is delivered"
  - "SimpleXGroupHandlerTest.mentionByDisplayName_ignored passes — a group message with only a display-name mention (not queue address) is NOT delivered"
  - "SimpleXGroupHandlerTest.noMention_ignored passes — a group message with no bot mention is silently dropped"
  - "SimpleXGroupHandlerTest.dmMessage_deliveredAsDmScope passes — a DM (non-group) message is delivered with DM scope regardless of mention"
  - "SimpleXMentionParserTest.queueAddressByteEquality passes — exact byte match of queue addresses succeeds; near-miss fails"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Identity and groups
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D10
  - D46
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-104: SimpleX group support + mention recognition

## Context

Group mode requires `supportsMentionByContactId=true` — the adapter
must recognize @mentions by cryptographic contact id, not display name
(`messaging.md` §Required SPI surface). SimpleX anchors mentions to
queue addresses. This ticket implements mention recognition and group
message routing for the SimpleX adapter.

`security_relevant: true` — mention recognition is security-load-bearing
(an attacker spoofing the bot's display name must not be able to trigger
or suppress mentions, per D10).

## Acceptance

See frontmatter.

## Out-of-scope

- Subprocess management, core messaging — M1-103 is frozen.
- Multi-adapter wiring — M1-105.
- Signal adapter — M1-106+.

## Notes

- **SimpleX membership events.** Research during implementation:
  does the simplex-chat WebSocket API expose `user_left_group` or
  `member_removed` events? If yes, wire to `MembershipHandler` and
  set `supportsMembershipEvents=true`. If no, set the flag to false
  and document the gap — Provider falls back to
  permanent-delivery-failure cleanup per `messaging.md` §Failure
  handling.
- **Queue address format.** SimpleX queue addresses are base64-encoded
  cryptographic identifiers. Byte equality comparison is exact-match
  on the decoded bytes, not string comparison of the base64 encoding
  (different encodings of the same bytes must match).
- **Group id.** SimpleX groups have an internal id in the simplex-chat
  API. The adapter surfaces this as the stable group_id. The format
  is adapter-specific and opaque to the Provider.
