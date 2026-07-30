---
id: M1-721
title: "Digest length is a function of tag count: the item cap is per-category, so every new category adds up to 12 more clusters"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by: []
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - docs/design/07-deployment.md
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/03-commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    `/summary`'s render path. `SummaryCommandHandler.java:332` calls
    `renderSummarySections(prose, lang)` and
    `renderSummarySections(prose, lang, Integer.MAX_VALUE)`; both keep
    the PER-CATEGORY `categoryItemCap` semantics unchanged. `/summary`
    is an interactive pull where the reader chose to ask and `--full`
    already exists as the uncapped escape; the digest is a push into a
    group chat twice a day. Only the digest entry point
    (`renderSections`) takes the budget. A diff that changes what
    `SummaryCommandHandler` receives has left scope.
  - >-
    `infochat.summary.cluster-cap` (`DigestPostCollector.java:34`,
    `EligiblePostQuery.java:79`) and its oldest-drops eviction. That is
    a POST-level capacity guard sized to the LLM's throughput, applied
    by SQL LIMIT before `ClusterTraversal` runs; this ticket bounds
    CLUSTERS after traversal. The two caps are independent and both
    remain in force.
  - >-
    Which clusters survive the budget. This ticket keeps the existing
    order (`DigestCategorizer` section order; publication order within a
    section) and only changes HOW MANY survive. Replacing the survival
    order with a prominence ranking is M1-724 and must not be
    anticipated here — shipping both at once would make a digest-size
    regression indistinguishable from a ranking regression.
  - >-
    `DigestCategorizer.java`. Category assignment, the
    `categoryMinClusters` qualifying threshold, section order and the
    Other bucket are unchanged (D62 tag arithmetic). The budget is
    applied by the renderer over the sections the categorizer already
    returned.
  - >-
    The degraded (D17) digest path — `DegradedDigestRenderer` has no
    category structure and no item cap, so there is nothing to bound.
  - >-
    `infochat.digest.category-summary-enabled` and
    `CategoryRollupGenerator`. The roll-up ADDS a prefix; it is not a
    length control and is not the compact mode (that is M1-722).
  - any other module
acceptance:
  - >-
    A new config key `infochat.digest.cluster-budget` (default 15)
    bounds the TOTAL clusters a single non-degraded digest renders,
    across all sections including Other. With 8 sections carrying 12
    clusters each, the rendered digest contains 15 clusters, not 96.
  - >-
    Allocation across sections is deterministic and gives every section
    at least one slot before any section gets a second: a round-robin
    over the sections in `DigestCategorizer` order, one cluster per
    section per pass, stopping when the budget is exhausted. Given the
    same sections, the surviving set and its order are byte-identical
    across runs (D19). A test pins the exact allocation for a 3-section
    / budget-7 case (3/2/2) and for a budget smaller than the section
    count (sections beyond the budget render no clusters and are not
    emitted as empty messages).
  - >-
    A section that loses clusters to the budget still appends its
    localized `reply.summary.category.more` overflow line with the
    correct `+N`, where N counts that section's own dropped clusters.
    Sections that lost nothing append no overflow line.
  - >-
    A new bundle key `reply.digest.budget.trimmed` renders ONCE per
    digest, on the last section, stating the digest-wide total trimmed
    (e.g. "Showing 15 of 96 stories. Use /summary <tag> --full for a
    full category."). It is distinct from the per-section overflow
    line — the per-section line explains one header, this one explains
    the digest. Added to `en.properties` and `cs.properties`; the cs
    value uses the same `{0,choice,...}` plural shape as the other
    count-bearing digest keys.
  - >-
    Per-cluster LLM prose is generated ONLY for clusters that survive
    the budget. A test asserts the prose generator is invoked exactly
    `min(totalClusters, budget)` times — D62 already commits that
    capped-out clusters waste no LLM calls, and the budget makes that
    saving larger, not smaller. This is the assertion that would catch
    a fix that trims at render time after paying for prose.
  - >-
    `infochat.digest.cluster-budget` is documented in
    `docs/design/07-deployment.md` §Configuration surface alongside the
    other `infochat.digest.*` keys, so `scripts/lint-config-keys.py`
    stays green.
  - >-
    `docs/spec/commands.md` §Periodic group digests and the D62 row in
    `docs/spec/decisions.md` state the digest-wide budget as the
    binding bound and describe the per-section item cap as subordinate
    to it. The spec text currently promises "at most a per-section item
    cap of clusters (operator-configurable, default 12)" with no total;
    that sentence is amended, not supplemented.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — budget smaller than total clusters trims to exactly the budget;
      round-robin allocation is 3/2/2 for 3 sections at budget 7;
      budget below section count drops whole sections rather than
      emitting empty ones; per-section overflow line counts only that
      section's drops; the digest-wide trimmed line renders once, on the
      last section; a digest under budget renders no trimmed line and no
      overflow lines; prose generator call count equals the surviving
      cluster count.
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — existing per-category-cap cases are re-pinned with a budget set
      high enough to be non-binding, so they keep testing the item cap
      rather than silently testing the budget.
  preserves:
    - >-
      Every `DigestRendererTest` assertion. `renderShortBody` and
      `render` are untouched by this ticket.
    - >-
      `DigestCategorizerTest` in full — category assignment, the
      fold-into-Other second pass and section order are unchanged.
    - >-
      The `/summary` render assertions in `SummaryCommandHandlerTest`
      and the `--full` / `--flat` / `--short` form tests. The budget
      must not reach the `/summary` entry point; a test asserts a
      `/summary` render with 40 clusters across 4 categories still
      renders 12 per category with the budget set to 5.
    - >-
      `DigestWorkerTest` and `DigestRoundtripIT`, including the
      per-category delivery (D63) message-count assertions — a digest
      whose sections all survive the budget produces the same message
      count as today.
    - >-
      `TranslationPipelineIT` LLM call-count assertions.
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

# M1-721: Digest length is a function of tag count

## Context

`DigestRenderer` applies its cap once per section:

```java
// DigestRenderer.java:105, :127
int shownCount = Math.min(section.clusters().size(), categoryItemCap);
```

`categoryItemCap` defaults to 12 (`DigestRenderer.java:68`). There is no
bound on the number of sections: `DigestCategorizer` promotes any tag
carried by at least `categoryMinClusters` clusters (default 3) to a
category, and D63 delivers one outbound message per category.

So the rendered digest is bounded by `(number of qualifying tags + 1) ×
12` clusters, and the number of qualifying tags grows with the source
count. Adding sources adds tags, which adds categories, which adds
*messages*. At 8 categories a single morning digest is 9 messages and up
to 96 prose paragraphs, pushed into a group chat unbidden.

The only ceiling today is `infochat.summary.cluster-cap`
(200/100/50/500 by profile, `DigestPostCollector.java:35`). That is not
a length control: it is a capacity guard sized to what the LLM can
process within the slot window. On `laptop` it permits a 200-cluster
digest. A bound that tracks the machine's throughput is not a bound that
tracks a reader's attention.

## What other systems do

The pattern is consistent across digest products: output length is a
constant the operator or reader sets, and corpus growth changes *what
gets in*, not *how much comes out*. Nuzzel shipped both a corroboration
threshold and a max-items-per-day cap, defaults deliberately low.
Readwise's Daily Review fixes the item count and lets library growth
lower each item's probability of appearing. Discourse's activity summary
sends a scored top-N since last visit.

Ours has the threshold-ish part (the item cap) but applies it to the
wrong unit — per section rather than per digest — which is exactly the
unit that multiplies.

## Why round-robin rather than proportional

Proportional allocation (each section gets `budget × its share of
clusters`) hands the whole budget to the largest category and starves the
tail: a group tracking 30 `ai` sources and 2 `zcash` sources would see
`zcash` vanish from every digest. Round-robin gives every section its
first slot before any section gets a second, so a small category always
appears. It is also trivially deterministic — no floats, no rounding
rule to tie-break — which keeps the D19 byte-identical-replay property
that `/retry --digest` depends on.

The per-section item cap stays in place as a subordinate bound. With the
budget binding first it is normally inert, but it still applies when the
budget is generous and one category is pathologically large.

## Notes

Follow-ups deliberately not folded in:

- Which clusters survive is M1-724 (prominence ordering). This ticket
  changes only how many.
- A per-group compact render mode is M1-722.
- Making the budget per-group rather than per-deployment is not filed —
  `/digest brief` (M1-722) covers the reader-facing need with one
  setting instead of a numeric knob, and D62's slot hours set the
  precedent that v1 keeps digest configuration global.
