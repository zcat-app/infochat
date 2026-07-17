---
id: M1-643
title: "Group /summary output under topic headers (shared categorizer)"
status: abandoned
abandoned_reason: wont-do-infeasible
created: 2026-07-17
last_updated: 2026-07-17
blocked_by:
  - M1-641
files_budget: 10
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/ClusterBlockRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The TopicCategorizer implementation itself — M1-641 builds the shared,
    scope-agnostic categorizer (summary package). This ticket REUSES it; it
    does not reimplement or change the assignment algorithm, threshold, or
    Other-bucket logic.
  - >-
    The digest path (DigestRenderer, DigestWorker) and per-category message
    delivery / roll-up summaries — those are M1-641 / M1-642. This ticket only
    changes the /summary + /retry render path (ClusterBlockRenderer).
  - >-
    The per-cluster `classification:` and `tags:` label lines that
    ClusterBlockRenderer already emits — they stay as-is. Whether they become
    redundant under headers is a separate decision, deliberately not made here.
    Any edit to ClusterBlockRendererTest is limited to adding header assertions;
    existing per-block assertions must remain.
acceptance:
  - >-
    /summary groups its clusters under UPPERCASE topic headers using the shared
    TopicCategorizer (same deterministic assignment as the digest: primary =
    highest digest-wide tag count, tie alphabetical; below-threshold / untagged
    → Other; sections ordered by size desc, tie alphabetical, Other last).
    SummaryCommandHandlerTest.groupsClustersUnderTopicHeaders passes.
  - >-
    The existing per-cluster block (title, covered-by + uids, summary prose,
    classification: and tags: labels) renders unchanged WITHIN each group.
    ClusterBlockRendererTest keeps its current per-block assertions and adds a
    header-boundary assertion.
  - >-
    /retry re-renders the last /summary through the same grouped path, so a
    regenerated summary shows the same headers. RetryCommandHandlerTest (or an
    added case) asserts the grouped layout is present on retry.
  - >-
    Grouping adds NO LLM call — it is the same deterministic tag arithmetic as
    the digest; a /summary over a fixed cluster set produces a byte-identical
    section layout across two runs.
  - >-
    Grouping applies in both DM and group scope for /summary.
  - >-
    docs/spec/commands.md §Command catalogue documents the topic-grouped layout and
    references decision D62 (established by M1-641); no new decision is minted.
  - mvn -pl infochat-provider verify is green
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/ClusterBlockRendererTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Command catalogue
decision_refs:
  - D18
  - D62
---

# M1-643: Group /summary output under topic headers

> **ABANDONED 2026-07-17 — product decision, NOT infeasibility.** After comparing
> the live `/summary` output against the digest, we chose to keep `/summary`
> as-is: it is an interactive DM surface whose rich per-cluster envelope (`uid`
> for `/save`, `classification:`, `tags:`) is the feature, and users can already
> slice it with `--tag` / `--since`. Topic headers pay off on the broadcast
> digest (M1-641), not on this pull surface. The shared-categorizer plan is
> reverted — M1-641 keeps a digest-local `DigestCategorizer`. Kept as a tombstone
> so the idea is not silently re-proposed.

## Context

`/summary` and the periodic digest are near-twins: both cluster the scope's
recent posts (`ClusterTraversal`) and write per-cluster prose
(`SummaryProseGenerator`); they differ only in trigger/scope and in their
RENDERER (`/summary` → `ClusterBlockRenderer`, digest → `DigestRenderer`).
M1-641 adds topic-grouped headers to the digest and — deliberately — builds
the categorizer as a shared, scope-agnostic `TopicCategorizer`. This ticket
brings the same structure to `/summary` (and `/retry`, which re-renders the
last summary through the same renderer) so the two surfaces stay consistent.
Contract: `docs/spec/commands.md` §Command catalogue; the on-the-fly `/summary`
determinism boundary is decision D18.

## Acceptance

- `SummaryCommandHandler` categorizes its clusters via the shared
  `TopicCategorizer` (M1-641) and renders each category as an UPPERCASE header
  followed by the existing per-cluster blocks. Assignment, threshold, ordering,
  and the Other bucket are identical to the digest's (they share the component).
- The existing per-cluster block content (title, covered-by + uids, `summary:`,
  `classification:`, `tags:`) is unchanged within each group — only the
  grouping/headers are added.
- `/retry` shows the same grouped layout (same renderer path).
- No new LLM call; grouping is deterministic and reproducible.
- Works in DM and group scope.
- Spec documents the layout and cites D62; `mvn -pl infochat-provider verify`
  is green.

## Out-of-scope

See `out_of_scope`. This is the `/summary` + `/retry` render path only. Reuse
`TopicCategorizer` from M1-641; do not reimplement it. Do not touch the digest
path. Keep the per-cluster `classification:`/`tags:` labels. New bundle keys
need en+cs twins (D43).

## Notes

**Shared seam.** `SummaryCommandHandler` already runs
`ClusterTraversal.cluster` then loops `ClusterBlockRenderer.appendClusterBlock`
per cluster. Insert `TopicCategorizer.categorize(clusters)` before the loop and
emit a header between groups (a small `appendCategoryHeader` on
`ClusterBlockRenderer`, or in the handler). `/retry` shares
`ClusterBlockRenderer`, so it inherits the grouping — call that out in the test.

**Header keys** can reuse M1-641's `en`/`cs` header + Other-label bundle
entries; only add a key if `/summary` needs different wording.

**Closing affordance:** the digest's closing affordance (M1-641) is a
broadcast @mention nudge. `/summary` is interactive DM/group and arguably
needs none; leave it off here (or add a DM-scope variant in a later ticket) —
headers are the parity goal, not the affordance.

**`/summary` clusters are singletons in MVP** (`SummaryCommandHandler` javadoc)
— grouping singletons under headers still works; the categorizer operates on
whatever clusters it is handed.

**Why separate from M1-641:** `/summary` is the most-used command with a
richer renderer and its own test surface; isolating its change keeps each
diff independently reviewable and lets the digest format be validated live
first.
