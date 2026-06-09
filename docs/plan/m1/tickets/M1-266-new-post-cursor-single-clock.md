---
id: M1-266
title: "new_post cursor: single clock for ready_at"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/ready/ReadyPromoter.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready
  - infochat-provider/src/main/java/app/zcat/infochat/provider/outbox/NewPostReconciler.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - ProviderStateDao.advanceCursor CAS semantics — the strictly-monotonic cursor stays as is.
  - The V48 approve_quarantine path — it already uses the DB clock and is the model being matched.
  - Building the real new_post consumer (still a stub; this ticket de-risks it ahead of time).
  - Kafka or any alternative eventing.
acceptance:
  - "ReadyPromoter assigns ready_at from the database clock inside the promoting statement (same clock source as V48 approve_quarantine); no JVM Instant.now() value flows into ready_at. A named test asserts a promoted row's ready_at was produced by the database (e.g. equals the DB transaction timestamp observed in the same transaction)."
  - "A named test (or amended existing reconciler test) covers the skip scenario the report describes: a row whose ready_at is earlier than an already-advanced cursor is still findable under the chosen fix (single clock now; see Notes for the residual commit-order caveat the test should document)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/ready
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 384
      removed: 41
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: WARN
  warnings:
    - "TEST-CHANGES-AUTHORIZED: Acceptance item 2's 'amended existing reconciler test' branch could modify a pre-existing test (likely NewPostReconcilerIT.java) without an explicit test_plan.modifies entry naming the file and old behavior. Implementer should prefer adding a new named test; if amending, add a modifies entry before implementation begins."
  blockers: []
---

# M1-266: new_post cursor: single clock for ready_at

## Context

Deep-review v4 verified HIGH **H5** (`deep-code-review/v4/UNIFIED-REPORT.md`
§1; source `deep-code-review/v4/fable5/01-architecture.md#F1`): the `new_post`
cursor is built on two unrelated clocks. `ReadyPromoter.promoteOne` (:151)
stamps `ready_at` with `Instant.now()` — the Collector JVM clock — while the
V48 `approve_quarantine` path stamps it with the DB's `now()`. The consumer
side (`ProviderStateDao.advanceCursor` strictly-monotonic CAS +
`NewPostReconciler`'s `(ready_at, id) > cursor` catch-up scan) assumes one
ordered timeline. Clock skew between JVM and DB can land a row whose
`ready_at` is below an already-advanced `cursor_high`; the catch-up scan then
never sees it — a permanently skipped event. The consumer is still a stub, so
this is latent; the report's verdict is "cheapest to fix before a real
consumer attaches", and the suggested cut (T5) is "DB clock everywhere".

## Acceptance

See frontmatter: one clock (the DB's) for every `ready_at` writer, with a
named test pinning the source.

## Out-of-scope

See frontmatter. The cursor CAS and the reconciler's comparison shape stay;
only the timestamp source unifies.

## Notes

- **Residual caveat the implementer must weigh:** DB `now()` is
  transaction-start time, so commit-order inversion remains possible even on
  a single clock (T1 starts early/commits late while T2's later `ready_at`
  advances the cursor first). The cheap mitigations are (a) computing
  `ready_at` with `clock_timestamp()` at the promoting statement, and/or (b)
  giving the reconciler's scan lower bound a small fixed lag behind the
  cursor. The report only mandates the single-clock cut; if the implementer
  judges the lag-window cheap enough, propose it in the same diff — otherwise
  document the residual in a code comment so the future consumer ticket
  inherits the analysis.
- `NewPostReconciler` is in files_scope only for the optional lag-window leg
  and its test; if untouched, the negative-space check will surface it as an
  intentional skip.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-266-*.md
```
