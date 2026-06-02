---
id: M1-121
title: "June+July 2026 partitions + monthly partition-creator scheduler"
status: done
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 6
files_scope:
  - infochat-core/src/main/resources/db/migration
  - infochat-collector/src/main/java/app/zcat/infochat/collector
  - infochat-collector/src/test/java/app/zcat/infochat/collector
  - infochat-collector/src/main/resources/application.properties
complexity: medium
risk: high
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - infochat-provider/** — no provider changes
  - any change to the existing _202605 partition definitions in V7/V11/V17/V28/V29
  - the partition-creation cadence alarm threshold beyond the 25-day check named below
acceptance:
  - "A new Flyway migration adds the _202606 and _202607 partitions for all five partitioned tables (post, post_embedding, post_entity, post_reference, price_snapshot) and applies cleanly on a fresh DB"
  - "An INSERT into post with fetched_at = '2026-06-15' succeeds (the partition exists) — covered by a new integration test that seeds a June-dated row"
  - "A @Scheduled bean provisions the next calendar month's partition for all five tables before month end, and logs a WARN if it has not successfully run in 25 days"
  - "PartitionCreatorTest (unit) asserts the DDL builder emits one CREATE TABLE ... PARTITION OF per table for a given month with the correct FROM/TO bounds"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/architecture.md §Pipelines
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-02
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 347
      removed: 0
  - round: 2
    date: 2026-06-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 347
      removed: 0
escalations:
  - date: 2026-06-02
    reason: budget-breach
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL — the changed file
      infochat-collector/src/main/resources/application.properties (the
      +infochat.partitions.check-interval=24h property) matches none of the
      files_scope entries; src/main/resources is a sibling of the src/main/java
      prefix the scope declares. The change is substantively legitimate (the
      @Scheduled binding needs the property to resolve) but the scope list did
      not authorize a resources/ edit.
revisions:
  - date: 2026-06-02
    reason: "refine (round 1 rework) — widen files_scope to authorize the application.properties edit the @Scheduled interval binding requires"
    snapshot:
      files_scope:
        - infochat-core/src/main/resources/db/migration
        - infochat-collector/src/main/java/app/zcat/infochat/collector
        - infochat-collector/src/test/java/app/zcat/infochat/collector
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-02
  verdict: PASS
  warnings:
    - "Acceptance item [3] asserts a WARN log if the partition-creator has not run in 25 days, but no named test covers the liveness-check path; verification may rely on log inspection."
  blockers: []
---

# M1-121: June+July 2026 partitions + monthly partition-creator scheduler

## Context

All five partitioned tables (`post`, `post_embedding`, `post_entity`,
`post_reference`, `price_snapshot`) currently define exactly one partition,
`FOR VALUES FROM ('2026-05-01') TO ('2026-06-01')` (V7, V11, V17, V28, V29).
The latest migration is V29 and **no partition-creation scheduler exists**.
Today is 2026-06-02, so the first INSERT with `fetched_at >= 2026-06-01`
(every new post) fails with `no partition of relation … found for row`. The
collector is dead on the first real insert. The spec promises an
"application-tier partition scheduler" that was never built. This is the
highest-priority finding across all nine audit runs.

## Acceptance

See frontmatter. Two parts: (1) **immediate unblock** — a migration adding
June + July 2026 partitions to all five tables; (2) **durable fix** — a
`@Scheduled` monthly partition-creator bean that provisions next month's
partition ahead of need, with a liveness WARN if it hasn't run in 25 days.

Run the existing IT suite first as a falsifier of "it's fine today" — if any
IT inserts at `now()` it may already be red on the month boundary.

## Out-of-scope

See frontmatter. Do not alter the existing `_202605` partitions. The migration
version number is assigned at start by the workflow (do not hardcode it).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A1 (PARTITIONS, Critical, GROUNDED);
  `opus-47-full-handout.md` §F-MAINT-01.
- Partitioned-table loci: `V7__joins_post.sql:175-176`, `V11__post_embedding.sql:77-78`,
  `V17__price_snapshot.sql:60-61`, `V28__post_entity.sql:69-70`, `V29__post_reference.sql:69-70`.
- The scheduler creates partitions via runtime `CREATE TABLE … PARTITION OF`
  DDL; the immediate migration is the data-shape unblock so the suite goes green
  today. Keep the month-bound computation in one place; mirror the existing
  partition naming convention (`<table>_YYYYMM`).

## Round 1 rework

1. Resolve the out-of-scope edit to
   `infochat-collector/src/main/resources/application.properties` (the added
   `infochat.partitions.check-interval=24h` property). The change is needed for
   the `@Scheduled` binding, but the file is outside the ticket's `files_scope`
   (which lists only `src/main/java` and `src/main/resources/db/migration`
   paths). Either escalate to widen `files_scope` to include the properties
   file, or move the cadence to a hardcoded `@Scheduled(every = "24h")` inside
   the declared scope. Do NOT silently broaden scope without authorization.
