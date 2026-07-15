---
id: M1-634
title: "Concurrent interruptible dispatch so the in-flight guard and /stop are reachable over live transports"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 14
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
decomposed_from: M1-629
spec_refs:
  - docs/spec/commands.md §Surface conventions
  - docs/spec/llm.md §Bounded concurrency and observability
decision_refs:
  - D35
  - D31
  - D46
out_of_scope:
  - >-
    Making the LLM or embeddings faster (M1-629's original boundary). The
    observed backlog's DURATION was amplified by slow CPU-bound embeddings on a
    large seeded corpus; that amplification is not in scope. Only the admission /
    cancellation reachability is.
  - >-
    Per-user FAIR scheduling. Design 06-messaging.md §6.3 explicitly defers a
    fair scheduler to a later revision ("Per-user fairness is not implemented in
    v1: … one sender's share is bounded by its per-minute rate-cap budget rather
    than by a fair scheduler"). This ticket does NOT add fairness; a sender's
    share stays bounded by LlmRateCap.
  - >-
    The transport adapters' single-threaded inbound READ/dispatch design
    (SimpleXWebSocketClient / SignalJsonRpcClient ThreadPoolExecutor(1,1) and the
    bounded INBOUND_QUEUE_CAPACITY queue, M1-177 / M1-224). The transport read
    loop stays single-threaded and its bounded queue is unchanged; only the
    provider-side interruptible LLM work is offloaded off that thread.
  - >-
    Multi-in-flight OUTBOUND delivery (one frame per connection is a hard v1
    constraint, 06-messaging.md §6.3) and raising/altering the per-process LLM
    concurrency bound or LlmRateCap values.
acceptance:
  - >-
    Guard reachability (reproduce-then-fix). An automated test drives two
    interruptible requests for the SAME (user, scope) through the provider intake
    path (InboundRouter.onMessage) such that the second is admitted WHILE the
    first still holds its InFlightTracker slot, and asserts the second receives
    the localized "request already in progress; use /stop to cancel" reply
    (BundleKeys.ERROR_CHAT_IN_FLIGHT) and causes NO second chat-agent / LLM
    invocation. The test must exercise real contention (concurrent dispatch), not
    a single-threaded direct handler call that can never observe the slot held.
  - >-
    /stop reachability (D35). An automated test shows that a /stop for a
    (user, scope) with an interruptible request in flight is processed WITHOUT
    waiting for that request to complete — it runs concurrently and marks the
    in-flight request cancelled (CancellationHandle.markCancelled /
    CancellationService), so the worker is freed and the cancelled result is
    discarded. Contrast the pre-fix behaviour where /stop queued behind the
    in-flight LLM call and could not cancel it.
  - >-
    Cross-scope isolation preserved. A test asserts that two dispatches for
    DIFFERENT (user, scope) run concurrently (neither blocks the other), and that
    per-request CDI request-scope state is isolated per concurrent dispatch: each
    dispatch sees its own InboundContext (adapterName, senderContactId,
    effectiveLanguage, operationId, pendingChatCommit, requestEndCleanups) with no
    field bleeding from one concurrent dispatch into another.
  - >-
    Bounded load preserved. Concurrent interruptible dispatch does not exceed the
    existing per-process LLM concurrency bound (llm.md §Bounded concurrency) /
    LlmRateCap — a burst does not spawn unbounded concurrent LLM calls. A test (or
    a preserved existing test) demonstrates the LLM-call concurrency / rate cap
    still binds. Non-interruptible paths (periodic digests, ingest, mutating
    commands) keep their existing threading and ordering.
  - >-
    Design doc corrected. docs/design/06-messaging.md §6.3 is amended so it no
    longer implies the WHOLE inbound turn (including the interruptible LLM stage)
    runs on the single transport dispatch thread. The transport read loop / bounded
    queue description stays accurate; the new text states that the provider offloads
    interruptible LLM work (chat, user-issued /summary, /retry) to per-request
    workers so the one-in-flight guard and /stop are reachable, while
    non-interruptible work stays on the existing path.
  - >-
    The full pre-existing suite is green (mvn verify), including the router /
    chat-dispatch tests updated per test_plan to await the async completion.
test_plan:
  adds:
    - >-
      New concurrency tests covering guard-reachability (acceptance 1), /stop
      reachability (acceptance 2), and cross-scope isolation + InboundContext
      propagation across the worker hop (acceptance 3). Exact class names/paths
      are the plan-writer's to fix; they live under
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging (and
      /chat) alongside the existing router/in-flight tests.
  modifies:
    - >-
      Router / chat-dispatch tests that assert outbound sends SYNCHRONOUSLY
      immediately after InboundRouter.onMessage() returns must be updated to await
      async completion once interruptible dispatch is offloaded — the asserted
      reject-with-guidance, progress-notifier, and no-double-send BEHAVIOURS are
      PRESERVED or strengthened, never weakened. Confirmed instances to update:
      InboundRouterChatProgressTest, RouterNoDoubleSendTest. The plan-writer MUST
      enumerate the complete set (every router/dispatch test with the
      synchronous-assert-after-onMessage shape — candidates include
      InboundRouterChatModeIT, InboundRouterAcquisitionCountTest,
      InboundRouterChatPersistFailureTest) and name each edit in the outline
      before implementation.
  preserves:
    - all tests currently green on main
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-634: Concurrent interruptible dispatch so the in-flight guard and /stop are reachable over live transports

## Context

Decomposed from **M1-629** (investigation, `abandoned` / `decomposed`). The
2026-07-14/15 isolated live test saw a burst of chat/summary requests from ONE
user in ONE DM scope drain serially over minutes (a ~13-minute-late /summary),
with no "request already in progress" reject for the second and later requests.

M1-629's investigation found the guard is implemented correctly but is
**structurally unreachable over live transports** — see M1-629's `## Outcome`
for the full evidence. In brief:

- `InFlightTracker` (`(userId, scopeKind, scopeId)` `putIfAbsent`) and its
  reject brackets at `ChatAgent.handleTurn:225-230`, `SummaryCommandHandler:277`,
  `RetryCommandHandler:188` are correct.
- But `SimpleXWebSocketClient` dispatches inbound on a **single-threaded**
  executor (`ThreadPoolExecutor(1,1)`, bounded FIFO queue; Signal mirrors it) and
  `AdapterRegistry.java:382` calls `InboundRouter.onMessage` **synchronously**, so
  the entire LLM turn runs inline on that one thread. A second same-`(user,
  scope)` request only dequeues after the first releases its slot — the guard
  never sees contention, and `/stop` (D35 "immediately") queues behind the very
  call it should cancel.

## Root cause and the spec-vs-design tension (read before implementing)

The spec commits to behaviour the current threading cannot deliver:

- `docs/spec/commands.md` §Surface conventions: "At most one in-flight
  interruptible request per (user, scope). A second request from the same caller
  while one is in flight returns a localized 'request already in progress; use
  /stop to cancel' reply." — requires the second request to be **handled while
  the first is still running**.
- **D35**: `/stop` "cancels the calling (user, scope)'s currently in-flight
  interruptible request **immediately**, so the worker is freed for others" —
  requires `/stop` to run **concurrently** with the in-flight request.

`docs/design/06-messaging.md` §6.3, as written, describes the single transport
dispatch thread processing inbound "in arrival order" — which, taken to include
the interruptible LLM stage, contradicts both. **The spec outranks the design
note** (design notes carry a "not spec, may change" banner). So the resolution is
to make provider-side interruptible dispatch concurrent AND correct §6.3 — an
implementation change, NOT a spec amendment.

## Design constraints (the plan-writer resolves the mechanism)

This ticket specifies BEHAVIOUR, not a specific implementation. The plan-writer
outline must design the mechanism against these hard constraints:

- **Offload only the interruptible work.** Chat-mode agent loops, user-issued
  `/summary`, and user-issued `/retry` (the D35 interruptible class) are what must
  run concurrently. Non-interruptible work (periodic digests, ingest, mutating
  commands, `/retry --digest`) keeps its current threading and ordering.
- **Do not change the transport read/dispatch design.** The single-thread
  transport executor and its bounded `INBOUND_QUEUE_CAPACITY` queue (the DOS
  memory bound, M1-224) stay. The fast intake gates (rate cap, ban, size cap,
  normalize) may stay on the dispatch thread; only the slow interruptible stage is
  handed to a per-request worker so a second request can be admitted and the guard
  can reject it.
- **Preserve per-`(user, scope)` isolation across the thread hop.** `InboundRouter`
  runs under `@ActivateRequestContext`; `InboundContext` is `@RequestScoped`
  (fields: `adapterName`, `senderContactId`, `effectiveLanguage`, `operationId`,
  `pendingChatCommit`, `requestEndCleanups`). Moving the interruptible stage onto a
  worker thread requires that request context be correctly propagated/snapshotted
  so no state leaks between concurrent dispatches (a leak here is a cross-user
  isolation defect — hence `risk: high`, `security_relevant: true`).
- **Keep LLM load bounded.** Concurrency must remain capped by the existing
  per-process LLM concurrency bound (llm.md §Bounded concurrency, D46) and
  `LlmRateCap`; the fix must not let a burst spawn unbounded concurrent LLM calls.
- **Preserve the D31 progress-notifier and no-double-send behaviour** through the
  async hop (the `StageProgressNotifier` placeholder → edits → terminal and the
  `/stop` "stopped" terminal must still fire exactly once).

## Out-of-scope

See frontmatter `out_of_scope`. In short: not making the LLM/embeddings faster,
not adding per-user fairness, not touching the transport single-thread read loop
or bounded queue, not multi-in-flight outbound, not changing the LLM cap values.

## Notes

- **Adjacent code:** `InboundRouter` (`.../messaging/InboundRouter.java`,
  `onMessage` + `dispatchChatSelfDelivering`), `AdapterRegistry.java:382` (the
  synchronous `setInboundHandler` wiring — the boundary where adapter threads
  enter provider code, already TCCL-pinned per M1-543), `InFlightTracker`,
  `CancellationService`, `ChatAgent.handleTurn`, `SummaryCommandHandler`,
  `RetryCommandHandler`, `StopCommandHandler`, `InboundContext`.
- **files_budget: 14** is an estimate for a change spanning the router, request
  context propagation, a likely small dispatch/worker seam, the design doc, and
  several test files. The plan-writer must validate it — if the honest file set
  exceeds it, escalate `budget-breach` at start rather than trimming scope.
- **Decision family:** D35 (cancellation), D31 (progress notifier), D46
  (per-process LLM concurrency cap / multi-adapter topology).
