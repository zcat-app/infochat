---
id: M1-557
title: SignalReconnectTest inbound push absorbs reconnect wiring gap
status: pending
created: 2026-07-04
last_updated: 2026-07-04
blocked_by: []
files_budget: 1
files_scope:
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any src/main change — SignalAdapter, SignalJsonRpcClient,
    SignalSubprocess, and the reconnect sequencing are correct (the
    disconnect→join→connect barrier is the exactly-once mechanism);
    only the test's pre-push synchronization is at fault
  - any assertion change — the three existing assertions
    (assertNotNull delivery, assertEquals text, assertNull duplicate
    settle window) stay byte-identical; adding a readiness retry
    before the push is a precondition, not a weakening
  - FakeSignalCli — pushNotification already declares IOException;
    the retry lives entirely in the test. If a fake-side accessor
    turns out to be required, escalate → refine rather than widening
    silently
  - the sibling tests sendDuringOutageFailsTransient and
    sendSucceedsAfterSupervisedRestart — the former has no reconnect,
    the latter already absorbs the gap via sendUntilSuccess
acceptance:
  - "In inboundDeliveredExactlyOnceAfterReconnect, the bare
    fake.pushNotification(...) call (currently SignalReconnectTest.java
    ~l.154) is wrapped in a bounded retry: on IOException from the
    push, sleep briefly and retry until a deadline (>= 5000 ms, same
    order as sendUntilSuccess's 10 s); deadline exhaustion throws with
    the last IOException attached. Retry fires ONLY on IOException —
    a flushed (non-throwing) push is never re-sent, so the
    exactly-once assertion keeps its full force. This
    pre-existing-test modification is explicitly authorized by this
    ticket (test-integrity rule §8)."
  - "The retry carries a comment stating the wiring-gap rationale
    (the fake's accept/generation barrier does not prove the
    adapter's connect() has wired writer+reader; the gap is
    documented in sendUntilSuccess's javadoc) and the flake evidence:
    SocketException 'Socket closed' from FakeSignalCli.sendLine
    during a full-suite verify on 2026-07-04 under co-located host
    load; M1-540 hardened the sibling MultiAdapterProductionIT test
    against the same race class."
  - "The three existing assertions in the test method (assertNotNull
    first-delivery, assertEquals text, assertNull duplicate within
    the 400 ms settle window) are byte-unchanged; no file outside
    SignalReconnectTest.java is touched."
  - mvn verify is green.
test_plan:
  adds: []
  modifies:
    - "infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
      (inboundDeliveredExactlyOnceAfterReconnect: bounded
      IOException-retry around pushNotification, authorized by this
      ticket; every assertion unchanged)"
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs: []
---

# M1-557: SignalReconnectTest inbound push absorbs reconnect wiring gap

## Context

During M1-556's full-suite verify on 2026-07-04 (co-located with the
live app stack, heavy host load),
`SignalReconnectTest.inboundDeliveredExactlyOnceAfterReconnect` errored
with `java.net.SocketException: Socket closed` thrown from
`FakeSignalCli.sendLine` (via `pushNotification`) at the test's push
call. Mechanism: `awaitConnectionGeneration(generationBeforeKill + 2)`
returns when the fake has *accepted* the reconnect's JSON-RPC socket,
but the adapter's `reconnect()` (probe → disconnect → connect on a
virtual thread) may still be mid-wiring; under load the
microseconds-wide gap stretches and the push's flush lands on a socket
the client side is still tearing down.

The test file already knows about this gap: `sendUntilSuccess`'s
javadoc says "the gap between the fake accepting the reconnect's
socket and the client wiring its writer/reader is microseconds wide
but real; polling absorbs it without weakening the category
assertion" — and the outbound twin
(`sendSucceedsAfterSupervisedRestart`) uses exactly that polling.
The inbound test is the one caller that never got the absorption.
M1-540 fixed the same race class in
`MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal` (connect
barrier before the liveness probe) and explicitly left this test out
of scope.

Production impact: none. The reconnect path sequences
disconnect → join old reader → fresh connect/executor, all transport
IOExceptions are caught and classified, and max-restart exhaustion
degrades to a FAILED adapter with admin notification — the race is a
test-harness synchronization gap only (analysis 2026-07-04).

## Acceptance

- The bare `fake.pushNotification(...)` is wrapped in a bounded retry
  that retries only on `IOException`, with a deadline ≥ 5000 ms and a
  short sleep between attempts; exhaustion fails the test with the
  last exception. An authorized modification of a pre-existing test.
- The retry's comment records the wiring-gap rationale and the
  2026-07-04 flake evidence, citing M1-540 as the sibling precedent.
- The three existing assertions are byte-unchanged; only
  `SignalReconnectTest.java` is touched.
- `mvn verify` is green.

## Out-of-scope

No production change — the exactly-once mechanism (old reader joined,
old dispatch executor shut down, fresh connection serves) is correct
and is what the unchanged assertions keep proving. Retry-on-IOException
cannot introduce a duplicate: an IOException from the push means the
frame was never consumed by the client (its side of that socket was
closed), so re-sending on the new connection delivers at most once.

## Notes

- Alternative considered — outbound round-trip barrier instead of
  push-retry (run `startSendResponder` + `sendUntilSuccess` before the
  push, proving the final socket full-duplex): rejected; it imports a
  responder thread and outbound traffic into a test whose point is
  inbound-path purity, and the push-retry mirrors the absorption
  pattern the file already documents.
- Alternative considered — widen FakeSignalCli with an
  adapter-side-readiness handshake: rejected as harness
  over-engineering; the bounded retry is the established idiom
  (sendUntilSuccess) and keeps the diff to one file.
- Anchor: push call at `SignalReconnectTest.java` ~l.154; the
  documented gap javadoc at ~l.171–177; sibling absorption at ~l.120
  (`sendUntilSuccess(adapter, 10_000)`).
