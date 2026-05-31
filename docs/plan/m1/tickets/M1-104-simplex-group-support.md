---
id: M1-104
title: "SimpleX group support + mention recognition"
status: done
created: 2026-05-26
last_updated: 2026-05-31
blocked_by:
  - M1-103
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
complexity: medium
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - infochat-provider/** — no provider changes
  - any change to MessagingAdapter SPI or CapabilityFlags — the SPI is not modified
  - any change to InMemoryAdapter — unchanged
  - SimpleXSubprocess — M1-103 transport supervisor is frozen
  - SimpleXWebSocketClient connect / frame-loop / command-response / pending-future / error-classification mechanics — frozen; only the dispatch() switch grows one new case routing the new GroupCandidate variant to the group handler, and the constructor takes one additional consumer parameter
  - existing SimpleXMessageCodec behavior for direct-chat frames, error frames, send-ack frames, encoding, and failure classification — only the group-frame decode path is added (new sealed variant); existing tests for the codec MUST stay green
  - multi-adapter wiring — M1-105
  - Signal adapter — M1-106..M1-109
acceptance:
  - "SimpleXMentionParser recognizes @mentions by comparing the mention target's queue address (byte equality) against the bot's per-adapter contact id — display-name matching is never used"
  - "Group messages that @mention the bot are delivered to the InboundHandler with group scope; group messages without a bot @mention are silently ignored"
  - "SimpleXAdapter surfaces a stable per-group id from the SimpleX group protocol"
  - "SimpleXAdapter surfaces user_left_group events to the MembershipHandler if SimpleX exposes a native signal; if not, supportsMembershipEvents is set to false and Provider uses permanent-delivery-failure cleanup per messaging.md §Failure handling"
  - "SimpleXMessageCodec emits a new sealed variant (GroupCandidate) for newChatItem frames with chatType='group', carrying adapterGroupId, sender contact id, sender displayName, text, mentions list (queue addresses only), and adapterMessageId; existing direct-chat decode path is unchanged"
  - "SimpleXGroupHandlerTest.mentionByQueueAddress_delivered passes — a group message with a mention matching the bot's queue address is delivered"
  - "SimpleXGroupHandlerTest.mentionByDisplayName_ignored passes — a group message with only a display-name mention (not queue address) is NOT delivered"
  - "SimpleXGroupHandlerTest.noMention_ignored passes — a group message with no bot mention is silently dropped"
  - "SimpleXGroupHandlerTest.dmMessage_deliveredAsDmScope passes — a DM (non-group) message is delivered with DM scope regardless of mention"
  - "SimpleXGroupHandlerTest.groupIdIsStableAcrossMessages passes — two group messages from the same SimpleX group surface the same adapterGroupId on delivered InboundMessage.scope()"
  - "SimpleXGroupHandlerTest.supportsMembershipEventsFalseWhenNoNativeSignal passes — SimpleXAdapter.capabilities().supportsMembershipEvents() returns false in v1 (the simplex-chat WebSocket bot API does not expose a native user_left_group signal; documented in the test javadoc)"
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
reviews:
  - round: 1
    date: 2026-05-31
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 903
      removed: 42
  - round: 2
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 10
      added: 962
      removed: 43
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-31
    category: AUTH-BYPASS
    severity: high
    promise: |
      "The adapter asserts identity via a stable, cryptographically
      anchored ID. Display names are informational and never used for
      authorization (decision D10)." (§Trust boundaries 1) — paired with
      "Banned-user check is the first thing after identity resolution"
      and "Banned user receives one fixed reply per inbound message"
      (§User ban).
    gap: |
      SimpleXMessageCodec.java memberId fallback (sender fallback:
      `senderContactId = optText(groupMember, "memberId")` when
      `memberContactId` is absent), surfaced into `Identity.contactId`
      via SimpleXGroupHandler.java (`new Identity(gc.senderContactId(),
      ...)`). The codec's own comment calls out that simplex-chat
      surfaces only `memberId` for "members whose contact has not yet
      been established bidirectionally" — i.e., a normal-operation path.
      `memberId` is a per-group identifier, not a cryptographic contact
      id, so Provider's ban check (which uses `(adapter, contact_id)`
      as the join key) misses a row banned under the user's real
      queue-address `contactId`.
    repro: |
      1) Bot admin bans Alice by her real SimpleX queue address; the
      `users` row carries `is_banned=true` for `(simplex,
      alice-queue-addr)`. 2) Alice joins an approved group with the bot
      but has never DM-established contact. 3) Alice posts in the group
      @mentioning the bot; simplex-chat surfaces the frame with
      `memberContactId` absent and only `memberId='group-7-member-3'`.
      4) Codec falls back to memberId; SimpleXGroupHandler delivers
      InboundMessage with `sender.contactId='group-7-member-3'`.
      5) Provider's ban check finds no match for that contact_id; the
      message reaches step 6 and beyond, evading the ban.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-05-31
    category: PERM-ESCAL
    severity: medium
    promise: |
      "Per-adapter trust level: the adapter asserts identity via a
      stable, cryptographically anchored ID" (§Trust boundaries 1) and
      the per-(user, scope) isolation invariant (CLAUDE.md "Per-(user,
      scope) isolation for state, memory, saves. Never leak across
      users").
    gap: |
      SimpleXMessageCodec.java memberId fallback also breaks per-user
      isolation in the other direction: two distinct users in two
      distinct groups can independently surface with the same
      `memberId` (memberId is a per-group counter in simplex-chat, not
      a global identifier), so the codec assigns the same
      `senderContactId` to two different real users. Provider routes
      both into the same `(adapter, contact_id)` row — sharing chat
      memory, saves, follow-tag preferences, and probation state across
      users. Conversely, the same real user appearing across two groups
      with no established contact gets two different `senderContactId`
      values, fragmenting their state.
    repro: |
      1) Alice joins group G1; simplex-chat surfaces her without
      `memberContactId` and assigns `memberId='m-1'`. 2) Bob joins
      group G2; simplex-chat independently surfaces Bob with
      `memberId='m-1'` (the counter is per-group, so collisions across
      groups are routine). 3) Alice @mentions the bot in G1; Provider
      creates/finds a row keyed `(simplex, 'm-1')` for Alice. 4) Bob
      @mentions the bot in G2; Provider finds the same row, treats
      Bob's message as Alice's, applies Alice's probation state, writes
      Bob's group activity into Alice's audit footprint.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-05-31
    verdict: FINDINGS
    base: 94ad9f96c3c71e7b5cb37e2e25afc12dc6aec77e
    head: ccaf6411c54eb0839f2bcf8a9d95a7c645708616
    verdict_file: docs/plan/m1/redteam/M1-104-2026-05-31.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Both findings trace to the codec's memberId fallback for group
      senders without `memberContactId`. Fixed in-branch (Option A:
      drop the fallback; group frames lacking memberContactId now
      decode as Ignored("newChatItem-group-without-sender")) as a
      second commit on the M1-104 branch before the squash-merge, so
      the merged commit on main presents the trust-boundary-tight
      code only. Regression test
      SimpleXGroupHandlerTest.memberIdOnlyGroupFrame_dropped pins the
      rejection contract. See
      docs/plan/m1/redteam/M1-104-2026-05-31.md §disposition for the
      full rationale and verification. Closed by the post-remediation
      audit on the same day (verdict CLEAN); see
      docs/plan/m1/redteam/M1-104-2026-05-31-postfix.md.
  - date: 2026-05-31
    verdict: CLEAN
    base: 94ad9f96c3c71e7b5cb37e2e25afc12dc6aec77e
    head: 3b8f1fd4b984a018e2bc13060fb37fd9244ee8f7
    verdict_file: docs/plan/m1/redteam/M1-104-2026-05-31-postfix.md
    findings_count: 0
    out_of_model_count: 3
    note: |
      Post-remediation audit of the in-branch fix (commit 3b8f1fd)
      confirms the AUTH-BYPASS/high and PERM-ESCAL/medium findings
      from the prior audit are CLOSED. Independent adversarial pass
      found no new findings introduced by the fix. Three OUT-OF-MODEL
      observations: (1) mention-parser cross-encoding collision
      (theoretical only, not reachable against real cryptographic
      queue addresses); (2) log-injection via chatType in DEBUG
      Ignored reasons (out-of-model per logger-baseline rule);
      (3) unbounded mention-list growth (bounded by frame cap and
      Provider rate caps; pre-existing, not introduced by this diff).
      Branch is clear to merge — invoke `/m1-tick merge M1-104` with
      `-C ccaf6411c54eb0839f2bcf8a9d95a7c645708616` so the squash
      keeps the canonical implementation subject.
escalations:
  - date: 2026-05-31
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — developer surfaced a scope gap pre-implementation: acceptance
      items 1, 2, 3 require group-frame decoding, but SimpleXMessageCodec.java
      drops every non-direct chatType as Ignored (line 289-291) and the codec
      was not in the original files_scope (5 listed, files_budget=6). The
      codec's own comment at line 285-288 explicitly names this ticket as the
      one that extends group support. No honest design satisfies acceptance
      without modifying the codec; escalating to refine the files_scope
      rather than silently exceed it.
  - date: 2026-05-31
    reason: round-cap
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — The diff touches 10 files. Subtracting the
      two lifecycle-exempt paths (docs/plan/m1/STATUS.md and
      docs/plan/m1/tickets/M1-104-simplex-group-support.md) leaves 8
      implementation files. The ticket frontmatter sets files_budget: 7,
      so the numeric budget is exceeded by 1. Additionally,
      infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
      is touched by the diff but is NOT a member of files_scope. The change
      is a forced compile-fix (three constructor call sites updated for the
      new fourth argument authorized in out_of_scope), but the file still
      needed to be declared in files_scope and the budget raised to
      accommodate it.
revisions:
  - date: 2026-05-31
    reason: budget-breach refine (codec needed; clarity warnings folded in)
    snapshot:
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
      out_of_scope_summary: "subprocess management or core WebSocket messaging — M1-103 is frozen"
      acceptance_count: 10
      acceptance_named_tests_for_items_3_and_4: missing
  - date: 2026-05-31
    reason: round-cap refine (round 1 SCOPE-DRIFT FAIL — add forced WS-client test mod to scope)
    snapshot:
      files_budget: 7
      files_scope:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParser.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandlerTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMentionParserTest.java
clarity_check:
  date: 2026-05-31
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 3: 'SimpleXAdapter surfaces a stable per-group id' has no named test"
    - "ACCEPTANCE-RUNNABLE item 4: membership-events item is conditional on implementation-time research; neither branch has a named test"
    - "COMPLEXITY-RISK-CALIBRATED: risk: medium on a security_relevant + D10-load-bearing ticket; risk: high would be more conservative"
  blockers: []
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

See frontmatter.

## Notes

- **Codec extension is the minimum entry point.** The existing
  `SimpleXMessageCodec.decode()` drops every non-direct chatType as
  `Ignored("newChatItem-non-direct:<type>")` (line 289-291). The
  codec adds a new sealed variant
  `GroupCandidate(adapterGroupId, senderContactId, senderDisplayName,
  text, mentions list of queue addresses, adapterMessageId)` and
  decodes group-scope newChatItem frames into it. The codec author
  already anchored this at line 285-288 ("Group scope is M1-104
  territory"). `SimpleXGroupHandler` consumes `GroupCandidate` + the
  bot's queue address to make the mention-delivery decision, keeping
  the security-load-bearing comparison in one class. The codec stays
  pure-static (no global mutable state) — the bot identity is held
  by `SimpleXGroupHandler`, not the codec.
- **WS-client touch is variant routing only.** Adding the new
  sealed variant breaks the exhaustive `switch (decoded)` in
  `SimpleXWebSocketClient.dispatch()` (compiler-enforced). The
  client gains one new case
  (`case GroupCandidate gc -> groupCandidateConsumer.onGroup(gc);`)
  and the constructor takes a second consumer alongside
  `inboundConsumer`. Connect, frame-loop, command/response,
  pending-future tracking, supervisor, and error classification
  are all unchanged. ALTERNATIVE: stateful codec with
  `configure(botQueueAddress)` static init avoids the WS-client
  touch but corrupts the codec's "Pure functions; no I/O, no state"
  promise (class javadoc line 21), forces per-test
  configure/teardown for any group-frame test, and puts a
  security-load-bearing decision behind global mutable state.
  Rejected. ALTERNATIVE: raw-frame interceptor on the WS client.
  Rejected — duplicates JSON parsing.
- **SimpleX membership events.** M1-103's
  `SimpleXAdapter.CAPABILITIES` (line 66) already ships with
  `supportsMembershipEvents=false`. M1-104 honors that v1 choice
  and pins it with `supportsMembershipEventsFalseWhenNoNativeSignal`;
  Provider falls back to permanent-delivery-failure cleanup per
  `messaging.md` §Failure handling. The test's javadoc records the
  basis (no observed `user_left_group` / `member_removed` event in
  the simplex-chat WebSocket bot API surface inspected during
  M1-103). If implementation-time research surfaces a native
  signal, BOTH the capability flag flips to true AND the test is
  updated to assert the wired MembershipHandler path — the test
  failing on a future simplex-chat upgrade is the forcing function
  for re-evaluation.
- **Queue address format.** SimpleX queue addresses are base64-encoded
  cryptographic identifiers. Byte equality comparison is exact-match
  on the decoded bytes, not string comparison of the base64 encoding
  (different encodings of the same bytes must match).
- **Group id.** SimpleX groups have an internal id in the simplex-chat
  API. The adapter surfaces this as the stable group_id. The format
  is adapter-specific and opaque to the Provider.
- **D47 downstream impact.** The adapter delivers group messages to
  Provider unchanged; the D47 group authorization gate
  (approval_status check, per-group rate cap) runs inside Provider's
  InboundRouter at step 3.5, not in the adapter. This ticket's
  acceptance criteria are unaffected by D47. However, ITs that send
  group @mentions through InboundRouter after D47 lands will need
  to pre-approve the test group (set approval_status='approved') in
  test setup.

## Round 1 rework

Reviewer verdict (round 1, 2026-05-31): REWORK — SCOPE-DRIFT-CHECK FAIL.

1. Either drop infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
   from the diff (not feasible — the three constructor call sites
   must be updated to pass the new fourth argument, otherwise the
   module will not compile), OR refine the ticket frontmatter via
   `/m1-tick escalate` to (a) raise files_budget from 7 to 8 and
   (b) add SimpleXWebSocketClientTest.java to files_scope. The
   escalation rationale is identical to the original budget-breach
   escalation: the WS-client constructor signature change is
   authorized in out_of_scope but the necessary compile-fix to its
   existing test was not folded into the revised files_scope.
   Without this revision, SCOPE-DRIFT-CHECK fails on both
   (a) files_budget=7 exceeded by one and (b) a diffed file outside
   files_scope.
