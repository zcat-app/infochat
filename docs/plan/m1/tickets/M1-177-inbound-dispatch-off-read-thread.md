---
id: M1-177
title: "Move inbound dispatch off the transport read thread"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 9
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
complexity: medium
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - reader-loop exception hardening (malformed frames, codec typed-accessor NPE/CCE, raw-line interpolation) — that is M1-184's; this ticket changes WHERE inbound is dispatched, not how decode failures are caught
  - InMemoryAdapter dispatch threading — it is transportless (no ack round-trip on a reader thread, so the deadlock mechanism does not exist there), and its synchronous delivery is pinned by InMemoryAdapterTest plus 29 provider test files; do not touch it
  - send-path serialization and handle-map bounding (M1-188)
  - supervisor-restart transport reconnect (M1-185)
  - any change to InboundRouter or other provider-side handler code — the fix is adapter-side
acceptance:
  - "An InboundHandler that synchronously calls the adapter's send path from inside onMessage completes without deadlocking against its own ack delivery — one named test per transport (Signal JSON-RPC fake transport, SimpleX fake WebSocket) asserts the reply's send completes while inbound delivery continues"
  - "A named test asserts InboundHandler.onMessage and MembershipHandler.onEvent execute on a thread other than the transport reader thread (signal-jsonrpc-reader / the WebSocket listener thread), for both adapters"
  - "A named test asserts inbound messages pushed in order on one transport connection reach the InboundHandler in the same order (FIFO preserved across the dispatch change)"
  - "MessagingAdapter.InboundHandler javadoc states the threading contract: the handler may block and may send replies synchronously from onMessage; adapters MUST NOT invoke it on the thread that reads the transport socket"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-177: Move inbound dispatch off the transport read thread

## Context

Both production adapters dispatch `InboundHandler.onMessage` synchronously
from the thread that reads the transport socket: `SignalJsonRpcClient`
dispatches from its `signal-jsonrpc-reader` thread
(`handleLine` → `handler.onMessage(inbound)`, SignalJsonRpcClient.java:542),
and `SimpleXWebSocketClient` dispatches from the JDK WebSocket listener
thread (`onText` → `inboundConsumer.onInbound`,
SimpleXWebSocketClient.java:320). A handler that replies from inside
`onMessage` awaits an ack that the same (now blocked) reader thread must
deliver — deadlock until the send timeout (30s SimpleX / 15s Signal; three
Signal timeouts trip restartHung → SIGKILL cycling). Confirmed by manual
trace in `deep-code-review/v2/opus-48/VERIFIED.md`; unified finding C1/M1 in
`deep-code-review/v2/UNIFIED.md` §1.

## Acceptance

See frontmatter. The behavioral core: a synchronous reply from inside
`onMessage` must complete, handler callbacks must leave the reader thread,
and per-connection FIFO delivery order must survive the change.

## Out-of-scope

See frontmatter. In particular, pre-existing tests in the two adapter test
dirs (`SignalJsonRpcClientTest`, `SignalGroupEndToEndTest`,
`SignalGroupHandlerTest`, and the SimpleX client tests) already use
queue-poll-with-timeout patterns and are expected to keep passing; this
ticket is AUTHORIZED to adjust their timing/ordering assumptions where the
async dispatch shifts them, but must not weaken what they assert (delivered
content, scopes, sender identity). `InMemoryAdapterTest` and provider tests
must not be touched.

## Notes

- Source: `UNIFIED.md` §1 C1 under `deep-code-review/v2/` (opus-48 msg F1,
  kimi-folder msg F1; confirmed in the opus-48 run's `VERIFIED.md`).

## Suggested direction (unverified hypothesis)

Hand inbound frames to a per-adapter virtual-thread executor (or a single
dedicated dispatch thread per connection, which preserves FIFO by
construction) and keep the reader loop pure: read, decode, hand off
(proposed by the opus-48 run).

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
