---
id: M1-915
title: "Migrate reddit feeds from kind=rss to kind=reddit in place"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  RedditKindFlipMigrationIT.rssRedditRowsFlipInPlacePreservingIdentity
  (to-be-written) — converted at start: written first, run RED (the
  migration does not exist, so a seeded kind='rss' reddit.com row stays
  rss). Verified on this checkout (2026-08-23): KindResolver.java:204-206
  maps reddit.com hosts to REDDIT for NEW /add-source calls, but existing
  rows keep their declared kind — prod's 31 reddit feeds run kind='rss'
  with .rss identifiers (brief-supplied prod-DB fact; the 12h
  zero-likes measurement follows mechanically: the RSS path has no
  engagement fields — grep "likes|reposts|score" in fetcher/rss →
  nothing), so no reddit post ever reaches the D71 likes term. The
  dedicated reddit fetch path is fully enabled (collector
  application.properties:228,236 interval+page-cap; M1-436/M1-457
  enable/revive gates accept reddit) — only the rows are wrong.
analysis_ref: docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md
blocked_by: []
files_scope:
  - infochat-core/src/main/resources/db/migration/V86__reddit_kind_flip.sql
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/RedditKindFlipMigrationIT.java
  - docs/design/02-schema.md
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    Any application-code change. KindResolver already resolves reddit.com
    → REDDIT (KindResolver.java:204-206, host pattern beats the .rss path
    rule), FetchScheduler already dispatches kind='reddit'
    (infochat.fetch.reddit.interval=15m, page-cap=5), the reddit parser
    already maps score→likes (RedditResponseParser.java:141-146), and
    /source-enable already accepts the kind (M1-436/M1-457). The defect is
    DATA, not code.
  - >-
    A permanent dedup bridge in PostPersister (url/permalink matching).
    REJECTED (analysis P12): permanent code for a one-time event
    (engineering-rules §7). The one-time re-ingest of listing-resident
    items is ACCEPTED — see Approach.
  - >-
    Rewriting stored post uids or upstream_identifiers of the old
    RSS-ingested rows. REJECTED: uid is the user-visible /save handle
    (docs/spec/schema.md §UID derivation) — rewriting orphans saved posts.
    The old rows age out via TTL partition drops (D33).
  - >-
    Deleting recent RSS-ingested reddit rows before the flip. REJECTED:
    data loss with /save and post_reference cascade blast radius, worse
    than the temporary dupe skew it avoids (analysis §Solution options).
  - >-
    The in-tree prod/config/bootstrap-sources.json: verified 2026-08-23,
    it is already correct (7 entries; the single reddit entry is
    kind='reddit' with a normalized identifier). The prod-HOST runtime
    bootstrap file (restore.sh:340,454 bundles
    runtime/bootstrap-sources.json) is the one the brief reports with 30
    wrong-kind entries — it is not in this repo; fixing it is the runbook
    step below (analysis P13, ASSUMPTION to verify on prod).
  - >-
    The comments-term ranking work (M1-914) and the render work
    (M1-912/M1-913). Recommended landing order is 914 before this ticket
    (analysis §Decomposition: flipped feeds then collect likes AND
    comments from their first JSON tick) — a soft ordering, not a code
    dependency.
acceptance:
  - "REPRODUCTION closed: RedditKindFlipMigrationIT.rssRedditRowsFlipInPlacePreservingIdentity passes — a V86__reddit_kind_flip.sql migration (next free number at analysis time; confirm at start) UPDATEs every source row with kind='rss' AND a reddit.com/redd.it host identifier to kind='reddit' with the identifier normalized by stripping the trailing /.rss or .rss path suffix (https://www.reddit.com/r/X/hot/.rss → https://www.reddit.com/r/X/hot, matching the in-tree bootstrap file's canonical form and RedditFetcher's identifier+'.json' construction, RedditFetcher.java:119). The test seeds an rss-kind reddit row WITH subscriptions, a source_exclusion, and posts, runs the migration, and asserts kind/identifier updated, source_id UNCHANGED, source_origin unchanged, subscriptions and exclusions intact, posts still attached (analysis P12/P13)."
  - "RedditKindFlipMigrationIT asserts the identifier variants the migration's stated predicate matches: /r/<sub>/hot/.rss, /r/<sub>/.rss, and a trailing-slash form all flip; non-reddit rss rows and non-.rss reddit rss rows are left untouched (the runbook's pre-check query enumerates them with a stated reason). The predicate is stated in the migration comment: kind='rss' AND a reddit host pattern (www.reddit.com / reddit.com / old.reddit.com / redd.it, subdomains included) AND a .rss-suffixed path."
  - "FAILURE-MODE (UNIQUE collision, analysis P14): RedditKindFlipMigrationIT.collidingRowsAreSkippedAndReported passes — a subreddit present as BOTH a kind='rss' row and a kind='reddit' row with the SAME normalized identifier collides on source's UNIQUE(kind, identifier); the migration SKIPS the colliding rss row (left for ops to /remove-source) and reports it via RAISE NOTICE naming the identifier, never failing the boot and never merging rows."
  - "RedditKindFlipMigrationIT asserts bootstrap-upsert convergence: after migration, a BootstrapLoader upsert of the corrected entry (kind='reddit', the normalized identifier — exactly the strings the corrected prod-host runtime bootstrap file must declare) hits the ON CONFLICT (kind, identifier) DO UPDATE branch (BootstrapLoader.java:177-184) and updates the SAME row — row count unchanged, source_id unchanged, no duplicate double-fetching row (analysis P13)."
  - "The reproduction IT asserts the seeded posts' uid and upstream_identifier columns are byte-identical after the migration: the one-time re-ingest is ACCEPTED with its bounds (analysis P12 — after the flip the JSON path derives upstream_identifier = t3_ fullname while the RSS rows used the permalink guid, so currently-listed items re-ingest once under new uids per docs/spec/schema.md §UID derivation; near-duplicate clustering folds each dupe into its story cluster so no user-visible double appears in digests; dupes age out via TTL partition drops (D33); the eval cost is one listing per source), and stored uids are NEVER rewritten — uid is the user-visible /save handle. The runbook recommends flipping right after a digest slot so the digest boundary absorbs the re-ingest."
  - "The runbook lands in docs/design/07-deployment.md — probe: grep -n 'reddit' docs/design/07-deployment.md: pre-check SELECT listing the rows the migration will touch and any collisions; the migration; the runtime bootstrap-sources.json edit to the identical (kind, identifier) strings (or the next pack/restore re-introduces kind='rss' rows — the brief's stated trap); post-check SELECT asserting zero kind='rss' reddit-host rows remain (skipped collisions excepted, named)."
  - "docs/design/02-schema.md §2.2.1 records the reddit-kind identifier convention (subreddit URL without a .rss suffix — the form RedditFetcher appends .json to) — probe: grep -n 'reddit' docs/design/02-schema.md"
  - "mvn verify from the repo root is green."
test_plan:
  adds:
    - >-
      infochat-core/src/test/java/app/zcat/infochat/core/schema/RedditKindFlipMigrationIT.java
      — the reproduction (identity-preserving flip with subscriptions,
      exclusion, origin and posts intact), the identifier-variant cases,
      the collision skip-and-report failure mode, and the
      bootstrap-upsert convergence case (P13).
  preserves:
    - >-
      Every existing migration IT and the tag-tree cutover checks — the
      migration is additive and touches only matching source rows.
    - all tests currently green on main
spec_refs:
  - docs/spec/schema.md §UID derivation
decision_refs:
  - D33
  - D38
  - D59
---

# M1-915: flip reddit feeds from kind=rss to kind=reddit, in place

## Context

All 31 prod reddit feeds are registered kind='rss' with .rss identifiers
(brief-supplied prod-DB fact, 2026-08-23), so they are fetched by the RSS
path, which has no engagement fields — measured: 1,013 posts in 12h, zero
with likes non-null. The dedicated reddit path exists, is correct
(RedditResponseParser: score→likes, num_comments→M1-914's column, reposts
deliberately null), and is fully wired (dispatch interval, page cap,
enable gates) — no feed row selects it. Ranking starves: with likes/reposts
NULL for the majority of corpus volume, the D71 social terms degenerate
and the digest collapses toward recency+corroboration. See
docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md.

## Root cause

The feeds were registered with an explicitly declared kind (bootstrap JSON
or --type rss): KindResolver would have chosen REDDIT from the host
(KindResolver.java:204-206), but explicit declarations win and existing
rows are never re-resolved. The registration is data, and the fix is a
data migration; the edge is that (kind, identifier) is the source
identity key and the two paths derive different post uids.

## Pitfalls

- P12: uid churn → one-time re-ingest of listing-resident items (RSS guid
  = permalink URL vs JSON t3_ fullname; sha256(source_id|upstream_identifier),
  docs/spec/schema.md §UID derivation). Accept it with its stated bounds;
  NEVER rewrite stored uids (the /save handle) and NEVER add a permanent
  dedup bridge (engineering-rules §7).
- P13: The bootstrap loader upserts on (kind, identifier)
  (BootstrapLoader.java:177-184): the migrated rows must land on EXACTLY
  the strings the corrected runtime bootstrap file declares, or the next
  boot/restore INSERTs duplicate rows beside them — double-fetch, both
  bootstrap-origin, every D59 world. The in-tree
  prod/config/bootstrap-sources.json is already correct (verified); the
  prod-host runtime file is the one to fix (ASSUMPTION — not in this
  repo).
- P14: UNIQUE(kind, identifier) collision: a subreddit present as both an
  rss row and a hand-added reddit row collides on UPDATE. Skip-and-report,
  never fail boot, never merge.

## Approach

Derived from spec_refs: docs/spec/schema.md §UID derivation pins why the
dupe happens (and why rewriting uids is rejected); D59 requires origin
preservation; D38/D33 bound the dupe's lifetime.

- **Files to touch:** see files_scope.
- **Steps, in order:** (1) reproduction IT RED against the pre-migration
  schema; (2) the V86 migration — a single guarded UPDATE with the stated
  predicate, the identifier normalization, and the collision skip
  (WHERE NOT EXISTS the target key, plus RAISE NOTICE); (3) the IT's
  variant/collision/convergence cases; (4) the runbook + schema-doc note.
- **Controls to preserve (engineering-rules §10):** source_origin (D59),
  subscriptions and source_exclusion rows (keyed on source_id — preserved
  by in-place UPDATE), the bootstrap loader's soft-delete-skip rule (a
  soft-deleted rss row is NOT flipped — it stays admin-lifecycle
  territory), status / park_reason untouched. No audit rows: migrations
  write none.
- **Pitfall→mitigation:** P12→acceptance 5 + runbook timing note;
  P13→acceptance 4's loader-convergence IT; P14→acceptance 3.

## Definition of done

Every acceptance item verified by its named test/probe, including the
collision failure mode; mvn verify green from the repo root.

## Verification

- P12 → RedditKindFlipMigrationIT.rssRedditRowsFlipInPlacePreservingIdentity
  — the seeded posts' uid and upstream_identifier columns are
  byte-identical after the migration; a diff that rewrote or deleted
  stored rows fails here.
- P13 → the bootstrap-convergence case — post-migration upsert of the
  corrected entry updates in place; a kind/identifier mismatch between
  migration and file inserts a duplicate row and fails the
  row-count/source_id assertions.
- P14 → RedditKindFlipMigrationIT.collidingRowsAreSkippedAndReported —
  failure-mode: both kinds pre-exist for one normalized identifier; the
  rss row survives untouched, the NOTICE names it, the migration
  completes; a migration that aborts the boot or merges rows fails.
- Reproduction → RedditKindFlipMigrationIT.rssRedditRowsFlipInPlacePreservingIdentity
  — the flip preserves identity (source_id, origin, subscriptions,
  exclusion, posts) and normalizes the identifier to the canonical form.
- Census note → the migration comment's predicate IS the re-runnable
  census; the runbook pre-check SELECT is the same predicate, so what the
  operator reviews is exactly what the migration touches.

## Out-of-scope

No application code (the kind is fully enabled end-to-end already —
verified: resolver, dispatch, parser, enable gates). No dedup bridge, no
uid rewrite, no row deletion — the three rejected designs are recorded
with their reasons in the analysis §Solution options. No render or
ranking change (siblings own those; recommended order 914 → 915, soft).
The prod-host runtime bootstrap file edit is a runbook step, not a repo
diff — the in-tree file is already correct (verified 2026-08-23), which
is a documented discrepancy with the brief's "the file carries the wrong
kind (30 entries)": the brief's file is the runtime file restore.sh
bundles (restore.sh:340,454), not the repo's.
