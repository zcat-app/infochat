---
id: M1-602
title: "test-hygiene: sweep all absolute-fetched_at scan-window fixtures onto a pinned Clock + add a build guard against new time-bombs"
status: pending
created: 2026-07-09
last_updated: 2026-07-09
blocked_by: []
files_budget: 30
files_scope:
  - docs/plan/m1/scan-window-fixture-census.md
  - infochat-collector/src/test/java/app/zcat/infochat/collector/testsupport/ScanWindowFixtureGuardTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/**
complexity: high
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ReEvalVerdictNotifyIT — already de-rotted by M1-601 (the one fixture whose
    detonation date had passed). This ticket sweeps the REST of the class and
    adds the recurrence guard; it must not re-touch ReEvalVerdictNotifyIT beyond
    confirming the guard passes for it.
  - >-
    Production code. Every eval-worker scan floor already reads the injected
    Clock (M1-444/M1-447); this is purely test-fixture hygiene. No worker,
    PartitionScan, ReadyPromoter, or migration change.
  - >-
    Rewriting assertion intent. Each swept fixture keeps its existing assertions;
    only the time seam changes (pin the injected Clock via QuarkusMock, or make
    the seeded fetched_at relative to the pinned clock's instant). No assertion
    is weakened or deleted.
acceptance:
  - >-
    A committed census at EXACTLY docs/plan/m1/scan-window-fixture-census.md
    enumerates every collector test (IT and unit) that seeds an ABSOLUTE
    fetched_at (or any other scan-window-gated absolute instant — TTL/expiry/
    cooldown pickup floors) via `Instant.parse("20NN-...")` WITHOUT pinning the
    injected Clock, classifying each as (A) LIVE/LATENT time-bomb (its seed feeds
    a `fetched_at >= now - Nd` style pickup gate) vs (B) BENIGN (the absolute
    instant is parser data, an expected value, a published_at recency seed not
    gating worker pickup, or a RAW-status seed never enumerated). The (A) list is
    the sweep worklist. Starting-point candidates (from the M1-601 investigation
    grep): the Stage1*/Stage2*/EmbeddingWorker*/TaggerWorker*/EntityExtractor*/
    Linking*/ReEvaluation*/Quarantine*/Partition* IT+Test set — each must be
    opened and classified, not assumed.
  - >-
    Every (A) fixture from the census reads its decision-time from the injected
    Clock pinned via `QuarkusMock.installMockForType(Clock.fixed(<instant
    relative to its seed>, ZoneOffset.UTC), Clock.class)` (the M1-444 / M1-601
    pattern), OR seeds its fetched_at relative to that pinned instant — so its
    pickup-gate outcome no longer depends on the wall-clock date. No new inline
    `Instant.now()` / SQL `now()` is introduced (engineering-rules §9). The
    filing-time files_scope glob over the collector test tree covers the
    per-fixture edits; the census (item 1) is the authoritative enumeration of
    exactly which files were swept and why.
  - >-
    A build guard at
    infochat-collector/.../testsupport/ScanWindowFixtureGuardTest.java fails the
    build when a collector test source seeds an absolute
    `Instant.parse("20NN-...")` used as a fetched_at (or other pickup-floor) seed
    without a `Clock.fixed(` / `installMockForType(..., Clock.class)` pin in the
    same file. The guard is documented with the allow-list mechanism for the
    (B) benign cases the census identified, so a legitimately-benign absolute
    instant does not trip it.
  - >-
    mvn verify is green from the repo root AND the new guard passes — proving
    both that the swept suite no longer depends on the wall-clock date and that a
    future absolute-fetched_at-without-Clock-pin fixture is caught at build time
    rather than detonating on its own future calendar boundary.
test_plan:
  adds:
    - >-
      infochat-collector/.../testsupport/ScanWindowFixtureGuardTest.java — the
      recurrence guard (source-scanning meta-test over collector test files).
  modifies:
    - >-
      Every (A) fixture the census identifies — pin the injected Clock (or make
      the seed relative). Enumerated by the census (acceptance item 1); covered
      by the filing-time files_scope glob over the collector test tree.
  preserves:
    - all tests currently green on main (each swept fixture keeps its assertions)
    - >-
      the injectable-time discipline (engineering-rules §9): the sweep ADDS Clock
      pins, never new inline now(); audit/record timestamps stay on the DB clock.
spec_refs:
  - docs/process/engineering-rules-verbatim.md §9 Injectable time in decision logic
decision_refs: []
redteam_findings: []
redteam_audits: []
reviews: []
escalations:
  - date: 2026-07-09
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL (round 2, after one bounded self-refine)
      FILES-BUDGET-PLAUSIBLE: FAIL — files_scope (census doc + guard test only)
      does not cover the files acceptance item 2 requires modifying ("every
      (A) fixture from the census"); acceptance item 2's own text admits this,
      deferring to a mid-ticket escalate→refine. As filed, the ticket can only
      complete acceptance item 1 before it must escalate; items 2-4 are
      unreachable inside the current files_scope. Fix by (a) widening
      files_scope now to a glob covering the collector test tree, e.g.
      infochat-collector/src/test/java/app/zcat/infochat/collector/**, or
      (b) splitting at the census boundary (census-only ticket now; follow-up
      sweep+guard ticket with exact files_scope once the (A) list exists).
overrides: []
revisions:
  - date: 2026-07-09
    reason: >-
      clarity-fail self-refine (auto, /m1-tick run bounded prose-refine): the
      FILES-BUDGET-PLAUSIBLE blocker — the ticket planned to add the census's
      (A) per-fixture file list to files_scope "at start-time", a mechanism
      that does not exist in the workflow (start.md has no files_scope
      amendment step; SKILL.md forbids silent files_scope expansion and treats
      an out-of-scope touch as an immediate escalation trigger). Pure
      mechanism-phrasing retarget; no scope, acceptance-behavior, or
      files_scope change.
    snapshot: |
      acceptance item 2 last sentence (pre-refine): "The exact per-fixture
        file list is added to files_scope at start-time from the census."
      test_plan.modifies (pre-refine): "Enumerated into test_plan.modifies +
        files_scope at start-time from the census (acceptance item 1)."
      Notes bullet 1 (pre-refine): "files_scope here lists only the census doc
        + the guard test; the per-fixture file set is added at /m1-tick start
        from the census (acceptance item 1), which is why files_budget is a
        generous 30. Size it down to the real (A) count once the census is
        written."
      clarity blocker (2026-07-09): files_scope excludes the (A) fixture files
        acceptance item 2 requires modifying, and "added at start-time" is not
        a supported workflow operation (workflow.md files_scope rule; SKILL.md
        never-silently-expand + immediate-escalation trigger).
      resolution: reworded all three sites to route the expansion through the
        documented mid-ticket escalate(budget-breach) -> refine cycle fired
        AFTER the census lands and BEFORE any fixture edit (clarity fix
        option (c)). files_budget / files_scope entries / acceptance semantics
        / complexity / risk / round_cap unchanged.
  - date: 2026-07-09
    reason: >-
      clarity-fail refine round 2 (user-directed via the escalation menu,
      option 1): round-2 clarity re-flagged the same FILES-BUDGET-PLAUSIBLE
      gap — files_scope structurally cannot cover acceptance items 2-4, and
      the fix (widen scope) is beyond the bounded self-refine. User chose to
      widen files_scope at filing time with a glob over the collector test
      tree rather than decompose at the census boundary.
    snapshot: |
      files_scope (pre-refine): [docs/plan/m1/scan-window-fixture-census.md,
        infochat-collector/.../testsupport/ScanWindowFixtureGuardTest.java]
      acceptance item 2 closing (pre-refine): "The exact per-fixture file list
        enters files_scope via the PLANNED post-census escalate -> refine
        cycle (see Notes) — never by silent start-time expansion."
      Notes bullet 1 (pre-refine): the planned mid-ticket
        escalate(budget-breach) -> refine choreography.
      resolution: files_scope += infochat-collector/src/test/java/app/zcat/
        infochat/collector/** (user-approved filing-time grant); dropped the
        now-moot planned-escalation prose from acceptance item 2,
        test_plan.modifies, and Notes. files_budget 30 / acceptance semantics
        / complexity / risk / round_cap unchanged.
aborted_attempts: []
reopens: []
---

# M1-602: sweep absolute-fetched_at scan-window time-bombs + add a build guard

## Context

M1-601 de-rotted `ReEvalVerdictNotifyIT`, the one scan-window fixture whose
detonation date (2026-07-09T10:00Z) had already passed. But it is one of a
**class**: a grep of `infochat-collector/src/test` for
`Instant.parse("20NN-...")` near `fetched_at` without a Clock pin surfaces ~40
files. Not all are bombs (many parse timestamps as data, seed `published_at`, or
seed RAW posts never enumerated), but every one that seeds a `fetched_at`
feeding a `fetched_at >= now − Nd` pickup gate is a latent time-bomb that will
detonate on its own calendar boundary — exactly as `ReEvalVerdictNotifyIT` did.

The injectable-time work (M1-398/M1-400/M1-444/M1-447) fixed only the fixtures
that were red at the time, plus converted production sites and added new pinned
tests for three security components; **it never swept the pre-existing eval-worker
IT fixtures**. M1-447's own acceptance says it modified no pre-existing test. So
the backlog remains, and CLAUDE.md records the whack-a-mole history verbatim:
"the date-boundary time-bomb M1-398 / M1-400 / M1-444 each fixed one instance of."

## The fix

1. **Census** every collector fixture with an absolute scan-window-gated instant;
   classify (A) bomb vs (B) benign.
2. **Pin** each (A) fixture's injected Clock (M1-444/M1-601 pattern), or make its
   seed relative to the pinned instant.
3. **Guard**: a source-scanning meta-test that fails the build if a new collector
   test seeds an absolute `fetched_at` without a Clock pin — turning the
   whack-a-mole class into a compile-time-caught invariant.

## Notes

- `files_scope` grants the census doc, the guard test, and a filing-time glob
  over the collector test tree (`infochat-collector/src/test/java/app/zcat/
  infochat/collector/**`) — the round-2 clarity resolution: the sweep's exact
  file set is unknowable until the census exists, so the wide grant is
  declared up front with user approval (no silent expansion) and bounded by
  `files_budget: 30`, out_of_scope item 3 (time-seam-only edits, no assertion
  rewrites), and the census itself (acceptance item 1), which enumerates
  exactly which files were swept and why.
- Deliberately NOT blocking M1-598 (the provider classification-render ticket):
  M1-601 alone unblocks the full verify today; this systemic sweep runs on its
  own schedule.
