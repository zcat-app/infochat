---
id: M1-638
title: "Model the interruptible turn lifecycle in one registry"
status: done
created: 2026-07-16
last_updated: 2026-07-17
blocked_by: []
files_budget: 18
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The per-user cross-scope concurrency CAP (M1-636). This ticket builds the
    lifecycle model M1-636's cap can be derived from — a count of a sender's
    non-terminal turns — but adds no cap, no new reject arm and no new bundle
    key. M1-636 stays a separate ticket layered on top.
  - >-
    Raising, lowering, auto-tuning or documenting
    infochat.chat.dispatch.max-concurrency (M1-636 owns the bound and its
    operator-facing documentation). The pool's size, its queue depth and its
    CallerRunsPolicy saturation path are untouched.
  - >-
    Queue ORDER and per-user fairness. 06-messaging.md §6.3 defers a fair
    scheduler and that deferral STANDS: arrival order is unchanged, and a
    cancelled turn is skipped in place rather than reordering its neighbours.
  - >-
    The M1-635 submit-time acknowledgement's timing or appearance. An ack
    appears if and only if InterruptibleDispatcher.wouldQueue() is true at
    submit, with the same text and the same purpose-minted operationId, exactly
    as shipped. This ticket may reuse that id as the turn's identity; it must
    not change when or whether the ack is published.
  - >-
    The in-flight guard's OBSERVABLE semantics: at most one running
    interruptible request per (user, scope), rejected on the WORKER with
    BundleKeys.ERROR_CHAT_IN_FLIGHT (commands.md §Surface conventions, the
    reject M1-634 exists to make reachable). This ticket relocates where
    lifecycle state lives and widens who can cancel it; it must not change when
    the reject fires, what it says, or which requests it admits.
  - >-
    Non-interruptible dispatch (periodic digests, ingest, mutating commands,
    /retry --digest) and the CallerRunsPolicy inline path. Neither queues, so
    neither is in this ticket's subject matter.
  - >-
    The pending-destructive-command confirm arm of /stop (ConfirmStateService,
    REPLY_STOP_CONFIRM_CANCELLED / REPLY_STOP_BOTH_CANCELLED). Its semantics
    are unchanged; only the in-flight arm's reach extends to queued turns.
acceptance:
  - >-
    A test saturates the InterruptibleDispatcher pool so a further
    same-(user, scope) request is QUEUED rather than run, drives /stop from that
    user while the turn is still queued, and asserts the queued turn never
    reaches the LLM (TestLlmProvider.callCount() unchanged across the release)
    and its M1-635 acknowledgement placeholder is finalized with the D35 stopped
    terminal (BundleKeys.PROGRESS_STOPPED) rather than left as a stranded
    "working on it".
  - >-
    A test asserts /stop issued by user A never cancels a QUEUED turn owned by
    user B in another scope: B's turn still runs and finalizes with its own
    reply, and A receives the no-op guidance. Cancellation stays keyed
    per-(user, scope) at every lifecycle state.
  - >-
    A test asserts the pre-existing worker-held cancellation path is
    unregressed — /stop against a turn already inside its LLM call still returns
    BundleKeys.REPLY_STOP_CANCELLED and finalizes the turn with the stopped
    terminal (the M1-634 InboundRouterConcurrentDispatchIT behaviour), and /stop
    with genuinely nothing in progress still returns BundleKeys.REPLY_STOP_NOOP.
  - >-
    A test asserts a cancelled turn produces EXACTLY ONE terminal message — the
    placeholder is finalized in place and no second bubble is sent. Pins the
    single-publisher invariant that StageProgressNotifier.terminate's
    no-state branch (:333, a fresh send) makes load-bearing.
  - >-
    InboundRouterConcurrentDispatchIT passes UNMODIFIED: a second same-(user,
    scope) interruptible request is still admitted to a worker and still
    rejected there with ERROR_CHAT_IN_FLIGHT. The guard's timing and text are
    unchanged by the lifecycle relocation.
  - >-
    Interruptible-turn lifecycle state lives in ONE registry keyed by turn
    identity: the diff introduces no second per-turn admission or cancellation
    map alongside it, and InFlightTracker's thread-bound CancellationHandle is
    reachable only from a turn that has attached a worker thread.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/QueuedTurnCancellationIT.java
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/InFlightTrackerTest.java
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/CancellationServiceTest.java
  preserves:
    - all tests currently green on main
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/InboundRouterConcurrentDispatchIT.java
      passes unmodified (pinned by an acceptance item)
spec_refs:
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D31
  - D35
outline_file: target/m1-tick-outline-M1-638.md
redteam_findings: []
redteam_audits:
  - date: 2026-07-17
    verdict: CLEAN
    base: "7765d023 (fork point; implementation uncommitted in working tree at audit time)"
    head: "working tree of m1/M1-638-model-the-interruptible-turn-l"
    verdict_file: docs/plan/m1/redteam/M1-638-2026-07-17.md
    out_of_model_count: 0
    note: >-
      Pre-commit audit (--in-progress form) after round-1 APPROVE, zero
      findings and zero out-of-model items. Cross-user cancellation isolation
      (the sweep is keyed per-(user, scope) at every lifecycle state) and the
      M1-634 monitor-gate remediation (handle stays thread-bound, unreachable
      before worker attach) both survive the registry remodel.
reviews:
  - round: 1
    date: 2026-07-17
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 12
      added: 914
      removed: 76
clarity_check:
  date: 2026-07-16
  verdict: PASS
  warnings:
    - >-
      TEST-CHANGES-AUTHORIZED nuance: CancellationServiceTest is named only in
      the §Notes files_budget justification, without InFlightTrackerTest's
      explicit "expected to change / additions-only" authorization sentence.
      The blanket "never redefine, additions only" policy covers it; populate
      test_plan.modifies for real once the §Notes open decision is resolved to
      remove ambiguity for the code reviewer.
    - >-
      Two design decisions are intentionally left open for /m1-tick start (the
      handlers' tryAcquire contract; who publishes the stopped terminal for a
      cancelled queued turn). Not a clarity defect given complexity:high, but
      the outline must resolve both before implementation — acceptance items
      1, 4 and 6 depend on which fork is taken.
  blockers: []
---

# M1-638: Model the interruptible turn lifecycle in one registry

## Context

`/stop` cannot reach an interruptible turn that has not started yet. The
cancellation target is captured on the worker —
`InFlightTracker.tryAcquire` takes `Thread.currentThread()`
(`InFlightTracker.java:103-108`) — so a turn sitting in the
`InterruptibleDispatcher` pool queue holds no slot,
`CancellationService.cancel` finds nothing (`CancellationService.java:47-52`),
and the sender gets `REPLY_STOP_NOOP` while their queued turn later dequeues
and runs the full LLM call. D35 says `/stop` cancels "immediately"; today that
is true only for turns lucky enough to have reached a worker.

The reason the model cannot express this is historical and benign: before
M1-634 there was no queue, so a thread-keyed handle was the *correct* model —
the domain had no pre-worker phase. M1-634 (@`f7c598b6`) created that phase by
moving the D35 class onto a bounded pool, and M1-635 gave the queued sender an
acknowledgement, making the contradiction visible ("working on it…" → `/stop` →
"nothing in progress" → the answer arrives anyway). The M1-635 red team
(`docs/plan/m1/redteam/M1-635-2026-07-16.md`, out-of-model item 3) and the
M1-635 outline (risk 2) both flagged it and correctly classed it as a D35
design question rather than an M1-635 defect.

**This ticket answers that design question by fixing the model rather than
bolting state beside it.** A turn's identity and lifecycle become one object,
born at submit and living to its terminal; the in-flight guard becomes a
*derived* property of that lifecycle ("at most one RUNNING per (user, scope)")
rather than a separate map that only exists once a thread does. Admission stays
exactly where it is — on the worker, same reject, same text.

The alternative shape — a second, pre-worker cancellation registry alongside
`InFlightTracker` — was considered and rejected: see §Notes.

## Acceptance

See the YAML `acceptance:` list. In prose: `/stop` must cancel a turn that is
still queued (never reaching the LLM, its M1-635 placeholder finalized with the
D35 stopped terminal, exactly one terminal message). Cancellation stays keyed
per-(user, scope) at every state, so one user's `/stop` can never cancel
another's. The worker-held cancel path and the genuine no-op path stay
unregressed, and `InboundRouterConcurrentDispatchIT` passes **unmodified** —
the guard's timing and text survive the relocation. Lifecycle state lives in one
registry, with no second per-turn map introduced. `mvn -pl infochat-provider -am
verify` is green.

## Out-of-scope

Covered in the YAML `out_of_scope:`. The load-bearing ones: this ticket adds no
per-user cap (M1-636), does not touch the pool bound or queue order, does not
change when the M1-635 ack appears, and — most importantly — does not change the
in-flight guard's observable behaviour. It moves where lifecycle state lives and
widens who can cancel; a diff that changes which requests are admitted, or when
the reject fires, has left scope.

No pre-existing test's expected behaviour is redefined.
`InboundRouterConcurrentDispatchIT` is pinned as passing unmodified.
`InFlightTrackerTest` is expected to change (it tests the structure being
remodelled), and any such change must be an ADDITION of lifecycle-state
coverage, never a weakening of an existing assertion.

## Notes

**Why this instead of a pre-worker registry beside `InFlightTracker`.** The
bolt-on shape works and is smaller, but it puts the codebase on a trajectory
already visible in the backlog: `InFlightTracker` (per-scope) + a queued-turn
registry (this bug) + a per-user counter — M1-636's `out_of_scope` says in as
many words that it "adds a second, coarser bound alongside it". Three structures
would then model admission and lifecycle for one turn, with three keyings that
must stay mirrored or `/stop` silently covers one phase and not another. M1-636
also currently designs *around* the limitation this ticket removes: its notes
state "the check must run on the worker … `tryAcquire` captures
`Thread.currentThread()`", and accept that "a rejected request therefore still
consumes a pool slot to discover its own rejection." Both M1-636 and M1-637 are
unstarted, so nothing merged is discarded by deciding this now — M1-634's pool
and M1-635's ack both stand unchanged. This is the cheapest moment this decision
will ever be available.

**The design constraint that makes it tractable: separate identity from
admission.** Conflating them is what makes the naive "extend `tryAcquire` with a
submit-time reservation" shape fail — a reservation under the same key that the
handler's own `tryAcquire` later probes makes the turn reject *itself* with
"request already in progress", forcing a token through `dispatchSlashOrChat`
into all three handlers. Keep them apart:
- *Identity/lifecycle* — one object per interruptible dispatch, created at
  submit, carrying the operationId M1-635 already purpose-mints there, state
  `QUEUED → RUNNING → TERMINAL | CANCELLED`, worker thread attached at `RUNNING`.
- *Admission* — unchanged: at most one `RUNNING` per (user, scope), decided on
  the worker at the existing `tryAcquire` call, same reject, same timing.

**A consequence worth knowing (not pinned).** Because the state transition to
`RUNNING` *is* the thread attach, the pre-existing window between a worker
starting the stage and reaching `tryAcquire` — during which today's `/stop`
finds no handle and answers `REPLY_STOP_NOOP` — closes for free. A bolt-on
registry cannot close it (its entry goes RUNNING at stage start, before the
attach), and would leave that residual permanently.

**Open decision — resolve at `/m1-tick start`.**
- *Do the three handlers keep calling `tryAcquire`* (adopting the submit-created
  turn found via `InboundContext`, signature and semantics unchanged — which
  keeps `ChatAgentTest`, the five tool tests and the handler tests untouched and
  holds the blast radius near the low end of the budget), *or do they read the
  turn from the context directly* (cleaner, but it changes three handlers and
  their tests)? The former is the lean; the latter must not be chosen for
  tidiness alone.
- *Who publishes the stopped terminal for a cancelled queued turn* — the worker
  on dequeue (honours the ownership invariant `publishQueuedPlaceholder`'s
  javadoc states explicitly, `StageProgressNotifier.java:200-220`: "Lifecycle
  ownership transfers to the worker"; needs no notifier API change; costs a
  placeholder that flips to "Stopped." only when a worker frees, up to one LLM
  turn later), or `/stop` immediately (instant flip; needs a package-private
  terminal widened across packages, and creates a second lifecycle owner). Lean:
  the worker. The lag is pre-existing M1-635 queued behaviour, so deferring
  regresses nothing.

**Single-publisher is mandatory, not stylistic.**
`StageProgressNotifier.terminate` removes the state and finalizes in place, but
its no-state branch (`:333`) performs a **fresh send** — so two terminals for one
operation are not an idempotent no-op, they are a duplicate bubble. Whatever
resolves the dequeue/cancel race must make exactly one side publish. A CAS on the
turn's state is the obvious linearization point: whoever loses publishes nothing.

**Keying must be per-turn, not per-scope.** Two turns from the same (user, scope)
can both be queued (the second later rejects on the worker). A `ScopeKey →
entry` map lets the second submit clobber the first, leaving the first
uncancellable and the second's terminal removed by the wrong `finally`. Key by
turn identity and index by scope. `InFlightTracker.ScopeKey`
(`InFlightTracker.java:17`) is package-private in `provider.chat`, so a registry
in that same package reuses that exact record rather than declaring a parallel
one — which is the anti-drift measure, and the reason for the placement.

**`InFlightTracker` has THREE slot owners, not two.** `ChatAgent.java:226/283`,
`SummaryCommandHandler.java:278/398` and — missed by M1-637's notes —
`RetryCommandHandler.java:189/262`. `/retry` without `--digest` is interruptible
(`InboundRouter.isInterruptible`), so it is a queued-turn path too. Any design
that changes the acquire contract must reckon with all three.

**Handle the redteamed gate with care.** `CancellationHandle`'s
`interruptWorker` / `releaseWorker` monitor gate (`InFlightTracker.java:59-79`)
is the M1-634 red-team remediation dated 2026-07-16 — it makes check-then-
interrupt and close-then-clear mutually atomic so a `/stop` cannot land an
interrupt on a recycled pool thread serving a different user. A threadless
lifecycle state must not weaken it: the handle should remain thread-bound and
simply not exist until a thread attaches (an acceptance item pins this), rather
than gaining a nullable thread.

**Proposed dispositions (NOT applied — the operator decides).**
- M1-637 is subsumed: its three acceptance items are carried verbatim into this
  ticket's first three. If this ticket is taken, M1-637 becomes
  `status: abandoned`, `abandoned_reason: superseded`, `deferred_on: M1-638`.
- M1-636 gains `blocked_by: [M1-638]`, and its "second, coarser bound alongside
  it" becomes a query over this registry — dropping its own release-discipline
  hazard (its notes warn a naive counter "locks them out permanently") and
  letting its cap reject before a pool slot is spent, which its notes currently
  accept as a wart.
- If instead the bolt-on is preferred, this ticket is abandoned and M1-637 runs
  as filed with Option A from its notes.

**`files_budget: 18` justification.** The lean design (handlers keep
`tryAcquire`) touches ~10: the registry, `InboundRouter`, `CancellationService`,
possibly `InterruptibleDispatcher`, `InFlightTrackerTest`,
`CancellationServiceTest`, and the new IT. The budget carries headroom for the
handler-side variant without licensing a sweep: 17 files reference
`InFlightTracker` today, and a diff approaching that number has chosen the wrong
shape and should escalate rather than spend the budget.

- Adjacent code: `InboundRouter.java:842-871` (the interruptible fork, the
  `wouldQueue` ack, the two stage variants) and `:913-921`
  (`runQueuedDispatchStage` — the seeded-operationId seam).
- Adjacent code: `InboundRouter.java:1079-1087` — the existing stopped terminal
  for a `/stop`-cancelled chat turn; the queued skip path should land on the same
  `progressNotifier.complete(scope, PROGRESS_STOPPED)` shape.
- Adjacent code: `CancellationService.java:47-88` — the single cancellation seam.
  Folding queued-state cancellation in here keeps `StopCommandHandler`'s three
  reply arms (and its untouched confirm arm) working with no change.
- Existing test: `InboundRouterQueuedFeedbackIT` (M1-635) builds saturation
  scenarios; `InboundRouterConcurrentDispatchIT` (M1-634) drives the worker-held
  `/stop`. Reuse `DispatchAwaits` + `inFlightTaskCount()` as await points, and
  note both classes seed users via `RegisteredContactSet.markRegistered` so the
  shared stranger rate bucket cannot silently drop test traffic.
