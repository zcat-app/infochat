---
id: M1-107
title: "Signal signal-cli JSON-RPC subprocess"
status: done
created: 2026-05-26
last_updated: 2026-05-30
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
reviews:
  - round: 1
    date: 2026-05-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
      spec_conformance: PASS
      parameter_contract: PASS
    diff_stats:
      files: 12
      added: 1885
      removed: 40
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-05-30
    category: INFO-LEAK
    severity: medium
    promise: |
      D37 — bodies of inbound chat-mode messages never appear in non-audit
      logs, at any log level. Stdout console logs pass through the closed
      API-key catalogue redactor.
    gap: |
      SignalJsonRpcClient.java:315 — LOG.warnf(e, "ignoring malformed
      JSON-RPC line: %s", line) logs the entire raw inbound line at WARN.
      No Redactor applied; Throwable passed to SLF4J violates §"User
      content in exceptions" requirement to route via SafeLog.
    repro: |
      Local-loopback caller injects a malformed JSON-RPC envelope whose
      payload embeds user-content or API-key-shaped substring; codec
      decode throws; raw line logged verbatim.
    suggested_fix_class: audit-log-coverage
  - date: 2026-05-30
    category: DOS
    severity: medium
    promise: |
      DOS — Resource exhaustion, unbounded loops. Adapter publishes
      maxInboundMessageBytes=16_384 as a capability (Trust boundary 1
      owns inbound size enforcement).
    gap: |
      SignalAdapter.java:77 declares the cap; SignalJsonRpcClient.java:302
      reads inbound lines via BufferedReader.readLine() with no length
      cap. The capability is documentary; the reader does not enforce it.
    repro: |
      A buggy/compromised signal-cli or local-loopback peer writes a
      single line that grows without bound; readLine() accumulates until
      OOM, well before any app-level size check fires.
    suggested_fix_class: input-sanitization
  - date: 2026-05-30
    category: DOS
    severity: medium
    promise: |
      DOS — Resource exhaustion, unbounded loops. Adapter classes must
      not retain per-message state indefinitely.
    gap: |
      SignalJsonRpcClient.java:84-85 — handles and finalized
      ConcurrentHashMaps grow without bound; put on every send() /
      finalizeHandle(); no path removes entries (no TTL, no finalize
      eviction, no disconnect() cleanup — disconnect clears pending
      only). Each entry retains the original OutboundMessage (incl.
      message text).
    repro: |
      Long-running Provider with sustained outbound digest+chat traffic
      accumulates one SignalMessageHandle per sent message forever;
      heap exhaustion DoS.
    suggested_fix_class: rate-limit
  - date: 2026-05-30
    category: INFO-LEAK
    severity: low
    promise: |
      "Contact IDs are logged in redacted form (prefix + ellipsis +
      suffix) outside the audit log." §"User content in exceptions"
      — exception messages MUST NOT contain user-authored prose or
      command arguments; SafeLog is the spec-mandated utility.
    gap: |
      SignalJsonRpcClient.java:264-265 builds MessagingException with
      raw signal-cli error text ("signal-cli error " + code + ": " +
      msg). signal-cli errors routinely embed destination contact IDs
      / phone numbers. Adapter does not pre-redact; if upstream logs
      e.getMessage() without SafeLog, the unredacted contact id leaks.
    repro: |
      /ban after a contact's Signal identity rotated → signal-cli
      returns -32602 "identity for <ACI> revoked"; MessagingException
      carries the full ACI; standard error logging in Provider leaks
      it cleartext.
    suggested_fix_class: audit-log-coverage
  - date: 2026-05-30
    category: INFO-LEAK
    severity: low
    promise: |
      D37 — bodies of inbound chat-mode messages never appear in
      non-audit logs, at any log level.
    gap: |
      SignalSubprocess.drainAndLog — LOG.debugf("signal-cli: %s",
      line) logs the merged stdout+stderr stream of signal-cli
      verbatim. signal-cli emits message metadata (recipient addresses,
      timestamp echoes, body excerpts in some modes); no Redactor
      applied. Spec says "at any log level" — DEBUG is not a defense.
    repro: |
      Operator enables DEBUG on app.zcat.infochat.messaging.impl.signal;
      signal-cli's own logger emits a line referencing destination ACI;
      wrapper passes through LOG.debugf without redaction.
    suggested_fix_class: audit-log-coverage
redteam_audits:
  - date: 2026-05-30
    verdict: FINDINGS
    base: 3b42854^
    head: 3b42854
    verdict_file: docs/plan/m1/redteam/M1-107-2026-05-30.md
    findings_count: 5
    out_of_model_count: 2
    note: |
      Five findings (3 medium INFO-LEAK/DOS, 2 low INFO-LEAK), all in
      the Signal-adapter cluster: log redaction discipline,
      maxInboundMessageBytes capability enforcement, and unbounded
      growth of the handles/finalized maps. M1-107 is done →
      remediation belongs in a follow-up ticket with `remediates:
      M1-107`. Two OUT-OF-MODEL items document the localhost-trust
      delegation already acknowledged in SignalSubprocess Javadoc.
outline_file: target/m1-tick-outline-M1-107.md
clarity_check:
  date: 2026-05-30
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE / SELF-CONTAINED-CHECK: Acceptance item 12 partially delegates the implementation-specific error→category mapping to messaging.md §Failure handling rather than inlining the Signal-specific examples."
    - "FILES-BUDGET / files_scope: §Notes mentions optionally extracting a shared SubprocessManager; if extracted, the implementer will exceed the 10 files in files_scope but stay within the 12-file budget."
  blockers: []
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
