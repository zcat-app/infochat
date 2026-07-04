---
id: M1-562
title: Signal group inbound parses the real signal-cli wire shape
status: done
created: 2026-07-04
last_updated: 2026-07-04
escalations:
  - date: 2026-07-04
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A (developer-discovered, pre-review): acceptance item 5's
      constructor change (SignalGroupHandler loses the membershipHandler
      parameter) breaks compilation of four 4-arg call-site test files
      OUTSIDE files_scope — SignalGroupTimestampGuardTest (2 sites),
      SignalAciValidationTest (2), SignalInboundByteCapTest (2),
      SignalGroupSpanTypeTest (2) — two of which out_of_scope declares
      "must keep passing unmodified". RecordingMembership.java is also
      fully orphaned by the change (only surviving users are those call
      sites) and must be deleted per the surgical-cleanup rule. Needs
      files_scope +5 / files_budget 11 -> 16, or a reframed item 5.
  - date: 2026-07-04
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (developer-discovered, pre-review): acceptance item 8 claims
      SignalMessageCodec's group-exclusion guard comment (~line 233) has
      "legacy/current labels inverted versus the observed wire". The
      actual comment reads "Group messages carry groupInfo / groupV2 —
      skip (not a DM); the group route handles them." — it carries no
      such labels and is accurate as-is. There is nothing to correct;
      the item is unsatisfiable as framed. All other acceptance premises
      were re-verified against the code and hold.
blocked_by: []
files_budget: 16
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - docs/design/06-messaging.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupInboundRobustnessTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundDispatchTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupEndToEndTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/MembershipDispatchShapeTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMembershipAciGateTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerMembershipIsolationTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupTimestampGuardTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupSpanTypeTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAciValidationTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundByteCapTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/RecordingMembership.java
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
    groupV2 fixtures and assertions remain valid because the groupV2
    spelling stays accepted; only their SignalGroupHandler constructor
    call sites are updated (4-arg → 3-arg, see the authorized-changes
    ledger); every fixture and assertion stays byte-identical
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
    false. The four 4-arg constructor call-site tests
    (SignalGroupTimestampGuardTest, SignalGroupSpanTypeTest,
    SignalAciValidationTest, SignalInboundByteCapTest) are updated
    mechanically to the 3-arg form — fixtures and assertions
    byte-identical — and the orphaned RecordingMembership fixture is
    deleted."
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
  - mvn verify is green.
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupEndToEndTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupInboundRobustnessTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundDispatchTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAdapterSkeletonTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupTimestampGuardTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupSpanTypeTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAciValidationTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundByteCapTest.java
  deletes:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/MembershipDispatchShapeTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMembershipAciGateTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandlerMembershipIsolationTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/RecordingMembership.java
  preserves:
    - all tests currently green on main, in particular
      SignalGroupTimestampGuardTest and SignalGroupSpanTypeTest (groupV2
      fixtures and assertions byte-identical; only constructor call
      sites updated) and the provider-side
      MembershipDispatchIsolationTest (InMemory-driven path unchanged)
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Capability flags (minimum set)
  - docs/spec/messaging.md §Identity and groups
decision_refs:
  - D10
redteam_findings: []
redteam_audits:
  - date: 2026-07-04
    verdict: CLEAN
    base: a9a110c94a5622ad5969bee4996a12cc8c744b7f
    head: m1/M1-562-signal-group-inbound-parses-th (working tree, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-562-2026-07-04.md
    out_of_model_count: 2
    note: |
      Pre-commit audit of the working-tree diff. CLEAN — the dual-shape
      parse preserves the D10 mention gate on both spellings and the
      capability flip matches the spec mandate. Two advisory out-of-model
      items: (1) leave-driven group_membership / is_group_admin cleanup
      is now uncovered on BOTH v1 adapters (per-user leaves have no
      delivery-failure trigger; spec only commits to refill after
      demotion/ban) — candidate spec clarification or follow-up ticket;
      (2) the group-id scope key is accepted without a base64-shape gate
      (pre-existing posture, daemon stream is a loopback trust boundary)
      — cheap defense-in-depth only if the model ever hardens that
      boundary.
reviews:
  - round: 1
    date: 2026-07-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 18
      added: 346
      removed: 776
clarity_check:
  date: 2026-07-04
  verdict: PASS
  warnings: []
revisions:
  - date: 2026-07-04
    reason: budget-breach rework (user-approved refine via escalation menu)
    snapshot:
      files_budget: 11
      files_scope_added:
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupTimestampGuardTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalGroupSpanTypeTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalAciValidationTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundByteCapTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/RecordingMembership.java
      note: |
        Acceptance item 5's constructor change (membershipHandler
        parameter removed) requires mechanical 4-arg → 3-arg updates at
        four out-of-scope test call sites and deletion of the orphaned
        RecordingMembership fixture. out_of_scope wording for
        TimestampGuard/SpanType relaxed from "must keep passing
        unmodified" to "fixtures and assertions byte-identical; only
        constructor call sites updated". See escalations[0]
        (budget-breach).
  - date: 2026-07-04
    reason: premise-fail rework (user-approved refine via escalation menu)
    snapshot:
      files_budget: 12
      files_scope_removed:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
      acceptance_removed: |
        "SignalMessageCodec's group-exclusion guard comment (~line 233) is
        corrected — its legacy/current labels are inverted versus the
        observed wire (0.14.5 emits groupInfo, not groupV2); the guard
        logic itself stays byte-identical." — removed: the premise is
        false; the actual comment carries no legacy/current labels and is
        accurate as-is (see escalations[0]).
  - date: 2026-07-04
    reason: clarity-fail rework (bounded self-refine via /m1-tick run)
    snapshot:
      body_change: |
        §Out-of-scope "Authorized pre-existing test changes" ledger lacked
        a bullet for SignalGroupInboundRobustnessTest although
        test_plan.modifies and acceptance item 4 both name it. Added the
        bullet (gains groupInfo malformed-stanza cases; loses its
        membership-array cases ~lines 99–164 with the removed branch).
        No frontmatter field changed.
      clarity_check:
        date: 2026-07-04
        verdict: FAIL
        blockers:
          - "TEST-CHANGES-AUTHORIZED: test_plan.modifies includes
            SignalGroupInboundRobustnessTest.java, but the §Out-of-scope
            'Authorized pre-existing test changes' list — which the ticket
            itself declares exhaustive — does not enumerate it. Add a
            bullet naming the specific edit."
        warnings: []
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
8. `mvn verify` is green.

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
- `SignalGroupInboundRobustnessTest` — gains groupInfo-spelling
  malformed-stanza cases (wrong-typed stanza, missing/wrong-typed
  `groupId`) mirroring its existing groupV2 cases; its
  membership-array cases (~lines 99–164: wrong-typed
  `memberJoined`/`memberLeft` drops and the well-formed
  `memberJoined` dispatch) are removed with the membership branch.
  The surviving groupV2 message-path cases stay unmodified.
- `SignalInboundDispatchTest` — the `memberLeft` routing case (~lines
  124, 209–218) is removed; its routing concern (non-DM envelopes reach
  the group route) must stay covered by a surviving or reworked case
  using a message-bearing group envelope.
- `SignalGroupEndToEndTest` — the `memberLeft` leg (~line 95) is removed;
  gains the groupInfo end-to-end case.
- `SignalGroupTimestampGuardTest`, `SignalGroupSpanTypeTest`,
  `SignalAciValidationTest`, `SignalInboundByteCapTest` — mechanical
  constructor call-site updates only (4-arg → 3-arg after the
  membershipHandler parameter is removed); every fixture and assertion
  stays byte-identical.
- DELETE `RecordingMembership.java` — the fixture is fully orphaned by
  this diff (its only surviving users are the updated call sites and
  the deleted membership tests); cleanup of an orphan this change
  creates, per the surgical-changes rule.

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
