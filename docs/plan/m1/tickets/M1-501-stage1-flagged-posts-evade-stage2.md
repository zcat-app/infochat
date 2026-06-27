---
id: M1-501
title: "Stage-1-flagged posts can permanently evade Stage 2 after a crash"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 4
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - "Any change to the Stage-1 LLM verdict semantics or the security-check prompt."
  - "The benign re-eval audit/notify scope and post_uid fix (that is M1-482)."
  - "The §9 injected-Clock migration of the re-enqueue predicate (separate backlog)."
acceptance:
  - >-
    A Stage-1-flagged post left in RAW with stage1_done=TRUE,
    stage1_flagged=TRUE, stage2_done=FALSE, stage2_failed=FALSE (the state a
    crash or a failed Stage-2 verdict write leaves behind) is rescued by exactly
    one of: the Stage-2 pickup query, the re-evaluation candidate enumeration,
    or a dedicated rehydrator — so it is eventually judged, released, or
    quarantined and never stranded in RAW.
  - >-
    The stale-RAW re-emit predicate (Stage1Worker reEmitStaleRaw) no longer
    re-selects the stranded post on every pass once it has been routed to a
    terminal-or-progressing state, so it cannot loop indefinitely on the same
    orphan (schema Invariant 5).
  - >-
    A new test seeds the orphan state directly in Postgres and asserts the post
    reaches a Stage-2 outcome (judged/quarantined) rather than remaining RAW
    across repeated worker passes.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1OrphanRescueIT.java"
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

# M1-501: Stage-1-flagged posts can permanently evade Stage 2 after a crash

## Context

From `/deep-code-review full` (2026-06-27), report
`03-main-infochat-collector-01.md#F1` (rated **high**, verified at source).
`Stage1Worker` short-circuits on `stage1_done`
(`Stage1Worker.java:159-167,234-237`) and routes a flagged post toward Stage 2.
If the process crashes — or the Stage-2 verdict write fails — between the
Stage-1 flag and the Stage-2 completion, the post is left in RAW with
`stage1_done=TRUE / stage1_flagged=TRUE / stage2_done=FALSE /
stage2_failed=FALSE`. The Stage-2 pickup (`TaggerWorker.java:526` excludes
`stage1_flagged=FALSE OR stage2_done=TRUE`) and the re-eval enumeration
(`ReEvaluationJob.java:589` matches only `stage2_failed=TRUE` or `QUARANTINED`)
both skip that exact state, so the security-suspicious post is never judged,
released, or quarantined — and `reEmitStaleRaw` re-selects it forever. This
strands the *security-suspicious minority* of posts and violates schema
Invariant 5.

## Acceptance

See frontmatter. Close the rescue gap so the orphan state is owned by exactly
one recovery path, and stop the re-emit predicate looping on it. Add an IT that
seeds the orphan state and proves convergence to a Stage-2 outcome.

## Out-of-scope

See frontmatter. No LLM-verdict change; the benign-release audit/post_uid fix
is M1-482; the §9 clock migration of the predicate is deferred.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 03#F1.
- The narrow trigger (hard crash / transient DB fault on the Stage-2 write) is
  why this is a recovery-path gap, not a steady-state bug — but the consequence
  (permanent strand + unbounded re-enqueue) is severe enough for high.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-501-*.md
```
