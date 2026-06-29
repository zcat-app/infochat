---
id: M1-523
title: "Rename DB-boot GroupJoinRepositoryTest to *IT (M1-519 naming-guard trunk-red fix)"
status: done
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 1
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupJoinRepositoryTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Any change to the test's behavior — this is a pure rename (file + class identifier) so the DB-boot @QuarkusTest runs in the failsafe (integration) phase instead of surefire (unit)."
  - "GroupJoinRepository production code and any other M1-519 artifact (V55 migration, GroupInvitationHandler) — untouched."
  - "Any other naming-guard offender or the baseline file — this fixes only the single new-drift offender M1-519 introduced."
acceptance:
  - >-
    infochat-provider/.../group/GroupJoinRepositoryTest.java is renamed to
    GroupJoinRepositoryIT.java and its top-level type renamed from
    `class GroupJoinRepositoryTest` to `class GroupJoinRepositoryIT`. The rename
    uses `git mv` so history follows the file. No other edit to the file body.
  - >-
    IntegrationTestNamingGuardTest.noNewIntegrationShapedTestIsNamedTest passes:
    the offender list no longer contains
    app.zcat.infochat.provider.group.GroupJoinRepositoryTest, restoring trunk
    green. The renamed IT runs in the failsafe phase and its three tests
    (recordedJoinIsCountedByInviterAndGlobally, duplicateJoinForSameGroupCountsOnce,
    recordedJoinIsCommittedAndReadableOnAFreshConnection) pass against DevServices.
  - "mvn -B verify is green from the repo root."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/group/GroupJoinRepositoryTest.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-29
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 94
      removed: 5
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-523: Rename DB-boot GroupJoinRepositoryTest to *IT (M1-519 naming-guard trunk-red fix)

## Context

M1-519 (a71e1ccf, current origin/main tip) introduced
`GroupJoinRepositoryTest` — a `@QuarkusTest` that injects
`@SeedDataSource DataSource` and boots DevServices Postgres — named `*Test`
and not listed in the M1-495 integration-test naming baseline. The M1-495
naming-ratchet guard (`IntegrationTestNamingGuardTest`, in infochat-core)
correctly flags it as new drift: a DB-boot `@QuarkusTest` named `*Test` runs
in the surefire unit phase instead of failsafe, so the build fails. This
leaves origin/main red — `mvn verify` aborts in infochat-core's surefire
phase before infochat-provider builds — blocking every downstream ticket.

This is the same resolution M1-495 applied to its DB-boot offenders: rename
`*Test` → `*IT`. `GroupJoinRepositoryIT` is free (no name collision). The
test is genuinely an integration test (real DataSource, real repository,
`ON CONFLICT` SQL, cross-connection durability read), so the baseline-add
escape (for genuine unit tests only) does not apply — rename is the correct
fix.

## Acceptance

See frontmatter. Pure `git mv` rename of file + top-level class identifier;
no behavioral change. Full suite green, naming guard passes.

## Out-of-scope

See frontmatter. No production-code change; no touch to any other M1-519
artifact or naming-guard offender.

## Notes

- Source: trunk-red discovered while running M1-520; escaped M1-519's merge
  gate. Filed and run as its own ticket per the surgical-changes rule (don't
  fix an unrelated M1-519 defect inline in M1-520).
- Reference precedent: M1-495 (rename DB-boot `@QuarkusTest *Test` → `*IT`).
