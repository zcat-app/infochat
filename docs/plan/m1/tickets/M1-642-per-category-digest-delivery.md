---
id: M1-642
title: "Per-category digest delivery + optional roll-up summaries"
status: done
created: 2026-07-17
last_updated: 2026-07-20
clarity_check:
  date: 2026-07-20
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-642.md
blocked_by:
  - M1-641
files_budget: 15
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestWorker.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestDelivery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/DigestRenderer.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/digest/CategoryRollupGenerator.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/OutboundDelivery.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/RecordingDigestRenderer.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestWorkerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/OutboundDeliveryTest.java
  - docs/spec/commands.md
  - docs/spec/messaging.md
  - docs/spec/decisions.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The categorization algorithm, thresholds, headers, item caps, and closing
    affordance — all delivered by M1-641 (Phase 1). This ticket consumes the
    ordered (category, clusters) structure Phase 1 produces; it does not change
    how clusters are assigned to categories.
  - >-
    /summary and ClusterBlockRenderer — unchanged. The roll-up summary is a
    digest-only prepend.
  - >-
    The single-row summary_cache model — one cache row per slot is retained.
    Delivery splits the rendered content at category boundaries at SEND time;
    it does not add per-category cache rows or change the /retry --digest cache
    contract (D17).
  - >-
    Gap-filling redelivery — tracking which categories landed so a later
    /retry --digest sends only the missing ones. That needs persisted
    per-(slot, category) delivery state AND replay state (arm (b), decided
    2026-07-20: M1-652 persists the section list at render time and replays
    it) and is M1-652's job, which is blocked_by THIS ticket (the
    per-category structure must exist before there are gaps to fill). This
    ticket adds no delivery-state persistence and no dedup logic. Until
    M1-652 lands, a /retry --digest re-posts every category, including ones
    already delivered — see the acceptance item on redelivery.
  - >-
    Adapter-side chunking. SimpleXOutboundChunker still applies its 4 000-byte
    line-based chunking to each category message independently; this ticket does
    not change, parameterize, or bypass it.
  - >-
    The retry ladder itself. deliverSequenceToGroup changes only the counter
    ATTRIBUTION granularity (one aggregate outcome per sequence); per-message
    failure classification, backoff, max-attempts, and the threshold value
    are untouched, and single-message callers (deliverToGroup) keep today's
    per-call attribution.
acceptance:
  - >-
    FORK CLOSED (user decision 2026-07-20): M1-652 runs arm (b) — replay
    PERSISTED sections on retry. The consequence folded into THIS ticket:
    renderSections() output is the EXACT delivery bytes — the closing
    affordance is folded into the last section's text and flag-on roll-up
    prefixes live inside their sections — and DigestDelivery sends whatever
    ordered section list it is handed, so M1-652 can persist the list at
    render time and replay a filtered subset on /retry --digest without
    re-deriving anything. This ticket still persists nothing and adds no
    replay logic.
  - >-
    A non-degraded digest with at least one post is handed to the outbound
    chokepoint as one OutboundMessage per category, plus one "Other" message
    when the Other bucket is non-empty (DigestCategorizer emits Other only
    when non-empty) — bounded at categories+1. The zero-posts fixed reply
    stays a single message: it is non-degraded but has no sections.
    DigestDelivery never places two categories in one message and never
    splits a category across two messages. (It cannot promise a cluster is
    never split: a category message exceeding the adapter's 4 000-byte cap is
    chunked line-wise by SimpleXOutboundChunker, which may land inside a cluster.
    This ticket reduces mid-cluster splits; it does not eliminate them.)
  - DigestDeliveryTest.splitsOnCategoryBoundariesNotSize passes
  - >-
    Category messages are sent SEQUENTIALLY in section order (D62 order:
    assigned-cluster count descending, alphabetical ties, Other last) —
    never fanned out in parallel. Sequential order is what makes "closing
    affordance on the last message" deterministic and preserves the digest's
    narrative order; a parallel implementation would pass every other test
    in this list, so the property is pinned explicitly.
    DigestDeliveryTest.sendsCategoriesSequentiallyInSectionOrder passes.
  - >-
    Partial-failure policy: each category message carries a per-(digest slot,
    category) correlationId — digest-<groupId>-<windowStart>-<categorySlug>,
    where categorySlug is the category's tag string as-is and the literal
    "other" for the Other bucket (tags are controlled vocabulary, so no
    further normalization; M1-652's (group_id, window_start, category_slug)
    delivery-state key inherits this mapping) — and is delivered
    independently through the outbound chokepoint, so a TRANSIENT failure
    retries only that category's message and a PERMANENT failure on one
    category still delivers the others.
    DigestDeliveryTest.retriesFailedCategoryMessageIndependently passes.
  - >-
    Counter attribution: one digest slot contributes at most ONE outcome to
    the per-group consecutive permanent-failure counter. OutboundDelivery
    gains deliverSequenceToGroup(adapter, messages, groupId): each message
    runs the existing per-message TRANSIENT-retry / PERMANENT-abort ladder
    unchanged, but the counter receives a single aggregate outcome per call —
    reset when at least one message was delivered, one increment only when
    every message in the sequence failed permanently. This is not optional
    hardening: the threshold is 3 in every profile except pi (5), calibrated
    for one message per slot, and its documented invariant — "always > 1, so
    a single misclassified failure cannot trigger it" — dies under naive
    per-category deliverToGroup calls, because SimpleX classifies a send on a
    closed or not-yet-started WebSocket as an IMMEDIATE PERMANENT
    (SimpleXWebSocketClient.sendCommand), so one routine simplex-chat
    subprocess restart during the sequential loop yields >= 3 instant
    PERMANENTs and soft-removes a healthy group in milliseconds, with no
    admin notification.
    The ladder's THIRD outcome is pinned too: an interrupted back-off
    already aborts with NO counter attribution today (backOff re-interrupts
    and returns false; execute returns ABORTED without
    onPermanentGroupFailure), and the sequence mirrors it — an interrupt
    mid-sequence stops the remaining sends and applies NO aggregate outcome
    (neither reset nor increment), so a Provider shutdown mid-digest never
    counts toward soft-removal.
    OutboundDeliveryTest.sequenceAttributesAtMostOneCounterOutcome passes
    (an all-permanent sequence of >= threshold messages increments the
    counter once and does NOT soft-remove the group; a sequence with one
    success resets it),
    OutboundDeliveryTest.interruptedSequenceStopsWithoutCounterAttribution
    passes (interrupt driven through the Sleeper seam), and
    DigestDeliveryTest.oneFailedDigestNeverSoftRemovesGroup passes.
  - >-
    Redelivery posts again and MAY duplicate: nothing in v1 records which
    categories were delivered, so a /retry --digest re-runs the slot and
    re-posts every category it produces, including any that already landed.
    This is deliberate — docs/spec/commands.md §"Cached digest message handle"
    commits that /retry --digest posts a new message, so suppressing it would
    be a spec violation. Each category message carries its own correlationId,
    but NO cross-regeneration stability is required or asserted: no consumer
    dedups on it, and M1-652 (gap-filling) will key on the
    (groupId, windowStart, categorySlug) tuple directly rather than on the id.
    DigestDeliveryTest.retryRepostsEveryCategory passes.
  - DigestDeliveryTest.partialFailureDeliversRemainingCategories passes
  - >-
    DigestRenderer's public render(posts, langCode) contract is UNCHANGED and
    its output stays byte-identical at the roll-up flag's default (off): the
    per-category structure is exposed by a new renderSections() and render()
    becomes a thin join over it. Proof: the pre-existing DigestRendererTest
    and DigestWorkerClockTest pass unmodified. Flag-ON roll-up prefixes may
    appear inside sections and therefore in render()'s join and the cached
    content — harmless: summary_cache.content has no reader in main code
    (verified 2026-07-20).
  - >-
    The closing affordance appears exactly once per digest, on the LAST
    category message only — never once per message. Mechanism (pinned by the
    arm-(b) fork decision): renderSections() folds the affordance into the
    last section's text, so render() stays a pure "\n\n" join (byte-identity
    preserved — the join reproduces today's trailing affordance exactly),
    DigestDelivery appends nothing, and persisted sections replay as the
    exact delivery bytes.
    DigestRendererSectionsTest.affordanceFoldedIntoLastSectionOnly and
    DigestDeliveryTest.appendsClosingAffordanceOnlyToFinalMessage pass.
  - >-
    Optional per-category roll-up: when infochat.digest.category-summary-enabled
    (default false) is on, each category message is prefixed with a 1–2 sentence
    LLM roll-up SYNTHESIS across ALL of that category's clusters — including
    any past the per-section item cap; the roll-up is the one line that names
    what the "+N more" line hides (a headline-level summary, not a restatement
    of the items) — ONE LLM request per category, alongside the existing
    one-request-per-cluster prose; the roll-up step is the only NEW LLM stage,
    routed as ModelTask.SUMMARIZER with its own system-prompt constant (a new
    ModelTask is out of reach for this ticket — closed enum in
    infochat-llm-adapter whose widening requires an llm.md spec amendment plus
    routing-config and SwitchLlmWiringTest changes, all outside files_scope;
    see Notes). When off, the category message is just header + items
    (Phase 1 behavior per message).
    CategoryRollupGeneratorTest.producesOneRollupPerCategory passes.
  - >-
    Roll-up prose is LLM output and receives the same outbound treatment as
    cluster prose: LlmOutputSanitizer.sanitize, then TranslationPipeline.run
    (which re-runs the sanitizer on translated text per llm.md) —
    security.md §LLM output sanitizer is unconditional ("before any
    LLM-generated text is delivered to a user").
    CategoryRollupGeneratorTest.rollupIsSanitizedAndTranslated passes.
  - >-
    Roll-up failure containment: roll-ups are generated inside the
    slot-window render budget (the same windowEnd deadline that bounds
    cluster prose), never at delivery time — delivery time sits outside
    every timeout budget in the pipeline. A roll-up LLM failure for a
    category yields that category's message WITHOUT a prefix (exactly the
    flag-off shape); it never degrades, blocks, or reorders the digest. A
    render-budget overrun still degrades the whole digest to the D17
    headlines-only message, exactly as slow cluster prose does today —
    roll-ups add no new degrade mode.
    CategoryRollupGeneratorTest.failedRollupYieldsCategoryWithoutPrefix passes.
  - >-
    The roll-up is prose only; category assignment stays deterministic (Phase 1).
    A degraded digest still delivers as the single headlines-only message (D17) —
    no per-category split, no roll-up.
  - >-
    docs/spec/commands.md §Periodic group digests and docs/spec/messaging.md
    §Failure handling document per-category delivery and the partial-failure
    policy, state explicitly that redelivery may duplicate, and record the
    slot-level counter attribution (one aggregate outcome per digest slot);
    a new decision D63 records all of it.
  - >-
    SafeLog on the roll-up LLM-failure path: the catch in
    CategoryRollupGenerator.generateRollup routes its RuntimeException
    through app.zcat.infochat.core.log.SafeLog (the project's exception-safe
    logger — drops the message body, keeps only the class name plus a
    depth-capped cause chain of class names, and runs the caller msg through
    the API-key redactor), NOT a raw LOG.warnf(e, ...) that passes the
    Throwable to SLF4J. The roll-up prompt interpolates post titles, bodies,
    and urls, so a provider / sanitizer / translation exception can carry
    user-authored prose in its message or cause chain; SafeLog is what keeps
    that prose out of the operator log stream per docs/spec/security.md
    §Secrets handling "User content in exceptions" — the same convention
    every other infochat-provider LLM-call catch already follows (ChatAgent,
    CompressCommandHandler, SummaryCommandHandler). (Added 2026-07-20 from
    the M1-642 redteam-multi INFO-LEAK finding.)
  - mvn -pl infochat-provider verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestDeliveryTest.java
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererSectionsTest.java
      — the renderSections() unit surface: section order matches D62,
      affordance folded into the last section only, and join-identity
      (String.join("\n\n", sections) equals render()'s output byte-for-byte).
      A NEW file so the pre-existing DigestRendererTest stays the
      unmodified byte-identity proof.
    - infochat-provider/src/test/java/app/zcat/infochat/provider/digest/CategoryRollupGeneratorTest.java
  modifies:
    - >-
      RecordingDigestRenderer.java — the stub currently overrides only
      render(); it must also override the new renderSections() (returning one
      section wrapping the configured response), otherwise DigestWorker's call
      reaches the real implementation and NPEs on the un-injected collaborators
      of this hand-wired unit test. The setBlocking latch must ALSO move to
      (or additionally gate) renderSections(): the in-flight overlap test
      parks an execution mid-render via that latch (DigestWorkerTest:238),
      and once the worker calls renderSections() a latch left only on
      render() makes the overlap test hang on its entered-await instead of
      exercising the guard. The calls counter must migrate the same way:
      DigestWorkerClockTest:81 and DigestWorkerTest:160 assert
      callCount()==1 against the entry point the worker invokes, and
      DigestWorkerClockTest must pass UNMODIFIED — a renderSections()
      override that returns a section without incrementing the counter
      fails it with no permitted fix.
    - >-
      OutboundDeliveryTest.java — ADDS the sequence-attribution cases
      (all-permanent sequence of >= threshold messages increments the counter
      once and does not soft-remove the group; a partial success resets the
      counter; an interrupted back-off mid-sequence stops the remaining sends
      with NO attribution, interrupt driven through the Sleeper seam;
      per-message retry/abort behavior inside a sequence is unchanged).
      Existing cases pass unmodified.
    - >-
      DigestWorkerTest.java — ADDS one case proving a multi-section render
      produces N sends through the chokepoint. The existing single-section
      cases keep asserting sendCount()==1 (the stub yields one section), so
      :104 and :124 are unchanged.
  preserves:
    - >-
      DigestRendererTest.java and DigestWorkerClockTest.java pass UNMODIFIED —
      this is the check that keeps render()'s contract honest (acceptance item 5).
    - all other tests currently green on main
spec_refs:
  - docs/spec/commands.md §Periodic group digests
  - docs/spec/messaging.md §Failure handling
  - docs/spec/security.md §LLM output sanitizer
decision_refs:
  - D17
  - D36
revisions:
  - date: 2026-07-20
    reason: >-
      Pre-implementation plan review (each finding falsified against code
      before adoption). (1) Roll-up routing pinned to ModelTask.SUMMARIZER —
      the choice was never actually open: ModelTask is a closed enum in
      infochat-llm-adapter whose widening requires an llm.md spec amendment
      (its own javadoc), per-profile routing config (D56: an unrouted task
      refuses boot on remote-llm), and the SwitchLlmWiringTest update — all
      outside files_scope and outside -pl infochat-provider verify.
      (2) Bundle-key ambiguity resolved to NONE NEEDED (the prefix is LLM
      prose on the translator path; the prompt is a Java constant per the
      SummaryProseGenerator precedent) — BundleKeys.java + en/cs.properties
      leave files_scope, files_budget 18 -> 15. (3) RecordingDigestRenderer's
      calls counter must migrate to renderSections() alongside the latch
      (DigestWorkerClockTest:81 asserts callCount()==1 and must pass
      unmodified). (4) The ladder's third outcome pinned: an interrupted
      back-off stops the sequence with NO aggregate attribution;
      OutboundDeliveryTest.interruptedSequenceStopsWithoutCounterAttribution
      named. (5) Roll-up input pinned to ALL of the category's clusters (it
      names what "+N more" hides). Notes gained three implementer
      constraints: no execute() refactor is needed (null-groupId execute
      calls are already the unattributed ladder), the worker caches the join
      of the section list it already holds (a second render() call would
      re-run the LLM pass and let cached prose diverge from delivered
      bytes), and DigestDelivery is dereferenced only after the findAdapter
      null-check (DigestWorkerClockTest leaves the field unset and must pass
      unmodified).
    snapshot:
      files_budget: 18
      files_scope_extra:
        - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
        - infochat-provider/src/main/resources/bundles/en.properties
        - infochat-provider/src/main/resources/bundles/cs.properties
  - date: 2026-07-20
    reason: >-
      M1-652 fork closed — user chose arm (b) (replay persisted sections).
      The RESOLVE-BEFORE-START gate became a resolution note; the one design
      consequence pinned here is that renderSections() output is the exact
      delivery bytes: the closing affordance is folded into the LAST section
      (render() stays a pure "\n\n" join, still byte-identical) and flag-on
      roll-up prefixes live inside their sections, so M1-652 can persist and
      replay sections verbatim. DigestRendererSectionsTest.java added to
      files_scope (17 of budget 18) as the renderSections() unit surface,
      keeping DigestRendererTest as the unmodified byte-identity proof.
    snapshot:
      acceptance_item_1: RESOLVE-BEFORE-START gate on the then-open M1-652 fork
  - date: 2026-07-20
    reason: >-
      Design-analysis rework (pre-start). (1) Counter-attribution hole
      closed: naive per-category deliverToGroup calls would let one
      transport blip (SimpleX classifies sends on a closed/not-yet-started
      WebSocket as immediate PERMANENT) soft-remove a healthy group in a
      single slot — threshold is 3, calibrated for one message per slot.
      OutboundDelivery.deliverSequenceToGroup (one aggregate counter outcome
      per sequence) added; OutboundDelivery.java + OutboundDeliveryTest.java
      enter files_scope, files_budget 16 -> 18. (2) Roll-up hardened:
      sanitize+translate mandated (security.md §LLM output sanitizer is
      unconditional), failure/timeout containment specified (failed roll-up
      -> category without prefix), generation pinned inside the slot-window
      render budget, "only new LLM call" corrected to one request per
      category; security_relevant flipped to true (new LLM-to-user output
      path). (3) RESOLVE-BEFORE-START gate added: M1-652's degraded-retry
      fork must be closed first — arm (b) reshapes this ticket's
      renderer/cache seam. (4) Wording: zero-posts carve-out in item 1,
      Other-only-when-non-empty, sequential section-order delivery pinned
      (a parallel implementation would have passed every listed test),
      categorySlug mapping defined, stub blocking-latch migration noted.
      No behavior of the core design changed; all four are gaps the
      2026-07-20 falsification pass found.
    snapshot:
      files_budget: 16
      security_relevant: false
  - date: 2026-07-18
    reason: >-
      Redelivery policy inverted after M1-652's clarity-fail investigation
      found chokepoint dedup unworkable (40 of 43 OutboundMessage sites mint
      UUID.randomUUID(); the ambiguous-ack duplicate writes no ack row and is
      invisible to that seam). User decision 2026-07-18: /retry --digest posts
      again, per docs/spec/commands.md §"Cached digest message handle".
      Acceptance item flipped from "redelivery does not double-post" to
      "redelivery posts again and MAY duplicate"; the cross-regeneration
      correlationId-stability obligation dropped; M1-652 removed from
      blocked_by and re-sequenced AFTER this ticket as gap-filling redelivery.
      No sizing change — files_budget/files_scope/complexity/risk unchanged.
    snapshot:
      blocked_by: [M1-641, M1-652]
      acceptance_item_4: >-
        Redelivery does not double-post: because each category's correlationId
        is stable across regenerations of the same (slot, category), M1-652's
        chokepoint dedup suppresses an already-delivered category on a
        mid-sequence Provider restart or a /retry --digest. This ticket's
        obligation is minting a STABLE id — derived from (groupId, windowStart,
        categorySlug), never from a counter, timestamp or random value — not
        implementing the suppression.
        DigestDeliveryTest.redeliveryReusesTheSameCorrelationIdPerCategory passes.
  - date: 2026-07-18
    reason: clarity-fail rework
    snapshot:
      status: escalated
      files_budget: 14
      risk: medium
      clarity_check:
        date: 2026-07-18
        verdict: FAIL
        blockers:
          - "DigestRenderer.render()'s return contract must change to give DigestDelivery the ordered (category, renderedSection) list, breaking 4 pre-existing test files (DigestRendererTest, RecordingDigestRenderer, DigestWorkerTest, DigestWorkerClockTest) neither in files_scope nor authorized under test_plan."
          - "The Notes' partial-failure policy requires restart-durable redelivery dedup and claims the chokepoint already does correlationId idempotency. It does not: OutboundDelivery.java has no correlationId-keyed dedup, and the only dedup in the system is adapter-level, a SHOULD, bounded to 60 seconds (docs/design/06-messaging.md §6.3.5)."
          - "files_budget: 14 does not accommodate the fixes to blockers 1 and 2."
        warnings:
          - "The closing-affordance line's placement in the split-message world is never specified."
          - "risk: medium may need to become risk: high once blocker 2 is resolved with a migration."
      escalation_reason: clarity-fail
      resolution_evidence:
        - >-
          Blocker 1 resolved by DESIGN, not by widening scope: render() already
          builds per-section strings and joins them with "\n\n"
          (DigestRenderer.java:72-96), so renderSections() + render()-as-thin-join
          is byte-identical. Only RecordingDigestRenderer (a stub overriding
          render()) and DigestWorkerTest (new case) enter scope — 2 files, not 4.
        - >-
          Blocker 2 resolved by DELETING the guarantee, not narrowing it. The
          §6.3.5 adapter dedup the original Notes leaned on is UNIMPLEMENTED in
          all three v1 adapters (verified 2026-07-18). Narrowing "to the existing
          adapter guarantee" would have narrowed to nothing while still claiming
          dedup in acceptance. Separately, §6.3.5's 60-second window is shorter
          than OutboundDelivery's ~90 s worst-case retry wall clock (3 attempts x
          SimpleX's 30 s ACK_TIMEOUT), so the window is inadequate even as
          specified. Both are system-wide defects, filed separately rather than
          fixed inside a digest ticket.
        - >-
          Acceptance item 1's absolute "no cluster/item is split across two
          messages" was UNACHIEVABLE and is now bounded: SimpleXOutboundChunker
          chunks each category message independently at 4 000 bytes on line
          boundaries, which can land mid-cluster when a category is large.
escalations:
  - date: 2026-07-18
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      CLARITY VERDICT: FAIL
      FILES-BUDGET-PLAUSIBLE: FAIL
      TEST-CHANGES-AUTHORIZED: FAIL
      COMPLEXITY-RISK-CALIBRATED: WARN
      (full verdict: target/m1-tick-clarity-M1-642.txt)
redteam_findings:
  - date: 2026-07-20
    category: INFO-LEAK
    severity: medium
    promise: |
      security.md §Secrets handling / D37 "User content in exceptions":
      exception messages and stack traces emitted via the application
      logger MUST NOT contain user-authored prose (post bodies, etc.). The
      application provides a SafeLog utility that drops the exception
      message body, retains only the exception class name, and truncates
      the cause chain to class names; the original Throwable is never
      passed to the underlying SLF4J logger.
    gap: |
      CategoryRollupGenerator.java:149-150 catches RuntimeException e and
      passes the raw Throwable to the JBoss logger via
      LOG.warnf(e, "category roll-up LLM call failed; ..."), bypassing
      SafeLog. The guarded call (provider.generate(ModelTask.SUMMARIZER,
      ROLLUP_SYSTEM_PROMPT, userPrompt)) builds userPrompt by interpolating
      every category cluster's post title, body, and url, so an exception
      from the LLM transport, sanitizer, or translation boundary can embed
      attacker-controlled post content in the Throwable's message/cause
      chain, which then lands verbatim in the operator log stream the
      threat model commits is free of post bodies at every log level.
      Corroboration: multi-auditor run, corroborated 2/3 (claude rated low,
      codex rated medium); the cross-exam parser split them into two
      single-auditor clusters only on a line-number difference (118 vs
      149-152) — substantively the same gap. opencode returned CLEAN on the
      incorrect rationale that "all new LOG lines are fixed strings,"
      overlooking the warnf(e, ...) call that passes the Throwable. claude's
      low rationale: feature is default-off, leak target is the operator
      log stream (API-key shapes separately caught by the console redactor),
      exploitation needs a provider client whose exceptions embed body
      content, and the same pattern already exists outside the diff
      (SummaryProseGenerator.java:105-106/136-138) — but this diff
      introduces a NEW instance of the surface SafeLog exists to close.
    repro: |
      1. Operator enables infochat.digest.category-summary-enabled
         (default false).
      2. An attacker who controls or can inject into a subscribed feed
         publishes a post whose body survives Stage 1/2 and is crafted to
         make the roll-up LLM call fail with a content-bearing exception
         (e.g. content that drives the model into schema-/format-violating
         output the provider client rejects with the raw reply quoted, or
         that inflates the request past a provider limit whose client
         exception echoes request context).
      3. On the next digest slot for a group whose world includes the
         source, generateRollup catches the RuntimeException and logs the
         original Throwable; attacker-derived post text lands verbatim in
         the production log stream.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-07-20
    verdict: FINDINGS
    base: 5449d777457b338ad85371fae1b71a81f3cea33d
    head: working-tree
    verdict_file: docs/plan/m1/redteam-multi/M1-642-2026-07-20/cross-examination.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Multi-auditor run (claude, opencode, codex) at the /m1-tick run
      step-4 gate, ahead of review. One INFO-LEAK finding (SafeLog bypass
      at CategoryRollupGenerator.java:149-150) corroborated by
      claude + codex; opencode returned CLEAN on an oversight. Per-auditor
      verdicts and the cross-examination report live alongside this pointer
      under docs/plan/m1/redteam-multi/M1-642-2026-07-20/ (verdict-*.txt,
      cross-examination.md, diff.patch). The single out-of-model item
      (deliverSequenceToGroup empty-sequence counter-increment trap) is not
      adversary-reachable today but becomes a risk when M1-652 adds a
      filtering caller; see escalation trigger context.
  - date: 2026-07-20
    verdict: CLEAN
    base: 5449d777457b338ad85371fae1b71a81f3cea33d
    head: working-tree-r2
    verdict_file: docs/plan/m1/redteam-multi/M1-642-2026-07-20-r2/cross-examination.md
    out_of_model_count: 1
    note: |
      Multi-auditor re-audit (claude, opencode, codex) after the
      redteam-finding refine remediated the r1 INFO-LEAK gap by routing the
      CategoryRollupGenerator.generateRollup catch through SafeLog. 3/3 CLEAN;
      claude explicitly confirmed the SafeLog.warn(LOG, ..., e) routing at the
      catch site and noted the prior finding is closed. The same out-of-model
      item (deliverSequenceToGroup empty-sequence counter trap) was flagged by
      all three auditors this round (corroborated out-of-model) — already
      dispositioned by an acceptance criterion added to M1-652 (commit
      52939154). Evidence under docs/plan/m1/redteam-multi/M1-642-2026-07-20-r2/.
reviews:
  - round: 1
    date: 2026-07-20
    verdict: REWORK
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      assertion_adequacy: FAIL
      spec_conformance: PASS
    diff_stats:
      files: 56
      added: 7098
      removed: 58
    note: |
      Files/lines inflated by the two redteam-multi evidence dirs + STATUS +
      ticket (all lifecycle-exempt); 14 implementation files, within
      files_budget 15. REWORK is a single ASSERTION-ADEQUACY item: the roll-up
      integration is asserted only at the producer; nothing pins the
      renderSections consumer line `.ifPresent(rollup -> sb.append(...))` at
      DigestRenderer.java:121-122 (removing that append survives every test).
  - round: 2
    date: 2026-07-20
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      assertion_adequacy: PASS
      spec_conformance: PASS
    diff_stats:
      files: 56
      added: 7172
      removed: 58
    note: |
      Rework item 1 addressed (end-of-path rollup-prefix test in
      DigestRendererSectionsTest wires a stub CategoryRollupGenerator and
      asserts the prefix appears in the rendered section text, pinning
      DigestRenderer.java:121-122). Must-shrink convergent — growth vs round 1
      is the new test method + the r2 redteam-multi evidence dir, both citable.
      Verdict file: target/m1-tick-review-M1-642-r2.txt.
---

# M1-642: Per-category digest delivery + optional roll-up summaries

## Context

Phase 1 (M1-641) structures the digest into topic sections within a single
rendered string, which SimpleX then chunks at 4 000-byte boundaries — greedily,
on line boundaries, so a chunk break can land between two lines of the same
cluster (observed live: a cluster cut across two frames). Phase 2 delivers one
message per category so the break lands on a meaningful boundary, and adds an
optional per-category roll-up sentence — the "one line that names the day's
stories in this topic" from the original operator sketch. This is the
higher-risk half: it moves the digest from one atomic send to a sequence, which
touches the outbound delivery/retry chokepoint. Contract:
`docs/spec/commands.md` §Periodic group digests + `docs/spec/messaging.md`
§Failure handling.

## Acceptance

- Fork closed (arm (b), 2026-07-20): `renderSections()` output is the exact
  delivery bytes — affordance folded into the last section, roll-up prefixes
  inside their sections — so M1-652 can persist and replay sections
  verbatim. This ticket persists nothing.
- One message per category + one "Other" message when the Other bucket is
  non-empty, bounded at categories+1; the zero-posts fixed reply stays a
  single message. DigestDelivery never merges two categories into one message
  and never splits a category across two.
- Category messages go out SEQUENTIALLY in section order — never in
  parallel. Ordering is what makes affordance-on-last deterministic and
  preserves the digest's narrative order.
- One `summary_cache` row is still written per slot (the full rendered
  digest); the split happens at delivery, so `/retry --digest` regenerates
  and re-delivers the same way (D17 cache contract unchanged).
- `DigestRenderer.render()`'s signature and output are unchanged (at the
  roll-up flag's default); the structure is exposed via a new
  `renderSections()`. `DigestRendererTest` and `DigestWorkerClockTest`
  passing unmodified is the acceptance check.
- Each category message carries a per-(slot, category) correlationId
  (`categorySlug` = the tag string as-is, literal `other` for the null
  bucket). A TRANSIENT delivery failure retries only that message; a
  permanent failure on one category still delivers the others (partial
  success is visible, not all-or-nothing). Redelivery may duplicate — see
  Out-of-scope.
- One digest slot feeds the per-group permanent-failure counter with at most
  ONE aggregate outcome, via the new
  `OutboundDelivery.deliverSequenceToGroup` — a transport blip during the
  sequence must not soft-remove a healthy group (see Notes §Partial-failure).
- The closing affordance is appended once per digest, on the final category
  message only.
- Optional roll-up (`infochat.digest.category-summary-enabled`, default
  false): a 1–2 sentence LLM synthesis prefixes each category message —
  a headline-level roll-up across ALL the category's clusters (including
  past-cap ones — it names what "+N more" hides), not a restatement.
  Routed as `ModelTask.SUMMARIZER` (see Notes). One LLM request per
  category, sanitized and translated like cluster prose;
  a roll-up failure yields that category without a prefix, never a degraded
  or blocked digest. Config-toggleable so it can be A/B'd against plain
  headers.
- The roll-up step is the only new LLM stage; assignment stays deterministic.
  Degraded digests (D17) still deliver as the single headlines-only message.
- Spec (`commands.md` + `messaging.md`) and decision D63 record the
  per-category delivery + partial-failure policy, the explicit statement
  that redelivery may duplicate, and the slot-level counter attribution.
- `mvn -pl infochat-provider verify` is green.

## Out-of-scope

See `out_of_scope`. Categorization/headers/caps/affordance are M1-641's;
this ticket only changes DELIVERY (one message per category) and adds the
optional roll-up prepend. Do not add per-category cache rows or alter the
`/retry --digest` cache contract. Do not touch `/summary`. Do not add
delivery de-duplication at any layer. Do not touch the adapter chunker. The
retry ladder's mechanics are untouched — `deliverSequenceToGroup` changes
only the counter attribution granularity (see Notes). No new bundle keys:
the roll-up adds no deterministic user-visible string (the prefix is LLM
prose; the prompt template is a Java constant, the `SummaryProseGenerator`
precedent), so `BundleKeys.java` and the bundle files left files_scope
(revision 2026-07-20).

## Notes

**Delivery seam.** `DigestWorker.executeSlot` currently renders one string and
calls `outboundDelivery.deliverToGroup(adapter, msg, groupId)` once. Add
`DigestRenderer.renderSections()` returning the ordered
`List<RenderedSection>` (category slug + rendered text) that `render()` already
builds internally, and keep `render()` as a thin join over it so its output is
byte-identical. `DigestDelivery` consumes the section list and sends one
`OutboundMessage` per category, sequentially in section order, through the
new chokepoint entry point `OutboundDelivery.deliverSequenceToGroup` (one
aggregate counter outcome per call — see the crux below) with correlationId
`digest-<groupId>-<windowStart>-<categorySlug>`; `categorySlug` is the
category tag as-is, the literal `other` for the null bucket, and M1-652's
delivery-state key inherits this mapping. One render pass and one
`generate()` pass, as before: the worker caches the join of the section
list it already holds (the same `"\n\n"` join `render()` performs) and
NEVER calls `render()` after `renderSections()` — a second `render()`
call would re-run the whole LLM pass, and the cached prose would silently
diverge from the delivered bytes (the hand-wired renderer stubs cannot
catch that). The worker also keeps the `findAdapter` lookup and
dereferences `DigestDelivery` only after the adapter-null early return:
`DigestWorkerClockTest` hand-wires the worker with the delivery
collaborators unset and relies on the empty-registry return right after
the cache upsert — and it must pass unmodified. The single-message paths
(degraded, zero posts) keep using `deliverToGroup` unchanged.

**Partial-failure policy (the crux).** Each category message retries
independently through the chokepoint's existing per-message TRANSIENT-retry /
PERMANENT-abort ladder; the digest is "delivered" if ≥1 category lands.

The per-group permanent-failure counter, however, must receive at most ONE
aggregate outcome per slot — that is what `deliverSequenceToGroup` exists
for. Naive per-category `deliverToGroup` calls would break the counter's own
documented invariant ("always > 1, so a single misclassified failure cannot
trigger it"): the threshold is 3 in every profile except pi, it was
calibrated for one message per slot, `deliverToGroup`'s only callers are
digests and approve/reject announcements (group chat replies never reset the
count), and SimpleX classifies a send on a closed or not-yet-started
WebSocket as an IMMEDIATE PERMANENT (`SimpleXWebSocketClient.sendCommand`;
`onClose` fails all pending futures the same way). One routine simplex-chat
subprocess restart during the sequential category loop therefore yields ≥3
instant PERMANENTs in milliseconds — soft-removing a healthy group
(`BOT_REMOVED` audit, scheduler stops for it) with no admin notification,
where the same blip today costs a single increment and removal needs 3
consecutive failed slots. Inside `deliverSequenceToGroup` the per-message
sends run unattributed; after the loop one aggregate is applied: any success
→ counter reset, all-permanent → one increment. Per-message classification,
backoff, and the threshold value are untouched.

No modification of `execute(...)` is needed for any of this:
`execute(channel, null, msg, op)` is already the unattributed ladder (it
is what `deliver()` uses for DMs — every counter mutation is gated on
`groupId != null`), so the sequence method is a loop of null-groupId
`execute` calls plus one aggregate applied through the existing helpers
(`consecutivePermanentByGroup.remove` / `onPermanentGroupFailure`). The
ladder also has a THIRD outcome the binary aggregate must not swallow: an
interrupted back-off aborts with NO counter attribution (`backOff`
re-interrupts and returns false). The sequence mirrors it — an interrupt
stops the remaining sends and applies no aggregate outcome, so a Provider
shutdown mid-digest never counts toward soft-removal.

Redelivery re-posts everything and may duplicate — see below.

This ticket originally asserted the chokepoint "already does its
idempotency/dedup work per message" — false when filed, and the prerequisite
raised to fix it (M1-652, correlationId-keyed dedup at the chokepoint) was
then found not to work either. Verified 2026-07-18: 40 of 43
`OutboundMessage` construction sites mint `UUID.randomUUID()`, so a
chokepoint dedup keyed on correlationId is inert almost everywhere; and the
one duplicate mode that reaches a user — adapter transmits, ack times out,
ladder re-sends — writes no ack row and so is not suppressed by it. The
chokepoint cannot observe whether an ambiguous transmit landed; only the
adapter can. That guarantee was therefore dropped rather than moved, and
M1-652 was repurposed (see below). D63 records the v1 position.

**What actually goes wrong, and who owns it.** On a mid-sequence Provider
death the scheduler does NOT re-run the slot — a `summary_cache` row exists,
so it is not "missed" (`DigestScheduler` is skip-not-catch-up). The
undelivered categories are simply lost, with no alarm. The real risk here is
silent under-delivery, not duplication. The remedy is gap-filling redelivery
— on `/retry --digest`, send only the categories that never landed — which
needs persisted per-(slot, category) delivery state and is **M1-652**, now
sequenced AFTER this ticket because the per-category structure has to exist
before there are gaps to fill. (Resolved 2026-07-20 to arm (b): M1-652 also
persists the section list at render time and REPLAYS it on retry, so the
gap-fill is byte-faithful and LLM-free.)

**This ticket owes no id stability.** Cross-regeneration correlationId
stability was the previous design's requirement and is now explicitly NOT
required: nothing dedups on the id, `/retry --digest` must post again per
`docs/spec/commands.md`, and M1-652 will key its delivery state on the
(groupId, windowStart, categorySlug) tuple rather than on the correlationId.

**Cluster splitting is reduced, not eliminated.** `SimpleXOutboundChunker`
chunks at 4 000 UTF-8 bytes on greedy line boundaries and is not caller-
controllable (no cap parameter, no split hints; `MAX_BYTES` is a compile-time
constant). Each category message is chunked independently, so a category whose
rendered text exceeds 4 000 bytes — reachable at the default
`infochat.digest.category-item-cap` of 12 clusters — can still break inside a
cluster. Do not write an acceptance criterion promising otherwise.

**Roll-up prompt shape.** `CategoryRollupGenerator` gets ALL clusters
assigned to the category — including those past the per-section item cap:
the roll-up is the one line that names what the "+N more" line hides, and
headline-level input keeps the prompt small even for a capped category —
and emits ONE synthesis sentence naming the themes ("Three supply-chain
attacks, an OpenSSL DoS, and a WordPress RCE"), NOT a re-list. Reuse the
group language. Routing is `ModelTask.SUMMARIZER` with a distinct
system-prompt constant (the `SummaryProseGenerator` precedent): a new
`ModelTask` is unreachable in this ticket — the enum is a closed set in
`infochat-llm-adapter` whose widening requires an `llm.md` spec amendment
(per its own javadoc), per-profile routing config (D56: an unrouted task
refuses boot on `remote-llm`), and the `SwitchLlmWiringTest` positional
update, all outside files_scope and outside `mvn -pl infochat-provider
verify`. Keep it behind the default-off flag so Phase 2 can ship the
delivery change first and enable roll-ups after evaluation.

**Roll-up safety and containment.** Roll-up prose is LLM output: sanitize
with `LlmOutputSanitizer`, then `TranslationPipeline.run` — the same
treatment cluster prose gets in `DigestRenderer.render()`; security.md §LLM
output sanitizer is unconditional. Generate roll-ups inside the slot-window
render budget (the flag-gated step lives in the `renderSections()` pass,
inside the worker's existing windowEnd-bounded future), never at delivery
time — delivery time sits outside every timeout budget in the pipeline. A
per-category roll-up failure is contained: that category ships without a
prefix; only a whole-render-budget overrun degrades the digest, exactly as
slow cluster prose does today (D17). One LLM request per category — the
digest already makes one request per cluster
(`SummaryProseGenerator.generate`), so the added volume is proportionally
small.

**Message-count bound.** Phase 1's sweep shows ≤6 categories + Other on
realistic windows, so ≤7 messages/digest — acceptable notification volume.
If a future corpus blows past that, the Phase-1 category self-bounding (no
cap needed) still holds; revisit only if a real deployment shows >~8
categories.

**Blocked on M1-641** (merged 2026-07-18, `52de43c3`) — consumes its
(category, clusters) output. The M1-652 fork was closed 2026-07-20 (arm (b),
first acceptance item); nothing else gates `/m1-tick start`.

## Round 1 rework

Reviewer round-1 REWORK (ASSERTION-ADEQUACY-CHECK FAIL; full verdict at
`target/m1-tick-review-M1-642-r1.txt`). One item:

1. **Add an end-of-path test for the roll-up integration.** The roll-up
   prefix is asserted only at the producer (`CategoryRollupGeneratorTest`
   checks `generateRollup`'s `Optional<String>` return); no test pins the
   consumer line
   `categoryRollupGenerator.generateRollup(...).ifPresent(rollup -> sb.append("\n\n").append(rollup))`
   at `DigestRenderer.java:121-122`. Removing that append survives every
   test — every `renderSections` test runs with `categorySummaryEnabled` at
   its default `false`, so the `ifPresent` is a no-op they cannot constrain.
   Fix: in `DigestRendererSectionsTest`, wire `renderer.categoryRollupGenerator`
   to a stub whose `generateRollup` returns `Optional.of("TEST-ROLLUP-PREFIX")`,
   render sections for a post set with at least one qualifying category, and
   assert the prefix appears inside the rendered section text.
