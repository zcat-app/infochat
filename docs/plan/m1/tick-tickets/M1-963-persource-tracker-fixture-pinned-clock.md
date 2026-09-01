---
id: M1-963
title: "Pin tracker test seeds to the injected Clock"
status: done
created: 2026-09-01
last_updated: 2026-09-01
flow: tick
reproduction: >-
  app.zcat.infochat.collector.eval.reeval.PerSourceUnknownTrackerTest.oldPartitionPost_excludedFromRate_sourceNotDisabled
  — verified RED at analysis time (2026-09-01) via
  `./mvnw -pl infochat-collector test -Dtest=PerSourceUnknownTrackerTest
  -Dsurefire.failIfNoSpecifiedTests=false`:
  `oldPartitionPost_excludedFromRate_sourceNotDisabled:102->seedStage2Post:175
  » PSQL ERROR: no partition of relation "post" found for row`,
  `Tests run: 4, Failures: 0, Errors: 1` (evidence:
  .scratch/analyst-M1-962-repro.log). The seed
  `Instant.now().minus(Duration.ofHours(50))` (PerSourceUnknownTrackerTest.java:102)
  lands in month(now)−1, which nothing provisions: migrations provision only
  202605/202606/202607 (V7:175, V30:20-42) and the collector test boot's
  PartitionCreator provisions active+next only (PartitionDdl.java:97-99).
  Fails during the first 50h of every month from 2026-09 onward; the three
  sibling tests (seeds now−60s…240s) fail the first 4 minutes of every month.
analysis_ref: docs/plan/m1/tick-analysis/partition-month-boundary-test-timebomb.md
blocked_by: []
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/AutoDisableStopBeforeNotifyIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerUpgradeIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/FirstPassStage2RowBenignCloseIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage2/Stage2FirstPassQuarantineRowIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/EvalQueueOverflowIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1BodyTextIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1BodyRemediationIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any production change. PerSourceUnknownTracker already reads the
    injected Clock (`@Inject Clock clock = Clock.systemUTC()`,
    PerSourceUnknownTracker.java:64-65; floors sampled at :131-135) — the
    seam exists; only fixture timestamp SOURCING changes. PartitionCreator /
    PartitionDdl / PartitionPruner untouched (deliberate design; analysis
    P11).
  - >-
    New migrations or a DEFAULT partition (Invariant 6 forbids one). Pins go
    inside the migration-provisioned bootstrap months May–July 2026, which
    every test DB carries forever (analysis P3).
  - >-
    The lint script, its roots/enforcement (M1-962), and its trace
    strengthening (M1-964); the other ambient collector fixture sites
    enumerated in the analysis census (M1-962/M1-964 disposal sets).
  - >-
    Any assertion change in the three files: verdict logic, ordering
    expectations, notification coalescing-key assertions, the D42 park-upgrade
    guard assertions, and the U-03 stop-before-notify ORDER assertion are
    byte-identical; only timestamp sourcing moves (§8 authorization in
    test_plan.modifies).
  - >-
    PerSourceUnknownTrackerClockIT (already the pinned pattern this ticket
    replicates) and Stage1Worker's SQL-now() stale-RAW sweep (pre-existing
    §9 migration backlog per M1-447, not this class).
acceptance:
  - "REPRODUCTION closed: `./mvnw -pl infochat-collector test -Dtest=PerSourceUnknownTrackerTest -Dsurefire.failIfNoSpecifiedTests=false` is green 4/4 with deterministic seeds, and `mvn verify` from the repo root passes with all eight touched classes green."
  - "NO WALL CLOCK left in the family: `grep -n \"Instant.now()\"` over the eight touched files returns NOTHING, and no `now()` remains in any of their seed SQL — status_changed_at becomes a bound, pin-relative parameter where the tracker family seeds it (analysis P2; engineering-rules §9 no-two-clock)."
  - "FAILURE-MODE (discrimination preserved): in oldPartitionPost_excludedFromRate_sourceNotDisabled the old post's pinned fetched_at stays BELOW the tracker's fetched floor — derived from the injected `infochat.reeval.unknown-rate-window` + `PartitionScan.PARTITION_SCAN_SLACK` per PerSourceUnknownTrackerClockIT.java:79-87, NOT hardcoded 49h/50h literals — while its pinned status_changed_at stays above the status floor, and `assertSourceStatus(sourceId, \"active\")` still fails the test if the production `p.fetched_at >= ?` bound were dropped (the old post would count, the source would auto-disable). After pinning, the INSERT no longer explodes on a partition error, so THIS assertion is the only remaining guard and must keep discriminating."
  - "PIN HYGIENE: `grep -n 'Instant.parse(\"2026-0'` over the eight files shows ONLY 2026-05/06/07 bootstrap-month constants, and no 2026-08 remains in executable code — the only grep survivors are historical citations in comments (the redteam ID M1-739-2026-08-01 and a census verification date), stable pointers that stay verbatim (analysis P3 — pins outside the migration-provisioned months re-arm the bomb forward)."
  - "NOTIFIER CONTROLS untouched: the two-sources test's per-source coalescing assertions (`throttledAdminNotifier.getState(...)`, `assertEquals(0L, stateA.get().suppressedCount(), ...)`) pass unmocked; AutoDisableStopBeforeNotifyIT's RecordingNotifier ORDER assertion (stop signal before admin notify) passes unchanged."
  - "AUGUST-PIN DISPOSAL (refine 2026-09-01, user-approved): the five fixed-August partition-key pins that went red at the Sept-1 calendar flip — FirstPassStage2RowBenignCloseIT (:39), Stage2FirstPassQuarantineRowIT (:48), EvalQueueOverflowIT (:285 SQL literal), Stage1BodyTextIT (:95), Stage1BodyRemediationIT (:55); pristine-main evidence .scratch/m1-963-probe-main-august.log — move inside May–July 2026, each file's straddle/window intent preserved by reading its production seam first (plain constant shift where nothing time-bounds the row; Clock pin + pin-relative seeds where the code under test reads the injected Clock); all five classes green with assertions unchanged."
  - "`mvn verify` from the repo root is green."
test_plan:
  adds: []
  modifies:
    - >-
      PerSourceUnknownTrackerTest (AUTHORIZED: pin Clock.fixed(PINNED_NOW)
      in @BeforeEach via QuarkusMock.installMockForType — the
      PerSourceUnknownTrackerClockIT.java:69-75 seam; PINNED_NOW =
      2026-06-20T12:00:00Z; every test method's seeds become
      pin-relative fixed instants; seedStage2Post gains a statusChangedAt
      parameter so status_changed_at is bound pin-relative instead of SQL
      now(); old-post seed = PINNED_NOW − 50h for fetched_at with a RECENT
      pinned status_changed_at, preserving the documented two-floor
      discrimination). No assertion changes.
    - >-
      AutoDisableStopBeforeNotifyIT (AUTHORIZED: same pin added to the
      existing @BeforeEach alongside the RecordingNotifier install; the
      three seeds become PINNED_NOW − 60s/120s/180s; the
      `?, 'QUARANTINED', now(),` seed SQL (:113) binds status_changed_at
      pin-relative). ORDER assertion and all others unchanged.
    - >-
      PerSourceUnknownTrackerUpgradeIT (AUTHORIZED: same pin; parked_at =
      (PINNED_NOW − 2h).truncatedTo(MILLIS) with the parked_at equality
      assertion unchanged; the three post seeds pin-relative; the seed SQL
      `now()` (:157) becomes a bound pin-relative parameter;
      setNextReprobeAt/selectDueReprobes args become pin-relative
      constants). All D42 upgrade-guard assertions unchanged.
    - >-
      FirstPassStage2RowBenignCloseIT (AUTHORIZED by refine 2026-09-01:
      FETCHED_AT (:39) moves 2026-08-01T11:00:00Z → 2026-06-01T11:00:00Z —
      plain bootstrap-month shift; the value is a pass-through partition
      key (post seed, Stage2VerdictHandler.apply arg, hand-built
      ReEvalCandidate) with no time-window logic reading it; assertions
      unchanged).
    - >-
      Stage2FirstPassQuarantineRowIT (AUTHORIZED by refine 2026-09-01:
      FETCHED_AT (:48) → 2026-06-01T10:00:00Z; same pass-through shape
      including the quarantine.post_fetched_at denormalized copy and the
      id+fetched_at partition-pruning lookup; assertions unchanged).
    - >-
      EvalQueueOverflowIT (AUTHORIZED by refine 2026-09-01: the
      bulkInsertRawPosts SQL literal (:285) TIMESTAMPTZ '2026-08-01
      00:00:00+00' → '2026-06-01 00:00:00+00'; the per-row millisecond
      spread follows; fetched_at rides the PersistedPostKey opaquely;
      assertions unchanged).
    - >-
      Stage1BodyTextIT (AUTHORIZED by refine 2026-09-01: SEED_FETCHED_AT
      (:95) 2026-08-06T13:00:00Z → 2026-06-06T13:00:00Z; the existing
      @BeforeEach Clock pin derives from the constant and follows; exact-key
      process() calls; assertions unchanged).
    - >-
      Stage1BodyRemediationIT (AUTHORIZED by refine 2026-09-01:
      SEED_FETCHED_AT (:55) 2026-08-07T13:00:00Z →
      2026-06-07T13:00:00Z; same — Clock pin follows; the job gates on the
      marker column, not a time window; assertions unchanged).
  preserves:
    - >-
      PerSourceUnknownTrackerClockIT (the seam proof), the tracker's
      verdict/threshold semantics, the throttled-admin per-source coalescing
      assertions, and every other test currently green on main.
spec_refs: []
decision_refs:
  - D72
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-09-01
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS — 3 falsification candidates dropped with citations (ClockIT-seem pointer-form concern defeated by §11 itself: the parenthetical names the same-package reference implementation as current truth, no stale-able historical claim; the Stage2FirstPass :190 `updated_at = now()` concern defeated by §9's pure-audit-write exemption and the line being untouched by this diff; the two-clock-skew concern defeated by PerSourceUnknownTracker.java:131-139 binding both floors from ONE injected-Clock sample and the unchanged parked_at equality assertion proving COALESCE(parked_at, now()) non-participating). Verdict: .scratch/tick-review-M1-963-r1.txt"
    diff_stats: "10 files, +185/-57 (3 tracker-family fixtures Clock-pinned with pin-relative two-column seeds and config-derived floors; 5 fixed-August partition-key constants shifted into June 2026; ticket frontmatter incl. the refine records; STATUS-TICK regen)"
    notes: >-
      0 rework items, 0 critical/high. Reviewer re-verified the round
      identity (snapshot be27206c tree == staged tree), re-ran the
      acceptance greps against the final tree, re-checked the
      discrimination arithmetic against the real test-profile config
      (threshold 0.5 / window PT1H / min-sample 3 / slack 2d), confirmed
      all §10 controls byte-identical and §8 authorization complete for
      all 8 files, confirmed the refine provenance (red-august log: 29
      partition errors across exactly the five absorbed files; pristine-
      main probe: identical signature), and re-checked the migration
      horizon (nothing provisioned past July 2026 — the June pins cannot
      re-arm). One RECOMMENDED-NEW-TICKET observation recorded under
      Review observations below.
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-09-01
  result: >-
    Self-check clean, no blocking question. Lint 0 BLOCKERs (1 WARN: empty
    spec_refs — legal for a defect ticket whose contract is its
    reproduction). All load-bearing file:line citations verified exact
    (Test :102/:163, AutoDisable :113, Upgrade :157, ClockIT
    :52/:69-75/:79-87, tracker :64-65/:131-135, PartitionDdl :97-99); two
    seed-range citations drift 1-3 lines but the behavior claims hold.
    Census grep re-runs clean (all three returned paths in files_scope);
    the "10 sites" label miscounts its own correct ranges (14 Test-class
    call sites) — enumeration complete, binding check is the acceptance
    grep. Analysis pitfall slice P1/P2/P3/P10/P11/P12 all landed.
escalation_reason:
---

# M1-963: Pin tracker test seeds to the injected Clock

## Context

Repo-root `mvn verify` is red TODAY (2026-09-01) at
`PerSourceUnknownTrackerTest.oldPartitionPost_excludedFromRate_sourceNotDisabled`
with `no partition of relation "post" found for row` — re-verified live at
analysis time (`.scratch/analyst-M1-962-repro.log`). The fixture seeds
`post.fetched_at = Instant.now() − 50h` (PerSourceUnknownTrackerTest.java:102);
migrations provision partitions only for May–July 2026 (V7/V11/V17/V28/V29 +
V30) and the collector test boot provisions active+next only
(PartitionDdl.java:97-99), so `now − 50h` lands in an unprovisioned trailing
month during the first 50 hours of EVERY month from September 2026 on. The
three sibling tests seed `now − 60s…240s` (first-4-minutes window), and two
same-shape files — `AutoDisableStopBeforeNotifyIT.java:73-75` and
`PerSourceUnknownTrackerUpgradeIT.java:91-93` — seed `now − 60s/120s/180s`
(first-3-minutes window). This is the third instance of the class (M1-479,
M1-740); the user directed the CLASS be closed once for all — this ticket is
the urgent fixture half; M1-962/M1-964 are the enforcement half. It
unblocks M1-960's full-verify gate.

## Root cause

The tracker reads the injected Clock (`PerSourceUnknownTracker.java:64-65`,
floors at `:131-135`) — the M1-448 pin seam — and
`PerSourceUnknownTrackerClockIT` already proves it (PINNED_NOW
2026-06-20T12:00:00Z, `QuarkusMock.installMockForType`, `:52`, `:69-75`).
The Test-class fixtures never adopted the pattern: they bind `fetched_at`
(and `status_changed_at` via SQL `now()`, `:163`) from ambient time, so
their partition placement floats with the calendar into months nothing
provisions. Verified: the lint cannot see this shape (helper-parameter
indirection — `scripts/lint-partitioned-test-inserts.py` docstring :48-53),
which is why the class survived M1-740.

## Pitfalls

Analysis-document numbering (partition-month-boundary-test-timebomb.md):

- P1: Half-pinning — fixing only the 50h seed (or only the Test class)
  leaves the 60–240s windows and the two sibling files armed. Fix all three
  files in this ticket; verify with the acceptance-2 grep.
- P2: Two-clock split (§9) — pinning the Clock while seeds keep SQL
  `now()` for `status_changed_at` judges a September DB stamp against a
  June app-clock floor. Bind BOTH timestamps pin-relative (the ClockIT
  seeds both).
- P3: Pins outside May–July 2026 re-arm the bomb forward. Only
  bootstrap-month constants (probe: acceptance-4 grep).
- P10: Hardcoded "49h/50h" straddle math rots when the window config
  changes — derive floors from the injected window + PARTITION_SCAN_SLACK
  (ClockIT :79-87 pattern).
- P11: No production drift — diff touches only the three test files.
- P12: §8 authorization — `test_plan.modifies` names all three files and
  the exact sourcing change; assertions untouched.

## Approach

**Files to touch** (the three `files_scope` entries; nothing else):

1. `PerSourceUnknownTrackerTest.java` — add the pin (`@BeforeEach`,
   `QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, UTC),
   Clock.class)`, PINNED_NOW = 2026-06-20T12:00:00Z); replace every
   `Instant now = Instant.now()` with the pin; extend `seedStage2Post` with
   a `statusChangedAt` parameter bound at the `status_changed_at` position
   (removing `now()` from the VALUES list); seeds: recent = PINNED_NOW −
   60s/120s/180s/240s with status_changed_at similarly recent; the
   old-partition test: fetched_at = PINNED_NOW − 50h, status_changed_at =
   PINNED_NOW − 60s (recent) — preserving exactly the discrimination the
   test's comment documents.
2. `AutoDisableStopBeforeNotifyIT.java` — add the Clock install to the
   existing `@BeforeEach`; three seeds → pin-relative; seed SQL `now()` →
   bound parameter.
3. `PerSourceUnknownTrackerUpgradeIT.java` — same pin; `parked_at` seed =
   pin − 2h truncated to MILLIS (equality assertion unchanged — disableSource's
   `COALESCE(parked_at, now())` keeps the ORIGINAL value, so the DB-clock
   write never participates); post seeds pin-relative; reprobe args
   pin-relative.

Order: 1 → 2 → 3 (largest first; identical mechanical pattern).

**Refine (2026-09-01, user-approved via /tick hurdle → refine):** the
round-1 full verify went red on five MORE census-CLEAN files holding
fixed-August partition-key pins that detonated at the Sept-1 calendar
flip (August stopped being the active/next provisioned month; pristine
main fails identically — .scratch/m1-963-probe-main-august.log). The
analysis census verified those constants were FIXED but never that they
were bootstrap-month (its own P3 rule, applied only to ambient sites).
Per-file seam read: all five are pass-through partition keys with zero
time-window logic — the Stage1 pair already pins its Clock at the
constant +1h (following it), and ScanWindowFixtureGuardTest's baseline is
unaffected (no pin added or removed). Fix: plain month shift Aug→Jun,
same day-of-month/time (see test_plan.modifies). No assertion changes.

**Controls to preserve (§10):** notification coalescing-key assertions,
min-sample exclusion semantics, D42 park-upgrade guard, U-03 ORDER
assertion — byte-identical. Tests ARE the controls here; no assertion is
weakened or retargeted (§8).

**Pitfall→mitigation:** P1 → all three files in one diff + acceptance-2
grep; P2 → seedStage2Post/seed SQL take bound status_changed_at;
P3 → acceptance-4 grep; P10 → floor math reads the config beans;
P11 → `git diff --stat` names exactly three files; P12 → this ticket's
test_plan.modifies.

## Definition of done

Mirror of `acceptance:`: the named reproduction test green 4/4 and repo-root
`mvn verify` green; zero `Instant.now()` and zero seed-SQL `now()` across
the three files; the old-partition discrimination intact against
config-derived floors; every new instant inside May–July 2026; notifier
state/ORDER assertions unmodified and passing.

## Verification

- P1 → `grep -n "Instant.now()" <three files>` returns nothing; the full
  class runs green (4/4) plus the two ITs — no half-pinned window remains.
- P2 → the diff shows no `now()` in the three seed statements (both
  timestamp columns bound pin-relative);
  PerSourceUnknownTrackerClockIT.checkAllSources_gatesBothFloorsOnInjectedClock
  stays green (both floors pinned against the same clock).
- P3 → `grep -n 'Instant.parse("2026-0' <three files>` shows only
  2026-05/06/07 constants.
- P10 → the diff derives the old-post bound from the injected
  `unknownRateWindow` + `PartitionScan.PARTITION_SCAN_SLACK` (no 49h/50h
  literals); a config change cannot silently invalidate the straddle.
- P11 → `git diff --stat` names exactly the three test files.
- P12 → this ticket's test_plan.modifies IS the §8 authorization; reviewer
  cross-checks every modified test appears there.
- Failure-mode → acceptance-3: locally drop the production `p.fetched_at
  >= ?` bound and `oldPartitionPost_excludedFromRate_sourceNotDisabled`
  must FAIL on `assertSourceStatus(sourceId, "active")` (the pinned seeds
  make the discriminator calendar-proof, so the guard is testable on any
  run date).
- acceptance-1 → the mvn probes; acceptance-6 → repo-root `mvn verify`.

## Out-of-scope

`out_of_scope` (frontmatter) carries the semantic exclusions: zero
production change (the Clock seam already exists), no migrations/DEFAULT
partition, no lint work (M1-962/M1-964), no assertion changes (§8
authorization is sourcing-only), ClockIT and Stage1Worker untouched. The
pre-existing tests modified here are exactly the three named in
`test_plan.modifies` — any other test edit is unauthorized (§8).

## Census

Class = wall-clock-derived partition-key seeds in the tracker test family.
Mechanical enumeration (re-runnable):

```
grep -rn "Instant.now()" \
  infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerTest.java \
  infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/AutoDisableStopBeforeNotifyIT.java \
  infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerUpgradeIT.java
```

10 sites in the Test class, 3 in AutoDisableStopBeforeNotifyIT, 3 in the
Upgrade IT (post seeds; `parked_at`/reprobe args ride along) — ALL disposed
here (FIX: pin-relative). The wider collector census (5 lint-visible +
4 lint-invisible ambient sites, all partition-safe-today) is disposed in
M1-962/M1-964; provider/core are clean (analysis document, Ground truth).

Refine correction (2026-09-01): the census's CLEAN bucket itself held five
fixed-AUGUST pins (FirstPassStage2RowBenignCloseIT :39,
Stage2FirstPassQuarantineRowIT :48, EvalQueueOverflowIT :285,
Stage1BodyTextIT :95, Stage1BodyRemediationIT :55) — green only while the
calendar provisioned August, red from the Sept-1 flip; the analysis P3
month-check was applied to ambient sites only. Those five files are
disposed by this ticket per the refine above. (Second-order finding for
M1-964: a fixed literal in an unprovisioned month is lint-invisible — the
strengthened trace flags ambient bindings only.)

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-963-persource-tracker-fixture-pinned-clock.md
```

## Review observations

- Round 1 (APPROVE) recommended-new-ticket entry, no decision requested:
  `PerSourceUnknownTrackerClockIT`'s class javadoc
  (PerSourceUnknownTrackerClockIT.java:30-31) still says the Test class's
  "seeds use {@code Instant.now()} / DB {@code now()}" — falsified by this
  diff (all three tracker-family files now pin the Clock). A comment-only
  fix in a file this ticket's out_of_scope deliberately keeps untouched;
  natural vehicle is the M1-962/M1-964 census/lint work or a standalone
  one-liner. Filing is the user's call.
