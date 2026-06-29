---
id: M1-515
title: "SimpleX groups: auto-accept invitations (registered-inviter gate)"
status: done
created: 2026-06-29
last_updated: 2026-06-29
blocked_by:
  - M1-514
files_budget: 11
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
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
outline_file: target/m1-tick-outline-M1-515.md
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
      files: 12
      added: 689
      removed: 12
  - round: 2
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 958
      removed: 14
escalations:
  - date: 2026-06-29
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — plan-writer outline R1: implementation requires editing
      SimpleXWebSocketClient.java (the only exhaustive switch over the sealed
      DecodedFrame, at SimpleXWebSocketClient.java:448, has no default and goes
      non-exhaustive the moment the ReceivedGroupInvitation permit is added —
      verified by Read). That path is NOT in files_scope. SimpleXGroupHandler.java
      (in files_scope) handles only GroupCandidate mention recognition (verified:
      no DecodedFrame/invitation reference) and is not needed here. Net: a
      one-for-one files_scope swap (drop SimpleXGroupHandler.java, add
      SimpleXWebSocketClient.java); file count stays 10, files_budget 11 unchanged.
  - date: 2026-06-29
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      MEDIUM DOS (target/redteam-verdict-M1-515.txt): the group-invitation
      inbound path bypasses the §step-1.5 per-(adapter, contact_id) transport
      rate cap (it never routes through InboundRouter.onMessage) and
      SimpleXAdapter.joinGroup issues /_join with no rate token, so a
      registered non-banned user can flood invitations for unbounded /_join +
      DB lookups and dispatch-queue contention. User chose refine (in-branch
      rate-cap fix, round 2). One out-of-model item (silent group membership,
      no audit/admin signal at join) noted as advisory, not fixed here.
  - date: 2026-06-29
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Round-2 re-audit (docs/plan/m1/redteam/M1-515-2026-06-29-recheck.md):
      the round-2 rate-cap remediation CLOSED the transport-rate-cap facet
      (per-(adapter, inviterContactId) cap now applied before the DB lookup,
      mirroring InboundRouter). Re-flags the DEEPER facet the round-1 GAP also
      listed: the rate cap bounds the join RATE, not the TOTAL. The §3.5 D47
      per-user group-activation cap + global max-groups cap are NOT enforced on
      the auto-join surface (they fire only at pending-row creation on first
      @mention). A registered user inviting at ~1/sec (under the transport cap)
      could grow the bot's PASSIVE memberships unbounded over time. Enforcing a
      total join-count cap needs join-tracking — architecturally part of the D47
      approval machine M1-515 lists in out_of_scope, likely a schema migration
      (migration_touch: false here). User chose defer (option 4): file a
      follow-up remediation ticket; ship M1-515 with the round-2 rate-cap
      mitigation (active-processing property intact). One out-of-model item
      (silent group membership) carried over from round 1, unchanged.
overrides: []
revisions:
  - date: 2026-06-29
    reason: "budget-breach refine — files_scope one-for-one swap"
    snapshot:
      files_scope_removed: "infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXGroupHandler.java"
      files_scope_added: "infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java"
      note: "SimpleXGroupHandler (mention recognition, M1-514 concern) not needed; SimpleXWebSocketClient required because its exhaustive DecodedFrame switch goes non-exhaustive when the ReceivedGroupInvitation permit is added. file count 10, files_budget 11 unchanged."
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-06-29
    category: DOS
    severity: medium
    promise: |
      §Authorization model step 1.5 / §User ban / §Rate limiting: the
      per-(adapter, contact_id) transport rate cap runs after step 1 and
      before every application-level check, so a hostile flood cannot drive
      outbound cost via per-inbound fixed-reply paths; all inbound-driven
      outbound cost is bounded.
    gap: |
      The new group-invitation inbound surface bypasses the step-1.5
      transport rate cap entirely — it dispatches via SimpleXWebSocketClient
      → onGroupInvitation → AdapterRegistry.dispatchGroupInvitation →
      GroupInvitationHandler.handle (unconditional findByAdapterAndContactId)
      → source.joinGroup (/_join draws no rate token), never routing through
      InboundRouter.onMessage where rateCapBucket.tryAcquire lives. Invitation
      frames also share the single bounded dispatchQueue with inbound
      messages, so an invitation flood can overflow-drop legitimate inbound.
      Auto-join also bypasses the §3.5 global max-groups / per-user
      group-activation caps (which fire only on pending-row creation at first
      @mention, not at join).
    repro: |
      A registered (registration_state IN ('invited','vouched')), non-banned
      user scripts creating N SimpleX groups and inviting the bot to each. The
      bot emits N /_join outbound commands with no per-(adapter, contact_id)
      bucket consumed, joining N attacker-chosen groups (no upper bound, no
      admin notification until/unless @mentioned), while the N invitation
      frames contend the shared bounded dispatch queue and push other users'
      inbound into the overflow-drop path.
    suggested_fix_class: rate-limit
  - date: 2026-06-29
    category: DOS
    severity: medium
    round: 2
    promise: |
      §Authorization model step 3.5 (D47): enforce the per-user group
      activation cap and the global max-groups cap (both profile-driven). §"What's
      intentionally NOT in v1" names the D47 group authorization gate (per-user
      activation cap, registered-only interaction) as the v1 lever that "bounds
      early resource damage per identity."
    gap: |
      The round-2 remediation added a per-(adapter, inviterContactId) transport
      rate cap to GroupInvitationHandler.handle, which CLOSED the round-1
      no-cap facet (the cap now runs before the registration lookup, mirroring
      InboundRouter). But the cap bounds the join RATE, not the TOTAL: handle()
      consults no total cap. The per-user group-activation cap and global
      max-groups cap live in GroupApprovalService/GroupApprovalCheck and fire
      only at the §3.5 @mention path when a `groups` pending row is created —
      never on the auto-join surface. A single registered, non-banned user gets
      its own ~60-token burst plus ~1 join/sec sustained, with NO ceiling on
      total groups joined; each join is a permanent passive membership.
    repro: |
      A registered (registration_state IN ('invited','vouched')), non-banned
      user — normally onboarded, no admin rights — scripts creating thousands of
      SimpleX groups over time and invites the bot to each at ~1/sec (under the
      transport cap). The bot joins every one (~60 immediately, then unbounded
      growth at the refill rate). Nothing consults the per-user activation cap or
      the global max-groups cap, so the operator's intended membership ceiling is
      silently exceeded by a single identity; the attacker then drives traffic in
      those groups, contending the shared bounded dispatch queue.
    suggested_fix_class: rate-limit
    disposition: |
      Deferred to follow-up ticket M1-519 (remediates: M1-515). Enforcing a
      total join-count cap needs join-tracking — architecturally part of the D47
      approval machine M1-515 lists in out_of_scope, likely a schema migration.
      Active-processing property stays intact (§3.5 caps still fire at @mention);
      the residual is the passive-membership ceiling. Shipped M1-515 with the
      round-2 rate-cap mitigation (bounds the growth slope).
redteam_audits:
  - date: 2026-06-29
    verdict: FINDINGS
    base: abe9541f6a3fbc08b7e36917d62cf4742326a557
    head: working-tree (M1-515 branch tip, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-515-2026-06-29.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Pre-commit --in-progress audit. One MEDIUM DOS finding: the invitation
      inbound path bypasses the §step-1.5 transport rate cap and joinGroup
      draws no rate token, so a registered non-banned user can flood
      invitations for unbounded /_join + DB lookups and dispatch-queue
      contention. Surfaced via redteam-finding escalation; not committed. One
      out-of-model item (silent group membership, no audit/admin signal at
      join) flagged for a decision.
  - date: 2026-06-29
    verdict: FINDINGS
    base: abe9541f6a3fbc08b7e36917d62cf4742326a557
    head: working-tree (M1-515 branch tip, post-rate-cap-fix, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-515-2026-06-29-recheck.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Round-2 re-audit after the rate-cap remediation. Confirms the round-1
      transport-rate-cap facet is CLOSED. Re-flags the deeper facet: rate cap
      bounds the join RATE, not the TOTAL — the §3.5 D47 per-user
      group-activation + global max-groups caps are not enforced on the
      auto-join surface, so passive memberships can grow unbounded at the
      rate-cap slope. User chose defer → follow-up ticket M1-519 (remediates:
      M1-515); M1-515 ships with the rate-cap mitigation. One out-of-model item
      (silent group membership) carried over unchanged.
clarity_check:
  date: 2026-06-29
  verdict: WARN
  warnings:
    - "Acceptance item 1 does not prescribe a specific test method name; a reviewer must scan SimpleXMessageCodecTest for a test exercising receivedGroupInvitation decode rather than running one named invocation. Low friction; worth tightening if the author has a preferred name."
    - "Acceptance item 4 claims live memberStatus transition behavior (invited→connected) that is not covered by any automated assertion — only by the manual 'live-confirmed on groupId 1' note. The item is informational and item 3's test covers the /_join command issuance; no blocker, but the transition claim cannot be regression-detected by the test suite."
  blockers: []
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

## Round 2 rework (redteam-finding: rate-cap bypass)

The round-1 implementation passed review (APPROVE) but the pre-commit `--in-progress`
redteam audit found a MEDIUM DoS gap (`docs/plan/m1/redteam/M1-515-2026-06-29.md`):
the new group-invitation inbound surface bypasses the `security.md` §step-1.5
per-`(adapter, contact_id)` transport rate cap that fires before every
application-level check. Fix (in `files_scope` — `GroupInvitationHandler.java` +
its test only):

- Inject the existing `RateCapBucket` bean into `GroupInvitationHandler` and
  consume a per-`(adapter, inviterContactId)` token (`tryAcquire`) at the start of
  `handle`, before the registered-inviter DB lookup. When the bucket is exhausted,
  drop the invitation (no lookup beyond what the cap needs, no join) — the same
  transport-cap-before-application-checks shape `InboundRouter.onMessage` uses.
  This bounds both the `/_join` outbound frequency and the DB-lookup rate per
  inviter, closing the flood vector.
- Add a test asserting an over-cap invitation flood from one inviter stops
  triggering joins once the bucket is drained.

Out of scope for this rework (advisory only, not a stated-spec violation): the
out-of-model "silent group membership / no audit row at join" item — left as a
conscious gap (matches outline R7); revisit separately if desired.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-515-*.md
```
