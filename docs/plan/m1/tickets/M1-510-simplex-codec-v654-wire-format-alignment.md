---
id: M1-510
title: "SimpleX codec: align DM inbound/outbound/error decode with live v6.5.4.1 wire format"
status: done
created: 2026-06-28
last_updated: 2026-06-29
blocked_by: []
files_budget: 15
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  # M1-510 refine (budget-breach): the chatInfo.type rename lives in the SHARED
  # decodeChatItemEntry, and the /_send array + meta.itemId moves change the
  # shared encode/decode helpers, so every adapter test that hand-builds a frame
  # breaks. These 11 files are migrated to the real v6.5.4.1 fixture shape in the
  # same commit (mvn verify cannot be green otherwise). Empirically confirmed via
  # a module test run: 28 failures across exactly these classes.
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecKnownFieldTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXCodecDeterministicIdTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXInboundDispatchTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXInboundQueueBoundTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXOversizeDropTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterChunkedSendTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXEditFallbackTest.java
  - docs/design/06-messaging.md
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - "Group @-mention RECOGNITION rework (formattedText/mentions{}/meta.userMention) and group invitation auto-accept — those are M1-511. This ticket touches the group path only for the SHARED decoder field reads (chatInfo.type discriminator and chatItem.meta.itemId, both shared by the direct and group dispatch), and the corresponding group test fixtures; it does NOT change mention extraction logic (extractGroupMentions is untouched) or invite-accept."
  - "Signal adapter — its codec has its own envelope shape; do not touch it."
  - "DM identity rules — inbound identity stays the connection-based contactId (D10); this ticket fixes WHICH JSON field the contactId/displayName/itemId are read from, never the identity model."
  - "Pinning a different simplex-chat version — the fix targets the bundled v6.5.4.1 frame shape; version bumps are separate."
  - "The decode dispatch of newChatItems (plural) vs singular — already landed as M1-508; do not re-do it. This ticket fixes the fields read AFTER dispatch reaches the shared entry decoder."
acceptance:
  - >-
    chatInfo type discriminator is read from chatInfo.type (NOT chatInfo.chatType).
    A real v6.5.4.1 newChatItems plural async event (no corrId) with
    chatInfo.type=="direct" decodes to an Inbound instead of being dropped as
    Ignored("newChatItem-without-chatType"). Named test
    decodesDirectInboundUsingRealV654Frame uses the captured frame verbatim as
    the fixture (see Notes). This is the live-confirmed blocker: 100% of SimpleX
    inbound is dropped at this field even after M1-508.
  - >-
    Inbound adapterMessageId is read from chatItem.meta.itemId (NOT top-level
    chatItem.itemId); the captured frame's itemId (e.g. 36 / 20) is surfaced
    rather than the content-hash fallback. Named test asserts the real itemId.
  - >-
    Inbound DM sender displayName is read from contact.localDisplayName (NOT
    contact.displayName), so the captured "admin_1" is surfaced. Named test
    asserts it.
  - >-
    Outbound encodeSendCommand emits the message content as a JSON ARRAY:
    /_send @<id> json [{"msgContent":{...}}]. A real simplex-chat v6.5.4.1
    rejects the prior single-object form with chatCmdError commandError
    "Failed reading: empty" (live-confirmed) and accepts the array form,
    returning a newChatItems send result. Named test asserts the array shape
    of the encoded command string.
  - >-
    decodeSendAck reads the chat-item id from chatItems[0].chatItem.meta.itemId
    (NOT chatItems[0].chatItem.itemId). A real v6.5.4.1 send result
    (resp.type=="newChatItems" WITH corrId) decodes to a SendAck carrying that
    id rather than Ignored("send-ack-without-chatItemId"). Named test uses the
    captured send-ack frame verbatim.
  - >-
    decodeError reads the error tag from BOTH real shapes captured live:
    chatError.errorType.type (e.g. "commandError") and chatError.storeError.type
    (e.g. "groupAlreadyJoined") — the tag is the .type of the nested object, not
    the object itself. Transient tags still classify TRANSIENT and everything
    else PERMANENT (unchanged fail-closed default). Named tests use both real
    error frames; no user-prose bytes are interpolated into logs/exceptions
    (security.md §User content in exceptions still holds).
  - >-
    /_update edit format is verified against live v6.5.4.1 and documented. If the
    in-place edit (encodeUpdateCommand/encodeFinalizeCommand) also requires the
    array/meta shape, it is fixed in the same pass; if the single-object form is
    correct for /_update item, that is asserted by a test and noted in the design.
  - >-
    docs/design/06-messaging.md is corrected: the chatType==direct/group claim
    (~line 536-537) becomes chatInfo.type; the /_send object-vs-array contract
    and the meta.itemId location are documented as the live v6.5.4.1 shape.
  - >-
    Regression: the M1-508 plural-dispatch tests, the M1-506 contactLink-identity
    regression, and all singular-path tests stay green; the hand-rolled fixtures
    that hid these bugs are replaced by/augmented with the real captured frames.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  preserves:
    - all tests currently green on main
    - the M1-508 plural-dispatch tests and the M1-506 contactLink-identity regression
spec_refs:
  - "docs/design/06-messaging.md §6.4.4 Event decoding"
  - "docs/design/06-messaging.md §6.4.5 Command encoding"
decision_refs:
  - D10
  - D46
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
      files: 16
      added: 402
      removed: 101
overrides: []
escalations:
  - date: 2026-06-29
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — mid-implementation budget-breach. The chatInfo.type rename lives in
      the shared decodeChatItemEntry (used by BOTH singular and plural, direct
      and group), and the /_send array + meta.itemId moves change the shared
      encode/decode helpers. A module test run showed 28 failures across 12
      adapter test files, all outside the original 3-file files_scope. The codec
      fix and all fixture migrations must land in one commit (mvn verify cannot
      be green between them), so a follow-up ticket is infeasible. User chose
      refine (widen files_scope/files_budget) on 2026-06-29.
revisions:
  - date: 2026-06-29
    reason: "budget-breach refine — widen files_scope from 3 to 14 files and files_budget 4→15 to cover the SimpleX adapter test fixtures broken by the shared-decoder field changes"
    snapshot:
      files_budget: 4
      files_scope:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
        - docs/design/06-messaging.md
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: 103e1d22aed0d2b3758f6033c0f2901e80e4cc40
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-510-2026-06-29.md
    out_of_model_count: 0
    note: |
      In-progress audit before commit. CLEAN: the inbound identity-decode field
      moves keep identity as the connection contactId (D10), the
      isValidQueueAddressId boundary still gates every wire id, and decodeError
      reads only the enum-like .type (never the free-form message), so no user
      prose leaks into logs/exceptions. No remediation needed.
clarity_check:
  date: 2026-06-28
  verdict: WARN
  warnings:
    - "Acceptance items 2-6 assert 'Named test asserts [X]' without naming the test method (item 1 does name decodesDirectInboundUsingRealV654Frame); behavioral assertions are concrete and reviewer-verifiable from diff/output."
    - "Acceptance item 7 (/_update edit format) is underdetermined until live v6.5.4.1 verification runs during implementation; handled via conditional branches, reviewer-checkable after the fact."
  blockers: []
---

# M1-510: SimpleX codec — align DM inbound/outbound/error decode with live v6.5.4.1

## Context

M1-508 fixed the event *dispatch* (plural `newChatItems` → inbound decoder) but
the codec is still misaligned with the real simplex-chat **v6.5.4.1** wire
format on the fields read *after* dispatch, in BOTH directions. Confirmed
field-by-field against frames captured from a live deployment (a throwaway
`java.net.http.WebSocket` probe issuing `/chats`, `/_send`, `/show_address`
against the bot's loopback `ws://127.0.0.1:5225`):

- **Inbound DM still 100% dropped.** `decodeChatItemEntry` reads
  `chatInfo.chatType`, but the real field is `chatInfo.type` → every inbound
  dies as `Ignored("newChatItem-without-chatType")`. Live-reproduced: two `/help`
  DMs both logged exactly that drop.
- **Outbound 100% broken (two independent bugs).** `encodeSendCommand` emits
  `/_send @<id> json {object}`, but v6.5.4.1 requires a JSON **array**
  (`json [{ "msgContent": … }]`) — the object form returns
  `chatCmdError … commandError "Failed reading: empty"` (live-reproduced); the
  array form succeeds. And `decodeSendAck` reads
  `chatItems[0].chatItem.itemId`, but the id is at
  `chatItems[0].chatItem.meta.itemId` → the ack never resolves even once the
  command is accepted.
- **Error classification inert.** `decodeError` reads `chatError.errorType` as a
  string, but it is an object; the tag is at `chatError.errorType.type` — and a
  second real shape exists, `chatError.storeError.type`. So every error
  classifies as `unrecognized-error-envelope` → forced PERMANENT, and the
  transient-retry vocabulary (rate-limit/network) never fires.
- Minor: inbound `itemId` (meta) and sender `displayName`
  (`contact.localDisplayName`) are read from the wrong field.

Root cause is the same as M1-508: the codec tests are **hand-rolled fixtures**
that encode the design doc's (wrong) assumptions, so CI stays green against
fiction. This ticket replaces them with **real captured frames**.

## Captured real frames (use verbatim as fixtures)

Direct inbound (DM `/help`) — trimmed to the decoded fields:
```json
{"resp":{"type":"newChatItems","chatItems":[{
  "chatInfo":{"type":"direct","contact":{"contactId":5,"localDisplayName":"admin_1",
              "profile":{"displayName":"admin"}}},
  "chatItem":{"chatDir":{"type":"directRcv"},
              "meta":{"itemId":20,"itemText":"/help"},
              "content":{"type":"rcvMsgContent","msgContent":{"type":"text","text":"/help"}}}}]}}
```
Send result (response to `/_send @5 json [{...}]`, carries corrId):
```json
{"corrId":"probe","resp":{"type":"newChatItems","chatItems":[{
  "chatInfo":{"type":"direct","contact":{"contactId":5}},
  "chatItem":{"chatDir":{"type":"directSnd"},
              "meta":{"itemId":21,"itemStatus":{"type":"sndNew"}},
              "content":{"type":"sndMsgContent","msgContent":{"type":"text","text":"…"}}}}]}}
```
Error shapes (both real):
```json
{"resp":{"type":"chatCmdError","chatError":{"type":"error","errorType":{"type":"commandError","message":"Failed reading: empty"}}}}
{"resp":{"type":"chatCmdError","chatError":{"type":"errorStore","storeError":{"type":"groupAlreadyJoined"}}}}
```
Self-address (`/show_address`) — confirms the CURRENT codec path is CORRECT, no change:
`resp.contactLink.connLinkContact.connFullLink` ✓ (and `addressSettings.autoAccept` is why DM contact-requests connect without codec handling).

## Notes

- **Re-capture full frames** with the probe technique above if more context is
  needed; the trimmed frames here are sufficient for the decoded-field set.
- **`/_send` is an array because simplex composes multiple messages per send.**
  v1 sends one message, so the array has one element. Outbound chunking
  (§6.3.4) still produces one `/_send` per chunk.
- **`/_update item` likely stays a single object** (it edits ONE item), but this
  was NOT live-verified (would mutate an existing message). Verify during impl
  before assuming, per the acceptance item.
- **Security-relevant:** inbound identity-decode path. A redteam pass should
  confirm contactId is still the connection-based id (D10), the new field reads
  cannot be driven to mis-attribute an inbound, and no user-prose bytes leak
  into logs/exceptions via the error-tag change.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-510-*.md
```
