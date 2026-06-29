---
id: M1-514
title: "SimpleX groups: meta.userMention @-mention recognition"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 10
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
  - docs/design/06-messaging.md
  - docs/spec/decisions.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
decomposed_from: M1-511
out_of_scope:
  - "Group invitation decode / auto-accept and the adapter→Provider SPI surface for it — that is M1-515 (blocked_by this ticket). Do not add a receivedGroupInvitation decode, a joinGroup/decline SPI method, or any Provider-side handler here."
  - "Signal adapter group path — Signal has its own native membership events (supportsMembershipEvents=true); do not touch it."
  - "Changing the D10 identity model for DM or group SENDER identity — sender contactId stays the connection-based memberContactId. This ticket changes only the MENTION-of-the-bot recognition model, not sender identity. Members without a direct contact stay dropped (memberIdOnlyGroupFrame_dropped behavior preserved), never fabricated."
  - "DM inbound/outbound/error field alignment — that was M1-510."
  - "Pinning a different simplex-chat version."
acceptance:
  - >-
    Group @-mention recognition uses meta.userMention (the v6.5.4.1 flag simplex
    sets when the current user/bot is mentioned), replacing the dead
    formattedText[].format.memberRef queue-address model (memberRef does not
    exist in v6.5.4.1 frames). The real captured group frame whose
    meta.userMention==true is delivered to Provider as a group-scope Inbound; a
    group message with meta.userMention==false is NOT delivered. Named tests in
    SimpleXMessageCodecTest / SimpleXGroupHandlerTest use the real captured group
    frame verbatim.
  - >-
    The bot's own mention span is stripped from the delivered text using the
    formattedText mention segment(s) / mentions{} object, so "@Admin-Reno help"
    is delivered as "help" (leading/trailing whitespace handled). A named test
    asserts the stripped text against the real frame.
  - >-
    The superseded queue-address mention path is removed, not left dead:
    format.memberRef extraction in SimpleXMessageCodec.extractGroupMentions and
    the bot-queue-address byte-match in SimpleXMentionParser.botMentioned (and
    the GroupCandidate.mentionQueueAddresses field if it becomes vestigial) are
    deleted/redesigned. No dead queue-address mention code remains.
  - >-
    docs/design/06-messaging.md §6.4 is updated to the real v6.5.4.1 group-frame
    mention shape (chatDir.groupMember.memberContactId, top-level mentions{},
    meta.userMention) and a new decision is recorded in docs/spec/decisions.md:
    SimpleX group mention recognition uses simplex's meta.userMention,
    superseding the D10 queue-address-byte-match mention model for SimpleX (which
    does not map to the v6.5.4.1 memberId-based mention shape).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
  preserves:
    - all tests green on main after M1-510
spec_refs:
  - "docs/spec/messaging.md §Required SPI surface"
  - "docs/design/06-messaging.md §6.4 SimpleX Chat adapter"
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

# M1-514: SimpleX groups — meta.userMention @-mention recognition

## Context

Split out of M1-511 (decomposed; see §Notes). This is the adapter-codec-local
half: fix group @-mention recognition against live v6.5.4.1 frames. The sibling
M1-515 (blocked_by this ticket) handles group-invitation auto-accept, the
SPI-crossing half.

`SimpleXMessageCodec` / `SimpleXMentionParser` / `SimpleXGroupHandler` currently
extract mentions from `formattedText[].format.memberRef` (a queue address) and
byte-match the bot's per-adapter queue address (D10). In v6.5.4.1 that field
does **not exist**: the format entry is `{type:"mention","memberName":"Admin-Reno"}`,
member identity lives in a separate top-level `mentions{}` object keyed by
display name (carrying `memberId`/`groupMemberId`, **not** a queue address), and
simplex sets **`meta.userMention: true`** when the bot was mentioned. So
`memberRef` is always null → zero mentions → every bot @mention is dropped.
**Decision (this ticket):** recognize via `meta.userMention`; use `mentions{}` /
`formattedText` only to locate the span to strip.

## Captured real group frame (mention "@Admin-Reno help", from /_get chat #1)

```json
"chatDir":{"type":"groupRcv","groupMember":{"memberContactId":5,
            "localDisplayName":"admin_1","memberId":"SENEZlYxaVpZV3dPK2FGWQ=="}},
"meta":{"itemId":36,"itemText":"@Admin-Reno help","userMention":true},
"content":{"type":"rcvMsgContent","msgContent":{"type":"text","text":"@Admin-Reno help"}},
"mentions":{"Admin-Reno":{"memberId":"WE1sRTBSZlVvMS9WYXdFcQ==",
            "memberRef":{"groupMemberId":2,"displayName":"Admin-Reno","memberRole":"member"}}},
"formattedText":[{"format":{"type":"mention","memberName":"Admin-Reno"},"text":"@Admin-Reno"},
                 {"text":" help"}]
```
Bot's own membership (from `/groups`): `membership.memberId == "WE1sRTBSZlVvMS9WYXdFcQ=="`
— equals `mentions["Admin-Reno"].memberId`, corroborating `meta.userMention`.
Group system items (`rcvGroupFeature`/`rcvGroupEvent`) have no `msgContent` and
are correctly ignored by the existing content-ladder — keep that behavior.

## Acceptance

1. Recognition via `meta.userMention`: a real captured group frame with
   `meta.userMention==true` delivers a group-scope Inbound; `==false` does not.
   Named tests use the real frame verbatim.
2. The bot's mention span is stripped (`"@Admin-Reno help"` → `"help"`), with
   leading/trailing whitespace handled. Named test asserts against the real frame.
3. The dead queue-address path (`format.memberRef` extraction, the
   `botMentioned(addresses, botAddress)` byte-match, vestigial
   `mentionQueueAddresses`) is removed, not left dead.
4. `docs/design/06-messaging.md` §6.4 reflects the real v6.5.4.1 mention shape;
   `docs/spec/decisions.md` records the meta.userMention supersession of the D10
   queue-address mention model for SimpleX.
5. `mvn -B verify` is green from the repo root.

## Out-of-scope

Group-invitation decode/auto-accept and the SPI surface that crosses to Provider
are M1-515 (which is `blocked_by` this ticket — both touch
`SimpleXMessageCodec`, `SimpleXGroupHandler`, and the two doc files, so they run
sequentially, not in parallel). Signal is untouched. D10 *sender* identity is
unchanged — only the mention-of-the-bot recognition model changes; the
`memberIdOnlyGroupFrame_dropped` behavior (members without a direct contact are
dropped, never fabricated) is preserved.

**Pre-existing tests this ticket modifies** (test-integrity authorization):
- `SimpleXMentionParserTest.java` — its 6 methods test the D10 queue-address
  byte-match model (`botMentioned`). As that model is removed, these are
  deleted/rewritten to the meta.userMention contract.
- `SimpleXGroupHandlerTest.java` — `mentionByQueueAddress_delivered`,
  `mentionByDisplayName_ignored`, `noMention_ignored`, `mentionSpanStripped*`
  and the `mentions(...)`/`decomposingBotMention(...)` helpers build
  `format.memberRef` fixtures; rewritten to drive recognition via
  `meta.userMention` and span-stripping via `mentions{}`/`formattedText`.
- `SimpleXMessageCodecTest.java` — `groupMentionDecodeKeepsAddressesWhen*`
  (memberRef fixtures) rewritten to the new shape; the group newChatItems
  delivery test updated to assert on `meta.userMention`.

## Notes

- **Security (redteam vectors 1 & 2 from M1-511):** confirm `meta.userMention`
  recognition cannot be spoofed by a non-mentioning peer (the flag is set by
  simplex, not the sender's prose), and that sender identity stays the
  connection-based `memberContactId`.
- `SimpleXMentionParser` is a package-private helper used **only** by the mention
  path; if `meta.userMention` makes it a pure boolean read, the class may shrink
  to near-nothing or fold into the handler — implementer's call, keep it simple.
- Decomposed from M1-511 on 2026-06-29 (clarity-fail: parent was ~13-14 files
  vs budget 10). Sibling: M1-515 (invitation auto-accept).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-514-*.md
```
