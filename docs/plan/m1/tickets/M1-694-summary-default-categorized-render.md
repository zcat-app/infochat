---
id: M1-694
title: "/summary renders the categorized form by default; --full keeps the flat form"
status: pending
created: 2026-07-25
last_updated: 2026-07-25
blocked_by: []
decomposed_from: M1-687
files_budget: 18
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryArgs.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/SummaryCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryArgsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/dev/DevTerminalHarnessRoundtripIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/journey/GoldenPathJourneyIT.java
  - docs/spec/commands.md
  - docs/spec/decisions.md
  - docs/design/03-commands.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Per-section delivery (M1-695). /summary keeps delivering ONE joined body
    through progressNotifier.complete(). Do not touch ProgressNotifier,
    StageProgressNotifier, OutboundDelivery, AdapterRegistry, or
    RecordingProgressNotifier. Delivery-count assertions
    (adapter.sentMessages()/finalizedBodies() == 1) must stay green
    unmodified.
  - >-
    /retry render-form persistence (M1-696). RetryCommandHandler,
    SummaryAnchorRepository, and the summary_anchor table are untouched;
    /retry keeps replaying the FLAT ClusterBlockRenderer form. The anchor
    written by the default path keeps its existing command_name value
    ("summary"). This boundary is what keeps RetryCommandHandlerGroupScopeIT
    and InboundRouterStopRetryIT green — see the census table.
  - >-
    ClusterBlockRenderer.java itself. It is NOT modified: it is what --full
    renders, and leaving it byte-identical is what makes the retargeted
    assertions preserve their meaning exactly.
  - >-
    The periodic group digest's own render and delivery path (DigestWorker,
    DigestDelivery, DigestScheduler, DigestRetryService, DigestCategorizer,
    CategoryRollupGenerator). DigestRenderer may only be EXTENDED with a new
    entry point plus a construction seam; DigestRenderer.render and
    DigestRenderer.renderSections must stay behaviorally unchanged so every
    digest test passes unmodified.
  - >-
    infochat.summary.cluster-cap, infochat.summary.summarizer-post-cap,
    infochat.digest.category-item-cap and their profile overrides. This is a
    render-side fix; re-tuning retrieval or cap sizing is a separate
    decision.
  - >-
    The SimpleX outbound chunker (SimpleXOutboundChunker,
    SimpleXMessageCodec).
  - >-
    The digest window/collection bugs (M1-688, M1-689). Do not touch
    DigestPostCollector or the collection window.
acceptance:
  - >-
    SummaryArgs.parse accepts a --full flag and carries it on the parsed
    record. SummaryArgsTest adds cases for bare `/summary --full`, --full
    combined with a positional tag, and --full combined with `-w 7d`. The
    pre-existing tagWithLeadingHyphenIsMalformed and windowOutOfRangeIsRejected
    stay green unmodified — a dash-prefixed token other than -w and --full
    still folds to Failure(error.summary.window_out_of_range).
  - >-
    A default `/summary` (no --full) renders the categorized form: uppercase
    category section headers, one prose paragraph per shown cluster, the
    per-category item cap, and a localized DM-appropriate "+N more" overflow
    line. The rendered body contains none of ClusterBlockRenderer's seven
    per-cluster lines — no "[topic_id=", no bare headline line, no "covered
    by:", "score:", "summary:", "classification:", or "tags:" labels.
  - >-
    `/summary --full` renders today's flat ClusterBlockRenderer output
    byte-identically, on both the normal and the over-cap branch.
    ClusterBlockRenderer.java is not modified.
  - >-
    The categorized render runs through a NEW no-LLM DigestRenderer entry
    point that takes an ALREADY-GENERATED List<ClusterProse> (not a post
    list) and returns the rendered sections. SummaryCommandHandler keeps
    ownership of its single summaryProseGenerator.generate(clusters, "en")
    call, so summarizer/translator call counts and the language argument are
    unchanged from today. The method carries a name distinct from
    renderSections — `renderSections(List<Cluster>, String)` and
    `renderSections(List<ClusterProse>, String)` share renderSections(List,
    String)'s erasure and will not compile.
  - >-
    TranslationPipelineIT passes UNMODIFIED, including its exact
    mockLlm.callCount() assertions (3 in the cs scope, 2 in the en scope) and
    its exactly-one-placeholder-send / exactly-one-finalized-message bounds.
    Verify both counts explicitly against the new default form; never adjust
    them by loosening an assertion to a range.
  - >-
    The over-cap branch (result.posts().size() > summarizerPostCap) also
    renders categorized in the default form, through the same no-LLM entry
    point, with today's semantics preserved exactly: no LLM call, no summary
    anchor written, no in-flight slot held, and the existing prefix ordering
    (top-3 restriction, cap-excess notice, then the window-too-large notice).
    This is the branch production actually hits
    (%remote-llm.infochat.summary.cluster-cap=500 against a
    summarizer-post-cap of 50 — application.properties:309,324).
  - >-
    The degraded-prose split is preserved in the new form: degraded
    ClusterProse bypasses sanitize+translate, non-degraded prose runs
    sanitizer-1 then the translation pipeline — matching both
    ClusterBlockRenderer and DigestRenderer.renderSections. No LLM-authored
    text reaches the adapter unsanitized.
  - >-
    SummaryCommandHandlerTest.sanitizerStripsPrivilegedCommandFromLlmAuthoredProse
    passes against the DEFAULT form. This requires the renderer wired into
    SummaryCommandHandlerTest.buildHandlerWithStubs to be a REAL DigestRenderer
    holding the test's own LlmOutputSanitizer and TranslationPipeline — a
    hand-written fake renderer would turn that test into a test of the fake.
    A construction seam on DigestRenderer is therefore required; its six
    collaborator fields and categoryItemCap are package-private
    (DigestRenderer.java:33-65) and the test lives in package
    app.zcat.infochat.provider.command. ClusterTraversal.java:62-69 is the
    in-repo precedent, hand-wired cross-package at
    SummaryCommandHandlerTest:115.
  - >-
    Delivery is unchanged — one joined body through
    progressNotifier.complete(). Every pre-existing
    adapter.sentMessages()/finalizedBodies() count assertion stays at 1,
    unmodified.
  - >-
    DigestRenderer.render and DigestRenderer.renderSections keep their
    existing behavior, and every digest-side test (DigestRoundtripIT,
    DigestDeliveryTest, DigestWorkerTest, DigestRetryConcurrencyIT,
    DigestPostCollectorIT, RetryDigestCommandTest) passes UNMODIFIED.
  - >-
    Every pre-existing test that the census table marks AFFECTED is
    retargeted per that table, never weakened: five move their existing
    assertions verbatim onto a `/summary --full` invocation (which renders
    those exact fields, so coverage is preserved byte-for-byte — including
    SummaryAdapterScopeIT's D46 cross-adapter non-leakage pair), and
    GoldenPathJourneyIT stays on the DEFAULT command (it pins the MVP §6
    journey a real user walks) with its hop-6 assertion re-pointed at the
    stubbed cluster prose the categorized form does render. No assertion is
    deleted, no test is disabled, and no @Disabled or assumeTrue is added.
  - >-
    The new "+N more" overflow key exists in BOTH en.properties and
    cs.properties (D43 bilateral keyset) and is DM-appropriate — the
    group-worded "@mention me to see them" of reply.digest.category.more is
    not emitted into a DM scope. BundleLoaderTest is green.
  - >-
    help.cmd.summary.short and help.cmd.summary.usage document --full in both
    en and cs. SummaryHelpFlagParityTest passes UNMODIFIED (it asserts every
    advertised dash-prefixed flag is parser-accepted, so it now covers
    --full), and HelpCommandHandlerTest passes UNMODIFIED (its
    contains("/summary [tag] [-w <duration>]") signature assertion survives
    appending the new flag to the signature line).
  - >-
    Spec is amended to match the code: docs/spec/commands.md §Periodic group
    digests drops the "/summary deliberately keeps its flat per-cluster
    format" carve-out (commands.md:1802), the D62 row in
    docs/spec/decisions.md drops its "/summary's flat interactive format ...
    unchanged" clause (decisions.md:79), and docs/design/03-commands.md
    §`/summary [tag] [-w 24h]` documents the default-plus---full split.
  - mvn verify from the repo root is green
test_plan:
  # No new test FILES. The added coverage is new test METHODS in two
  # existing files, both listed under `modifies` and pinned by acceptance
  # items 1 and 2: categorized-default-form and categorized-over-cap methods
  # in SummaryCommandHandlerTest (whose buildHandlerWithStubs already carries
  # the seam and stubs they need), and --full parse cases in SummaryArgsTest.
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryCommandHandlerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryGroupScopeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryAdapterScopeIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/dev/DevTerminalHarnessRoundtripIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/journey/GoldenPathJourneyIT.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command/SummaryArgsTest.java
  preserves:
    - >-
      All tests currently green on main EXCEPT the six named in `modifies`
      above, which are retargeted per the census table with their assertions
      preserved verbatim; only the invocation (or, for GoldenPathJourneyIT,
      the asserted string) changes. Nothing is deleted or disabled.
    - >-
      TranslationPipelineIT, RetryCommandHandlerGroupScopeIT,
      SummaryHelpFlagParityTest, HelpCommandHandlerTest,
      ClusterBlockRendererTest and InboundRouterStopRetryIT pass UNMODIFIED —
      each is green only because of a specific scope boundary this ticket
      holds; see the census table.
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/commands.md §Periodic group digests
decision_refs:
  - D19
  - D36
  - D43
  - D46
  - D62
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

Read M1-687's body before implementing — its §Context, the over-cap
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

Re-run live 2026-07-25: **35 files**, all dispositioned below.

**The discriminator.** A site goes red iff it drives a **default**
`/summary` (no `--full`) **and** asserts on something only
`ClusterBlockRenderer` emits. Two facts collapse most of the list, and both
were verified in the main session:

1. **`SummaryProseGenerator.degradedProseFor`
   (`SummaryProseGenerator.java:193-203`) emits `title — url (uid)` per
   post — inside the prose itself.** So every assertion on a headline, URL
   or uid that runs on a **degraded** path (LLM throwing, or the over-cap
   branch) survives the categorized form unchanged: the categorized form
   renders that same prose, just under a category header. Only
   **non-degraded** (`setResponseText`) sites lose their headline/uid.
2. **`/retry` is untouched**, so every `/retry`-body assertion is green by
   construction.

### Affected — 7 files

| Site | Asserts on default `/summary` | Disposition |
|---|---|---|
| `SummaryCommandHandlerTest:249-276` (`happyPathThreeEligiblePostsYieldsThreeClusterBlocksAndThreeLlmCalls`) | `[topic_id=` ×3, `covered by:`, `classification: technical\n`, `tags: …\n` | retarget the invocation to `/summary --full`; assertions verbatim. Add the default-form coverage as new methods in the same file |
| `SummaryCommandHandlerTest:104-137` (`buildHandlerWithStubs`) | — (hand-wires every `@Inject` field) | extend with a REAL `DigestRenderer` built from the test's own sanitizer/translator/bundle, else the terminal path NPEs and `sanitizerStrips…` becomes a test of a fake |
| `SummaryIT:95-137` (happy path) | four post uids + both source display names (`MvpNews`, `MvpTech`) — non-degraded (`setResponseText`) | retarget to `/summary --full`; assertions verbatim |
| `SummaryGroupScopeIT:124,141-144` | `GROUP FLOW HEADLINE` + `flow-p1` uid — non-degraded | retarget to `/summary --full`; assertions verbatim |
| `SummaryAdapterScopeIT:100,117-121` | `ALPHA HEADLINE m1-040si` present **and** `BRAVO HEADLINE m1-040si` absent — **D46 cross-adapter non-leakage**, non-degraded | retarget to `/summary --full`; BOTH assertions verbatim. `--full` renders the headline identically, so the security property is pinned exactly as before |
| `DevTerminalHarnessRoundtripIT:80,95,109-114` | three seeded post uids, driven as `dm … /summary -w 24h` — non-degraded (`setResponseText("Seeded summary prose.")`) | retarget to `/summary --full -w 24h`; assertions verbatim. This is one of the two sites M1-687's round-2 plan pass found and its label-based census had missed |
| `GoldenPathJourneyIT:238-249` | `postTitle` — **MVP §6 exit criterion**, hop 6, non-degraded | **stays on the default command** — the golden path must walk what a real user gets. Re-point the assertion at the stubbed cluster prose (`"Cluster prose for the journey summary."`), which the categorized form does render |
| `SummaryArgsTest` | — (pure parser tests, no render) | **additions only**: `--full` cases. `tagWithLeadingHyphenIsMalformed:107-117` uses `-leading-hyphen` and is unaffected by adding `--full` |

### Unaffected but load-bearing — 6 files

These pass **unmodified** only because this ticket holds a specific
boundary. If a round of rework moves that boundary, these go red first.

| Site | Green because |
|---|---|
| `RetryCommandHandlerGroupScopeIT:125,162,203` → `:139-146,172-178,211-217` | drives a default `/summary`, then asserts on the **`/retry`** body (`GROUP/DM/UNDATED RETRY HEADLINE`) plus `assertEquals(1, sent.size())`. `/retry` still renders flat (M1-696 is out of scope) and delivery is still one message. The third test is the M1-689 redteam-round-3 NPE regression guard |
| `TranslationPipelineIT:126-153,178-197` | drives a default `/summary -w 24h` in cs and en scopes and pins exact `mockLlm.callCount()` (3 / 2) plus one-send/one-finalized bounds. Green **only because the handler keeps its own `generate(clusters, "en")` call** and the new entry point takes pre-built `ClusterProse`. Its body assertions are on the prose sentinels (`<cs-translation>`, `<en-summary>`), which the categorized form renders. Two clusters share one tag and `infochat.digest.category-min-clusters` defaults to 3, so both land in a single Other section under a `categoryItemCap` of 12 — nothing is capped away. **Audit, do not assume** |
| `SummaryHelpFlagParityTest` | asserts every dash-prefixed flag advertised in `help.cmd.summary.{short,usage}` (en + cs) is accepted by `SummaryArgs.parse`. Documenting `--full` is safe precisely because the parser now accepts it; the test then covers the new flag for free |
| `HelpCommandHandlerTest:273-286` | `contains("/summary [tag] [-w <duration>]")` is a substring check, so appending `[--full]` to the signature line keeps it true; line 273 reads `HELP_CMD_SUMMARY_USAGE` dynamically |
| `ClusterBlockRendererTest` | constructs `ClusterBlockRenderer` directly and never invokes the handler. `ClusterBlockRenderer` is unmodified and still backs `--full` |
| `InboundRouterStopRetryIT:115-116` | asserts on `retryReply.text()` (`Retried summary prose` / `[topic_id=`); drives `/retry`, never a `/summary` |

### Unaffected — remaining 22 files

| Site | Why |
|---|---|
| `SummaryIT:156-185` (over-cap) | over-cap renders **degraded** prose, which carries headline + uid inside the prose; `assertEquals(0, mockLlm.callCount())` and the too-large-notice assertions are preserved by acceptance 6 |
| `SummaryIT:192-211` (MVP degraded) | degraded path — headline + uid live in the prose |
| `SummaryCommandHandlerTest:326-344` (`llmUnreachableYieldsDegradedFallbackReply`) | degraded — `Degraded headline` is inside the degraded prose |
| `SummaryCommandHandlerTest:395-421` (`capExcessYieldsCapExcessNoticePrefix`) | asserts handler-composed prefixes (`Showing 3 of 5`, `2 oldest excluded`) and `proseGenerator.callCount()==3`; the handler still generates for all clusters |
| `SummaryCommandHandlerTest:458-475` (`sanitizerStripsPrivilegedCommandFromLlmAuthoredProse`) | asserts the prose is sanitized; green on the default form **given** the real-renderer seam of acceptance 8 |
| `RouterNoDoubleSendTest:47` | drives `/summary` against a stub `SelfDeliveringHandler`, not the real handler |
| `RetryCommandHandlerTest`, `RetryDigestCommandTest` | `/retry` and `/retry --digest` units; no `ClusterBlockRenderer` body assertions |
| `InboundRouterQueuedFeedbackIT:325` | no-anchor `/retry` queue feedback |
| `InboundRouterInterruptibleClassificationTest` | D35/D61 membership table; command strings only, no render |
| `DigestRoundtripIT`, `DigestDeliveryTest`, `DigestWorkerTest`, `DigestRetryConcurrencyIT`, `DigestPostCollectorIT` | digest path. `render`/`renderSections` are behaviorally unchanged (acceptance 10) |
| `EligiblePostQueryIT`, `EligiblePostQueryClockIT`, `EligiblePostQueryStatementTimeoutIT` | query-level; never reach the renderer |
| `SubscriptionGuidanceCopyTest` | the **empty-window** `/summary` reply, which returns before any render |
| `InboundReflectionGuardTest:166` | the `error.summary.unknown_tag` echo, a parse-failure reply |
| `ProbationCommandListConsistencyTest` | `/summary` as an entry in the probation command list |
| `LlmOutputSanitizerAuditRowIT:34` | drives the sanitizer directly, explicitly *not* a full `/summary` |
| `RetrievalWorldPredicateIT:37`, `SearchPostsToolTest:344`, `SeedFixtureIT:24` | `/summary` appears in javadoc/comments only; these exercise `EligiblePostQuery` and the chat tool |
| `RecordingProgressNotifier`, `InMemoryConversationBackend` | test doubles/helpers, not tests. `InMemoryConversationBackend` watermarks and returns every reply since the mark, matching if any one matches |

## Acceptance

See the frontmatter. In short: default `/summary` routes through a new
no-LLM `DigestRenderer` entry point taking pre-generated `ClusterProse`;
`--full` preserves today's flat `ClusterBlockRenderer` output verbatim on
both branches; delivery, `/retry`, and the scheduled digest are untouched;
the DM-worded overflow string lands in both bundles; the degraded-prose
sanitize/translate split and the over-cap guard's semantics are unchanged.

## Out-of-scope

See the frontmatter for the full list and the reasons. The load-bearing
ones: per-section delivery (M1-695), `/retry` render-form persistence
(M1-696), and any behavioral change to `DigestRenderer.render` /
`renderSections`.

## Notes

Blockers M1-687's second plan pass verified, plus what this ticket's own
grounding pass established. All re-verified in the main session 2026-07-25.

- **Erasure collision.** A cluster- or prose-taking sibling cannot be
  `renderSections(List<Cluster>, String)` or
  `renderSections(List<ClusterProse>, String)` — both share
  `renderSections(List, String)`'s erasure and will not compile. Pick a
  distinct name.
- **`DigestRenderer`'s six collaborator fields and `categoryItemCap` are
  package-private** (`DigestRenderer.java:33-65`), so tests in package
  `provider.command` cannot wire a real renderer. A construction seam is
  needed; `ClusterTraversal.java:62-69` is the in-repo precedent, hand-wired
  cross-package at `SummaryCommandHandlerTest:115`. Note `DigestRenderer` is
  `@ApplicationScoped` with `@Inject` **fields**, so any added constructor
  must leave a usable no-arg path for CDI.
- **The wired renderer must reuse the test's own collaborators.**
  `SummaryCommandHandlerTest` drives the default `/summary` in several tests
  that assert on the recording prose generator and the sanitizer
  (`sanitizerStripsPrivilegedCommandFromLlmAuthoredProse` among them), so a
  hand-written fake renderer would turn those into tests of the fake.
- **The langCode hazard is designed out, not managed.**
  `DigestRenderer.renderSections` passes `langCode` to
  `SummaryProseGenerator.generate` (`DigestRenderer.java:104`) where
  `SummaryCommandHandler` hardcodes `"en"`
  (`SummaryCommandHandler.java:315`). Routing the default path through
  `renderSections(posts, lang)` would change cs-scope routing and break
  `TranslationPipelineIT`'s exact call counts — **and** would hide the
  `ClusterProse` list the handler needs for its `anyDegraded` notice
  (`SummaryCommandHandler.java:353`) and re-cluster the posts a second time.
  Taking pre-built `ClusterProse` instead avoids all four problems at once.
- **The existing overflow and affordance strings are group-worded.**
  `reply.digest.category.more` is `+{0} more — @mention me to see them` and
  `reply.digest.closing_affordance` is `@mention me to go deeper …`
  (`en.properties:832-833`). Neither belongs in a DM. The new entry point
  needs its own `/summary`-scoped overflow key (en + cs per D43) and must
  not emit the digest's closing affordance at all — `/summary` is an
  interactive surface that already composes its own prefixes and notices.
- **Sizing is bounded by the existing guard**: the over-cap guard returns
  before any LLM call, so the default path's prose-call count stays at
  `summarizer-post-cap` (50), identical to today. Because the handler
  generates prose for every cluster and the renderer caps afterwards, the
  call count is byte-for-byte what it is today on every branch — which is
  exactly why the pre-existing call-count assertions survive.
- This ticket **amends spec**: `docs/spec/commands.md` §Periodic group
  digests (the carve-out at `commands.md:1802`) and decision **D62**
  (`decisions.md:79`) both state `/summary` keeps its flat format, which
  this change contradicts.
