---
id: M1-769
title: "Render-scoped exact LLM call accounting and per-call bound for the digest budget"
status: done
created: 2026-08-04
last_updated: 2026-08-05
blocked_by: [M1-767]
files_budget: 18
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    THE BUDGET'S EXISTENCE, ITS CONFIG KEYS, ITS DEGRADE-ON-BREACH
    BEHAVIOUR AND ITS BREACH SIGNAL. `SystemLlmBudget`, the two
    `infochat.digest.system-llm-call-*` properties, the
    `DigestWorker.executeSlot` admission gate that degrades to the
    non-generative renderer, and the once-per-window
    `ThrottledAdminNotifier` signal all ship in M1-767 and all work. This
    ticket changes WHERE the meter is drawn and WHEN it refuses; it does
    not re-litigate what the meter is.
  - >-
    THE PER-USER AND PER-GROUP CAPS ON THE USER-INITIATED ROUTE.
    `LlmRateCap` (M1-183) and the D47 per-group sub-bucket (M1-222) meter
    `/summary` and `/retry --digest` today and keep doing so unchanged.
    The point of the render-scoped sink is that the system budget stays
    OFF the OTHER user-initiated routes — a draw that fires for `/summary`
    or the retry REPLAY path would double-meter it and break the spec's
    system-vs-user-initiated split. The `/retry --digest` FALLBACK re-run
    is the one exception, decided in M1-767 (round-2 red-team
    disposition; mechanism refined rounds 3+4): its render is genuinely
    digest cost and binds the pool, refused PRE-CHARGE in
    `RetryCommandHandler` via the `retryLeg` probe (no token, no D47
    draw, no cooldown on refusal), while the replay leg stays off.
  - >-
    WIDENING THE BUDGET TO NON-DIGEST LLM SURFACES. Chat, ingest (Stage 1
    / Stage 2 / tagging / entity / embedding), `/compress` and saves are
    not covered and must not become covered. Instrumenting a shared
    chokepoint is only acceptable if the sink is UNBOUND on those paths;
    a counter that fires deployment-wide would let a collector backlog
    starve the provider — an availability trade M1-767 already refused.
  - >-
    THE DIGEST RENDER'S SHAPE IN NORMAL OPERATION. `maxCategories`,
    `categoryItemCap`, cluster selection, the FULL-mode lifted cap and
    the roll-up structure are unchanged. The per-call bound reduces what
    the render spends ONLY once the budget is exhausted, reusing the
    generators' existing per-call degradation outcomes; it is not a
    cheaper render.
  - >-
    M1-763's slot-window cancellation and M1-764's transport-interrupt
    contract. The temporal bound stays exactly as it is; this ticket
    supplies the per-call volume bound alongside it.
acceptance:
  - >-
    THE METER COUNTS ACTUAL PROVIDER CALLS, NOT A PROXY. The draw fires
    where an LLM call is really issued, so it does not fire when zero
    HTTP request is made (unresolvable `llmRouter.forTask`, circuit
    breaker OPEN short-circuiting "without an HTTP attempt",
    `CategoryRollupGenerator`'s empty-prompt skip, the `en`
    short-circuit, a `TranslationPipeline` cache hit) and does fire for
    every call that is (including
    `CategoryRollupGenerator`'s second, TRANSLATOR leg, which M1-767
    under-counts by construction). M1-767's named divergence legs — see
    its acceptance item 1b — are the checklist this item discharges; each
    must be shown closed or explicitly re-justified.
  - >-
    THE DRAW IS SCOPED TO THE SCHEDULED RENDER. `SummaryProseGenerator`,
    `CategoryRollupGenerator` (reached from `renderShortBody` as well as
    `renderSections`) and `TranslationPipeline`/`LlmTranslationProvider`
    are SHARED with `/summary`, `/retry`, `ChatAgent`, `SavedCommandHandler`
    and `ClusterBlockRenderer`. A call made under any of those must draw
    NOTHING. The mechanism is a render-scoped sink bound around
    `renderSections` (reached from BOTH the scheduled route and
    `/retry --digest` via `DigestRetryService.fallbackRerun` →
    `DigestWorker.execute` → `executeSlot`; the M1-767 round-2 red-team
    corrected the earlier "sole caller" wording — see its acceptance
    item 1b) — the `ScopedValue` pattern `LlmCallContext` /
    `MeteredLlmProvider` already establishes — read by the draw sites;
    unbound means no draw. A test proves a `/summary` run leaves
    `callsInWindow()` unchanged.
  - >-
    THE BOUND ENGAGES WITHIN A RENDER, NOT ONLY AT ADMISSION. Today
    `canStartRender()` is consulted once and an admitted render's spend
    is bounded only by the M1-763 slot window, so one render can overshoot
    the ceiling without limit (both auditors' finding 1). Once the window
    is at the ceiling, further generative calls in the SAME render are
    refused and the affected unit degrades through the generators'
    EXISTING per-call degradation outcomes (`ClusterProse.degraded`,
    `generateRollup` returning `Optional.empty()`) — no new degradation
    machinery, no partial-message state, and the digest still goes out.
  - >-
    A PER-GROUP SHARE SO ONE GROUP CANNOT STARVE THE DEPLOYMENT, AND
    ADMISSION MUST DECIDE ON THE SAME PREDICATE THE DRAW DOES. One
    group's post volume (any non-banned user can inflate it via
    `/add-source` + subscription) must not consume the whole aggregate
    ceiling. `DigestScheduler.staggerOffset` is a deterministic
    `groupId hash % windowWidthMinutes`, so under a purely global ceiling
    the SAME late-firing groups degrade every day — the fairness
    requirement is about that stable starvation set, not just the total.
    The shape of the share (reservation, per-group sub-cap, or
    round-robin admission) is the ticket's design call; three properties
    are required of whatever shape is chosen.
    (a) REACHABLE, not merely declared: a group that has not yet taken
    its own share still draws at an all-but-exhausted window, however
    much an earlier group burned.
    (b) ONE PREDICATE at both altitudes. The admission gate and the
    per-call draw of acceptance item 3 must agree, so a render is never
    admitted into a window that will refuse its very first call: a group
    the draw would refuse is refused at ADMISSION, where it degrades to
    the non-generative digest and raises the existing breach signal,
    rather than running a render that issues nothing and notifies
    nobody. Redteam round 1 (low/DOS) found this violated —
    `canStartRender()` was a group-blind `callsInWindow() < ceiling`
    check while the share lived only in `tryDraw`, so the first starved
    group to reach the reserved tail consumed the whole reserve, drove
    the window to the ceiling, and every later group was refused
    ADMISSION and never reached the share it was promised.
    (c) STATED AT OPERATOR ALTITUDE, exactly. A reserve sized at one
    render funds ONE latecomer per window and no more; the config
    comment in `application.properties` must claim precisely what the
    mechanism delivers, since an over-claimed rate-limiting control is
    itself the finding.
  - >-
    `/retry --digest` BINDS THE DEPLOYMENT-WIDE POOL, DECIDED IN M1-767
    (round-2 red-team disposition; mechanism refined rounds 3-5). It
    reaches the same render via `DigestRetryService.fallbackRerun` ->
    `digestWorker.execute`, so its fallback re-run's calls draw the system
    budget exactly like the scheduled route's — M1-767 refuses the
    fallback leg PRE-CHARGE in `RetryCommandHandler` (the
    `DigestRetryService.retryLeg` probe, then `SystemLlmBudget.canStartRender`
    only on that leg, before any token, D47 draw or cooldown); the replay
    leg (zero LLM calls) is never gated in steady state. This ticket's job
    is the EXACT draw for that
    bound route (the sink reads the same on both entry points); it does
    not re-decide the policy. A test proves a `/retry --digest` replay
    path (no re-render) draws nothing while a fallback re-run draws its
    exact calls.
  - >-
    THE CONCURRENCY STORY IS STATED AND TESTED. The admission gate is
    check-then-act across slot dispatches on virtual threads bounded by
    `infochat.summary.workers`, so N renders can pass under the ceiling
    and then all spend. Say what the intended bound is under concurrency
    and prove it; do not leave it to the reader.
  - >-
    A BUDGET REFUSAL IS NEVER MISTAKEN FOR ENDPOINT EVIDENCE. Whatever the
    per-call refusal of acceptance item 3 throws or returns, it must not be
    recorded by `LlmCircuitBreakerRegistry` as reachability evidence about the
    provider. `CircuitBreakingLlmProvider:70-77` catches
    `LlmCallFailedException.ProviderUnreachableException` and
    `LlmCallFailedException` and records against the task's breaker, so a
    refusal of either type would let this cost control trip the breaker
    against a HEALTHY endpoint under exactly the load it exists to shed —
    turning a spend cap into an outage, the same availability trade
    `out_of_scope` item 3 refuses. A test proves that exhausting the budget
    mid-render leaves the task's breaker state unchanged.
    NOR MAY IT CONSUME THE BREAKER'S RECOVERY PROBE. "Unchanged" was the
    wrong invariant for the OPEN case: redteam round 1 (medium/DOS) found
    that `tryAcquireForTask` transitions `OPEN -> HALF_OPEN`, spends the
    single probe slot and pushes `deadline` a full cooldown forward
    BEFORE the inner decorator refuses — and because the refusal is
    (correctly, per the paragraph above) outside the
    `LlmCallFailedException` family, neither catch records anything. The
    probe is burned with zero observation, so every user-facing LLM
    surface keeps reading `wouldShortCircuit = true` against an endpoint
    that has already recovered, for as long as the refused render keeps
    calling. The catch that restores it is typed at the whole class of
    exceptions falling OUTSIDE the `LlmCallFailedException` family rather
    than at the refusal by name, because naming the refusal would import
    the budget type into the breaker, undoing the separation this same
    acceptance item requires.
    NO-EVIDENCE IS DECIDED BY CAUSE, NOT BY TYPE ALONE, because one
    member of the class hides INSIDE the reachable family. An interrupted
    caller also sends no request, yet `LlmHttpSupport.sendForBody`
    surfaces the interrupt as a plain `LlmCallFailedException` — the type
    the breaker records as REACHABLE, whose `recordReachable()` closes
    unconditionally. So a call that sent no bytes un-trips the breaker
    and zeroes its consecutive-failure count; and because M1-763's
    cancelled render runs its loop to completion issuing exactly such
    calls, the breaker cannot re-trip while an orphaned render drains
    (redteam round 2, medium/DOS). Both recording arms must therefore
    consult the caller's interrupt flag first and, when it is armed,
    record nothing and return the probe. Reading the FLAG rather than
    retyping the exception is required, not incidental: retyping would
    change M1-764's transport contract and the assertion that pins it,
    which `out_of_scope` item 5 fences and which buys nothing — the flag
    is already re-armed before the throw, and that re-arm is the property
    `HttpProviderSharedPipelineTest` guards. The SUCCESS path stays
    unguarded: a call that returned a response observed the endpoint
    whatever the flag says afterwards. The mis-classification direction
    is safe by construction — an interrupt landing between a real
    response and the catch discards one piece of evidence and leaves the
    breaker for the next uncancelled call to settle, rather than
    asserting a reachability nobody observed.
    THE EMBEDDING TWIN IS IN SCOPE for the same reason and by a different
    door: the digest render makes no embedding calls, but `/stop` cancels
    a chat turn whose `SemanticSearchTool` / `HelpLookupTool` path embeds,
    and the embedding providers share `LlmHttpSupport.sendForBody`, so
    `CircuitBreakingEmbeddingProvider` carries the identical defect.
    Moving the budget decorator OUTSIDE the breaker is not an
    acceptable fix: it re-charges the breaker-OPEN short-circuits that
    acceptance item 1 requires to charge nothing. The round-1 test
    exercises only a CLOSED breaker, where `tryAcquire` does not mutate;
    the OPEN-and-cooldown-elapsed case needs its own leg.
  - >-
    `mvn verify` is green from the repo root.
test_plan:
  adds:
    - >-
      A test that a scheduled render whose provider calls all fail with
      the breaker OPEN draws ZERO — the M1-767 phantom-charge finding
      (claude f3), which converted a transient outage into a
      deployment-wide 24h degradation.
    - >-
      A test that a non-`en` roll-up draws BOTH its SUMMARIZER and its
      TRANSLATOR call (claude f2 — M1-767 draws 1 of 2).
    - >-
      A test that `/summary` leaves the system budget's
      `callsInWindow()` unchanged (the scoping property), while a
      `/retry --digest` fallback re-run draws exactly its calls (the
      M1-767-decided policy: the retry route binds the pool).
    - >-
      A test that a single render with far more clusters than the
      remaining ceiling stops issuing generative calls at the ceiling and
      degrades the remainder (claude f1 / kimi f1).
    - >-
      A test that exhausting the budget mid-render leaves the SUMMARIZER
      task's circuit-breaker state unchanged — the refusal is a cost signal,
      not endpoint evidence (acceptance item 8).
    - >-
      A test that a budget refusal taken while the breaker is OPEN and its
      cooldown has ELAPSED leaves the recovery probe available: the next
      call still reaches the provider and closes the breaker. Must be
      non-vacuous against a steppable clock — with a fixed clock or a
      zero cooldown the probe is trivially re-admissible and the leg
      proves nothing (redteam round 1 medium/DOS, acceptance item 8).
    - >-
      A test that the admission gate refuses exactly when a draw for the
      same group would be refused, so a render is never admitted into a
      window that refuses its first call, and that the refused admission
      raises the breach signal (redteam round 1 low/DOS, acceptance
      item 4b).
    - >-
      Tests that an INTERRUPTED call — generative and embedding alike —
      does not reset the breaker's consecutive-failure count, so a
      cancelled render's or a /stop-ed chat turn's remaining calls cannot
      hold the breaker closed against an endpoint nobody contacted
      (redteam round 2 medium/DOS, acceptance item 8). The count, not
      `wouldShortCircuit()`, is the observable that DISCRIMINATES: once
      the probe is released both readings agree, but a reset count cannot
      trip and an intact one can. Each leg runs on a fresh thread so the
      armed flag cannot leak into later tests, and carries assertion
      failures back to the JUnit thread.
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/digest/DigestRendererTest.java
      — `renderSections_drawsSystemLlmBudgetAtLiveCallSites` and
      `renderSections_enScope_drawsNoTranslationCalls`. AUTHORIZED, and here is
      what they must newly assert and why. Both pin M1-767's PROXY draws, whose
      counts (`assertEquals(6, …)`, `assertEquals(7, …)`, `assertEquals(3, …)`)
      are produced by `DigestRenderer:386/402/464/901` firing against test
      doubles — `DistinctProseGenerator` and `RecordingCategoryRollupGenerator`
      override `generate`/`generateRollup` and issue ZERO provider calls. This
      ticket's acceptance item 1 deletes those four draws outright, so the
      numbers cannot survive under ANY correct implementation; leaving them
      green would mean the proxy meter is still there. The replacements must
      assert the OPPOSITE property at the same seam: a render whose generative
      collaborators are non-calling doubles draws ZERO (proving the meter now
      follows real provider calls rather than loop cardinality), and the
      exact-count assertions move to a seam that drives a real provider chain
      so the number asserted is a count of issued calls. The `en`-scope test
      keeps its NAME and its intent — an `en` render draws nothing for
      translation — but its expected total becomes whatever the real-call
      accounting yields for the summarizer leg at that seam, which is zero
      under a non-calling double. Do NOT weaken either test to an inequality or
      delete either method: the scoping property they guard (a render's draw is
      bounded and attributable) is the M1-767 control this ticket tightens
      rather than removes.
  preserves:
    - >-
      M1-767's admission gate, degrade-to-non-generative fallback, and
      once-per-window breach signal — including that the notify does not
      hold the budget monitor across its JDBC write.
    - >-
      The user-initiated route's per-user token and D47 per-group
      sub-bucket draw order and refund-on-group-reject behaviour
      (`RetryCommandHandler`, `DigestRetryService`).
    - >-
      `DigestWorkerTest.execute_renderOverrunningWindow_stopsSpendingProviderCalls`
      (M1-763) and
      `HttpProviderSharedPipelineTest.interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed`
      (M1-764) — the temporal bound is unaffected by tightening the
      volume bound.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/security.md §Failure handling
decision_refs:
  - D47
reviews:
  - round: 1
    date: 2026-08-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 26
      added: 4006
      removed: 295
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-08-05
    category: DOS
    severity: medium
    promise: |
      docs/spec/security.md §Failure handling, "Fail-fast on a
      known-unreachable provider (circuit breaker)": after a cooldown "a
      single probe is admitted — success closes the breaker, failure
      re-opens it."
    gap: |
      The budget decorator sits INSIDE the breaker decorator, so the
      breaker hands out its single HALF-OPEN recovery probe BEFORE the
      budget can refuse the call, and the refusal produces neither
      outcome the spec enumerates. tryAcquireForTask transitions
      OPEN -> HALF_OPEN, consumes the probe slot and sets
      deadline = now + cooldown; RefusedException is outside the
      LlmCallFailedException hierarchy, so neither catch records
      anything. The probe is burned with zero observation.
      exhaustedBudgetRefusesTheCallWithoutTouchingTheBreaker only
      exercises a CLOSED breaker; the HALF-OPEN case is untested.
    repro: |
      Endpoint outage trips the breaker OPEN; endpoint recovers; a digest
      render admitted in the reserved tail has its first generative call
      acquire the HALF-OPEN probe and be refused by the budget. The probe
      reports nothing and the deadline moves a full cooldown forward, so
      a chat turn in that window still reads wouldShortCircuit = true and
      returns "chat assistant is unavailable" against a healthy provider.
    suggested_fix_class: other
  - date: 2026-08-05
    category: DOS
    severity: low
    promise: |
      docs/spec/security.md §Trust boundaries item 9: usage tampering
      "shows up as a gap between the call counter and the token counters".
      §Failure handling: admin notifications are coalesced so an outage
      produces one summary message.
    gap: |
      A budget refusal is presented to the operator as an LLM ENDPOINT
      failure everywhere except the breaker. MeteredLlmProvider
      (APPLICATION, outside the new decorator) records
      llm.calls.total{outcome=FAIL} with usage=null for a call that never
      left the process — manufacturing boundary 9's tamper signature from
      a routine internal cause — and each degrading caller logs it as an
      LLM call failure. The new tryDraw refusal emits no signal of its
      own, and canStartRender() stays true across the reserved-tail band,
      so a wholly-refused render produces no breach notification.
    repro: |
      A group over its reserve has its evening render refused: dashboards
      show an outcome=fail spike with no token usage and the log fills
      with "SUMMARIZER call failed" WARNs, while no admin notification
      fires. The operator investigates a non-existent endpoint outage.
    suggested_fix_class: audit-log-coverage
  - date: 2026-08-05
    category: DOS
    severity: low
    promise: |
      docs/spec/security.md §Rate limiting: "the aggregate system LLM
      budget is the backstop for digest cost", resting on the per-group
      bounding principle stated one bullet above (D47).
    gap: |
      The reserved tail is documented as closing cross-group starvation
      but does not deliver it. The reserve lives only in tryDraw;
      canStartRender() is a plain callsInWindow() < ceiling check with no
      reserve awareness, and DigestWorker.executeSlot refuses the render
      on that gate. tryDraw admits a group under its own reserve all the
      way to size == ceiling, so the FIRST starved group to reach the
      tail consumes the entire reserve and drives the window to exactly
      ceiling; every later group is then refused ADMISSION and never
      reaches tryDraw at all. Because staggerOffset is a deterministic
      hash, the single beneficiary is the same group every day.
    repro: |
      50 groups at defaults (ceiling=3000, groupReserve=30). Group A's
      FULL render spends ~2970. Group B is admitted, takes the whole
      30-call reserve, and drives the window to 3000. Groups C..Z are
      refused admission and ship the non-generative degraded digest, for
      the rest of the window and again the next day.
    suggested_fix_class: rate-limit
  - date: 2026-08-05
    category: DOS
    severity: low
    promise: |
      docs/spec/security.md §Rate limiting ("the aggregate system LLM
      budget is the backstop for digest cost") and §Trust boundaries item
      9's posture that the provider-call boundary is bounded by
      construction rather than by convention.
    gap: |
      The budget's largest single exemption rests on an undocumented JDK
      behaviour. BudgetedLlmProvider skips the draw when the calling
      thread carries an interrupt and STILL delegates, justified by
      M1-764's "an interrupted caller sends no request" — but
      LlmHttpSupport.sendForBody contains no such check and only handles
      InterruptedException after the fact, so the property depends on
      java.net.http.HttpClient.send testing the interrupt flag before
      issuing, which its javadoc does not state. The asymmetry is in the
      unsafe direction: any provider impl that does reach the wire on an
      interrupted thread is spend the budget never sees.
    repro: |
      A FULL render overruns its slot window, renderFuture.cancel(true)
      interrupts the render thread, and the loop continues to completion
      by design. Every remaining provider.generate skips the draw and is
      delegated; if the delegate issues the request — which nothing in
      this repository enforces — those calls never charge the ceiling.
      Repeatable every slot.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-08-05
    category: DOS
    severity: medium
    promise: |
      docs/spec/security.md §Failure handling: after a cooldown "a single
      probe is admitted — success closes the breaker, failure re-opens
      it. Only transport failures trip it: an application error (non-2xx
      status, over-cap body, unparseable reply) proves the endpoint
      answers and counts as reachable."
    gap: |
      Round 2. The round-1 remediation stated a GENERAL no-evidence
      invariant but implements it only for exceptions outside the
      LlmCallFailedException family. An interrupted caller also sends no
      request, yet LlmHttpSupport.sendForBody surfaces it as a plain
      LlmCallFailedException, which CircuitBreakingLlmProvider records as
      REACHABLE and recordReachable() closes unconditionally — so a call
      that sent no bytes un-trips the breaker and zeroes its
      consecutive-failure count. An interrupted send is none of the three
      application errors the spec licenses as reachability evidence.
    repro: |
      A hung endpoint trips the breaker OPEN and makes a digest render
      overrun its slot, so M1-763 interrupts the render thread and the
      loop runs on. After the cooldown one of the orphaned render's calls
      takes the HALF-OPEN probe, sends nothing, and is recorded reachable
      — every LLM surface resumes real attempts against the hung endpoint
      and re-runs the D28 pre-fetch the spec bounds to once per breaker
      cycle. Self-sustaining: each further interrupted call re-zeroes the
      counter, so the breaker cannot re-trip until the loop ends.
    suggested_fix_class: other
    disposition: |
      FIXED IN-BRANCH at the user's direction. The first disposition
      deferred it to a new ticket (M1-770) on the reading that
      out_of_scope item 5 fenced M1-764's transport-interrupt contract;
      that ticket has been DELETED and the deferral was wrong on the
      merits, not only on process:
        - out_of_scope item 5's stated concern is that "the temporal
          bound stays exactly as it is". Reading the interrupt FLAG in
          the breaker decorator does not touch the temporal bound, the
          exception type, or the M1-764 assertion that pins it — so the
          fence's own rationale never reached this change.
        - the deferral's blast-radius claim was false. ChatAgent needs no
          change: the `catch (Exception e)` arm below its
          LlmCallFailedException arm already yields the identical
          user-facing outcome, and its comment says so. Only two sites in
          the deployment catch plain LlmCallFailedException, one being
          the breaker itself.
        - the fix lands in machinery this same diff built — the
          releaseProbeForTask path — so deferring meant shipping a
          control whose own javadoc documented a hole in the invariant it
          stated, for a follow-up commit to reopen the same files.
      Cost measured rather than assumed: 2 files new to the diff
      (CircuitBreakingEmbeddingProvider, LlmCircuitBreakerRegistryTest),
      15 -> 17 against a budget of 18.
redteam_audits:
  - date: 2026-08-05
    verdict: FINDINGS
    base: 3ffd1293a3ac97d199f764db41fb214d7fb0df3a
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-769-2026-08-05.md
    findings_count: 4
    out_of_model_count: 4
    note: |
      Round 1 at the /m1-tick run gate, ahead of review, against the
      uncommitted branch tip. All four are DOS (availability/cost); none
      is a confidentiality or integrity break. Two (the HALF-OPEN probe
      burn, the FAIL-outcome metric) were pre-identified as residuals in
      the plan-writer outline and judged statable rather than fixable
      here; the auditor's medium disagrees on the first because burning
      the probe with zero observation extends user-facing chat
      unavailability against an already-recovered provider. The third
      bears on this ticket's OWN acceptance item 4 rather than only on
      the spec: canStartRender() has no reserve awareness, so the first
      starved group to reach the tail exhausts the reserve and every
      later group is refused admission before tryDraw can honour it.
      Out-of-model items note the retained ungated public recordCalls,
      replay of budget-truncated sections, the display-hit fallback note
      shape, and a cross-group timing channel.
  - date: 2026-08-05
    verdict: FINDINGS
    base: 3ffd1293a3ac97d199f764db41fb214d7fb0df3a
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-769-2026-08-05-r2.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      Round 2, run as an explicit RE-AUDIT: the prompt listed all four
      round-1 findings, told the adversary not to assume they were closed
      and not to re-report them if they were, and explicitly authorised
      CLEAN. All four are gone from the verdict — the two taken into
      scope and the two dispositioned as residuals alike, including the
      interrupt-exemption one that the M1-764 characterisation test
      answers.

      The one new medium is real, and PRE-EXISTING rather than introduced
      here: an interrupted call surfaces as a plain
      LlmCallFailedException, which the breaker records as REACHABLE,
      un-tripping it on an endpoint nobody contacted. Both the
      classification and the recording catch are verbatim at the fork
      point and LlmHttpSupport is absent from this diff.

      FIXED IN-BRANCH (user direction, after the initial deferral to a new
      ticket was challenged and did not survive scrutiny — see the
      finding's disposition for why out_of_scope item 5 does not reach
      this change and why the blast-radius claim was false). Both
      recording arms of both breaker decorators now consult the caller's
      interrupt flag and release the probe instead of recording. The
      embedding twin is in scope with it: /stop cancels a chat turn that
      embeds, and the embedding providers share sendForBody. Two files
      new to the diff, 15 -> 17 against budget 18.

      Also narrowed in-branch: the round-1 remediation's general "a call
      that reports nothing returns the probe" claim, which was over-broad
      while the interrupted caller sat inside the family and untreated.

      The out-of-model item (shared endpoint-string breaker keying) is
      RESOLVED IN-BRANCH at the user's direction, as two distinct defects
      the auditor had merged:

      (a) KEYING. The map keyed by endpoint string alone, so the
      generative and embedding breakers collapsed into one under the
      SHIPPED DEFAULT on every profile (both base-urls are the local
      Ollama), not merely under an unusual operator config. Fixed by
      keying on (transport kind, endpoint). The deciding evidence is that
      the endpoint is NOT the failure domain: infochat.embeddings.timeout-ms
      defaults to 30s and is unprofiled while infochat.llm.chat.timeout-ms
      is 120s, and a read timeout classifies as transport-unreachable
      (LlmHttpSupport:153-156), so a live-but-slow backend times out
      embeddings while chat answers well inside its own budget — on a
      shared key those embedding timeouts denied chat with no HTTP attempt
      against an endpoint demonstrably answering. This makes the code match
      docs/spec/security.md §Failure handling ("the embedding endpoint is
      tracked separately") rather than amending the spec to match the code,
      and matches D54/D56 keeping embeddings off the LLM routing defaults.
      No spec edit, so no authorization for docs/spec/security.md is needed.

      (b) PROBE OWNERSHIP — the half this diff actually created.
      EndpointBreaker.releaseProbe gated on state == HALF_OPEN with no
      ownership check, so a call admitted while CLOSED that outlives a trip
      plus a full cooldown (a 120s chat budget against a 30s cooldown) and
      only then reports no evidence would hand back a probe a newer caller
      holds, admitting a second concurrent probe. Reachable within chat
      traffic alone; the shared key only widened who could do it. Fixed by
      recording the acquiring Thread as the probe holder and no-op'ing a
      non-holder's release. Thread identity is sound because acquire and
      release are one synchronous decorator invocation; a mismatch degrades
      to the pre-M1-769 no-op, which is the safe direction.

      Both are covered by new non-vacuous legs in LlmCircuitBreakerRegistryTest
      (llmAndEmbeddingsOnOneEndpointDoNotShareABreaker,
      releasingCallerCannotReturnAProbeItNeverHeld). No new files.
  - date: 2026-08-05
    verdict: CLEAN
    base: 3ffd1293a3ac97d199f764db41fb214d7fb0df3a
    head: working-tree
    verdict_file: docs/plan/m1/redteam/M1-769-2026-08-05-r3.md
    out_of_model_count: 0
    note: |
      Round 3, framed explicitly as a RE-AUDIT of the surface rounds 1
      and 2 produced — surface no prior round saw, since every finding
      from both was fixed in-branch rather than deferred. The adversary
      was given rounds 1-2 and their remediations, instructed to verify
      each against current bytes rather than assume closure, not to
      re-report genuinely-closed items, not to re-report the two
      user-accepted stated residuals, and was explicitly authorized to
      return CLEAN so a third round under momentum would not manufacture
      a finding to justify itself.

      New surface audited: the HALF-OPEN probe acquire/release lifecycle
      and its Thread-identity ownership token, the (TransportKind,
      endpoint) breaker keying, the interrupt-flag reads in both breaker
      decorators, and the SystemLlmBudget admission predicate.

      CLEAN, 0 findings, 0 out-of-model. The verdict file carries
      per-item closure evidence with file:line for all four prior
      remediations, twelve probed attack lines that did not pan out, and
      the explicitly non-examined scope. No disposition question was owed
      to the user this round — there were no findings to disposition.
      redteam_findings: above is left carrying rounds 1-2 verbatim rather
      than blanked to [], which would destroy the record of what was
      found and fixed.
clarity_check:
  date: 2026-08-05
  verdict: WARN
  warnings:
    - "lint: 6x FILES-SCOPE-COVERAGE (test_plan paths not in files_scope) — moot, the ticket declares no files_scope, only a numeric files_budget"
    - "self-check: census grep re-run live; the 5 disposition rows and the not-on-this-path list (DigestRenderer:608 in renderSummarySections, :453/:510 comments) match the returned sites"
    - "self-check: verified DigestRenderer:386/402/464/901 draws, DigestWorker:246/247 submit->renderSections, MeteredLlmProvider @APPLICATION, CircuitBreakingLlmProvider @APPLICATION+100 with both catch-and-record legs, and the (6, 7, 3) DigestRendererTest counts"
    - "self-check: marked the pass-1 OUTLINE FAILED section RESOLVED so it is not read as a live defect description"
  blockers: []
escalation_reason:
outline_file: target/m1-tick-outline-M1-769.md
---

# M1-769: Render-scoped exact LLM call accounting and per-call bound for the digest budget

## Context

M1-767 shipped the aggregate system LLM budget `docs/spec/security.md`
§Rate limiting names as "the backstop for digest cost" — the meter, the
config keys, the degrade-on-breach path and the breach signal. A
two-auditor `/redteam-multi` pass on that ticket returned 6 findings
(4 medium, 2 low, all DOS; verbatim in M1-767's `redteam_findings:`, full
record at `docs/plan/m1/redteam-multi/M1-767-2026-08-04/`). M1-767's
red-team refine fixed one of them, documented four as named approximation
legs, and deferred the rest here.

Two root causes survive into this ticket.

**The meter counts a proxy.** M1-767 draws in `DigestRenderer.renderSections`
— `recordCalls(shownClusters.size())`, `recordCalls(leadProse.size())`,
`recordCalls(1)` per roll-up, and a cache-probe-guarded draw in
`appendClusterProse`. None of those is the number of calls actually issued.
It over-counts where zero HTTP happens (unresolvable provider, breaker
OPEN, empty-prompt roll-up skip) and under-counts where a second call
happens (`CategoryRollupGenerator` calls `translationPipeline.run` after
`provider.generate`, so a non-`en` roll-up spends 2 and draws 1) or where
the probe races the pipeline. Both auditors reached this from opposite
directions, which is the argument that it is an altitude problem rather
than a list of bugs.

**The bound is admission-only.** `canStartRender()` is consulted once per
render. An admitted render's spend is then bounded only by the M1-763 slot
window, and FULL mode deliberately lifts the per-section cluster cap, so
the overshoot multiple is unbounded — `SystemLlmBudget`'s own class javadoc
concedes "A render admitted under the ceiling draws its actual calls as it
runs and may push the window over". With a single global counter and a
deterministic `DigestScheduler.staggerOffset`, the groups that lose are the
same ones every day.

## Why M1-767 could not just fix it

The obvious fix — draw where the call is made — breaks the scoping M1-767
gets for free. `renderSections` is reached by BOTH the scheduled route
(`DigestScheduler` → `DigestWorker.executeSlot`) and `/retry --digest`
(`DigestRetryService.fallbackRerun` → `DigestWorker.execute` →
`executeSlot`); the draws sit at that altitude because every generative
helper below it is shared far more widely:

| Helper | Also reached from |
|---|---|
| `SummaryProseGenerator.generate` | `SummaryCommandHandler:454`, `RetryCommandHandler:330` |
| `CategoryRollupGenerator.generateRollup` | `DigestRenderer.renderShortBody` → `/summary --short`, `/retry` |
| `TranslationPipeline.run` → `LlmTranslationProvider` | `ChatAgent`, `SavedCommandHandler`, `ClusterBlockRenderer`, `SummaryCommandHandler` |

So a draw moved down to the `provider.generate(...)` sites would meter the
user-initiated routes into the system budget — precisely the split the spec
and `LlmRateCap` / D47 exist to maintain. (M1-767's original "only
scheduled-route-only entry point" wording for this was FALSE — the
`/retry --digest` route reaches the same render — corrected in M1-767's
round-2 red-team disposition.) Correct scope and correct count
are only simultaneously reachable with a render-scoped sink, which is this
ticket.

## Why `complexity: high` / `risk: high`

The change threads a new context through three classes on the hot path of
`/summary`, `/retry`, chat and saves, and it makes a control that currently
only refuses ADMISSION able to refuse a call MID-render. A bug in the
scoping silently double-meters user commands; a bug in the per-call refusal
silently truncates digests. Both fail quietly, which is the profile that
earns a third review round.

## Census

LLM call sites reachable from the digest render. Re-runnable enumeration
(run from the repo root):

```
grep -rn "\.generate(\|translationPipeline\.run" --include=*.java \
  infochat-provider/src/main/java/app/zcat/infochat/provider/digest/ \
  infochat-provider/src/main/java/app/zcat/infochat/provider/summary/SummaryProseGenerator.java \
  infochat-provider/src/main/java/app/zcat/infochat/provider/translation/
```

Every site below must be disposed by the implementation — a missed one is
exactly the M1-767 failure this ticket exists to close. "Reaches HTTP" is the
property the exact meter must key on; a site that returns without an HTTP
attempt (unresolvable router, breaker OPEN, empty prompt, `en` short-circuit,
cache hit) must draw ZERO.

| # | Site | Reaches HTTP only when | M1-767 today |
|---|---|---|---|
| 1 | `SummaryProseGenerator:124` `provider.generate` — one per shown cluster | router resolves AND breaker closed | draws `shownClusters.size()` / `leadProse.size()` regardless |
| 2 | `DigestRenderer:899` `translationPipeline.run` — cluster prose | non-`en` AND cache miss | cache-probe-guarded draw (races the pipeline) |
| 3 | `CategoryRollupGenerator:196` `provider.generate` — one per category | non-empty prompt AND router resolves AND breaker closed | draws 1 regardless |
| 4 | `CategoryRollupGenerator:213` `translationPipeline.run` — roll-up prose | non-`en` AND cache miss | **not drawn at all** (the under-count leg) |
| 5 | `DigestRenderer:965` `translationPipeline.runForDisplayHit` — headlines | non-`en` AND cache miss, under the M1-756 per-render translation budget | deliberately excluded |

**Sites the raw grep returns that are NOT on this path** — enumerated so a
later pass does not re-add them (the pass-1 census did, and it was the
ticket's most dangerous defect):

- `DigestRenderer:608` `translationPipeline.run` is inside
  **`renderSummarySections`**, not `renderSections`. `renderSections` has
  exactly one caller (`DigestWorker:247`); `renderSummarySections` is called
  only from `SummaryCommandHandler:333/334/516/525` and
  `RetryCommandHandler:366/372` — the user-initiated routes `out_of_scope`
  item 2 forbids metering. Drawing there would be the exact scoping break this
  ticket exists to prevent. It is additionally mis-labelled as a section/category
  header leg: `DigestRenderer:789-795` states header lines are deterministic
  bundle strings "never routed through the translation pipeline".
- `DigestRenderer:453/510` are comment text, not call sites.

Disposition note for #5: it is a real call the scheduled render issues, so
acceptance item 1 ("does fire for every call that is") makes it draw under an
exact meter. It keeps its own M1-756 per-render cap — that cap bounds the
headline leg, this budget bounds the deployment; they compose, and neither
replaces the other.

Because the exact draw sits at the provider chokepoint rather than at named
call sites (see §Notes), completeness over this table is a *property of the
design*, not a checklist the implementer discharges by hand: any call reaching
the provider under the render-scoped binding draws, whether or not it appears
above. The table's remaining job is to state which legs must draw ZERO.

## Notes

- The `ScopedValue` precedent is `LlmCallContext.callWith` as used by
  `MeteredLlmProvider` — same module family, same synchronous call path.
  **Bind inside `renderSections` itself (or inside the submitted lambda) —
  NEVER around the `submit` call.** `DigestWorker:246` hands the render to
  `renderExecutor.submit(...)`, and a `ScopedValue` binding is not inherited
  across a plain executor submit, so a binding placed around `submit` is
  simply absent on the render thread and every draw silently vanishes — a
  failure that looks exactly like "the budget works" until the deployment is
  unmetered. Binding inside `renderSections` also covers both bound entry
  points (scheduled and `DigestRetryService.fallbackRerun` →
  `DigestWorker.execute` → `executeSlot`) at one site while leaving
  `renderSummarySections` and `renderShortBody` unbound, which is exactly
  what acceptance item 2 requires.
- **Draw altitude — resolved to the decorator, not the named call sites.**
  `LlmHttpSupport.executeJsonCall` is the single HTTP chokepoint for every
  `LlmProvider.generate` (both HTTP providers, every `ModelTask`, once per
  attempt including caller-driven retries), but it is package-private in
  `app.zcat.infochat.llm.impl` in `infochat-llm-adapter`, while
  `SystemLlmBudget` lives in `infochat-provider` — so the sink must be a new
  adapter-module abstraction that the provider module implements. The natural
  exact altitude is a third `LlmProvider` CDI decorator at
  `@Priority(Interceptor.Priority.APPLICATION + 200)`: that places it INSIDE
  `CircuitBreakingLlmProvider` (`APPLICATION + 100`), which is itself inside
  `MeteredLlmProvider` (`APPLICATION`), so a breaker-OPEN short-circuit
  never reaches the draw and the phantom-charge leg closes by construction
  rather than by a hand-maintained call-site list.
- **The mid-render refusal must not trip the real circuit breaker.**
  `CircuitBreakingLlmProvider:70-77` catches
  `LlmCallFailedException.ProviderUnreachableException` →
  `recordUnreachableForTask` and `LlmCallFailedException` →
  `recordReachableForTask`. A budget refusal thrown from the inner decorator
  passes through those catches on its way out, so if it is either type the
  budget's own refusals become endpoint evidence and a busy digest window
  trips the breaker against a healthy provider — converting a cost control
  into an availability outage. The refusal must therefore be a type outside
  that hierarchy. See acceptance item 8.
- **The M1-763 cancellation leg is NOT closed by decorator altitude alone.**
  An interrupted render's remaining `provider.generate` calls still pass the
  decorator before failing inside `http.send`, so the largest M1-767
  over-count leg survives unless explicitly dispositioned — e.g. skipping the
  draw when the calling thread already carries an interrupt, matching the
  M1-764 "interrupted caller sends no request" contract that
  `HttpProviderSharedPipelineTest.interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed`
  pins. Acceptance item 1 requires each M1-767 leg be shown closed or
  explicitly re-justified; this is the one that needs a deliberate choice.
- **Redteam round 1 (2026-08-05) dispositions.** The medium (probe burn)
  and the reserve finding are IN SCOPE — acceptance items 8 and 4
  respectively. The other two are accepted as STATED RESIDUALS:
  - *A refusal records `outcome=FAIL, model=unknown, usage=null` on
    `llm.calls.total`.* `MeteredLlmProvider` sits outside the budget
    decorator by the same priority argument acceptance item 1 rests on,
    and a call-counter-without-tokens gap is not a shape this diff
    invents: every breaker short-circuit, non-2xx and timeout already
    produces it, which that class's own javadoc records as a state
    consumers must already handle. The residual is that the metric alone
    does not distinguish a cost cap from an outage. The finding's second
    half — a wholly-refused render notifying nobody — is NOT a residual;
    acceptance item 4(b) fixes it, because such a group is now refused at
    admission, which is where the breach signal already fires.
  - *The interrupt exemption depends on `HttpClient.send` testing the
    interrupt flag before issuing the request.* The javadoc does not
    promise it, but the behaviour is not unowned:
    `HttpProviderSharedPipelineTest.interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed`
    (M1-764, listed under `test_plan.preserves`) pins zero requests
    served on BOTH platform and virtual threads against a real server,
    with a non-vacuity control leg, and exists precisely to fail on a
    future JDK change. The residual is a hypothetical non-HTTP provider;
    v1 ships none, and every provider it does ship shares that pipeline.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-769-render-scoped-exact-llm-call-accounting.md`

## OUTLINE FAILED (2026-08-05, plan-writer pass 1) — RESOLVED

Both defects below were fixed by the 2026-08-05 refine (commit `f94fe146`):
the `test_plan.modifies` entry now authorizes both `DigestRendererTest`
methods by name, and census row #5 was replaced plus an explicit
not-on-this-path list added. Retained verbatim as the audit record — read
it as history, not as a live description of the ticket above.

REASON: The ticket cannot be implemented without rewriting two pre-existing
test methods that it names nowhere, which is an unauthorized test modification
under `engineering-rules-verbatim.md` §8 ("the ticket body OR the commit
message MUST explicitly authorize the change… 'Authorized' means the ticket
says, in plain language, what the new test should assert and why").
`DigestRendererTest.renderSections_drawsSystemLlmBudgetAtLiveCallSites`
asserts `assertEquals(6, budget.callsInWindow())` and `assertEquals(7, …)`, and
`renderSections_enScope_drawsNoTranslationCalls` asserts `assertEquals(3, …)`.
All three numbers are produced by the four proxy draws at
`DigestRenderer:386/402/464/901` firing against test doubles —
`DistinctProseGenerator` and `RecordingCategoryRollupGenerator` are subclasses
that override `generate`/`generateRollup` and issue ZERO provider calls.
Acceptance item 1 ("the draw fires where an LLM call is really issued, so it
does not fire when zero HTTP request is made") and test_plan.adds item 1 (a
breaker-OPEN render "draws ZERO") make those four draws unshippable, so no
implementation satisfying the acceptance leaves those three assertions
reachable; the developer must retarget assertions that pin an M1-767
acceptance-item-5 security control, on a `security_relevant: true` /
`risk: high` ticket whose `round_cap` is 3 and where a
`TEST-INTEGRITY-CHECK: FAIL` is MANUAL-only, never developer-REWORK-able. The
ticket instead lists them implicitly under `test_plan.preserves: all tests
currently green on main`, so the ticket as written simultaneously demands they
stay green and requires the behaviour they pin to change.

A second, independent defect compounds it: the body's census is load-bearing
("Every site below must be disposed by the implementation") and row #5 is
factually wrong. `DigestRenderer:608` is inside `renderSummarySections`, not
the digest render; `renderSections` is called from exactly one site
(`DigestWorker:247`), while `renderSummarySections` is called only from
`SummaryCommandHandler:333/334/516/525` and `RetryCommandHandler:366/372` —
the user-initiated routes `out_of_scope` item 2 forbids metering. The row's
label ("section/category label") is wrong too: `DigestRenderer:789-795` states
header lines are "never routed through the translation pipeline". A developer
discharging the census literally would either add a draw on the `/summary`
path (the exact scoping break the ticket exists to prevent) or silently drop a
row the ticket says must be disposed.

SUGGESTED ESCALATION: refine

What a refine should add:

1. A `test_plan` entry authorizing the two `DigestRendererTest` methods BY
   NAME, stating what each must now assert and why.
2. Correct census row #5 — either delete it (`DigestRenderer:608` is not on
   the scheduled render's path) or replace it with the leg actually meant —
   and re-state the #5/#6 disposition note accordingly.
3. Ground-truth fixes: the "Why M1-767 could not just fix it" table cites
   `RetryCommandHandler:315` for `summaryProseGenerator.generate`, but the
   call site is line 330.

Two design findings from the API-surface audit that a refined ticket should
absorb, since they change the shape of the work and neither is visible from
the ticket text:

- `SystemLlmBudget` lives in `infochat-provider` while the only exact
  chokepoint (`LlmHttpSupport.executeJsonCall`/`sendForBody`) is
  package-private in `app.zcat.infochat.llm.impl` in `infochat-llm-adapter`,
  so the sink must be a new adapter-module abstraction. The natural exact
  altitude is a third `LlmProvider` CDI decorator at
  `@Priority(Interceptor.Priority.APPLICATION + 200)` — inside
  `CircuitBreakingLlmProvider` (APPLICATION+100), which itself sits inside
  `MeteredLlmProvider` (APPLICATION) — so a breaker-OPEN short-circuit never
  reaches the draw. A mid-render refusal thrown from there must be a type that
  is neither `LlmCallFailedException` nor its `ProviderUnreachableException`
  subtype, or `CircuitBreakingLlmProvider:70-77` will record it as endpoint
  evidence and trip the real breaker on a budget refusal.
- `DigestWorker:246` uses `renderExecutor.submit(...)`, and a `ScopedValue`
  binding is NOT inherited across a plain executor submit, so the sink must be
  bound inside the submitted lambda or inside `renderSections` itself — not
  around the `submit` call. Binding inside `renderSections` covers both the
  scheduled route and `DigestRetryService.fallbackRerun` →
  `DigestWorker.execute` → `executeSlot` with one site, and leaves
  `renderSummarySections`/`renderShortBody` unbound, which is what acceptance
  item 2 requires.
- Separately: M1-767's largest named over-count leg (the M1-763 cancellation
  leg) is NOT closed by drawing at decorator altitude — an interrupted
  render's remaining `provider.generate` calls still pass the decorator before
  failing inside `http.send`. Closing it needs an explicit disposition (e.g.
  skipping the draw when the calling thread is already interrupted, matching
  the M1-764 "interrupted caller sends no request" contract), and acceptance
  item 1 demands each M1-767 leg be "shown closed or explicitly re-justified".

