---
id: M1-499
title: "Test fixture duplication → testsupport, plus a leaked registration teardown"
status: done
created: 2026-06-27
last_updated: 2026-06-29
blocked_by: []
files_budget: 51
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Changing the behavior any of these tests assert; this is extraction/dedup and a missing teardown only."
  - "(34#F2) the outbox-IT awaitCursor helper — its five copies have diverged (nanoTime vs Instant deadline, vacuous assertNotNull in two, different cursor channel, per-file timeout/poll); unifying it requires assertion/timing normalization this ticket's out_of_scope forbids. Split to M1-524."
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
    fixture helpers (resetNewPostCursor/clearAllItPosts/ensureTestSource)
    across the NewPost*IT files — awaitCursor excluded, see out_of_scope and
    M1-524; (24#F2) the pgvector image tag pinned in one
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
      files: 53
      added: 515
      removed: 900
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
revisions:
  - date: 2026-06-29
    reason: >-
      budget-breach (foreknown pre-implementation). On-disk enumeration of all
      nine findings totals ~45 modified+new files; the original files_budget of
      16 cannot hold the full sweep. User authorized a single-sweep refine over
      a per-module decompose (AskUserQuestion, 2026-06-29). Widened files_budget
      16 -> 46, then corrected to 51 once the sweep was implemented: 27#F1's
      parse helper proved to live in 12 files (not the ~6 estimated) and 31#F2's
      bundle-loader in 7 (not ~4), so the actual touch is 51 source files (8 new
      shared fixtures + 43 modified). The "one sweep" authorization covers the
      corrected count; no acceptance/out_of_scope/behavioral change.
    snapshot:
      files_budget: 16
  - date: 2026-06-29
    reason: >-
      premise-fail (34#F2 awaitCursor). Source inspection showed the five
      awaitCursor copies are NOT clean duplicates: they diverge in deadline
      mechanism (System.nanoTime vs Instant.now), a vacuous assertNotNull on an
      Optional present in two copies, the cursor channel, and per-file
      AWAIT_TIMEOUT/AWAIT_POLL. Unifying them requires assertion/timing
      normalization this ticket's out_of_scope forbids, so acceptance (extract
      awaitCursor) conflicts with out_of_scope (no assertion change). User chose
      "extract 3 clean, defer awaitCursor" (AskUserQuestion, 2026-06-29):
      narrowed 34#F2 to {clearAllItPosts, resetNewPostCursor, ensureTestSource},
      added an awaitCursor out_of_scope carve-out, and split awaitCursor to the
      new M1-524. The other three helpers extract with no behavior change.
    snapshot:
      acceptance_34F2_helpers: resetNewPostCursor/clearAllItPosts/ensureTestSource/awaitCursor
escalations:
  - date: 2026-06-29
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — budget-breach foreknown before the first edit (clarity WARN
      FILES-BUDGET-PLAUSIBLE; ~45 files vs files_budget 16). Resolved via
      refine: widen files_budget to 46 and implement as one sweep.
  - date: 2026-06-29
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise-fail found by source inspection (34#F2 awaitCursor copies
      have diverged; clean extraction conflicts with out_of_scope). Resolved via
      refine: narrow 34#F2 to the three genuinely-duplicated helpers, split
      awaitCursor to M1-524.
clarity_check:
  date: 2026-06-29
  verdict: WARN
  warnings:
    - >-
      FILES-BUDGET-PLAUSIBLE: files_budget of 16 is likely under-estimated for
      nine extraction tasks (rough minimum ~26 files). Verify or split by module
      before the first implementation round to avoid a budget-breach escalation
      mid-ticket.
  blockers: []
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
