---
id: M1-598
title: "Provider: render real per-post classification in /summary (union, drop 'unknown' unless sole)"
status: pending
created: 2026-07-08
last_updated: 2026-07-08
blocked_by:
  - M1-597
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/summary/EligiblePostQueryIT.java
  - docs/design/03-commands.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Everything the collector/schema half (M1-597) owns: the V57 migration, the
    ClassifierWorker, ModelTask/LlmRouter, prompts, ReadyPromoter, and the
    02-schema / 05-llm / architecture design updates. This ticket only CONSUMES
    the `post.classification` column M1-597 lands. Do NOT touch infochat-collector,
    infochat-llm-adapter, or infochat-core.
  - >-
    The reply bundle keys. `reply.summary.cluster.classification_label` (en + cs),
    the BundleKeys constant, and the renderer's `classification:` line already
    exist (restored by the M1-591 revert). This ticket changes only the DATA
    SOURCE of that line (tag-union copy → the real classification union); it does
    NOT add or remove any bundle key, so the D43 bilateral keyset is untouched.
  - >-
    Widening the deterministic query with anything other than the single
    `p.classification` column, or changing the ORDER BY / cluster-cap / cutoff
    logic. The retrieval determinism (docs/spec/llm.md §Determinism boundary) and
    byte-identical replay (D19/D36) are preserved: classification is read from the
    DB like tags, so both call sites (/summary + /retry) emit the same bytes for
    the same DB state.
acceptance:
  - >-
    EligiblePostQuery.selectPosts adds `p.classification` to the SELECT projection
    and the `Post` record gains a `List<String> classification` field (non-null,
    mirroring `tags`), populated from the array column. No other query change.
    EligiblePostQueryIT asserts classification is projected (a seeded post's
    classification round-trips into the Post record).
  - >-
    ClusterBlockRenderer renders the `classification:` line from the REAL
    classification union, not the tag copy. A new `joinedClassifications(posts)`
    helper unions the cluster's posts' `classification` lists, then applies the
    `unknown` rule: drop `unknown` when any substantive label is present in the
    union, and emit `classification: unknown` only when the whole union is exactly
    {unknown}. The `tags:` line is unchanged (still `joinedTags`). The two lines
    are now genuinely independent — a cluster can show `classification: factual,
    technical` above `tags: security, ai`.
  - >-
    NAMED TESTS. ClusterBlockRendererTest's en and cs byte-for-byte blocks are
    updated so classification is seeded DISTINCT from tags (proving the lines no
    longer mirror), plus two new cases: (a) a cluster whose union mixes `unknown`
    with a substantive label renders the substantive label only (no `unknown`),
    and (b) a cluster whose union is only `unknown` renders `classification:
    unknown`. SummaryCommandHandlerTest asserts the `classification:` line is
    present and reflects the seeded classification (not the tag set). Red-before /
    green-after on the old tags-copy assertions.
  - >-
    docs/design/03-commands.md §`/summary` output sample keeps the distinct
    `classification:` line (already `classification: technical, urgent` above
    `tags: security, ai`) and a one-line note is added that classification is the
    per-post ingest classification union (with `unknown` shown only when nothing
    else applies). This is the only design edit here; the schema/pipeline docs are
    M1-597's.
  - mvn verify is green from the repo root.
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/.../command/ClusterBlockRendererTest.java — classification
      seeded distinct from tags; add the union `unknown`-strip and sole-`unknown`
      cases.
    - >-
      infochat-provider/.../command/SummaryCommandHandlerTest.java — assert
      classification reflects seeded classification, not tags.
    - >-
      infochat-provider/.../summary/EligiblePostQueryIT.java — assert
      p.classification is projected into the Post record.
  preserves:
    - all tests currently green on main
    - >-
      the D43 bilateral bundle keyset (no bundle key added/removed) and the
      score-plural / covered-by / summary render cases (untouched).
    - >-
      byte-identical replay (D19/D36): the shared renderer emits the same new
      shape at /summary and /retry.
spec_refs:
  - docs/design/03-commands.md §`/summary [tag] [-w 24h]`
  - docs/design/05-llm-and-embeddings.md §5.4.4 Summarizer (cluster mode)
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D36
  - D43
redteam_findings: []
redteam_audits: []
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
---

# M1-598: render real per-post classification in /summary

## Context

M1-597 lands `post.classification` (a per-post ingest evaluation over the closed
enum `{factual, opinion, technical, urgent, ongoing, unknown}`) and populates it
in the collector. This ticket is the provider half: project that column into the
`/summary` retrieval and render the cluster `classification:` line from it, so
the line finally carries real, distinct data instead of the reverted tag-union
stub.

## The fix

1. `EligiblePostQuery`: add `p.classification` to the deterministic SELECT and a
   `classification` field to the `Post` record.
2. `ClusterBlockRenderer`: replace the classification line's data source
   (`joinedTags` → a new `joinedClassifications`) that unions the cluster's
   per-post classifications and drops `unknown` unless it is the sole label.
3. Tests pin that `classification:` and `tags:` are now independent, plus the
   two `unknown` render rules.
4. `03-commands.md` §`/summary` keeps the distinct sample and notes the
   per-post-union + `unknown` semantics.

The bundle key, BundleKeys constant, and the `classification:` line itself
already exist (restored by the M1-591 revert), so no bundle change is needed —
only the line's data source changes.

## Determinism (D19/D36)

Classification is read from the DB (like tags), so the shared renderer emits the
same bytes at `/summary` and `/retry` for the same DB state — byte-identical
replay holds. This ticket does not touch the ORDER BY, cluster cap, or cutoff.

## Notes

- **Blocked by M1-597** — the `post.classification` column must exist and be
  populated first. Until M1-597 merges, the column is absent and this ticket
  cannot compile its SELECT.
- **`unknown` render rule.** `unknown` is a real per-post label but a "no-signal"
  one at cluster level: the union drops it when any substantive label is present,
  and shows `classification: unknown` only when the entire cluster union is
  `{unknown}` — so the line is always populated and never mirrors tags.
