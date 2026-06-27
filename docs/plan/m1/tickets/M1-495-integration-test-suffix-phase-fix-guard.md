---
id: M1-495
title: "Integration/DB-boot tests named *Test run in the surefire (unit) phase"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 8
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Genuine unit tests that happen to end in *Test (e.g. the non-DevServices siblings of QuarantineReviewListenerTest) — only DevServices/full-boot @QuarkusTest classes are renamed."
acceptance:
  - >-
    The full-boot @QuarkusTest classes that hit DevServices Postgres but carry a
    *Test suffix are renamed to *IT so they run in failsafe/verify, not surefire:
    LinkingJobTest, Kind6HandlerTest, NostrSinceCursorTest (collector),
    QuarantineReviewListenerTest (provider outbox), and
    EligiblePostQueryStatementTimeoutTest (provider summary). Their behavior and
    assertions are unchanged; only the class name (and references) change.
  - >-
    A build-time guard prevents regressions of this whole class: a test (or
    enforcer rule) asserts that no @QuarkusTest injecting a DataSource /
    @SeedDataSource carries a *Test name. The guard fails the build if a future
    integration-shaped class is named *Test.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-core/src/test/java/app/zcat/infochat/core/testsupport/IntegrationTestNamingGuardTest.java"
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

# M1-495: Integration/DB-boot tests named *Test run in the surefire (unit) phase

## Context

From `/deep-code-review full` (2026-06-27), cross-cutting theme **CT3** —
reports `22#F2`, `34#F1`, `35#F2` (verified at source, including the pom phase
binding: failsafe runs `*IT` in verify, surefire runs `*Test` in the test
phase). Five full-boot `@QuarkusTest` classes that hit DevServices Postgres, emit
real `pg_notify`, or poll for async cursor advances are named `*Test`, so they
run in the unit phase — pulling DevServices into `mvn test`, defeating the
module's documented `*IT` split, and hiding integration coverage from anyone
scanning by suffix.

## Acceptance

See frontmatter. Rename the five to `*IT` and add a build-time guard that catches
the whole class of misnaming.

## Out-of-scope

See frontmatter. Genuine unit `*Test` classes are not renamed.

## Notes

- Source: `/deep-code-review full` (2026-06-27), CT3 (22#F2, 34#F1, 35#F2).
- The guard is the durable fix; the renames are the immediate cleanup.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-495-*.md
```
