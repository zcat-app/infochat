---
id: M1-082
title: Relocate ThrottledAdminNotifier to infochat-core
status: done
created: 2026-05-25
last_updated: 2026-05-26
clarity_check:
  date: 2026-05-25
  verdict: WARN
  warnings:
    - "AssetSnapshotFetcherTest.java missing from test_plan.modifies (fixed)"
    - "V22 GRANT overlaps M1-081a V21 — harmless no-op if M1-081a lands first"
escalations:
  - date: 2026-05-25
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — escalation is from budget-breach before implementation.
      M1-081a landed between ticket creation and start, adding 3 new importers
      of collector.notifier.ThrottledAdminNotifier (PerSourceUnknownTracker,
      ReEvaluationJob, TaggerWorker) plus their test files (6 files total).
      files_budget=7 and files_scope cover only 7 files; updating all importers
      requires touching 13 files. Additionally, V22 is taken (by M1-081a's
      V22__post_stage2_verdict.sql) — migration must be renumbered to V23.
revisions:
  - date: 2026-05-25
    reason: budget-breach refine
    prior_files_budget: 7
    prior_files_scope:
      - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
      - infochat-core/src/main/java/app/zcat/infochat/core/notifier/AdminNotificationRecord.java
      - infochat-core/src/main/java/app/zcat/infochat/core/notifier/NotifyOutcome.java
      - infochat-core/src/test/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifierTest.java
      - infochat-core/src/main/resources/db/migration/V22__provider_notification_grants.sql
      - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
      - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcherTest.java
    prior_migration_touch: true
    changes: |
      M1-081a landed 3 new importers of collector.notifier (PerSourceUnknownTracker,
      ReEvaluationJob, TaggerWorker + their tests). files_budget 7→12, files_scope
      expanded by 6 entries. V21 already contains the provider GRANT on
      admin_notification_state — migration file dropped, migration_touch→false.
      Title updated (removed "provider notification grants" — no longer applicable).
blocked_by: []
files_budget: 16
files_scope:
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifier.java
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/AdminNotificationRecord.java
  - infochat-core/src/main/java/app/zcat/infochat/core/notifier/NotifyOutcome.java
  - infochat-core/src/test/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifierTest.java
  - infochat-core/pom.xml
  - infochat-core/src/test/resources/application.properties
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcherTest.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTracker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TaggerWorker.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ConfirmStateService.java
  - docs/plan/m1/tickets/M1-080c-retry-digest-and-missed-slot-notify.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any behavioral change to ThrottledAdminNotifier — method signatures, SQL, log format, throttle semantics all stay identical; this ticket changes only the package declaration and import paths
  - any new caller of ThrottledAdminNotifier — M1-080c (digest missed-slot notification) and future provider-side callers adopt the notifier in their own tickets
  - any change to V16__admin_notification_state.sql — the original migration is FROZEN
  - any change to V21__quarantine_admin.sql — the provider GRANT is already in V21; no new migration needed
  - any change to ThrottledAdminNotifier's @ConfigProperty or defaultValue — the throttle-window configuration stays unchanged
  - any modification to pre-existing tests NOT listed in files_scope beyond import-path updates
acceptance:
  - "ThrottledAdminNotifier.java, AdminNotificationRecord.java, and NotifyOutcome.java exist under infochat-core/src/main/java/app/zcat/infochat/core/notifier/ with package declaration `package app.zcat.infochat.core.notifier;` — no other behavioral change from the M1-058 versions"
  - "The three files no longer exist under infochat-collector/src/main/java/app/zcat/infochat/collector/notifier/"
  - "ThrottledAdminNotifierTest.java exists under infochat-core/src/test/java/app/zcat/infochat/core/notifier/ with package declaration `package app.zcat.infochat.core.notifier;` — all five existing test methods (firstCallEmitsAndPersists, withinWindowSuppresses, afterWindowEmitsAgain, concurrentNotifyOnceRaceSafe, and the getState test) pass unchanged"
  - "The test file no longer exists under infochat-collector/src/test/java/app/zcat/infochat/collector/notifier/"
  - "Every .java file in files_scope that imports from `app.zcat.infochat.collector.notifier` is updated to import from `app.zcat.infochat.core.notifier` — specifically AssetSnapshotFetcher, AssetSnapshotFetcherTest, PerSourceUnknownTracker, ReEvaluationJob, TaggerWorker, PerSourceUnknownTrackerTest, ReEvaluationJobTest, and TaggerWorkerTest"
  - "No grep hit for `collector.notifier` in any .java file under infochat-collector/src/ or infochat-core/src/ — the old package is fully vacated"
  - "mvn -B clean verify from the repo root exits 0; every prior test currently green on main continues to pass"
test_plan:
  adds: []
  modifies:
    - infochat-core/src/test/java/app/zcat/infochat/core/notifier/ThrottledAdminNotifierTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/PerSourceUnknownTrackerTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/tagger/TaggerWorkerTest.java
  preserves:
    - all tests currently green on main
    - all five ThrottledAdminNotifierTest methods pass with identical assertions
spec_refs: []
reviews:
  - round: 1
    date: 2026-05-26
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 18
      added: 246
      removed: 34
  - round: 2
    date: 2026-05-26
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 18
      added: 271
      removed: 34
decision_refs:
  - D22
---

# M1-082: Relocate ThrottledAdminNotifier to infochat-core

## Context

M1-058 landed ThrottledAdminNotifier in `infochat-collector` because
its initial callers (Stage 1/2 pipeline, tagger, embedding) are all
collector-side. However, the spec and design expect provider-side
usage too:

- `docs/spec/commands.md` §Periodic group digests — the digest
  overload signal is "the throttled admin notification already in
  security.md §Failure handling."
- `docs/design/06-messaging.md` — adapter connection failures and
  auth-failure terminal transitions trigger the provider's admin
  notifier.

The provider module does not depend on the collector module (correct
by architecture — they are separate services). Moving the notifier to
`infochat-core` (which both services depend on) makes it available to
both without creating a wrong-direction dependency.

M1-081a's V21 already grants INSERT, UPDATE on
`admin_notification_state` to `infochat_provider`, so no new migration
is needed — the original V22 migration in this ticket is dropped.

## Acceptance

1. Three production files move from `collector.notifier` to
   `core.notifier` — package declaration change only, no behavioral
   change.
2. Test file moves to `infochat-core` test tree — all five test
   methods pass unchanged.
3. All existing importers of `collector.notifier` (AssetSnapshotFetcher,
   PerSourceUnknownTracker, ReEvaluationJob, TaggerWorker, plus their
   tests) update their imports to `core.notifier`.
4. No residual `collector.notifier` references in any `.java` file.
5. `mvn verify` is green.

## Out-of-scope

- Any behavioral change to the notifier.
- Any new caller (M1-080c wires DigestScheduler; future tickets wire
  the pipeline stages).
- Any change to V16 or V21.

## Authorized test changes

- `ThrottledAdminNotifierTest.java` is relocated (package declaration
  change). No assertion or method-body change. The test moves from
  the collector test tree to the core test tree.
- `AssetSnapshotFetcherTest.java` has import lines updated
  (`collector.notifier` → `core.notifier`). No assertion or
  method-body change.
- `PerSourceUnknownTrackerTest.java`, `ReEvaluationJobTest.java`,
  `TaggerWorkerTest.java` each have one import line updated
  (`collector.notifier` → `core.notifier`). No assertion or
  method-body change.

## Notes

- `infochat-core` already owns V16 (the table definition). Placing
  the Java code next to the migration's module is natural.
- This is a pure mechanical move with four existing callers and zero
  behavioral changes.

## Round 1 rework

1. Expand files_budget 12→16 and add 4 orphan-infrastructure files to
   files_scope: infochat-core/pom.xml, application.properties (test),
   ConfirmStateService.java (duplicate Clock producer removal),
   M1-080c ticket (blocked_by dependency). All are caused by the move.
