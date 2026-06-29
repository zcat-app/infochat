---
id: M1-517
title: "Schema: NOT NULL post.upstream_identifier + backfill 37 test fixtures"
status: done
created: 2026-06-29
last_updated: 2026-06-29
blocked_by: []
files_budget: 42
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
decomposed_from: M1-493
out_of_scope:
  - "F2 (approve_quarantine phantom new_post NOTIFY guard) — split to M1-516. Do NOT touch approve_quarantine or any stored procedure here."
  - "Do NOT weaken the constraint with a column DEFAULT or a BEFORE-INSERT trigger to avoid editing fixtures — that masks the spec contract (forbidden shim) and defeats the point. Each fixture insert must supply a real non-null upstream_identifier."
acceptance:
  - >-
    post.upstream_identifier is NOT NULL, matching docs/spec/schema.md §UID
    derivation (every Fetcher/StreamSource MUST produce a non-null
    upstream_identifier; ID-less items are rejected at the Fetcher boundary).
    V7__joins_post.sql:139 currently declares it nullable and no later
    migration (V8-V52) adds the constraint. Lands via a forward migration
    (ALTER TABLE post ALTER COLUMN upstream_identifier SET NOT NULL).
  - >-
    Every pre-existing INSERT INTO post in the test suite that omitted
    upstream_identifier (44 sites across the 37 files listed in §Notes) is
    updated to supply a non-null upstream_identifier, so all tests currently
    green on main stay green under the new constraint. Production code
    (PostPersister) already supplies it.
  - >-
    Test: an INSERT INTO post with a NULL upstream_identifier is rejected with
    SQLState 23502 (not_null_violation).
  - "mvn -B verify is green from the repo root."
test_plan:
  adds:
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/UpstreamIdentifierNotNullIT.java"
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/partition/PartitionInsertIT.java"
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/PartitionHorizonInsertIT.java"
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/PerScopeIsolationIT.java"
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/PostPartitioningTest.java"
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/QuarantineActorCheckTest.java"
    - "infochat-core/src/test/java/app/zcat/infochat/core/schema/SoftDeletedSourceFkTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPostToolTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetReferencesToolTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolClockTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerGroupScopeIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCapConcurrencyIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnsaveCommandHandlerTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/journey/GoldenPathJourneyIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterStopRetryIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostHandlerHardeningIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostHandlerRollbackIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerReconcileOnReconnectIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerReconnectIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostReconcilerIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostReconcilerPagingIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostReconcilerSingleClockIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewCursorNotifyAtomicityIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewListenerTest.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewReconcileOnReconnectIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryClockIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java"
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineIT.java"
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §UID derivation
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
      files: 42
      added: 269
      removed: 102
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

# M1-517: NOT NULL post.upstream_identifier + backfill 37 test fixtures

## Context

Split from M1-493 (decomposed: budget-breach) — this is finding 19#F1 from
`/deep-code-review full` (2026-06-27). `post.upstream_identifier` is nullable
(`V7__joins_post.sql:139`), contradicting `docs/spec/schema.md` §UID
derivation, which mandates a non-null `upstream_identifier` for every
post (ID-less items are rejected at the Fetcher boundary; the SPI type
`NormalizedPost.upstreamIdentifier` is non-nullable). The gap is masked
today by the non-null `uid` + UNIQUE(uid, fetched_at), but the storage
layer does not back the contract.

M1-493 budgeted 5 files for this assuming a surgical migration. The real
blast radius is much larger: **44 `INSERT INTO post` sites across 37 test
files omit `upstream_identifier`** and would fail with 23502 once the
constraint lands. `test_plan.preserves` (all green tests stay green) forces
each to be backfilled with a non-null value. Production code is unaffected —
`PostPersister` (the only `src/main` inserter) already supplies it.

## Acceptance

See frontmatter. Add the NOT NULL via a forward migration (next free version
is V53+; current head is V52), add a new IT asserting a NULL insert is
rejected with SQLState 23502, and update each of the 37 fixtures below to
supply a non-null `upstream_identifier` in its `INSERT INTO post`.

## Out-of-scope

See frontmatter. F2 (the approve_quarantine phantom NOTIFY guard) is M1-516.
Do NOT use a column DEFAULT or trigger to dodge the fixture edits.

## Notes

- Source: `/deep-code-review full` (2026-06-27), report 19#F1.
- `migration_touch: true`; forward migration only.
- `files_budget: 42` = 1 forward migration + 1 new IT + 37 fixture files +
  ~3 margin. `risk: medium` reflects the broad (but mechanical) test churn,
  not data risk — M1 is greenfield, migrations run against a fresh schema
  with no pre-existing NULL rows (M1-493 clarity WARN noted, then dismissed,
  a production-NULL-row concern that does not apply here).
- The 37 fixture files needing an `upstream_identifier` value added to their
  `INSERT INTO post` (verified by grep 2026-06-29; column lists that already
  include `upstream_identifier` are NOT in this list):
  - infochat-collector/src/test/java/app/zcat/infochat/collector/partition/PartitionInsertIT.java
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/PartitionHorizonInsertIT.java
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/PerScopeIsolationIT.java
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/PostPartitioningTest.java (x2 inserts)
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/QuarantineActorCheckTest.java
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/SoftDeletedSourceFkTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetPostToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/GetReferencesToolTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolClockTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/SearchPostsToolTest.java (x3 inserts)
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RetryCommandHandlerGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCapConcurrencyIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SavedLibraryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/UnsaveCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRoundtripIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/journey/GoldenPathJourneyIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterStopRetryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostHandlerHardeningIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostHandlerRollbackIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerReconcileOnReconnectIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostListenerReconnectIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostReconcilerIT.java (x2 inserts)
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostReconcilerPagingIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/NewPostReconcilerSingleClockIT.java (x2 inserts)
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewCursorNotifyAtomicityIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewListenerTest.java (x2 inserts)
  - infochat-provider/src/test/java/app/zcat/infochat/provider/outbox/QuarantineReviewReconcileOnReconnectIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/quarantine/QuarantineWorkflowIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryClockIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java (x2 inserts)
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineIT.java
- Each edit is mechanical: add `upstream_identifier` to the column list and a
  matching value (e.g. derived from the existing uid) to the VALUES. Re-run
  the grep before review to confirm zero remaining nullable inserts.
- The "44 sites across 37 files" count in acceptance #2 was taken from a grep
  on 2026-06-29 that predated M1-478 (merged the same day, 17:24), which added
  a 38th omitting insert site in RetryCommandHandlerGroupScopeIT. That file is
  fixed too — acceptance #2's operative clause ("every pre-existing INSERT INTO
  post that omitted upstream_identifier is updated ... so all tests currently
  green on main stay green") and `preserves` both mandate it, and the total
  (1 migration + 1 IT + 38 fixtures = 40) stays within `files_budget: 42`. No
  files_budget/files_scope/out_of_scope change.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-517-*.md
```
