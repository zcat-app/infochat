---
id: M1-497
title: "Test name/comment accuracy sweep: names and comments that contradict the body"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 7
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Changing production code; this sweep corrects test names/comments and, where a name promises a real assertion the body lacks, adds that assertion."
acceptance:
  - >-
    Each test name/comment is corrected to track what the body asserts (and where
    the name promised coverage the body lacked, the assertion is added):
    (20#F2) PartitionScanSharedSourceTest's javadoc no longer claims a floor
    computation the test does not perform — either it performs it or the javadoc
    is corrected (PartitionScanSharedSourceTest.java:15-56); (25#F1) the
    "no raw control byte" test name/assertion covers the full control range it
    claims (DEL + C1), not only C0 (JsonEscaperTest.java:50-57); (30#F3)
    truncationReportedWhenCapExceeded actually exceeds the cap (or is renamed to
    match its under-cap reality), with a real over-cap sibling retained
    (ExportDataCollectorTest.java:234-257); (30#F4) the "seed an audit row"
    comment matches reality — either the row is seeded or the comment is removed
    (ForgetPurgeServiceTest.java:119-141); (33#F3) the "wait for async processing"
    comment is removed/corrected in front of the synchronous read
    (InboundRouterClearCompressIT.java:96-101); (35#F1) the stale cap comment
    (cap 200 / 205 posts) is corrected to the real cap=5 / 8 posts
    (EligiblePostQueryIT.java:190-194).
  - "mvn -B verify is green from the repo root."
test_plan:
  modifies:
    - "infochat-collector/src/test/java/app/zcat/infochat/collector/eval/PartitionScanSharedSourceTest.java — javadoc tracks the body (or the floor check is added)."
    - "infochat-core/src/test/java/app/zcat/infochat/core/util/JsonEscaperTest.java — assertion covers DEL + C1, matching the name."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/ExportDataCollectorTest.java — truncation test exceeds the cap or is renamed."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/command/ForgetPurgeServiceTest.java — seed comment matches reality."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterClearCompressIT.java — misleading async comment corrected."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java — stale cap comment corrected."
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

# M1-497: Test name/comment accuracy sweep

## Context

From `/deep-code-review full` (2026-06-27), cross-cutting theme **CT6** —
reports `20#F2`, `25#F1`, `30#F3`, `30#F4`, `33#F3`, `35#F1` (verified at source).
Method names, javadoc, and inline comments describe behavior the body does not
exercise (or the inverse): a name claiming "no raw control byte" that checks only
C0, a "truncation reported" test that asserts no truncation, a "seed an audit
row" comment for seeding that never happens, a "wait for async" comment before a
synchronous read, a stale cap comment contradicting the code. Tests are
documentation (CLAUDE.md §Descriptive names); each is corrected to track its
assertion — and where the name promised real coverage, the assertion is added.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. Pre-existing tests modified deliberately (authorized per
engineering-rules §8); no production change.

## Notes

- Source: `/deep-code-review full` (2026-06-27), CT6.
- 25#F1 and 30#F3 may need an added assertion (not just a rename) to make the
  name honest — prefer making the name true over weakening it.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-497-*.md
```
