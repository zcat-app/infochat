---
id: M1-696
title: "/retry replays the render form its anchored /summary produced"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by:
  - M1-694
decomposed_from: M1-687
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope: []
acceptance: []
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-696: /retry replays the render form its anchored /summary produced

## Context

Split out of **M1-687** (failed the `complexity: high` plan gate twice,
2026-07-25). Once M1-694 gives `/summary` two render forms, `/retry` replaying
only the flat one is a visible inconsistency: D36 has `/retry` re-run the
prose stage of the last summary-producing command, so it should come back in
the form that command produced.

This is last of the three children by design. While it is unbuilt, `/retry`
keeps rendering flat — which is exactly what
`RetryCommandHandlerGroupScopeIT`'s three tests already assert, so M1-694 and
M1-695 ship without touching them. **This ticket is where that cost is paid.**

## Census

**Required — class-scoped.** The class is "every test that runs `/retry`
against an anchor written by a default `/summary` and asserts on
`ClusterBlockRenderer` output".

```
grep -rln '"/retry\| /retry' --include=*.java infochat-provider/src/test/java
```

Known-affected (verified 2026-07-25, re-verify at start):
`RetryCommandHandlerGroupScopeIT` — all three tests run a bare `/summary`
(`:125`, `:162`, `:203`) then `/retry` and assert the replayed body contains
`GROUP RETRY HEADLINE` / `DM RETRY HEADLINE` / `UNDATED RETRY HEADLINE`
(`:146`, `:178`, `:217`). The third is M1-689's redteam-round-3 NPE
regression guard and must keep guarding that.

Also give rows to `RetryCommandHandlerTest`, `RetryDigestCommandTest`,
`InboundRouterStopRetryIT` and `DigestRetryConcurrencyIT` — the invocation
grep returns them; verify whether the render form reaches them at all.

## Acceptance

TO BE WRITTEN. Carried forward from M1-687:

- `/retry` replays in the same render form the anchored `/summary` produced —
  a `--full` anchor replays flat, a default anchor replays categorized
- the form is carried in the **existing** `summary_anchor.command_name`
  column (`"summary"` vs `"summary --full"`). That column is `TEXT NOT NULL`
  with **no** CHECK constraint (`V19__summary_anchor.sql:10`; the CHECK is on
  `command_kind`, lines 8-9), and `SummaryAnchorRepository.write` already
  takes `commandName` while `AnchorRow` already exposes it — so **no
  migration and no repository signature change**
- `/retry` keeps returning a single `OutboundMessage`; its delivery shape does
  not change

## Out-of-scope

TO BE WRITTEN. At minimum: the `/summary` render itself (M1-694), per-section
delivery (M1-695), `/retry --digest` (`DigestRetryService`), and any Flyway
migration — the whole point of the `command_name` encoding is that none is
needed.

## Notes

- **Do NOT add a V63 migration.** The tree holds
  `V64__post_window_index_on_ready_at.sql` (M1-689, merged) and Flyway runs
  with default `outOfOrder=false` — no `quarkus.flyway.*out-of-order`
  property exists in the repo — so a V63 landing after V64 fails validation on
  any database that already applied V64.
- **Anchor values are not normalized.** Pre-existing rows write
  `command_name = '/summary'` with a leading slash
  (`OutboundDeliveryCleanupIT:297`, `ChatMemoryPrunerTest:215`), so the form
  dispatch must treat anything other than the exact `--full` marker as the
  default form rather than comparing equality against `"summary"`.
- **`RetryCommandHandler` does not read `anchor.commandName()` today** — it
  reads `postUids()` and `clusterMapJson()` only. Adding the read is this
  ticket's core change.
- **`renderSections` re-clusters internally** (`DigestRenderer.java:90`), so
  `/retry` cannot call it without discarding the frozen `cluster_map` that
  D19/D36 byte-identical replay depends on. A cluster-taking entry point is
  required, and it **cannot** be `renderSections(List<Cluster>, String)` —
  same erasure as the existing `(List<Post>, String)` overload, will not
  compile. If M1-694 already added such an entry point for its over-cap path,
  reuse it.
