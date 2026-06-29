---
id: M1-515
title: "SimpleX groups: auto-accept invitations (registered-inviter gate)"
status: pending
created: 2026-06-29
last_updated: 2026-06-29
blocked_by:
  - M1-514
files_budget: 11
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/group/GroupInvitationHandler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupInvitationHandlerTest.java
  - docs/design/06-messaging.md
  - docs/spec/decisions.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
decomposed_from: M1-511
out_of_scope:
  - "Group @-mention recognition (meta.userMention) and removal of the dead queue-address path — that is M1-514 (this ticket is blocked_by it). Do not change mention recognition or span-stripping here."
  - "Signal adapter group path — Signal has its own native membership events. The new SPI surface MUST be capability-gated / default-method-shaped (mirror setMembershipEventHandler + supportsMembershipEvents) so adding it does NOT force SignalAdapter to change. Do not touch Signal impl files."
  - "The D47 group approval state machine (approval_status pending/approved, /approve-group, /reject-group, rate caps, max-groups) — already specified; this ticket only makes the bot a member so an @mention can reach that machine. Do not re-implement approval."
  - "Changing the D10 SENDER identity model; DM inbound/outbound/error alignment (M1-510); pinning a different simplex-chat version."
acceptance:
  - >-
    A receivedGroupInvitation async event is decoded to a new DecodedFrame
    variant (ReceivedGroupInvitation) carrying the adapterGroupId and the inviter
    identity (invitedBy.byContactId). A named test in SimpleXMessageCodecTest uses
    a REAL captured invitation frame (capture during impl — see Notes; it was not
    captured at ticket-write time).
  - >-
    The adapter surfaces the invitation to Provider across a NEW, minimal SPI
    surface (a callback on the InboundHandler/MessagingAdapter boundary, mirroring
    the existing setMembershipEventHandler style) and accepts an instruction back
    to join or decline. The registration decision lives in Provider; the adapter
    queries no DB.
  - >-
    Provider auto-accepts by instructing the adapter to issue /_join #<groupId>
    ONLY when the inviter is a registered user (registration_state IN
    ('invited','vouched') and not banned), per D47's registered-only gate. An
    invitation from an unregistered or banned inviter is NOT auto-joined. A named
    GroupInvitationHandlerTest asserts both the registered-inviter join and the
    unregistered/banned no-join.
  - >-
    The adapter join path issues /_join #<groupId> (live-confirmed mechanism on
    groupId 1) so the bot memberStatus transitions invited→connected. The group
    still enters D47 approval_status='pending' on the first @mention (no approval
    logic added here).
  - >-
    docs/design/06-messaging.md §6.4.4 Event decoding is updated with the real
    v6.5.4.1 invitation frame shape and the invitation/join flow; docs/spec/decisions.md
    records the group-invitation auto-accept gate (registered inviter only).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupInvitationHandlerTest.java
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  preserves:
    - all tests green on main after M1-514
spec_refs:
  - "docs/spec/messaging.md §Required SPI surface"
  - "docs/design/06-messaging.md §6.4.4 Event decoding"
decision_refs:
  - D46
  - D47
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-515: SimpleX groups — auto-accept invitations (registered-inviter gate)

## Context

Split out of M1-511 (decomposed; see §Notes), the SPI-crossing half.
`blocked_by: M1-514` because both edit `SimpleXMessageCodec` and
`SimpleXGroupHandler` and the two doc files, and this builds on M1-514's
corrected group-frame reads.

There is no group-invitation handling anywhere in the adapter; the `decode`
switch drops the invitation event as `unknown-resp-type`. DM contact-requests
connect only because the bot's address has simplex-level auto-accept
(`addressSettings.autoAccept`); group invites have **no** auto-accept, so the bot
sits at `memberStatus:"invited"` forever and can never receive the @mention
D47's approval machine needs. **Decision (this ticket):** auto-accept via
`/_join #<groupId>` only when the inviter is a registered user
(`invited`/`vouched`, non-banned), matching D47's registered-only gate; the group
still enters D47 `approval_status='pending'` on the first @mention.

Invited-group shape (from `/groups`, before join):
```json
"membership":{"memberStatus":"invited","invitedBy":{"type":"contact","byContactId":5}}
```

## Acceptance

1. `receivedGroupInvitation` decodes to a new `ReceivedGroupInvitation`
   DecodedFrame variant (adapterGroupId + `invitedBy.byContactId`). Named
   SimpleXMessageCodecTest uses a REAL captured invitation frame.
2. A new minimal SPI callback surfaces the invitation to Provider and accepts a
   join/decline instruction back, mirroring `setMembershipEventHandler`;
   capability-gated so Signal is not forced to change. Registration decision
   stays in Provider.
3. Provider instructs `/_join #<groupId>` ONLY for a registered, non-banned
   inviter (`registration_state IN ('invited','vouched')`); unregistered/banned →
   no join. Named `GroupInvitationHandlerTest` asserts both branches.
4. After a registered-inviter join, the bot memberStatus transitions
   invited→connected via `/_join #<groupId>`. No D47 approval logic added.
5. `docs/design/06-messaging.md` §6.4.4 and `docs/spec/decisions.md` updated for
   the invitation frame shape, join flow, and the registered-inviter gate.
6. `mvn -B verify` is green from the repo root.

## Out-of-scope

Mention recognition is M1-514. The D47 approval state machine is untouched — this
ticket only makes the bot a member. Signal is untouched, and the SPI addition
**must not force Signal to change** (capability-gated / default-method shape, per
the `setMembershipEventHandler`/`supportsMembershipEvents` precedent). The new
Provider file `GroupInvitationHandler.java` and its test are net-new.

## Notes

- **Capture the real `receivedGroupInvitation` async event during impl.** It was
  NOT captured at ticket-write time: simplex routes async events to the adapter's
  controlling WS connection, not to a second probe connection. Capture via a
  temporary raw-frame log at the `dispatch` boundary (revert before commit; never
  log user prose in committed code), OR observe the `unknown-resp-type` drop and
  read the event name, then re-invite the bot to a fresh group. Confirm the exact
  `resp.type` string and the fields carrying the group id + inviter contactId
  before finalizing the decode. The accept command is `/_join #<groupId>`
  (live-confirmed on groupId 1).
- **SPI shape (the M1-511 clarity gap this resolves):** `MessagingAdapter` grows
  a capability-gated invitation callback (e.g. `setGroupInvitationHandler`) plus a
  `joinGroup`/`declineGroupInvitation` outbound method. `AdapterRegistry.start()`
  wires the callback to the new Provider `GroupInvitationHandler`, which reads the
  `users` table and calls back to join or decline. Mirror the existing
  `setMembershipEventHandler` precedent so the boundary stays adapter-agnostic and
  Signal is unaffected. `DecodedFrame` is a sealed interface in
  `SimpleXMessageCodec` — add the variant to its `permits` clause.
- **Security (redteam vector 3 from M1-511):** the registered-inviter gate cannot
  be bypassed to make the bot join arbitrary groups; an unregistered or banned
  inviter never triggers `/_join`.
- Decomposed from M1-511 on 2026-06-29. Sibling: M1-514 (mention recognition).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-515-*.md
```
