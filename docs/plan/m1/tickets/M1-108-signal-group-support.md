---
id: M1-108
title: "Signal mention recognition + group support"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-107
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMentionParser.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMentionParserTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - infochat-provider/** — no provider changes
  - any change to MessagingAdapter SPI — not modified
  - any change to InMemoryAdapter or SimpleXAdapter — unchanged
  - subprocess management or JSON-RPC connection — M1-107 is frozen
  - multi-adapter production IT — M1-109
acceptance:
  - "SignalMentionParser recognizes @mentions by comparing the mentionUuid (ACI) against the bot's per-adapter contact id — display-name matching is never used"
  - "Group messages that @mention the bot (via mentionUuid) are delivered to the InboundHandler with group scope; messages without a bot mention are silently ignored"
  - "SignalAdapter surfaces a stable per-group id from Signal's group v2 id"
  - "SignalAdapter surfaces user_joined_group and user_left_group events to the MembershipHandler — Signal exposes these natively"
  - "supportsMembershipEvents is true (confirmed — Signal protocol provides native membership events)"
  - "SignalGroupHandlerTest.mentionByAci_delivered passes — a group message with mentionUuid matching the bot's ACI is delivered"
  - "SignalGroupHandlerTest.mentionByDisplayName_ignored passes — a group message with only a display-name mention is NOT delivered"
  - "SignalGroupHandlerTest.noMention_ignored passes — a group message with no bot mention is silently dropped"
  - "SignalGroupHandlerTest.memberLeftEvent_surfaced passes — a user_left_group event from signal-cli is delivered to the MembershipHandler"
  - "SignalGroupHandlerTest.memberJoinedEvent_surfaced passes — a user_joined_group event is delivered to the MembershipHandler"
  - "SignalMentionParserTest.aciComparison passes — exact ACI UUID match succeeds; different UUID fails"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMentionParserTest.java
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

# M1-108: Signal mention recognition + group support

## Context

Same pattern as SimpleX (M1-104) but for Signal. Signal's mention
primitive is `mentionUuid` — the ACI (Account Credential Identifier)
that signal-cli surfaces in group message payloads. Signal natively
exposes membership events (user joined, user left), so
`supportsMembershipEvents=true`.

`security_relevant: true` — mention recognition is the D10 trust
anchor for group mode on Signal.

## Acceptance

See frontmatter.

## Out-of-scope

- JSON-RPC connection — M1-107 is frozen.
- Multi-adapter production IT — M1-109.
- SimpleX adapter — unchanged.

## Notes

- **mentionUuid from signal-cli.** In signal-cli's JSON-RPC output,
  group messages carry a `mentions` array where each entry has
  `uuid` (the ACI), `start`, and `length`. The parser checks if
  any mention's UUID matches the bot's ACI.
- **Signal group v2 id.** Signal's group v2 protocol provides a
  stable group id (base64-encoded). This is the adapter's group_id.
- **Membership events.** signal-cli surfaces `memberJoined` and
  `memberLeft` in group update events. These map directly to
  `user_joined_group` and `user_left_group` on the MembershipHandler.
