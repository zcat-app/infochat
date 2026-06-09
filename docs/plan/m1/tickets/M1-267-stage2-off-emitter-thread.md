---
id: M1-267
title: "Stage 2 judge off the emitter thread"
status: done
created: 2026-06-09
last_updated: 2026-06-10
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Worker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/EvalQueueProducer.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - Stage2Worker.judge internals and the Stage-2 verdict handling.
  - Kafka migration of the eval channel — stays SmallRye in-memory per the v1 decision.
  - Eval concurrency limits and their per-profile values — preserved, not retuned.
  - The outbox/rehydrator design (it already provides the at-least-once safety net).
acceptance:
  - "The eval-queue consumer no longer runs Stage-2 work on the emitting thread: Emitter.send in EvalQueueProducer returns without waiting on the Stage-2 LLM call (e.g. @Blocking on the @Incoming handler or an explicit executor hop in Stage1Worker.onPostKey)."
  - "A named test asserts via thread-name capture that the Stage-2 judge executes on a different thread than the one that emitted the post key (the report explicitly asks for this assertion)."
  - "Per-profile eval concurrency still bounds concurrent Stage-2 evaluations after the hop; the existing concurrency tests stay green."
  - "At-least-once survives the hop: a post enqueued but not yet judged at shutdown is re-enqueued by the startup rehydrator (existing rehydrator tests stay green)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-10
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 277
      removed: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-267: Stage 2 judge off the emitter thread

## Context

Deep-review v4 verified HIGH **H6** (`deep-code-review/v4/UNIFIED-REPORT.md`
§1; source `deep-code-review/v4/opus-48/06-module-infochat-collector.md#F1`):
`EvalQueueProducer.emit` uses a plain `Emitter.send`, and
`Stage1Worker.onPostKey` (`@Incoming("eval-queue")`) has no `@Blocking` and no
executor hop before calling `stage2Worker.judge(...)`. SmallRye in-memory
channels run the subscriber on the emitting thread, so one Stage-1 regex hit
parks the fetch dispatcher (or the Nostr delivery loop) for the full Stage-2
LLM duration — ~60 s worst case — throttling ingest for all sources. A
back-pressure inversion: the slowest stage executes inline on the fastest
stage's thread.

## Acceptance

See frontmatter. The report asks to "confirm with a thread-name assertion
when fixing" — that assertion is a named acceptance test, not optional.

## Out-of-scope

See frontmatter. The fix is a threading hop, not a queue redesign; bounded
behavior under overflow is already owned by the outbox pattern.

## Notes

- `@Blocking` (SmallRye) on the `@Incoming` handler is the smallest cut and
  keeps virtual-thread/blocking style per the stack decision; an explicit
  `@RunOnVirtualThread`/executor hop is the alternative if `@Blocking`'s
  default worker pool interacts badly with the eval-concurrency semaphore.
  Verify which mechanism the eval concurrency limit is implemented with
  before choosing.
- Watch `Stage1WatchdogIT`'s 50 ms cap — it is known marginal (flaked once at
  51 ms); a threading change near Stage 1 may surface it. Retry once on a
  flake per the recorded policy.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-267-*.md
```
