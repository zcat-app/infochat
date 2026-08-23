---
id: M1-914
title: "Persist post.comments and rank reddit replies in the digest"
status: pending
created: 2026-08-23
last_updated: 2026-08-23
flow: tick
reproduction: >-
  RedditResponseParserCommentsTest.numCommentsLandsOnTheTypedFieldAbsentStaysNull
  (to-be-written) and
  ClusterProminenceTest.commentsTermRanksWithinKindAndDropsWhenNull
  (to-be-written) — converted at start: written first, run RED. Verified on
  this checkout (2026-08-23): RedditResponseParser.java:153 stringifies
  num_comments into rawMetadata with asInt(0) defaulting, and the map is
  then DISCARDED for reddit posts (PostPersister writes no metadata column
  — grep of V7__joins_post.sql for metadata: nothing; M1-723 §Context:
  "for Bluesky and Reddit the map is constructed, carried through the
  pipeline, and discarded"), so the reply count never reaches any scored
  column; and ClusterProminence.java:61-71,237-258 scores exactly four
  terms (corroboration/reposts/likes/scarcity) — no comments term exists.
  Owner direction (2026-08-23): "upvotes AND reply counts should inform
  ranking".
analysis_ref: docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md
blocked_by: []
files_scope:
  - infochat-core/src/main/resources/db/migration/V85__post_comments.sql
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParserCommentsTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterIT.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/ClusterProminence.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterProminenceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestPostCollectorIT.java
  - infochat-provider/src/main/resources/application.properties
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/02-schema.md
  - docs/design/03-commands.md
  - docs/design/07-deployment.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - >-
    The `social_score` column's canonical formula
    (`2 * COALESCE(reposts,0) + COALESCE(likes,0)`,
    docs/design/05-llm-and-embeddings.md §5.4.5). M1-724 deliberately
    separated the ranking's inputs from that column (its §"Why the ranking
    does not use social_score"); a comments term joins the RANKING as its
    own percentiled term, not the column. A diff that rewrites the column
    formula has left scope.
  - >-
    Bluesky replyCount or any other new engagement extraction: Bluesky
    collects likeCount/repostCount only
    (BlueskyResponseParser.java:125-126) and Nostr collects no engagement
    counts (NostrEvent.java:97-101 uses the engagement-less constructor;
    kind-6 edges are graph structure, not counts — verified 2026-08-23).
    Only reddit populates the new column; every other kind writes NULL
    (the documented no-signal case).
  - >-
    The kind flip itself (which feeds carry kind='reddit') — sibling
    M1-915. This ticket is valuable either way (the in-tree bootstrap
    reddit entry is already kind='reddit') and its tests do not depend on
    prod feed state.
  - >-
    Backfill of existing rows: posts already persisted keep comments=NULL;
    the post table is TTL-partitioned (D33) so the NULL cohort ages out —
    the M1-723 backfill precedent.
  - >-
    Any weight retuning beyond the single new key's default (1). The four
    existing weights are untouched; retuning against the live corpus is an
    ops edit (M1-724 §Tuning).
  - >-
    The digest render path (M1-912/M1-913) — this ticket touches ranking
    inputs, not rendering.
  - any other module
acceptance:
  - "REPRODUCTION closed (ingest half): RedditResponseParserCommentsTest.numCommentsLandsOnTheTypedFieldAbsentStaysNull passes — NormalizedPost carries a new @Nullable Integer comments component and RedditResponseParser populates it from num_comments via the SAME intOrNull saturate-never-narrow boundary helper as score (RedditResponseParser.java:181-194): an absent or non-numeric node yields NULL (never 0 — absent is not zero, analysis P9), a present 0 yields 0, and the rawMetadata num_comments string entry is REMOVED (the typed field replaces it; author/subreddit stay in the map — the M1-723 rawMetadata-retarget precedent)."
  - "FAILURE-MODE (boundary wrap, the M1-723 redteam shape): RedditResponseParserCommentsTest asserts num_comments values 4294967296 and 2147483648 SATURATE to MAX rather than narrowing — asInt() wraps 4294967296 to exactly 0, which would persist a fabricated seen-and-ignored zero (analysis P9; NormalizedPost.java:72-81 states the contract) — and comments is magnitude-clamped by NormalizedPost's existing clampCount like its siblings."
  - "PostPersisterIT reads back a written comments value and asserts NULL persists as NULL (not 0) while the pre-existing columns keep their values (column-count/bind-index consistency): V85__post_comments.sql adds post.comments INT NULL (next free migration number at analysis time — confirm at start; additive nullable, metadata-only, no table rewrite — the M1-723 V7 argument), and PostPersister's INSERT lists and binds comments. The round-trip lives in PostPersisterIT, NOT the no-database PostPersisterTest (the M1-723 clarity-check lesson)."
  - "DigestPostCollectorIT asserts the returned row SET is unchanged while both DigestPostCollector SQL blocks (POSTS_ALL_SQL and POSTS_EXPLICIT_SQL) project p.comments and EligiblePostQuery's projection does too (the M1-724 both-collectors precedent; the load-bearing-arity lesson of DigestPostCollector.java:147-153, M1-756/M1-759); DigestPostCollectorTest's JDBC-proxy stub is extended to serve the column."
  - "REPRODUCTION closed (ranking half): ClusterProminenceTest.commentsTermRanksWithinKindAndDropsWhenNull passes — ClusterProminence scores a FIFTH term, comments: max post.comments in the cluster, integer percentile 0-100 against clusters of the SAME source kind (percentilesByKind, ClusterProminence.java:301-312), weight from the new config key infochat.digest.weight.comments (default 1), present-terms denominator (a NULL comments term drops out of numerator AND denominator; a 0 is present with a bottom percentile — the test pins both and pins they produce DIFFERENT scores, the M1-724 NULL-vs-0 twin). No float participates anywhere (D19; the integer-percentile, cross-multiplied-long machinery is reused, not paralleled — analysis P10)."
  - "The M1-724 all-NULL-social ClusterProminenceTest fixture passes UNMODIFIED: corroboration stays the highest-weighted term (owner direction 2 — weight.corroboration keeps default 7 against comments default 1), and an RSS-only digest ranks by corroboration and scarcity exactly as before this ticket, so plain-RSS sources are never excluded from top stories by a signal they cannot have."
  - "ClusterProminenceTest's hand-arithmetic case asserts a known fixture's score reproduces by hand-arithmetic including the comments term: ScoredCluster exposes the comments percentile alongside the existing per-term components (the M1-724 tunability rule — inputs must be readable back)."
  - "DocumentedConfigKeyParityTest passes: infochat.digest.weight.comments (default 1) carries a base declaration in the provider application.properties and a docs/design/07-deployment.md §7.4 row in the same diff — probe: grep -n 'weight.comments' infochat-provider/src/main/resources/application.properties docs/design/07-deployment.md"
  - "The spec and design amendments land with wording to user approval (§12, rule-text only) — probe: grep -n 'comments' docs/spec/decisions.md: docs/spec/commands.md §Periodic group digests' D71 paragraph names the fifth term and its weight; docs/spec/decisions.md's D71 row is amended to record the comments term (population, weight, NULL semantics); docs/design/03-commands.md §3.12's term table gains the row; docs/design/02-schema.md §2.3.1 documents post.comments (which fetcher populates it; NULL means no-such-signal, distinct from 0 — the M1-723 schema-comment precedent)."
  - "mvn verify from the repo root is green."
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParserCommentsTest.java
      — the reproduction + boundary-wrap failure-mode cases (per-concern
      suite matching the M1-723 naming convention).
    - >-
      ClusterProminenceTest additions — comments term in isolation;
      within-kind population separation; NULL-vs-0 distinctness;
      all-NULL-comments fixture unchanged; total-order validity over the
      existing 200-cluster fixture extended with comments values;
      hand-arithmetic reproduction of a known fixture's score.
    - >-
      PostPersisterIT / DigestPostCollectorIT cases per acceptance items
      3-4.
  modifies:
    - >-
      DigestPostCollectorTest — the JDBC-proxy stub serves the new
      column; window-semantics assertions unchanged (the M1-724 stub
      precedent).
  preserves:
    - >-
      Every pre-existing ClusterProminenceTest assertion — the four-term
      scores of comments-NULL fixtures are byte-identical (the term drops
      out of both numerator and denominator).
    - >-
      RedditResponseParserItemCapTest, RedditResponseParserNameValidationTest,
      RedditResponseParserPermalinkTest, RedditResponseParserSocialSignalTest
      — untouched except where the rawMetadata num_comments removal is
      asserted; any such assertion is retargeted onto the typed field, not
      deleted (the M1-723 retarget-not-delete rule), and the modification
      is §8-authorized here.
    - >-
      The nitter, youtube, odysee, RSS, bluesky and Nostr parser/persister
      tests — all keep producing/consuming comments=NULL.
    - >-
      social_score's value and its M1-723 pins — the column formula is
      untouched.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/decisions.md
  - docs/design/02-schema.md §2.3.1
  - docs/design/03-commands.md §3.12
decision_refs:
  - D19
  - D33
  - D71
---

# M1-914: reddit reply counts as a ranking signal

## Context

Owner direction (2026-08-23): "Reddit engagement stats SHOULD feed
ranking/ordering the way Bluesky likes and reposts do" and "upvotes AND
reply counts should inform ranking". Upvotes already map: the reddit
parser's score→likes (RedditResponseParser.java:141-146) flows into the
D71 likes term the moment a feed runs kind='reddit' (the kind flip is
M1-915). Reply counts do not map at all: num_comments is stringified into
rawMetadata (RedditResponseParser.java:153) and discarded — no post
column carries it, so ClusterProminence can never read it. This ticket
persists num_comments as a typed nullable column and adds it as a fifth
percentile term. Corroboration stays the highest-weighted term and
no-signal sources are never scored as zero-engagement (owner direction
2). See docs/plan/m1/tick-analysis/digest-info-value-and-ranking.md.

## Root cause

M1-723 deliberately left num_comments in rawMetadata ("num_comments is
not a repost and must not be mapped to one") and M1-724 built the ranking
over the four columns that existed. The reply-count signal has simply
never had a typed home: rawMetadata reaches only the Nostr kind-6
dispatch, and no post column accepts it.

## Pitfalls

- P7: D71's decision row and the spec's term enumeration change with user
  approval; spec prose is rule-text only, no dates or ticket IDs
  (engineering-rules §12).
- P9: The boundary semantics are the ticket's center: absent node → NULL
  (term absent, drops from the denominator), present 0 → 0 (term present,
  bottom percentile); intOrNull's saturate-never-narrow rule applies
  (4294967296 narrows to a fabricated 0 under asInt — the M1-723 redteam
  finding; NormalizedPost.java:72-81 states the contract).
- P10: D19 integer-only ranking — the term slots into the EXISTING
  percentile machinery (percentilesByKind, cross-multiplied-long total
  order); no parallel float path, no new comparator. Corroboration weight
  7 stays the highest single weight; comments default 1.
- P11: Both DigestPostCollector SQL blocks plus EligiblePostQuery project
  the column (load-bearing arity, M1-756/M1-759); the PostPersisterIT
  round-trip (not the no-database PostPersisterTest) pins column-count /
  bind-index consistency; the V-migration is additive nullable
  (metadata-only).

## Approach

Derived from spec_refs: D71's machinery (integer percentiles, per-kind
populations, present-terms denominator) is designed to admit exactly this
term; the amendments record it.

- **Files to touch:** see files_scope.
- **Steps, in order:** (1) reproduction tests RED (parser + prominence);
  (2) V85 + NormalizedPost component + PostPersister bind + PostPersisterIT
  round-trip (P9/P11); (3) RedditResponseParser typed mapping +
  rawMetadata retarget (P9); (4) collector projections + stub (P11);
  (5) ClusterProminence fifth term + config key + tests (P10); (6) spec /
  decision / design amendments (P7).
- **Controls to preserve (engineering-rules §10):** the M1-723
  ingest-boundary rules (saturation, NULL-vs-0, magnitude clamp);
  rawMetadata's Nostr keys untouched (this ticket removes only the reddit
  num_comments entry); PostPersister's uid pre-filter and ON CONFLICT
  unchanged; the engagement-less constructor's all-NULL shape for
  non-social kinds.
- **Pitfall→mitigation:** P9→acceptance 1-2; P10→acceptance 5-6;
  P11→acceptance 3-4; P7→acceptance 9.

## Definition of done

Every acceptance item verified by its named test/probe, including the
boundary-wrap failure-mode case; mvn verify green from the repo root.

## Verification

- P9 → RedditResponseParserCommentsTest.numCommentsLandsOnTheTypedFieldAbsentStaysNull
  (absent → NULL; 0 → 0; rawMetadata entry removed) and the wrap cases
  (4294967296 / 2147483648 saturate, never narrow) — feeds the parser
  hostile JSON, the M1-723 redteam repro shape.
- P10 → ClusterProminenceTest.commentsTermRanksWithinKindAndDropsWhenNull
  — two otherwise-identical reddit clusters rank by comments percentile;
  a comments-NULL cluster (RSS) never competes on the term; the all-NULL
  fixture's ordering is byte-identical to pre-change.
- P11 → PostPersisterIT round-trip (comments written and read back; NULL
  stays NULL; pre-existing columns unchanged); DigestPostCollectorIT row
  SET unchanged.
- P7 → the amendment acceptance probe (grep -n 'comments'
  docs/spec/decisions.md) confirms D71 names the term; the wording itself
  is user-approved at implementation, never pinned by a test.
- Owner direction 2 → the all-NULL-social ClusterProminenceTest fixture
  (M1-724) passes unmodified; weight defaults asserted (7 > 1).
- Acceptance 7 → ClusterProminenceTest hand-arithmetic case reproduces a
  fixture score including the comments term.
- Failure-mode beyond the reproductions: the boundary-wrap cases (P9)
  feed the diff's own parser a hostile count that narrows to a fabricated
  zero under asInt — an implementation that narrows instead of
  saturating fails them.

## Out-of-scope

The kind flip (M1-915) decides which FEEDS collect reddit engagement; this
ticket makes the signal exist and be scored. No social_score formula
change, no bluesky/nostr extraction, no backfill, no weight retuning, no
render change. If implementation finds V85 already taken by a landed
sibling, take the next free number — the ticket pins the DDL shape, not
the number. Fixture calibration: the new ClusterProminence assertions are
written against this family's END state — nothing here pins render
behavior M1-912/M1-913 change (the M1-785 rule).

## Census

The class scoped here: every projection and construction site of the
shared Post/NormalizedPost shapes must account for the new component.
Enumerated:

| Site | Disposition |
|---|---|
| DigestPostCollector POSTS_ALL_SQL | **fix** — project p.comments |
| DigestPostCollector POSTS_EXPLICIT_SQL | **fix** — project p.comments |
| DigestPostCollector.mapPost | **fix** — read the column (getObject, NULL survives) |
| EligiblePostQuery projection | **fix** — project p.comments (M1-724 both-collectors precedent) |
| DigestPostCollectorTest JDBC-proxy stub | **fix** — serve the column |
| PostPersister INSERT | **fix** — list + bind comments |
| NormalizedPost | **fix** — new @Nullable component + clampCount |
| RedditResponseParser | **fix** — typed mapping; rawMetadata entry removed |
| Bluesky / nitter / youtube / odysee / RSS parsers | unchanged — comments=NULL |
| NostrEvent.toNormalizedPost | unchanged — engagement-less constructor; kind-6 rawMetadata keys untouched |
