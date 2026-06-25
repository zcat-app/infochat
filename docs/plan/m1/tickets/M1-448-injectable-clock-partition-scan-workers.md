---
id: M1-448
title: "Make partition-scan worker time injectable (5 scan-window workers)"
status: pending
created: 2026-06-25
last_updated: 2026-06-25
blocked_by: []
files_budget: 14
complexity: high
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - "The security-timing trio (InviteCodeConsumer, GroupAutoPromoteService, AdminReviewTtlJob) — already converted by M1-447. Do not re-touch."
  - "ReEvaluationJob — already converted by M1-444 (the reference). Do not re-touch."
  - "The other deferred (A) components in their own follow-up tickets: PartitionPruner / DigestRetryService / FetchScheduler (M1-449) and ProbationCheck (M1-450). Do not convert them here."
  - "(B) pure audit/record writes left on the DB clock — e.g. ReadyPromoter's `ready_at = now()` / `status_changed_at = now()` stamps and any DDL `DEFAULT now()`. Only the scan-window SELECT cutoffs convert; the writes stay on the DB clock (see docs/plan/m1/now-clock-audit.md)."
  - "Any behavioural change to a scan-window size / slack / cadence. Determinism refactor only: with the real production Clock behaviour is byte-for-byte preserved. Changing PartitionScan.PARTITION_SCAN_SLACK or any window is a separate ticket."
acceptance:
  - "Each of the five partition-scan workers — EmbeddingWorker, EntityExtractorWorker, TaggerWorker, ReadyPromoter, PerSourceUnknownTracker — samples its scan-window instant from the injected `java.time.Clock` (the app-wide `@Produces @ApplicationScoped Clock` in `ThrottledAdminNotifier.systemUtcClock()`; field-injected with a `Clock.systemUTC()` initializer per the M1-444 reference) and binds a Java-computed cutoff (`clock.instant().minus(window)`, converted to the column's bind type) into the pickup query, instead of the in-SQL `now() - ?::INTERVAL`. No inline `now()` / `Instant.now()` remains in the scan-window decision path of these five workers."
  - "Behaviour is byte-for-byte preserved under the real production `Clock.systemUTC()` — the scan window selects exactly the same rows it does today; only the instant's source moves from the DB clock to the injected Clock so it can be pinned in tests. Where the shared `PartitionScan` helper computes the window, the injected instant flows through it without changing its window math."
  - "EmbeddingWorkerIT and EntityExtractorWorkerIT (the M1-398 / M1-400 de-rots, which today seed `fetched_at` relative to the wall clock) are migrated to pin the Clock via `QuarkusMock.installMockForType(Clock.fixed(...), Clock.class)` and assert the scan-window boundary against the fixed instant — replacing the wall-clock-relative fixtures so they can never age out again. These are the two declared test modifications (see test_plan.modifies)."
  - "TaggerWorker, ReadyPromoter, and PerSourceUnknownTracker each gain a NEW fixed-Clock test that pins the Clock and asserts the scan-window boundary deterministically (named in test_plan.adds)."
  - "(B) pure audit/record writes (e.g. ReadyPromoter's `ready_at` / `status_changed_at`) and DDL `DEFAULT now()` are LEFT on the DB clock and NOT converted."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - "TaggerWorkerClockIT — pins Clock.fixed(...) and asserts the tagger pickup scan window selects/excludes rows against the injected instant."
    - "ReadyPromoterClockIT — pins Clock.fixed(...) and asserts the ready-promotion pickup scan window decides against the injected instant (the ready_at audit write stays on the DB clock)."
    - "PerSourceUnknownTrackerClockIT — pins Clock.fixed(...) and asserts the per-source UNKNOWN-rate scan window decides against the injected instant."
  modifies:
    - "EmbeddingWorkerIT — replace its wall-clock-relative fetched_at fixture with a fixed Clock.fixed(PINNED_NOW) pin and a fixed fetched_at straddling PINNED_NOW − (retention + slack); new expected behaviour: the scan-window boundary is asserted deterministically against the injected instant (it currently depends on the real run date — the M1-398 time-bomb)."
    - "EntityExtractorWorkerIT — same migration as EmbeddingWorkerIT: replace the wall-clock-relative fixture (M1-400) with a fixed Clock pin and assert the scan-window boundary against the injected instant."
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

# M1-448: Make partition-scan worker time injectable (5 scan-window workers)

## Context

M1-447 converted the security-timing decision-logic trio onto the injectable
`Clock` pattern (M1-444 reference) and classified the whole production
current-time surface in `docs/plan/m1/now-clock-audit.md`. That audit found 11
unconverted (A) decision-logic components and deferred 8 of them to follow-up
tickets to keep each diff small and reviewable. This ticket converts the **five
partition-scan workers** — the most uniform of the deferred set: each picks up
rows inside a scan window expressed today as SQL `now() - ?::INTERVAL`, which the
test cannot pin without a wall-clock-relative fixture. Two of them
(`EmbeddingWorker`, `EntityExtractorWorker`) already carry the exact time-bomb
this whole effort exists to kill — their ITs (M1-398, M1-400) were de-rotted
one-off with wall-clock-relative fixtures that can age out again. Converting the
production scan window to a Java-bound cutoff from the injected Clock lets those
ITs pin a fixed instant permanently.

## Acceptance

See the YAML `acceptance:` list. In short: the five scan workers bind a
Java-computed scan-window cutoff from the injected `Clock` (M1-444 pattern)
instead of in-SQL `now() - interval`; behaviour byte-for-byte preserved under
`Clock.systemUTC()`; the two existing de-rot ITs are migrated to a fixed Clock
(declared modifications) and the other three workers gain new fixed-Clock tests;
(B) audit writes stay on the DB clock; full suite green.

## Out-of-scope

See the YAML `out_of_scope:` list. The security trio (M1-447) and `ReEvaluationJob`
(M1-444) are already done. The remaining deferred (A) components —
`PartitionPruner` / `DigestRetryService` / `FetchScheduler` (M1-449) and
`ProbationCheck` (M1-450) — are separate tickets. (B) audit writes and DDL
defaults stay on the DB clock. No scan-window size or slack changes.

## Out-of-scope test modifications

This ticket DOES modify two pre-existing tests — `EmbeddingWorkerIT` and
`EntityExtractorWorkerIT` — declared in `test_plan.modifies` with their new
expected behaviour (pin a fixed Clock; assert the scan boundary against the
injected instant). All other pre-existing tests must stay green UNMODIFIED under
the default `systemUTC()` Clock.

## Notes

- Reference: M1-444 (`ReEvaluationJob` + `ReEvaluationJobScheduledPathIT`) — the
  canonical SQL-`now()`→Java-bound-cutoff conversion. The producer is
  `ThrottledAdminNotifier.systemUtcClock()`.
- The shared `PartitionScan` helper (used by several of these workers to widen
  the window by `PARTITION_SCAN_SLACK`) computes a window string today; threading
  the injected instant through it is expected. Do NOT change the slack value.
- Expect the plan-writer at `start` to confirm the exact per-worker SQL sites and
  whether any worker shares a query builder that needs a single coordinated edit.
