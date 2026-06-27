---
id: M1-499
title: "Test fixture duplication → testsupport, plus a leaked registration teardown"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 16
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Changing the behavior any of these tests assert; this is extraction/dedup and a missing teardown only."
acceptance:
  - >-
    Each duplicated test fixture below lives once in its module's
    testsupport/testing package and is consumed by every former copy, with no
    change to what the tests assert: (21#F2) the Postgres LISTEN/NOTIFY await
    fixture (QuarantinePendingNotifyIT, Stage2BenignNotifyScopeIT); (24#F3) the
    MutableClock double (ThrottledAdminNotifierTest,
    ThrottledAdminNotifierFallbackThrottleTest); (27#F1) the Signal recording
    inbound/membership doubles + parse helper (signal test package, ~multiple
    files); (28#F3) the SimpleX adapter-over-fake harness (newAdapter/ackFrame)
    (SimpleXEditFallbackTest, SimpleXEditFallbackMetricsTest,
    SimpleXAdapterChunkedSendTest); (29#F1) the CountingRecordingDataSource copy
    in SearchPostsToolTest replaced by the shared one; (31#F2) the
    reflective bundle-loader/translation-pipeline fixtures (load() reflection +
    newEnShortCircuitPipeline) consolidated to one helper; (34#F2) the outbox IT
    fixture helpers (resetNewPostCursor/clearAllItPosts/ensureTestSource/awaitCursor)
    across the NewPost*IT files; (24#F2) the pgvector image tag pinned in one
    place consumed by both PostgresSchemaTestBase and the dev-services config.
  - >-
    (23#F2) NostrSourceDisabledStopsWorkerIT no longer leaks its OTHER_KEY
    supervisor registration — an @AfterEach (or equivalent) tears it down so the
    shared application-scoped bean is clean between tests.
  - "mvn -B verify is green from the repo root."
test_plan:
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

# M1-499: Test fixture duplication → testsupport, plus a leaked registration teardown

## Context

From `/deep-code-review full` (2026-06-27), cross-cutting theme **CT4** plus one
hygiene finding — reports `21#F2`, `24#F2`, `24#F3`, `27#F1`, `28#F3`, `29#F1`,
`31#F2`, `34#F2`, `23#F2` (verified at source; verification noted 27#F1 and 31#F2
are in fact wider than the cited file counts). Non-trivial named test scaffolding
is copy-pasted across sibling test files instead of extracted to the
`testsupport`/`testing` package each module already maintains (cf.
`FakeSignalCli`, `CountingRecordingDataSource`, `SeedDataSource`), so a single fix
to subtle concurrency/wire-contract logic must land in every copy. 23#F2 is a
leaked supervisor registration with no teardown.

## Acceptance

See frontmatter — extract each duplicated fixture to the module's shared package
and add the missing teardown, behavior unchanged.

## Out-of-scope

See frontmatter. No assertion changes; pre-existing tests modified deliberately
(authorized per engineering-rules §8).

## Notes

- Source: `/deep-code-review full` (2026-06-27), CT4 + 23#F2.
- The shared `CountingRecordingDataSource` already exists — 29#F1 just deletes the
  nested copy and imports it.
- Large file count by nature of a dedup sweep; consider splitting by module if the
  reviewer prefers.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-499-*.md
```
