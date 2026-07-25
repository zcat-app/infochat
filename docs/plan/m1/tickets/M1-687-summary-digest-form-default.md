---
id: M1-687
title: "/summary renders the categorized digest form by default"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryArgs.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RetryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-core/src/main/resources/db/migration/V63__*.sql
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryArgsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerRenderFormTest.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/03-commands.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: true
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
    and the localized "+N more" overflow line — i.e. it routes through
    DigestRenderer.renderSections rather than ClusterBlockRenderer.
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
    A new SummaryCommandHandlerRenderFormTest pins that a --full anchor
    replays flat and a default anchor replays categorized.
  - >-
    The degraded-prose path stays sanitizer/translator-correct in the new
    default form: degraded cluster prose bypasses sanitize+translate and
    non-degraded prose does not, matching DigestRenderer's existing
    behavior. No LLM-authored text reaches the adapter unsanitized.
  - >-
    The existing over-cap guard still fires — a window whose post count
    exceeds infochat.summary.summarizer-post-cap makes no LLM call, writes
    no anchor, and returns the degraded reply plus the too-large notice.
  - mvn verify from the repo root is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerRenderFormTest.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryArgsTest.java
  preserves:
    - all tests currently green on main
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
clarity_check: {}
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
- Render-form persistence for `/retry`: `summary_anchor` (V19) stores
  `command_kind`, `command_name`, `arg_hash`, `post_uids`, `cluster_map` —
  nothing that distinguishes the two render forms. Highest existing
  migration is V62. Whether to add a column or encode the form elsewhere is
  an implementation call; `migration_touch: true` is set conservatively.
- Sizing check the implementer should make explicitly: at
  `cluster-cap=500`, categorizing then capping at 12/category can still put
  ~8 categories × 12 = ~96 clusters into `SummaryProseGenerator.generate`,
  where today's `summarizer-post-cap` hard-stops at 50. Decide and state
  what bounds the total prose-call count on the new default path.
- Adjacent code: `DigestRenderer.java`, `DigestCategorizer.java`,
  `ClusterBlockRenderer.java` (shared today by `/summary` and `/retry`
  only).
- Relevant design note: `docs/design/03-commands.md` §`/summary [tag]
  [-w 24h]` carries the canonical output shape and the cluster-cap table.
