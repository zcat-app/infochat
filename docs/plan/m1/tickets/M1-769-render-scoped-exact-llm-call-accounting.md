---
id: M1-769
title: "Render-scoped exact LLM call accounting and per-call bound for the digest budget"
status: pending
created: 2026-08-04
last_updated: 2026-08-04
blocked_by: [M1-767]
files_budget: 16
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
    A PER-GROUP SHARE SO ONE GROUP CANNOT STARVE THE DEPLOYMENT. One
    group's post volume (any non-banned user can inflate it via
    `/add-source` + subscription) must not consume the whole aggregate
    ceiling. `DigestScheduler.staggerOffset` is a deterministic
    `groupId hash % windowWidthMinutes`, so under a purely global ceiling
    the SAME late-firing groups degrade every day — the fairness
    requirement is about that stable starvation set, not just the total.
    The shape of the share (reservation, per-group sub-cap, or
    round-robin admission) is the ticket's design call; the property is
    that a late slot is not systematically starved by an early one.
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
escalation_reason:
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
| `SummaryProseGenerator.generate` | `SummaryCommandHandler:454`, `RetryCommandHandler:315` |
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

## Notes

- The `ScopedValue` precedent is `LlmCallContext.callWith` as used by
  `MeteredLlmProvider` — same module family, same synchronous call path.
  The digest render is synchronous on the virtual thread `DigestWorker:240`
  submits, so a value bound inside `renderSections` is still bound at
  `LlmHttpSupport.sendForBody`.
- `LlmHttpSupport.executeJsonCall` is the single HTTP chokepoint for every
  `LlmProvider.generate` (both HTTP providers, every `ModelTask`, once per
  attempt including caller-driven retries) and is NOT reached on the
  breaker-open path. It lives in `infochat-llm-adapter`. Whether the draw
  sits there or at the three in-provider `provider.generate(...)` sites is
  the ticket's design call — the chokepoint is exact but cross-module, the
  three sites stay in `infochat-provider` but still charge a breaker-open
  short-circuit unless the sink is read below the breaker decorator.
- Pre-flight: `python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-769-render-scoped-exact-llm-call-accounting.md`
</content>
</invoke>
