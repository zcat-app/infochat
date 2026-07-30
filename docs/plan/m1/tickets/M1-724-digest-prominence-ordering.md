---
id: M1-724
title: "Digest cluster selection is recency-only: the cap keeps the newest stories, never the most significant"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by:
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
    Any FITTED or LEARNED weight. The four weights are operator config
    keys with hand-chosen defaults, tuned later against the live corpus
    by a human reading the emitted components (§Tuning). A diff that
    derives weights from data, adds a training step, or varies them per
    deployment at runtime has left scope.
  - >-
    `post.social_score`'s own `2 * reposts + likes` formula
    (`docs/design/05-llm-and-embeddings.md:461`). That column exists to
    feed the summarizer prompt and keeps its canonical shape; the
    ranking reads `reposts` and `likes` SEPARATELY so their relative
    weight is tunable. The two are allowed to disagree and a diff that
    "reconciles" them by rewriting the column has left scope — see
    §Why the ranking does not use social_score.
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
    A new `ClusterProminence` scores each cluster as a WEIGHTED SUM over
    four percentile-normalized terms — corroboration, reposts, likes,
    source scarcity — gated by the `urgent` classification and
    tie-broken by recency. The full ordering is: (1) clusters carrying
    the `urgent` ingest classification sort ahead of those that do not;
    (2) within each group, descending weighted score; (3)
    `COALESCE(published_at, fetched_at) DESC, id DESC` — the existing
    sort key, unchanged — as the final tiebreak so the order is total.
  - >-
    Every term is an INTEGER PERCENTILE 0–100 within its own population,
    never a raw value. Raw units do not share a scale — distinct-source
    counts run 1–10 while like counts run to five figures, so a weighted
    sum over raw values is a like-count ranking with rounding noise from
    the other terms. A test pins that a cluster with 50 000 likes does
    not outrank a broadly-corroborated cluster.
  - >-
    Percentile is rank-based with ties sharing a value ("percentage of
    the population scoring strictly below"), computed in integer
    arithmetic. No float participates in any ordering comparison, so the
    order cannot drift with floating-point representation across JVMs —
    this is what preserves the D19 byte-identical-replay property
    `/retry --digest` depends on. A test asserts the comparator is a
    valid total order over a 200-cluster fixture (antisymmetric,
    transitive) by sorting, shuffling and re-sorting to the identical
    sequence.
  - >-
    Populations, one per term, each chosen so the comparison is
    like-with-like: corroboration ranks against the OTHER CLUSTERS IN
    THIS DIGEST; reposts and likes rank against clusters of the SAME
    SOURCE KIND in the window; scarcity ranks against the other clusters
    in this digest. A test asserts a Bluesky cluster is never ranked
    against RSS clusters on the reposts or likes term.
  - >-
    The corroboration VALUE fed to the percentile is `distinct sources
    in the cluster ÷ distinct sources that posted under that cluster's
    assigned category tag within the window`, NOT the raw source count.
    A test pins the intent: a 3-source cluster in a tag with 4 active
    sources outranks a 5-source cluster in a tag with 40 active sources.
  - >-
    The scarcity VALUE is the inverse posting volume, in the window, of
    the cluster's least-prolific member source. A test pins that a
    single-source cluster from a source with 2 posts in the window
    outranks a single-source cluster from a source with 300.
  - >-
    Weights are config keys — `infochat.digest.weight.corroboration`
    (default 7), `.reposts` (2), `.likes` (1), `.scarcity` (2) — and the
    denominator is the sum of the weights of the terms actually PRESENT
    on that cluster, not the sum of all four. A cluster with NULL
    reposts and likes is scored out of 9, not given zeros out of 12.
    This is the property that stops editorial sources being
    structurally beaten by social ones; a test asserts an RSS cluster
    and a Bluesky cluster with identical corroboration and scarcity
    percentiles score identically when the Bluesky cluster's social
    percentiles are at the population median.
  - >-
    NULL and 0 stay distinct end to end (M1-723 §Absent is not zero). A
    NULL repost count means the term is absent and drops out of the
    denominator; a repost count of 0 means the term is present with a
    low percentile. A test pins both, and pins that they produce
    DIFFERENT scores for otherwise-identical clusters.
  - >-
    `ClusterProminence` returns the per-term percentiles, the weights
    applied, the denominator and the final score alongside the ordering
    — not an opaque sort. The weights ship uncalibrated and are meant
    to be tuned against the live corpus, which is impossible if the
    inputs cannot be read back. A test asserts the components of a
    known fixture reproduce its score by hand-arithmetic.
  - >-
    The score orders clusters WITHIN a section; it never reorders or
    merges sections, and it never moves a cluster between them. Section
    membership stays D62 tag arithmetic. A test asserts that reordering
    by prominence leaves every section's membership set identical, which
    is what makes a high-scoring cluster unable to starve a small
    category — each section renders its own head regardless of how it
    scores against another section's.
  - >-
    A digest whose posts all carry NULL reposts and likes (the state
    before M1-723 ships anything, and permanently true for an RSS-only
    deployment) is ordered by corroboration and scarcity alone, with no
    NullPointerException, no division by zero in the denominator, and no
    silent collapse to a single score. A test runs the full fixture with
    both columns absent.
  - >-
    `docs/spec/commands.md` §Periodic group digests states that digest
    cluster order within a section is prominence-ordered and that
    `/summary` remains publication-ordered, and a new decision row
    records the weighted-percentile design, the present-terms
    denominator and the within-section scope. The spec currently describes
    only "the existing per-cluster prose + links render unchanged" under
    each header, which no longer says enough.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/summary/ClusterProminenceTest.java
      — each term in isolation with the others held equal; the four
      worked-example clusters in §The design reproduce their stated
      scores; raw-magnitude immunity (50 000 likes loses to
      corroboration); percentile ties share a value; population
      separation by source kind; present-terms denominator; NULL vs 0
      distinctness; the urgent gate outranks a higher-scoring
      non-urgent cluster; determinism over a shuffled input;
      total-order validity over 200 clusters; the all-NULL-social
      fixture; a single-post cluster with no signals still orders
      deterministically rather than throwing.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — reordering by prominence leaves every section's membership set
      identical; a section whose clusters all score low still renders
      its own head; the head of each section is its highest-scoring
      cluster, not its newest.
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
      Every M1-721 section-cap and overflow-line assertion. This
      ticket reorders within sections; it does not change how many
      sections render.
    - >-
      M1-723's `social_score` column value and the tests pinning its
      `2 * reposts + likes` formula. The ranking reads the two inputs
      separately; it does not redefine the column.
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

**Social sources beat editorial ones.** Once `reposts` and `likes`
(M1-723) carry values, any scheme that scores an absent signal as zero
sinks every RSS article beneath every Bluesky post. A blog post has no
likes because blogs have no like button, not because nobody cared.

**Single-source reporting never surfaces.** The item that only one
outlet has is, by construction, the one with no corroboration — and it
is frequently the one worth reading.

## The design: percentile-normalize, then weight

Ordering is `urgent` gate → weighted score → recency tiebreak.

The score is a weighted sum over four terms. Each term is an **integer
percentile within its own population**, and the denominator is the sum
of the weights of the terms **actually present**:

```
score = Σ (weight_t × percentile_t) / Σ (weight_t)    over terms t present
```

| Term | Value ranked | Population | Weight |
|---|---|---|---|
| corroboration | distinct sources ÷ distinct sources active under that tag in the window | other clusters in this digest | 7 |
| reposts | max `post.reposts` in the cluster | clusters of the same source kind | 2 |
| likes | max `post.likes` in the cluster | clusters of the same source kind | 1 |
| scarcity | inverse window post volume of the least-prolific member source | other clusters in this digest | 2 |

Three properties do the actual work:

**Percentile IS the non-linearity.** Engagement is power-law
distributed: 10 → 100 likes is meaningful, 10 000 → 10 100 is noise. A
percentile compresses exactly that way, and unlike a hand-picked `log`
curve it adapts to the observed distribution. (Applying `log` before
taking a percentile would change nothing — percentile depends only on
order.)

**Populations are never mixed.** Bluesky ranks against Bluesky.
Corroboration ranks as a *share of the field reachable for that tag*, so
three of four possible sources beats five of forty.

**A missing term drops out of the denominator, it does not score zero.**
An RSS cluster is scored out of 7 + 2 = 9. This is the single rule that
keeps editorial sources competitive, and it is why M1-723 insists NULL
and 0 stay distinct all the way from the fetcher.

Worked, over a 40-cluster window at the default weights:

| Cluster | corrob. | reposts | likes | scarcity | denom | score |
|---|---|---|---|---|---|---|
| A — 4 RSS sources, no social data | 95 | — | — | 50 | 9 | **85** |
| D — 3 RSS sources, quiet | 70 | — | — | 60 | 9 | **67** |
| C — 2 sources, viral on Bluesky | 50 | 99 | 99 | 40 | 12 | **60** |
| B — 1 Bluesky source, 8k likes, 400 reposts | 20 | 99 | 98 | 30 | 12 | **41** |

Broad corroboration leads; a viral single-source post places but does not
lead; a quiet three-source story still beats a viral one-source story.

**The score never crosses a section boundary.** It orders clusters within
a section and selects the lead (M1-725); it does not reorder sections,
move clusters between them, or decide which sections render — that is
D62 assignment and M1-721's cap. So a category full of low-scoring
stories still renders its own five headlines. An earlier draft added a
"diversity reserve" to guarantee that, back when a single cluster budget
was round-robinned across sections; under the hybrid the section
structure provides it for free, so the reserve is gone.

## Why a weighted sum and not a lexicographic tuple

An earlier draft of this ticket ordered by a strict lexicographic tuple
(urgent → corroboration → social → scarcity → recency), on the argument
that a weighted sum needs weights and weights need labelled data.

That argument is wrong about the cost. Lexicographic ordering does not
avoid a judgement call — it makes the most extreme one available:
corroboration becomes *infinitely* more important than engagement, so a
4-of-4-sources story beats a 3-of-4-sources story no matter how much
larger the second one is. The terms should trade off, and a weighted sum
is the honest way to say by how much. The weights are hand-chosen and
uncalibrated, which is stated rather than hidden.

## Why the ranking does not use `social_score`

`post.social_score` is `2 * reposts + likes` and is canonical for the
summarizer prompt (`05-llm-and-embeddings.md:458-465`). The ranking reads
`reposts` and `likes` separately instead, for two reasons: the repost-to-
like ratio becomes a tunable config key rather than a constant frozen
into a column, and each gets its own percentile against its own
distribution, which a pre-summed value cannot express. The column and the
ranking are permitted to disagree; they answer different questions.

## Tuning

The weights ship uncalibrated. `ClusterProminence` therefore returns its
per-term percentiles, weights, denominator and final score — not just an
ordering — so a digest's ranking can be dumped against the live-test
corpus and read by a human before any weight is changed. Retuning is
then a config edit, not a code change. No feedback loop, no fitting, no
per-deployment adaptation: those are out of scope above.

## Notes

Rejected inputs, recorded so they are not re-proposed:

- **Raw like/repost counts as a global axis.** Imports each platform's
  engagement bias into our ranking and is unavailable for four of six
  fetcher kinds. The social terms are percentiles within source kind.
- **Embedding novelty** (distance from the recent corpus as a
  "first report" signal). Genuinely attractive and cuts usefully against
  corroboration, but `post_embedding` is optional — a post reaches READY
  without one when the embedding stage exhausts retries (D22) — so the
  signal is absent exactly when the pipeline was under stress. Needs its
  own ticket and a decision on how to order embedding-less clusters.
- **Aggregated `/save` rate per source** as a quality prior. Crosses D13.
