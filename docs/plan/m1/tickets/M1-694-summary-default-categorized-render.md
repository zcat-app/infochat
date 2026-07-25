---
id: M1-694
title: "/summary renders the categorized form by default; --full keeps the flat form"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
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

# M1-694: /summary renders the categorized form by default

## Context

Split out of **M1-687**, which failed the `complexity: high` plan gate twice
(2026-07-25). This child carries the part that actually fixes the reported
bug: a `/summary` in a DM returns an unreadable wall of text, because
`ClusterBlockRenderer` emits seven lines per cluster with no per-section cap,
while the group digest over the same corpus reads cleanly.

**This child is deliberately the smallest thing that fixes the wall.** It
changes the render only. Delivery stays a single joined body through
`StageProgressNotifier.complete` (per-section delivery is M1-695) and
`/retry` is not touched at all (anchored-form replay is M1-696). That
boundary is what keeps `RetryCommandHandlerGroupScopeIT` green here — its
three tests run a bare `/summary` then `/retry` and assert the replayed body
contains the post headline, which stays true while `/retry` still renders
flat.

Read M1-687's body before filling this in — its §Context, the over-cap
analysis, and the two `## OUTLINE FAILED` blocks carry the ground truth this
ticket inherits.

## Census

**Required — this is a class-scoped ticket.** The class is "every test that
drives a default `/summary` and asserts on `ClusterBlockRenderer` output".

M1-687's census predicate was **wrong and must not be reused**: it grepped
field *labels* (`topic_id=`, `covered by:`, `classification:`,
`finalizedBodies`) and therefore missed the two `ClusterBlockRenderer` fields
that are bare content — the headline (`ClusterBlockRenderer.java:87`) and the
uid (`:94`). Three separate passes each found a different subset because of
it. Enumerate by **invocation**, not by output token:

```
grep -rln '"/summary\|"/retry\| /summary\| /retry' \
  --include=*.java infochat-provider/src/test/java
```

That returned 35 files on 2026-07-25. Most are unaffected (the
`EligiblePostQuery*` tests exercise the query not the render; the `Digest*`
tests exercise the digest path; the `bundle/*` tests are copy parity). Every
returned path still needs a row — `out-of-scope: <reason>` is a valid
disposition, "not listed" is not.

Known-affected from M1-687's two plan passes (re-verify, do not trust):
`SummaryCommandHandlerTest`, `SummaryIT`, `SummaryGroupScopeIT`,
`SummaryAdapterScopeIT` (D46 cross-adapter non-leakage),
`GoldenPathJourneyIT` (MVP §6 exit criterion), `TranslationPipelineIT`
(exact `mockLlm` call counts), `DevTerminalHarnessRoundtripIT` (three uids).

## Acceptance

TO BE WRITTEN. The shape M1-687 had settled on, carried forward:

- default `/summary` renders category headers, one prose paragraph per
  cluster, the per-category item cap and the localized "+N more" line
- `/summary --full` renders today's flat `ClusterBlockRenderer` output
  verbatim
- the over-cap branch (`posts > infochat.summary.summarizer-post-cap`) also
  renders categorized, via a **no-LLM** `DigestRenderer` entry point, while
  keeping its existing semantics: no LLM call, no anchor written, too-large
  notice present. This is the branch production actually hits
  (`%remote-llm.infochat.summary.cluster-cap=500` against a
  `summarizer-post-cap` of 50, `application.properties:309,324`)
- the degraded-prose sanitize/translate split is preserved
- affected pre-existing tests are **retargeted to `--full`**, assertions
  verbatim — never weakened, deleted, `@Disabled` or `assumeTrue`'d

## Out-of-scope

TO BE WRITTEN. At minimum: per-section delivery (M1-695), `/retry` render-form
persistence (M1-696), the scheduled digest's own render and delivery path,
`infochat.summary.cluster-cap` re-tuning, and the SimpleX chunker.

## Notes

Blockers M1-687's second plan pass verified — they land here, so size for
them:

- **Erasure collision.** A cluster-taking sibling cannot be
  `renderSections(List<Cluster>, String)`; same erasure as
  `renderSections(List<Post>, String)`, will not compile. Pick a distinct
  name.
- **`DigestRenderer`'s six collaborator fields and `categoryItemCap` are
  package-private** (`DigestRenderer.java:33-65`), so tests in package
  `provider.command` cannot wire a real renderer. A construction seam is
  needed; `ClusterTraversal.java:62-69` is the in-repo precedent, hand-wired
  cross-package at `SummaryCommandHandlerTest:115`.
- **The wired renderer must reuse the test's own collaborators.**
  `SummaryCommandHandlerTest` drives the default `/summary` in several tests
  that assert on the recording prose generator and the sanitizer
  (`sanitizerStripsPrivilegedCommandFromLlmAuthoredProse` among them), so a
  hand-written fake renderer would turn those into tests of the fake.
- **`renderSections` passes `langCode` to `SummaryProseGenerator.generate`
  where `SummaryCommandHandler` hardcodes `"en"`.** That changes cs-scope
  routing and is what `TranslationPipelineIT`'s exact call counts pin.
- **Sizing is already settled**: the over-cap guard bounds the default path's
  prose-call count at `summarizer-post-cap`, identical to today, because
  `shownClusters` is a subset of the clustered posts.
- This ticket **amends spec**: `docs/spec/commands.md` §Periodic group digests
  and decision **D62** both carry a `/summary`-keeps-its-flat-format carve-out
  that this change contradicts.
