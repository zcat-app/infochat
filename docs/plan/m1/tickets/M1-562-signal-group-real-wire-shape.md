---
id: M1-562
title: Signal group inbound parses the real signal-cli wire shape
status: pending
created: 2026-07-04
last_updated: 2026-07-04
blocked_by: []
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - docs/design/06-messaging.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupInboundRobustnessTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundDispatchTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupEndToEndTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/MembershipDispatchShapeTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMembershipAciGateTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerMembershipIsolationTest.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-messaging-adapter SimpleX and InMemory adapter code — InMemoryAdapter
    keeps supportsMembershipEvents=true and its synthetic dispatch; the
    Provider-side membership path stays live and tested through it
  - infochat-provider/** — AdapterRegistry membership wiring,
    MembershipEventHandler, GroupRepository, and
    MembershipDispatchIsolationTest are untouched (the Provider-side
    event path remains for InMemory; the delivery-failure fallback for
    capability-false adapters already exists, SimpleX precedent)
  - deriving Signal membership deltas via listGroups revision-diff
    (alternative considered and rejected — see §Notes)
  - docs/spec/messaging.md — per-adapter capability values are
    design-tier (spec §"What lives in design notes"); no spec amendment
  - BotRemoved / group-deleted signals — a separate adapter surface per
    spec §Required SPI surface (they fire whether or not
    supportsMembershipEvents is true); not touched here
  - SignalGroupTimestampGuardTest, SignalGroupSpanTypeTest — their
    groupV2 fixtures remain valid because the groupV2 spelling stays
    accepted; they must keep passing unmodified
  - the s07-over-Signal live run and the image rebuild (host work after
    merge, tracked in docs/plan/live-e2e/HANDOFF.md)
acceptance:
  - "SignalGroupHandlerTest gains a groupInfo-shape case: an envelope whose
    dataMessage carries groupInfo{groupId, groupName, revision,
    type=\"DELIVER\"} (the live-captured signal-cli 0.14.5 shape) plus a
    bot-ACI mention dispatches an InboundMessage with
    ScopeRef.Group(<base64 groupId>) and the mention-stripped body."
  - "SignalGroupEndToEndTest drives a shape-faithful reconstruction of the
    live-captured 0.14.5 group envelope (synthetic ACIs and numbers — the
    real capture contains private phone numbers and must never enter the
    repo) through FakeSignalCli's receive-notification path and asserts it
    reaches the InboundHandler — the exact frame class that silently
    dropped live."
  - "The groupV2{id} spelling still dispatches: existing groupV2 message
    fixtures (SignalGroupTimestampGuardTest, SignalGroupSpanTypeTest, the
    surviving SignalGroupHandlerTest cases) keep passing unmodified. Route
    symmetry with SignalMessageCodec.extractDm's dual groupInfo/groupV2
    exclusion guard is preserved: every shape the DM route excludes is a
    shape the group route parses."
  - "A groupInfo stanza that is wrong-typed or missing groupId (or with a
    wrong-typed groupId) drops without throwing —
    SignalGroupInboundRobustnessTest extended to the groupInfo spelling,
    mirroring its existing groupV2 cases."
  - "SignalAdapter declares supportsMembershipEvents=false and
    SignalGroupHandler no longer dispatches MembershipEvents (the
    memberJoined/memberLeft branch and its helpers are removed, and the
    constructor loses the membershipHandler parameter): signal-cli 0.14.5
    exposes no native per-user membership signal in the receive stream,
    and a false-declaring adapter MUST NOT call the membership handler
    (design 06-messaging.md §6.5.4 wiring rule). SignalAdapterSkeletonTest
    and SignalGroupHandlerTest capability assertions are updated to pin
    false."
  - "The Signal membership-dispatch test files are deleted and
    SignalInboundDispatchTest's memberLeft routing case is removed —
    authorized test removals/edits enumerated in §Out-of-scope prose."
  - "docs/design/06-messaging.md is reconciled with the observed wire:
    §6.5.2 flips supportsMembershipEvents to false with the
    permanent-delivery-failure fallback note (SimpleX precedent, §6.3.6),
    and the Signal group wire-shape prose (§6.5.4-adjacent rationale,
    currently 'memberJoined/memberLeft ACI arrays in groupV2 update
    envelopes') documents groupInfo{groupId} as the signal-cli 0.14.5
    receive shape with groupV2{id} retained for route symmetry."
  - "SignalMessageCodec's group-exclusion guard comment (~line 233) is
    corrected — its legacy/current labels are inverted versus the observed
    wire (0.14.5 emits groupInfo, not groupV2); the guard logic itself
    stays byte-identical."
  - mvn verify is green.
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupEndToEndTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupInboundRobustnessTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundDispatchTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
  deletes:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/MembershipDispatchShapeTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMembershipAciGateTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerMembershipIsolationTest.java
  preserves:
    - all tests currently green on main, in particular
      SignalGroupTimestampGuardTest and SignalGroupSpanTypeTest (groupV2
      fixtures stay valid, unmodified) and the provider-side
      MembershipDispatchIsolationTest (InMemory-driven path unchanged)
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Identity and groups
decision_refs:
  - D10
reviews: {}
clarity_check: {}
---

# M1-562: Signal group inbound parses the real signal-cli wire shape

## Context

F-live-10 (docs/plan/live-e2e/HANDOFF.md §Live findings, found 2026-07-04
in the Phase-5 live run): every real Signal group message is silently
dropped. signal-cli 0.14.5 emits the group stanza as
`dataMessage.groupInfo{groupId, groupName, revision, type}` but
`SignalGroupHandler.handleReceive` gates on `dataMessage.groupV2` and
reads `id` — the first guard returns, and the spec-permitted silent-drop
path makes the loss invisible in logs. Green in CI because every fixture
emits the assumed `groupV2` shape (the D-live-9 thesis, Signal edition:
fakes encode the author's assumption, only the real wire falsifies it).
The DM codec's exclusion guard already knew both spellings
(`SignalMessageCodec` ~line 233 checks `groupInfo` OR `groupV2`); the
group-side parser was never reality-reconciled. DMs are unaffected.

Second leg of the finding: 0.14.5's receive stream carries NO
`memberJoined`/`memberLeft` arrays, so the membership-event dispatch —
and the `supportsMembershipEvents=true` declaration it justifies — is
unfulfillable as coded. The spec is prescriptive here: "Adapters without
a native left-group signal MUST set `supportsMembershipEvents = false`"
(messaging.md §Required SPI surface — Membership events), and the
Provider's fallback (permanent-delivery-failure-driven cleanup) already
exists and carries SimpleX — which runs the full group lifecycle,
including the live s07 scenario, with this flag false.

Completing this ticket unblocks the s07 group scenario over Signal (the
last blocking item of live-e2e Phase 5; the 3-party live group fixture
is already built and waiting).

## Acceptance

Mirrors the YAML `acceptance:` list:

1. `SignalGroupHandlerTest` — a `groupInfo{groupId,…}`-shaped envelope
   (the live 0.14.5 shape) with a bot-ACI mention dispatches an
   `InboundMessage` with `ScopeRef.Group(<base64 groupId>)` and the
   mention-stripped body.
2. `SignalGroupEndToEndTest` — a shape-faithful reconstruction of the
   live-captured envelope (synthetic identifiers only) pushed through
   `FakeSignalCli` reaches the `InboundHandler`.
3. The `groupV2{id}` spelling still dispatches; existing groupV2 fixtures
   pass unmodified. Route symmetry with the DM exclusion guard holds.
4. Malformed `groupInfo` (wrong-typed stanza, missing/wrong-typed
   `groupId`) drops without throwing (`SignalGroupInboundRobustnessTest`).
5. `SignalAdapter` declares `supportsMembershipEvents=false`; the
   membership dispatch branch is removed from `SignalGroupHandler`;
   capability-pinning assertions updated to pin false.
6. Membership-dispatch test files deleted; `SignalInboundDispatchTest`'s
   memberLeft case removed (authorized edits, see §Out-of-scope).
7. `docs/design/06-messaging.md` reconciled (capability table + group
   wire-shape prose).
8. `SignalMessageCodec` exclusion-guard comment labels corrected; guard
   logic byte-identical.
9. `mvn verify` is green.

## Out-of-scope

The Provider-side membership machinery stays untouched: InMemoryAdapter
still declares `supportsMembershipEvents=true` and synthesizes events, so
`AdapterRegistry`'s dispatch, `MembershipEventHandler`, and the D47
group-authorization invariants they enforce remain live and tested. The
delivery-failure fallback for capability-false adapters needs no new
code — it is architectural (two signal paths, not a conditional) and
SimpleX already exercises it.

**Authorized pre-existing test changes** (test-integrity rule — anything
not listed here is drift):

- DELETE `MembershipDispatchShapeTest`, `SignalMembershipAciGateTest`,
  `SignalGroupHandlerMembershipIsolationTest` — they pin the removed
  Signal membership-dispatch branch (per-event isolation of the
  Provider-side path is separately covered by the provider module's
  `MembershipDispatchIsolationTest`, which stays).
- `SignalGroupHandlerTest` — membership-dispatch cases removed; the
  capability assertion (~line 361, "MUST remain true per spec") flips to
  pin `false` — the spec mandate it cites points the other way now that
  the wire is known (§Membership events: no native signal ⇒ MUST be
  false); gains the groupInfo cases.
- `SignalAdapterSkeletonTest` (~line 25) — capability assertion flips to
  `false`.
- `SignalInboundDispatchTest` — the `memberLeft` routing case (~lines
  124, 209–218) is removed; its routing concern (non-DM envelopes reach
  the group route) must stay covered by a surviving or reworked case
  using a message-bearing group envelope.
- `SignalGroupEndToEndTest` — the `memberLeft` leg (~line 95) is removed;
  gains the groupInfo end-to-end case.

NOT changed: `SignalGroupTimestampGuardTest`, `SignalGroupSpanTypeTest`
(groupV2 fixtures remain a supported spelling), everything under
`infochat-provider`, both adapters other than Signal, and
`docs/spec/messaging.md`.

## Notes

- **Why dual-shape (groupInfo AND groupV2) rather than groupInfo-only:**
  `SignalMessageCodec.extractDm` excludes BOTH spellings from the DM
  route. If the group route parsed only `groupInfo`, a `groupV2` envelope
  would be dropped by both routes — a coverage gap between two
  complementary filters over the same stream. Dual acceptance also keeps
  the existing groupV2 fixture corpus valid. If both stanzas are somehow
  present (untrusted wire), pick one deterministically (suggest:
  `groupInfo` first, the observed real shape) — the choice only needs to
  be stable, and both carry the same base64 group id semantics.
- **`groupInfo.type` (`DELIVER`/`UPDATE`) needs no gating:** the existing
  body-present check already drops body-less update notifications, same
  as today's behavior for body-less groupV2 envelopes.
- **Membership alternative considered and rejected:** deriving
  join/leave deltas by calling `listGroups` and diffing member lists on
  each `revision` bump. Rejected: it is new I/O machinery + persistent
  state inside a pure-dispatch handler, it turns a push surface into a
  poll, and the spec's fallback (permanent-delivery-failure cleanup,
  §6.3.6) is already proven by SimpleX — including live s07. If a future
  signal-cli restores native deltas in the receive stream, a follow-up
  ticket can re-enable the capability; per the no-backwards-compat rule
  the dead dispatch code does not stay behind as a shim.
- **Fixture privacy:** the live capture (bot-CLI `--output=json receive`)
  contains real phone numbers in `mentions[].name`/`.number`. Test
  fixtures reconstruct the SHAPE with synthetic ACIs/numbers; the capture
  itself never enters the repo. The shape is recorded in
  docs/plan/live-e2e/HANDOFF.md §F-live-10.
- Adjacent pattern: `aciFromArrayEntry`'s both-shapes acceptance comment
  documents the same reality-drift risk this ticket fixes; the new
  groupInfo/groupV2 dual parse should carry a WHY comment of the same
  kind (stable anchor: F-live-10).
- `MessagingAdapter.setMembershipEventHandler` has a no-op default
  (MessagingAdapter.java:231); `SignalAdapter` can drop its override
  (~line 467) and inherit it, mirroring SimpleX's posture.
