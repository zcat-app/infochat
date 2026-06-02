---
id: M1-132
title: "Signal/SimpleX adapter resilience (handler isolation, hung-process, config-validate, send/close race)"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 9
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - capability-flag reconciliation / cross-adapter contract test (covered by M1-147)
  - SPI lifecycle (finalize→shutdown) (covered by M1-148)
  - the SimpleX mention parser (covered by M1-137)
acceptance:
  - "A RuntimeException thrown by handler.onMessage no longer kills the signal-jsonrpc-reader thread: it is caught (RuntimeException), logged class-name-only (D37, no user bytes), the message dropped, and the reader keeps running — covered by a test that throws from the handler and asserts subsequent messages still deliver"
  - "signal-cli hung-process detection: a consecutive-timeout counter restarts the subprocess after N timeouts (the watchdog currently only sees Process.onExit)"
  - "SignalConfig.validate javadoc no longer overpromises a permanent boot guarantee (softened to boot-time-only and/or re-checked at adapter start), matching SimpleXConfig"
  - "SimpleXWebSocketClient.sendCommand racing with close() throws MessagingException(PERMANENT), not a raw IllegalStateException"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
  - docs/spec/messaging.md §Per-adapter trust level and identity
decision_refs:
  - D37
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-132: Signal/SimpleX adapter resilience

## Context

Four convergent adapter-robustness gaps that keep an adapter "alive but useless":

- **A10 (High)** — any `RuntimeException` from `handler.onMessage`
  (`SignalJsonRpcClient.java:433`) propagates through `readerLoop` and kills the
  reader thread; the subprocess stays alive (no restart), so Signal is half-dead
  indefinitely. `SimpleXAdapter.onInbound` wraps its handler call; Signal does not.
- **B-SIGNAL-HUNG** — the watchdog detects `Process.onExit()` but not a
  deadlocked-but-alive subprocess; JSON-RPC calls time out at 15s with no
  consecutive-timeout escalation.
- **B-SIGNALCONFIG-BOOT** — `SignalConfig.validate()` javadoc promises misconfig
  fails at boot, but the `Files.exists`/`isWritable` check is a single instant; a
  post-boot remount defeats it.
- **B-SIMPLEX-RACE** — `SimpleXWebSocketClient.sendCommand` can race `close()`
  between the `closed` check and `ws.sendText()`; the `IllegalStateException`
  escapes the catch set as a raw RuntimeException.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. The capability-flag and SPI-lifecycle work is split into
M1-147 / M1-148 to keep diffs reviewable within the shared module.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A10, §B-SIGNAL-HUNG, §B-SIGNALCONFIG-BOOT,
  §B-SIMPLEX-RACE; `opus-47-full-handout.md` §F-MAINT-22/23/56/57; `opus-48-audit-handout.md` §A4, B9, B14.
- Mirror the existing `SimpleXAdapter.onInbound` try/catch shape for the Signal handler.
