---
id: M1-107
title: "Signal signal-cli JSON-RPC subprocess"
status: pending
created: 2026-05-26
last_updated: 2026-05-26
blocked_by:
  - M1-106
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageHandle.java
  - infochat-messaging-adapter/src/main/resources/application.properties
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocessTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodecTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - any change to MessagingAdapter SPI — the SPI is not modified
  - any change to InMemoryAdapter or SimpleXAdapter — unchanged
  - group support or mention recognition — M1-108
  - multi-adapter production IT — M1-109
acceptance:
  - "SignalSubprocess starts signal-cli in daemon mode as a child process via ProcessBuilder (e.g. signal-cli -a <account> daemon --socket <path> or --tcp <host:port>)"
  - "SignalSubprocess captures stdout/stderr for logging and detects process exit via Process.onExit()"
  - "On process crash, SignalSubprocess restarts with exponential backoff — same pattern as SimpleXSubprocess"
  - "After repeated crash-restart cycles exceeding the cap, the adapter transitions to failed state with throttled admin notification"
  - "SignalAdapter.start() starts the subprocess, waits for the JSON-RPC endpoint to become reachable, then connects"
  - "SignalAdapter.close() disconnects from JSON-RPC, sends SIGTERM, waits, then SIGKILL if needed"
  - "SignalJsonRpcClient connects to signal-cli's JSON-RPC endpoint and translates inbound Signal messages to (contact_id, scope, body) tuples delivered to the InboundHandler"
  - "SignalAdapter.send(OutboundMessage) sends via JSON-RPC and returns a SignalMessageHandle"
  - "SignalAdapter.update(handle, body) edits a previously sent message"
  - "SignalAdapter.finalize(handle, body) performs the terminal edit"
  - "SignalAdapter.setTyping(scope, true/false) sends typing indicator via JSON-RPC"
  - "Send/update/finalize failures are classified as transient or permanent per messaging.md §Failure handling"
  - "SignalSubprocessTest.startsAndStopsProcess passes"
  - "SignalSubprocessTest.crashRestartWithBackoff passes — same pattern as SimpleX"
  - "SignalJsonRpcClientTest.inboundMessageDelivered passes — a FakeSignalCli sends a message; the client delivers it to the InboundHandler"
  - "SignalJsonRpcClientTest.outboundSendReturnsHandle passes"
  - "SignalMessageCodecTest.encodesAndDecodesMessages passes — round-trip JSON-RPC message encoding/decoding"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocessTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodecTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Required SPI surface
  - docs/spec/messaging.md §Failure handling
  - docs/spec/messaging.md §Message handles
decision_refs:
  - D32
  - D46
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-107: Signal signal-cli JSON-RPC subprocess

## Context

The core Signal adapter implementation. Provider-managed subprocess
(decided 2026-05-26) using signal-cli in daemon mode with JSON-RPC.
Same subprocess pattern as SimpleX (M1-103) — ProcessBuilder start,
crash-restart with backoff, SIGTERM/SIGKILL shutdown.

`security_relevant: true` — production messaging channel with
transient/permanent failure classification.

## Acceptance

See frontmatter.

## Out-of-scope

- Group support, mention recognition — M1-108.
- Multi-adapter production IT — M1-109.
- SimpleX adapter — M1-102/M1-103 are frozen.

## Notes

- **signal-cli daemon modes.** signal-cli supports:
  (a) `signal-cli -a <number> daemon --tcp <host:port>` — TCP JSON-RPC
  (b) `signal-cli -a <number> daemon --socket <path>` — Unix socket
  (c) `signal-cli -a <number> jsonRpc` — stdin/stdout JSON-RPC
  Option (a) or (b) is preferred for clean separation; the
  implementer picks based on what's simplest for cross-platform
  testing. Option (c) requires stdin piping which is more fragile.
- **Subprocess management reuse.** By this point, SimpleXSubprocess
  (M1-103) exists. If the pattern is clean, extract a shared
  `SubprocessManager` utility. If the patterns diverge (different
  readiness checks, different signal handling), keep them separate.
  Don't force premature abstraction.
- **FakeSignalCli.** A test double that speaks signal-cli's JSON-RPC
  protocol over TCP or Unix socket. Receives send commands, returns
  canned responses, and can push inbound message events. Same
  test-double pattern as FakeSimpleXProcess.
- **JSON-RPC protocol.** signal-cli uses standard JSON-RPC 2.0.
  Methods include: `send`, `sendGroupMessage`, `sendTyping`,
  `updateMessage`. Events arrive as JSON-RPC notifications with
  method `receive`.
