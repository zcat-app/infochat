---
id: M1-634
title: "Concurrent interruptible dispatch so the in-flight guard and /stop are reachable over live transports"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 21
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
      immediately after an interruptible body enters InboundRouter.onMessage()
      must be updated to await async completion once interruptible dispatch is
      offloaded — the asserted reject-with-guidance, progress-notifier, and
      no-double-send BEHAVIOURS are PRESERVED or strengthened, never weakened.
      The complete authorized set below is the plan-writer's start-time
      ground-truth census (2026-07-15) — the enumeration behind the
      files_budget 14->21 refine. All 16 pre-existing test files live under
      infochat-provider/src/test/java/app/zcat/infochat/provider/. No entry is
      droppable: leaving any un-awaited converts a deterministic green into a
      timing flake, which "PRESERVED or strengthened, never weakened" forbids.
    - "messaging/InboundRouterChatProgressTest.java — chat drives at 77, 99, 115, 152, 173 (ticket-confirmed)"
    - "messaging/RouterNoDoubleSendTest.java — /summary at 47 (ticket-confirmed)"
    - "messaging/InboundRouterAcquisitionCountTest.java — chat at 105"
    - "messaging/InboundRouterChatPersistFailureTest.java — chat at 100"
    - "messaging/InboundRouterChatModeIT.java — chat at 98, 230, 244, 249"
    - "messaging/InboundRouterTest.java — chat at 181; group-chat LLM-bucket drives at 326, 407, 492 with synchronous asserts"
    - "messaging/InboundRouterChatDeliveryOrderingIT.java — chat at 82, 99; synchronous countChatMessages asserts at 89, 101"
    - "messaging/InboundRouterStopRetryIT.java — /retry at 83, 105, 128 with direct reply asserts"
    - "messaging/InboundRouterClearCompressIT.java — chat drive at 96"
    - "messaging/LanguageThreadingIT.java — chat drive at 105"
    - "command/SummaryIT.java — /summary at 93, 149, 185; synchronous adapter.sentMessages() asserts at 100-127"
    - "command/SummaryGroupScopeIT.java — /summary at 122, 167, 168, 205, 206"
    - "command/SummaryAdapterScopeIT.java — /summary at 98"
    - "command/RetryCommandHandlerGroupScopeIT.java — /summary + /retry at 116, 122, 148, 151"
    - "journey/GoldenPathJourneyIT.java — /summary at 237, chat at 252"
    - "translation/TranslationPipelineIT.java — /summary at 122, 169"
    - >-
      Explicitly NOT modified (verified non-interruptible or non-router — do
      not touch): digest/DigestRoundtripIT.java (drives only /retry --digest,
      D35 non-interruptible); chat/StopToolQueryCancellationIT.java (drives
      ChatAgent directly, not through the router);
      messaging/InboundRouterIntakeOrderingTest.java (its chat body terminates
      pre-offload at the step-3.5 SilentDrop).
  preserves:
    - all tests currently green on main
reviews: {}
overrides:
  - date: 2026-07-15
    kind: start-precondition
    what: >-
      User-approved parallel start while M1-632 was in-progress in a concurrent
      session, despite M1-634 having no files_scope (numeric files_budget only),
      which the --parallel gate normally requires for a disjointness proof.
      Estimated file sets are disjoint (M1-632: invite command/bundles/spec;
      M1-634: router/chat dispatch/design doc); second-to-merge rebases on any
      conflict.
escalations:
  - date: 2026-07-15
    reason: outline-fail
    reviewer_verdict_excerpt: |
      OUTLINE FAILED — the honest file set exceeds files_budget: 14. Full
      enumeration of tests with the synchronous-assert-after-interruptible-
      dispatch shape found 16 pre-existing test files (not 2 confirmed + 3
      candidates as the ticket estimated); with minimum production surface
      (InboundRouter.java, one new worker-seam bean, 06-messaging.md) and the
      new concurrency tests folded into a single class, the minimum is 20
      files, realistically 21. SUGGESTED ESCALATION: refine — raise
      files_budget to ~21; the enumerated set becomes the authorized
      test_plan.modifies list. Full block with the per-file census: ticket
      body §OUTLINE FAILED (2026-07-15).
revisions:
  - date: 2026-07-15
    reason: outline-fail rework
    snapshot:
      status: escalated
      files_budget: 14
      escalation_reason: outline-fail
      clarity_check:
        date: 2026-07-15
        verdict: WARN
        warnings:
          - "FILES-BUDGET-PLAUSIBLE: files_budget 14 plausible-but-tight (~13-16 files); test_plan.modifies explicitly incomplete pending the plan-writer's enumeration."
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
- **files_budget: 21** is the plan-writer's 2026-07-15 start-time ground-truth
  count (16 enumerated pre-existing tests + `InboundRouter.java` + a per-request
  worker-seam bean + `InboundContext` propagation + the design doc + one new
  concurrency-test class), raised from the original 14 estimate via the
  outline-fail refine (see `revisions:` / `escalations:` and the §OUTLINE FAILED
  census below). The mandatory fresh Plan pass on the next `start` must
  re-validate it — if the honest file set still exceeds 21, escalate
  `budget-breach` at start rather than trimming scope.
- **Decision family:** D35 (cancellation), D31 (progress notifier), D46
  (per-process LLM concurrency cap / multi-adapter topology).

## OUTLINE FAILED (2026-07-15)

Verbatim from the plan-writer pass at `start` (escalation `outline-fail`):

REASON: The honest file set exceeds the `files_budget: 14` hard ceiling. Both
spec_refs resolve cleanly (commands.md line 8 "Surface conventions"; llm.md
line 439 "Bounded concurrency and observability") and the design shape is
implementable exactly as the ticket frames it (offload the interruptible
step-6 stage — chat-mode, `/summary`, `/retry` without `--digest` — from
`InboundRouter.onMessage` onto a bounded per-request worker seam, keeping the
pre-LLM intake gates on the transport dispatch thread; `/stop` and all other
slash commands stay inline, which is what makes `CancellationService.cancel`
reachable). But the ticket's test_plan.modifies is explicitly incomplete and
instructs the plan-writer to enumerate the complete set of tests that assert
outbound sends synchronously after an interruptible body enters
`InboundRouter.onMessage`. I performed that enumeration by grepping every
`onMessage(...)`/`deliverDm(...)`/`deliverGroupMention(...)` drive in the
provider test tree and classifying each body against the D35 interruptible
class: **16 pre-existing test files** carry the
synchronous-assert-after-interruptible-dispatch shape and must be updated to
await async completion — not the 2 confirmed + 3 candidate the ticket names.
Adding the minimum production surface (modify `InboundRouter.java`; create one
worker-seam bean; modify `docs/design/06-messaging.md` §6.3.7 lines 420/425)
and even folding all three new acceptance-concurrency tests into a single new
test class yields 16 + 3 + 1 = **20 files minimum, realistically 21** — well
past 14. The ticket's own Notes pre-authorize exactly this outcome: "if the
honest file set exceeds it, escalate `budget-breach` at start rather than
trimming scope," and the clarity_check WARN (FILES-BUDGET-PLAUSIBLE) predicted
the breach would be decided by this enumeration. No affected test is
droppable: leaving any of them un-awaited converts a deterministic green into
a timing flake, which the test_plan forbids (behaviours "PRESERVED or
strengthened, never weakened").

SUGGESTED ESCALATION: refine — raise `files_budget` to ~21 (scope unchanged;
the enumerated set below becomes the authorized test_plan.modifies list).

EVIDENCE: Ground-truthed census of affected pre-existing tests (all paths
under `infochat-provider/src/test/java/app/zcat/infochat/provider/`), each
with the interruptible drive line:

1. `messaging/InboundRouterChatProgressTest.java` — chat drives at 77, 99, 115, 152, 173 (ticket-confirmed)
2. `messaging/RouterNoDoubleSendTest.java` — `/summary` at 47 (ticket-confirmed)
3. `messaging/InboundRouterAcquisitionCountTest.java` — chat at 105 (ticket candidate, confirmed)
4. `messaging/InboundRouterChatPersistFailureTest.java` — chat at 100 (ticket candidate, confirmed)
5. `messaging/InboundRouterChatModeIT.java` — chat at 98, 230, 244, 249 (ticket candidate, confirmed)
6. `messaging/InboundRouterTest.java` — chat at 181; group-chat LLM-bucket drives at 326, 407, 492 with synchronous asserts
7. `messaging/InboundRouterChatDeliveryOrderingIT.java` — chat at 82, 99; synchronous `countChatMessages` asserts at 89, 101
8. `messaging/InboundRouterStopRetryIT.java` — `/retry` at 83, 105, 128 with direct reply asserts
9. `messaging/InboundRouterClearCompressIT.java` — chat drive at 96
10. `messaging/LanguageThreadingIT.java` — chat drive at 105
11. `command/SummaryIT.java` — `/summary` at 93, 149, 185; synchronous `adapter.sentMessages()` asserts at 100-127
12. `command/SummaryGroupScopeIT.java` — `/summary` at 122, 167, 168, 205, 206
13. `command/SummaryAdapterScopeIT.java` — `/summary` at 98
14. `command/RetryCommandHandlerGroupScopeIT.java` — `/summary`+`/retry` at 116, 122, 148, 151
15. `journey/GoldenPathJourneyIT.java` — `/summary` at 237, chat at 252
16. `translation/TranslationPipelineIT.java` — `/summary` at 122, 169

Not affected (verified): `digest/DigestRoundtripIT.java:221` drives only
`/retry --digest`, which D35 (decisions.md line 52) classifies
non-interruptible and the classification predicate must exclude from offload;
`chat/StopToolQueryCancellationIT.java` drives ChatAgent directly, not through
the router; `messaging/InboundRouterIntakeOrderingTest.java` line 508's chat
body terminates pre-offload at the step-3.5 SilentDrop; all other census hits
are non-interruptible slash or pre-dispatch fixed-reply paths (caps, ban,
probation, invite), which stay synchronous.

*(Main-session spot-check 2026-07-15: census entries 10, 13, 15, 16 verified
against source — all real interruptible drives with synchronous asserts.)*
