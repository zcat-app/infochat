---
id: M1-641
title: "Topic-grouped periodic digest: category headers + affordance"
status: pending
created: 2026-07-17
last_updated: 2026-07-17
blocked_by: []
files_budget: 12
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestCategorizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategorizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    /summary and its ClusterBlockRenderer — categorization lives only in the
    digest path (DigestRenderer / DigestCategorizer). /summary deliberately keeps
    its current rich per-cluster format (topic_id, uids, classification:, tags:):
    it is an interactive/filterable DM surface, unlike the broadcast digest
    (M1-643 considered this and was abandoned 2026-07-17). Do not touch
    SummaryCommandHandler or ClusterBlockRenderer.
  - >-
    Per-category MESSAGE delivery and per-category roll-up summaries — those
    are M1-642 (Phase 2). Phase 1 stays a single rendered string; SimpleX may
    still size-split it. No change to DigestWorker delivery or the outbound
    chokepoint.
  - >-
    The clustering/dedup step (ClusterTraversal) and the per-cluster prose
    (SummaryProseGenerator) are unchanged — categorization groups the EXISTING
    clusters; it does not re-cluster or re-prose.
  - >-
    The degraded (headlines-only) digest path (DegradedDigestRenderer, D17) —
    it stays as-is: no category headers, no affordance. Only the non-degraded
    prose render gains structure.
acceptance:
  - DigestCategorizerTest.assignsClusterToHighestCountQualifyingTag passes
  - DigestCategorizerTest.tieBreaksAlphabetically passes
  - DigestCategorizerTest.foldsPostAssignmentUnderThresholdIntoOther passes
  - DigestCategorizerTest.untaggedAndBelowThresholdGoToOther passes
  - DigestRendererTest.rendersUppercaseHeadersOrderedBySizeThenAlphaOtherLast passes
  - DigestRendererTest.capsItemsPerSectionWithLocalizedMoreHint passes
  - DigestRendererTest.appendsClosingAffordanceOncePerDigest passes
  - >-
    Categorization is deterministic (pure tag arithmetic, no LLM call): given
    the same clusters + tags the assignment and section order are identical.
    A test asserts a fixed cluster set produces a byte-identical section
    layout across two runs.
  - >-
    docs/spec/commands.md §Periodic group digests documents the categorized
    format (deterministic tag assignment, threshold, Other bucket, per-section
    item cap, closing affordance) and a new decision D62 is recorded in
    docs/spec/decisions.md.
  - mvn -pl infochat-provider verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategorizerTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D17
  - D59
---

# M1-641: Topic-grouped periodic digest — category headers + affordance

## Context

The non-degraded periodic group digest currently renders as a flat,
time-ordered list of per-cluster prose sentences + links (verified live
2026-07-17). Operators asked for structure: group the stories under topic
headers so a reader can scan "what's in Security today" without reading the
whole list, and close with a guiding affordance consistent with the chat
RAG "ask for more" nudge. This is Phase 1 of a two-phase change (Phase 2 =
M1-642). It keeps the dedup + per-cluster prose that operators liked and
adds a deterministic categorization + header layer on top, digest-only.
Contract: `docs/spec/commands.md` §Periodic group digests.

## Acceptance

- A deterministic categorizer assigns each cluster to exactly ONE category:
  among the cluster's tags that qualify (a tag carried by ≥ `category-min-clusters`
  clusters in the digest, config `infochat.digest.category-min-clusters`,
  default 3), pick the one with the highest digest-wide cluster count; ties
  broken alphabetically. A cluster with no qualifying tag → "Other".
- Post-assignment fold-back: any category left with fewer than the threshold
  of assigned clusters is folded into "Other" (a category can lose its
  clusters to a larger co-tag). Deterministic second pass.
- Sections render with an UPPERCASE header (bundle-formatted, e.g. `AI NEWS`,
  `SECURITY NEWS`, `OTHER NEWS`), ordered by assigned-cluster count descending,
  ties alphabetical, with "Other" always last. Under each header the EXISTING
  per-cluster prose + links render unchanged.
- Each section (including Other) caps at `infochat.digest.category-item-cap`
  (default 12) clusters shown; a capped section appends a localized
  "+N more — @mention me to see them" line.
- The digest ends with one localized closing affordance line.
- Categorization uses NO LLM — it is pure tag arithmetic over the already-
  clustered posts, so the digest stays reproducible (the project determinism
  rule: LLM only for prose). The per-cluster prose LLM step is unchanged.
- The named tests above pass; `mvn -pl infochat-provider verify` is green.
- Spec + decision D62 recorded (see §Acceptance list).

## Out-of-scope

Prose in `out_of_scope`. In short: digest-path only. Do not touch `/summary`
/ `ClusterBlockRenderer`, the clustering or per-cluster prose engines, the
degraded (headlines-only) path, or the delivery layer. Per-category message
delivery and per-category roll-up summaries are deferred to M1-642. New
bundle keys need en+cs twins (D43) — adding an `en.properties` key without
its `cs.properties` twin fails `BundleLoaderTest`.

## Notes

**Where it slots in.** `DigestRenderer.render` today does
`clusterTraversal.cluster(posts)` → `summaryProseGenerator.generate(...)` →
concatenate. Insert a categorization step between clustering and assembly: a
new `DigestCategorizer` (digest package) takes `List<Cluster>` and returns an
ordered list of (categoryLabel, clusters); `DigestRenderer` then renders the
header + the existing per-cluster prose per group. `/summary` is untouched — it
uses `ClusterBlockRenderer`, not `DigestRenderer`, and deliberately keeps its
rich flat format (M1-643 considered and abandoned 2026-07-17).

**Cluster-level tags.** A cluster's tag-set is the union of its member posts'
tags (`EligiblePostQuery.Post.tags`, already projected by
`DigestPostCollector`). Count categories at the cluster level (the render
unit), not the post level.

**Sizing evidence (2026-07-17 sweep on the seeded corpus).** Post-level proxy
of the one-category assignment across windows:

| window | posts | raw tags ≥3 | effective categories | Other |
|---|---|---|---|---|
| 6h  | 48  | 10 | 3 | 7 |
| 12h | 87  | 14 | 5 | 7 |
| 24h | 366 | 19 | 6 | 8 |

Greedy "assign to biggest co-tag" collapses 10–19 qualifying tags into **3–6
effective categories** and keeps Other small — so **no category cap is
needed** (it self-bounds) and threshold **3 vs 5 barely moves the count**, so
a plain absolute default of 3 is fine (no relative-threshold machinery). The
per-section ITEM cap is still worth it: the largest category (`ai`) held ~33
clusters.

**Header wording.** Bundle-driven so it localizes: an `en` value like
`{0} NEWS` uppercased in code, with a dedicated "Other" label
(`OTHER NEWS`). Keep caps to the header line only — v1 output is plain text
with no markdown bold (`supportsMarkdownLinks=false`), so caps are the
strongest available header anchor.

**Closing affordance** (group scope → @mention), e.g.
`@mention me to go deeper on any story, or ask about a topic you don't see here.`

**New bundle keys (en+cs twins):** category header format, "Other" label,
per-section "+N more" line, closing affordance.

**Determinism anchor:** decision D62 should state that digest categorization
is deterministic tag arithmetic (reproducible), the LLM touching only the
per-cluster/`/summary` prose — extending the existing "deterministic
retrieval, LLM only for prose" principle to the digest structure.
