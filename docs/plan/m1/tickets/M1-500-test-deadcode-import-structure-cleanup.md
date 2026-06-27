---
id: M1-500
title: "Test dead-code, dead-import, and structure cleanup sweep"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 9
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Changing what any of these tests assert beyond removing dead scaffolding and splitting one over-bundled test; no production change."
acceptance:
  - >-
    Each test-side dead-code/structure nit is cleaned without weakening coverage:
    (26#F2) dead imports removed from OpenAiCompatibleProviderTest.java:7,10;
    (29#F2) dead imports removed from SummaryAnchorRepositoryTest.java lines 13,15,16,17 (NOT line 14 — SQLFeatureNotSupportedException is used at line 191; falsification correction to the report's 13-16 range);
    (28#F1) the dead consecutiveCrlf counter removed from
    FakeSimpleXProcess.java:231-251; (31#F1) the dead/misnamed BlockingDataSource
    scaffolding removed and the per-group serialization test no longer asserts
    circularly (DigestRetryServiceTest.java:103-147,177-193); (32#F1)
    GoldenPathJourneyIT uses the imported assertNull instead of reimplementing it
    (GoldenPathJourneyIT.java:572-574); (32#F2) DigestWorkerTest stops passing
    null into the non-null GroupRepository constructor — it uses a real/stub
    DataSource or removes the unused repo (DigestWorkerTest.java:77-79); (28#F2)
    the encodesAndDecodesMessages mega-test is split into behavior-named tests,
    one per contract (SimpleXMessageCodecTest.java:27-126).
  - "mvn -B verify is green from the repo root."
test_plan:
  modifies:
    - "infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderTest.java — drop dead imports."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/chat/SummaryAnchorRepositoryTest.java — drop dead imports."
    - "infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java — remove dead consecutiveCrlf."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRetryServiceTest.java — remove BlockingDataSource; fix circular assertion."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/journey/GoldenPathJourneyIT.java — use imported assertNull."
    - "infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java — stop passing null into GroupRepository ctor."
    - "infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java — split the mega-test into behavior-named tests."
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

# M1-500: Test dead-code, dead-import, and structure cleanup sweep

## Context

From `/deep-code-review full` (2026-06-27), reports `26#F2`, `28#F1`, `28#F2`,
`29#F2`, `31#F1`, `32#F1`, `32#F2` (verified at source) — test-side dead imports,
dead scaffolding (BlockingDataSource, consecutiveCrlf), a circular assertion, a
reimplemented `assertNull`, a `null` passed into a non-null constructor, and one
test method bundling seven unrelated contracts. Low-severity test hygiene,
bundled as one sweep.

## Acceptance

See frontmatter — remove the dead scaffolding/imports, fix the circular and
null-ctor smells, and split the seven-in-one test into behavior-named tests, all
without weakening coverage.

## Out-of-scope

See frontmatter. No production change; pre-existing tests modified deliberately
(authorized per engineering-rules §8).

## Notes

- Source: `/deep-code-review full` (2026-06-27), reports 26#F2, 28#F1, 28#F2,
  29#F2, 31#F1, 32#F1, 32#F2.
- 31#F1: removing `BlockingDataSource` requires the per-group serialization test
  to assert on the real `inFlight` short-circuit rather than the dead wrapper.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-500-*.md
```
