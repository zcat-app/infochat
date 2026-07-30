---
id: M1-725
title: "The digest has no lead: a reader who opens only the first message gets the newest item of the largest tag"
status: pending
created: 2026-07-30
last_updated: 2026-07-30
blocked_by:
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
    here, not modified. A diff that adjusts a prominence level to make
    the lead section look better has left scope — that would tune the
    ranking against one section's appearance.
  - >-
    `/summary`. It gains no lead section: it is a pull for a named tag,
    where a "top stories" header above the reader's own query is noise.
  - >-
    The degraded (D17) digest, the zero-posts reply, and `brief` mode
    (M1-722). The first two have no cluster structure to lead with; the
    third is already a four-line summary and a lead above it would be
    longer than the thing it leads.
  - >-
    `DigestCategorizer`'s assignment arithmetic. The categorizer is in
    scope only to exclude lead clusters from their home sections
    (see acceptance); the qualifying threshold, the fold-into-Other
    pass and section order are D62 and unchanged.
  - >-
    Per-cluster prose generation. Lead clusters get the same prose from
    the same generator; the lead is a placement change, not a rendering
    mode.
  - any other module
acceptance:
  - >-
    A non-degraded digest with at least `infochat.digest.lead-minimum`
    (default 6) clusters renders a leading section under a localized
    UPPERCASE header, holding the top
    `infochat.digest.lead-size` (default 3) clusters by
    `ClusterProminence` order across the WHOLE digest, before any
    category section.
  - >-
    A digest below the lead minimum renders no lead section at all. A
    3-cluster digest with a "TOP STORIES" header over 3 of 3 stories is
    a header over the whole digest; a test pins that the boundary case
    at `lead-minimum - 1` emits no lead and at `lead-minimum` emits one.
  - >-
    A cluster promoted to the lead is REMOVED from its category section
    — no cluster renders twice in one digest. A test asserts the union
    of lead clusters and section clusters has no duplicate `topicId`,
    and that the total rendered cluster count still equals the M1-721
    budget rather than exceeding it by the lead size.
  - >-
    A category left below the D62 qualifying threshold by that removal
    folds into Other, reusing the categorizer's existing second pass
    rather than a new code path. A test pins a 3-cluster category
    losing one cluster to the lead and folding into Other at the
    default threshold of 3.
  - >-
    The lead is delivered as its own message under D63, first in the
    sequence, and the closing affordance stays on the LAST message. A
    test asserts message count is sections + lead + Other-when-present,
    and that the affordance appears exactly once and not on the lead.
  - >-
    Lead clusters render the same per-cluster prose and links as they
    would in a section — the lead is a placement, not a second render
    mode, so the LLM call count for the digest is unchanged. A test
    pins the call count against the no-lead baseline.
  - >-
    `docs/spec/commands.md` §Periodic group digests documents the lead
    section, its two config keys, its suppression below the minimum,
    and the no-duplicate-cluster property.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — lead holds the top 3 by prominence across all sections; no
      cluster appears in both the lead and a section; the boundary at
      lead-minimum in both directions; a category folding into Other
      after losing a cluster to the lead; total rendered clusters equal
      the budget; message ordering puts the lead first and the
      affordance last; LLM call count unchanged versus no-lead.
  preserves:
    - >-
      Every M1-724 `ClusterProminenceTest` assertion — the ordering
      function is not modified.
    - >-
      Every M1-721 budget and round-robin allocation assertion, and the
      M1-724 diversity-reserve assertions. The lead consumes budget
      slots; it does not extend the budget.
    - >-
      `DigestCategorizerTest` — assignment, threshold, fold-to-Other
      and section order for inputs with no lead extraction.
    - >-
      `DigestWorkerTest` / `DigestRoundtripIT` D63 delivery assertions,
      re-pinned for the new message count.
    - >-
      `/summary` render assertions in `SummaryCommandHandlerTest` — no
      lead section reaches that path.
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
descending, and within a section clusters render in the order the
collector returned them — publication order. So the first thing a reader
sees in the first message of a morning digest is *the most recent item
belonging to whichever tag happens to have the most stories*.

That is not a lead. It is an accident of two sort keys, neither of which
is about significance.

Every ranked digest that is read rather than skimmed opens with its
strongest item: Techmeme's top block, Discourse's highest-scoring topic,
the "big story" slot in curated newsletters. The reason is the same in
each case — a push artifact must survive being read only partly. Under
D63 our digest is several messages, and the realistic reader opens the
first one.

## Why this is filed after M1-724 and not with it

A lead section is only as good as the ordering behind it. Building the
header first would mean leading with the three newest clusters, which is
worse than no lead at all: it makes an implicit significance claim the
selection cannot support. M1-724 supplies `ClusterProminence`; this
ticket places its top three.

## The suppression rule

Below a threshold the lead is noise: a "TOP STORIES" header over three
of a total four clusters tells the reader nothing, and it costs a whole
extra message under D63 to say it. The default minimum of 6 means the
lead only appears when at least half the digest sits below it.

## Notes

The lead draws from the M1-721 budget rather than adding to it. A lead
section that lengthened the digest would undo the ticket that shortened
it; the point of leading is that a reader can stop after the first
message, not that they receive more.
