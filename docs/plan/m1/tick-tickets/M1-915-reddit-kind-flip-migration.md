---
id: M1-915
title: "Migrate reddit feeds from kind=rss to kind=reddit in place, onto the /.rss transport"
status: done
created: 2026-08-23
last_updated: 2026-08-23
reviews:
  - round: 1
    date: 2026-08-23
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN, SCOPE PASS"
    diff_stats: "6 files changed, 497 insertions(+), 7 deletions(-)"
    rework_items: 2
    verdict_file: .scratch/tick-review-M1-915-r1.txt
  - round: 2
    date: 2026-08-23
    verdict: APPROVE
    checks: "round-1 items 1-2 SATISFIED + driver-directed item 3 SATISFIED; SPEC-TRUTHNESS, SECURITY, TEST-ADEQUACY, MAINTAINABILITY, SCOPE all PASS"
    diff_stats: "fix hunks: 5 files, +97/-7"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-915-r2.txt
  - round: 3
    date: 2026-08-23
    verdict: REWORK
    checks: "SPEC-TRUTHNESS FAIL (author capture last-wins vs promised first-wins), SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN, SCOPE PASS"
    diff_stats: "vs commit 07c4e84e: 22 files, +468/-1104 (net -636; .json retirement)"
    rework_items: 1
    verdict_file: .scratch/tick-review-M1-915-r3.txt
  - round: 4
    date: 2026-08-23
    verdict: APPROVE
    checks: "round-3 item SATISFIED; SPEC-TRUTHNESS, SECURITY, TEST-ADEQUACY, MAINTAINABILITY, SCOPE all PASS"
    diff_stats: "fix hunks: 3 files, +52/-2"
    rework_items: 0
    verdict_file: .scratch/tick-review-M1-915-r4.txt
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
  enable/revive gates accept reddit) — only the rows are wrong. ROUND 3
  PREMISE CORRECTION (2026-08-23, live-probed from prod's egress): the
  ".json endpoint is fully enabled" half was FALSE — www .json is 403
  for every UA (browser UAs and logged-in feed tokens included),
  old.reddit 302s to login; only identifier + '/.rss' answers (200, the
  shared outbound UA). RedditFetcher's .json URL was prod-dead code no
  review gate caught (gates verify code-vs-spec; "the endpoint answers
  from prod's network" was nobody's question). Operator decisions:
  keep the committed migration, extend THIS ticket with the /.rss
  transport re-point (round_cap lifted 2→3), engagement deferred (see
  out_of_scope). Transport reproduction RED first:
  RedditFetcherRssTransportTest (2 errors, 'Reddit fetch got HTTP 404',
  .scratch/red-run-reddit-rss-transport.log) against a byte-real
  r/java/hot Atom capture (fixtures/reddit/atom-listing.rss) served
  only on /.rss.
analysis_ref: docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md
blocked_by: []
files_scope:
  - infochat-core/src/main/resources/db/migration/V86__reddit_kind_flip.sql
  - infochat-core/src/test/java/app/zcat/infochat/core/schema/RedditKindFlipMigrationIT.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcher.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParser.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/rss/RssFeedParserTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherRssTransportTest.java
  - infochat-collector/src/test/resources/fixtures/reddit/atom-listing.rss
  - infochat-collector/src/test/java/app/zcat/infochat/collector/testsupport/ScanWindowFixtureGuardTest.java
  - infochat-collector/src/main/resources/application.properties
  - docs/design/01-architecture.md
  - docs/design/02-schema.md
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 4
security_relevant: false
migration_touch: true
out_of_scope:
  - >-
    OAuth / authenticated-engagement collection. DECIDED 2026-08-23 by
    the operator after live probes: www .json is edge-blocked (403, every
    UA incl. browser UAs and logged-in feed tokens), old.reddit 302s to
    login, oauth.reddit.com is reachable (401 without credentials) but
    app creation was judged too painful for now. Consequence: reddit
    likes/reposts/comments stay NULL and the D71 social terms stay
    starved for reddit — accepted, recorded in
    .agents/memory/reddit-rss-transport-not-json.md. The .rss Atom
    payload carries NO engagement numbers (verified against a live
    capture); no parser extension can produce them. A future OAuth
    ticket re-pointing onto oauth.reddit.com inherits t3_-fullname
    upstream identifiers unchanged (uid parity).
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
  - "REPRODUCTION closed: RedditKindFlipMigrationIT.rssRedditRowsFlipInPlacePreservingIdentity passes — a V86__reddit_kind_flip.sql migration (next free number at analysis time; confirm at start) UPDATEs every source row with kind='rss' AND a reddit.com/redd.it host identifier to kind='reddit' with the identifier normalized by stripping the trailing /.rss or .rss path suffix (https://www.reddit.com/r/X/hot/.rss → https://www.reddit.com/r/X/hot, matching the in-tree bootstrap file's canonical form and the listing URL the reddit fetcher requests via identifier + '/.rss'). The test seeds an rss-kind reddit row WITH subscriptions, a source_exclusion, and posts, runs the migration, and asserts kind/identifier updated, source_id UNCHANGED, source_origin unchanged, subscriptions and exclusions intact, posts still attached (analysis P12/P13)."
  - "RedditKindFlipMigrationIT asserts the identifier variants the migration's stated predicate matches: /r/<sub>/hot/.rss, /r/<sub>/.rss, and a trailing-slash form all flip; non-reddit rss rows and non-.rss reddit rss rows are left untouched (the runbook's pre-check query enumerates them with a stated reason). The predicate is stated in the migration comment: kind='rss' AND a reddit host pattern (www.reddit.com / reddit.com / old.reddit.com / redd.it, subdomains included) AND a .rss-suffixed path."
  - "FAILURE-MODE (UNIQUE collision, analysis P14): RedditKindFlipMigrationIT.collidingRowsAreSkippedAndReported passes — a subreddit present as BOTH a kind='rss' row and a kind='reddit' row with the SAME normalized identifier collides on source's UNIQUE(kind, identifier); the migration SKIPS the colliding rss row (left for ops to /remove-source) and reports it via RAISE NOTICE naming the identifier, never failing the boot and never merging rows."
  - "RedditKindFlipMigrationIT asserts bootstrap-upsert convergence: after migration, a BootstrapLoader upsert of the corrected entry (kind='reddit', the normalized identifier — exactly the strings the corrected prod-host runtime bootstrap file must declare) hits the ON CONFLICT (kind, identifier) DO UPDATE branch (BootstrapLoader.java:177-184) and updates the SAME row — row count unchanged, source_id unchanged, no duplicate double-fetching row (analysis P13)."
  - "The reproduction IT asserts the seeded posts' uid and upstream_identifier columns are byte-identical after the migration: the one-time re-ingest is ACCEPTED with its bounds (analysis P12 — after the flip the JSON path derives upstream_identifier = t3_ fullname while the RSS rows used the permalink guid, so currently-listed items re-ingest once under new uids per docs/spec/schema.md §UID derivation; near-duplicate clustering folds each dupe into its story cluster so no user-visible double appears in digests; dupes age out via TTL partition drops (D33); the eval cost is one listing per source), and stored uids are NEVER rewritten — uid is the user-visible /save handle. The runbook recommends flipping right after a digest slot so the digest boundary absorbs the re-ingest."
  - "The runbook lands in docs/design/07-deployment.md — probe: grep -n 'reddit' docs/design/07-deployment.md: pre-check SELECT listing the rows the migration will touch and any collisions; the migration; the runtime bootstrap-sources.json edit to the identical (kind, identifier) strings (or the next pack/restore re-introduces kind='rss' rows — the brief's stated trap); post-check SELECT asserting zero kind='rss' reddit-host rows remain (skipped collisions excepted, named)."
  - "docs/design/02-schema.md §2.2.1 records the reddit-kind identifier convention (subreddit URL without an .rss suffix — the form the reddit fetcher appends /.rss to) — probe: grep -n 'reddit' docs/design/02-schema.md"
  - "TRANSPORT REPRODUCTION closed: RedditFetcherRssTransportTest passes — the reddit fetch path requests identifier + '/.rss' (asserted on the stub server's captured path), parses the byte-real r/java/hot Atom capture via RssFeedParser (t3_ fullname upstream identifiers, title, content-type-html body, alternate-link url, RFC3339 published), and makes exactly ONE request per tick (the .rss listing has no after-cursor). RED evidence: .scratch/red-run-reddit-rss-transport.log — both tests error 'Reddit fetch got HTTP 404' against a stub serving ONLY /.rss, mirroring prod egress where .json is 403 for every UA."
  - "Engagement trio (likes/reposts/comments) and derived socialScore are NULL on the .rss transport — pinned by RedditFetcherRssTransportTest.engagementStaysNullOnRssTransport (null = no signal, never 0, per NormalizedPost's contract)."
  - "RssFeedParser's Atom leg gains generic author/category capture: first <author><name> → rawMetadata 'author', first <category term> → rawMetadata 'category' (the reddit nodes that DO exist in the Atom payload); entries without them keep Map.of(). Pinned by a parser unit test plus the transport test's metadata assertions."
  - "Identifier edge handling in the transport URL builder: a bare subreddit (https://…/r/X), a listing (…/r/X/hot), and a trailing-slash form all build …/.rss; an identifier ALREADY ending in .rss (case-insensitive) is used as-is, never double-suffixed. Pinned by unit assertions."
  - "The .json path is retired: RedditResponseParser and its five test classes are deleted, RedditFetcherTest is rewritten onto the Atom transport (fields, non-2xx, empty feed, the M1-704 single-User-Agent guard preserved), the reddit page-cap config key is removed (base + %pi — no pagination on .rss), and docs/design/01-architecture.md's pagination-cap table moves reddit to the no-pagination row alongside RSS — probes: grep -rn 'RedditResponseParser' infochat-collector/src returns nothing; grep -n 'reddit.page-cap' infochat-collector/src/main/resources/application.properties returns nothing."
  - "Deploy-order hazard resolved by construction: the transport re-point and V86 land in the SAME merge, so a deploy can never again apply V86 without a fetcher that serves the flipped rows — probe: git diff --name-only $(git merge-base main HEAD)..HEAD lists V86__reddit_kind_flip.sql and fetcher/reddit/RedditFetcher.java together."
  - "mvn verify from the repo root is green."
test_plan:
  adds:
    - >-
      infochat-core/src/test/java/app/zcat/infochat/core/schema/RedditKindFlipMigrationIT.java
      — the reproduction (identity-preserving flip with subscriptions,
      exclusion, origin and posts intact), the identifier-variant cases,
      the collision skip-and-report failure mode, and the
      bootstrap-upsert convergence case (P13).
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherRssTransportTest.java
      — the round-3 transport reproduction over the byte-real Atom
      capture: /.rss requested, t3_ identifiers, field mapping,
      single-request, engagement-null. Plus the rewritten
      RedditFetcherTest (Atom-transport fields, non-2xx, empty feed,
      M1-704 UA guard) and the RssFeedParser author/category unit test.
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
clarity_check: "start 2026-08-23 pass — all six file:line citations re-verified on the rebased tree (KindResolver.java:204-206; collector application.properties:228,236 interval+page-cap; RedditResponseParser.java:141-146 score→likes; RedditFetcher.java:119 identifier+'.json'; BootstrapLoader.java:177-184 ON CONFLICT DO UPDATE WHERE deleted_at IS NULL, collector module; prod/scripts/restore.sh:340,454); V86 confirmed next free (V85 latest); canonical identifier form pinned from the in-tree bootstrap entry (https://www.reddit.com/r/aipromptprogramming/hot — no trailing slash); analysis P12/P13/P14 present in ticket; the Approach's soft-delete control (soft-deleted rss row NOT flipped) is added to the IT assertions beyond acceptance 2's stated predicate text; no ambiguity"
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

## Round 3 scope extension (driver-directed, 2026-08-23)

After round 2's APPROVE and the commit (07c4e84e), the operator
falsified the ticket's transport premise with live probes from prod's
egress: www.reddit.com .json is 403 for EVERY user agent (browser UAs
and logged-in feed tokens included); old.reddit.com 302s everything to
login; identifier + '/.rss' answers 200 under the shared outbound UA.
The committed V86 alone would therefore park all 31 feeds on a dead
path. Operator decisions, recorded: (1) keep the committed migration —
it works on prod's actual data; (2) extend THIS ticket with the /.rss
transport re-point instead of a new ticket, round_cap lifted 2→3;
(3) engagement collection is deferred to a future OAuth ticket
(see out_of_scope); (4) reuse the RSS parser, extend it for reddit's
Atom nodes, retire the .json parser. Transport reproduction was written
FIRST and run RED (RedditFetcherRssTransportTest, 2 errors,
RedditFetchException HTTP 404 — the fetcher requests .json, the stub
serves only /.rss, mirroring prod). Deploy-order hazard (V86 without a
serving fetcher) is resolved by construction: one merge carries both.

## Round 3 rework

Round cap lifted 3→4 (driver decision, 2026-08-23): one low item, a
3-line guard — escalation would cost more than the fix. REWORK ITEMS
(verbatim from .scratch/tick-review-M1-915-r3.txt):

1. Finding 1: guard the author capture in RssFeedParser.parseAtomEntry
   (RssFeedParser.java:279) to first-wins while still draining every
   <author> element, evaluated via the RssFeedParserTest two-author case
   asserting rawMetadata 'author' equals the first name, plus full
   `mvn verify` green.

## Round 1 rework

REWORK ITEMS (verbatim from .scratch/tick-review-M1-915-r1.txt):

1. Finding 1: add the case-insensitive 'i' flag to both suffix-strip
   regexp_replace call sites (V86__reddit_kind_flip.sql:34 and the runbook
   pre-check's identical calls, docs/design/07-deployment.md:1778/:1781),
   optionally seeding an uppercase-.RSS row in the IT — evaluated via
   `grep -n "rss/?$', '', 'i'"` on both files plus
   RedditKindFlipMigrationIT.identifierVariantsFlipAndUntouchedClassesStay
   green under `mvn verify`.
2. Finding 2: wrap V86__reddit_kind_flip.sql:47's UPDATE in an
   EXCEPTION WHEN unique_violation handler that counts and NOTICE-reports
   the row exactly like the existing skip branch — evaluated via
   `grep -n 'unique_violation'` on V86 plus
   RedditKindFlipMigrationIT.collidingRowsAreSkippedAndReported green
   under full `mvn verify`.
3. (driver-directed 2026-08-23, user call: analysis miss, in-ticket —
   NOT a new ticket) Make the non-.rss reddit-host rss class VISIBLE in
   the runbook, as acceptance 2 ("the runbook's pre-check query
   enumerates them with a stated reason") and acceptance 6 (post-check on
   kind='rss' reddit-host rows, "skipped collisions excepted, named")
   already promise: a second pre-check SELECT enumerating reddit-host
   kind='rss' rows WITHOUT the .rss suffix with the keep-or-re-add
   decision stated, and a broadened post-check listing every remaining
   reddit-host rss row (expected: only nameable rows — a step-1
   collision or a deliberate keep). The migration itself still does not
   flip them (acceptance 2 leaves them untouched; auto-flipping
   arbitrary non-listing reddit URLs onto the JSON fetch path is
   untested behavior — the operator decides per row).

## Review observations

- Recommended-new-ticket (round 1, TOUCHED-BY-THIS-DIFF: yes, no
  DECIDE-BEFORE): ops visibility for reddit-host rss rows outside the
  census — a kind='rss' row on a reddit host WITHOUT an .rss suffix
  (e.g. 'https://www.reddit.com/r/Y' — the class the IT seeds as
  keptRedditHostWithoutSuffix, RedditKindFlipMigrationIT.java:88) is
  deliberately left untouched (acceptance 2's stated predicate) but is
  also invisible to the runbook's post-check, whose WHERE carries the
  same .rss-suffix condition (docs/design/07-deployment.md step 4).
  Running the runbook on a DB holding that row prints "zero rows" while
  the row keeps fetching over the engagement-less RSS path forever.
  Expected: a second review query (or a broadened post-check) listing
  reddit-host rows still kind='rss' outside the census, so the class is
  at least named for the operator. Disposition (user, 2026-08-23): an
  analysis miss — belongs IN this ticket; folded into Round 1 rework as
  driver-directed item 3. No new ticket filed.
