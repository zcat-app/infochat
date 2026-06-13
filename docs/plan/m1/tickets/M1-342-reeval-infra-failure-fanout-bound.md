---
id: M1-342
title: "ReEvaluationJob: bound infra-failure re-judge fan-out to one call per tick during an outage"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The spec rule that INFRA_FAILURE re-eval verdicts MUST NOT increment re_eval_attempts (docs/spec/security.md §Re-evaluation job — counter increments limited to INJECTION/MALWARE/UNKNOWN) — unchanged; this ticket does not burn the cap, it bounds the per-tick CALL volume.
  - The candidate query and the UNKNOWN/QUARANTINED branch (those advance toward the cap and self-terminate) — unchanged.
acceptance:
  - "During a sustained LLM outage, the re-eval job issues at most one Stage-2 provider call per tick for the infra-failure candidate set, not one per backlog entry per tick. Once an INFRA_FAILURE verdict is observed within a tick, the remaining candidates that tick are skipped (a per-tick providerDown flag, reset at the top of onTick): a second provider call in the same tick is near-certain to fail identically and gains nothing."
  - "The fix preserves the no-increment rule (INFRA_FAILURE still does not advance re_eval_attempts) and the eventual-recovery behavior (the next tick retries from the top of the ordered scan); it only removes the outage-time fan-out where a backlog of K fail-open posts produced K identical failing LLM calls per tick."
  - "A test pins the bound: with a stubbed Stage-2 worker returning INFRA_FAILURE, a tick over a multi-candidate backlog invokes judgeBody at most once (the remaining candidates are deferred to the next tick) and increments no attempt counter; when the worker returns a normal verdict, all candidates are processed as before."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval (infra-failure fan-out bound case)
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Re-evaluation job
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-342: ReEvaluationJob — bound infra-failure re-judge fan-out

## Context

Deep-review v5.5 (opus-48, `06-module-infochat-collector.md` F2) found that the
infra-failure candidate branch re-selects and re-judges already-visible (READY,
fail-open) posts on every tick while the LLM stays down, with no attempt-counter
progress. **Verified at source 2026-06-14:** the candidate query matches
`stage2_failed = TRUE AND status != 'NEEDS_REVIEW'` regardless of status
(ReEvaluationJob.java:535-539), and the `INFRA_FAILURE` branch deliberately skips
the attempt increment (lines 168-171) — correct per
`docs/spec/security.md` §Re-evaluation job, but it means no progress and no exit
while the LLM is unreachable.

During a sustained outage, every fail-open post in the retention window is
re-submitted to `stage2Worker.judgeBody` on every tick. The Stage-2 semaphore
bounds concurrency, not call volume: a backlog of K posts produces K identical
failing LLM calls per tick, indefinitely — wasted outbound cost and log volume
exactly when the provider is already struggling. The fix bounds this to one call
per tick without changing the no-increment rule.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- One provider call per tick is enough to learn the provider is still down. A
  transient single-call failure amid a healthy provider defers the rest of that
  tick's batch to the next interval (profile-driven minutes) — negligible latency
  against the outage savings.
