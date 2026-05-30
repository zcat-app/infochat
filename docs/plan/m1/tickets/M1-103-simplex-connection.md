---
id: M1-103
title: "SimpleX subprocess + WebSocket messaging"
status: done
created: 2026-05-26
last_updated: 2026-05-31
blocked_by:
  - M1-102
files_budget: 12
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageHandle.java
  - infochat-messaging-adapter/src/main/resources/application.properties
  - infochat-messaging-adapter/pom.xml
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-core/** — no SPI changes
  - infochat-collector/** — no collector changes
  - any change to MessagingAdapter SPI — the SPI is not modified
  - any change to InMemoryAdapter — unchanged
  - group support or mention recognition — M1-104
  - multi-adapter wiring — M1-105
  - Signal adapter — M1-106..M1-109
acceptance:
  - "SimpleXSubprocess starts simplex-chat as a child process via ProcessBuilder with the configured binary, data-dir, and WebSocket port"
  - "SimpleXSubprocess captures stdout/stderr for logging and detects process exit via Process.onExit()"
  - "On process crash, SimpleXSubprocess restarts with exponential backoff (base delay, max delay, jitter — profile-driven)"
  - "After repeated crash-restart cycles exceeding a profile-driven cap, the adapter transitions to failed state and fires a throttled admin notification"
  - "SimpleXAdapter.start() starts the subprocess, waits for the WebSocket endpoint to become reachable, then connects"
  - "SimpleXAdapter.close() disconnects WebSocket, sends SIGTERM to the subprocess, waits up to a timeout, then SIGKILL if needed"
  - "SimpleXWebSocketClient connects to the simplex-chat WebSocket JSON API and translates inbound SimpleX messages to (contact_id, scope, body) tuples delivered to the InboundHandler"
  - "SimpleXAdapter.send(OutboundMessage) sends a message via the WebSocket JSON API and returns a SimpleXMessageHandle"
  - "SimpleXAdapter.update(handle, body) edits a previously sent message via the WebSocket API"
  - "SimpleXAdapter.finalize(handle, body) performs the terminal edit"
  - "SimpleXAdapter.setTyping(scope, true/false) sends typing indicator commands via the WebSocket API"
  - "Send/update/finalize failures are classified as transient or permanent per messaging.md §Failure handling and raised as MessagingException"
  - "SimpleXSubprocessTest.startsAndStopsProcess passes — the subprocess starts, the test verifies the process is running, then stop() terminates it"
  - "SimpleXSubprocessTest.crashRestartWithBackoff passes — a FakeSimpleXProcess that exits immediately is restarted with increasing delays up to the cap"
  - "SimpleXWebSocketClientTest.inboundMessageDelivered passes — a fake WebSocket server sends a message; the client delivers it to the InboundHandler"
  - "SimpleXWebSocketClientTest.outboundSendReturnsHandle passes — send() dispatches via the WebSocket and returns a handle"
  - "SimpleXMessageCodecTest.encodesAndDecodesMessages passes — round-trip encoding/decoding of SimpleX JSON API messages"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
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
    date: 2026-05-31
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 2161
      removed: 49
  - round: 2
    date: 2026-05-31
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 2230
      removed: 51
escalations:
  - date: 2026-05-31
    reason: round-cap
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL
        The diff touches `infochat-messaging-adapter/pom.xml` (adds the
        jackson-databind dependency) but pom.xml is NOT listed in the
        ticket's `files_scope`. Per the budget+scope rule, when `files_scope`
        is non-empty every diffed file must match an entry in it; a diffed
        file outside `files_scope` is automatic FAIL on SCOPE-DRIFT-CHECK.
        The numeric `files_budget: 12` is satisfied (13 files − 2 lifecycle
        exemptions = 11 implementation files ≤ 12), so the budget itself
        passes — only the path-membership check fails. The jackson-databind
        addition is reasonable on the merits (SimpleXMessageCodec needs JSON
        parsing), so the fix is to add `infochat-messaging-adapter/pom.xml`
        to `files_scope` in the ticket frontmatter, not to revert the dep.
      Note: round-cap (3) was not exhausted — escalating early because the
      single rework item is a frontmatter-scope expansion that workflow
      rules require to go through escalate→refine rather than in-place
      rework.
revisions:
  - date: 2026-05-31
    reason: round-cap (round 1 REWORK; scope expansion to add pom.xml to files_scope)
    snapshot:
      files_scope:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageHandle.java
        - infochat-messaging-adapter/src/main/resources/application.properties
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodecTest.java
        - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
outline_file: target/m1-tick-outline-M1-103.md
clarity_check:
  date: 2026-05-30
  verdict: WARN
  warnings:
    - "Acceptance item 4 (failed state + throttled admin notification after cap exhaustion) has no named test method — verified by code inspection only"
    - "Acceptance item 12 (transient/permanent failure classification) names §Failure handling rather than stating a checkable assertion and has no named test method"
    - "SELF-CONTAINED-CHECK WARN: acceptance item 12 partially delegates to §Failure handling; ticket Notes inline the rule but the item itself does not"
  blockers: []
---

# M1-103: SimpleX subprocess + WebSocket messaging

## Context

The core SimpleX adapter implementation. Provider-managed subprocess
(decided 2026-05-26): the Provider starts `simplex-chat` as a child
process via ProcessBuilder and communicates via the WebSocket JSON API.

`security_relevant: true` — this is the first production messaging
channel. Failure handling, crash recovery, and message classification
(transient vs permanent) are security-load-bearing.

## Acceptance

See frontmatter. Subprocess lifecycle + WebSocket connection + full
send/receive/update/finalize/typing implementation.

## Out-of-scope

- Group support, mention recognition — M1-104.
- Multi-adapter wiring — M1-105.
- MessagingAdapter SPI changes — SPI is not modified.

## Notes

- **Subprocess management utility.** This is the first subprocess
  in the codebase. The `SimpleXSubprocess` class can be generalized
  later (M1-107 needs the same pattern for signal-cli), but for v1
  keep it SimpleX-specific and extract if the pattern stabilizes.
- **simplex-chat WebSocket API.** The API is JSON over WebSocket.
  Commands: `apiSendMessage`, `apiUpdateMessage`, `apiDeleteMessage`,
  `apiSetContactTyping`, etc. Events arrive as JSON objects with a
  `type` field. The implementer should reference the simplex-chat
  documentation for the exact API surface.
- **FakeSimpleXProcess.** A test double that mimics the simplex-chat
  subprocess: starts quickly, exposes a WebSocket endpoint on a
  random port, responds to API commands with canned responses. This
  avoids needing the real simplex-chat binary in CI.
- **Transient vs permanent failure classification.** Per
  `messaging.md` §Failure handling: network timeouts, WebSocket drops
  → transient. User blocked bot, group not found → permanent. If
  ambiguous → permanent (spec rule).
- **Design reference:** `docs/design/06-messaging.md` for the wire
  protocol details and capability defaults.

## Round 1 rework

Reviewer verdict: REWORK — SCOPE-DRIFT-CHECK FAIL.

1. Add `infochat-messaging-adapter/pom.xml` to the ticket's
   `files_scope` frontmatter so the jackson-databind dependency
   addition (already present in `infochat-messaging-adapter/pom.xml`
   with an inline justification comment) is inside the declared
   scope. The dep itself is necessary for SimpleXMessageCodec's JSON
   parsing and should NOT be reverted; the fix is purely a
   frontmatter update. After the edit `files_scope` grows from 10 to
   11 entries and the numeric `files_budget: 12` still holds.

   Per workflow rules ("Never silently expand a ticket's files_scope
   — frontmatter changes go through escalate → refine"), this scope
   expansion must be applied via `/m1-tick escalate M1-103 refine`,
   not by editing the frontmatter in-place during rework.
