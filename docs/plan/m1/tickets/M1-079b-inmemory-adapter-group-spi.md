---
id: M1-079b
title: InMemoryAdapter group SPI + membership event model
status: done
created: 2026-05-25
last_updated: 2026-05-25
reviews:
  - round: 1
    date: 2026-05-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 241
      removed: 14
clarity_check:
  date: 2026-05-25
  verdict: PASS
  warnings: []
  blockers: []
blocked_by:
  - M1-079a
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/inmemory/InMemoryAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MembershipEvent.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/inmemory/InMemoryAdapterGroupTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - infochat-provider/** — handler unwinding and command handlers are M1-079c/d/e
  - infochat-core/src/main/resources/db/migration/** — no migration in this ticket
  - any modification to ScopeRef.java — stays as-is
  - any /promote, /demote, /group-timezone handler — M1-079c
  - any modification to InboundRouter.java — M1-079c
  - any modification to any pre-existing test in infochat-messaging-adapter/src/test/ or infochat-provider/src/test/
  - SimpleX or Signal adapter changes — v1 adapters are out of M1 scope
acceptance:
  - "MembershipEvent.java is a sealed interface with permits: UserJoined(String adapterGroupId, String contactId), UserLeft(String adapterGroupId, String contactId), BotRemoved(String adapterGroupId), GroupDeleted(String adapterGroupId)"
  - "MessagingAdapter.java gains a default method `void onMembershipEvent(MembershipEvent event)` that is a no-op by default (existing adapters unaffected); the InMemoryAdapter overrides it to record events for test assertions"
  - "InMemoryAdapter gains group primitives: createGroup(String groupId) registers a group, addMember(String groupId, String contactId) adds a member, removeMember(String groupId, String contactId) fires UserLeft, removeBot(String groupId) fires BotRemoved"
  - "InMemoryAdapter gains deliverGroupMention(String groupId, String senderContactId, String text) that delivers an InboundMessage with ScopeRef.Group(groupId) and Identity(senderContactId) to the registered InboundHandler"
  - InMemoryAdapterGroupTest.createGroup_registersGroup passes
  - InMemoryAdapterGroupTest.addMember_addsToGroup passes
  - InMemoryAdapterGroupTest.deliverGroupMention_deliversInboundMessageWithGroupScope passes
  - InMemoryAdapterGroupTest.removeMember_firesUserLeftEvent passes
  - InMemoryAdapterGroupTest.removeBot_firesBotRemovedEvent passes
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/inmemory/InMemoryAdapterGroupTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Identity and groups
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D46
---

# M1-079b: InMemoryAdapter group SPI + membership event model

## Context

The InMemoryAdapter is currently DM-only — it can deliver DM messages
but has no group primitives. T2-F's integration tests (the M1-079
umbrella IT) need to drive group lifecycle scenarios end-to-end through
the adapter: create a group, add members, deliver @mentions, trigger
user-left and bot-removed events. This ticket extends the adapter SPI
with a `MembershipEvent` sealed type and gives InMemoryAdapter the
group primitives needed by downstream tests.

The spec contract is `docs/spec/messaging.md` §Identity and groups
(adapter must expose group membership signals) + §Failure handling
(bot-removed and user-left lifecycle).

## Acceptance

1. `MembershipEvent` is a sealed interface in the messaging-adapter
   module with four permits: `UserJoined`, `UserLeft`, `BotRemoved`,
   `GroupDeleted`. Each carries the `adapterGroupId`; user-scoped
   events also carry `contactId`.
2. `MessagingAdapter` gains a default `onMembershipEvent` method
   (no-op) so existing adapter implementations are unaffected.
3. `InMemoryAdapter` gains group primitives for test use:
   `createGroup`, `addMember`, `removeMember`, `removeBot`.
4. `InMemoryAdapter.deliverGroupMention(groupId, senderContactId, text)`
   delivers an `InboundMessage` with `ScopeRef.Group(groupId)` and
   `Identity(senderContactId)` to the registered handler.
5. Membership events are recorded on InMemoryAdapter for assertion
   by tests (e.g., `adapter.membershipEvents()` returns the list).
6. All tests pass; `mvn verify` is green.

## Out-of-scope

- Provider-side consumption of membership events (writing
  `removed_at` on group_membership rows) — that wiring is M1-079c's
  InboundRouter group-dispatch work.
- SimpleX/Signal adapter implementations — those are v1 adapter
  tickets beyond M1.
- Any command handler changes.
- Any migration changes.

## Notes

- The `MembershipEvent` sealed type lives in `infochat-messaging-adapter`
  (the SPI module), not in `infochat-provider`, because it's part of
  the adapter contract. Adapters surface these signals; Provider
  consumes them.
- `deliverGroupMention` mirrors the existing `deliverDm` method
  shape — it constructs an `InboundMessage` with the appropriate
  `ScopeRef` variant and calls the registered `InboundHandler`.
- The default `onMembershipEvent` on `MessagingAdapter` ensures the
  interface stays minimal — adapters that don't support groups (none
  in v1, but the contract allows it) don't need to implement it.
- InMemoryAdapter's internal group state is a simple
  `Map<String, Set<String>>` (groupId → member contactIds) — no
  persistence needed for test infrastructure.
