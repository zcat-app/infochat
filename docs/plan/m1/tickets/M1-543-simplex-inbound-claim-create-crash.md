---
id: M1-543
title: Fix SimpleX first-inbound crash at SimpleXAdminClaim create
status: pending
created: 2026-07-02
last_updated: 2026-07-02
blocked_by:
  - M1-542
files_budget: 4
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any file not yet known (skeleton — files_scope set on refine once the root
    cause from M1-542 is visible)
acceptance:
  - PROVISIONAL, to refine after M1-542 reveals the cause — an inbound DM to the
    SimpleX bot no longer throws at SimpleXAdminClaim ARC bean creation; the
    message is routed (claim then invite fall-through) instead of being dropped by
    the D37 catch.
  - A @QuarkusTest reproduces the live-only failure mode (a green such test today
    is exactly why it escaped — the repro must capture whatever the live wiring
    differs on) and passes after the fix.
  - mvn verify is green.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D50
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-543: Fix SimpleX first-inbound crash at SimpleXAdminClaim create

## Context

Live-e2e Phase 4b finding **F-live-1** (`docs/plan/live-e2e/HANDOFF.md` §Live
findings): the FIRST real inbound DM to the SimpleX bot crashes the Provider
inbound handler. `InboundRouter.onMessage` (InboundRouter.java:504) calls
`SimpleXAdminClaim.claim(...)`; the `@ApplicationScoped SimpleXAdminClaim` bean
throws a bare `RuntimeException` **at ARC instantiation** (`SimpleXAdminClaim_Bean.create`),
before `claim()` runs, and `SimpleXAdapter.onInbound` silently drops the message
per D37 — the sender never gets a reply. Deterministic (every inbound). It is
**live-only**: `SimpleXAdminClaimTokenTest` is a `@QuarkusTest` that creates the
same bean the same way and is green on main.

This ticket is a **skeleton**, deliberately `blocked_by: M1-542`. The root cause is
currently masked because the D37 stack logger drops the cause chain; M1-542 fixes
that, so re-sending the live DM after M1-542 lands will print the real `Caused by:`.
Only then can this ticket's `files_scope` and acceptance be pinned to the actual
defect (via `/m1-tick escalate M1-543 refine`). Falsified-already hypotheses
(do not re-chase): config expansion of `infochat.adapters.simplex.admin-token`
(env present, token is clean letters); injected-bean init (`RegisteredContactSet`
is `@Startup` and boot succeeded; `AuditLogWriter`/`DataSource` fine).

## Acceptance

See the provisional YAML `acceptance` above — to be replaced on refine once the
cause is known. The binding shape will be: (1) a `@QuarkusTest` that reproduces the
live inbound crash and goes green with the fix, (2) an inbound DM routes normally
instead of being dropped, (3) `mvn verify` green.

## Out-of-scope

To be set on refine. Until then: no code is written for this ticket (it cannot
start while `blocked_by: M1-542` is unmet).

## Notes

- Repro (after the stack up + clients provisioned per HANDOFF): send an admin-client
  DM to the bot and grep the Provider log for `inbound handler threw`.
- The fix must not regress the D50 claim-token security properties (single-use gate,
  constant-time compare, no token-validity oracle, audit-before-effect).
- Refine will likely flip `security_relevant: true` depending on the fix surface.
