---
id: M1-774
title: "Intake rejections skip the confirm drain and the anchor clear"
status: pending
created: 2026-08-06
last_updated: 2026-08-06
blocked_by: []
files_budget: 6
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  - docs/spec/commands.md
complexity: medium
risk: low
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE SINGLE-LINE RULE'S OWN DRAIN. M1-772 already carried the
    step-4.5 drain across the multi-line rejection
    (`InboundRouter:768-780`), unconditionally and without re-using
    `isConfirmShape`. That branch is the reference implementation for
    whatever this ticket does to the other return paths — it is not
    re-opened, re-argued, or restructured.
  - >-
    `isConfirmShape` AND THE STEP-4.5 SWEEP BLOCK. The sweep's
    cancel-vs-leave-alone decision tree for bodies that DO reach it is
    correct and unchanged. This ticket is about return paths that never
    reach it.
  - >-
    THE LOOSE CONFIRM MATCH. `isConfirmShape` accepts any body that
    starts with `/<prefix> ` and ends with ` confirm`, so
    `/ban bob confirm` redeems a pending `/ban alice`. That is
    pre-existing, orthogonal to the ordering question here, and its own
    ticket if it is one at all — the handler's `takeMatching` is keyed
    on commandName by design.
  - >-
    THE TTL. `infochat.confirm.timeout` stays 60s (90s on `pi`). The
    window's length is not the defect; whether it closes early is.
acceptance:
  - >-
    THE DECISION IS MADE ONCE, FOR EVERY EARLY RETURN. Enumerate every
    `return` in `InboundRouter.onMessage` that precedes the step-4.5
    drain and decide, per path, whether it must drain. The known set is:
    the step-1.5 transport rate-cap drop, the step-2 DM invite gate, the
    step-3 group unregistered/preban drop, the step-4 ban reply, the
    step-3.5 group-approval short-circuit, the chat body cap
    (`:721-726`), the command body cap (`:733-738`), and the step-5
    probation block. Some are vacuous (a banned or unregistered sender
    cannot have armed a confirm); say which, and why, rather than
    draining defensively on a path where no entry can exist.
  - >-
    THE TWO BODY-CAP BRANCHES DRAIN, OR THE SPEC SAYS THEY DO NOT. These
    are the non-vacuous cases: an admin arms `/ban`, then sends an
    over-cap body, and the confirm stays armed for the rest of its TTL
    with no `reply.confirm.cancelled`. `takeAny` is an in-memory map
    removal, so draining does NOT violate §Input length caps' "no DB
    write for an oversized message" — that constraint binds the
    step-4.6 anchor clear, not this.
  - >-
    THE SPEC SENTENCE MATCHES THE CODE. `docs/spec/commands.md`
    §Surface conventions says the confirmation "is scoped to (user,
    scope) and any other input cancels it with an explicit
    acknowledgement". After this ticket that is either literally true,
    or the sentence is amended to state the exception set. Do not leave
    it overstated.
  - >-
    §/retry GETS THE SAME TREATMENT. "Any non-`/retry` input from the
    same (user, scope) clears the anchor" has at least four pre-existing
    exceptions — the ban reply, the probation block, the group-approval
    short-circuit and the step-1.5 rate-cap drop all return ahead of the
    step-4.6 clear, as does M1-772's single-line rejection. The clear is
    a DB write, so hoisting it is a real trade, not a free fix; the
    likely resolution is an amended sentence rather than moved code, but
    the enumeration must be done either way.
  - >-
    NAMED TESTS in `InboundRouterConfirmCancelTest` for whichever paths
    gain the drain, in the shape M1-772 established: assert the pending
    entry is drained, the cancellation is emitted BEFORE the rejection
    reply, and no handler dispatches.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConfirmCancelTest.java
  preserves:
    - >-
      The intake step order: the authorization gates (2/3/4/3.5) stay
      ahead of the body caps, and nothing moves ahead of them.
    - >-
      M1-772's single-line rejection and its drain, byte-for-byte.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
  - docs/spec/commands.md §Conversation control
  - docs/spec/security.md §Authorization model
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
escalation_reason:
---

# M1-774: Intake rejections skip the confirm drain and the anchor clear

## Context

Filed 2026-08-06 out of M1-772's red-team round 1, which found the
then-new multi-line rejection returning ahead of the step-4.5 confirm
drain. That instance was fixed in M1-772. This ticket is the general
case the fix deliberately did not touch.

## The defect

`docs/spec/commands.md` §Surface conventions promises that a pending
confirmation "is scoped to (user, scope) and any other input cancels it
with an explicit acknowledgement". The implementation delivers a
narrower promise: *any input that reaches step 4.5* cancels it. Several
paths return before that point, and on those the user's armed `/ban` or
`/invite create --open` survives, silently, for the rest of its TTL.

The non-vacuous instances are the two body caps
(`InboundRouter:721-726` and `:733-738`). An admin who arms a
destructive confirm and then sends an over-cap body gets no
cancellation and no acknowledgement; the confirm remains redeemable
until the 60s deadline. The other early returns — ban, unregistered,
preban, unapproved group — are almost certainly vacuous, because a
sender on those paths cannot have armed a confirm in the first place,
but "almost certainly" is exactly what this ticket is for: the
enumeration should be written down once rather than re-derived by the
next reader.

§/retry carries the same shape. "Any non-`/retry` input from the same
(user, scope) clears the anchor" is false for at least four
pre-existing return paths plus M1-772's. The difference is that the
anchor clear is a **DB write**, and §Input length caps forbids a DB
write for a body that never reaches the parser — so hoisting it is a
genuine trade rather than a free correction. The confirm drain has no
such obstacle: `ConfirmStateService.takeAny` is a `ConcurrentHashMap`
removal.

## Why this is low severity, and why it is still worth doing

Nothing here is cross-principal. `PendingConfirm` is keyed by
`(actorUserId, scope)`, so no third party can arm, prolong, or redeem
another actor's confirm, and a rejected body dispatches nothing so it
cannot redeem the armed payload itself. The TTL bounds the exposure at
60 seconds and is not extended by the rejected message.

What is worth doing is the honesty: the spec currently states a
property the code does not have, on the primitives the threat model
singles out as having the broadest blast radius. Either the code should
match the sentence or the sentence should match the code — and one
ticket should decide which, for every path at once, rather than each
new intake rejection re-litigating it. M1-772 is the precedent for how
easily that happens: a new early return inherited the gap without
anyone noticing until the red-team pass.

## Reference implementation

`InboundRouter:768-780` (M1-772, commit `444b41fb`). Note the drain
there is **unconditional** — it does not re-use `isConfirmShape`,
because a multi-line body can satisfy that predicate
(`/ban x\nnote confirm` starts with the prefix and ends with
` confirm`) while still being rejected. Any path that gains the drain
in this ticket has the same property and should drain the same way.

## Notes

- M1-772's `redteam_findings[0].disposition` and its §Notes both name
  this follow-up; the round-2 verdict at
  `docs/plan/m1/redteam/M1-772-2026-08-06-r2.md` records the same gap
  as out-of-model item 2 with the bounded-exposure argument spelled
  out.
- The red-team round-2 pass also verified there is no confidentiality
  consequence to a surviving anchor: §/retry's "Status filter on the
  frozen UID set" re-filters the anchor's UIDs against current post
  status at retry time, so an anchor that outlives a rejected message
  cannot re-surface a post quarantined in the meantime.
