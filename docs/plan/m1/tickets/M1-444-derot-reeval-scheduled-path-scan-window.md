---
id: M1-444
title: "test: de-rot the ReEvaluationJobScheduledPathIT scan-window fixture so the in-window post never ages out"
status: pending
created: 2026-06-24
last_updated: 2026-06-24
clarity_check:
  date:
  verdict:
  warnings: []
  blockers: []
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobScheduledPathIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "ReEvaluationJob production code (the job and its enumerateCandidates() scan-window SQL `fetched_at >= now() - (retention + slack)::INTERVAL` are correct; the bug is the test fixture's hard-coded FETCHED_AT, not the window floor itself). This is the same posture M1-400 and M1-398 took for the sibling workers."
  - "Any deliberately-below-floor fixture the test keeps on purpose — leave it fixed and comment why; do not relative-date a fixture whose point is to sit outside the window."
  - "A suite-wide time-bomb sweep. Fix only ReEvaluationJobScheduledPathIT here; if other fixed-date fixtures are found, file them separately (per M1-400's precedent)."
acceptance:
  - "Both currently-failing methods pass: `capExhaustedRowReachesNeedsReviewThroughScheduledTick` and `unknownEntryPostWithInterimInjectionRollStaysEnumerated`. Today (2026-06-24) they fail `expected <NEEDS_REVIEW> but was <QUARANTINED>` because the seeded post (FETCHED_AT=2026-05-23T09:00:00Z) has aged past the retention(30d)+slack(2d)=32d scan-window floor, so enumerateCandidates() no longer returns it and the post is never transitioned."
  - "The in-window seed(s) use a `now()`-relative `Instant` (e.g. `Instant.now()`, matching the convention M1-400 brought EntityExtractorWorkerIT to and that ReEvaluationJobWindowTest already uses) so the post stays inside the 32-day window regardless of the wall-clock date the suite runs."
  - "mvn -B clean verify from the repo root exits 0 (collector module green; this is the only known red on main as of 2026-06-24, so this ticket unblocks the full-suite gate for all subsequent tickets)."
test_plan:
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobScheduledPathIT.java (replace the fixed FETCHED_AT with a now()-relative value for the in-window post(s))"
  preserves:
    - all other tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-444: de-rot the ReEvaluationJobScheduledPathIT scan-window fixture

## Context

Surfaced 2026-06-24 while working M1-441: `mvn -B clean verify` fails in
infochat-collector. `ReEvaluationJobScheduledPathIT` seeds a post at a **fixed**
`FETCHED_AT = 2026-05-23T09:00:00Z` and drives `ReEvaluationJob.onTick()`,
expecting the post to transition to `NEEDS_REVIEW`. The job's
`enumerateCandidates()` SQL carries `fetched_at >= now() - (retention + slack)`;
with the test profile's `infochat.partitions.retention-days.post = 30` and the
2-day slack the floor is `now() - 32 days`. On 2026-06-24 the floor first crosses
`2026-05-23 09:00`, so the seeded post drops below the floor, is no longer
enumerated, is never transitioned, and stays at its seeded `QUARANTINED` —
failing the `NEEDS_REVIEW` assertion (line ~203 `assertPostStatus`).

This is the **exact same time-bomb class** M1-398 fixed for `EmbeddingWorkerIT`
and M1-400 fixed for `EntityExtractorWorkerIT`. `ReEvaluationJobScheduledPathIT`
is a sibling that was missed in those sweeps only because its 2026-05-23 seed
stayed in-window until 2026-06-24. It would fail on `main` today regardless of
M1-441 — collector is byte-identical to `main`. The product code is correct; the
fix is test-only.

## Why this matters beyond the one red

This is the failure that is currently making `mvn verify` red on `main`, so it
blocks the full-suite gate for **every** ticket, not just M1-441. M1-445 (pin
surefire) is `blocked_by` this ticket because its "verify is green with the unit
suite running" acceptance cannot hold while this red persists. Land this first.

## Notes

- The fix shape is identical to M1-400: replace the fixed `FETCHED_AT` constant
  with a `now()`-relative `Instant` so the in-window post never ages out. Do NOT
  touch `ReEvaluationJob` or the scan-window floor.
- Read both failing methods and the shared `@BeforeEach`/seed helper before
  editing — there may be more than one fixed-date seed to relative-date.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-444-derot-reeval-scheduled-path-scan-window.md
```
