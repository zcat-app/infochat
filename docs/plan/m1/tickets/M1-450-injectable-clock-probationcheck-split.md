---
id: M1-450
title: "Move ProbationCheck onto the injected Clock (close the probation_until app/DB split)"
status: pending
created: 2026-06-25
last_updated: 2026-06-25
blocked_by: []
files_budget: 6
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "InviteCodeConsumer and GroupAutoPromoteService — already converted by M1-447 (InviteCodeConsumer writes probation_until from the app Clock; GroupAutoPromoteService reads it from the app Clock). Do not re-touch them; this ticket converts only ProbationCheck's read."
  - "The other deferred (A) components (M1-448 scan workers, M1-449 schedulers/pruners) — separate tickets."
  - "(B) pure audit/record writes and DDL `DEFAULT now()` — left on the DB clock."
  - "Any behavioural change to the probation window duration. Determinism refactor only: behaviour byte-for-byte preserved under the real production Clock."
acceptance:
  - "ProbationCheck reads the current instant for its probation-expiry gate from the injected `java.time.Clock` (the app-wide producer `ThrottledAdminNotifier.systemUtcClock()`; test seam `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)`) instead of the SQL `probation_until > NOW()` DB-clock comparison — closing the app-write/DB-read split M1-447 documented (InviteCodeConsumer writes `probation_until` from the app Clock; this read now uses the same Clock). The gate binds a Java instant sampled from `clock.instant()` rather than comparing against in-SQL `NOW()`."
  - "Behaviour is byte-for-byte preserved under the real production `Clock.systemUTC()` — the probation gate admits/blocks exactly the same users it does today; only the instant's source moves from the DB clock to the injected Clock so the write and the read share one clock and the gate is pinnable in tests."
  - "ProbationCheck gains a NEW deterministic test that pins the Clock and asserts the probation-expiry gate decides against the injected instant (named in test_plan.adds), with a discriminating case (a probation_until that is in the future under the pinned clock but in the past on the wall clock, or vice versa) that would fail if the read still used SQL NOW(). The test is ADDITIVE — no pre-existing test's assertions are modified; if conversion requires migrating a pre-existing wall-clock-relative test, that is surfaced via `escalate refine` first."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "ProbationCheckClockIT — pins Clock.fixed(PINNED_NOW) and asserts the probation-expiry gate decides against the injected instant; the discriminating case uses a probation_until whose verdict differs between the pinned clock and the wall clock, proving the read no longer uses SQL NOW()."
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-450: Move ProbationCheck onto the injected Clock (close the probation_until app/DB split)

## Context

M1-447 converted `InviteCodeConsumer` (which WRITES `probation_until` from the
app `Clock`) and `GroupAutoPromoteService` (which READS it from the app `Clock`),
but deliberately left `ProbationCheck` — a second reader of `probation_until`
that still compares against SQL `NOW()` on the DB clock — out of scope to keep
that diff small. The M1-447 audit (`docs/plan/m1/now-clock-audit.md`) records the
result as an **app-write / DB-read authorship split**: the value is written by
the app Clock but one reader still gates on the DB clock. This is byte-for-byte
safe in production only because `Clock.systemUTC()` ≈ DB `now()`. This ticket
closes the split by moving `ProbationCheck`'s read onto the same injected Clock,
so the probation value is written and read under one clock and the gate becomes
deterministically testable.

## Acceptance

See the YAML `acceptance:` list. In short: ProbationCheck's probation-expiry gate
reads `clock.instant()` from the injected Clock instead of SQL `probation_until >
NOW()`; behaviour byte-for-byte preserved under `Clock.systemUTC()`; a new
fixed-Clock test with a discriminating case (verdict differs between pinned and
wall clock); full suite green.

## Out-of-scope

See the YAML `out_of_scope:` list. The M1-447 components (InviteCodeConsumer,
GroupAutoPromoteService) are already done — only ProbationCheck's read converts
here. The other deferred (A) components are separate tickets. No probation
duration change.

## Notes

- `security_relevant: true` — probation gating is part of the slow-start access
  control surface (D45); the threat-actor review (`/redteam`) is appropriate
  after APPROVE, exactly as it was for M1-447.
- Reference: M1-444 (`ReEvaluationJob`) and the M1-447 trio. Producer:
  `ThrottledAdminNotifier.systemUtcClock()`.
- The plan-writer at `start` should confirm whether ProbationCheck's read is a
  standalone SQL query (bind a Java instant) or shares a statement with a write
  (in which case the M1-444 "no two-clock split" whole-component rule applies).
