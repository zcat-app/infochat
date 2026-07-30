---
id: M1-723
title: "post.likes / post.reposts / post.social_score are declared, parsed at two fetchers, and never persisted"
status: done
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 14
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/outbox/PostPersister.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParser.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParser.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/NormalizedPost.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyResponseParserTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditResponseParserSocialSignalTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherTest.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/testsupport/ScanWindowFixtureGuardTest.java
  - docs/plan/m1/scan-window-fixture-census.md
  - docs/design/02-schema.md
  - docs/design/05-llm-and-embeddings.md
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
    `BlueskyFetcherTest`'s `meta.get("likeCount")` / `meta.get("repostCount")`
    assertions are **retargeted** onto the typed fields, not deleted —
    the end-to-end fetch path keeps pinning both counts.
  - >-
    `RedditResponseParser` populates `likes` from `score` and leaves
    `reposts` null (Reddit exposes no repost count; `num_comments` is
    not a repost and must not be mapped to one). It stops writing
    `score` into `rawMetadata`; `num_comments`, `author` and `subreddit`
    stay in the map untouched. `RedditFetcherTest`'s `meta.get("score")`
    assertion is **retargeted** onto `likes`, not deleted.
    Reddit's `score` is a net value and can
    be NEGATIVE; it is persisted as-is and the `socialScore` formula is
    applied unchanged, so a heavily-downvoted post yields a negative
    social score rather than being clamped. A test pins a negative
    score surviving to the INSERT.
  - >-
    `PostPersister`'s INSERT lists `likes, reposts, social_score` and
    binds them from the `NormalizedPost`. The column count in the
    statement and the bind-index sequence stay consistent — a test
    asserts a round-trip through `PostPersisterIT` reads back the
    three values it wrote, and that the pre-existing 19 columns keep
    their current values. The round-trip lives in `PostPersisterIT`,
    not `PostPersisterTest`: the latter is a no-database unit test of
    the pure `normalizeTitle` helper and cannot read a row back.
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
  - >-
    `docs/design/05-llm-and-embeddings.md` §5.4.5's "`social score`
    computation" paragraph no longer asserts that posts without social
    signals have `social_score = 0` — it states they carry NULL and that
    the `{{#has_social}}` block is suppressed on NULL. Without this the
    two design docs contradict each other on the exact point the ticket
    establishes: `02-schema.md` would say NULL-is-not-zero one file away
    from the canonical-formula paragraph saying the no-signal case is 0.
    The `social_score = 2 * COALESCE(reposts, 0) + COALESCE(likes, 0)`
    formula line itself is unchanged, and the prompt block stays
    unimplemented per out_of_scope — this is a doc-consistency edit, not
    consumer work.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/outbox/PostPersisterIT.java
      — a NormalizedPost carrying likes/reposts/socialScore round-trips
      all three; a NormalizedPost with all three null persists NULL (not
      0) in all three columns; the 19 pre-existing columns are unchanged
      in both cases. The IT, not PostPersisterTest: reading a row back
      needs the database.
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
  modifies:
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/testsupport/ScanWindowFixtureGuardTest.java
      — the new Reddit suite joins BENIGN_BASELINE alongside the three
      sibling parser suites already listed there. Its FETCHED_AT is
      parser input: RedditResponseParser.parse stamps it onto the
      NormalizedPost and substitutes it for a missing created_utc, never
      comparing it against now, so there is no pickup gate to detach
      from (engineering-rules §9's first benign category).
    - >-
      docs/plan/m1/scan-window-fixture-census.md — the new suite is added
      to the existing parser-input bullet that already names
      RssFeedParserTest and the three sibling Reddit suites, per the
      guard's "record why" requirement.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/bluesky/BlueskyFetcherTest.java
      — the two rawMetadata assertions at lines 170-171 move onto
      first.likes() / first.reposts(). Retargeted, not dropped: the
      end-to-end fetch path must keep pinning both counts.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherTest.java
      — the meta.get("score") assertion at line 186 moves onto
      first.likes(). The sibling author / num_comments / subreddit
      assertions stay on rawMetadata unchanged.
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
      Every existing `PostPersisterIT` assertion, including the
      outbox `status='RAW'` invariant, the `WHERE NOT EXISTS` uid guard
      and the `ON CONFLICT ... DO NOTHING` dedup behaviour. Also every
      existing `PostPersisterTest` case (the `normalizeTitle` unit
      suite), which this ticket does not touch.
    - all tests currently green on main
spec_refs:
  - docs/design/02-schema.md §2.3.1
  - docs/design/05-llm-and-embeddings.md §5.4.5
decision_refs:
  - D33
reviews:
  - round: 1
    date: 2026-07-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
      assertion_adequacy: PASS
    diff_stats:
      files: 18
      added: 1335
      removed: 32
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-30
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §Threat model — "The Collector is exposed to
      arbitrary feed content. Every RSS publisher, Reddit poster, Bluesky
      user, etc. is untrusted." §Trust boundaries item 9 sets the in-model
      standard for a wire-derived NUMERIC crossing a trust boundary:
      provider-reported numeric usage is checked at the boundary so no
      counter "can be driven backwards or inflated to a magnitude that
      swamps later honest increments". The diff introduces the ingest-side
      mirror and asserts the same bound in NormalizedPost's javadoc.
    gap: |
      The clamp is applied to an int that has already lost its magnitude
      and its SIGN. Both parsers admit any JSON numeric node and narrow it
      with asInt(), which is a truncating cast, not a saturating one, so a
      JSON integer outside int range wraps modulo 2^32 BEFORE
      NormalizedPost's compact constructor and clampCount ever see it.
      likeCount 2147483648 becomes -2147483648, clamps to
      -MAX_ENGAGEMENT_COUNT and yields socialScore -1610612733 — the exact
      negative the javadoc says cannot occur. likeCount 4294967296 becomes
      exactly 0 and is persisted as a non-NULL 0, the precise null-vs-zero
      conflation the boundary exists to prevent. Both diff tests stop at
      2147483647, one below the first wrapping value, so nothing pins it.
    repro: |
      A hostile or compromised bluesky/reddit upstream (the case the threat
      model names directly) answers with a well-formed feed entry carrying
      "likeCount": 2147483648, "repostCount": 2147483648. intOrNull accepts
      them (isNumber() is true), asInt() wraps each to -2147483648,
      clampCount floors each at -536870911, and PostPersister stores
      likes=-536870911, reposts=-536870911, social_score=-1610612733.
      Repeat with "likeCount": 4294967296 to store a fabricated likes=0 /
      social_score=0 on a row that should have carried a clamped magnitude
      or SQL NULL. Developer-confirmed against Jackson 2.21: asInt() yields
      -2147483648, 0 and 1661992959 for 2147483648, 4294967296 and
      99999999999999999999 respectively; canConvertToInt() is false for all.
    suggested_fix_class: input-sanitization
    remediated: 2026-07-30
    remediation: |
      Both parsers' intOrNull now SATURATE instead of narrowing: gated on
      JsonNode.canConvertToInt(), an out-of-range value becomes
      Integer.MIN_VALUE / MAX_VALUE by sign (decimalValue().signum())
      before NormalizedPost's magnitude clamp runs, so the clamp bounds a
      value that reached int intact. Verified against Jackson 2.21:
      2147483648 now yields +MAX_ENGAGEMENT_COUNT (was -536870911),
      4294967296 yields +MAX_ENGAGEMENT_COUNT (was a fabricated 0), and
      -2147483649 yields -MAX_ENGAGEMENT_COUNT (was +536870911, a sign
      flip the original finding did not name). Honest in-range values are
      byte-identical. NormalizedPost's javadoc now states the saturate-
      never-narrow obligation as part of the record's contract, since the
      bound is only meaningful for a value that reached int intact. Six
      new tests pin the wrap boundaries in both parser suites (the prior
      tests stopped at 2147483647, one below the first wrapping value).
redteam_audits:
  - date: 2026-07-30
    verdict: FINDINGS
    base: 312e6f46de01327a677aea13cb776d05154ed12f
    head: "<working tree>"
    verdict_file: docs/plan/m1/redteam/M1-723-2026-07-30.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Ran at the /m1-tick run redteam gate, ahead of review, against the
      uncommitted working tree (branch carried 0 commits). The skill's
      step-1 merged-form grep matches the ticket-spec refine commit
      312e6f46 rather than an implementation commit, which would have
      produced a vacuous CLEAN over a docs-only diff; the --in-progress
      uncommitted form was used instead. The one low finding was
      independently reproduced by the developer against Jackson 2.21 before
      disposition. It falsifies a bound NormalizedPost's own javadoc
      asserts and defeats the NULL-vs-zero distinction the ticket exists to
      establish, so it belongs to M1-723, not a follow-up. Two out-of-model
      items recorded: in-range engagement inflation (a source claiming
      MAX_ENGAGEMENT_COUNT dominates any future social ranking — relevant
      to M1-724, which wires the first consumer), and Jackson number-coercion
      fidelity loss generally.
  - date: 2026-07-30
    verdict: CLEAN
    base: 312e6f46de01327a677aea13cb776d05154ed12f
    head: "<working tree, round 2>"
    verdict_file: docs/plan/m1/redteam/M1-723-2026-07-30-r2.md
    out_of_model_count: 3
    note: |
      Re-audit of the remediated diff. The adversary was given the round-1
      GAP verbatim, told not to assume it closed merely because
      remediation was attempted, and explicitly authorized to return
      CLEAN. It confirmed closure across LongNode/BigIntegerNode and
      verified Integer.MIN_VALUE hits the clamp's `<` branch with no
      Math.abs self-negation bug. Recorded an out-of-model note that the
      new saturate path reached decimalValue(), which throws on a
      non-finite double — see round 3, where that was fixed.
  - date: 2026-07-30
    verdict: CLEAN
    base: 312e6f46de01327a677aea13cb776d05154ed12f
    head: "<working tree, round 3>"
    verdict_file: docs/plan/m1/redteam/M1-723-2026-07-30-r3.md
    out_of_model_count: 2
    note: |
      Final audit, covering the non-finite-double fix (doubleValue() +
      Double.isFinite, returning null for a value that is not a
      representable count). CLEAN with zero findings. Independently
      verified that doubleValue()'s lossiness is sign-preserving, that
      every numeric node shape isNumber() admits reaches a correct
      branch, that NaN takes the null branch rather than the ternary's
      MAX, and that the null-vs-saturate asymmetry grants an adversarial
      feed no capability it lacks. Both out-of-model items are
      informational and closed by the auditor's own analysis.
clarity_check:
  date: 2026-07-30
  verdict: WARN
  warnings:
    - >-
      lint: PASS (0 blockers, 0 warnings). Census grep re-run live at
      start; 3 hits, all in V7__joins_post.sql, matching the §Census
      table.
    - >-
      Self-check found three false ticket-vs-code claims; user chose
      "widen to 12" on the blocking question and the ticket was refined
      before start. (1) PostPersisterTest.java is a 72-line no-database
      unit test of normalizeTitle — it cannot host the round-trip
      read-back acceptance 5 asks for, and the status='RAW' /
      WHERE NOT EXISTS / ON CONFLICT assertions test_plan credits it
      with actually live in PostPersisterIT.java. (2) Dropping the
      rawMetadata keys breaks BlueskyFetcherTest.java:170-171 and
      RedditFetcherTest.java:186, neither in the original files_scope.
      (3) docs/design/05-llm-and-embeddings.md:463 states no-signal
      posts have social_score = 0, contradicting the ticket's central
      NULL-is-not-zero rule. files_budget 8 -> 12; acceptance 5 and
      test_plan retargeted onto the IT; acceptance 8 added for the
      design-doc contradiction.
  blockers: []
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
