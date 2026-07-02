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
security_relevant: true
migration_touch: false
out_of_scope:
  - the Signal adapter and its admin bootstrap path (ACI-based, no claim token)
  - the D37 stack logger (SimpleXAdapter.stackWithoutMessage — M1-542's surface;
    no further changes to it)
  - any weakening of the D50 claim-token properties (single-use gate,
    constant-time compare, no token-validity oracle, audit-before-effect)
  - InboundRouter dispatch branches beyond the admin-claim / invite intake path
  - infochat-collector (untouched)
  - DB schema / Flyway migrations
acceptance:
  - An inbound DM to the SimpleX bot no longer throws at SimpleXAdminClaim ARC
    bean creation — InboundRouter.onMessage routes the message (admin-claim
    check, then invite fall-through) instead of SimpleXAdapter.onInbound's D37
    catch dropping it.
  - A @QuarkusTest reproduces the live-only failure mode (a green such test
    today is exactly why it escaped — the repro must capture whatever the live
    wiring differs on; SimpleXAdminClaimTokenTest is green on main while the
    live container crashes on every inbound) — red before the fix, green after.
  - mvn verify is green.
test_plan:
  adds:
    - a @QuarkusTest reproducing the live-only SimpleXAdminClaim ARC-create
      crash (red before the fix, green after; class name bound at
      implementation)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Per-adapter admin threat profile
decision_refs:
  - D50
reviews: {}
revisions:
  - date: 2026-07-02
    reason: clarity-fail rework (bounded self-refine via /m1-tick run)
    snapshot:
      status: pending
      security_relevant: false
      acceptance:
        - "PROVISIONAL, to refine after M1-542 reveals the cause — an inbound
          DM to the SimpleX bot no longer throws at SimpleXAdminClaim ARC bean
          creation; the message is routed (claim then invite fall-through)
          instead of being dropped by the D37 catch."
        - "A @QuarkusTest reproduces the live-only failure mode (a green such
          test today is exactly why it escaped — the repro must capture
          whatever the live wiring differs on) and passes after the fix."
        - "mvn verify is green."
      out_of_scope:
        - "any file not yet known (skeleton — files_scope set on refine once
          the root cause from M1-542 is visible)"
      test_plan_adds: []
      clarity_check:
        date: 2026-07-02
        verdict: FAIL
        blockers:
          - "Acceptance item 1 is self-labeled PROVISIONAL / 'to be replaced
            on refine once the cause is known' — not a stable, checkable bar."
          - "out_of_scope has exactly one entry and it is 'any file not yet
            known' — the vague/circular form; commits the implementer to no
            boundary at all."
        warnings:
          - "SECURITY-FLAG-CONSISTENT: security_relevant: false while the
            ticket touches the D50 SimpleX admin-claim-token bean and lists
            D50 invariants the fix must preserve."
          - "test_plan.adds is empty while acceptance item 2 requires a
            @QuarkusTest repro."
      escalation_reason: clarity-fail
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

Diagnosis-first implementation order (per user direction "fix both, run once"):
build the `@QuarkusTest` repro FIRST — constructing it against the live
container's wiring is what reveals the root cause — then fix, offline. One live
round-trip verification happens after the fix lands (outside this ticket's CI
scope; see HANDOFF). Falsified-already hypotheses (do not re-chase): config
expansion of `infochat.adapters.simplex.admin-token` (env present in the
container, token is clean letters); injected-bean init (`RegisteredContactSet`
is `@Startup` and boot succeeded; `AuditLogWriter`/`DataSource` fine).

This ticket started as a skeleton `blocked_by: M1-542` (the D37 stack logger
dropped the cause chain, masking the root cause; M1-542 fixed that). Refined
2026-07-02 via the bounded self-refine arm of `/m1-tick run` after a
clarity-fail — see `revisions:` for the pre-refine snapshot.

## Acceptance

The YAML `acceptance` above is binding: (1) an inbound DM routes normally
(admin-claim check, then invite fall-through) instead of throwing at
`SimpleXAdminClaim` ARC create and being dropped, (2) a `@QuarkusTest`
reproduces the live-only crash (red before the fix, green after), (3)
`mvn verify` green.

## Out-of-scope

See the YAML `out_of_scope` above. In short: SimpleX inbound intake only — no
Signal, no collector, no migrations, no re-touching M1-542's stack logger, and
no weakening of any D50 claim-token property.

## Notes

- Repro (needs the live stack up per HANDOFF): send an admin-client DM to the
  bot and grep the Provider log for `inbound handler threw`.
- The fix must not regress the D50 claim-token security properties (single-use
  gate, constant-time compare, no token-validity oracle, audit-before-effect).
- `security_relevant: true` (flipped on refine per the pre-refine Notes): the
  crash sits inside the D50 admin-claim path, so the fix diff gets the redteam
  gate before commit.
