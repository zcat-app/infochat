---
id: M1-767
title: "Aggregate system LLM budget for the unmetered scheduled digest"
status: done
created: 2026-08-04
last_updated: 2026-08-05
blocked_by: []
files_budget: 17
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE PER-USER AND PER-GROUP CAPS. `LlmRateCap` (M1-183) and the D47
    per-group sub-bucket (M1-222) both ship and both work. This ticket adds
    the missing THIRD meter above them; it does not re-tune, re-scope or
    refactor either. Round-2/3/4/5 red-team disposition (2026-08-04): the
    user-initiated route gains ONE new gate ahead of both caps — the
    PRE-CHARGE refusal in `RetryCommandHandler.handleDigestRetry` (`the
    DigestRetryService.retryLeg` probe, then `SystemLlmBudget.canStartRender`
    only on the FALLBACK leg, since that call emits the breach signal)
    that refuses the fallback re-run leg — the retry's only LLM spend —
    when the system window is at/over the ceiling, drawing NO per-user
    token, no D47 draw and no cooldown, while the zero-LLM replay leg is
    never gated in steady state (a stale probe can refuse it, but refuses
    it free, so re-issuing succeeds). The caps' own mechanics stay
    untouched.
  - >-
    THE DIGEST RENDER'S SHAPE. Cluster selection, category count
    (`DigestCategorizer.maxCategories`), `categoryItemCap`, prose
    generation and the roll-up structure are not changed. This ticket adds
    a meter around the generative calls; it does not reduce how many the
    render makes. Making the render cheaper is a different ticket with a
    different risk profile.
  - >-
    M1-763's CANCELLATION PATH and M1-764's transport-interrupt test. The
    slot-window timeout and the interrupt-driven no-op spend are the
    TEMPORAL bound and stay exactly as they are. This ticket supplies the
    VOLUME bound they were never meant to be.
  - >-
    ASSET COMMANDS AND THE INGEST PIPELINE. `price_snapshot` calls are not
    LLM calls; Stage 1 / Stage 2 / tagging / embedding have their own
    per-task concurrency semaphores and are metered by feed volume, not by
    a user or a schedule. Widening this budget to cover ingest would make
    a collector backlog able to starve the provider — an availability
    trade the ticket must not make.
  - >-
    ANY CHANGE TO `docs/spec/security.md` §Rate limiting's WORDING. The
    sentence already promises this control; the ticket implements the
    promise rather than amending it. If implementation reveals the
    promised semantics are wrong, that is a `spec-amend` escalation, not
    an inline edit.
  - >-
    EXACT PER-CALL ACCOUNTING — deferred to M1-769 (red-team refine
    2026-08-04). Counting the render's provider calls exactly requires the
    draw to sit where the call is made, but all three generative helpers
    (`SummaryProseGenerator`, `CategoryRollupGenerator` via
    `renderShortBody`, `TranslationPipeline`/`LlmTranslationProvider`) are
    SHARED with the user-initiated routes (`/summary`, `/retry`, chat,
    saves). `renderSections` is reached by BOTH the scheduled route
    (`DigestScheduler` → `DigestWorker.executeSlot`) AND `/retry --digest`
    (`DigestRetryService.fallbackRerun` → `DigestWorker.execute` →
    `executeSlot`), so the draws sit at that altitude NOT because the entry
    point is single-route but because moving them down to the call sites
    would meter the shared user-initiated routes into the system budget —
    breaking the split `LlmRateCap` (M1-183) and the D47 per-group
    sub-bucket exist to maintain (round-2 red-team finding 2: the earlier
    "only scheduled-route-only entry point" claim was FALSE and has been
    corrected here, in `SystemLlmBudget`'s javadoc, in M1-769 and in
    `application.properties`). Making the count exact without losing that
    scope needs a render-scoped call sink threaded through those shared
    classes — a different blast radius and a different risk profile. THIS
    ticket meters at `renderSections` and DOCUMENTS its named
    approximation legs; it does not chase exactness with better proxies.
  - >-
    THE INTRA-RENDER BOUND AND PER-GROUP FAIRNESS — deferred to M1-769
    (red-team refine 2026-08-04). The admission gate is consulted once per
    render, so an admitted render's spend is bounded only by the M1-763
    slot window; a per-call bound, a per-group share and the deterministic
    `DigestScheduler.staggerOffset` starvation all belong with the exact
    accounting that makes a per-call bound implementable. Whether
    `/retry --digest` draws the deployment-wide pool is NOT deferred: the
    round-2/3/4/5 red-team disposition decides it binds on its FALLBACK
    re-run (refused PRE-CHARGE in `RetryCommandHandler`, so there is
    nothing to refund; the replay leg is never gated in steady state);
    M1-769 makes the draw exact for that bound route.
acceptance:
  - >-
    A system-wide LLM call meter exists and is drawn by the SCHEDULED
    digest route (`DigestScheduler` -> `DigestWorker.executeSlot` ->
    `DigestRenderer`), which today draws no bucket at all. The unit is
    LLM CALLS over a rolling window, not tokens or currency — the codebase
    has no token accounting and inventing one here would balloon the
    ticket. Window and ceiling are operator-configurable properties with
    defaults derived from the per-slot render cost measured in acceptance
    item 4, not guessed.
  - >-
    THE METER IS AN APPROXIMATION AND THE CODE SAYS SO (red-team refine
    2026-08-04; corrected 2026-08-04 round-2). The draw sites live in
    `renderSections` — reached by BOTH the scheduled route and
    `/retry --digest` — and therefore count a PROXY for provider calls,
    not the calls themselves; the draws sit at that altitude because every
    generative helper below `renderSections` is shared with chat, saves,
    `/summary` and `/retry`, not because the entry point is
    single-route. Every comment and javadoc on the budget must state the
    named divergence legs rather than claim a precision the meter does not
    have. At minimum: OVER-count when the render makes zero HTTP calls
    (unresolvable SUMMARIZER provider — `SummaryProseGenerator` returns
    every cluster degraded with no call; circuit breaker OPEN — each
    `provider.generate` short-circuits "without an HTTP attempt";
    `generateRollup`'s empty-prompt skip), OVER-count when M1-763's
    slot-window cancellation discards a render whose LOOP still runs to
    completion — every draw site still fires for a render nobody received
    (round-2 red-team finding 1, the largest leg: up to ~211 phantom
    calls, ~7% of the ceiling, driven by post volume and firing while the
    endpoint is UP but slow), and UNDER-count on the roll-up's second
    provider-reaching leg (`CategoryRollupGenerator` calls
    `translationPipeline.run` after `provider.generate`, so a non-`en`
    roll-up spends 2 and draws 1) and on a translation-cache eviction
    between `appendClusterProse`'s pre-call probe and the pipeline read.
    The existing comment "the generator makes one provider call per
    invocation" is FALSE for a non-`en` scope and must go. The default
    ceiling is 100x the measured cost of ONE render — which is exactly the
    documented daily capacity (~50 full-mode groups at two slots/day), so
    at that capacity there is NO headroom left to absorb a ~2x accounting
    error; the docs must say that plainly and point operators at scaling
    the ceiling to deployment size (round-2 out-of-model note) rather than
    claiming headroom the sizing does not provide.
  - >-
    A USER-INITIATED RETRY NEVER BURNS A TOKEN ON A DOOMED RENDER
    (round-2 red-team refine 2026-08-04; mechanism corrected rounds 3-6).
    `/retry --digest` binds the deployment-wide pool — a retry re-run is
    genuinely digest cost, so it draws and is gated like the scheduled
    route — but ONLY on its FALLBACK re-run leg. The refusal is decided
    PRE-CHARGE in `RetryCommandHandler.handleDigestRetry`: the
    `DigestRetryService.retryLeg` probe (REPLAY | FALLBACK | NO_PRIOR,
    computed with the same reads `retryDigest` performs) runs FIRST, and
    `SystemLlmBudget.canStartRender()` is consulted ONLY on the FALLBACK
    leg. That order is load-bearing, not incidental: `canStartRender()`
    is not a pure predicate — its false branch emits the breach signal
    through `ThrottledAdminNotifier.notifyOnce`, which UPSERTs on every
    call — so consulting it ahead of the probe alarms "scheduled digest
    degraded" on the un-gated REPLAY leg, for a digest that was not
    degraded (round-6 redteam rework; pinned by
    `RetryDigestCommandTest.retryDigest_replayLegProceedsWithoutConsultingTheSystemBudget`,
    which asserts `canStartRenderCalls == 0`). When the leg is FALLBACK
    and the window is at/over the ceiling, the handler replies with the
    distinct bundle key and NO charge of any kind happens: no per-user
    token, no D47 draw, no cooldown stamp, `DigestRetryService` untouched
    (rounds 3 and 4: the post-charge gate-and-refund shape was abandoned
    because it burned the D47 draw and added a second refusal path). The
    REPLAY leg — persisted bytes re-delivered with zero provider calls —
    is NEVER gated in steady state (round-3 redteam finding, fixed). The
    probe is an ESTIMATE: a concurrent scheduled render persisting
    sections can flip the would-be leg FALLBACK→REPLAY between the
    probe's reads and the refusal, so a refusal can land on a retry that
    would by then have made no LLM call. What bounds that residual is NOT
    the width of the interval — under this order it spans
    `canStartRender()`'s own `notifyOnce` JDBC UPSERT — but the refusal's
    ZERO COST: nothing drawn, nothing stamped, so the re-issued retry
    probes REPLAY and proceeds (round-5 finding, documented in
    `retryLeg`'s javadoc). The
    worker's `executeSlot` admission gate remains the authoritative
    check on both routes: if the window fills between the pre-charge
    check and that gate (an interval spanning `retryDigest`'s cache and
    metadata reads and the worker's post collection — NOT millisecond-
    scale, round-4 correction), the re-run DEGRADES like the scheduled
    route, reports SUCCESS, and the already-drawn tokens follow the
    pre-existing conservative non-render-result convention (documented in
    `RetryCommandHandler`); the refusal path itself draws nothing, so it
    cannot burn a token. A refused re-run records nothing against the
    window, so admission recovers as it drains; the attempt still lands
    an audit row (hammering stays audit-visible, matching the other
    gates).
  - >-
    THE BREACH SIGNAL MUST NOT HOLD THE BUDGET MONITOR ACROSS ITS DB WRITE
    (red-team refine 2026-08-04). `ThrottledAdminNotifier.notifyOnce`
    opens a JDBC connection and UPSERTs `admin_notification_state` on
    EVERY call — its coalescing suppresses the emission, not the
    round-trip. Emitting it inside `canStartRender()`'s `synchronized`
    block makes every concurrent render's draw queue behind a DB write,
    on the small (12-16) provider pool, exactly under the overload the
    control exists to handle. The notify happens outside the monitor.
  - >-
    Breaching the budget DEGRADES the digest rather than failing it: the
    render falls back to its existing non-generative path (the same
    degraded renderer the slot-window timeout already uses) and the digest
    still goes out. A breach must never drop a scheduled digest silently,
    and must never throw into the scheduler.
  - >-
    A breach emits an operator signal exactly once per window, through the
    existing `ThrottledAdminNotifier` — not one per suppressed call. An
    unbounded-spend control whose alarm is itself unbounded is the same
    bug one layer up.
  - >-
    MEASURE BEFORE CHOOSING THE DEFAULT. Record, in the ticket body, the
    generative call count of one `digest_mode=full` render at a realistic
    post volume, counted at the two live call sites
    (`DigestRenderer.appendClusterProse` /
    `summaryProseGenerator.generate`, and `CategoryRollupGenerator`). The
    default ceiling is a stated multiple of that number. A budget whose
    default trips on normal operation will be raised until it is
    meaningless.
  - >-
    A test proves the meter is drawn on the SCHEDULED route specifically.
    This is the leg with no other meter, and `DigestWorkerTest` asserts on
    a stub provider, so the test must assert on the budget's own counter,
    not on the provider.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - >-
      `RetryDigestCommandTest`: with the leg probe reporting FALLBACK and
      the system window at/over its ceiling, `/retry --digest` is refused
      with the distinct bundle reply BEFORE any charge — `retryDigest`
      never called, per-user token untouched, D47 draw untouched
      (rounds 2-4 red-team refine: the pre-charge gate). With the probe
      reporting REPLAY, the retry proceeds despite an exhausted budget
      (round-3 finding regression).
    - >-
      `DigestRetryServiceTest`: the `retryLeg` probe reports REPLAY for a
      live row with persisted sections, FALLBACK for a section-less slot,
      and NO_PRIOR for a missing row — the handler's pre-charge gate input.
  preserves:
    - >-
      The user-initiated `/retry --digest` route keeps drawing the
      per-user token and the D47 per-group sub-bucket in the existing
      order, with the existing refund-on-group-reject behaviour
      (`RetryCommandHandler`, `DigestRetryService`).
    - >-
      `DigestWorkerTest.execute_renderOverrunningWindow_stopsSpendingProviderCalls`
      (M1-763) and
      `HttpProviderSharedPipelineTest.interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed`
      (M1-764) — the temporal bound is unaffected by adding a volume bound.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
decision_refs:
  - D47
reviews:
  - round: 1
    date: 2026-08-05
    verdict: REWORK
    rework_items: 1
    diff_stats:
      files: 38
      added: 3802
      removed: 16
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PARTIAL
      spec_conformance: PASS
      assertion_adequacy: PASS
    verdict_file: target/m1-tick-review-M1-767-r1.txt
    note: |
      Single rework item, doc-only: acceptance item 3 still specified the
      ABANDONED budget-first ordering ("canStartRender() runs FIRST ... the
      retryLeg probe runs only when the window is at/over the ceiling"),
      the reverse of what shipped, and carried the stale "sub-millisecond
      race" characterization. Corrected to probe-first with the
      load-bearing reason stated, and the residual re-bounded on the
      refusal's zero cost rather than the interval's width. No
      production-code change required — the reviewer confirmed the code is
      the correct shape.
  - round: 2
    date: 2026-08-05
    verdict: APPROVE
    rework_items: 0
    diff_stats:
      files: 38
      added: 3860
      removed: 17
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
      assertion_adequacy: PASS
    verdict_file: target/m1-tick-review-M1-767-r2.txt
    note: |
      Round-1's single doc-only rework item (acceptance item 3 describing
      the abandoned budget-first ordering) addressed; no production code
      changed between rounds. mvn verify reused per the M1-272
      unchanged-testable-tree rule, user-gated.
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-04
    category: DOS
    severity: medium
    auditor: claude
    promise: |
      security.md §Rate limiting — "**Per-group LLM rate (D47)** — a separate
      sub-bucket per approved group bounding LLM-triggering operations (chat
      replies + on-demand `/summary` + `/retry` re-rolls) across all group
      members. The per-user LLM cap fires first; the per-group cap is the
      backstop for groups with many active members. ... Periodic digests do NOT
      count against user-initiated per-group LLM budget (they are
      system-initiated; the aggregate system LLM budget is the backstop for
      digest cost)." Paired with §Authorization model — "**Group admin** — one
      group only." The whole §Rate limiting section is built on per-consumer
      bounds so one sender/one group cannot consume the shared capacity of
      others.
    gap: |
      The new control is a single deployment-wide counter with no per-group
      share, no per-render bound, and an admission-only gate.
      `SystemLlmBudget.callTimestamps` is consulted exactly once per render, in
      `DigestWorker.executeSlot` (`|| !systemLlmBudget.canStartRender()`). Once
      admitted, the render's spend is bounded only by wall clock:
      `DigestRenderer.renderSections` draws `shownClusters.size()` — one
      summarizer call per cluster over ALL clusters of ALL surviving sections,
      and FULL mode deliberately lifts the per-section item cap. The cluster
      count is driven by post volume in the group's D59 world, which any
      non-banned user can inflate (`/add-source` + subscription). Nothing
      reserves budget for later groups, and `DigestScheduler.staggerOffset` is a
      deterministic `groupId hash % windowWidthMinutes`, so the SAME late-firing
      groups lose every day. The gate is also check-then-act across concurrent
      slot dispatches. Finally the user-initiated `/retry --digest` route reaches
      the very same render and the same global pool, so a GROUP-admin-tier actor
      draws down a deployment-wide resource. The shipped default has zero
      headroom by its own arithmetic: ceiling 3000 / 30 calls-per-render =
      exactly 50 groups x 2 slots.
    repro: |
      1) Attacker is a registered, non-probation user and group admin of one
      approved group. 2) `/add-source <feed-they-control> --tags ai` and
      subscribe the group to it. 3) Publish a few hundred posts into that feed
      between digest slots; they cluster into hundreds of clusters. 4) At the
      group's slot the FULL-mode render is admitted by a single
      `canStartRender()` and then issues one summarizer call per cluster plus
      one translator call per cluster (non-`en` scope) until the 30-minute slot
      window cancels it. 5) The 3000-call PT24H window is now at/over the
      ceiling. Every OTHER group whose slot fires later that day is refused and
      receives the headlines-only degraded digest for the remainder of the
      window, including groups the attacker has no membership in.
    suggested_fix_class: rate-limit
  - date: 2026-08-04
    category: DOS
    severity: medium
    auditor: claude
    promise: |
      security.md §Rate limiting — "Periodic digests do NOT count against
      user-initiated per-group LLM budget (they are system-initiated; the
      aggregate system LLM budget is the backstop for digest cost)." A backstop
      on *cost* has to count the calls that are actually made; a ceiling that
      meters a strict subset of the render's generative calls is not the bound
      the spec names.
    gap: |
      The meter systematically under-counts the render's real provider calls on
      the DEFAULT digest mode. `DigestRenderer.renderSections` records exactly
      one call per roll-up call site (`recordCalls(1)`, comment: "the generator
      makes one provider call per invocation"), but
      `CategoryRollupGenerator.generateRollup` makes TWO provider-reaching
      generative calls on a non-`en` scope: `provider.generate(ModelTask.SUMMARIZER,
      ...)` and then `translationPipeline.run(sanitized, langCode)`, the latter a
      `ModelTask.TRANSLATOR` call on a cold cache. That second leg is uncounted
      and has no budget of its own. The diff meters `appendClusterProse`'s
      translator call but not the roll-up's. `brief`/`normal` are the modes that
      render roll-ups (`normal` is the SQL-deserialization fallback), and
      `max-categories=8`, so a single default-mode render in a non-English group
      can spend up to ~2x what it records.
    repro: |
      A deployment of non-English (`/lang cs`) groups on the default `normal`
      digest mode. Each slot render makes 8 SUMMARIZER roll-up calls + up to 8
      TRANSLATOR roll-up calls + up to 5 headline translations, and records 8.
      Real generative spend runs ~2.6x the recorded figure, so the operator-set
      ceiling of 3000 calls/24h is crossed in reality at ~1150 recorded calls
      and `canStartRender()` keeps admitting renders long past the point the
      operator believes spending stops.
    suggested_fix_class: rate-limit
  - date: 2026-08-04
    category: DOS
    severity: medium
    auditor: claude
    promise: |
      security.md §Failure handling — "Fail-fast changes WHEN a doomed call
      fails, never WHERE it goes or how the task degrades: the short-circuit
      surfaces as the same failure the consumer already handles" and "A complete
      LLM outage degrades quality, not safety." §Trust boundaries item 9 puts
      the hostile/unreachable endpoint in scope.
    gap: |
      The draws record calls that were never made on exactly the failure paths
      where zero HTTP requests are issued, so an outage burns the 24-hour
      budget. `DigestRenderer` calls `summaryProseGenerator.generate(...)` and
      then unconditionally `recordCalls(shownClusters.size())`. Inside
      `SummaryProseGenerator.generate` an unresolvable provider returns every
      cluster degraded with ZERO calls, and with the circuit breaker OPEN each
      `provider.generate` short-circuits "without an HTTP attempt" into the
      per-cluster catch — again zero real calls. The roll-up draw has the same
      shape: it fires after `generateRollup`, which returns `Optional.empty()`
      from its `catch (RuntimeException)` with no call made. The budget
      therefore fills at full nominal rate during an outage, and once the window
      reaches the ceiling every subsequent slot is refused for the rest of the
      PT24H window. The breaker's short-circuit thus acquires a lasting side
      effect on how a LATER, healthy task degrades.
    repro: |
      1) The configured LLM endpoint becomes unreachable. 2) The breaker trips
      OPEN after 3 consecutive transport failures. 3) The scheduled digest slots
      for 50 groups fire; each render makes zero provider calls yet records ~30,
      so ~1500 phantom calls land per slot round; two rounds exhaust the 3000
      ceiling. 4) The endpoint recovers minutes later and the breaker closes.
      5) Every group's digest nevertheless stays headlines-only degraded until
      the 24-hour window drains, and the admin receives "scheduled digest
      degraded: system LLM call budget exhausted" for spend that never happened.
    suggested_fix_class: other
  - date: 2026-08-04
    category: DOS
    severity: low
    auditor: claude
    promise: |
      security.md §Failure handling — "**Admin notifications** are coalesced per
      `(channel, error_class)` for a short window so an outage produces one
      summary message, not 200 individual alerts." The coalescing exists so a
      breach condition does not turn into per-event work.
    gap: |
      `SystemLlmBudget.canStartRender()` is `synchronized` and calls
      `adminNotifier.notifyOnce(BREACH_KEY, ...)` INSIDE the monitor, on EVERY
      refusal. `ThrottledAdminNotifier.notifyOnce` opens a JDBC connection and
      runs an UPSERT against `admin_notification_state` — the coalescing
      suppresses the *emission*, not the round-trip. The class javadoc's claim
      ("never one per suppressed render") is true of the message and false of
      the work. Because `recordCalls` and `callsInWindow` share the same
      monitor, every concurrent render's draw blocks behind that DB round-trip.
      The provider's JDBC pool is small (12-16), so the contention appears
      precisely under the overload the control is meant to handle.
    repro: |
      Drive the budget to the ceiling. From then on, every scheduled slot
      dispatch and every `/retry --digest` calls `canStartRender()`, each doing
      a blocking DB write while holding the single global budget monitor; the
      concurrent renders admitted just before exhaustion stall on `recordCalls`
      for the duration of each of those writes. The breach path adds per-refusal
      DB and lock work rather than shedding it.
    suggested_fix_class: other
  - date: 2026-08-04
    category: DOS
    severity: medium
    auditor: kimi
    promise: |
      docs/spec/security.md §Rate limiting ("Per-group LLM rate (D47)"):
      "Periodic digests do NOT count against user-initiated per-group LLM budget
      (they are system-initiated; the aggregate system LLM budget is the
      backstop for digest cost)." The threat model also establishes that feed
      publishers are untrusted, and the DOS category covers "unbounded LLM
      calls".
    gap: |
      The "backstop" is gate-then-draw with no intra-render bound.
      `DigestWorker.executeSlot` consults `SystemLlmBudget.canStartRender()`
      once, before the render starts. The render's calls are then drawn AFTER
      they complete: `recordCalls(shownClusters.size())` after
      `summaryProseGenerator.generate` returns, `recordCalls(leadProse.size())`
      after the lead generate, `recordCalls(1)` after `generateRollup`, and a
      post-hoc per-cluster draw in `appendClusterProse`. Nothing caps the calls a
      single ADMITTED render makes: FULL mode lifts the per-section cluster cap,
      so per-render summarizer + translator call count is driven by inter-slot
      post volume — which is attacker-controlled feed content.
      `SystemLlmBudget`'s own class comment concedes "A render admitted under the
      ceiling draws its actual calls as it runs and may push the window over";
      the overshoot multiple is unbounded, not a small slack.
    repro: |
      Adversary controls (or injects into) a feed a digest group subscribes to.
      Between two digest slots they publish tens of thousands of items. The next
      scheduled render passes `canStartRender()` (window near zero), clusters the
      flood, and makes one summarizer provider call plus one non-en translator
      call per cluster — e.g. 50,000 calls against a 3000-call ceiling — all
      spent before the first `recordCalls` lands. The bound only engages on the
      NEXT render.
    suggested_fix_class: rate-limit
  - date: 2026-08-04
    category: DOS
    severity: low
    auditor: kimi
    promise: |
      Same §Rate limiting commitment — the budget is meaningful only if its
      accounting tracks actual provider calls; a meter that undercounts is a
      weaker backstop than the one the spec names.
    gap: |
      Four undercount paths in the draw sites: (a) `recordCalls` is placed after
      `summaryProseGenerator.generate` returns, so an exception mid-generate
      (schema-violating reply after retry, transport failure) drops the count of
      the calls already made — no finally-block draw; (b) §Failure handling
      mandates "retry once" on schema-violating LLM output, so the
      summarizer/roll-up can spend 2 provider calls per drawn unit while the
      budget draws 1; (c) the `appendClusterProse` translation draw is decided by
      a PRE-call probe, `translationCache.get(sanitized, langCode)` — the diff's
      own comment acknowledges the probe-vs-pipeline race, but only the
      safe-direction half: the cache is capacity-bounded, so an eviction between
      probe and `translationPipeline.run` yields a provider call that was never
      drawn; (d) the lead draw uses `leadProse.size()` (the RETURNED list), so
      generator failure containment that drops a cluster after spending its call
      undercounts.
    repro: |
      A hostile or flaky endpoint (in scope per §Trust boundaries item 9)
      returns schema-violating replies: every summarizer cluster costs 2
      provider calls (initial + mandated retry) and draws 1, silently halving
      the effective ceiling; sustained failure modes that throw mid-generate
      draw 0 for partial spend. The backstop trips later than its accounting
      claims.
    suggested_fix_class: rate-limit
  - date: 2026-08-04
    category: DOS
    severity: medium
    auditor: threat-actor (round 2)
    promise: |
      security.md §Rate limiting — "Periodic digests do NOT count against
      user-initiated per-group LLM budget (they are system-initiated; the
      aggregate system LLM budget is the backstop for digest cost)." Paired
      with §Failure handling — "A complete LLM outage degrades quality, not
      safety" — and §Trust boundaries item 9, which puts the endpoint in
      scope: "whatever answers on the configured base-url picks every field
      of the reply, so a hostile or compromised endpoint is in scope here
      for the same reason a hostile feed is on the ingest side."
    gap: |
      The documented divergence enumeration is INCOMPLETE, and the omitted
      leg is the largest one. The three named OVER-count legs (unresolvable
      SUMMARIZER router; breaker-OPEN short-circuit; the empty-prompt
      skip) and the two named UNDER-count legs (the roll-up's second
      provider-reaching leg; the cache-eviction race) are all accurate, and
      the prior round's monitor-holding breach signal is structurally
      fixed. What is NOT named is the M1-763 slot-window cancellation:
      on TimeoutException the worker degrades the digest and DISCARDS the
      render's result, but the render thread keeps running — each remaining
      generative call fails fast without opening a socket, yet the render
      LOOP still runs to completion. So every draw site still fires on a
      cancelled render: up to ~200 summarizer draws + up to 3 lead draws +
      up to 8 roll-up draws — roughly 7% of the shipped 3000-call ceiling
      charged for ONE render whose output nobody received. Larger than any
      named leg, sized by post volume rather than nominal render cost, and
      the ONLY over-count leg that fires while the endpoint is UP and
      answering — so the operator guidance ("It over-counts during an LLM
      outage") does not warn about it. ~14 such slots fill the window and
      degrade every group for the rest of the PT24H even after the latency
      recovers.
    repro: |
      1) The configured LLM endpoint answers every request successfully but
      slowly — latency under read-timeout, so no transport failure occurs
      and the circuit breaker never trips. 2) Each group's scheduled slot
      admits a render, the render overruns the 30-minute slot window, M1-763
      cancels it, and the group receives the headlines-only degraded
      digest. 3) That discarded render nevertheless records up to ~211
      calls. 4) After roughly 14 such slots the PT24H window is at the
      ceiling. 5) From then on every group's scheduled digest is refused
      and degrades, and keeps degrading for the remainder of the 24-hour
      window even after the endpoint's latency recovers.
    suggested_fix_class: other
  - date: 2026-08-04
    category: DOS
    severity: medium
    auditor: threat-actor (round 2)
    promise: |
      security.md §Rate limiting — "**Per-group LLM rate (D47)** — a
      separate sub-bucket per approved group bounding LLM-triggering
      operations (chat replies + on-demand `/summary` + `/retry` re-rolls)
      across all group members. ... Periodic digests do NOT count against
      user-initiated per-group LLM budget (they are system-initiated; the
      aggregate system LLM budget is the backstop for digest cost)." The
      spec assigns `/retry` re-rolls to the per-user and per-group buckets
      and the aggregate budget to SYSTEM-initiated digest cost; the whole
      §Rate limiting section is built on per-consumer bounds.
    gap: |
      The load-bearing justification for the draw altitude is FALSE. The
      javadoc claims the draws live in `renderSections` because it is "the
      only scheduled-route-only entry point" whose scope buys correctness
      at the price of an inexact count. `renderSections` is not
      scheduled-route-only: `DigestRetryService.fallbackRerun` calls
      `digestWorker.execute(slot)` on the `/retry --digest` command path,
      and `execute` → `executeSlot` → `renderSections`. The SAME source
      file already documents this — "TWO ROUTES reach this render ... The
      USER-INITIATED route is `/retry --digest`" — so the new javadoc
      contradicts the code beside it. Consequences: (i) a user-initiated
      re-run draws the aggregate system budget ON TOP of the per-user LLM
      token and the D47 per-group sub-bucket already spent — the
      double-metering the javadoc claims the altitude avoids; (ii) the
      admission gate sits on the SHARED path, so a deployment-wide counter
      driven by OTHER tenants' system-initiated digests now refuses a group
      admin's user-initiated command. The trade-off recorded in the
      javadoc, the ticket and `application.properties` is not the trade-off
      that shipped.
    repro: |
      1) Unrelated groups' scheduled slots drive the PT24H window to the
      3000 ceiling. 2) A group admin in group B — whose group has never
      overrun anything — issues `/retry --digest` for a slot whose sections
      were not persisted (any slot that previously degraded leaves
      `renderedSections` null, so nothing is persisted and the retry takes
      the `fallbackRerun` branch). 3) `RetryCommandHandler` spends B's
      per-user LLM token and B's D47 per-group token, `DigestRetryService`
      stamps B's PT2M cooldown, and the render is then refused by a counter
      B never touched. 4) B receives the headlines-only degraded form and
      has burned a token, a group token and a cooldown for it. The converse
      is equally reachable: a group admin's own `/retry --digest` full
      re-runs draw down the deployment-wide pool that backstops every OTHER
      group's scheduled digest, with no per-group share to bound it.
    suggested_fix_class: rate-limit
  - date: 2026-08-04
    category: DOS
    severity: medium
    auditor: claude (round 3 multi)
    promise: |
      security.md §Rate limiting — "**Per-group LLM rate (D47)** — a
      separate sub-bucket per approved group bounding LLM-triggering
      operations (chat replies + on-demand `/summary` + `/retry` re-rolls)
      across all group members. ... Periodic digests do NOT count against
      user-initiated per-group LLM budget (they are system-initiated; the
      aggregate system LLM budget is the backstop for digest **cost**)."
      The whole §Rate limiting section is built on per-consumer bounds,
      and the aggregate budget is scoped by the spec to *digest cost*, i.e.
      LLM spend.
    gap: |
      The round-2 pre-charge gate is placed ABOVE the branch that decides
      whether the retry costs any LLM call at all, so it refuses a
      zero-LLM-cost operation on the strength of a counter that only other
      tenants filled. `RetryCommandHandler` consults
      `SystemLlmBudget.canStartRender()` BEFORE `retryDigest`, but
      `retryDigest` has two legs: a REPLAY leg (`replayMissing`) that
      re-delivers the already-persisted, already-sanitized section bytes
      for the categories with no delivery record — zero provider calls,
      zero render, no DigestWorker involvement at all — and the
      `fallbackRerun` leg that really does re-render. Only the second leg
      is "genuinely digest cost". The shipped gate cannot tell them apart,
      so the delivery-recovery path — the only way a group recovers
      categories that failed to send — is denied for as long as the
      deployment-wide PT24H window stays at its ceiling. The denial can
      outlive the fix: the replay row expires on the same order as the
      budget window, after which the retry falls through to `fallbackRerun`
      — which the same gate also refuses — and the undelivered categories
      are simply lost.
    repro: |
      1) Group B's scheduled digest fires; some category messages fail to
      deliver (adapter transient failure), leaving delivery rows missing
      for those slugs while the rendered sections are persisted. 2)
      Meanwhile the deployment-wide window is driven to the ceiling by
      OTHER groups (nominal load at the documented capacity, or an attacker
      inflating group A's post volume so A's FULL-mode render draws
      hundreds of calls). 3) B's group admin issues `/retry --digest`. 4)
      The handler refuses with the system-budget reply even though the
      retry would have taken the replay leg and issued ZERO LLM calls — it
      would only have re-sent bytes already rendered, already sanitized and
      already stored. 5) B cannot recover the missing categories for the
      rest of the 24-hour window. Nothing in the threat model lets one
      tenant's LLM consumption deny another tenant an operation that
      consumes no LLM.
    suggested_fix_class: rate-limit
  - date: 2026-08-04
    category: DOS
    severity: low
    auditor: opencode (round 4 multi)
    promise: |
      The round-2/3 dispositions commit: "/retry --digest BINDS the pool
      (Option B), with a new pre-charge gate in RetryCommandHandler so a
      refused retry burns no token, no D47 draw and no cooldown." Under
      the round-3 mechanism ("the fallback-boundary gate ... with
      RetryCommandHandler refunding the per-user token on that refusal —
      so a doomed re-run burns no personal budget and no cooldown").
      Underlying spec promise, security.md §Rate limiting (D47): "a
      separate sub-bucket per approved group bounding LLM-triggering
      operations (chat replies + on-demand `/summary` + `/retry`
      re-rolls) across all group members." The sub-bucket bounds
      LLM-triggering operations; a zero-LLM refusal is not one.
    gap: |
      The D47 per-group LLM token is deterministically burned on a
      system-budget-refused re-run. The group sub-bucket is drawn BEFORE
      the service is consulted (`rateCapBucket.tryAcquireGroupLlm` then
      `digestRetryService.retryDigest`); when the fallback boundary
      refuses (`DigestRetryService.fallbackRerun`, `if
      (!systemLlmBudget.canStartRender()) return
      RetryResult.SYSTEM_BUDGET_REFUSED`), the refund arm refunds ONLY the
      per-user token — `RateCapBucket` exposes no group-token refund (only
      `tryAcquireGroupLlm` and `refundCheapCommand`), so the D47 token
      stays consumed. Two amplifiers on the same path: (a) the refusal
      never stamps the retry cooldown, so refusals are not
      self-throttling — the only rate limit on repeat refusals is the very
      D47 sub-bucket the refusals drain; (b) the same draw-before-gate
      ordering applies to the worker-gate leg: a re-run admitted by the
      fallback gate can still be degraded by the second gate in
      `DigestWorker.executeSlot` when the window fills between the two
      checks — an interval spanning the cache-boundary read, post
      collection and group-metadata read, NOT the "millisecond-scale" the
      ticket acceptance claims; that path returns SUCCESS, so the per-user
      token, the D47 token and the cooldown are all burned for a degraded
      (non-refused) re-run.
    repro: |
      1) Unrelated groups' scheduled slots drive the PT24H window to the
      ceiling (nominal load reaches it at the ticket's own stated capacity
      of ~50 full-mode groups at two slots/day). 2) Group admin of group B
      issues `/retry --digest` for a slot with no persisted sections. 3)
      The fallback gate refuses with SYSTEM_BUDGET_REFUSED: B's per-user
      token is refunded, but B's D47 per-group token stays consumed and no
      cooldown is stamped. 4) The admin repeats — each refusal consumes
      another D47 token until B's sub-bucket is empty. 5) B's D47
      sub-bucket — the budget bounding chat replies, `/summary` and
      `/retry` across ALL group members — is exhausted by zero-LLM
      refusals, so other members' LLM-triggering commands hit the fixed
      group.llm_rate_limit reply.
    suggested_fix_class: rate-limit
  - date: 2026-08-04
    category: DOS
    severity: medium
    auditor: codex (round 4 multi)
    promise: |
      "LLM-triggering operations (chat replies + on-demand `/summary` +
      `/retry` re-rolls) — its own bucket, capped lower, profile-driven";
      the per-group LLM bucket is "the backstop for groups with many
      active members."
    gap: |
      `DigestRetryService.fallbackRerun()` admits a fallback at
      `DigestRetryService.java:250-252`, but `DigestWorker.executeSlot()`
      independently re-checks the same system budget at
      `DigestWorker.java:231-234`. If the budget reaches its ceiling
      between those checks, the worker degrades and returns RAN;
      fallbackRerun stamps the retry cooldown and returns SUCCESS; the
      handler refunds only the SYSTEM_BUDGET_REFUSED enum arm, so this
      no-LLM system-budget refusal instead retains both the per-user and
      D47 group draws and reports success.
    repro: |
      With a section-less prior digest and the system window one call
      below its ceiling, a group admin sends `/retry --digest`. Its
      fallback passes the first gate after the handler has acquired the
      per-user and group LLM tokens. Before executeSlot reaches its second
      gate, another admitted digest records enough calls to fill the
      window. executeSlot sends only the degraded digest, yet the retry is
      reported as SUCCESS and its cooldown is stamped; the caller's token
      and the group-shared token remain consumed. Repeating this race lets
      a group admin consume the shared group retry capacity without any
      fallback provider call, denying recovery retries to the group's
      other authorized admins while the system budget drains.
    suggested_fix_class: rate-limit
redteam_audits:
  - date: 2026-08-04
    verdict: FINDINGS
    base: f9c068b8
    head: <working tree>
    verdict_file: docs/plan/m1/redteam-multi/M1-767-2026-08-04/cross-examination.md
    findings_count: 6
    out_of_model_count: 5
    auditors: [claude, kimi]
    note: |
      Multi-auditor run (/redteam-multi). 6 finding clusters, 0 corroborated by
      the report's file:line clustering — but by substance the two auditors
      independently reached the SAME two root causes from opposite directions:
      (i) the meter is an admission-only gate with no intra-render bound
      (claude f1 / kimi f1), and (ii) the four draw sites count a PROXY for
      calls at the caller rather than the calls themselves, which over-counts on
      failure paths (claude f3) and under-counts the roll-up translator leg and
      the retry/eviction legs (claude f2 / kimi f2). Verified independently:
      `CategoryRollupGenerator.generateRollup` does make two provider-reaching
      calls (provider.generate + translationPipeline.run) while the diff records
      1; `SummaryProseGenerator.generate` does return every cluster degraded
      with zero calls when `llmRouter.forTask` throws while the diff records
      `shownClusters.size()`. Disposition: escalate as redteam-finding ->
      refine. The accounting altitude moves to the real `provider.generate(...)`
      sites (all three helpers are already in infochat-provider, so no
      cross-module work), which also makes an intra-render bound nearly free
      since both generators already have per-call degradation paths. The
      per-group share, the deterministic staggerOffset starvation, the
      `/retry --digest` shared-pool draw, and the breaker-OPEN residual
      over-count are deferred to a follow-up ticket. OUT-OF-MODEL items
      (in-memory window resets on restart; per-JVM ceiling under a future
      multi-instance Provider; operator misconfiguration) accepted as filed.
  - date: 2026-08-04
    verdict: FINDINGS
    base: f9c068b8
    head: <working tree>
    verdict_file: docs/plan/m1/redteam/M1-767-2026-08-04-r2.md
    findings_count: 2
    out_of_model_count: 2
    note: |
      Re-audit (/redteam M1-767 --in-progress) after the round-1 refine
      landed. Confirmed the round-1 remediation held — P4 structurally
      fixed, all five named divergence legs accurate, not re-reported.
      Finding 1: the divergence enumeration misses the M1-763 slot-window
      cancellation leg — up to ~211 phantom draws per discarded render,
      ~7% of the ceiling, the only over-count leg that fires while the
      endpoint is UP but slow, and the operator note points away from it.
      Finding 2: the javadoc's load-bearing "only scheduled-route-only
      entry point" justification is FALSE — `/retry --digest` reaches the
      same render via `DigestRetryService.fallbackRerun`, so the retry
      double-meters and can be refused by other tenants' scheduled spend.
      Disposition: escalate as redteam-finding -> refine. Finding 1 fixed
      by naming the leg (javadoc + ticket + application.properties).
      Finding 2 fixed by correcting the claim in all four places and
      deciding the retry-route policy: /retry --digest BINDS the pool
      (Option B), with a new pre-charge gate in `RetryCommandHandler` so a
      refused retry burns no token and no D47 draw. OUT-OF-MODEL: the
      ceiling's "100x headroom" is headroom over ONE render, not daily
      load — zero headroom at documented capacity; sizing wording fixed.
  - date: 2026-08-04
    verdict: FINDINGS
    base: f9c068b8
    head: <working tree>
    verdict_file: docs/plan/m1/redteam-multi/M1-767-2026-08-04-r2/cross-examination.md
    findings_count: 1
    out_of_model_count: 2
    auditors: [claude, opencode, codex]
    note: |
      Multi-auditor re-audit (/redteam-multi, round 3) after the round-2
      fixes. opencode: CLEAN; codex: CLEAN (short-form reply). claude: 1
      medium finding — the round-2 PRE-CHARGE gate (RetryCommandHandler
      consulting the budget before retryDigest) refuses the retry's REPLAY
      leg, a zero-LLM operation (persisted bytes re-delivered, no render),
      on the strength of the deployment-wide counter. Single-auditor;
      falsification pass verified it against the code — REAL: the blanket
      pre-charge gate fires before the replay-vs-fallback decision.
      Disposition: escalate as redteam-finding -> refine; the gate moves
      into DigestRetryService.fallbackRerun (refusing ONLY the
      LLM-spending leg, new RetryResult.SYSTEM_BUDGET_REFUSED, no cooldown
      stamp) and RetryCommandHandler refunds the per-user token on that
      refusal (group-cap-rejection shape); the replay leg is never gated.
      The D47 draw follows the pre-existing conservative non-render-result
      convention. OUT-OF-MODEL (claude, advisory): the refusal path adds
      cheap DB round-trips per attempt when a group admin hammers during
      exhaustion (bounded by the D47 per-group command sub-bucket); the
      distinct refusal string is a cross-tenant load oracle for group
      admins (admin-tier, metadata-only — noted for the spec owner).
      Round-2 fixes verified closed (cancellation leg named; false claim
      corrected in all four places; monitor-holding breach signal fixed).
  - date: 2026-08-04
    verdict: FINDINGS
    base: f9c068b8
    head: <working tree>
    verdict_file: docs/plan/m1/redteam-multi/M1-767-2026-08-04-r3/cross-examination.md
    findings_count: 2
    out_of_model_count: 2
    auditors: [opencode, codex]
    note: |
      Multi-auditor re-audit (/redteam-multi, round 4) after the round-3
      fix. claude UNAVAILABLE (session rate limit, no verdict written).
      opencode: 1 low — the round-3 post-charge fallback gate burns the
      D47 per-group token on SYSTEM_BUDGET_REFUSED (no group-bucket refund
      exists) and repeat refusals drain the group's shared budget;
      also corrected the "millisecond-scale" race claim (the interval
      spans DB reads). codex: 1 medium — the two-gate race
      (fallbackRerun gate vs executeSlot gate): a re-run admitted by the
      first gate is degraded by the second, reported SUCCESS, with both
      tokens and the cooldown burned. Both independently converge on the
      same root cause: any gate that refuses or degrades AFTER the charges
      burns the group's shared budget for a system-level denial.
      Disposition: escalate as redteam-finding -> refine. The post-charge
      gate/refund shape is ABANDONED: the refusal decision moves to a
      PRE-CHARGE probe in RetryCommandHandler (DigestRetryService.retryLeg
      + canStartRender) — FALLBACK leg + exhausted window refuses before
      ANY charge (no token, no D47 draw, no cooldown); the fallbackRerun
      gate, SYSTEM_BUDGET_REFUSED enum value and refund arm are removed;
      the executeSlot gate remains the authoritative admission point and
      its residual degrade-after-charge outcome is documented honestly
      (interval spans retryDigest's reads + the worker's collection; the
      tokens follow the pre-existing conservative non-render convention).
      OUT-OF-MODEL (opencode): in-memory per-JVM window; the refusal
      path's notifier JDBC UPSERT + cross-tenant load-oracle string
      (bounded by the D47 command sub-bucket, admin-tier — noted for the
      spec owner).
  - date: 2026-08-04
    verdict: FINDINGS
    base: f9c068b8
    head: <working tree>
    verdict_file: docs/plan/m1/redteam-multi/M1-767-2026-08-04-r4/disposition.md
    findings_count: 1
    out_of_model_count: 2
    auditors: [claude, opencode, codex]
    note: |
      Round-5 multi-auditor re-audit of the pre-charge probe. claude CLEAN,
      opencode CLEAN, codex 1 medium/DOS. Both CLEAN auditors verified the
      round-4 root cause structurally closed. Disposition written
      2026-08-05 after the fact — the run completed 01:07 but the session
      ended mid-remediation, leaving no disposition and no audit entry.
      codex finding: the pre-charge probe is not an ATOMIC authorization —
      retryLeg's unlocked reads can be flipped FALLBACK -> REPLAY by a
      concurrent render persisting sections, so a refusal can gate a leg
      that would have made no LLM call. Falsification pass: MECHANISM real
      (verified against the code), SEVERITY not — the refusal returns
      before llmRateCap/rateCapBucket and never enters retryDigest, so it
      refuses FREE (no token, no D47 draw, no cooldown) and self-heals on
      re-issue; the repro also compares against a counterfactual timeline
      (retryDigest is never called on the refusal path). Regraded LOW,
      carried as a documented residual — no ordering closes it, only a
      lock, and the zero cost is what bounds it. Round-6 rework instead
      reverted a REGRESSION this audit never saw (the 01:08 gate reorder
      put the signalling canStartRender() ahead of the probe, alarming
      "digest degraded" on the un-gated REPLAY leg) and qualified the
      "never gated" claim at all nine live sites. OUT-OF-MODEL (claude):
      in-memory per-JVM window; the M1-769 accounting/fairness residuals
      restated as scope, not new.
  - date: 2026-08-05
    verdict: CLEAN
    base: f9c068b8
    head: <working tree>
    verdict_file: docs/plan/m1/redteam/M1-767-2026-08-05-r6.md
    out_of_model_count: 4
    note: |
      Round-6 re-audit (/redteam M1-767 --in-progress, single threat-actor)
      of the round-6 rework. RE-AUDIT framing appended to the rendered
      prompt: all seven prior findings listed with their claimed
      disposition, auditor instructed not to assume closure from attempted
      remediation, CLEAN explicitly authorised. Verdict CLEAN — 0 findings
      at every severity. The auditor independently confirmed the
      probe-first rationale against the code and confirmed the revert
      introduces no new gap (the FALLBACK leg is byte-identical under
      either order). All four out-of-model items are carried forward, none
      newly filed: in-memory per-JVM window; the refusal-string
      cross-tenant load oracle (admin-tier, metadata-only); pre-cap DB work
      on the refusal path (verified bounded by the D47 per-group command
      sub-bucket, default 20 per 15 min); and a scope note restating the
      accounting/fairness residuals already owned by M1-769. The round-5
      codex finding was not re-reported — it ships as a documented
      residual. NOTE: `redteam_findings:` is deliberately NOT reset to []
      on this CLEAN verdict. The skill's reset rule assumes a single audit;
      resetting here would erase the 11 findings rounds 1-5 recorded, and
      the skill's own "persist each round separately" rule governs.
clarity_check:
  date: 2026-08-04
  verdict: PASS
  warnings:
    - >-
      CENSUS-PRESENT-IF-CLASS-SCOPED nudge declined: the ticket adds a new
      meter class, it does not dispose every site of an existing class, so
      no Census section applies. Live call sites are already enumerated in
      the Notes section.
  blockers: []
escalation_reason:
---

# M1-767: Aggregate system LLM budget for the unmetered scheduled digest

## Context

`docs/spec/security.md` §Rate limiting states, of the per-group LLM rate
under D47:

> Periodic digests do NOT count against user-initiated per-group LLM budget
> (they are system-initiated; the aggregate system LLM budget is the
> backstop for digest cost).

The exemption ships. The backstop does not. No aggregate, global, or
system-wide LLM spend control exists anywhere in the codebase — a search
across every module for such a control returns only *byte* budgets on
chat-tool JSON output (`SearchPostsTool`, `GetReferencesTool`,
`ListSavesTool`, `SemanticSearchTool`), which are response-size clamps and
have nothing to do with call volume.

The code says so itself. `DigestRenderer`'s javadoc (lines 125–137),
written in response to an unrelated red-team finding on 2026-08-04:

> TWO ROUTES reach this render, and they are metered differently
> [redteam 2026-08-04, low/DOS]. The SCHEDULED route (`DigestScheduler` →
> `DigestWorker.executeSlot`) has no user in the loop, so no per-user or
> per-group bucket is drawn and this budget is the only rate-limiting
> control that exists on it.

— where "this budget" is `infochat.save.translation-max-per-page`, which
the same javadoc says "bounds THIS leg only" and "is not a bound on the
render's translator cost as a whole", because `appendClusterProse` and
`CategoryRollupGenerator` "reach the same `ModelTask.TRANSLATOR` on the
same render with no per-render budget of their own".

So the scheduled digest is the one LLM-spending surface in the system with
no volume meter of any kind.

## What currently bounds it, and why that is not enough

Three partial bounds exist, and none of them is a spend meter:

| Bound | What it limits | Why it is not the backstop |
|---|---|---|
| `DigestCategorizer.maxCategories = 8` | roll-up calls per render | Structural. Says nothing about cluster-prose calls, which scale with admitted post volume. |
| Slot window + `renderFuture.cancel(true)` (M1-763) | how LONG one render may spend | Temporal, not volumetric. A render that stays inside its window is unbounded in cost. |
| `categoryItemCap` | items per category | Passed as `Integer.MAX_VALUE` on the `/summary --full` path (`DigestRenderer:228`). |

The M1-763 cancellation is doing the most work of the three, and it is a
stopwatch. Its efficacy also rests entirely on the transport contract
M1-764 pinned — which is why that ticket flagged this one.

## Why this is filed rather than folded into a digest ticket

Three tickets have now named this gap in `out_of_scope` and declined it —
M1-756, M1-758, M1-764. Each decline was individually correct: none of the
three was a rate-limiting ticket. But three declines and zero owners is how
a documented gap becomes a permanent one, so it gets its own ID.

Two independent red-team audits have reached it from different directions
(the M1-763/M1-764 digest-cancel line, and whichever audit prompted the
`DigestRenderer` javadoc above). The recurrence is the argument for filing.

## Why `security_relevant: true`

The control is a DOS/cost boundary named in the threat model but absent
from the code, on the one surface deliberately exempted from every other
bucket. A budget that fails open, throws into the scheduler, or alarms
once per suppressed call would each be worse than the status quo, and each
is the kind of defect the adversarial pass is good at finding.

## Measurement (acceptance item 4)

One `digest_mode=full` render at a realistic post volume — 30 posts in an
inter-slot period clustering to ~15 clusters, 3 promoted to the lead
(`lead-size` 3, `lead-minimum` 6):

| Call site | Generative calls |
|---|---|
| `summaryProseGenerator.generate(shownClusters)` (`DigestRenderer:347`) | 12 — one provider call per cluster, the generator's documented per-cluster contract |
| `summaryProseGenerator.generate(leadClusters)` (`DigestRenderer:359`) | 3 |
| `appendClusterProse` prose translation (`DigestRenderer:825`) | 15 — one per cluster, non-`en` scope, cold translation cache |
| `CategoryRollupGenerator.generateRollup` | 0 — full mode renders no roll-ups |
| **Total** | **30 generative calls per full render** |

Default ceiling = 100× the measured per-render cost = **3000 calls per
PT24H window**. Read that "100×" correctly: it is headroom over ONE
render, NOT over the deployment's daily load — 3000 / 30 calls-per-render
is exactly ~50 full-mode digest groups at two slots/day, so at the
documented capacity there is zero headroom left for meter error, retry
volume or a slow-endpoint slot (round-2 red-team out-of-model note). The
default "trips on runaway spend" only below that capacity; operators
running at or near it MUST scale the ceiling (or reduce digest groups)
or normal operation will degrade. The window default matches the digest
cadence the cost was measured over. The display-hit headline leg
(`appendHeadlines`, M1-756) is not counted: it already has its own
per-render budget (`translation-max-per-render`), so the meter covers the
render's previously-unbounded legs.

## Red-team disposition (2026-08-04)

`/redteam-multi` returned 6 findings from 2 auditors (verbatim in
`redteam_findings:`; full record at
`docs/plan/m1/redteam-multi/M1-767-2026-08-04/`). Disposition:

| Finding | Disposition |
|---|---|
| claude f4 — `notifyOnce` holds the budget monitor across a JDBC write | **Fixed here** (acceptance 3b) |
| claude f2 — roll-up TRANSLATOR leg uncounted; the "one provider call per invocation" comment is false | **Documented here** as a named under-count leg (acceptance 1b); exact fix → M1-769 |
| claude f3 — draws fire when zero HTTP calls happen (unresolvable provider, breaker OPEN) | **Documented here** as a named over-count leg; exact fix → M1-769 |
| kimi f2 (c) — cache eviction between `appendClusterProse`'s probe and the pipeline read | **Documented here** as a named under-count leg; exact fix → M1-769 |
| kimi f2 (b) — "retry once" spends 2 and draws 1 | **False positive, not carried.** The retry-once loops live in the collector workers (`Stage2Worker`, `ClassifierWorker`, `EntityExtractorWorker`, `BodySummaryWorker`, `IngestTranslationWorker`). None of the three digest-route helpers retries — `SummaryProseGenerator`, `CategoryRollupGenerator` and `LlmTranslationProvider` degrade instead. |
| kimi f2 (d) — `leadProse.size()` under-counts when a cluster is dropped | **False positive, not carried.** `SummaryProseGenerator.generate` adds exactly one `ClusterProse` per input cluster on every branch (refusal, empty text, per-cluster exception) and the unresolvable-router path maps the whole input list, so the returned size always equals the input size. No cluster is ever dropped. |
| kimi f2 (a) — exception mid-generate drops the count of calls already made | **Narrow, not carried.** `SummaryProseGenerator.generate` cannot propagate: the router resolution and each per-cluster call are both caught. The residual is `translationPipeline.run` throwing in `appendClusterProse` before its draw, which is the same leg (c) already covers. |
| claude f1 / kimi f1 — admission-only gate, no intra-render bound, no per-group share, deterministic stagger starvation, `/retry --digest` shares the pool | **Deferred → M1-769** |

The reason the accuracy findings are documented rather than fixed here: the
draw has to sit where the call is made to be exact, but the three generative
helpers are shared with `/summary`, `/retry`, chat and saves, while
`renderSections` is reached by BOTH the scheduled route and `/retry --digest`
— the draws stay at that altitude because moving them down would meter the
shared user-initiated routes into the system budget (round-2 finding 2
corrected the earlier "only scheduled-route-only entry point" wording, here
and in `SystemLlmBudget`'s javadoc, M1-769 and `application.properties`).
Getting both needs a render-scoped call sink threaded through those shared
classes — M1-769's job. The OUT-OF-MODEL items both auditors raised
(in-memory window resets on restart; per-JVM ceiling under a future
multi-instance Provider; operator misconfiguration of the two keys) are
accepted as filed; the "100x headroom" sizing wording is corrected in
§Measurement.

## Round-2 red-team disposition (2026-08-04)

`/redteam M1-767 --in-progress` re-audited the round-1-refined diff:
**FINDINGS — 0 critical, 0 high, 2 medium, 0 low; 2 out-of-model**
(verbatim in `redteam_findings:`; full record at
`docs/plan/m1/redteam/M1-767-2026-08-04-r2.md`). Disposition:

| Finding | Disposition |
|---|---|
| F1 — the divergence enumeration misses the M1-763 cancellation leg (~211 phantom draws per discarded render; fires while the endpoint is UP but slow) | **Documented here** as a named over-count leg (acceptance 1b), in `SystemLlmBudget`'s javadoc and in `application.properties`' operator note |
| F2 — the "only scheduled-route-only entry point" justification is FALSE; `/retry --digest` reaches the same render and double-meters | **Fixed here**: the claim is corrected in all four places, and the retry-route policy is decided — `/retry --digest` BINDS the pool (acceptance 1c); the pre-charge gate in `RetryCommandHandler` is superseded by the fallback-boundary gate in `DigestRetryService` after round 3 (below) |
| Out-of-model — "100x headroom" is headroom over ONE render, not daily load; zero headroom at documented capacity | **Wording fixed here** (§Measurement, `application.properties`); exact accounting and the per-call bound stay M1-769 |

## Round-3 red-team disposition (2026-08-04)

`/redteam-multi` re-audited the round-2-fixed diff with three auditors
(claude, opencode, codex): **opencode CLEAN; codex CLEAN; claude FINDINGS —
0 critical, 0 high, 1 medium, 0 low; 2 out-of-model** (verbatim in
`redteam_findings:`; full record at
`docs/plan/m1/redteam-multi/M1-767-2026-08-04-r2/`). Disposition:

| Finding | Disposition |
|---|---|
| F1 — the round-2 PRE-CHARGE gate refuses the retry's REPLAY leg (zero LLM calls) on the strength of the deployment-wide counter, denying the only delivery-recovery path during exhaustion | **Fixed here** (acceptance 1c; mechanism superseded by the round-4 pre-charge probe below): the gate moved into `DigestRetryService.fallbackRerun` refusing ONLY the LLM-spending leg, with a per-user-token refund — itself superseded because the post-charge refusal burned the D47 draw (round-4 findings). Single-auditor finding — falsification pass verified it against the code: REAL |
| Out-of-model — refusal path adds cheap DB round-trips when an admin hammers during exhaustion; the refusal string is a cross-tenant load oracle | **Advisory, accepted as filed** (both bounded by the D47 per-group command sub-bucket / admin-tier metadata; noted for the spec owner) |
| Round-2 fixes | **Verified closed** by all three auditors (cancellation leg named; false claim corrected; monitor-holding breach signal fixed) |

## Round-4 red-team disposition (2026-08-04)

`/redteam-multi` re-audited the round-3-fixed diff (opencode, codex;
claude UNAVAILABLE — session rate limit): **opencode FINDINGS — 1 low;
codex FINDINGS — 1 medium; 2 out-of-model** (verbatim in
`redteam_findings:`; full record at
`docs/plan/m1/redteam-multi/M1-767-2026-08-04-r3/`). Disposition:

| Finding | Disposition |
|---|---|
| opencode (low) — the post-charge fallback gate burns the D47 per-group token on `SYSTEM_BUDGET_REFUSED` (no group-bucket refund exists); repeat refusals drain the group's shared budget; the "millisecond-scale" race claim is wrong | **Fixed here** (acceptance 1c): the post-charge gate/refund shape is ABANDONED for a PRE-CHARGE probe — `DigestRetryService.retryLeg` + `canStartRender()` in the handler, refusing before ANY charge; the fallbackRerun gate, `SYSTEM_BUDGET_REFUSED` and the refund arm are removed; the interval wording is corrected |
| codex (medium) — the two-gate race: a re-run admitted by the fallback gate is degraded by the executeSlot gate, reported SUCCESS with both tokens + cooldown burned | **Fixed here**: with the fallback gate removed there is exactly ONE admission decision on the shared path (executeSlot, degrade-not-refuse); the residual fill-in-the-window outcome is documented honestly and follows the conservative non-render convention; the refusal path draws nothing and is race-free by construction |
| Out-of-model (opencode) — in-memory per-JVM window; refusal-path notifier UPSERT + load-oracle string | **Advisory, accepted as filed** (bounded by the D47 command sub-bucket; noted for the spec owner) |

## Notes

- Live generative call sites inside the render, as of this filing:
  `DigestRenderer.java:347` and `:359`
  (`summaryProseGenerator.generate`), and
  `CategoryRollupGenerator.java:195-196`.
- The existing meters this must NOT disturb: `LlmRateCap` (per-user,
  M1-183) and the D47 per-group sub-bucket (M1-222).
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-767-aggregate-system-llm-budget.md`

## Round 1 rework

1. **Acceptance item 3 described the abandoned ordering.** The item read
   "the `SystemLlmBudget.canStartRender()` check runs FIRST (in-memory) and
   the `DigestRetryService.retryLeg` probe ... runs only when the window is
   at/over the ceiling" — the reverse of the shipped
   `retryLeg(...) == FALLBACK && !canStartRender()`, and in contradiction
   with the ticket's own `out_of_scope` item 1, `retryLeg`'s javadoc,
   `RetryCommandHandler`, `application.properties` and
   `RetryDigestCommandTest.retryDigest_replayLegProceedsWithoutConsultingTheSystemBudget`
   (which asserts `canStartRenderCalls == 0`). Restated as probe-first,
   with the load-bearing reason recorded: `canStartRender()` is not a pure
   predicate, so consulting it on the REPLAY leg alarms "scheduled digest
   degraded" for a digest that was not degraded. The same item's
   "sub-millisecond race" wording was replaced — under the shipped order
   the probe-to-refusal interval spans `canStartRender()`'s `notifyOnce`
   JDBC UPSERT, so the residual is bounded by the refusal's zero cost, not
   by the interval's width. Doc-only; no production code changed.
