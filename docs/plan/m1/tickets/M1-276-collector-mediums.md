---
id: M1-276
title: "Collector mediums: re-eval splice, scan bounds, vocab, edges"
status: pending
created: 2026-06-09
last_updated: 2026-06-10
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
  - "ReEvaluationJob.reconstructOriginalBody is a single-pass position-anchored splice: a named test with a quarantined span whose content contains a placeholder-shaped literal ([REDACTED:<other-id>]) reconstructs the original bytes exactly (today's order-dependent global String.replace corrupts them). The new test lands in the pre-existing ReEvaluationJobTest.java; its existing reconstruction assertions (non-colliding placeholders) are preserved, not weakened."
  - "The NEEDS_REVIEW depth check bounds its scan (fetched_at bound or equivalent) so partition pruning applies; the 5-minute count no longer scans every partition; a named test asserts the bounded count excludes NEEDS_REVIEW rows older than the bound and still counts rows within it."
  - "TagVocabulary refreshes at runtime: a tag added via /add-source becomes visible to the tagger without a Collector restart (periodic reload or NOTIFY-driven; see Notes); named test."
  - "Kind6Handler persists the post and its repost edge atomically: a failure between the two writes cannot leave a committed post without its edge; a named test injects an edge-write failure and asserts the post write rolled back (or the edge is recovered — one semantics, pinned). The failure-injection test lands alongside the pre-existing Kind6HandlerTest.java, whose unresolved-edge-shape assertions are preserved (fixtures/wiring may adjust if the two writes collapse into one transaction); Kind6RepostResolutionIT/Kind6LinkingIT are modified only if shared fixture shape changes."
  - "RedditFetcher handles missing created_utc explicitly (skip the item with a counted/logged reason, or substitute fetch time — pick one in the diff and pin it) instead of silently storing 1970-01-01; named test. The test lands in the pre-existing RedditFetcherTest.java; no existing assertion pins the 1970-01-01 behavior, and existing test methods are not modified."
  - "latestPublishedAtEpochSeconds no longer forces an all-partition MAX(published_at) scan on every relay reconnect (fetched_at/recency bound or equivalent partition-pruned form), with the cursor semantics preserved; a named test covers the stale-source path (source with no recent posts: falls back to the unbounded query or a persisted cursor, cursor semantics intact)."
  - "A new migration rebuilds V34's unresolved-repost-edge unique index with NULLS NOT DISTINCT so duplicate unresolved edges are rejected; named test inserts a duplicate and asserts rejection."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-collector/src/test/java/app/zcat/infochat/collector
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6HandlerTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobWindowTest.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/reeval/ReEvaluationJobScheduledPathIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6RepostResolutionIT.java
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/Kind6LinkingIT.java
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
revisions:
  - date: 2026-06-10
    reason: clarity-fail refine (test_plan.modifies named only a directory; scan-bound items 2/6 had no named test; stale V50 migration note)
    snapshot: |
      Pre-refine test_plan.modifies listed only the directory
      infochat-collector/src/test/java/app/zcat/infochat/collector.
      Pre-refine acceptance items 2 and 6 verbatim:
        2: "The NEEDS_REVIEW depth check bounds its scan (fetched_at bound or
            equivalent) so partition pruning applies; the 5-minute count no
            longer scans every partition."
        6: "latestPublishedAtEpochSeconds no longer forces an all-partition
            MAX(published_at) scan on every relay reconnect (fetched_at/recency
            bound or equivalent partition-pruned form), with the cursor
            semantics preserved."
      Items 1, 4, 5 lacked the pre-existing-test naming appended by the refine.
      Pre-refine Notes migration bullet targeted V50 ("M1-269 takes the next
      free version (V49 at drafting time); this ticket takes the one after").
      All other frontmatter fields unchanged by the refine.
escalations:
  - date: 2026-06-10
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED: FAIL — test_plan.modifies lists only the directory
      infochat-collector/src/test/java/app/zcat/infochat/collector rather than the
      specific existing test files and methods that will be changed. At a minimum,
      identify which of the following existing tests are being modified and describe
      what assertion changes: ReEvaluationJobTest.java (item 1 body-reconstruction
      fix), RedditFetcherTest.java (item 5 missing-utc fix), Kind6HandlerTest.java or
      Kind6RepostResolutionIT.java (item 4 atomicity fix). For each modified
      pre-existing test, state the old expected behavior being replaced and the new
      one being installed.
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
- Migration version: V49 (re-swept 2026-06-10 — M1-269 landed with no
  migration; highest version on disk across main and all worktrees is V48).
  Re-sweep worktrees again at implementation time.
- Pre-existing test modifications (old → new):
  - `ReEvaluationJobTest.java` — old: reconstruction pinned only with
    non-colliding placeholders (`assertPostBodyContains` on
    `[REDACTED:placeholder-1/2]`); new: those assertions stay green, the
    colliding-literal splice test (item 1) joins the file; shared fixtures
    may extend.
  - `Kind6HandlerTest.java` — old: pins the persist→edge two-write shape and
    the unresolved-edge invariants (`to_post` NULL, score 1.0); new: the same
    edge-shape assertions hold under the pinned atomic semantics;
    wiring/fixtures adjust if the two writes collapse into one transaction.
  - `RedditFetcherTest.java` — old: fixtures always supply `created_utc`;
    nothing pins the 1970-01-01 fallback; new: the missing-created_utc named
    test (item 5) joins the file; existing methods untouched.
  - `ReEvaluationJobWindowTest.java` / `ReEvaluationJobScheduledPathIT.java`
    — modified only if the item-2 fetched_at bound changes which fixture rows
    the depth count sees (old: unbounded count sees all NEEDS_REVIEW rows;
    new: rows older than the bound are excluded).
  - `Kind6RepostResolutionIT.java` / `Kind6LinkingIT.java` — modified only if
    shared fixture shape changes; their resolution/linking assertions are
    preserved.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-276-*.md
```
