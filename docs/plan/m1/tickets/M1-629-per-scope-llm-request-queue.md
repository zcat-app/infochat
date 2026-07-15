---
id: M1-629
title: "Investigate the one-in-flight-per-(user, scope) guard under a chat/summary burst (multi-minute-late replies)"
status: abandoned
abandoned_reason: decomposed
decomposed_into:
  - M1-634
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 6
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Making the LLM itself faster. This ticket is about how the provider handles
    MULTIPLE in-flight/queued chat+summary requests for one (user, scope).
acceptance:
  - >-
    Determine, with evidence, what actually happens when a second interruptible
    request (chat-mode message or user-issued /summary) arrives from the SAME
    (user, scope) while one is in flight. Evidence means: name the code path
    (class and method) that admits, queues, or rejects the second request, plus a
    reproducing test or a captured log trace. Observed in the 2026-07-14/15 live
    test: one user (the synthetic admin, contact 4, DM scope) fired several
    chat/summary requests in quick succession and the replies drained serially
    over minutes (a ~13-minute-late /summary), with no clear "request already in
    progress" reply to the second request.
  - >-
    The spec already settles the intended behaviour — docs/spec/commands.md
    §Surface conventions: "At most one in-flight interruptible request per (user,
    scope). A second request from the same caller while one is in flight returns
    a localized 'request already in progress; use /stop to cancel' reply. ...
    once the first request completes (or is cancelled by /stop) the next is
    accepted normally." — i.e. reject-with-guidance, NOT bound/coalesce. Chat-mode
    agent loops and user-issued /summary are both in the interruptible class
    (D35). If the guard is missing or bypassed for these paths, implement
    conformance to that rule. If investigation shows current behaviour already
    conforms (the observed backlog had a different cause consistent with the
    spec), close with that finding plus a test that pins the reject-with-guidance
    behaviour.
test_plan:
  adds:
    - >-
      A test that pins the reject-with-guidance rule: while one interruptible
      request for a (user, scope) is in flight, a second request from the same
      (user, scope) receives the localized "request already in progress; use
      /stop to cancel" reply and triggers no second LLM call.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D35
revisions:
  - date: 2026-07-15
    reason: >-
      clarity-fail refine (bounded self-refine via /m1-tick run, decision C).
      Round-1 clarity FAILed with 3 blockers: (1) acceptance item 2 delegated its
      criterion to "whichever the spec intends" without naming a section; (2) the
      frontmatter had no spec_refs at all; (3) the title/body said "per-scope"
      while the spec's guard is keyed per (user, scope), and the ticket did not
      state which burst scenario was observed. Fixed by inlining the settled spec
      rule (commands.md §Surface conventions commits to reject-with-guidance, not
      bound/coalesce; chat + user-issued /summary are the interruptible class per
      D35), adding spec_refs/decision_refs, correcting the guard-key terminology,
      and recording the observed scenario from the Phase-D live-test report: ONE
      user (synthetic admin, contact 4) in one DM scope — exactly the case the
      spec's guard covers. Also applied both clarity WARNINGs on the same edit,
      neither expanding scope: named what counts as evidence in acceptance item 1
      (code path + reproducing test or log trace) and added a test_plan (adds a
      reject-with-guidance pinning test). files_budget, complexity, risk, and the
      out_of_scope boundary are unchanged.
---

Found in the 2026-07-14/15 isolated live test (PHASE-D-REPORT-20260715, finding 3
"Serial LLM backlog"): ONE user — the synthetic admin (contact 4), in their own DM
scope — fired several chat/summary requests in quick succession and the replies
drained one-at-a-time over minutes (a ~13-minute-late /summary reply), with no
clear "request already in progress; use /stop to cancel" reject for the second and
later requests. This is exactly the single-(user, scope) case the spec's
one-in-flight guard commits to rejecting (docs/spec/commands.md §Surface
conventions), so the working hypothesis is that the guard is missing or bypassed
for chat-mode and/or user-issued /summary; the investigation must confirm or
refute that with the evidence named in acceptance item 1.

The backlog's *duration* was amplified by the test instance's slow CPU-bound
embeddings and a large seeded corpus — that amplification (LLM/embedding speed) is
out of scope; only the admission behaviour is in scope.

If a pre-existing test pins a contrary (queueing) behaviour for these paths, this
ticket does NOT pre-authorize modifying it — escalate instead.

## Outcome (2026-07-15) — investigation complete, decomposed into M1-634

The investigation (acceptance item 1) is **done**; the finding is below. Because
the fix it implies is a materially larger, higher-risk change than this
investigation ticket was sized or gated for (`complexity: medium`,
`security_relevant: false`, `files_budget: 6`, no plan-writer outline), the
implementation is carried by a fresh, correctly-gated ticket — **M1-634**
(`complexity: high`, `security_relevant: true`, plan-writer + redteam gates).
This ticket is `abandoned` / `decomposed`; M1-634 fully replaces it.

**Finding — the guard is correct but unreachable over live transports.**

- The per-`(user, scope)` guard EXISTS and is implemented correctly:
  `InFlightTracker` keys on `(userId, scopeKind, scopeId)` via `putIfAbsent`
  (`infochat-provider/.../chat/InFlightTracker.java`), and every interruptible
  surface brackets its work with a `tryAcquire` → reject path:
  `ChatAgent.handleTurn` (`ChatAgent.java:225-230`, reply
  `BundleKeys.ERROR_CHAT_IN_FLIGHT`), `SummaryCommandHandler` (`:277-280`),
  `RetryCommandHandler` (`:188-191`).
- It can never fire over a real transport. `SimpleXWebSocketClient` hands
  inbound delivery to a **single-threaded** dispatch executor
  (`ThreadPoolExecutor(1, 1, …)`, bounded FIFO queue, M1-177 / M1-224;
  `SignalJsonRpcClient` mirrors it), and `AdapterRegistry` wires
  `setInboundHandler → InboundRouter.onMessage` **synchronously**
  (`AdapterRegistry.java:382`). So the whole LLM turn runs inline on that one
  dispatch thread; a second same-`(user, scope)` request waits in the transport
  queue and is only dequeued AFTER the first finishes and has already released
  its slot. The guard therefore observes no contention — requests drain
  serially (the observed multi-minute backlog), never rejected.
- **Same root cause makes `/stop` dead over live transports.** D35 says `/stop`
  cancels "immediately, freeing the worker for others," but `/stop` queues
  behind the very in-flight LLM call it is meant to cancel, so it cannot be
  processed until that call finishes on its own.
- This is a **spec-vs-design-doc tension**, resolved in the spec's favour:
  `commands.md` §Surface conventions (guard) and D35 (`/stop` immediately) both
  require a second request to arrive-and-be-handled while the first is still
  running — which the `06-messaging.md` §6.3 single-dispatch-thread model cannot
  deliver. Spec outranks the "not spec, may change" design note, so the fix is
  to make provider-side interruptible dispatch concurrent AND correct the design
  doc — an implementation change, not a spec amendment. M1-634 carries both.

The `/stop` reachability gap is a real, previously-unfiled defect surfaced by
this investigation; M1-634's acceptance pins it alongside the guard.
