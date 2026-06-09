---
id: M1-257
title: "ReEvaluationJob: SKIP concurrent ticks to bound attempt burn"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 3
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The candidate-enumeration scan and its index (fetched_at window + partial index) — owned by M1-245; unchanged.
  - The verdict/attempt UPDATE statements (recordVerdictAndIncrementCounter, WHERE id=? AND fetched_at=?) — the attempt-increment logic itself is unchanged; SKIP removes the concurrent double-increment without altering the single-tick increment.
  - FOR UPDATE SKIP LOCKED / CAS row-leasing (report Option B) — REJECTED for v1; the single-Collector topology means same-instance overlap is the only exposure and SKIP is the correct v1 fix. A v2 multi-instance leasing amendment is a separate future ticket.
  - The other module pollers (FetchScheduler, EmbeddingWorker, ReadyPromoter, TaggerWorker, EntityExtractorWorker, LinkingJob, AdminReviewTtlJob, PerSourceUnknownTracker, AssetSnapshotFetcher) — already set SKIP; not touched.
acceptance:
  - "ReEvaluationJob.onTick's @Scheduled annotation carries concurrentExecution = Scheduled.ConcurrentExecution.SKIP, matching every other candidate-processing poller in the module, so two ticks cannot run concurrently within the single Collector instance."
  - "A named test asserts the onTick @Scheduled annotation declares ConcurrentExecution.SKIP (reflective assertion on the annotation), pinning the convention so a future edit cannot silently drop it."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Re-evaluation job
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-257: ReEvaluationJob: SKIP concurrent ticks to bound attempt burn

## Context

`ReEvaluationJob.onTick` is the lone `@Scheduled` poller in the collector module
that omits `concurrentExecution = Scheduled.ConcurrentExecution.SKIP` (every
candidate-processing sibling sets it). Quarkus's `@Scheduled` default is
`PROCEED`, so two ticks can overlap. The re-eval tick is the most likely poller
to overrun its interval: it calls `stage2Worker.judgeBody(...)` synchronously per
candidate for up to `batchSize=16` candidates, each bounded only by the Stage-2
retry/backoff/timeout budget (worst case ~62s per candidate, ~16min per tick),
against a 5m poll interval on vps/remote-llm. During a Stage-2 outage — exactly
the condition that fills the re-eval queue — ticks reliably overlap.
`enumerateCandidates()` is not idempotent across concurrent runs (no
`FOR UPDATE SKIP LOCKED`, no in-flight marker, no CAS): two overlapping ticks
read the same rows, double-issue billable LLM judge calls, and both run the
unconditional `re_eval_attempts = re_eval_attempts + 1`, burning the per-post
attempt budget at up to 2× the intended rate so posts hit `NEEDS_REVIEW` earlier
than the cap intends (the BENIGN-release audit math `attempt = reEvalAttempts()+1`
is also wrong under a concurrent increment). Source:
`deep-code-review/v3.5/opus-48/06-module-infochat-collector.md#F1` (verified live:
`ReEvaluationJob.java:124` has no `concurrentExecution`; all sibling pollers do).

## Acceptance

See frontmatter. In prose: add `concurrentExecution = SKIP` to `onTick`'s
`@Scheduled`, matching the module convention, so same-instance ticks cannot
overlap. A test pins the annotation; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The candidate scan (M1-245), the increment statement itself, and
row-leasing (the v2 Option B) are untouched.

## Notes

- A skipped tick loses nothing: candidates stay `QUARANTINED`/`stage2_failed` and
  are re-enumerated next tick. `SKIP` only affects same-instance overlap, which
  is the entire v1 exposure under the single-Collector topology
  (`docs/spec/architecture.md` §Deployment topology).
- Adjacent code / pattern to match: `ReadyPromoter.onTick` (line ~118-119) and
  `EmbeddingWorker.onTick` (line ~188) already carry the exact annotation form.
</content>
