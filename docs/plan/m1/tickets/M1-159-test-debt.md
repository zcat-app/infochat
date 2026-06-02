---
id: M1-159
title: "Test-debt (inner-class extraction, truncateAll completeness, delete IngestSpisLoadTest)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider
  - infochat-core/src/test/java/app/zcat/infochat/core
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - production source files (test-only ticket)
  - the tests' behavioral assertions (extraction is mechanical; assertions unchanged)
acceptance:
  - "The six test files exceeding the 3-inner-class guideline (InboundRouterProbationOrderingTest, IntakeOrderingTest, ContactIdRedactionTest, ConfirmCancelTest, DigestWorkerTest, InboundRouterNormalizeTest) extract their shared fakes to top-level package-private test doubles"
  - "PostgresSchemaTestBase.truncateAll() includes the currently-omitted tables (no cross-test pollution)"
  - "IngestSpisLoadTest (which asserts only Class.forName/isInterface — compiler guarantees) is deleted"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider
    - infochat-core/src/test/java/app/zcat/infochat/core
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-159: Test-debt

## Context

Test-only cleanup, consistent with the standing convention (memory
`feedback_avoid_test_inner_classes.md`): six test files carry up to 13 inner-class
fakes that should be top-level package-private doubles; `PostgresSchemaTestBase.truncateAll()`
omits tables (cross-test pollution risk); `IngestSpisLoadTest` ratifies what the
compiler already guarantees (`Class.forName`/`isInterface`) and is dead weight.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. No production source; behavioral assertions unchanged. Deleting
the dead test is authorized here (it asserts nothing the compiler doesn't).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-TEST-INNERCLASS, §C-TRUNCATEALL,
  §C-INGESTSPIS-TEST; `opus-47-full-handout.md` §F-MAINT-86/75/70; `opus-47-only-handout.md` §Si3.
