---
name: scan-window-fixture-timebombs
description: "Scan-window fixture time-bombs CLOSED by M1-602 (133272de): census found only 3 real bombs (all vacuous-negatives), pinned; ScanWindowFixtureGuardTest now blocks new ones"
metadata: 
  type: project
---

A whole CLASS of collector tests seeded an **absolute** `fetched_at` (e.g.
`Instant.parse("2026-06-07T10:00:00Z")`) without pinning the injected `Clock`,
so eval workers ran on `Clock.systemUTC()` and the post aged out of the
`fetched_at >= now − 32d` pickup window on `seed + 32d` — a calendar-boundary
failure (or silently-vacuous assertion) with zero code change.

**CLOSED 2026-07-10 by M1-602 (`133272de`).** Census at
`docs/plan/m1/scan-window-fixture-census.md` traced all 65 `Instant.parse`
collector test files (+2 SQL-literal-only) seed→gate. Key learnings:

- **The feared ~40-bomb backlog was actually 3 bombs — and ALL were
  vacuous-negatives, not red failures**: EmbeddingWorkerDimensionMismatchTest
  (onTick containment path dead since 2026-06-17), TaggerWorkerIT 27.7
  (quarantine-filter exclusion, 2026-06-16), ReadyPromoterIT Orders 2+3
  (rollback atomicity + quarantine filter, 2026-06-17). The suite stayed GREEN
  while silently not testing those contracts — grep proximity of a seed to
  `fetched_at` proves nothing; most seeds feed exact-key direct-call paths.
- **ReadyPromoterIT trap**: the file grepped as "pinned" (Order-8 method-local
  pin from M1-597) while two other methods hit the windowed query unpinned. A
  file-granular guard can't catch method-level gaps — documented limitation.
- **Guard**: `collector/testsupport/ScanWindowFixtureGuardTest` (surefire,
  M1-495 shape) fails the build on any new unpinned `Instant.parse("20NN-`
  test source outside its 51-FQCN census-verified benign baseline. SQL-literal
  seeds (`'20NN-...'`) evade it — census addendum documents the residual risk.
- **If a collector IT goes red on a calendar boundary anyway**: check the
  census first; the date-robust no-pin pattern (relative `Instant.now()`
  in-window seed + permanently-below-floor absolute + loud fixture self-guard,
  e.g. EmbeddingWorkerPickupFloorIT) is the sanctioned alternative to pinning.

Fix pattern stays: pin the app-wide `Clock` via
`QuarkusMock.installMockForType(Clock.fixed(seed+1h, UTC), Clock.class)` in
`@BeforeEach` (M1-444/M1-601; never inline `Instant.now()` — engineering-rules
§9). History: fired live 2026-07-09 as ReEvalVerdictNotifyIT → M1-601
(`2b131514`); M1-447 had only converted production sites additively, so the
test-fixture backlog survived until M1-602. See [[m1-tick-workflow-cannot-nest-gates]].
