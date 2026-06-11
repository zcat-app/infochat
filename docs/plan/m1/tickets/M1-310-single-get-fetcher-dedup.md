---
id: M1-310
title: "Collector dedup: one fetchAndParse helper for the four single-GET fetchers"
status: pending
created: 2026-06-11
last_updated: 2026-06-11
blocked_by: []
files_budget: 12
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/nitter/NitterFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/odysee/OdyseeFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/youtube/YouTubeFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - BlueskyFetcher and RedditFetcher — different request shapes; not part of the byte-identical quartet.
  - The redaction/interrupt/status logic ITSELF — it moves, it does not change (M1-292 owns redaction changes; if both are in flight, sequence so the logic lands once).
  - The Fetcher SPI and CDI binding structure — kind classes stay as CDI bindings.
acceptance:
  - "The ~280 duplicated lines (redaction, interrupt handling, status-code logic) across RssFetcher, NitterFetcher, OdyseeFetcher, YouTubeFetcher collapse into one package-level fetchAndParse helper; each kind class keeps its CDI identity and parser hook; the four fetchers' existing tests stay green unmodified (the refactor is invisible at the test seam)."
  - "A named test exercises the helper's redaction/interrupt/status behaviour once, directly (the logic is currently tested only via per-fetcher copies, if at all)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher
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

# M1-310: Collector dedup: one fetchAndParse helper for the four single-GET fetchers

## Context

Deep-review v5 verified MEDIUM **U-49**
(`deep-code-review/v5/UNIFIED-REPORT.md` §4; sources `fable-5/06#F4`,
`gpt-55#M-17` — gitignored; all load-bearing facts inlined):

Four byte-identical single-GET fetchers carry ~280 duplicated lines
including the security-relevant redaction and interrupt logic — four
places to patch every time that logic changes (M1-023's URL-redaction
lesson already lives in all four copies).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- "Byte-identical" was the report's claim about the quartet — verify with
  a diff across the four files at start; any drift found is part of the
  finding (the drifted copy is probably the buggy one) and must be
  reconciled consciously, not averaged.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-310-*.md
```
