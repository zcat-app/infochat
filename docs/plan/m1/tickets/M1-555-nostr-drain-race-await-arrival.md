---
id: M1-555
title: "NostrStreamSourceTest drain race: await arrival before stop"
status: done
created: 2026-07-03
last_updated: 2026-07-03
clarity_check:
  date: 2026-07-03
  verdict: PASS
  warnings: []
reviews:
  - round: 1
    date: 2026-07-03
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 28
      removed: 8
blocked_by: []
files_budget: 2
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any change to production stop()/drain/enqueue semantics — the seam this
    ticket adds is observation-only (a counter read); NostrStreamSource's
    delivery behavior is correct and stays byte-equivalent
  - the other NostrStreamSourceTest methods (connectsToAllConfiguredRelays,
    receivesAndDeliversEvents, reconnectsWithSinceOnDisconnect) — only
    stopDrainsAndClosesConnections carries the race
  - the Stage1WatchdogIT duration-cap flake (101ms vs 100ms CI band,
    observed 2026-07-03 on M1-554's verify attempt 1) — distinct root
    cause, not tracked by this ticket
  - OutboxRehydratorPaginationIT mid-drain overflow (M1-551's concern) and
    the StopToolQueryCancellationIT cancel-vs-statement-timeout race —
    already excluded by M1-554's out_of_scope for the same reason
acceptance:
  - NostrStreamSource exposes a package-private, observation-only arrival
    seam — a cumulative count of successfully enqueued inbound events,
    incremented in enqueueInbound only after a successful offer (same
    seam pattern as the existing failedSigCount()). No production
    behavior changes; the enqueue/drain/stop logic is otherwise untouched.
  - NostrStreamSourceTest.stopDrainsAndClosesConnections awaits BOTH
    "delivery started" (delivered ≥ 1) AND "all three drain events
    arrived" (arrival seam == 3) before calling stop(). Its three
    existing assertions — stop() flushed every buffered event (3),
    stop() closed the WebSocket connection, no deliver callback fires
    after stop() returns — are byte-for-byte unchanged.
  - "Determinism proof, recorded in the commit message: 10 consecutive
    module-only runs green — for i in 1..10:
    mvn -B -pl infochat-collector -Dtest=NostrStreamSourceTest
    -Dsurefire.failIfNoSpecifiedTests=false test.
    (Baseline 2026-07-03: 2 of 4 identical runs on unmodified main
    failed at stopDrainsAndClosesConnections:161 while the production
    llama.cpp container saturated the 4-core host.)"
  - mvn verify is green.
test_plan:
  adds: []
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSourceTest.java —
      stopDrainsAndClosesConnections only; the racy "awaitSize(delivered, 1)
      then stop()" arm gains the arrival-seam await; all assertions
      unchanged (this is a de-flake, not a weakening — the assertion
      contract is preserved verbatim)
  preserves:
    - the full pre-existing suite
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
---

## Context

Root-caused 2026-07-03 while driving M1-554: its full-suite verify failed
twice consecutively at `stopDrainsAndClosesConnections:161` ("stop()
flushed every buffered event ==> expected: <3> but was: <2>", then
`<1>`), firing the loop escalation trigger and deferring M1-554 onto this
ticket. Falsification confirmed the flake is pre-existing and
diff-independent: 2 of 4 module-only runs on UNMODIFIED main failed at
the same assertion while `infochat-llamacpp-1` ran at ~767% CPU on the
4-core host.

The race is in the test, not the code. `NostrStreamSource.stop()` is
correct: it stops the relay connections (no further enqueues), then the
delivery loop drains until `inbound.isEmpty()` before exiting. The test
sends three events over a real loopback WebSocket to a deliberately slow
(50ms/event) consumer, awaits only the FIRST delivery, then calls
`stop()`. Its inline comment — "the remaining events are now queued" —
is an unenforced assumption: on a saturated host, events B/C can still
be in WebSocket transit when `stop()` tears the relays down, so they are
never enqueued and never delivered. The drain guarantee the assertion
states ("every BUFFERED event is flushed") was never violated; the test
conflates "sent by the relay" with "buffered in the source".

## Acceptance

- A package-private, observation-only arrival counter on
  `NostrStreamSource` (cumulative successful `enqueueInbound` offers;
  pattern: the existing `failedSigCount()` / `drainInbound()` seams).
  Production enqueue/drain/stop behavior is unchanged.
- `stopDrainsAndClosesConnections` awaits `delivered ≥ 1 AND arrivals ==
  3` before `stop()`; its three assertions are unchanged.
- Determinism proof in the commit message: 10 consecutive green
  module-only runs (`mvn -B -pl infochat-collector
  -Dtest=NostrStreamSourceTest -Dsurefire.failIfNoSpecifiedTests=false
  test`), against the 2026-07-03 baseline of 2 failures in 4 runs on
  unmodified main.
- `mvn verify` is green.

## Out-of-scope

Production semantics: the delivery loop, `stop()` ordering, and
queue-full handling are correct and must not change — the seam is a
read-only counter. Other test methods in the class are untouched. The
Stage1WatchdogIT duration-band flake and the two flakes already excluded
by M1-554 (OutboxRehydratorPaginationIT, StopToolQueryCancellationIT)
have distinct root causes and stay out.

This ticket modifies one pre-existing test,
`NostrStreamSourceTest.stopDrainsAndClosesConnections`: the new expected
behavior is identical assertions behind a deterministic arrival gate —
the await-then-stop arm changes, the assertion contract does not.

## Notes

- Why awaiting arrival does not defeat the test's purpose (exercising
  the stop()-drain path rather than steady-state delivery): the consumer
  holds the delivery thread for 50ms per event, so once all three events
  have arrived and the first delivery has started, at least one event is
  still buffered when `stop()` fires in any realistic schedule; and in
  the degenerate all-delivered-before-stop schedule the assertion still
  holds, so the test cannot false-fail.
- Awaiting the queue depth instead (e.g. `inboundDepth() == 2`) is NOT
  equivalent: the delivery thread concurrently polls the queue, so depth
  2 may never be observed even when all three events arrived (A
  delivered, B in the consumer, C queued → depth 1). The cumulative
  arrival count is monotonic and race-free to await on.
- Reproduction recipe (for verifying the fix under the conditions that
  surfaced the bug): loop the module-only command from the acceptance
  item while the host is busy; on 2026-07-03 the production llama.cpp
  container provided the load. `.scratch/flake-run-*.log` from the
  M1-554 session holds the baseline evidence.
- Adjacent code: `NostrStreamSource.failedSigCount()` (the seam pattern
  to match), `NostrStreamSource.enqueueInbound()` (the increment site —
  after the successful `offer`, beside the dedup `record`).
