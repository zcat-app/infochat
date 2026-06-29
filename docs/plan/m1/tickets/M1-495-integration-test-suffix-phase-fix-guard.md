---
id: M1-495
title: "Integration/DB-boot tests named *Test run in the surefire (unit) phase"
status: done
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 14
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Genuine unit tests that happen to end in *Test (e.g. the non-DevServices siblings of QuarantineReviewListenerTest) — only DevServices/full-boot @QuarkusTest classes are renamed."
  - >-
    The ~89 pre-existing DB-backed @QuarkusTest classes already named *Test
    (BanCommandHandlerTest, ProbationCheckTest, GroupRepositoryTest, the
    *CommandHandlerTest / eval-worker / chat-tool families, etc.) are NOT
    renamed by this ticket. They are recorded verbatim in the guard's frozen
    baseline allowlist as known, accepted debt. Whether the project later
    renames them all to *IT, or formally blesses DB-backed @QuarkusTest as
    *Test, is a separate convention decision outside this ticket's scope.
acceptance:
  - >-
    The five DevServices-Postgres @QuarkusTest classes the deep-review (CT3,
    reports 22#F2 / 34#F1 / 35#F2) named are renamed from *Test to *IT so they
    run in failsafe/verify, not surefire: LinkingJobTest -> LinkingJobBehaviorIT
    (NOT LinkingJobIT — that name already exists as the onTick end-to-end test;
    LinkingJobBehaviorIT matches this class's own javadoc, "each test pins one
    observable behaviour"), Kind6HandlerTest -> Kind6HandlerIT,
    NostrSinceCursorTest -> NostrSinceCursorIT (collector),
    QuarantineReviewListenerTest -> QuarantineReviewListenerIT (provider
    outbox), and EligiblePostQueryStatementTimeoutTest ->
    EligiblePostQueryStatementTimeoutIT (provider summary). Their behavior and
    assertions are unchanged; only the class name and the references to it
    change. References include the compile-critical
    @TestProfile(LinkingJobTest.WideLookbackProfile.class) in LinkingJobIT and
    LinkingJobSemanticProbeIT (the nested profile moves to
    LinkingJobBehaviorIT.WideLookbackProfile) and the now-stale class-name
    mentions in javadoc/comments (LinkingJob.java, NostrStreamSource.java,
    DigestPostCollectorIT.java, ExportDataCollectorTest.java,
    application.properties) that the rename orphans.
  - >-
    A build-time regression guard prevents NEW instances of this whole class:
    a unit test (IntegrationTestNamingGuardTest — itself NOT a @QuarkusTest, it
    only walks the on-disk test sources of every infochat-* module) collects
    every *Test-named class that is @QuarkusTest AND injects a DataSource /
    @SeedDataSource, and asserts that set is a SUBSET of a frozen baseline
    allowlist checked into the repo. The build fails when a future
    integration-shaped class is named *Test (it appears in the found set but
    not the baseline). The baseline holds exactly the ~89 pre-existing
    accepted-debt classes (NOT the five renamed above); renaming any of them to
    *IT later only shrinks the found set, which the subset assertion tolerates
    without a guard edit.
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-core/src/test/java/app/zcat/infochat/core/testsupport/IntegrationTestNamingGuardTest.java"
    - "infochat-core/src/test/resources/integration-test-naming-baseline.txt"
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
      files: 16
      added: 407
      removed: 38
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-29
  verdict: PASS
  warnings: []
  blockers: []
escalations:
  - date: 2026-06-29
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise-fail discovered during start-time implementation grounding,
      before any branch/diff. The guard criterion in original acceptance item 2
      ("no @QuarkusTest injecting a DataSource / @SeedDataSource carries a
      *Test name") matches ~94 pre-existing classes (89 + the 5 renamed), not
      the 5 the ticket renames, so it would fail the build on classes original
      acceptance item 1 does not touch — items 1, 2, and 3 (mvn verify green)
      were mutually unsatisfiable as written. Resolved by refine (subset-of-
      frozen-baseline ratchet guard); see revisions[0].
revisions:
  - date: 2026-06-29
    reason: premise-fail refine
    note: |
      Original acceptance assumed only 5 DB-backed @QuarkusTest classes were
      named *Test and a guard forbidding "@QuarkusTest + DataSource named
      *Test" would catch just that class. In fact ~89 OTHER such classes exist
      (the project's dominant pattern for DB-backed component tests), so the
      absolute guard would have failed the build on 89 classes the ticket does
      not rename. Refined: guard now asserts the found set is a SUBSET of a
      frozen 89-entry baseline allowlist (regression ratchet, not absolute
      ban); out_of_scope records the 89 as accepted debt; files_budget 8->14
      (adds baseline resource + the orphaned javadoc/comment reference fixes);
      complexity low->medium (filesystem-walking guard + baseline).
    snapshot:
      files_budget: 8
      complexity: low
      acceptance:
        - >-
          The full-boot @QuarkusTest classes that hit DevServices Postgres but
          carry a *Test suffix are renamed to *IT so they run in
          failsafe/verify, not surefire: LinkingJobTest, Kind6HandlerTest,
          NostrSinceCursorTest (collector), QuarantineReviewListenerTest
          (provider outbox), and EligiblePostQueryStatementTimeoutTest
          (provider summary). Their behavior and assertions are unchanged; only
          the class name (and references) change.
        - >-
          A build-time guard prevents regressions of this whole class: a test
          (or enforcer rule) asserts that no @QuarkusTest injecting a
          DataSource / @SeedDataSource carries a *Test name. The guard fails
          the build if a future integration-shaped class is named *Test.
        - "mvn -B verify is green from the repo root."
      out_of_scope:
        - "Genuine unit tests that happen to end in *Test (e.g. the non-DevServices siblings of QuarantineReviewListenerTest) — only DevServices/full-boot @QuarkusTest classes are renamed."
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

**Premise-fail refine (2026-06-29).** The original acceptance assumed an
absolute guard ("no `@QuarkusTest` injecting a DataSource carries a `*Test`
name") would catch exactly this five-class anomaly. A pre-implementation scan
found ~89 OTHER DB-backed `@QuarkusTest` classes already named `*Test` — this is
the project's dominant pattern for DB-backed component tests, not a five-instance
anomaly. An absolute guard would have failed the build on all 89, contradicting
the rename-only-five scope and the green-build acceptance. The guard is therefore
a **regression ratchet**: it freezes the 89 pre-existing classes into a baseline
allowlist and fails only when a NEW integration-shaped class is named `*Test`
(found set ⊄ baseline). See `revisions[0]` for the original spec.

## Acceptance

See frontmatter. Rename the five to `*IT` (and fix every reference the rename
orphans), then add the subset-of-baseline regression guard.

## Out-of-scope

See frontmatter. Genuine unit `*Test` classes are not renamed; neither are the
~89 pre-existing DB-backed `@QuarkusTest` `*Test` classes (they are the guard's
frozen baseline of accepted debt).

## Notes

- Source: `/deep-code-review full` (2026-06-27), CT3 (22#F2, 34#F1, 35#F2).
- The guard is the durable fix; the renames are the immediate cleanup.
- The guard scans on-disk test sources (it is a plain unit test, no DevServices)
  so a single test in `infochat-core` can see every module's test tree; it locates
  the repo root by walking up from the working directory.
- The baseline (`integration-test-naming-baseline.txt`) must be generated with the
  exact same detection the guard applies, or the subset assertion will mismatch.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-495-*.md
```
