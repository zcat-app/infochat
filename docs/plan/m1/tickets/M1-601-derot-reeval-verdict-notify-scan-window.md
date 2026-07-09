---
id: M1-601
title: "test: de-rot ReEvalVerdictNotifyIT scan-window fixture by pinning the injected Clock (2026-07-09 time-bomb)"
status: done
created: 2026-07-09
last_updated: 2026-07-09
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvalVerdictNotifyIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The other latent absolute-fetched_at fixtures. A census of the whole
    collector IT suite for the same absolute-fetched_at + unpinned-Clock pattern
    (and a build guard that fails on new instances) is the SYSTEMIC follow-up
    M1-602 — NOT this ticket. This ticket de-rots ONLY ReEvalVerdictNotifyIT,
    the single fixture whose detonation date (FETCHED_AT 2026-06-07 + 32d =
    2026-07-09T10:00Z) has already passed, so the full suite is red TODAY.
  - >-
    Any production code. The eval-worker scan floor
    (PartitionScan.scanWindowFloor(clock.instant())) and the ReadyPromoter
    classifier_done gate already read the injected Clock correctly — the fault
    is purely test-side (the IT never pins that Clock, so it falls back to
    Clock.systemUTC()). Do NOT touch PartitionScan, the workers, ReadyPromoter,
    or ClassifierWorker.
  - >-
    The seeded FETCHED_AT value and the four tests' assertion intent. FETCHED_AT
    stays 2026-06-07T10:00:00Z; pinning the Clock relative to it is what removes
    the wall-clock dependency. No assertion is weakened, disabled, or deleted —
    the post is meant to reach READY, and after the fix it does so
    deterministically.
acceptance:
  - >-
    ReEvalVerdictNotifyIT pins the app-wide injected Clock in @BeforeEach via
    `QuarkusMock.installMockForType(Clock.fixed(<instant>, ZoneOffset.UTC),
    Clock.class)`, where `<instant>` is chosen RELATIVE to the class constant
    FETCHED_AT (e.g. `FETCHED_AT.plus(Duration.ofHours(1))`) so the seeded post's
    fetched_at falls inside every eval stage's 32-day scan window
    (`fetched_at >= scanWindowFloor(clock.instant()) = now - (retention 30 +
    slack 2)d`) regardless of the real calendar date. No new inline
    `Instant.now()` / SQL `now()` is introduced (engineering-rules §9 injectable
    time; M1-444 reference implementation).
  - >-
    unknownBenignReEvalCompletesPipelineAndEmitsNewPost passes deterministically:
    with the Clock pinned, tagger/entity/embedding/classifier/ReadyPromoter no
    longer age the seeded post out of their pickup floor, so it completes the
    pipeline and reaches status READY (the `post.status expected READY but was
    RAW` failure is gone). The other three tests in the class remain green under
    the pin.
  - >-
    mvn verify is green from the repo root — the full suite, which failed on
    ReEvalVerdictNotifyIT for any run on/after 2026-07-09T10:00Z, is green again
    and no longer depends on the wall-clock date for this fixture.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-collector/.../eval/reeval/ReEvalVerdictNotifyIT.java — add the
      @BeforeEach QuarkusMock Clock pin (Clock.fixed relative to FETCHED_AT) plus
      the Clock/ZoneOffset/Duration/QuarkusMock imports. No assertion-intent
      change; the pin only makes the existing "post reaches READY" expectation
      deterministic instead of wall-clock-dependent.
  preserves:
    - all other tests currently green on main
    - >-
      the injectable-time discipline (engineering-rules §9): the fix ADDS a Clock
      pin rather than introducing any new inline now(); audit/record timestamps
      (status_changed_at, ready_at, created_at) stay on the real DB clock and are
      unaffected by the decision-logic Clock pin.
spec_refs:
  - docs/process/engineering-rules-verbatim.md §9 Injectable time in decision logic
decision_refs: []
redteam_findings: []
redteam_audits: []
reviews:
  - round: 1
    date: 2026-07-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 25
      removed: 7
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-07-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-601: de-rot ReEvalVerdictNotifyIT scan-window fixture

## Context

The full `mvn verify` went red on 2026-07-09 at
`app.zcat.infochat.collector.eval.reeval.ReEvalVerdictNotifyIT
.unknownBenignReEvalCompletesPipelineAndEmitsNewPost` with
`post.status ==> expected: <READY> but was: <RAW>`.

Root cause (diagnosed): the fixture pins `FETCHED_AT =
Instant.parse("2026-06-07T10:00:00Z")` (absolute) but the IT **never pins the
injected `Clock`**, so the eval workers use `Clock.systemUTC()`. Every stage
gates pickup on `fetched_at >= scanWindowFloor(clock.instant()) = now − 32d`
(retention 30 + slack 2). The post's expiry instant is
`2026-06-07T10:00Z + 32d = 2026-07-09T10:00Z`; on/after that instant the post
falls below the floor for tagger/entity/embedding/classifier/ReadyPromoter, so
it never leaves RAW. It passed when introduced (M1-182, 2026-06-07) and stayed
latent until the calendar crossed the boundary today.

This is the same date-boundary time-bomb class as M1-398 / M1-400 / M1-444. The
production scan path is already injectable (M1-444/M1-447); only this pre-existing
IT was never retrofitted to pin the Clock. It is independent of any provider work.

## The fix

Pin the injected `Clock` in `@BeforeEach` via
`QuarkusMock.installMockForType(Clock.fixed(FETCHED_AT.plus(Duration.ofHours(1)),
ZoneOffset.UTC), Clock.class)` (the M1-444 reference pattern). With the Clock
fixed shortly after FETCHED_AT, the scan floor is `FETCHED_AT + 1h − 32d`, which
keeps the seeded post permanently in-window — deterministic regardless of the
real date. FETCHED_AT and every assertion are unchanged.

## Scope

ONE test file. The whole-suite census + a build guard against new
absolute-fetched_at-without-Clock-pin fixtures is the systemic follow-up M1-602
(filed alongside this ticket), deliberately kept separate so this unblock stays
minimal.
