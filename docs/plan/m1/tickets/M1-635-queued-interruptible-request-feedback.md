---
id: M1-635
title: "Queued interruptible request gives the sender no feedback until a worker frees"
status: done
created: 2026-07-16
last_updated: 2026-07-16
blocked_by: []
files_budget: 16
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Raising, lowering or auto-tuning infochat.chat.dispatch.max-concurrency, and
    documenting that knob (M1-636 owns the operator-facing concurrency-bounds
    documentation). This ticket changes only WHEN the sender first hears back,
    never how many workers exist.
  - >-
    The CallerRunsPolicy saturation path and the transport's bounded inbound
    queue (M1-634 / M1-224). A caller-runs submission runs inline and is never
    queued, so it is out of this ticket's subject matter by construction.
  - >-
    Per-user fairness or any cross-scope admission cap (M1-636). Queue ORDER is
    untouched; only the sender's visibility into it changes.
  - >-
    Non-interruptible dispatch (periodic digests, ingest, mutating commands,
    /retry --digest). Those never reach InterruptibleDispatcher.
acceptance:
  - >-
    A test drives an interruptible request through InboundRouter.onMessage while
    the InterruptibleDispatcher pool is saturated, such that the request is
    QUEUED rather than immediately run, and asserts the sender receives an
    outbound acknowledgement BEFORE any worker begins the stage.
  - >-
    A test asserts the queued acknowledgement and the worker's own progress
    placeholder do NOT both render as separate outbound messages for one turn —
    exactly one placeholder lifecycle per request (no double-send), consistent
    with the M1-607 self-delivering-handler contract.
  - >-
    A test asserts an in-flight-guard REJECT (a second same-(user, scope)
    request) still terminates in exactly one outbound message to the sender —
    the "request already in progress" guidance — with no orphaned placeholder
    left behind by the acknowledgement added here.
  - mvn -pl infochat-provider -am verify is green
test_plan:
  adds:
    # - infochat-provider/src/test/java/.../messaging/InboundRouterQueuedFeedbackIT.java
  modifies:
    # - infochat-provider/src/test/java/.../messaging/InboundRouterConcurrentDispatchIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D31
  - D35
  - D43
clarity_check:
  date: 2026-07-16
  verdict: WARN
  warnings:
    - >-
      risk: medium is calibrated on the low side given the ticket's own
      admission that this touches the isolation surface M1-634's red-team audit
      scrutinised, and its recommendation of a follow-up /redteam pass; consider
      risk: high.
    - >-
      The commented-out test_plan.modifies entry naming
      InboundRouterConcurrentDispatchIT.java is ambiguous scaffolding — if the
      chosen design changes an EXISTING test method's assertions in that file
      (rather than only adding new methods that reuse its fixtures), that
      modification is not yet explicitly authorized with a stated new expected
      behavior. Clarify add-only vs modify-existing before touching that file.
  blockers: []
outline_file: target/m1-tick-outline-M1-635.md
reviews:
  - round: 1
    date: 2026-07-16
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 7
      added: 560
      removed: 10
redteam_findings: []
redteam_audits:
  - date: 2026-07-16
    verdict: CLEAN
    base: main (eb5e2358)
    head: working-tree@m1/M1-635-queued-interruptible-request-feedback (uncommitted impl; branch tip == main)
    verdict_file: docs/plan/m1/redteam/M1-635-2026-07-16.md
    out_of_model_count: 3
    note: >-
      Pre-commit --in-progress audit of the working-tree diff vs the fork
      point (branch-form range would have been empty — uncommitted impl).
      CLEAN across all severities; the hop-isolation surface (purpose-minted
      operationId seeded across the dispatch hop) held under adversarial
      review. Three out-of-model advisories recorded in the verdict file
      for follow-up consideration; none block commit.
---

# M1-635: Queued interruptible request gives the sender no feedback until a worker frees

## Context

M1-634 (@`f7c598b6`) offloaded the D35 interruptible class — chat-mode turns,
user-issued `/summary`, user-issued `/retry` re-roll — onto
`InterruptibleDispatcher`'s bounded worker pool
(`infochat.chat.dispatch.max-concurrency`, default 4, queue depth 4). That fixed
the M1-629 serialization, but it introduced a wait state nothing reports on: a
request submitted while all workers are busy sits in the pool's
`ArrayBlockingQueue` in **complete silence**.

The reason is placement. `InboundRouter.java:843` submits and returns without
sending anything; the `STARTED` placeholder is published at
`SummaryCommandHandler.java:299` — *inside* the stage, on the worker — so it
cannot fire until a worker picks the task up. The sender sees no placeholder, no
typing indicator, nothing at all.

This bites hardest on chat, the class M1-634 primarily serves
(`InboundRouter.java:888` — every non-slash message is interruptible). A chat
turn holds its slot for a multi-turn tool loop (`ChatAgent.java:63`,
`MAX_TOOL_ITERATIONS = 10`, each round-trip bounded by
`infochat.llm.chat.timeout-ms=90000`), so the queued sender's silence can run
long. Silence reads as a broken bot, and the user's natural response — send it
again — costs another `LlmRateCap` token and another pool task.

The bound is not the problem and this ticket does not touch it. The sender's
blindness to it is.

## Acceptance

See the YAML `acceptance:` list. In prose: a request that is queued rather than
immediately run must produce an outbound acknowledgement to its sender before a
worker starts the stage; one turn must still yield exactly one placeholder
lifecycle (no double-send); and the in-flight-guard reject path must still
terminate in exactly one message with no orphaned placeholder. `mvn -pl
infochat-provider -am verify` is green.

Note the `-am`: `ProgressStage` lives in `infochat-messaging-adapter`, and `-pl
infochat-provider` **without** `-am` resolves a stale adapter from `~/.m2` (the
M1-620 gotcha). If the chosen design adds a `ProgressStage` value, `-am` is
mandatory rather than merely advisable.

## Out-of-scope

Covered in the YAML `out_of_scope:`. The knob itself, the caller-runs saturation
path, cross-scope fairness (M1-636), and non-interruptible dispatch are all
untouched. The pool's size and queue depth are inputs to this ticket, never
outputs.

## Notes

**Two designs are live; the implementer must resolve this at
`/m1-tick start`.** Both are constrained by two facts established while scoping:

*Constraint 1 — the operationId does not cross the hop.* M1-611 keys
per-operation progress state on `InboundContext.operationId`, and
`InterruptibleDispatcher.runStage` **deliberately does not copy** the submitting
context's operationId — the worker self-mints its own (see the class javadoc,
"deliberately NOT copied"). So an acknowledgement published on the transport
thread and a `STARTED` published on the worker are two different operations, and
a naive pre-dispatch publish yields **two placeholders**, not one edited in
place.

*Constraint 2 — the guard runs on the worker.* `InFlightTracker.tryAcquire`
captures `Thread.currentThread()` as the cancellation target, so the
already-in-progress check cannot move to the transport thread. Anything
published at submit time is therefore published *before* we know whether the
request will be rejected — and the reject at
`SummaryCommandHandler.java:280` is currently a plain `reply()`, not a
ProgressNotifier terminal. Whichever design wins must reconcile that path, which
is what the third acceptance item pins.

**Option A — publish `STARTED` at submit time.** Move the existing publish onto
the transport thread and seed the turn's operationId across the hop as a fourth
scalar (purpose-minted per turn, *not* a read of the submitting context — so
M1-634's isolation contract holds: nothing reads the submitting context on the
worker). Provider-only, no new enum value, no new bundle key, no cs twin. Costs:
"working on it" is displayed while the request is really queued (honest, if
imprecise), and the reject path must become a terminal that replaces the
placeholder.

**Option B — add a `QUEUED` ProgressStage.** Semantically precise ("queued…"),
but `ProgressStage` lives in `infochat-messaging-adapter`, so this is a
cross-module change; it forces the exhaustive switch at
`StageProgressNotifier.java:304`, a new `BundleKeys.PROGRESS_QUEUED`, and an
en/cs twin (D43 bilateral keyset — a missing cs key fails `BundleLoaderTest`).
It carries constraint 1 unchanged. It also raises a sub-question: publish
`QUEUED` always and rely on M1-607 stage coalescing to swallow it on the fast
path, or only when the pool is actually saturated (`getQueue()`/`getActiveCount()`
are documented approximate — `InterruptibleDispatcher.inFlightTaskCount()` is the
exact counter and exists precisely because the JDK's is not).

**Lean: Option A** — same user-visible win (silence removed, which is the actual
defect), materially smaller blast radius, and it stays inside one module.

`security_relevant: true` is deliberate: any change to what crosses the CDI
request-context hop touches the exact isolation surface M1-634's red-team audit
scrutinised (`docs/plan/m1/redteam/M1-634-2026-07-16.md` and its r2 re-audit).
Seeding one more scalar is not obviously safe by inspection and should get the
`/redteam` pass.

- Adjacent code: `InterruptibleDispatcher.java:176` (`dispatch`) and `:202`
  (`runStage`) — the seam and its documented hop contract.
- Adjacent code: `StageProgressNotifier.java:304` — the stage→bundle-key switch.
- Existing test: `InboundRouterConcurrentDispatchIT` (M1-634) already builds
  saturation scenarios; `DispatchAwaits` + `inFlightTaskCount()` are the
  established await points for race-free asserts. Reuse both rather than
  inventing a sleep.
- Findings context: the 2026-07-16 concurrency review that produced this ticket
  and M1-636.
