---
id: M1-724
title: "Digest cluster selection is recency-only: the cap keeps the newest stories, never the most significant"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by:
  - M1-721
  - M1-723
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/ClusterProminence.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestPostCollector.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterProminenceTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - docs/design/07-deployment.md
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/03-commands.md
complexity: high
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    `infochat.summary.cluster-cap` and its SQL LIMIT
    (`DigestPostCollector.java:88`, `EligiblePostQuery.java:279`). That
    cap is applied to POSTS before `ClusterTraversal` runs, so no
    cluster exists yet to rank; it stays a recency-ordered capacity
    guard. This ticket orders CLUSTERS after traversal. The only change
    to the two collectors is selecting the additional per-post columns
    the prominence function needs.
  - >-
    `ClusterTraversal` and the `post_reference` graph. Which posts form
    a cluster is unchanged; only the order of the resulting clusters
    changes.
  - >-
    `DigestCategorizer`. Category assignment, the qualifying threshold,
    the fold-into-Other pass and SECTION order (assigned-cluster count
    descending, alphabetical ties, Other last) are D62 tag arithmetic
    and stay exactly as they are. This ticket reorders clusters WITHIN
    a section.
  - >-
    `/summary`'s cluster order. It stays publication-ordered. `/summary`
    is a pull where the reader asked for a specific tag and a stable
    reverse-chronological list is the right answer; the digest is a
    push where the reader asked for nothing.
  - >-
    Any LLM participation in ordering. The prominence function is pure
    arithmetic over columns already on `post`. A diff that asks a model
    to rank, score, or pick stories violates D19 and is an escalation,
    not a design choice.
  - >-
    A trained or tuned model, a weighted sum with fitted coefficients,
    or any per-deployment calibration. See §Why lexicographic.
  - >-
    Personalization. The group digest is one artifact delivered to every
    member (spec §Conversation control, `/forget`); per-user ranking
    would require a per-user render and is not v1.
  - >-
    `saved_post` as a ranking input. Aggregating saves across users to
    rank a shared digest crosses the D13 per-user isolation boundary
    and needs a decision before it needs an implementation.
  - any other module
acceptance:
  - >-
    A new `ClusterProminence` computes a total order over clusters as a
    LEXICOGRAPHIC tuple, not a weighted sum. Levels, in order: (1) the
    cluster carries the `urgent` ingest classification; (2) normalized
    corroboration, bucketed; (3) normalized social signal, bucketed;
    (4) source scarcity, bucketed; (5) `COALESCE(published_at,
    fetched_at) DESC, id DESC` — the existing sort key, unchanged, as
    the final total-order tiebreak. Given the same clusters the order is
    byte-identical across runs; a test asserts two independent
    invocations over a shuffled input list produce the identical
    sequence.
  - >-
    Normalized corroboration is `distinct sources in the cluster ÷
    distinct sources that posted under that cluster's assigned category
    tag within the digest's window`, NOT the raw source count. A test
    pins the intent: a 3-source cluster in a tag with 4 active sources
    outranks a 5-source cluster in a tag with 40 active sources.
  - >-
    Normalized social is the cluster's max `post.social_score`
    positioned within the distribution of `social_score` for the SAME
    source kind in the same window. Posts whose `social_score` is NULL
    are EXEMPT from this level — it neither raises nor lowers them, and
    the comparison falls through to level 4. A test asserts an RSS
    cluster and a Bluesky cluster identical at levels 1, 2 and 4 tie at
    level 3 and are separated by level 5; a test asserts a NULL
    social_score is not treated as 0.
  - >-
    Source scarcity is the inverse of the posting volume, in the
    window, of the cluster's least-prolific member source. A test pins
    that a single-source cluster from a source with 2 posts in the
    window outranks a single-source cluster from a source with 300.
  - >-
    Every continuous level (2, 3, 4) is BUCKETED into a small fixed
    number of bands (default 5, `infochat.digest.prominence-buckets`)
    before comparison. Ratios are computed in integer arithmetic against
    the bucket count — no float ever participates in an ordering
    comparison, so the order cannot drift with floating-point
    representation across JVMs. A test asserts the comparator is a valid
    total order over a 200-cluster fixture (antisymmetric, transitive,
    no ties outside level 5) by sorting and re-sorting.
  - >-
    The M1-721 budget reserves a configurable fraction
    (`infochat.digest.diversity-reserve`, default one third, rounded
    down) of its slots for a DIVERSITY pass that ignores levels 1–4 and
    round-robins over sections in publication order. Prominence claims
    the remaining slots. A test pins that with budget 15, reserve 1/3
    and one category holding every high-prominence cluster, the other
    categories still receive 5 slots between them.
  - >-
    A digest whose posts all carry NULL social_score (the state before
    M1-723 backfills anything, and permanently true for an RSS-only
    deployment) produces an order determined entirely by levels 1, 2, 4
    and 5, with no NullPointerException and no silent demotion of every
    cluster. A test runs the full fixture with the column absent.
  - >-
    `docs/spec/commands.md` §Periodic group digests states that digest
    cluster order within a section is prominence-ordered and that
    `/summary` remains publication-ordered, and a new decision row
    records the lexicographic-tuple design and the diversity reserve.
    The spec currently describes only "the existing per-cluster prose +
    links render unchanged" under each header, which no longer says
    enough.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterProminenceTest.java
      — each level in isolation with the others held equal; the
      normalization cases named in the acceptance criteria; NULL
      social_score exemption; determinism over a shuffled input;
      total-order validity over 200 clusters; all-NULL-social fixture;
      a cluster with a single post and no signals at all still orders
      deterministically rather than throwing.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — the diversity reserve leaves slots for low-prominence sections;
      reserve 0 gives every slot to prominence; reserve 1 (whole budget)
      reproduces the M1-721 round-robin exactly, which pins that the
      two selection paths compose rather than conflict.
  preserves:
    - >-
      `DigestCategorizerTest` in full — section order and category
      assignment are untouched.
    - >-
      `/summary`'s publication order: the existing
      `SummaryCommandHandlerTest` ordering assertions and the M1-689
      `COALESCE(published_at, fetched_at) DESC, id DESC` sort-key
      assertions must pass unchanged. A test asserts a `/summary` over
      the same fixture the digest reorders returns the OLD order.
    - >-
      Every M1-721 budget and allocation assertion.
    - >-
      `EligiblePostQueryTest` / `DigestPostCollectorTest` window
      semantics (`ready_at` membership, M1-689) — this ticket adds
      selected columns, not predicates. A test asserts the row SET
      returned is unchanged.
    - >-
      `DigestWorkerTest` per-category delivery (D63) message counts.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/commands.md §Content
  - docs/design/03-commands.md §Periodic group digests
decision_refs:
  - D19
  - D62
  - D13
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-724: the digest keeps the newest stories, never the most significant

## Context

Nothing in the digest path ranks. Three separate places make a
keep-or-drop decision and all three key on time:

| Site | Decision | Key |
|---|---|---|
| `DigestPostCollector.java:88` | which posts enter the digest | `LIMIT clusterCap`, DESC — "keeps the freshest posts and drops the oldest" |
| `DigestRenderer.java:105,127` | which clusters render under a header | `Math.min(size, categoryItemCap)` — takes the head of a publication-ordered list |
| `DigestCategorizer.java:109` | which sections come first | assigned-cluster count, alphabetical tie |

Section order is the only one that is not purely temporal, and it ranks
*categories* by size, not *stories* by significance.

The corroboration signal exists and is displayed but never used: the
`score:` line is computed at render time as a distinct-source count
(`ClusterBlockRenderer.java:105-115`, comment: "placeholder shape for
MVP") and only appears in `--flat`, which is not the digest's form. So
the digest computes cross-source clustering — the expensive part — and
then throws away what the cluster shape tells it.

## The balance problem

Corroboration alone is not a fair ranking, and this is the whole
difficulty of the ticket. Three failure modes, each of which a naive
"sort by source count" produces:

**Popular topics beat significant ones.** A tag with 40 subscribed
sources produces 5-source clusters routinely; a tag with 4 sources can
never produce more than a 4-source cluster. Raw counts rank the tag, not
the story.

**Social sources beat editorial ones.** Once `social_score` (M1-723)
carries a value, any scheme that scores an absent signal as zero sinks
every RSS article beneath every Bluesky post. A blog post has no likes
because blogs have no like button, not because nobody cared.

**Single-source reporting never surfaces.** The item that only one
outlet has is, by construction, the one with no corroboration — and it
is frequently the one worth reading.

## The design: normalize within population, never across

Each level compares like with like, and a missing signal is an
**exemption from that level**, never a zero on it:

1. **`urgent` classification** — already assigned at ingest over the
   closed set `{factual, opinion, technical, urgent, ongoing, unknown}`
   and currently only displayed. Free to consume.
2. **Normalized corroboration** — the cluster's distinct sources as a
   fraction of the sources *reachable for that topic* (distinct sources
   that posted under the cluster's category tag in the window). Three of
   four possible sources is near-total coverage; five of forty is noise.
   This is the level that makes a niche tag comparable to a busy one.
3. **Normalized social** — `social_score` positioned within the
   distribution *for the same source kind*. A Bluesky post is compared
   against Bluesky posts. RSS, YouTube, Odysee, nitter and Nostr posts
   have NULL and skip the level entirely.
4. **Source scarcity** — inverse posting volume of the cluster's
   least-prolific source. A source that publishes twice a month spends
   more signal per post than one publishing 300 times a week. This is
   the level that carries a single-source niche article, which by
   definition scores nothing at levels 2 and 3.
5. **Recency** — the existing `COALESCE(published_at, fetched_at) DESC,
   id DESC` key, retained verbatim as the final tiebreak so the order is
   total.

And because no scoring function is trustworthy enough to be given the
whole budget, a **diversity reserve** (default one third of the M1-721
budget) is allocated by plain round-robin over sections, ignoring levels
1–4. Prominence cannot starve a category; it can only decide who wins
the contested slots.

## Why lexicographic and not a weighted sum

A weighted sum needs weights, and weights need labelled data about which
stories the group actually wanted — which does not exist and would take
months of feedback to gather. A lexicographic tuple needs no
calibration: each level is a strict tiebreak on the one above it. It is
also explainable, which matters for a push artifact — "this led because
it was marked urgent, then because 3 of the 4 sources covering that tag
carried it" is a sentence; a 0.73 is not.

Every continuous level is bucketed into 5 bands before comparison. This
is not a rounding convenience: it keeps floats out of the comparator
entirely, so the D19 byte-identical-replay property that `/retry
--digest` depends on cannot be broken by floating-point representation
differing across JVMs or platforms.

## Notes

Rejected inputs, recorded so they are not re-proposed:

- **Raw like/repost counts as a global axis.** Imports each platform's
  engagement bias into our ranking and is unavailable for four of six
  fetcher kinds. Level 3 uses them only within source kind.
- **Embedding novelty** (distance from the recent corpus as a
  "first report" signal). Genuinely attractive and cuts usefully against
  corroboration, but `post_embedding` is optional — a post reaches READY
  without one when the embedding stage exhausts retries (D22) — so the
  signal is absent exactly when the pipeline was under stress. Needs its
  own ticket and a decision on how to order embedding-less clusters.
- **Aggregated `/save` rate per source** as a quality prior. Crosses D13.
