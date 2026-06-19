---
id: M1-400
title: "test: de-rot the EntityExtractorWorkerIT scan-window fixture so the in-window post never ages out"
status: pending
created: 2026-06-19
last_updated: 2026-06-19
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorkerIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - EntityExtractorWorker production code (the worker and its enumeratePending query are correct; the bug is in the test fixture's hard-coded date, not the scan-window floor itself).
  - The other latent fixed-date fixtures listed in the Notes below — each is a separate follow-up unless the implementer confirms it is scan-window-sensitive AND already firing; do not broaden this ticket into a suite-wide sweep without re-sizing.
acceptance:
  - "EntityExtractorWorkerIT.endToEndEntityExtraction's seeded post uses a fetched_at computed relative to now() (e.g. Instant.now(), matching ReEvaluationJobWindowTest's in-window seed convention) instead of the hard-coded FETCHED_AT = 2026-05-18T09:00:00Z, so the post always sits inside the retention(30d)+slack(2d) scan-window floor regardless of the wall-clock date the suite runs."
  - "Any other in-window post the same test seeds is likewise relative-dated; any fixed date the test KEEPS (e.g. a deliberately-below-floor fixture) carries a comment explaining why it is intentionally outside the window."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/entity/EntityExtractorWorkerIT.java (replace the fixed FETCHED_AT with a now()-relative value)
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

# M1-400: de-rot the EntityExtractorWorkerIT scan-window fixture

## Context

Surfaced during M1-370's `mvn verify` gate on 2026-06-19.
`EntityExtractorWorkerIT.endToEndEntityExtraction` seeds a post at a **fixed**
`FETCHED_AT = 2026-05-18T09:00:00Z` and asserts it is returned by
`EntityExtractorWorker.enumeratePending`, whose SQL carries
`fetched_at >= now() - (retention + slack)::INTERVAL`. With the test profile's
`infochat.partitions.retention-days.post = 30` and the worker's 2-day slack, the
window floor is `now() - 32 days`. On 2026-06-19 the floor first crosses
`2026-05-18 09:00`, so the seeded post drops below the floor partway through the
day and `enumeratePending` no longer returns it — the test fails with
"seeded post must be picked up by enumeratePending".

This is a **time-bomb test fixture**, not a product bug. It is unrelated to
M1-370 (whose diff touches nothing in the entity path) and would fail on `main`
today regardless. M1-370 is `deferred_on` this ticket so its full-suite gate can
go green once this lands. The sibling reeval tests already avoid this trap by
seeding in-window posts at `Instant.now()` (see `ReEvaluationJobWindowTest`'s
`in-window` seed and `ReEvaluationJobTest.needsReviewDepthAlert`'s
"fetched_at = now()" comment) — this ticket brings EntityExtractorWorkerIT to
the same convention.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- **The fix** is the same shape the reeval tests use: replace the fixed
  `FETCHED_AT` constant with a `now()`-relative `Instant` (or inline
  `Instant.now()`) so the in-window post never ages out. Do NOT touch the
  worker's `enumeratePending` query or the scan-window floor — those are correct.

- **Latent siblings (NOT in this ticket's scope; recorded for triage).** A sweep
  of `infochat-collector/src/test` for hard-coded `Instant.parse(...)` fetched_at
  fixtures found several more. Only those whose worker pickup query uses the same
  `fetched_at >= now() - scanWindow()` floor AND seed an intended-in-window post
  are at risk; the rest pick posts up by id/uid and are immune. Candidates worth
  checking before they fire (window floor advances one day per day):
    - `ReEvaluationJobScheduledPathIT` FETCHED_AT 2026-05-23 (would fire ~2026-06-24)
    - `LinkingJobIT` / `LinkingJobSemanticProbeIT` FETCHED_AT 2026-05-22 (~2026-06-23)
  If the implementer confirms one of these is genuinely scan-window-sensitive and
  about to fire, file (or fold via an explicit re-size) — do not silently expand.
