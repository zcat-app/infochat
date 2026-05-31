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
redteam_findings:
  - date: 2026-05-31
    category: INFO-LEAK
    severity: high
    promise: |
      "Stdout console logs pass through the closed API-key catalogue
      redactor, fail-closed on regex timeout (whole message replaced
      with a fixed sentinel). The audit_log writer consumes the same
      Redactor utility so the two cannot drift." +
      "User-content logging. chat_memory content, saved_post bodies
      and annotations, and the bodies of inbound chat-mode messages
      never appear in non-audit logs, at any log level (decision
      D37)." + "Contact IDs are logged in redacted form (prefix +
      ellipsis + suffix) outside the audit log."
    gap: |
      infochat-messaging-adapter/.../SimpleXSubprocess.java:285-287 —
      drainStream pipes raw simplex-chat stdout/stderr lines directly
      to SLF4J (LOG.warn("simplex-chat: {}", line) / LOG.info(...))
      with no Redactor pass, no contact-id redaction, and no
      chat-body suppression.
    repro: |
      A peer DMs the bot any message body containing what looks like
      an API key (e.g. sk-ant-abc...) or any prose. simplex-chat logs
      the inbound envelope to stdout. The drainer copies the line
      into the application log unredacted, violating D37, the
      contact-id redaction rule, and the API-key redactor commitment.
    suggested_fix_class: input-sanitization
  - date: 2026-05-31
    category: INJECTION
    severity: high
    promise: |
      "The adapter asserts identity via a stable, cryptographically
      anchored ID." (Trust boundary 1) + the spec's adapter-inbound
      boundary commits to validating system-boundary input. The
      simplex-chat command-string surface is the only path the
      adapter has into a privileged local subprocess.
    gap: |
      SimpleXMessageCodec.java:137-142 — targetSelector concatenates
      "@" + dm.contactId() (or "#" + g.adapterGroupId()) into the
      outbound simplex-chat command string with no validation that
      the id contains only the queue-address character set. contactId
      is sourced from inbound JSON (lines 236-241, 263-266) with no
      shape check, then echoed back into outbound /_send, /_update
      item, and /_set_contact_typing commands (lines 87-89, 109-115,
      132-134).
    repro: |
      A peer (or a compromised relay) registers a contact whose id
      contains the bytes "attacker_id on\n/_send @victim json
      {...}". The codec stores it in ScopeRef.Dm. When the Provider
      replies to that DM, encodeSendCommand produces a command line
      that, after the newline, contains a second /_send directed at
      the victim — sent under the bot's queue identity.
    suggested_fix_class: input-sanitization
  - date: 2026-05-31
    category: DOS
    severity: medium
    promise: |
      The capability surface in messaging.md is a contract:
      maxInboundMessageBytes declares the size cap above which
      inbound is rejected. The Provider trusts the adapter to honor
      its declared caps; otherwise downstream paths face inputs
      larger than the budget assumed.
    gap: |
      SimpleXAdapter.java:62-68 declares maxInboundMessageBytes =
      16 KiB. SimpleXMessageCodec.decodeNewChatItem (lines 255-258,
      263-270) extracts text from JSON and constructs InboundMessage
      with no length check. The only size bound is
      SimpleXWebSocketClient.MAX_FRAME_BYTES = 1_048_576 (line 54)
      — 64× the declared inbound cap.
    repro: |
      A peer sends a 1 MiB text body in a single newChatItem frame.
      The frame passes MAX_FRAME_BYTES, the codec deserializes it,
      and the InboundMessage with a 1 MiB body is dispatched to the
      Provider's InboundHandler — contradicting the declared
      capability flag.
    suggested_fix_class: input-sanitization
  - date: 2026-05-31
    category: INFO-LEAK
    severity: low
    promise: |
      "Exception messages and stack traces emitted via the
      application logger MUST NOT contain user-authored prose ...
      The application provides a SafeLog utility that drops the
      exception message body, retains only the exception class
      name..."
    gap: |
      SimpleXWebSocketClient.java:248-251 — dispatch catches
      MalformedFrameException and logs e.getMessage() directly.
      MalformedFrameException's message (SimpleXMessageCodec.java:194)
      embeds Jackson's parse error with byte fragments of the
      offending input. Similarly LOG.debug at line 253 and the
      Ignored(...) reason string at SimpleXMessageCodec.java:233
      interpolate untrusted JSON values into log messages without
      sanitization.
    repro: |
      A peer's relay delivers a malformed frame containing
      "sk-ant-abc-real-key-bytes-here...invalid". Jackson's parse
      error message includes the offending tokens; the warn-level
      log line carries the bytes verbatim, exposing them to
      operators with log access — SafeLog/Redactor bypassed.
    suggested_fix_class: input-sanitization
redteam_audits:
  - date: 2026-05-31
    verdict: FINDINGS
    base: ec1806c33e28ac6254f5fbd7239c66800cd7dc9f
    head: 0b24a7f794797fc026f41de9cc87f4680a9ae9b5
    verdict_file: docs/plan/m1/redteam/M1-103-2026-05-31.md
    findings_count: 4
    out_of_model_count: 2
    note: |
      Audit ran post-/m1-tick commit, pre-/m1-tick merge. 4 findings
      (2 high, 1 medium, 1 low) + 2 out-of-model observations.
      Ticket is `done` and commit is immutable; remediation must
      land as a new ticket with `remediates: M1-103`. Findings 1+4
      collapse into one redaction-coverage remediation (drainStream +
      MalformedFrame log); finding 2 (command injection) is highest
      priority — local-subprocess command injection under bot
      identity; finding 3 (size cap) is a single-line fix (enforce
      maxInboundMessageBytes in decodeNewChatItem rather than
      relying on MAX_FRAME_BYTES).
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
