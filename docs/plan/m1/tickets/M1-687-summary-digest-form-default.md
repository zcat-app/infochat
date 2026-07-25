---
id: M1-687
title: "/summary renders the categorized digest form by default"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
files_budget: 20
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryArgs.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/StageProgressNotifier.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryArgsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerRenderFormTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/journey/GoldenPathJourneyIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineIT.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/03-commands.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The periodic group digest's own render and delivery path
    (DigestWorker, DigestDelivery, DigestScheduler, DigestRetryService).
    This ticket makes /summary REUSE DigestRenderer; it changes nothing
    about how the scheduled digest renders or delivers. DigestRenderer
    itself may only be extended in ways that leave renderSections'
    existing output byte-identical for the digest's call site.
  - >-
    The digest window/collection bugs. Those are M1-688 (first-run
    lookback + zero-post boundary) and M1-689 (ready_at vs published_at).
    Do not touch DigestPostCollector or the collection window here.
  - >-
    infochat.summary.cluster-cap and the profile overrides. The blow-up
    this ticket fixes is a RENDER-side cap problem; re-tuning retrieval
    caps is a separate sizing decision.
  - >-
    The SimpleX outbound chunker (SimpleXOutboundChunker,
    SimpleXMessageCodec). Per-category delivery keeps individual messages
    well under the 4000-byte cap; the chunker stays as the backstop it is.
acceptance:
  - >-
    /summary with no flags renders the categorized digest form: category
    headers, one prose paragraph per cluster, the per-category item cap,
    and the localized "+N more" overflow line — i.e. the default render
    path routes through DigestRenderer's section renderers rather than
    ClusterBlockRenderer. Under the summarizer post cap that is
    renderSections; over it, the no-LLM sibling required by acceptance 7.
  - >-
    /summary --full renders exactly today's flat per-cluster form
    (ClusterBlockRenderer: topic_id, title, covered by, score, summary,
    classification, tags). SummaryArgsTest covers --full accepted, --full
    combined with a tag and -w, and an unknown flag still rejected.
  - >-
    In the default form, /summary is delivered as one outbound message per
    category section (D63's shape, applied to the /summary scope), not one
    joined body. The last section carries the closing affordance exactly
    once.
  - >-
    The overflow and closing-affordance strings used by /summary are
    DM-appropriate — the group-worded "@mention me to see them" wording is
    not emitted in a DM scope. New bundle keys exist in BOTH en.properties
    and cs.properties (D43 bilateral keyset) and BundleLoaderTest is green.
  - >-
    /retry replays in the SAME render form the anchored /summary produced
    (D36 re-runs the prose stage of the last summary-producing command).
    The form is carried in the EXISTING summary_anchor.command_name column
    — "summary" for the default form, "summary --full" for the flat one —
    which is TEXT NOT NULL with no CHECK constraint (V19:10; the CHECK is
    on command_kind, V19:8-9). No migration, no SummaryAnchorRepository
    signature change. A new SummaryCommandHandlerRenderFormTest pins that
    a --full anchor replays flat and a default anchor replays categorized.
  - >-
    The five pre-existing tests that assert ClusterBlockRenderer fields on
    the DEFAULT /summary are retargeted, never weakened or deleted, per
    the disposition table in the body's "Pre-existing test dispositions"
    section. Four move their existing assertions verbatim onto a
    /summary --full invocation — which still renders those exact fields,
    so coverage is preserved byte-for-byte, including
    SummaryAdapterScopeIT's D46 cross-adapter non-leakage property.
    GoldenPathJourneyIT stays on the DEFAULT command (it pins the MVP
    journey a real user walks) and re-points hop 6 at the stubbed cluster
    prose, which the categorized form does render. No assertion is
    removed, no test is disabled, and no @Disabled/assumeTrue is added.
  - >-
    SummaryCommandHandlerTest.buildHandlerWithStubs (lines 111-127)
    hand-wires every @Inject field of SummaryCommandHandler, so it is
    extended with whatever renderer collaborator the new default path
    adds. Without this the terminal path NPEs in that test.
  - >-
    The degraded-prose path stays sanitizer/translator-correct in the new
    default form: degraded cluster prose bypasses sanitize+translate and
    non-degraded prose does not, matching DigestRenderer's existing
    behavior. No LLM-authored text reaches the adapter unsanitized.
  - >-
    The existing over-cap guard still fires — a window whose post count
    exceeds infochat.summary.summarizer-post-cap makes no LLM call, writes
    no anchor, and returns the degraded reply plus the too-large notice.
    In the DEFAULT form that degraded reply is ALSO categorized: a new
    no-LLM DigestRenderer entry point applies the same category headers,
    per-category item cap and "+N more" overflow to degraded per-cluster
    prose, and it is delivered per-section like the under-cap form. This
    is the branch production actually hits
    (%remote-llm.infochat.summary.cluster-cap=500 against a
    summarizer-post-cap of 50, application.properties:309,324), so
    leaving it on ClusterBlockRenderer would leave the reported wall
    unfixed. --full keeps the flat form on this branch too.
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerRenderFormTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryArgsTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/journey/GoldenPathJourneyIT.java
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/translation/TranslationPipelineIT.java
      (conditional — in scope so it can be audited against the new default
      form; edit only if the call counts genuinely change)
  preserves:
    - >-
      Every test green on main EXCEPT the five named in `modifies` above,
      which assert ClusterBlockRenderer fields on the default /summary and
      are retargeted per the body's disposition table. Their assertions are
      preserved verbatim; only the invocation (or, for GoldenPathJourneyIT,
      the asserted string) changes. Nothing is deleted or disabled.
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D19
  - D36
  - D43
  - D62
  - D63
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
escalation_reason:
---

# M1-687: /summary renders the categorized digest form by default

## Context

Filed from live SimpleX testing on 2026-07-25. `/summary` in a DM returned
an unreadable wall of text spread across dozens of SimpleX messages, while
the group digest over the same corpus reads cleanly. The two surfaces have
entirely separate renderers and the interactive one has no display cap.

`ClusterBlockRenderer.appendClusterBlock` emits **seven lines per cluster**
(`[topic_id=…]`, title, `covered by:` + per-post uid list, `score:`,
`summary:`, `classification:`, `tags:`) and `SummaryCommandHandler` loops it
over every cluster into a single `StringBuilder`
(`SummaryCommandHandler.java:360`). There is no per-section cap and no
"+N more" footer on this path. `DigestRenderer.renderSections` over the same
posts emits a bare prose paragraph per cluster under category headers,
capped at `infochat.digest.category-item-cap` (default 12) with a localized
overflow line.

Production runs `quarkus.profile=remote-llm`, where
`infochat.summary.cluster-cap=500` (`application.properties:309`) while
`infochat.summary.summarizer-post-cap=50` has no profile override
(`application.properties:324`). So a routine `/summary -w 24h` fetches up to
500 posts, trips the over-cap guard, and renders the *degraded* form — which
still emits all seven `ClusterBlockRenderer` lines per post, only with
`title — url (uid)` in the `summary:` slot. That is the wall the user hit.

The reuse is cheap because `DigestRenderer.renderSections(List<Post>,
String langCode)` takes plain `EligiblePostQuery.Post` plus a language code
and does clustering, categorization, prose generation and capping
internally — it carries no group-only coupling (`DigestRenderer.java:88`).

## Acceptance

See the frontmatter. Default `/summary` routes through
`DigestRenderer.renderSections` and delivers one message per category;
`--full` preserves today's flat `ClusterBlockRenderer` output verbatim;
`/retry` replays whichever form the anchor recorded; the DM-worded
affordance/overflow strings land in both bundles; the degraded-prose
sanitize/translate split and the over-cap guard are unchanged.

## Out-of-scope

The scheduled digest's own render and delivery path, the digest collection
window bugs (M1-688, M1-689), retrieval cap re-tuning, and the SimpleX
chunker. See the frontmatter for the full list and the reasons.

This ticket **amends spec**: `docs/spec/commands.md` §Periodic group digests
currently states *"`/summary` deliberately keeps its flat per-cluster format
— it is an interactive, filterable DM surface, unlike the broadcast
digest"*, and decision **D62** carries the same carve-out (*"`/summary`'s
flat interactive format … unchanged"*). Both must be updated to describe the
new default-plus-`--full` split rather than left contradicting the code.

## Pre-existing test dispositions

`DigestRenderer.renderSections` emits **prose only** per cluster — no title,
no source display name, no uid, no `[topic_id=` (`DigestRenderer.java:124-134`).
So every pre-existing test that asserts a `ClusterBlockRenderer` field on the
**default** `/summary` stops passing the moment acceptance 1 lands. Enumerate
the class mechanically:

```
grep -rln "topic_id=\|covered by:\|classification: \|finalizedBodies" \
  --include=*.java infochat-provider/src/test/java | xargs grep -ln "/summary"
```

Each site is disposed as follows. **No assertion is deleted, weakened,
`@Disabled`, or `assumeTrue`'d** — the engineering rules forbid it and the
reviewer enforces it.

| Site | Asserts | Disposition |
|---|---|---|
| `SummaryCommandHandlerTest:266-275` | `[topic_id=` ×3, `covered by:`, `classification: technical\n`, `tags: …\n` | retarget the invocation to `/summary --full`; assertions verbatim. Add the default-form coverage in the new `SummaryCommandHandlerRenderFormTest` instead |
| `SummaryCommandHandlerTest:111-127` | — (`buildHandlerWithStubs` hand-wires every `@Inject` field) | extend with the new renderer collaborator, else the terminal path NPEs |
| `SummaryIT:116-126` | four post uids + both source display names | retarget to `/summary --full`; assertions verbatim |
| `SummaryGroupScopeIT:141-144` | `GROUP FLOW HEADLINE` + uid | retarget to `/summary --full`; assertions verbatim |
| `SummaryAdapterScopeIT:117` | `ALPHA HEADLINE m1-040si` — **D46 cross-adapter non-leakage** | retarget to `/summary --full`; assertion verbatim. `--full` renders the headline identically, so the security property is pinned exactly as before |
| `GoldenPathJourneyIT:239-249` | `postTitle` — **MVP §6 exit criterion**, hop 6 | **stays on the default command** — the golden path must walk what a real user gets. Re-point the assertion at the stubbed cluster prose (`"Cluster prose for the journey summary."`), which the categorized form does render |
| `TranslationPipelineIT:126-153, 178-197` | default `/summary -w 24h` in cs and en scopes: `exactly one placeholder send`, `exactly one finalized summary message`, prose/translation sentinels, and exact `mockLlm.callCount()` (3 and 2) | **stays on the default command** — it is the end-to-end pin for acceptance 6. **Audit, do not assume.** Both fixtures seed 2 posts sharing one tag, and `infochat.digest.category-min-clusters` defaults to 3, so both clusters fall into a single Other section and the one-message assertions should still hold. The call counts are the live risk: `DigestRenderer.renderSections` passes `langCode` to `SummaryProseGenerator.generate`, whereas `SummaryCommandHandler` hardcodes `"en"` today, so the cs-scope routing changes. Verify both counts explicitly; adjust only if the new form genuinely changes them, and never by loosening the assertion to a range |

Explicitly **not** disposed, and why (the census grep returns them; both are unaffected):

- `ClusterBlockRendererTest` — constructs `ClusterBlockRenderer` directly and
  never invokes the handler; `/summary` appears only in a javadoc line.
  `ClusterBlockRenderer` is unchanged by this ticket and still backs `--full`.
- `InMemoryConversationBackend` — a scenario helper, not a test. It watermarks
  `sentMessages()`/`finalizedBodies()` and returns *every* reply since the
  mark, matching if any one matches, so per-section delivery widens what it
  sees without breaking it.

Why `--full` for four of them: `--full` preserves today's flat
`ClusterBlockRenderer` output verbatim (acceptance 2), so moving the
invocation preserves the assertion's meaning byte-for-byte rather than
trading coverage away. `GoldenPathJourneyIT` is the exception because its
value is specifically that it exercises the *default* journey; retargeting it
to `--full` would silently stop covering the path every user actually hits.

## Notes

- Delivery shape: the user explicitly chose per-category messages over one
  joined body, so a single category can be forwarded on its own to someone
  not using the app. `DigestDelivery.deliver` cannot be reused as-is — it
  constructs `ScopeRef.Group` targets and writes per-group delivery records
  (`DigestDelivery.java:113`). `OutboundDelivery.deliver(adapter, msg)`
  (`OutboundDelivery.java:140`) is scope-generic and is the likely seam.
- `/summary` currently self-delivers a single body through
  `StageProgressNotifier.complete(scope, finalText)`
  (`StageProgressNotifier.java:249`), which finalizes the progress
  placeholder. Multi-section delivery has to reconcile with that lifecycle —
  finalizing the placeholder with the first section and sending the rest as
  fresh messages is the obvious shape, but the placeholder/abandonment
  safety net (`terminateAbandoned`, M1-334/M1-611) must still hold.
- **Render-form persistence for `/retry`: settled, no migration.**
  `summary_anchor.command_name` is `TEXT NOT NULL` with no CHECK
  (`V19__summary_anchor.sql:10`; the CHECK is on `command_kind`, lines 8-9),
  and `SummaryAnchorRepository.write` already takes `commandName` as a
  parameter while `AnchorRow` already exposes it. Writing `"summary"` vs
  `"summary --full"` therefore carries the render form with **no DDL and no
  repository signature change**, which also avoids breaking three
  pre-existing files that a signature change would hit at compile time
  (`RetryCommandHandlerTest:345-379`, `InboundRouterStopRetryIT:104,128`,
  `SummaryAnchorRepositoryTest`). `RetryCommandHandler` must start *reading*
  `anchor.commandName()` — it does not today.
  A V63 migration is **actively unsafe** here: the tree holds
  `V64__post_window_index_on_ready_at.sql` (M1-689, merged), and Flyway runs
  with default `outOfOrder=false` (no `quarkus.flyway.*out-of-order`
  property exists in the repo), so a V63 landing after V64 fails validation
  on any database that already applied V64.
- **Sizing: settled.** The over-cap guard bounds the default path's
  prose-call count at `summarizer-post-cap` (50) — identical to today. The
  guard returns before any LLM call, so `renderSections` only ever runs on
  a ≤50-post set; `shownClusters` is a subset of those clusters, so
  per-category capping can only *lower* the call count, never raise it. The
  "~8 categories × 12 = ~96 clusters" concern assumed the guard does not
  fire; it does.
- Adjacent code: `DigestRenderer.java`, `DigestCategorizer.java`,
  `ClusterBlockRenderer.java` (shared today by `/summary` and `/retry`
  only).
