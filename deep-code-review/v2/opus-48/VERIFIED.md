# Manual verification log — opus-48 audit run

Independent confirmations of findings from this run's reports, done by
manual code trace (not by re-running an agent). The per-target report
files are left untouched on purpose — they are per-model artifacts for
the cross-model comparison. This file is the only place verification
verdicts live.

| Finding | Verdict | Date | By |
|---|---|---|---|
| 05#F1 — inbound dispatch deadlock | CONFIRMED critical | 2026-06-06 | Opus 4.8, manual trace |

## 05#F1 — Inbound dispatch blocks the transport read thread

**Verdict: CONFIRMED critical.**

Traced the full chain in `src/main` (not the worktree copies). Every hop
is synchronous on the transport read thread, no executor anywhere:

- SimpleX `onText` calls `dispatch(frame)` before `webSocket.request(1)`
  (`SimpleXWebSocketClient.java:277-283`); `dispatch` → `onInbound` →
  `current.onMessage(msg)` direct (`SimpleXAdapter.java:358`).
- Signal `readerLoop` → `handleLine` → `dispatchNotification` →
  `handler.onMessage(inbound)` direct, on the `signal-jsonrpc-reader`
  thread (`SignalJsonRpcClient.java:541`).
- Registry wires a bare lambda: `setInboundHandler(msg -> inboundRouter.onMessage(msg, adapterName))` (`AdapterRegistry.java:282`).
- `InboundRouter.onMessage` is plain synchronous; every branch calls
  `sendReply` → `target.send(...)` (`InboundRouter.java:690`).
- `send()` blocks on a future completed only by the read thread:
  SimpleX `future.get(ackTimeout)` (`SimpleXWebSocketClient.java:187`),
  Signal `future.get(responseTimeout)` (`SignalJsonRpcClient.java:323`),
  both completed by `completePending` on the now-blocked read thread.

Falsifiers checked, all failed to break it: (1) no executor/thread
hand-off on any of the 6 hops — the `@ActivateRequestContext` on
`onMessage` is same-thread CDI scope activation, not a switch; (2)
`send()` is blocking in both adapters; (3) the ack/response future is
completed only on the read thread; (4) `onMessage` replies on every path.

SimpleX is doubly locked: the JDK WebSocket won't deliver the ack frame
both because the prior `onText` hasn't returned and because `request(1)`
is unreached. Signal restart claim holds: `consecutiveTimeouts` resets
only on success (`:343`), inbound replies always time out (15s),
`HUNG_TIMEOUT_THRESHOLD = 3` (`:105`) → `hungRestartHook.run()`.

Caveats layered on the report (do NOT edit into 05's file):

- It is deadlock-until-timeout, not a permanent hang — the call unwinds
  after 30s (SimpleX) / 15s (Signal); the reply is lost. Practical
  conclusion unchanged: neither production adapter answers inbound on the
  normal path.
- Category is really correctness/liveness, not PERFORMANCE. Severity
  (critical) is right regardless.
- Regression test for the eventual fix must drive ack delivery on a
  DIFFERENT thread than the inbound, so a re-collapse onto one thread
  re-deadlocks and fails CI. The current adapter tests are skeleton/
  contract-level and never exercise a real inbound→reply round-trip,
  which is why this survived.
