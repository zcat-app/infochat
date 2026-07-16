---
id: M1-637
title: "Queued interruptible turn is not cancellable by /stop"
status: pending
created: 2026-07-16
last_updated: 2026-07-16
blocked_by: [M1-635]
files_budget: 12
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Raising, lowering or auto-tuning infochat.chat.dispatch.max-concurrency
    (M1-636 owns the bound and its operator-facing documentation). This
    ticket changes only whether a QUEUED turn can be cancelled, never how
    many workers exist.
  - >-
    Queue ORDER, per-user fairness, and any cross-scope admission cap
    (M1-636). A cancelled queued turn is removed or skipped; the ordering
    of the turns around it is untouched.
  - >-
    The M1-635 submit-time acknowledgement itself. The ack stays exactly as
    shipped — this ticket makes the wait it advertises cancellable, and must
    not change when or whether the ack is published.
  - >-
    Non-interruptible dispatch (periodic digests, ingest, mutating commands,
    /retry --digest) and the CallerRunsPolicy inline path. Neither ever
    queues, so neither is in this ticket's subject matter.
  - >-
    The pending-destructive-command confirm arm of /stop
    (ConfirmStateService, REPLY_STOP_CONFIRM_CANCELLED /
    REPLY_STOP_BOTH_CANCELLED). Its semantics are unchanged; only the
    in-flight arm's reach extends to queued turns.
acceptance:
  - >-
    A test saturates the InterruptibleDispatcher pool so a further
    same-(user, scope) request is QUEUED rather than run, drives /stop from
    that user while the turn is still queued, and asserts the queued turn
    never reaches the LLM (TestLlmProvider.callCount() unchanged across the
    release) and its M1-635 acknowledgement placeholder is finalized with
    the D35 stopped terminal (BundleKeys.PROGRESS_STOPPED) rather than left
    as a stranded "working on it".
  - >-
    A test asserts /stop issued by user A never cancels a QUEUED turn owned
    by user B in another scope: B's turn still runs and finalizes with its
    own reply, and A receives the no-op guidance. Cancellation stays keyed
    per-(user, scope) exactly as the worker-held path is.
  - >-
    A test asserts the pre-existing worker-held cancellation path is
    unregressed — /stop against a turn already inside its LLM call still
    returns BundleKeys.REPLY_STOP_CANCELLED and finalizes the turn with the
    stopped terminal (the M1-634 InboundRouterConcurrentDispatchIT
    behaviour), and /stop with genuinely nothing in progress still returns
    BundleKeys.REPLY_STOP_NOOP.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    # - infochat-provider/src/test/java/.../messaging/QueuedTurnCancellationIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D31
  - D35
---

# M1-637: Queued interruptible turn is not cancellable by /stop

## Context

M1-634 (@`f7c598b6`) moved the D35 interruptible class onto
`InterruptibleDispatcher`'s bounded pool, and M1-635 gave a queued sender a
submit-time acknowledgement so the wait is no longer silent. Both left one
D35 promise unmet at the queue: **`/stop` cannot reach a turn that has not
started yet.**

The cause is where the cancellation target is captured.
`InFlightTracker.tryAcquire` captures `Thread.currentThread()` as the
cancellation target (`InFlightTracker.java:99-103`), and it is called from
the worker — `ChatAgent.handleTurn` (`ChatAgent.java:225-226`) and
`SummaryCommandHandler` (`:278`). A turn sitting in the pool's
`ArrayBlockingQueue` therefore holds **no slot**, so
`StopCommandHandler`'s `cancellationService.cancel(...)`
(`StopCommandHandler.java:74`) finds nothing, returns `false`, and the
sender gets `REPLY_STOP_NOOP` — "nothing in progress" — while their queued
turn later dequeues and runs the full LLM call anyway.

M1-635 made this contradiction user-visible rather than causing it: the
sender now reads "working on it…", types `/stop`, is told nothing is in
progress, and then watches the answer arrive. Before M1-635 the same thing
happened, but in silence. The 2026-07-16 red-team audit of M1-635
(`docs/plan/m1/redteam/M1-635-2026-07-16.md`, out-of-model item 3) and the
M1-635 implementation outline (risk 2) flagged this independently; both
classed it as a pre-existing D35 scope question rather than an M1-635
defect, which is why it is filed here instead of escalated there.

The cost is bounded — the queued turn draws the sender's own `LlmRateCap`
token, so this is a UX-contract gap, not a resource-exhaustion one. But D35
says `/stop` cancels "immediately", and today that is true only for turns
lucky enough to have reached a worker.

## Acceptance

See the YAML `acceptance:` list. In prose: `/stop` must cancel a turn that
is still queued — the turn must never reach the LLM, and its M1-635
acknowledgement placeholder must finalize with the D35 stopped terminal
rather than strand. Cancellation must stay keyed per-(user, scope), so one
user's `/stop` can never cancel another's queued turn. The existing
worker-held cancel path and the genuine no-op path must both be
unregressed. `mvn -pl infochat-provider -am verify` is green.

## Out-of-scope

Covered in the YAML `out_of_scope:`. The pool bound (M1-636), queue order
and fairness (M1-636), the M1-635 acknowledgement mechanism, the
non-interruptible and CallerRuns paths, and `/stop`'s confirm arm are all
untouched.

## Notes

**The design is open; resolve it at `/m1-tick start`.** Two shapes are
visible from the M1-635 work, and both must reckon with one fact: the
cancellation target cannot be captured at submit time, because the thread
that will run the turn does not exist yet (constraint 2 in M1-635's notes).

**Option A — pre-worker cancellation registry.** Register the turn at
submit time in a per-(user, scope) structure the worker consults before
acquiring its slot; `/stop` marks the entry cancelled, and the dequeuing
worker sees the mark and skips straight to the stopped terminal. Keeps
`InFlightTracker`'s worker-thread capture untouched. The subtlety is the
dequeue/cancel race: the worker may acquire its slot at the same instant
`/stop` marks the entry, so mark-then-check ordering must be arranged such
that neither a double-terminal nor a missed cancel is possible.

**Option B — extend `InFlightTracker` with a pre-thread slot state.** Let
`tryAcquire` be split into a submit-time reservation (no thread) and a
worker-time attach (binds `Thread.currentThread()`). More invasive, and it
touches the guard that M1-634 exists to make reachable — a mis-step here
could re-break the "request already in progress" reject the spec's
§Surface conventions pins.

**Lean: Option A** — it leaves the in-flight guard and its M1-634 contract
alone, and confines the new state to the submit path M1-635 already
established.

`security_relevant: true` is deliberate: cancellation targeting is exactly
the per-(user, scope) isolation the spec commits to, and this ticket adds a
new keyed structure on the submit path. A mis-keyed registry would let one
user cancel another's turn — the second acceptance item pins that, and the
`/redteam` pass should confirm it.

- Adjacent code: `InFlightTracker.java:99-103` (the worker-thread capture
  that creates the gap) and `StopCommandHandler.java:60-90` (the three
  reply arms: cancelled / noop / both-cancelled).
- Adjacent code: `InboundRouter.java` queued branch + `runQueuedDispatchStage`
  (M1-635) — the submit-time seam a registry would hook into, and the
  operationId seeding the stopped terminal needs to finalize the right
  placeholder.
- Existing test: `InboundRouterQueuedFeedbackIT` (M1-635) builds saturation
  scenarios and `InboundRouterConcurrentDispatchIT` (M1-634) drives the
  worker-held `/stop`; reuse `DispatchAwaits` + `inFlightTaskCount()` as
  the await points, and note both classes seed users via
  `RegisteredContactSet.markRegistered` so the shared stranger rate bucket
  cannot silently drop test traffic.
- `blocked_by: M1-635` because this builds directly on the ack placeholder
  and the seeded operationId that M1-635 introduces.
