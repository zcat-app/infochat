---
id: M1-449
title: "Make scheduler/pruner decision time injectable (PartitionPruner, DigestRetryService, FetchScheduler)"
status: pending
created: 2026-06-25
last_updated: 2026-06-25
blocked_by: []
files_budget: 10
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "The security-timing trio (M1-447), ReEvaluationJob (M1-444), and the 5 partition-scan workers (M1-448) — already converted or in their own ticket. Do not re-touch."
  - "ProbationCheck (M1-450) — its own ticket. Do not convert here."
  - "(B) pure audit/record writes and DDL `DEFAULT now()` — left on the DB clock (see docs/plan/m1/now-clock-audit.md). Only the Java-side decision reads convert."
  - "Any behavioural change to a retention cutoff, retry cooldown, or fetch cadence. Determinism refactor only: behaviour byte-for-byte preserved under the real production Clock."
acceptance:
  - "PartitionPruner reads its retention cutoff from the injected `java.time.Clock` (field-injected with a `Clock.systemUTC()` initializer per the M1-444 reference; producer `ThrottledAdminNotifier.systemUtcClock()`) — `YearMonth.now(...)` / `Instant.now()` that gate which partitions drop become `clock.instant()`-derived. No inline `now()` / `Instant.now()` remains in the prune-decision path."
  - "DigestRetryService reads its retry-cooldown instant from the injected Clock — `Instant.now().isBefore(lastRetryAt.plus(cooldown))` becomes `clock.instant()`-based. No inline `Instant.now()` remains in the cooldown gate."
  - "FetchScheduler reads its per-kind tick instant from the injected Clock — `Duration.between(lastTick, now)` (the interval gate that decides which fetchers run) samples `clock.instant()`. No inline `now()` / `Instant.now()` remains in the tick-interval gate."
  - "Behaviour is byte-for-byte preserved under the real production `Clock.systemUTC()` for all three components; only the instant's source moves so it can be pinned in tests. Where a component reads back its own time-write for a decision, the write and the read move to the one Clock together (no two-clock split, M1-444 rule)."
  - "Each converted component gains a NEW deterministic test that pins the Clock via `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)` and asserts the time-gated behaviour at a fixed instant (named in test_plan.adds). All such tests are ADDITIVE — this ticket modifies the assertions of NO pre-existing test. If conversion turns out to require migrating a pre-existing wall-clock-relative test, that is surfaced via `escalate refine` (adding a test_plan.modifies entry) before implementation, not done silently."
  - "(B) pure audit/record writes and DDL `DEFAULT now()` are LEFT on the DB clock and NOT converted."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "PartitionPrunerClockIT (or unit test) — pins Clock.fixed(...) and asserts which partitions are selected for drop against the injected instant deterministically."
    - "DigestRetryServiceClockIT — pins Clock.fixed(...) and asserts the retry-cooldown gate (lastRetryAt + cooldown vs the injected now) deterministically."
    - "FetchSchedulerClockIT (or unit test) — pins Clock.fixed(...) and asserts the per-kind tick-interval gate decides against the injected instant deterministically."
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

# M1-449: Make scheduler/pruner decision time injectable

## Context

Follow-up to M1-447, which classified the whole production current-time surface
in `docs/plan/m1/now-clock-audit.md` and deferred 8 (A) decision-logic
components. This ticket converts the three **scheduler/pruner** components whose
decision reads are Java-side (not in-SQL): `PartitionPruner` (retention cutoff),
`DigestRetryService` (retry cooldown), and `FetchScheduler` (per-kind tick
interval). Each gates behaviour on ambient `Instant.now()` / `YearMonth.now()`
today, so its time-dependent behaviour cannot be pinned deterministically in a
test — the same time-bomb class M1-398 / M1-400 / M1-444 each fixed one instance
of. Moving each onto the injected `Clock` (M1-444 pattern) makes the gate
testable with a fixed instant.

## Acceptance

See the YAML `acceptance:` list. In short: PartitionPruner's retention cutoff,
DigestRetryService's retry cooldown, and FetchScheduler's tick interval each read
`clock.instant()` from the injected Clock; behaviour byte-for-byte preserved
under `Clock.systemUTC()`; each gains a new fixed-Clock test (additive — no
pre-existing test modified, with a refine path if one turns out to need it); (B)
audit writes stay on the DB clock; full suite green.

## Out-of-scope

See the YAML `out_of_scope:` list. The trio (M1-447), ReEvaluationJob (M1-444),
the scan workers (M1-448), and ProbationCheck (M1-450) are separate. (B) audit
writes and DDL defaults stay on the DB clock. No retention/cooldown/cadence size
changes.

## Notes

- Reference: M1-444 (`ReEvaluationJob`). Producer: `ThrottledAdminNotifier.systemUtcClock()`.
- `DigestRetryService` reads back a time it (or the digest pipeline) wrote
  (`lastRetryAt`) to gate the cooldown — apply the M1-444 "no two-clock split"
  rule: if a value the component writes is read back for this decision, move both
  onto the one Clock. The plan-writer at `start` should confirm the read/write
  ownership before converting.
