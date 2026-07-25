---
id: M1-694
title: "/summary renders the categorized form by default; --full keeps the flat form"
status: done
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
  - docs/spec/security.md
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
    digest test passes unmodified. In particular, acceptance 7's
    sanitize-the-degraded-prose change applies to the /summary entry point
    ONLY — the digest's own unsanitized assembly operands are M1-691's
    ticket and must not be pre-empted here.
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
    In the /summary entry point, BOTH degraded and non-degraded per-cluster
    prose run through llmOutputSanitizer.sanitize; only non-degraded prose
    additionally runs the translation pipeline. Degraded prose keeps its
    translation bypass (D43 is a bundle-not-translator rule) but NOT a
    sanitize bypass: degradedProseFor composes "title — url (uid)" from
    upstream-controlled feed bytes, and security.md §LLM output sanitizer
    requires a title whose canonical form is a privileged command to render
    as [redacted command]. Bare URLs survive — the sanitizer loses no
    characters and only breaks "](" adjacency (security.md §"Sanitizer
    output never contains `](`"), so D30's bare-URL requirement holds.
    REDTEAM ROUND 1, finding 1 (INJECTION, medium).
  - >-
    A feed title whose canonical form is a privileged command produces a
    per-occurrence LLM_OUTPUT_SANITIZED audit row when it reaches a user
    through the DEFAULT /summary form, including on the over-cap branch —
    restoring the operator's mechanical detector, which the pre-diff path
    emitted from ClusterBlockRenderer.java:87 and which the categorized form
    dropped by rendering no headline. A test pins that a command-shaped
    title renders as [redacted command] in the default form.
    REDTEAM ROUND 1, finding 2 (AUDIT-EVASION, medium).
  - >-
    The DEFAULT form keeps a non-vacuous end-to-end isolation assertion —
    retargeting the negatives onto --full must not leave the form every user
    receives unguarded. SummaryAdapterScopeIT additionally drives a bare
    /summary and asserts the rendered body carries EXACTLY ONE cluster prose
    paragraph (the shared contact subscribes only to the alpha source, so a
    foreign-adapter leak renders a second one); GoldenPathJourneyIT's hop-6
    assertion is likewise made count-sensitive rather than a bare contains.
    Occurrence-counting is the discriminator because the prose stub returns
    one fixed string for every cluster, so a contains() on it cannot
    distinguish one surfaced post from two.
    REDTEAM ROUND 1, finding 3 (INFO-LEAK, low).
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
  - >-
    docs/spec/security.md is amended so the threat model stops asserting a
    control the code does not perform. The M1-675 closure paragraph
    (security.md:381-397) names "the `/summary` cluster headline" as a
    render-side-redacted surface; the default form renders no headline at
    all, so that clause must describe what actually ships — the headline
    redaction applies to the --full form, and the default form's protection
    is the per-cluster prose sanitize of acceptance 7. The §"Sanitizer output
    never contains `](`" residual paragraph (security.md:526-536) likewise
    says the degraded branch "sanitizes each feed-derived headline but joins
    the results with the source's display name and a bare URL"; under
    acceptance 7 the /summary path sanitizes the whole assembled degraded
    prose, so that description must be corrected for this path while leaving
    the digest's own operands to M1-691. Missing this amendment while
    amending commands.md and D62 is exactly what redteam round 1 caught.
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
reviews:
  - round: 1
    date: 2026-07-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      assertion_adequacy: WARN
    diff_stats:
      files: 22
      added: 1441
      removed: 67
    note: |
      ASSERTION-ADEQUACY WARN (informational, not a FAIL — both mandated
      questions answered yes): the /summary overflow line
      (DigestRenderer.java:245-249) has no test anywhere in the repo. A
      mutation swapping REPLY_SUMMARY_CATEGORY_MORE for
      REPLY_DIGEST_CATEGORY_MORE emits the group-worded "@mention me to see
      them" into a DM — exactly what acceptance 14 forbids — and survives the
      whole suite, since BundleLoaderTest only pins en/cs keyset parity.
      Reachable in production (up to summarizer-post-cap=50 clusters can land
      in one section against a cap of 12). Closed in round 2 rather than
      deferred.
      REDTEAM GATE NOT RE-FIRED for round 2, deliberately and on evidence: the
      round-2 delta is one additive test method plus the accepted-residual
      documentation in security.md that redteam round 3 itself asked for. No
      file under any module's src/main is newer than
      docs/plan/m1/redteam/M1-694-2026-07-25-r3.md, so the threat surface is
      byte-identical to what round 3 audited. Re-auditing unchanged production
      code has a known failure mode — a later round under momentum manufactures
      a finding to justify itself — and no upside.
  - round: 2
    date: 2026-07-25
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      assertion_adequacy: PASS
    diff_stats:
      files: 22
      added: 1513
      removed: 68
    note: |
      Round 2 exists because round 1's ASSERTION-ADEQUACY WARN was closed
      rather than deferred: the /summary overflow line had zero coverage, so a
      one-token swap to the group-worded digest key would have shipped
      "@mention me to see them" into every DM and survived the suite. Added
      defaultFormOverflowLineUsesDmWordingNotTheGroupDigestWording. All six
      checks PASS. Must-shrink not applicable — files unchanged at 22, so
      growth is not simultaneous on all three axes.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-25
    category: INJECTION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer, M1-675 paragraph: "the
      group-visible echo surfaces (/saved reply, the /summary cluster
      headline, and the degraded group digest) are additionally passed
      through the closed-list LlmOutputSanitizer at render, where a title or
      tag whose canonical form is a privileged command renders as
      [redacted command]."
    gap: |
      The categorized default form renders no post title at all, so no
      upstream-controlled title reaches llmOutputSanitizer.sanitize on the
      path every user now hits. DigestRenderer.renderSummarySections emits
      only the section header plus per-cluster prose, and appends degraded
      prose verbatim; degradedProseFor composes "title — url (uid)" from raw
      feed bytes. The pre-diff default called
      ClusterBlockRenderer.appendClusterBlock, which sanitized the headline
      at ClusterBlockRenderer.java:87; after the diff that call is reachable
      only behind the opt-in --full flag. commands.md and D62 were amended
      for the new render form, but security.md's M1-675 closure claim was
      not, so the threat model now asserts a control the default path does
      not perform.
    repro: |
      Publish a feed post whose title is a privileged command string (e.g.
      "/ban p-victimcontact"); title normalization is still open (M1-693).
      Publish more than infochat.summary.summarizer-post-cap posts in a
      window to force the deterministic over-cap branch — no LLM outage
      needed. Any non-banned user runs bare /summary; the reply renders
      "/ban p-victimcontact — https://attacker/… (uid p-…)" at line start
      with no [redacted command] substitution. Pre-diff the same reply
      carried [redacted command] on the headline line.
    suggested_fix_class: input-sanitization
  - date: 2026-07-25
    category: AUDIT-EVASION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer: "the M1-675 render-side
      redaction, by contrast, emits the per-occurrence LLM_OUTPUT_SANITIZED
      audit row on every hit ... Every match is audit-logged
      (per-occurrence, not throttled)."
    gap: |
      The audit row is emitted only from inside LlmOutputSanitizer.sanitize.
      Because the default /summary form never calls sanitize on any post
      title, and skips the sanitizer entirely for degraded prose, a
      feed-supplied privileged-command token that reaches a user through
      /summary now produces zero audit rows and zero WARN lines. The
      pre-diff path produced one row per matching cluster headline. The loss
      covers the deterministically-reachable over-cap branch, not just the
      LLM-outage branch, so the operator loses the only mechanical signal
      that a feed is injecting command-shaped titles into user-visible
      output.
    repro: |
      Run the INJECTION finding's sequence, then query audit_log_view as the
      operator Admin role. Pre-diff each /summary surfacing a command-shaped
      feed title wrote one LLM_OUTPUT_SANITIZED row; post-diff the same
      attack produces an identical user-visible delivery with an empty audit
      trail, leaving no way to distinguish "no attack" from "attack with the
      detector removed".
    suggested_fix_class: audit-log-coverage
  - date: 2026-07-25
    category: INFO-LEAK
    severity: low
    promise: |
      docs/spec/security.md §Trust boundaries item 1 and the cross-adapter
      isolation invariant (messaging.md §Per-adapter trust level, referenced
      from §Threat model: "the cross-adapter isolation invariant … prevents
      identity bleed between adapters").
    gap: |
      Every end-to-end negative assertion for /summary was moved off the new
      default render path onto the opt-in --full path, so the form all users
      now receive has no integration-level coverage of the isolation
      negatives — including SummaryAdapterScopeIT's D46 cross-adapter
      non-leakage assertFalse. GoldenPathJourneyIT stays on the default but
      its content assertion was weakened from the seeded post title to the
      stubbed LLM prose string, so it can no longer distinguish which posts
      were surfaced. This is a coverage/resilience loss, not a live leak:
      the world predicate is enforced upstream in EligiblePostQuery.fetch
      identically for both forms, and renderSummarySections uses only
      method-local state.
    repro: |
      No live exploit today. Mutation check: reintroduce a contact_id-only
      user lookup (the bug SummaryAdapterScopeIT was written to catch) and
      the suite still goes green for every user running bare /summary — only
      the retargeted --full invocation fails.
    suggested_fix_class: other
  - date: 2026-07-25
    round: 2
    status: CLOSED
    category: INJECTION
    severity: medium
    promise: |
      docs/spec/security.md §LLM output sanitizer, as amended by the round-1
      remediation: "Both forms therefore redact, and both emit the audit row
      below; neither leaves a command-shaped title unredacted."
    gap: |
      The final clause was false for the --full form this ticket introduces.
      ClusterBlockRenderer.java:112-115 writes the summary: field as
      cp.degraded() ? cp.prose() : translate(sanitize(...)), so the degraded
      arm passes prose through verbatim, and degradedProseFor composes
      "title — url (uid)" for every post from raw feed bytes. The headline
      sanitize at :87 also only ever sees posts.get(0), so a multi-post
      cluster whose command-shaped post is not first emits no
      LLM_OUTPUT_SANITIZED row. The round-1 remediation thus mirrored onto
      --full the exact failure mode round 1 flagged for the default form:
      the threat model asserting a control the code does not perform.
    repro: |
      Publish a post titled "/grant-admin p-attacker" on a bootstrap source
      (D59: public to every scope; title normalization is still open,
      M1-693), force the degraded branch with volume alone, then run
      /summary --full. The summary: line carries the raw title while the
      headline line above reads [redacted command].
    suggested_fix_class: input-sanitization
    resolution: |
      Remediated by SPEC WORDING ONLY. The §LLM output sanitizer paragraph
      now states that completeness differs per render form and labels the
      --full and /retry surface "open, not closed"; the "](" residual
      paragraph records that neither M1-691 nor this ticket owns it.
      ClusterBlockRenderer.java is NOT modified — it is in this ticket's
      out_of_scope, and the runtime behavior is pre-existing and strictly
      narrower than what shipped pre-diff, where the flat form was the only
      /summary form. Round 3 verified this CLOSED sentence by sentence.
  - date: 2026-07-25
    round: 3
    status: OPEN
    category: INJECTION
    severity: low
    promise: |
      docs/spec/security.md §LLM output sanitizer, "Flag position mirrors
      the parser's own scan": the whole-message redaction span is accepted
      at spec level ONLY on the stated ground that the bytes swallowed
      between the command word and the flag are bot-authored.
    gap: |
      This ticket changes the sanitizer's UNIT OF INPUT on the default
      /summary path from one feed title to one cluster's whole assembled
      multi-post prose. DigestRenderer.java:241 calls sanitize(cp.prose())
      once per cluster, and degradedProseFor concatenates "title — url
      (uid)" for EVERY post, so that single call spans several mutually
      untrusted publishers' bytes. redactFlagEntry
      (LlmOutputSanitizer.java:294-333) deletes everything from the command
      word through the flag token (appended = flagEnd at :327) and the
      token search crosses newlines (isTokenSeparator includes '\n'), so a
      redaction span can now cross post boundaries and erase a third
      party's content. Every pre-existing feed-bytes sanitize site is
      scoped to a SINGLE author's field (ClusterBlockRenderer:87,
      DegradedDigestRenderer:38, SavedCommandHandler:275/277). The two
      flag-bearing closed-list entries are "/list-sources --all" and
      "/list-sources --include-deleted".
    repro: |
      Co-cluster three posts via a post_reference edge (e.g. a Nostr kind-6
      repost, which security.md §Per-source trust boundaries specifies
      writes such an edge) so cluster.posts() orders them A, V, B with
      titles "/list-sources", "Legitimate headline", "--all". Force the
      degraded branch with volume. A bare /summary hands the whole cluster
      string to one sanitize() call; the span from /list-sources to --all is
      replaced by [redacted command], deleting V's headline, URL and uid.
      Pre-diff the sanitize input was a single title, where no cross-post
      span was constructible. Direction is over-redaction, not
      under-redaction, and the marker plus audit row still ship — hence low.
    suggested_fix_class: trust-boundary-tightening
    resolution: |
      ACCEPTED RESIDUAL, documented in docs/spec/security.md §LLM output
      sanitizer ("The span's justification depends on the caller's unit of
      input"). Not fixed in this ticket, by explicit decision: the direction
      is over-redaction rather than under-redaction, the marker is visible
      and the audit row still fires, so it costs the availability of one
      story rather than the injection guarantee. The cheap in-scope fix
      (sanitize per line inside DigestRenderer) is REJECTED as unsafe —
      "/list-sources" is not a bare CLOSED_LIST entry (only the two
      flag-bearing forms are), so a feed title containing a newline would
      split across lines and UNDER-redact, which is the wrong direction and
      is reachable until M1-693 normalizes titles. The correct fix scopes the
      sanitize call to one post rather than one cluster, which needs
      SummaryProseGenerator.java — outside this ticket's files_scope. Filed
      as a follow-up covering the whole class (this cross-post span,
      /summary --full, and /retry), none of which M1-691 owns: its
      files_scope is DegradedDigestRenderer.java and its out_of_scope names
      /summary explicitly.
redteam_audits:
  - date: 2026-07-25
    verdict: FINDINGS
    base: 6c487d9cc65e1dc1fe3acd5b0418dbc4906a2379
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-694-2026-07-25.md
    findings_count: 3
    out_of_model_count: 2
    note: |
      Round 1 at the /m1-tick run redteam gate, ahead of review. Findings 1
      and 2 share one root cause — the categorized default form renders no
      headline, so the M1-675 render-side sanitize and its per-occurrence
      LLM_OUTPUT_SANITIZED audit row are both absent from the default path,
      while security.md still records that control as closed for /summary.
      Finding 3 is the coverage regression from retargeting the end-to-end
      negatives onto --full. Two out-of-model items: the raw title inside
      degraded prose predates this diff (booked as residual, M1-691), and
      DigestRenderer.forSummaryRendering widens the production API with a
      partially-initialised-bean seam that is not adversary-reachable.
  - date: 2026-07-25
    verdict: FINDINGS
    base: 6c487d9cc65e1dc1fe3acd5b0418dbc4906a2379
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-694-2026-07-25-r2.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Round 2, after remediating round 1. All three round-1 findings verified
      CLOSED against the code, not accepted on the remediation note. One new
      medium, in the remediation itself: the security.md amendment asserted
      "neither leaves a command-shaped title unredacted", which is false for
      --full. Remediated in-band by correcting the spec wording only, since
      that finding is a failure of the refine's own accepted acceptance item
      ("security.md stops asserting a control the code does not perform") and
      ClusterBlockRenderer is out_of_scope.
  - date: 2026-07-25
    verdict: FINDINGS
    base: 6c487d9cc65e1dc1fe3acd5b0418dbc4906a2379
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-694-2026-07-25-r3.md
    findings_count: 1
    out_of_model_count: 4
    note: |
      Round 3, after remediating round 2. The round-2 medium verified CLOSED
      sentence by sentence. One new LOW, not remediated and surfaced to the
      user: widening the sanitizer's unit of input to a cluster's whole
      multi-post prose lets redactFlagEntry's command-to-flag span cross post
      boundaries and delete a third party's post. Fails safe (over-redaction,
      visible marker, audit row still emitted). The cheap in-scope fix
      (sanitize per line) risks UNDER-redaction when a feed title itself
      contains a newline, which titles may until M1-693 lands; the thorough
      fix needs SummaryProseGenerator, outside files_scope. Also noted
      out-of-model: capped-out clusters are no longer scanned, so the
      operator's corpus-wide detector coverage narrows — worth a deliberate
      accept.
clarity_check:
  date: 2026-07-25
  verdict: PASS
  warnings:
    - >-
      lint PASS (0 blockers, 0 warnings). The skeleton this ticket started as
      failed lint on OUT-OF-SCOPE-PRESENT and carried empty acceptance /
      files_scope plus three TO BE WRITTEN body sections; filled in from
      M1-687's settled shape and re-verified against the code (commit
      6c487d9c).
    - >-
      CLASS-COMPLETENESS - re-ran the body's invocation-based census grep
      live: 35 files, and all 35 are named in the ticket's three disposition
      tables (verified mechanically per basename, not by row count - the
      tables group some files and give others multiple rows, so 31 rows cover
      35 files).
    - >-
      Ticket-vs-code spot checks all passed: ClusterBlockRenderer.java:87/:94
      are the bare headline and uid, DigestRenderer.java:33-65 fields are
      package-private and :104 passes langCode where
      SummaryCommandHandler.java:315 hardcodes "en",
      SummaryProseGenerator.degradedProseFor:193-203 emits title/url/uid
      inside the prose, en.properties:832-833 are group-worded,
      application.properties:309,324 carry the over-cap arithmetic,
      commands.md:1802 and decisions.md:79 carry the carve-outs to amend, and
      every cited test line range matches its stub mode (setResponseText vs
      setThrowOnCall) and assertion text.
    - >-
      Two couplings M1-687 never listed were found and verified green without
      edits: SummaryHelpFlagParityTest (advertised-flag/parser parity) and
      HelpCommandHandlerTest:275 (substring signature assertion survives
      appending the new flag).
  blockers: []
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
| `SummaryAdapterScopeIT:100,117-121` | `ALPHA HEADLINE m1-040si` present **and** `BRAVO HEADLINE m1-040si` absent — **D46 cross-adapter non-leakage**, non-degraded | retarget to `/summary --full`; BOTH assertions verbatim. `--full` renders the headline identically, so the security property is pinned exactly as before. **Additionally** drive a bare `/summary` and assert exactly one cluster prose paragraph, so the DEFAULT form keeps a non-vacuous non-leakage guard (redteam round 1, finding 3) |
| `DevTerminalHarnessRoundtripIT:80,95,109-114` | three seeded post uids, driven as `dm … /summary -w 24h` — non-degraded (`setResponseText("Seeded summary prose.")`) | retarget to `/summary --full -w 24h`; assertions verbatim. This is one of the two sites M1-687's round-2 plan pass found and its label-based census had missed |
| `GoldenPathJourneyIT:238-249` | `postTitle` — **MVP §6 exit criterion**, hop 6, non-degraded | **stays on the default command** — the golden path must walk what a real user gets. Re-point the assertion at the stubbed cluster prose (`"Cluster prose for the journey summary."`), which the categorized form does render, and make it count-sensitive (exactly one paragraph for the one seeded post) so it can still distinguish which posts surfaced (redteam round 1, finding 3) |
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
- This ticket **amends spec** in three files: `docs/spec/commands.md`
  §Periodic group digests (the carve-out at `commands.md:1802`) and decision
  **D62** (`decisions.md:79`) both state `/summary` keeps its flat format,
  which this change contradicts; and `docs/spec/security.md` records a
  render-side redaction of "the `/summary` cluster headline" (`:381-397`)
  plus a residual paragraph describing the degraded branch as sanitizing
  "each feed-derived headline" (`:526-536`), neither of which describes the
  default form. Redteam round 1 caught the security.md omission — amending
  the two behavioral specs while leaving the threat model asserting a
  control the code no longer performs is the failure mode to avoid.
- **Why degraded prose is sanitized here but not translated.** The two
  bypasses have different reasons and only one of them survives. Translation
  is skipped because degraded prose is deterministic, non-LLM text and D43
  is a bundle-not-translator rule. Sanitization was skipped for the same
  "it's not LLM output" intuition, but that is the wrong test: the bytes are
  upstream-controlled feed titles, which is precisely the tier M1-675's
  render-side redaction exists for. `ClusterBlockRenderer` gets away with
  the bypass only because it sanitizes the headline separately
  (`ClusterBlockRenderer.java:87`); the categorized form has no headline, so
  the bypass would leave nothing sanitized at all.
