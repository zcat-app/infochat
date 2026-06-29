---
id: M1-514
title: "SimpleX groups: per-group memberId @-mention recognition"
status: done
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 13
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXCodecDeterministicIdTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
  - docs/spec/messaging.md
  - docs/design/06-messaging.md
  - docs/spec/decisions.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
decomposed_from: M1-511
outline_file: target/m1-tick-outline-M1-514.md
out_of_scope:
  - "Group invitation decode / auto-accept and the adapter→Provider SPI surface for it — that is M1-515 (blocked_by this ticket). Do not add a receivedGroupInvitation decode, a joinGroup/decline SPI method, or any Provider-side handler here."
  - "Signal adapter group path — Signal has its own native membership events (supportsMembershipEvents=true) and its own ACI mention anchor; do not touch it. The docs/spec/messaging.md edit generalizes the SimpleX-specific anchor wording only; the cross-adapter byte-equality SPI rule and Signal's ACI anchor are unchanged."
  - "Changing the D10 identity model for DM or group SENDER identity — sender contactId stays the connection-based memberContactId. This ticket changes only the MENTION-of-the-bot recognition anchor (queue address → per-group memberId), not sender identity. Members without a direct contact stay dropped (memberIdOnlyGroupFrame_dropped behavior preserved), never fabricated."
  - "Recognizing on meta.userMention. Per simplex source (Messages.hs) userMention is 'True for messages that mention user OR reply to user messages' — broader than @mention-only and not anchored to the bot's contact id at the adapter. This ticket uses precise memberId byte-equality instead; userMention is at most a documented fallback if a live capture shows groupInfo.membership absent (see Notes)."
  - "DM inbound/outbound/error field alignment — that was M1-510."
  - "Pinning a different simplex-chat version."
acceptance:
  - >-
    Group @-mention recognition does byte-equality of each top-level mentions{}
    entry's memberId against the bot's own per-group memberId, read from the same
    inbound frame at chatInfo.groupInfo.membership.memberId — replacing the dead
    formattedText[].format.memberRef queue-address model (the queue-address
    mention anchor does not exist in v6.5.4.1 frames). A real captured group frame
    in which a mentions{} memberId equals groupInfo.membership.memberId is
    delivered to Provider as a group-scope Inbound; a group frame with no
    mentions{} memberId matching the bot (including a quote-reply-to-bot, which
    sets meta.userMention but carries no bot mention payload) is NOT delivered.
    Named tests in SimpleXMessageCodecTest / SimpleXGroupHandlerTest use the real
    captured group frame verbatim.
  - >-
    Only the bot's OWN mention span is stripped — located via the mentions{} entry
    whose memberId==bot memberId, then its display-name key, then the formattedText
    mention segment(s) carrying that memberName — so "@Admin-Reno help" is
    delivered as "help" (leading/trailing whitespace handled); co-mentions of OTHER
    members are NOT stripped. A named test asserts the stripped text against the
    real frame.
  - >-
    The superseded queue-address mention path is removed, not left dead:
    format.memberRef (queue-address) extraction in
    SimpleXMessageCodec.extractGroupMentions and the bot-queue-address byte-match
    are removed. SimpleXMentionParser's constant-time compare (MessageDigest.isEqual)
    is RETAINED but repointed to memberId byte-equality
    (botMentioned(mentionMemberIds, botMemberId)). No dead queue-address mention
    code remains.
  - >-
    docs/spec/messaging.md §Required SPI surface (the Mention-recognition rule and
    the supportsMentionByContactId capability-flag text) and docs/design/06-messaging.md
    §6.2.3 / §6.3.3 / §6.4 / §6.10 are updated so SimpleX's group mention anchor
    reads as the per-group memberId (from chatInfo.groupInfo.membership.memberId),
    not the queue address; the byte-equality-against-the-bot's-cryptographic-contact-id
    security model is preserved verbatim (only SimpleX's concrete anchor changes).
    A new decision D51 is recorded in docs/spec/decisions.md: SimpleX group mention
    recognition byte-matches the per-group memberId, superseding the queue-address
    anchor (which v6.5.4.1's mention payload no longer carries); D10's identity
    anchor is unchanged for sender identity.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXCodecDeterministicIdTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
  preserves:
    - all tests green on main after M1-510
spec_refs:
  - "docs/spec/messaging.md §Required SPI surface"
  - "docs/design/06-messaging.md §6.4 SimpleX Chat adapter"
decision_refs:
  - D10
  - D46
revisions:
  - date: 2026-06-29
    reason: >-
      Implementation-time refine (run-driven). The original spec recognized via
      meta.userMention. Falsified against simplex source (Messages.hs:
      "userMention :: Bool -- True for messages that mention user or reply to user
      messages") + Types.hs (GroupInfo.membership :: GroupMember; GroupMember.memberId,
      both deriveJSON-serialized): userMention is broader than @mention-only (also
      fires on replies-to-bot) and is not an adapter-side contact-id match. The
      frame already carries the bot's own per-group memberId at
      chatInfo.groupInfo.membership.memberId, so precise memberId byte-equality is
      in-scope and preserves the spec's byte-equality security model. Mechanism
      changed userMention → memberId byte-equality; files_scope gained
      docs/spec/messaging.md (anchor-accuracy edit, queue-address → memberId);
      files_budget 10 → 11. Prior acceptance snapshot: recognize via
      meta.userMention; strip via mentions{}/formattedText; D-decision framed as
      userMention supersession.
  - date: 2026-06-29
    reason: >-
      Implementation-time refine #2 (run-driven, user-directed). Discovered that
      mention recognition was the SOLE behavioral consumer of the bot's derived
      queue address; switching to per-frame memberId decouples them, which (a)
      mechanically breaks SimpleXCodecDeterministicIdTest (group frame needs a
      groupInfo.membership) and (b) invalidates the PREMISE of three behavioral
      tests in SimpleXAdapterIdentityDerivationTest (derived-address-routes-
      mentions / flips-on-restart / admin-decoupling-via-mention-routing) — that
      behavior no longer exists. Both files were outside the prior files_scope.
      Resolution: widen files_scope (+2 test files), files_budget 11 -> 13, and
      authorize re-pointing those tests to the memberId model (deleting the
      admin-decoupling test, whose property is now structural). The queue-address
      derivation is RETAINED as a startup health-check pending follow-up M1-518
      (remove the now-vestigial derivation); per the reviewer rule, that cleanup
      is a separate ticket, not folded in here.
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
      files: 14
      added: 806
      removed: 556
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-29
    verdict: CLEAN
    base: ec149def155e0a32c1ee0e19f6ee315a3da4600c
    head: working-tree (M1-514 branch tip, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-514-2026-06-29.md
    out_of_model_count: 2
    note: |
      Pre-commit --in-progress audit (D47 group authorization). CLEAN — no
      threat-model gap between the diff and docs/spec/security.md. Two
      out-of-model advisory items (per-frame self-anchor trust if the WS
      bot-API port is exposed off-loopback; display-name collision at the
      simplex resolution layer) — both outside the documented threat model
      and NOT regressions introduced by the diff. The per-frame-anchor
      defense-in-depth note is carried to follow-up M1-518.
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-514: SimpleX groups — per-group memberId @-mention recognition

## Context

Split out of M1-511 (decomposed; see §Notes). This is the adapter-codec-local
half: fix group @-mention recognition against live v6.5.4.1 frames. The sibling
M1-515 (blocked_by this ticket) handles group-invitation auto-accept, the
SPI-crossing half.

`SimpleXMessageCodec` / `SimpleXMentionParser` / `SimpleXGroupHandler` currently
extract mentions from `formattedText[].format.memberRef` (a queue address) and
byte-match the bot's per-adapter queue address (D10). In v6.5.4.1 that field
does **not exist**: the format entry is `{type:"mention","memberName":"Admin-Reno"}`,
and member identity lives in a separate top-level `mentions{}` object keyed by
display name, each entry carrying a per-group **`memberId`** (a cryptographic
group member id — `CIMention { memberId :: MemberId, ... }` in simplex), **not** a
queue address. So `memberRef` is always null → zero mentions → every bot @mention
is dropped.

**Decision (this ticket, D51):** recognize a bot @mention by **byte-equality of a
`mentions{}` entry's `memberId` against the bot's own per-group memberId**, which
the same inbound frame carries at `chatInfo.groupInfo.membership.memberId` (the
bot's own group membership). This preserves the spec's security model verbatim —
recognition is byte-equality against the bot's cryptographic contact id, never
display-name text — and only swaps SimpleX's concrete anchor from the (now-absent)
queue address to the per-group memberId. Use `mentions{}` + `formattedText` to
locate the bot's own span to strip.

**Why not `meta.userMention`** (the prior draft): per simplex source
(`Messages.hs`) `userMention` is "True for messages that mention user **or reply
to user messages**" — broader than @mention-only, and it moves the recognition
decision out of the adapter into a coarse flag. memberId byte-equality is precise,
adapter-side, and faithful to §6.2.3. See `revisions:` and §Notes.

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
— **equals `mentions["Admin-Reno"].memberId`**: that equality IS the recognition
predicate. `chatDir.groupMember` is the *sender's* member (memberContactId 5),
distinct from the bot's `membership`. Group system items
(`rcvGroupFeature`/`rcvGroupEvent`) have no `msgContent` and are correctly ignored
by the existing content-ladder — keep that behavior.

**Capture obligation (impl):** the inlined fragment shows only the inner
`chatItem`. Capture one real full group `newChatItem` frame during impl to confirm
the `chatInfo.groupInfo.membership.memberId` envelope is present (verified from
simplex source `deriveJSON` on `GroupInfo`/`GroupMember`, but not yet from a live
frame — no instance was running at ticket time). This also closes the
"frame-fragment lacks chatInfo.groupInfo" risk for the test fixtures.

## Acceptance

1. Recognition by **memberId byte-equality**: deliver a group frame iff some
   `mentions{}` memberId equals `chatInfo.groupInfo.membership.memberId`; a frame
   with no such match (including a reply-to-bot that sets `meta.userMention` but
   carries no bot mention) is dropped. Named tests use the real frame verbatim.
2. Strip **only the bot's own** mention span (mentions{} memberId==bot →
   display-name key → matching `formattedText` segment): `"@Admin-Reno help"` →
   `"help"`, whitespace handled; other members' co-mentions are left intact.
   Named test asserts against the real frame.
3. The dead queue-address path (`format.memberRef` extraction, the
   queue-address byte-match) is removed. `SimpleXMentionParser`'s constant-time
   `MessageDigest.isEqual` compare is retained but repointed to memberId. No dead
   queue-address mention code remains.
4. `docs/spec/messaging.md` §Required SPI surface (Mention-recognition rule +
   `supportsMentionByContactId` text) and `docs/design/06-messaging.md`
   §6.2.3/§6.3.3/§6.4/§6.10 are updated so SimpleX's anchor reads as the per-group
   memberId (not queue address); the byte-equality security model is preserved.
   `docs/spec/decisions.md` records **D51**.
5. `mvn -B verify` is green from the repo root.

## Out-of-scope

Group-invitation decode/auto-accept and the SPI surface that crosses to Provider
are M1-515 (which is `blocked_by` this ticket — both touch `SimpleXMessageCodec`,
`SimpleXGroupHandler`, and the two doc files, so they run sequentially, not in
parallel). Signal is untouched, and the spec edit only generalizes the
SimpleX-specific anchor wording — the cross-adapter byte-equality SPI rule and
Signal's ACI anchor are unchanged. D10 *sender* identity is unchanged — only the
mention-of-the-bot recognition anchor changes (queue address → per-group
memberId); `memberIdOnlyGroupFrame_dropped` (members without a direct contact are
dropped, never fabricated) is preserved. Recognizing on `meta.userMention` is
explicitly out (see §Context "Why not").

**Pre-existing tests this ticket modifies** (test-integrity authorization):
- `SimpleXMentionParserTest.java` — its 6 methods test the queue-address
  byte-match (`botMentioned`). Repointed to memberId byte-equality fixtures
  (the constant-time compare is retained, so the class survives).
- `SimpleXGroupHandlerTest.java` — `mentionByQueueAddress_delivered`,
  `mentionByDisplayName_ignored`, `noMention_ignored`, `mentionSpanStripped*`,
  and the `mentions(...)`/`decomposingBotMention(...)` helpers build
  `format.memberRef` fixtures; rewritten to drive recognition via
  `mentions{}` memberId vs `groupInfo.membership.memberId`, span-stripping via
  the bot's matched segment. Add a reply-to-bot-not-delivered case.
- `SimpleXMessageCodecTest.java` — `groupMentionDecodeKeepsAddressesWhen*`
  (memberRef fixtures) rewritten to the memberId shape; the group newChatItems
  delivery test updated to assert memberId-match delivery.
- `SimpleXCodecDeterministicIdTest.java` — `GROUP_FRAME_NO_ITEM_ID` gains a
  `groupInfo.membership` so the group frame still decodes after the membership
  gate; the determinism assertions are unchanged.
- `SimpleXAdapterIdentityDerivationTest.java` — its three behavioral tests
  (`startDerivesQueueAddressFromShowMyAddress`, `restartRederivesAnchor`,
  `derivedAnchorIndependentOfAdminConfig`) probe the dead "derived queue address
  routes mentions / flips on restart" coupling. Re-pointed to the memberId model:
  the end-to-end FakeSimpleXProcess wire path now asserts a `mentions{}`-memberId
  match against `groupInfo.membership.memberId` routes (and survives a supervised
  restart). `derivedAnchorIndependentOfAdminConfig` is DELETED — its property
  (admin config cannot move the mention anchor) is now structural (there is no
  queue-address mention anchor). The `SelfAddress` codec round-trip tests are
  kept (the derivation wire surface stays for now, see §Notes / M1-518).

## Notes

- **Queue-address derivation retained as a health-check (follow-up M1-518).**
  Mention recognition was the sole behavioral consumer of the bot's derived queue
  address; memberId recognition decouples them, so `adoptBotQueueAddress` + the
  `/show_address` derivation now only validate that the running simplex-chat
  returns a well-formed self-address (a fail-fast startup contract gate). Removing
  the now-vestigial derivation subsystem is **M1-518** (`blocked_by` this ticket)
  — kept out of here per the reviewer rule "file a follow-up, don't fix unrelated
  code inline."

- **Security (redteam vectors 1 & 2 from M1-511).** Recognition stays anchored to
  a cryptographic id the local trusted simplex resolves: a `mentions{}` entry's
  `memberId` is set by simplex from the structured @mention (the v6.3 mention
  model references members by their random group id, NOT typed display-name text),
  so a peer typing `@Admin-Reno` as plain prose creates no `mentions{}` entry and
  no match. The comparison is the bot's own `membership.memberId` (from the frame)
  vs the mention memberId — adapter-side byte-equality, constant-time. Sender
  identity stays the connection-based `chatDir.groupMember.memberContactId`.
- **Reply-to-bot is intentionally NOT delivered.** `meta.userMention` would fire on
  it; memberId match does not (a reply carries no bot mention payload). This keeps
  the spec's "only when @mentioned" contract precise.
- **Multi-mention precision.** Because the strip is keyed to the bot's matched
  memberId/display-name, co-mentions of other members are preserved (avoids the
  over-strip the all-segments approach would cause).
- **Sources / verification.** simplex `Messages.hs` (`userMention` semantics,
  `CIMention.memberId`), `Types.hs` (`GroupInfo.membership :: GroupMember`,
  `GroupMember.memberId`, both `deriveJSON`-serialized), v6.3 mentions blog
  (memberId-anchored, spoof-resistant). The one empirical gap (live full-frame
  confirmation of `chatInfo.groupInfo.membership`) is the capture obligation above.
- `SimpleXMentionParser` stays a package-private constant-time compare helper,
  now over memberId instead of queue address — keep it small.
- Decomposed from M1-511 on 2026-06-29 (clarity-fail: parent was ~13-14 files vs
  budget 10). Sibling: M1-515 (invitation auto-accept). Mechanism refined
  userMention → memberId byte-equality on 2026-06-29 (see `revisions:`).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-514-*.md
```
