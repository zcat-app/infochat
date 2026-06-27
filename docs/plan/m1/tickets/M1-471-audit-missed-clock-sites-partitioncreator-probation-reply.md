---
id: M1-471
title: "Move two audit-missed now() sites onto the Clock: PartitionCreator gates + probation-reply formatter"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/partition/PartitionCreator.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/partition/PartitionCreatorTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationClockTest.java
  - docs/plan/m1/now-clock-audit.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # Pure audit/record writes stay on the DB/system clock (engineering-rules
  # §9 exemption B): created_at/updated_at/status_changed_at and DDL
  # DEFAULT now(). No behaviour change to any window/threshold/cadence — this
  # is a determinism/testability refactor; under the real production Clock
  # (Clock.systemUTC()) behaviour is byte-for-byte preserved.
  - infochat-collector/src/main/java/app/zcat/infochat/collector/partition/PartitionPruner.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/ProbationCheck.java
acceptance:
  - >-
    PartitionCreator reads its current instant/month from the app-wide
    injected java.time.Clock (the @Produces Clock in
    ThrottledAdminNotifier.systemUtcClock()), not ambient Instant.now() /
    YearMonth.now(). Both the which-months-to-provision decision
    (PartitionDdl.monthsToProvision(YearMonth.now(ZoneOffset.UTC))) and the
    liveness-WARN gate (Duration.between(lastSuccessfulRun, Instant.now())
    vs LIVENESS_THRESHOLD) read from the Clock; lastSuccessfulRun is both
    written and read back against that SAME Clock (no app-vs-DB two-clock
    split — engineering-rules §9). Behaviour under Clock.systemUTC() is
    unchanged.
  - >-
    PartitionCreatorTest gains a deterministic case that pins the Clock via
    QuarkusMock.installMockForType(Clock.fixed(...), Clock.class) and asserts
    the liveness WARN fires exactly when fixed-now minus lastSuccessfulRun
    crosses LIVENESS_THRESHOLD (previously un-pinnable because the gate read
    wall-clock now()).
  - >-
    InboundRouter.formatTimeUntilUnlock takes the already-sampled gate instant
    (probationNow = clock.instant(), InboundRouter.java:679) as a parameter
    instead of re-reading Instant.now() (InboundRouter.java:1119), so the
    probation-blocked reply's remaining-time token is computed against the
    same instant as the gate decision and is pinnable in tests. The helper
    stays static and pure. This is a display-consistency / testability fix
    (engineering-rules §9 taxonomy category C, display) layered on top of the
    already-correctly-clocked gate decision — not a security-gate change.
  - >-
    InboundRouterProbationClockTest asserts that, with the Clock pinned, the
    blocked reply renders a deterministic remaining-time token (e.g. ~Nh /
    ~Nm / <1m) derived from the pinned instant rather than wall-clock now().
  - >-
    docs/plan/m1/now-clock-audit.md is corrected: PartitionCreator is added as
    an (A) decision-logic site (the audit currently lists only PartitionPruner,
    row 9) and both PartitionCreator and the InboundRouter probation-reply
    formatter are marked CONVERTED with this ticket id.
  - mvn -B verify is green from the repo root.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/partition/PartitionCreatorTest.java
      — pins Clock.fixed(...) and asserts the liveness-WARN gate fires at the
      LIVENESS_THRESHOLD boundary against the injected instant.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterProbationClockTest.java
      — pins Clock.fixed(...) and asserts the probation-blocked reply's
      remaining-time token is computed from the injected instant.
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-471: Move two audit-missed now() sites onto the Clock

## Context

The M1-447 injectable-Clock audit (`docs/plan/m1/now-clock-audit.md`)
classified every production current-time site, and follow-ups M1-448/449/450/
451/452/454/460 converted the (A) decision-logic sites one coherent group at a
time. The `/deep-code-review full` run (2026-06-27) surfaced two sites the
audit **missed**:

1. **`PartitionCreator` (collector) — (A) decision-logic, audit-missed.**
   `PartitionCreator.java` reads ambient time at four points: seeding
   `lastSuccessfulRun = Instant.now()` (line 58), the liveness-WARN gate
   `Duration.between(lastSuccessfulRun, Instant.now())` vs `LIVENESS_THRESHOLD`
   (line 68), the which-months-to-provision decision
   `YearMonth.now(ZoneOffset.UTC)` (line 77), and advancing `lastSuccessfulRun
   = Instant.now()` on success (line 82). The audit lists its sibling
   `PartitionPruner` (row 9) — since converted by M1-449 — but **not**
   `PartitionCreator`, leaving an in-package asymmetry and an un-pinnable
   liveness threshold. This matches the "audit-missed §9 site" follow-up
   pattern (M1-451/452/454/460).

2. **`InboundRouter.formatTimeUntilUnlock` (provider) — (C) display, not a
   gate.** The probation gate already samples `probationNow = clock.instant()`
   (`InboundRouter.java:679`) from the injected Clock, but the reply formatter
   re-reads `Instant.now()` (`InboundRouter.java:1119`) to render the
   remaining-time token on the *same* reply. The gate **decision** is
   correctly clocked; only the rendered string bypasses the clock. Impact is
   display-only and engineering-rules §9 classifies display as category (C),
   so this is a consistency/testability cleanup — not a §9 violation — but the
   gate already holds the right instant, so threading it in is strictly
   better and pins the rendered token under `InboundRouterProbationClockTest`.

These are grouped because both are "thread the injected/already-sampled Clock
instant through an audit-missed inline `now()`," both make a previously
wall-clock-dependent assertion pinnable, and both are byte-for-byte behaviour-
preserving under `Clock.systemUTC()`. (The deep review flagged them as the
single cross-cutting theme CT1.)

Source: `/deep-code-review full` (2026-06-27), collector report F1 +
provider report F1.

## Acceptance

See frontmatter. PartitionCreator moves wholesale onto the injected Clock
(write and read of `lastSuccessfulRun` on the one clock — no two-clock split);
`formatTimeUntilUnlock` takes the gate's `probationNow` as a parameter; both
gain a Clock-pinned deterministic test; the now-clock audit doc is corrected;
full suite green.

## Out-of-scope

See frontmatter. `PartitionPruner` and `ProbationCheck` are already on the
Clock (M1-449 / M1-450) — do not re-touch them. No window/threshold/cadence
value changes. Pure audit/record timestamps stay on the DB clock.

## Notes

- The §9 "never split one component across two clocks" rule is why
  `lastSuccessfulRun` must be both written (seed + on-success) and read (the
  liveness gate) against the injected Clock — converting only the read would
  introduce an app-vs-DB skew bug.
- `PartitionCreator` injects the Clock the same way `PartitionPruner` does
  (field `Clock clock = Clock.systemUTC();` overridable by QuarkusMock); copy
  that seam for consistency with the sibling.
- The `formatTimeUntilUnlock` change keeps the helper `static` so its existing
  unit coverage stays bean-free; only its signature gains the `Instant now`
  parameter, supplied at the single call site (`InboundRouter.java:685`).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-471-*.md
```
