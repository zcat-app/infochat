---
id: M1-511
title: "SimpleX groups: v6.5.4.1 mention recognition + auto-accept group invitations"
status: abandoned
abandoned_reason: decomposed
decomposed_into:
  - M1-514
  - M1-515
created: 2026-06-28
last_updated: 2026-06-29
blocked_by:
  - M1-510
files_budget: 10
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  - docs/design/06-messaging.md
  - docs/spec/decisions.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - "DM inbound/outbound/error field alignment — that is M1-510 (this ticket is blocked_by it and builds on the corrected chatInfo.type/meta.itemId reads)."
  - "Signal adapter group path — Signal has its own native membership events (supportsMembershipEvents=true); do not touch it."
  - "The D47 group approval state machine (approval_status pending/approved, /approve-group, /reject-group, rate caps, max-groups) — already specified; this ticket only makes the bot a member so an @mention can reach that machine. Do not re-implement approval."
  - "Changing the D10 identity model for DM or group SENDER identity — sender contactId stays the connection-based memberContactId. This ticket changes only the MENTION-of-the-bot recognition model, not sender identity."
  - "Pinning a different simplex-chat version."
acceptance:
  - >-
    Group @-mention recognition uses meta.userMention (the v6.5.4.1 flag simplex
    sets when the current user/bot is mentioned), replacing the dead
    formattedText[].format.memberRef queue-address model (memberRef does not
    exist in v6.5.4.1 frames). A real captured group message whose
    meta.userMention==true is delivered to Provider as a group-scope Inbound; a
    group message with meta.userMention==false (no bot mention) is NOT delivered.
    Named tests use the real captured group frame verbatim.
  - >-
    The bot's own mention span is stripped from the delivered text using the
    formattedText mention segment(s) / mentions{} object, so "@Admin-Reno help"
    is delivered as "help" (leading/trailing whitespace handled). Named test
    asserts the stripped text against the real frame.
  - >-
    The superseded queue-address mention path (format.memberRef extraction and
    any bot-queue-address byte-match for mentions) is removed, not left dead.
  - >-
    A receivedGroupInvitation async event is decoded to a new DecodedFrame
    variant carrying the adapterGroupId and the inviter identity
    (invitedBy.byContactId). Named test uses a REAL captured invitation frame
    (capture during impl — see Notes; it was not captured at ticket-write time).
  - >-
    The bot auto-accepts a group invitation by issuing /_join #<groupId> ONLY
    when the inviter is a registered user (registration_state IN
    ('invited','vouched') and not banned), per D47's registered-only gate. An
    invitation from an unregistered or banned inviter is NOT auto-joined. After a
    registered-inviter join, /groups shows the bot memberStatus transitioning
    from "invited" to "connected" (live-confirmed mechanism: /_join #<groupId>).
    Because the registration check lives in Provider, this adds the minimal SPI
    surface to surface the invitation and the registration decision across the
    adapter boundary (see Notes) — no Provider business logic in the adapter.
  - >-
    A new decision is recorded in docs/spec/decisions.md: SimpleX group mention
    recognition uses simplex's meta.userMention (superseding the D10
    queue-address-byte-match mention model for SimpleX, which does not map to the
    v6.5.4.1 memberId-based mention shape); and the group invitation auto-accept
    gate (registered inviter only). docs/design/06-messaging.md §6.4 is updated
    to the real v6.5.4.1 group frame shape (chatDir.groupMember.memberContactId,
    mentions{}, meta.userMention) and the invitation/join flow.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  preserves:
    - all tests green on main after M1-510
spec_refs:
  - "docs/spec/messaging.md §Required SPI surface"
  - "docs/design/06-messaging.md §6.4 SimpleX Chat adapter"
  - "docs/design/06-messaging.md §6.4.4 Event decoding"
decision_refs:
  - D10
  - D46
  - D47
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
escalations:
  - date: 2026-06-29
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL (2 blockers)
      1. FILES-BUDGET-PLAUSIBLE: files_scope excludes SPI/Provider paths required by
         AC 5 (InboundHandler.java, MessagingAdapter.java, a Provider-side handler/test
         for the registered-inviter gate). Add the missing paths to files_scope, or
         decompose the SPI-surface change into a separate ticket and narrow AC 5 to
         adapter-codec-only work.
      2. TEST-CHANGES-AUTHORIZED: AC 3 (removing the queue-address mention path)
         requires changing/deleting existing test methods in SimpleXMessageCodecTest.java
         that the ticket does not name. List the modified/deleted methods with new
         assertions, or state only-additions and move the file to test_plan.adds.
clarity_check:
  date: 2026-06-29
  verdict: FAIL
  warnings:
    - "AC 4: real receivedGroupInvitation frame not captured at write time; test finalizable only after the live capture step during impl."
    - "AC 5: registered/banned-inviter gate logic should have a named unit test but none is specified (the memberStatus transition is a live-system check, not a unit test)."
    - "AC 6: doc/decision update acceptance is by inspection only; no runnable check."
    - "SELF-CONTAINED: new SPI method signatures (InboundHandler group-invitation callback; adapter join/decline method) are not spelled out in the ticket body."
    - "FORWARD-REFERENCE: M1-511a / M1-511b referenced in Notes as a decomposition path do not exist on disk."
  blockers:
    - "FILES-BUDGET-PLAUSIBLE: files_scope excludes SPI/Provider paths required by AC 5 (InboundHandler.java, MessagingAdapter.java, and a Provider-side handler/test for the registered-inviter gate). Add the missing paths to files_scope, or decompose the SPI-surface change into a separate ticket and narrow AC 5 to adapter-codec-only work."
    - "TEST-CHANGES-AUTHORIZED: AC 3 (removing the queue-address mention path) requires changing/deleting existing test methods in SimpleXMessageCodecTest.java that the ticket does not name. List the modified/deleted methods with new assertions, or state only-additions and move the file to test_plan.adds."
---

# M1-511: SimpleX groups — v6.5.4.1 mention recognition + auto-accept invitations

## Context

Two group-lifecycle gaps, both confirmed against live v6.5.4.1 frames (captured
via a loopback `java.net.http.WebSocket` probe against the bot's
`ws://127.0.0.1:5225`, with a real test group "Admin Group" / groupId 1):

1. **Group @-mention recognition is broken.** `SimpleXMessageCodec` /
   `SimpleXMentionParser` / `SimpleXGroupHandler` extract mentions from
   `formattedText[].format.memberRef` (a queue address) and byte-match the
   bot's per-adapter queue address (D10). In v6.5.4.1 that field does **not
   exist**: the format entry is `{type:"mention","memberName":"Admin-Reno"}`,
   member identity lives in a separate top-level `mentions{}` object keyed by
   display name (carrying `memberId`/`groupMemberId`, **not** a queue address),
   and simplex sets **`meta.userMention: true`** when the bot was mentioned. So
   `memberRef` is always null → zero mentions → every bot @mention is dropped,
   and the D10 queue-address mention model has nothing to match against.
   **Decision (this ticket):** recognize via `meta.userMention`; use
   `mentions{}` / `formattedText` only to locate the span to strip.

2. **The bot never joins groups.** There is no group-invitation handling
   anywhere in the adapter; the `decode` switch drops the invitation event as
   `unknown-resp-type`. DM contact-requests connect only because the bot's
   address has simplex-level auto-accept (`addressSettings.autoAccept`, seen in
   the `/show_address` frame); group invites have **no** auto-accept, so the bot
   sits at `memberStatus:"invited"` forever and can never receive the @mention
   that D47's approval machine needs. **Decision (this ticket):** auto-accept
   via `/_join #<groupId>` only when the inviter is a registered user
   (`invited`/`vouched`, non-banned), matching D47's registered-only gate; the
   group still enters D47 `approval_status='pending'` on the first @mention.

This ticket is **blocked_by M1-510** because both edit `SimpleXMessageCodec`
and M1-511 builds on M1-510's corrected `chatInfo.type` / `meta.itemId` reads.

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

Invited-group shape (from `/groups`, before join):
```json
"membership":{"memberStatus":"invited","invitedBy":{"type":"contact","byContactId":5}}
```

## Notes

- **Capture the real `receivedGroupInvitation` async event during impl.** It was
  NOT captured at ticket-write time: simplex routes async events to the
  adapter's controlling WS connection, not to a second probe connection, so the
  invitation event can't be sniffed passively. Capture it the same disciplined
  way M1-508 should have: a temporary raw-frame log at the `dispatch` boundary
  (revert before commit; never log user prose in committed code), OR observe the
  `unknown-resp-type` drop and read the event name, then re-invite the bot to a
  fresh group. The accept command is `/_join #<groupId>` (live-confirmed on
  groupId 1). Confirm the exact `resp.type` string and the field carrying the
  group id + inviter contactId before finalizing the decode.
- **Invite gating crosses the SPI boundary.** "Inviter is registered" is a
  Provider-side `users`-table fact; the adapter must not query the DB. Add the
  minimal SPI surface to (a) surface the invitation (adapterGroupId + inviter
  Identity) to Provider and (b) let Provider instruct the adapter to join (or
  decline). Mirror the existing `InboundHandler` callback style; keep the
  registration decision in Provider.
- **Size risk / must-shrink.** files_budget is 10 and this spans codec + handler
  + parser + adapter + SPI + Provider wiring + design + decisions. If the
  reviewer's must-shrink fires at start, the natural split is M1-511a (mention
  recognition, adapter-local) and M1-511b (invitation auto-accept, SPI-crossing).
  Kept as one per the operator's two-ticket structure; flag at `start`.
- **Security-relevant:** group authorization (D47). A redteam pass should confirm
  (1) `meta.userMention` recognition cannot be spoofed by a non-mentioning peer,
  (2) sender identity stays the connection-based `memberContactId` (members
  without a direct contact are still dropped, not fabricated), and (3) the
  registered-inviter gate cannot be bypassed to make the bot join arbitrary
  groups.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-511-*.md
```
