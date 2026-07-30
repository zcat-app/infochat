---
id: M1-725
title: "The digest has no lead: a reader who opens only the first message gets the newest item of the largest tag"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by:
  - M1-722
  - M1-724
files_budget: 9
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestCategorizer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
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
    `ClusterProminence` (M1-724). The ordering function is consumed
    here, not modified. A diff that adjusts a prominence term or one of
    its weights to make the lead look better has left scope — weight
    retuning is a config edit against the live corpus (M1-724 §Tuning),
    never a side effect of a rendering ticket.
  - >-
    `/summary`. It gains no lead section: it is a pull for a named tag,
    where a "top stories" header above the reader's own query is noise.
  - >-
    The category body — count, roll-up, headlines, footer. That is
    M1-722. This ticket adds a section ABOVE the categories.
  - >-
    The section cap (M1-721). Independent: that bounds how many
    categories render, this adds one non-category section.
  - >-
    The degraded (D17) digest, the zero-posts reply, and `brief` mode.
    The first two have no cluster structure to lead with; `brief` is a
    few lines per category and a prose lead above it would dominate the
    thing it introduces.
  - >-
    Proportional lead sizing (`min(floor(clusters/2), lead-size)`).
    Considered and dropped: at `lead-size` 3 with `lead-minimum` 6 the
    halving never binds, because `floor(6/2)` is already 3. It was only
    load-bearing for a much larger lead.
  - >-
    `DigestCategorizer`'s assignment arithmetic. In scope only to
    exclude lead clusters from their home sections; the qualifying
    threshold, fold-into-Other pass and section order are D62 and
    unchanged.
  - any other module
acceptance:
  - >-
    A non-degraded `normal` or `full` digest with at least
    `infochat.digest.lead-minimum` (default 6) clusters renders a
    leading section under a localized UPPERCASE header, holding the top
    `infochat.digest.lead-size` (default 3) clusters by
    `ClusterProminence` order across the WHOLE digest, before any
    category section. Lead clusters render full per-cluster prose and
    links — the same render the category sections no longer do.
  - >-
    A digest below the lead minimum renders no lead at all. A header
    over 3 of 4 total stories is a header over the whole digest, and it
    costs an extra message under D63 to say nothing; a test pins the
    boundary in both directions.
  - >-
    A cluster promoted to the lead is REMOVED from its category section
    — no cluster renders twice in one digest. A test asserts the union
    of lead and section clusters contains no duplicate `topicId`.
  - >-
    A section's story count (M1-722) reflects the removal: a 13-cluster
    section that loses one to the lead reports 12. The count must
    describe what the section actually holds, or it double-counts
    against the lead above it.
  - >-
    A category left below the D62 qualifying threshold by that removal
    folds into Other, reusing the categorizer's existing second pass
    rather than a new code path. A test pins a 3-cluster category losing
    one to the lead and folding into Other at the default threshold.
  - >-
    The lead is delivered as its own message, first, and the closing
    affordance stays on the LAST message of the digest. Under M1-722's
    batched delivery a `normal` digest is therefore two messages: lead,
    then all categories. A test asserts the affordance appears exactly
    once and not on the lead.
  - >-
    Lead prose is generated only for the clusters actually promoted. A
    test pins the LLM call count at `lead-size` for the lead, on top of
    M1-722's one-per-section roll-ups.
  - >-
    `docs/spec/commands.md` §Periodic group digests documents the lead,
    its two config keys, its suppression below the minimum, the
    no-duplicate-cluster property and the count adjustment. Both keys are
    documented in `docs/design/07-deployment.md` §Configuration surface.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — the lead holds the top 3 by prominence across all sections; no
      cluster appears in both lead and section; the section count drops
      by the number of its clusters promoted; the lead-minimum boundary
      in both directions; a category folding into Other after losing a
      cluster; the lead renders full prose while categories render
      headlines; message ordering puts the lead first with the
      affordance last; brief mode renders no lead; LLM call count equals
      lead-size plus one per section.
  preserves:
    - >-
      Every M1-724 `ClusterProminenceTest` assertion — the ordering
      function is not modified.
    - >-
      Every M1-722 category-body assertion: header, count, roll-up,
      headline count and footer, for digests with and without a lead.
    - >-
      Every M1-721 section-cap and overflow-line assertion. The lead is
      not a category and does not consume a section slot.
    - >-
      `DigestCategorizerTest` — assignment, threshold, fold-to-Other and
      section order for inputs with no lead extraction.
    - >-
      `/summary` render assertions in `SummaryCommandHandlerTest` — no
      lead reaches that path.
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/design/03-commands.md §Periodic group digests
decision_refs:
  - D62
  - D63
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-725: the digest has no lead

## Context

`DigestCategorizer.java:109` orders sections by assigned-cluster count
descending, and within a section clusters render in collector order —
publication order. So the first thing a reader sees in the first message
of a morning digest is *the most recent item belonging to whichever tag
happens to have the most stories*.

That is not a lead. It is an accident of two sort keys, neither of which
is about significance.

Every ranked digest that is read rather than skimmed opens with its
strongest item: Techmeme's top block, Discourse's highest-scoring topic,
the "big story" slot in curated newsletters. A push artifact must survive
being read only partly, and the realistic reader opens the first message.

## Why three, given the categories lost their prose

Under M1-722 a category renders a count, a roll-up and five bare
headlines — no prose. The lead is therefore the only place in the digest
where anything is described at length, which is an argument for making it
large.

It is the wrong argument. Breadth is already covered: five headlines per
category across eight categories names forty stories, for the cost of
forty lines and no LLM calls. What the digest lacks without a lead is
*depth on the few things that matter*, and three paragraphs a reader
finishes beat ten they scroll past. Three is a config key; the default is
the claim.

## Why this is filed after M1-724

A lead is only as good as the ordering behind it. Leading with the three
newest clusters would make an implicit significance claim the selection
cannot support — worse than no lead. M1-724 supplies `ClusterProminence`;
this ticket places its top three.

## The count interaction

M1-722's section header reports the section's story count. Promoting a
cluster to the lead removes it from its section, so the count must drop
with it. Left alone, a story would be counted once in the lead and once
in the header of the section it no longer appears in — the one place
where the two tickets can silently disagree, hence an explicit criterion.

## Notes

The lead is a separate message from the batched category message, so a
`normal` digest is two messages: three prose stories, then everything
else. A reader who opens only the first gets the day.
