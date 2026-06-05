---
id: M1-159
title: "Test-debt (inner-class extraction, truncateAll completeness, delete IngestSpisLoadTest)"
status: pending
created: 2026-06-02
last_updated: 2026-06-05
blocked_by: []
files_budget: 40
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
  - the tests' behavioral assertions (doubles may be unified or renamed; what each test asserts is unchanged)
  - test files at or below 3 nested classes today (no extraction sweep beyond the nine named files)
acceptance:
  - "Each of the nine test files exceeding the 3-inner-class guideline (InboundRouterProbationOrderingTest, InboundRouterIntakeOrderingTest, InboundRouterContactIdRedactionTest, InboundRouterConfirmCancelTest, InboundRouterNormalizeTest, DigestWorkerTest, SummaryCommandHandlerTest, AddSourceCommandHandlerTest, AddSourceBanCheckOrderingTest) ends with at most 3 nested class declarations"
  - "Every test double used by two or more of the nine files exists as exactly one top-level package-private class in the test package that uses it; no double class name is declared in more than one file. Same-named doubles with divergent bodies are unified behavior-preserving, or renamed where genuinely different"
  - "PostgresSchemaTestBase.truncateAll() derives its table list at runtime from pg_tables (schemaname = 'public'), excluding flyway_schema_history and the migration-seeded reference tables embedding_metadata and provider_state, and truncates with RESTART IDENTITY CASCADE; the exclusion set and its rationale are documented in a comment at the query site"
  - "IngestSpisLoadTest (which asserts only Class.forName/isInterface/isRecord — compiler guarantees) is deleted"
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
escalations:
  - date: 2026-06-05
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL
      BLOCKERS:
        1. Acceptance item 1 names three test files that do not exist on disk:
           "IntakeOrderingTest", "ContactIdRedactionTest", "ConfirmCancelTest".
           The actual file names are "InboundRouterIntakeOrderingTest",
           "InboundRouterContactIdRedactionTest", "InboundRouterConfirmCancelTest".
           Fix: correct the three names in the acceptance criterion to match the
           files on disk.
revisions:
  - date: 2026-06-05
    reason: clarity-fail rework (wrong file names; budget ~3x under-sized for top-level extraction; truncateAll criterion not independently checkable)
    snapshot:
      status: escalated
      escalation_reason: clarity-fail
      files_budget_at_snapshot: 12
      acceptance_at_snapshot:
        - "The six test files exceeding the 3-inner-class guideline (InboundRouterProbationOrderingTest, IntakeOrderingTest, ContactIdRedactionTest, ConfirmCancelTest, DigestWorkerTest, InboundRouterNormalizeTest) extract their shared fakes to top-level package-private test doubles"
        - "PostgresSchemaTestBase.truncateAll() includes the currently-omitted tables (no cross-test pollution)"
        - "IngestSpisLoadTest (which asserts only Class.forName/isInterface — compiler guarantees) is deleted"
        - "mvn -B clean verify from the repo root exits 0"
      out_of_scope_at_snapshot:
        - production source files (test-only ticket)
        - the tests' behavioral assertions (extraction is mechanical; assertions unchanged)
clarity_check: {}
---

# M1-159: Test-debt

## Context

Test-only cleanup, consistent with the standing convention (memory
`feedback_avoid_test_inner_classes.md`). Nine test files exceed the 3-inner-class
guideline: the six audit-named ones (InboundRouterProbationOrderingTest 13,
InboundRouterIntakeOrderingTest 11, InboundRouterContactIdRedactionTest 10,
InboundRouterConfirmCancelTest 8, DigestWorkerTest 8, InboundRouterNormalizeTest 7)
plus three found by the refine-time guideline sweep (SummaryCommandHandlerTest 5,
AddSourceCommandHandlerTest 4, AddSourceBanCheckOrderingTest 4). Fifteen
messaging-package double names and three command-package double names
(UnsupportedDataSource, StubUserDataSource, RecordingUrlProbe) are duplicated
across 2–4 files each; some duplicates have divergent bodies (CallLog,
FakeBundleLoader, FakeBanCheck), so extraction is unify-or-rename, not pure
cut-paste — behavioral assertions stay unchanged either way.

`PostgresSchemaTestBase.truncateAll()` truncates only 5 of the 29 migration-created
parent tables (cross-test pollution risk). The fix derives the table list from
`pg_tables` at runtime so future `CREATE TABLE`s cannot reintroduce the drift.
Two tables carry migration-seeded reference rows (`embedding_metadata` from V11,
`provider_state` from V9/V21) and must be excluded alongside
`flyway_schema_history`.

`IngestSpisLoadTest` ratifies what the compiler already guarantees
(`Class.forName`/`isInterface`/`isRecord`) and is dead weight.

Budget: 9 modified test files + PostgresSchemaTestBase + ~24 extracted top-level
doubles + 1 deletion ≈ 35 touched; 40 leaves headroom for unify-vs-rename splits.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. No production source; behavioral assertions unchanged. Deleting
the dead test is authorized here (it asserts nothing the compiler doesn't).

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §C-TEST-INNERCLASS, §C-TRUNCATEALL,
  §C-INGESTSPIS-TEST; `opus-47-full-handout.md` §F-MAINT-86/75/70; `opus-47-only-handout.md` §Si3.
- 2026-06-05 refine: corrected three file names (the audit shorthand "IntakeOrderingTest"
  etc. lacks the `InboundRouter` prefix the files carry on disk); widened scope to the
  three command-package files the guideline sweep found; re-sized budget from 12 (which
  implicitly priced a nested-holder pattern) to 40 (top-level extraction per convention).
