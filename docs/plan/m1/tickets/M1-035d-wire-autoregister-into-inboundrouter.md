---
id: M1-035d
title: Wire AutoRegisterService into InboundRouter intake
status: pending
created: 2026-05-17
last_updated: 2026-05-17
blocked_by: []
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-035d: Wire AutoRegisterService into InboundRouter intake

## Context

Skeleton drafted as the defer target of M1-035 (the M1-035a/b/c
umbrella). M1-035's umbrella IT discovered that
`AutoRegisterService.resolveOrRegister` is never invoked from
production code: `InboundRouter.onMessage` goes directly from
`normalize()` to `handleSlash()` with no AutoRegisterService
injection. `grep -rn 'resolveOrRegister' infochat-provider/src/main/`
returns only the method definition.

M1-035c's own ticket body committed to *"the auto-register-on-first-DM
service the InboundRouter calls before slash-prefix dispatch"*, but
the production wiring was omitted from commit `a6e97ec`. M1-035c is
FROZEN per the umbrella + subticket idiom (never amend a passed
commit); this ticket carries the wiring forward.

## Definition of Done

- (to be filled in)

## Implementation notes

Likely shape:

- `InboundRouter` injects `AutoRegisterService`.
- `onMessage(InboundMessage msg)`: after `normalize()`, before the
  empty-body short-circuit (or before `handleSlash`, depending on the
  spec read), call `autoRegisterService.resolveOrRegister(msg.sender(), adapterName)`.
- The adapter name needs to reach the router. Either:
  (a) `AdapterRegistry.start()` passes a per-adapter `InboundHandler`
      bound to the adapter's name (small SPI change — wrap the router
      per adapter), or
  (b) extend the SPI so `InboundMessage` carries the adapter name
      alongside the scope (intrusive — touches M1-035a's frozen SPI), or
  (c) the router consults `replyTarget.name()` at intake (simplest;
      reply target is set per-adapter at registration time).
- Per `docs/spec/security.md` §Authorization model and §Identity
  intake, auto-register runs AFTER Unicode normalization and BEFORE
  ban / invite / probation checks (those are T2-A). The umbrella IT
  asserts the row insert happens on first DM.

Relevant code:

- `infochat-provider/src/main/java/io/infochat/provider/messaging/InboundRouter.java`
- `infochat-provider/src/main/java/io/infochat/provider/messaging/AutoRegisterService.java`
- `infochat-provider/src/test/java/io/infochat/provider/messaging/InboundRouterTest.java`
  (existing per-branch test — preserve unchanged; add a new test or
  extend InboundRouterTest to assert the AutoRegisterService call
  happens before dispatch)

## Big-picture notes

- This ticket unblocks M1-035 (the umbrella's IT). On `done`, the
  user runs `/m1-tick reopen M1-035` to bring the umbrella back to
  `pending` for re-attempt.
- The umbrella + subticket idiom (per
  `docs/process/workflow.md` §Ticket-ID placeholder convention)
  permits hand-authored suffix-IDs like `M1-035d`. This ticket is a
  late-arriving sibling of M1-035a/b/c that shipped after the
  umbrella's IT-authoring attempt surfaced the missing wire.
- M1-035c's audit comment in `InboundRouter.java` (line 58-59) that
  says *"M1-035c adds the AutoRegisterService at the same intake
  point"* should be updated to reference this ticket once the wiring
  lands (or removed entirely — comments referencing tickets rot).

## Out-of-scope expansion

(to be filled in — likely: no SPI change to InboundMessage; no
change to AutoRegisterService internals; no invite-gating / ban /
probation wiring (T2-A); no group-scope dispatch (T2-F); no
modification to M1-035a/b/c committed surfaces beyond the small
InboundRouter wiring edit).

## Authorized test changes

- (to be filled in — likely: InboundRouterTest gets a new @Test
  asserting the AutoRegisterService call, OR a new
  InboundRouterAutoRegisterTest is added; the existing five branches
  remain unchanged. If the file needs to be modified rather than
  appended-to, list the modification verbatim here.)

## Alternatives considered

- (to be filled in once the implementation approach is chosen — see
  Implementation notes for the (a)/(b)/(c) adapter-name plumbing
  choices.)
