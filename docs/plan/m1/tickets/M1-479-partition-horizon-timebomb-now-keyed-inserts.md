---
id: M1-479
title: "infochat-core partition seeds break after 2026-08-01 (no PartitionCreator in core tests)"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 4
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Production partition provisioning — the live PartitionCreator (M1-121, an infochat-collector @Startup bean) already provisions the current + next month ahead, so there is NO production time-bomb. This ticket is TEST-scoped to infochat-core, whose test datasource never runs that collector bean."
  - "Implementing a core-side partition scheduler; the smallest fix that makes the core test seeds date-independent is sufficient."
acceptance:
  - >-
    The infochat-core tests that seed posts on the wall clock no longer fail once
    the date passes the V30-provisioned horizon (post partitions exist only
    through 2026-07; upper bound exclusive '2026-08-01'). The two affected seeds —
    QuarantineActorCheckTest.java:200 (explicit fetched_at = now()) and
    PerScopeIsolationIT.java:289 (omits fetched_at, routes via DEFAULT now()) —
    are made date-independent (seed a fixed in-range fetched_at, or the core test
    harness provisions the needed partition). They pass deterministically
    regardless of the wall-clock date.
  - >-
    A test asserts a post insert succeeds for a fetched_at beyond the current
    V30 horizon under whatever mechanism the fix chooses (so the cliff cannot
    silently return).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/PartitionHorizonInsertIT.java"
  modifies:
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/QuarantineActorCheckTest.java — seed fetched_at pinned in-range; assertions only tightened."
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/PerScopeIsolationIT.java — seed fetched_at made explicit so it no longer relies on the wall-clock DEFAULT."
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

# M1-479: infochat-core partition seeds break after 2026-08-01

## Context

From `/deep-code-review full` (2026-06-27), report `24-test-infochat-core-00.md#F1`.
**The original "production-wide time-bomb" framing was FALSIFIED:** a live
`PartitionCreator` (`infochat-collector`, M1-121) provisions the current + next
calendar month ahead at startup and daily, so production inserts are covered. The
real, narrower gap is **test-only in infochat-core**: that module's test
datasource boots Flyway (partitions through 2026-07 via V30, no DEFAULT
partition) but never runs the collector's `PartitionCreator` bean. So two
infochat-core seeds that key on `now()` —
`QuarantineActorCheckTest.java:200` and `PerScopeIsolationIT.java:289` — will hit
"no partition of relation post found for row" on/after **2026-08-01** (~5 weeks
out), turning the infochat-core suite red with no code change.

## Acceptance

See frontmatter. Make the two core seeds date-independent (smallest fix), and add
a test that proves an insert past the V30 horizon succeeds.

## Out-of-scope

See frontmatter. No production change (PartitionCreator already covers it); no
core-side scheduler.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 24#F1, corrected by the
  falsification pass (PartitionCreator exists; gap is infochat-core test scope).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-479-*.md
```
