---
id: M1-721
title: "Digest length is a function of tag count: nothing bounds how many category sections a digest renders"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestCategorizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategorizerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - docs/design/07-deployment.md
  - docs/spec/commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    `/summary`'s render path. `SummaryCommandHandler.java:332` keeps the
    PER-CATEGORY `categoryItemCap` semantics unchanged, and gains no
    section cap. `/summary` is an interactive pull where the reader
    chose to ask and `--full` already exists as the uncapped escape; the
    digest is a push into a group chat twice a day.
  - >-
    `infochat.summary.cluster-cap` (`DigestPostCollector.java:34`) and
    its oldest-drops eviction. That is a POST-level capacity guard sized
    to the LLM's throughput, applied by SQL LIMIT before
    `ClusterTraversal` runs. Unchanged and still in force.
  - >-
    `infochat.digest.category-item-cap` (default 12). It stays as the
    bound on clusters WITHIN a section for the modes that still render
    per-cluster prose. This ticket adds a bound on the NUMBER of
    sections; the two are independent.
  - >-
    Which clusters survive, and cluster order within a section. This
    ticket bounds sections only. Prominence ordering is M1-724 and must
    not be anticipated here — shipping both at once would make a
    digest-size regression indistinguishable from a ranking regression.
  - >-
    Category ASSIGNMENT — the qualifying-tag threshold, the
    highest-count-wins rule, the fold-into-Other second pass, and
    section order (assigned-cluster count descending, alphabetical ties,
    Other last). All D62 tag arithmetic, all unchanged. The cap is
    applied to the ordered section list the categorizer already returns.
  - >-
    The degraded (D17) digest path and the zero-posts reply — neither
    has category structure to bound.
  - any other module
acceptance:
  - >-
    A new config key `infochat.digest.max-categories` (default 8) bounds
    how many category sections a single non-degraded digest renders. The
    cap is applied to the tail of `DigestCategorizer`'s ordered section
    list, so the sections dropped are the SMALLEST ones — section order
    is already assigned-cluster count descending.
  - >-
    **Other is never dropped by the cap.** It is the bucket that catches
    clusters with no qualifying tag, so evicting it silently discards
    the content least likely to be reachable another way. When the cap
    binds and Other is present, Other occupies the last slot and one
    more real category is dropped in its place. A test pins that a
    digest with 12 real categories plus Other at cap 8 renders 7 real
    categories and Other.
  - >-
    Clusters belonging to dropped sections are NOT redistributed into
    surviving sections or folded into Other. Folding them into Other
    would make Other grow without bound exactly when the cap binds,
    which is the opposite of what the cap is for. They are simply not
    rendered, and the overflow line accounts for them.
  - >-
    A capped digest appends ONE localized overflow line, on the last
    section, naming the number of categories not shown and steering to
    `/summary` — a new bundle key `reply.digest.categories.more`, added
    to `en.properties` and `cs.properties`, using the same
    `{0,choice,...}` plural shape as the other count-bearing digest
    keys. A digest under the cap appends no such line.
  - >-
    Per-cluster LLM prose and per-category roll-ups are generated ONLY
    for sections that survive the cap. D62 already commits that
    capped-out clusters waste no LLM calls; this extends the same
    property to whole sections. A test asserts the generator call count
    against a 12-section fixture at cap 8.
  - >-
    `infochat.digest.max-categories` is documented in
    `docs/design/07-deployment.md` §Configuration surface alongside the
    other `infochat.digest.*` keys, so `scripts/lint-config-keys.py`
    stays green.
  - >-
    `docs/spec/commands.md` §Periodic group digests states the section
    cap, the Other carve-out, and that dropped sections' clusters are
    not redistributed. The spec currently bounds clusters per section
    and says nothing about the number of sections.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestCategorizerTest.java
      — 12 sections at cap 8 keeps the 8 largest in order; Other
      survives the cap and displaces a real category; a digest at
      exactly the cap drops nothing; dropped sections' clusters are not
      folded into Other or any surviving section.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — the overflow line renders once, on the last section, with the
      correct count; no overflow line under the cap; prose and roll-up
      call counts cover only surviving sections.
  preserves:
    - >-
      Every existing `DigestCategorizerTest` assertion — assignment, the
      qualifying threshold, the fold-into-Other second pass and section
      order all hold unchanged for inputs at or below the cap.
    - >-
      `DigestRendererTest` in full; `renderShortBody` and `render` are
      not modified by this ticket.
    - >-
      The `/summary` render assertions in `SummaryCommandHandlerTest`
      and the `--full` / `--flat` / `--short` form tests. The section cap
      must not reach the `/summary` entry point; a test asserts a
      `/summary` with 12 categories still renders all 12.
    - >-
      `DigestWorkerTest` and `DigestRoundtripIT` per-category delivery
      (D63) message-count assertions, re-pinned where a fixture exceeds
      the cap.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/design/03-commands.md §Periodic group digests
decision_refs:
  - D62
  - D63
  - D19
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-721: nothing bounds how many sections a digest renders

## Context

`DigestCategorizer` promotes any tag carried by at least
`categoryMinClusters` clusters (default 3) to a category
(`DigestCategorizer.java:98-117`), and D63 delivers one outbound message
per category. Nothing caps the number of sections.

The tag vocabulary is seeded from the union of `bootstrap-sources.json`
tags and grows with `/add-source --tags`, so **section count tracks source
count**. Adding sources adds tags, which adds categories, which adds
messages. That is the axis this ticket bounds.

Today's per-section item cap (`category-item-cap`, default 12) bounds
each section but not their number, so the digest is bounded at
`(qualifying tags + 1) × 12` clusters — a product with an unbounded
factor.

## Why the cap moved from clusters to sections

An earlier draft of this ticket capped the TOTAL clusters rendered
(default 15) and handed them out round-robin across sections. That was
correct for the shape it was written against, where every rendered
cluster costs a prose paragraph.

Under the hybrid digest (M1-722) a category renders a story count, one
roll-up and a handful of bare headlines **regardless of how many clusters
it holds** — a 3-story category and a 300-story category cost the same
five lines. Counting clusters across sections stops measuring anything
that varies, so a total-cluster budget would bound only the lead
(M1-725), which has its own explicit size. The axis that still grows is
the number of sections, so that is what the cap addresses.

## Two rules that are easy to get wrong

**Other survives the cap.** Sections are ordered by assigned-cluster
count and Other is always last, so a naive tail-drop evicts Other first.
Other holds precisely the clusters with no qualifying tag — the content
with no other route to a reader, including (after M1-726) posts the
tagger found no topic for and (after M1-727) personal-classified
clusters. Dropping it silently discards the least-reachable content while
keeping the most-reachable. Other takes the last slot; a real category
yields instead.

**Dropped clusters are not redistributed.** Folding a dropped section's
clusters into Other would inflate Other exactly when the cap binds. They
are not rendered at all, and the overflow line tells the reader how many
categories are missing.

## Notes

The overflow line points at `/summary` rather than naming the dropped
tags. Listing eight tag names to say what was omitted spends the lines
the cap just saved.
