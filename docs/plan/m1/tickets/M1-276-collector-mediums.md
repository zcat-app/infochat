---
id: M1-276
title: "Collector mediums: re-eval splice, scan bounds, vocab, edges"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 16
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJob.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/tagger/TagVocabulary.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/Kind6Handler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr
  - infochat-collector/src/test/java/app/zcat/infochat/collector
  - infochat-core/src/main/resources/db/migration
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - The Stage-2 judge and quarantine semantics — only the byte-reconstruction feeding them changes.
  - The /add-source command and tag vocabulary seeding — only the Collector-side refresh changes.
  - The Reddit fetch pipeline beyond the created_utc handling.
  - The eval-queue threading (M1-267).
acceptance:
  - "ReEvaluationJob.reconstructOriginalBody is a single-pass position-anchored splice: a named test with a quarantined span whose content contains a placeholder-shaped literal ([REDACTED:<other-id>]) reconstructs the original bytes exactly (today's order-dependent global String.replace corrupts them)."
  - "The NEEDS_REVIEW depth check bounds its scan (fetched_at bound or equivalent) so partition pruning applies; the 5-minute count no longer scans every partition."
  - "TagVocabulary refreshes at runtime: a tag added via /add-source becomes visible to the tagger without a Collector restart (periodic reload or NOTIFY-driven; see Notes); named test."
  - "Kind6Handler persists the post and its repost edge atomically: a failure between the two writes cannot leave a committed post without its edge; a named test injects an edge-write failure and asserts the post write rolled back (or the edge is recovered — one semantics, pinned)."
  - "RedditFetcher handles missing created_utc explicitly (skip the item with a counted/logged reason, or substitute fetch time — pick one in the diff and pin it) instead of silently storing 1970-01-01; named test."
  - "latestPublishedAtEpochSeconds no longer forces an all-partition MAX(published_at) scan on every relay reconnect (fetched_at/recency bound or equivalent partition-pruned form), with the cursor semantics preserved."
  - "A new migration rebuilds V34's unresolved-repost-edge unique index with NULLS NOT DISTINCT so duplicate unresolved edges are rejected; named test inserts a duplicate and asserts rejection."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
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

# M1-276: Collector mediums: re-eval splice, scan bounds, vocab, edges

## Context

Deep-review v4 verified mediums **M-K1..M-K5**, **M-K7**, plus the V34
NULLS-DISTINCT low (`deep-code-review/v4/UNIFIED-REPORT.md` §2/§3; sources
`deep-code-review/v4/opus-48/06-module-infochat-collector.md#F2/#F3`,
`deep-code-review/v4/fable5/06-module-infochat-collector.md#F3`,
`deep-code-review/v4/opus-47/06-module-infochat-collector.md#F3/#F4`,
`deep-code-review/v4/mimo/report.md` MED-007, opus-48 V34 item):

- **M-K1** (security-adjacent — drives `security_relevant: true`):
  `reconstructOriginalBody` does a row-loop global
  `body.replace("[REDACTED:"+id+"]", originalHtml)` over untrusted text; a
  quarantined span whose *content* contains a placeholder-shaped literal
  corrupts the bytes the Stage-2 judge then classifies.
- **M-K2:** `SELECT COUNT(*) FROM post WHERE status='NEEDS_REVIEW'` every
  5 min with no `fetched_at` bound — partition pruning structurally defeated.
- **M-K3:** `TagVocabulary` loads once in `@PostConstruct` into an immutable
  set; `/add-source` extends the vocabulary at runtime but new tags are
  invisible to the tagger until restart.
- **M-K4:** `Kind6Handler`: `postPersister.persist` (tx 1) then
  `writeRepostEdge` (tx 2); edge-write failure leaves the post edge-less,
  unrecovered (rehydrator re-covers eval, not edges).
- **M-K5:** missing Reddit `created_utc` → `MissingNode.asDouble()` = 0.0 →
  1970-01-01.
- **M-K7:** `SELECT MAX(published_at) FROM post WHERE source_id = ?` on
  every relay reconnect — all-partition scan.
- V34 low: the unique index admits duplicate unresolved repost edges under
  NULLS DISTINCT.

## Acceptance

See frontmatter. The report says "split as needed" — if the outline at start
finds these don't share enough surface, decompose rather than forcing one
diff.

## Out-of-scope

See frontmatter.

## Notes

- TagVocabulary refresh: the project already standardizes on
  LISTEN/NOTIFY for collector↔provider events, but a NOTIFY from the
  Provider on /add-source is new plumbing; a periodic reload (scheduler
  tick, vocabulary is tiny) is the simpler cut. Surface the choice in the
  diff; default to periodic.
- M-K7's bound must not break the reconnect cursor when a source has no
  recent posts — fall back to the unbounded query or a persisted cursor in
  that case; the named test should cover the stale-source path.
- Migration version: M1-269 takes the next free version (V49 at drafting
  time); this ticket takes the one after (V50). Re-sweep worktrees at
  implementation time; `migration_touch: true` serializes the two starts.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-276-*.md
```
