---
id: M1-723
title: "post.likes / post.reposts / post.social_score are declared, parsed at two fetchers, and never persisted"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParserTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParserSocialSignalTest.java
  - docs/design/02-schema.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Any consumer of the three columns. This ticket makes them TRUE; it
    does not make anything read them. The summarizer prompt's
    `{{#has_social}}social score: {{score}}{{/has_social}}` block
    (`docs/design/05-llm-and-embeddings.md:437`) stays unimplemented,
    and digest ranking is M1-724. Wiring a consumer here would mean
    shipping a ranking change whose input has never been observed on
    real data.
  - >-
    A migration. `V7__joins_post.sql:160-162` already declares all
    three columns as nullable INT. Nothing in the DDL changes;
    `migration_touch: false` is correct and a diff adding a `V66__` is
    out of scope.
  - >-
    `social_score` as a stored generated column. The design
    (`05-llm-and-embeddings.md:458`) says the value is "computed
    deterministically in SQL before the prompt is built" — that is a
    read-time computation, and V7 declares a plain `INT`. Converting it
    to `GENERATED ALWAYS AS` is a schema change this ticket does not
    make; the column is written by the INSERT alongside its two inputs.
  - >-
    The nitter, youtube, odysee and RSS fetchers. `NitterFetcher` does
    not scrape HTML at all — it GETs the instance's `/<username>/rss`
    endpoint and delegates to `RssFeedParser` (`NitterFetcher.java:14-17`),
    and Nitter's RSS 2.0 output carries no like or retweet elements, so
    there is nothing to extract without adding a second transport.
    YouTube's and Odysee's parsers extract no engagement fields today,
    and RSS has none by format. All four keep writing NULL for all three
    columns, which is the documented "no social signals" case, NOT zero.
    This is not a small residue: nitter alone is 1,868 of the 9,236
    posts in the live-test corpus (M1-714 §Context), so the NULL branch
    is the common path and its handling is load-bearing.
  - >-
    Nostr. `NormalizedPost.rawMetadata` is load-bearing for the kind-6
    repost dispatch (`NostrStreamSource.java:461`,
    `Kind6Handler.java:128`) and its key namespace must not be
    disturbed. Nostr has no like/repost counts in the events we
    subscribe to and keeps writing NULL.
  - >-
    Backfill of existing rows. Posts already persisted keep NULL; the
    columns are populated going forward only. `post` is partitioned by
    `fetched_at` with TTL partition drops (D33), so the NULL cohort
    ages out on its own.
  - any other module
acceptance:
  - >-
    `NormalizedPost` carries three new `@Nullable Integer` components —
    `likes`, `reposts`, `socialScore` — rather than continuing to smuggle
    engagement through the `rawMetadata` string map. `rawMetadata` keeps
    its existing Nostr keys untouched.
  - >-
    `socialScore` is computed at construction as
    `2 * coalesce(reposts,0) + coalesce(likes,0)`, matching the canonical
    formula in `docs/design/05-llm-and-embeddings.md:461` verbatim. When
    BOTH `likes` and `reposts` are null the score is null, NOT zero — a
    post from a source with no social signals must be distinguishable
    from a social post that nobody engaged with. A test pins both cases.
  - >-
    `BlueskyResponseParser` populates `likes` from `likeCount` and
    `reposts` from `repostCount`, and stops writing those two keys into
    `rawMetadata`. A malformed or absent count yields null for that
    field, not 0 — `asInt(0)`'s current defaulting is replaced by an
    explicit missing-node check, because this is a system boundary
    (untrusted upstream JSON) where absent and zero are different facts.
  - >-
    `RedditResponseParser` populates `likes` from `score` and leaves
    `reposts` null (Reddit exposes no repost count; `num_comments` is
    not a repost and must not be mapped to one). It stops writing
    `score` into `rawMetadata`. Reddit's `score` is a net value and can
    be NEGATIVE; it is persisted as-is and the `socialScore` formula is
    applied unchanged, so a heavily-downvoted post yields a negative
    social score rather than being clamped. A test pins a negative
    score surviving to the INSERT.
  - >-
    `PostPersister`'s INSERT lists `likes, reposts, social_score` and
    binds them from the `NormalizedPost`. The column count in the
    statement and the bind-index sequence stay consistent — a test
    asserts a round-trip through `PostPersisterTest` reads back the
    three values it wrote, and that the pre-existing 19 columns keep
    their current values.
  - >-
    The three columns are bounded at the ingest boundary: a value whose
    magnitude exceeds `Integer.MAX_VALUE / 4` is clamped, so the
    `2 * reposts + likes` arithmetic cannot overflow into a negative
    social score from an upstream-supplied count. This is boundary
    validation on untrusted fetcher input, not internal defensive
    code. A test feeds `Integer.MAX_VALUE` as `repostCount` and asserts
    the persisted `social_score` is positive and clamped.
  - >-
    `docs/design/02-schema.md` §2.3.1's `social_score` column comment
    (line 750) states which fetchers populate it and that NULL means "no
    social signal available", distinct from 0.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterTest.java
      — a NormalizedPost carrying likes/reposts/socialScore round-trips
      all three; a NormalizedPost with all three null persists NULL (not
      0) in all three columns; the 19 pre-existing columns are unchanged
      in both cases.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParserTest.java
      — likeCount/repostCount land on the typed fields; an absent
      likeCount yields null rather than 0; socialScore is
      2*reposts+likes; likeCount and repostCount no longer appear in
      rawMetadata; Integer.MAX_VALUE repostCount is clamped and the
      resulting socialScore is positive.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParserSocialSignalTest.java
      — score lands on likes; reposts stays null; num_comments is NOT
      mapped to reposts; a negative score persists negative.
  preserves:
    - >-
      Every Nostr test. `NostrStreamSourceTest`, `NostrEventTest`,
      `Kind6HandlerTest` and the kind-6 repost-edge ITs must pass
      unchanged — `rawMetadata`'s META_KIND / META_REPOST_TARGET keys
      and the dispatch that reads them are untouched.
    - >-
      The nitter, youtube, odysee and RSS parser tests, which must keep
      producing NormalizedPosts with all three fields null.
    - >-
      The three existing sibling Reddit parser suites —
      `RedditResponseParserItemCapTest`,
      `RedditResponseParserNameValidationTest` and
      `RedditResponseParserPermalinkTest`. The new social-signal cases
      go in a fourth per-concern suite matching that naming convention,
      not appended to one of them.
    - >-
      Every existing `PostPersisterTest` assertion, including the
      outbox `status='RAW'` invariant, the `WHERE NOT EXISTS` uid guard
      and the `ON CONFLICT ... DO NOTHING` dedup behaviour.
    - all tests currently green on main
spec_refs:
  - docs/design/02-schema.md §2.3.1
  - docs/design/05-llm-and-embeddings.md §5.4.5
decision_refs:
  - D33
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-723: social-signal columns are declared, parsed, and thrown away

## Context

`V7__joins_post.sql:160-162` declares three columns on `post`:

```sql
social_score        INT,
likes               INT,
reposts             INT,
```

`docs/design/05-llm-and-embeddings.md:458-465` specifies them in
detail — a canonical formula (`social_score = 2 * COALESCE(reposts, 0) +
COALESCE(likes, 0)`), a commitment that it is computed deterministically
in SQL rather than asked of the LLM, and a summarizer-prompt block
`{{#has_social}}social score: {{score}}{{/has_social}}` that is
suppressed for sources without social signals.

Two of the six fetchers already extract the inputs:

| Parser | Extracted | Destination |
|---|---|---|
| `BlueskyResponseParser.java:109-110` | `likeCount`, `repostCount` | `rawMetadata` map |
| `RedditResponseParser.java:140-141` | `score`, `num_comments` | `metadata` map |

And then it is dropped. `PostPersister.java:144-155` writes nineteen
columns:

```
id, uid, source_id, upstream_identifier, url, title, body,
author, published_at, fetched_at, status,
stage1_done, stage2_done, tagger_done, embedding_done,
stage1_flagged, stage2_failed, tagger_fallback, tags
```

None of the three. `rawMetadata` reaches only the Nostr kind-6 dispatch
(`NostrStreamSource.java:461`), so for Bluesky and Reddit the map is
constructed, carried through the pipeline, and discarded.

## Census

The defect class is "a declared `post` column with no writer". Enumerated:

```bash
grep -rn "social_score\|likes\|reposts" --include=*.java --include=*.sql . \
  | grep -v '^./.bench' | grep -vi 'nostr\|repost_edge\|kind-6\|NIP-18'
```

Three hits, all three in `V7__joins_post.sql`. No Java file in any module
reads or writes any of them. `body_summary` is the same class of defect
and is already filed as M1-715; these three are not covered by it.

## Why this is filed as a fetcher/persister ticket and not a ranking one

The columns' only proposed consumer is digest ranking (M1-724). Writing
the ranking against a column that has never held a value on real data
would mean tuning a selection rule against zeros. This ticket makes the
data exist and observable — `social_score` can then be inspected across
the live corpus before anything selects on it.

## Absent is not zero

The single correctness point running through the acceptance criteria:
NULL and 0 must stay distinct at every hop. An RSS article has no like
count; a Bluesky post with `likeCount: 0` was seen and ignored. The
current `asInt(0)` defaulting in both parsers erases that difference at
the boundary, and the design's `{{#has_social}}` suppression depends on
it surviving. Any consumer that treats a missing signal as a zero score
sinks every RSS source below every social source — the exact failure a
ranking change would then be blamed for.

## Notes

Reddit's `score` is a net vote count and is legitimately negative. It is
persisted unclamped in sign (only magnitude is bounded, against overflow),
because a negative social score is real information about a post and the
formula's other input, `reposts`, is null for Reddit anyway.
