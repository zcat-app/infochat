---
id: M1-295
title: "Ingest resilience: eval-queue boundary, fetch-ladder scope, parser boundary, re-eval predicate"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 14
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Worker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetch/FetchScheduler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - SmallRye failure-strategy configuration research beyond the boundary catch — the fix is catch-at-boundary plus re-emitter, not channel reconfiguration (both reviewers flagged the framework semantics as uncertain; don't build on them).
  - The D42 ladder thresholds and the saturation tracker — only WHAT counts as a ladder failure changes.
  - Other fetcher parsers — RssFeedParser already validates; only the Reddit parser lacks the boundary check.
  - The outbox/rehydrator itself — the re-emitter reuses its logic, it does not replace it.
acceptance:
  - "U-18: Stage1Worker's @Incoming(\"eval-queue\") consumer cannot throw out of the subscription: the explicit rethrow on SQL load failure (:103-110) and unchecked escapes from the pipeline transaction are caught at the consumer boundary, logged, and swallowed (post stays RAW); no mp.messaging failure-strategy is configured anywhere today, so the outcome of an escape (subscription death vs message drop) is SmallRye-version-dependent — either branch leaves posts stuck RAW until restart; a named test sends a poison key and asserts the consumer survives to process the next key."
  - "U-18 companion: a scheduled stale-RAW re-emitter re-enqueues posts stuck in status='RAW' past a profile-driven age (the startup rehydrator already implements the query shape — reuse it); a named test."
  - "U-19: the D42 per-source failure ladder counts only fetcher.fetch() failures: the single catch (Exception) wrapping fetch+persist+emit (~:287) is split so a DB-side persist/enqueue failure is logged and admin-notified WITHOUT incrementing any source's ladder (today a partition/DB fault increments every active source and can flip them all to terminal failed, each needing manual /source-enable); AssetSnapshotFetcher's existing fetch-vs-persist split in the same module is the shape to follow; a named test injects a persist failure and asserts the ladder is untouched."
  - "U-36: RedditResponseParser validates name at the parse boundary (:81 data.path(\"name\").asText() maps a missing name to \"\") the way RssFeedParser does, so a malformed listing entry is skipped/reported at parse time instead of aborting the whole tick downstream in PostPersister with an 'SPI contract violation'; a named test with a name-less entry."
  - "U-25: ReEvaluationJob.closeQuarantineRows (:374) carries the flagged_by='stage1' predicate its Stage2VerdictHandler twin has (:265) — harmless today, diverges the day any non-stage1 quarantine writer lands; existing re-eval tests updated if they pin the broader UPDATE."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetch
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval
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

# M1-295: Ingest resilience: eval-queue boundary, fetch-ladder scope, parser boundary, re-eval predicate

## Context

Deep-review v5 verified **U-18** (MEDIUM), **U-19** (MEDIUM), **U-36** (LOW),
**U-25** (MEDIUM) (`deep-code-review/v5/UNIFIED-REPORT.md` §3; sources
`fable-5/06#F2` + `gpt-55#M-09`, `fable-5/06#F3` + `gpt-55#M-10`,
`fable-5/06#F6` (unique), `opus-47/06#F2` (unique) — gitignored; all
load-bearing facts inlined; anchors verified 2026-06-11: Stage1Worker
rethrow at :103-110 and zero failure-strategy hits in collector resources;
FetchScheduler catch(Exception) at ~:287; RedditResponseParser:81;
closeQuarantineRows at ReEvaluationJob:195/:374 with the predicate present
only in Stage2VerdictHandler:265).

All four are boundary-discipline fixes in the collector ingest path,
bundled for one review pass.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- U-18's catch is a system-boundary catch (messaging-channel consumer), not
  internal defensive code — keep it at the @Incoming method, not inside the
  pipeline.
- The Stage1Worker javadoc's phantom `mp.messaging…broadcast=true` property
  claim (U-71) is M1-312's; don't fix comments here beyond what the diff
  touches (surgical-changes rule) — but if the boundary-catch edit rewrites
  that javadoc anyway, fixing the phantom reference in place is in-scope as
  an orphan of this change. Note which way it went in the commit message.
- Coordination: M1-312 (doc truth) lists Stage1Worker; whichever lands
  second rebases trivially.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-295-*.md
```
