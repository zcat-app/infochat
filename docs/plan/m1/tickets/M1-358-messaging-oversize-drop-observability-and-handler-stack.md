---
id: M1-358
title: "messaging: emit the adapter.inbound.dropped{reason=oversize} counter + WARN; log inbound-handler stack traces"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 9
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/metrics/AdapterMetrics.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/metrics
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The 16 KiB inbound cap value and the decode-time enforcement point — unchanged; this adds the missing observability around the existing drop.
  - The decision NOT to propagate a handler exception (dispatch-thread survival) — kept; only the logging detail changes.
  - D37's rule that inbound chat-mode message BODIES must not appear in non-audit logs — preserved; the exception MESSAGE stays suppressed, only the stack (class/method/file/line) is added.
acceptance:
  - "AdapterMetrics registers adapter.inbound.dropped{adapter, scope_kind, reason} as a counter (reason at least OVERSIZE and QUEUE_FULL); the oversize-drop sites in SimpleXMessageCodec, SignalMessageCodec, and SignalGroupHandler increment it with reason=oversize, and the existing queue-overflow drop is routed through the same counter."
  - "The oversize drop is logged at WARN (not DEBUG) with the redacted sender contactId and the adapterMessageId, per design §6.3.10."
  - "The inbound-handler catch in SimpleXAdapter.onInbound and SignalJsonRpcClient logs the exception class AND its stack trace (class/method/file/line) while still suppressing the exception message (D37); a Provider-side handler bug is now localizable from the log."
  - "Tests pin: an oversize inbound increments adapter.inbound.dropped{reason=oversize} and emits the WARN; a throwing handler logs a stack but not the message, and dispatch continues."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/metrics (dropped-counter registration test)
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex (oversize + handler-stack assertions)
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal (oversize + handler-stack assertions)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-358: oversize-drop observability + handler-stack logging

## Context

Two deep-review v6 findings on `infochat-messaging-adapter`, grouped (both are
"missing operator diagnostic signal at the inbound boundary"):

- **opus-47 `05-module-infochat-messaging-adapter.md` F1** (medium) — the
  transport-layer oversize-inbound drop is silent: no
  `adapter.inbound.dropped{reason=oversize}` counter and no WARN, contradicting
  design §6.3.10. **Verified 2026-06-14:** grep for `adapter.inbound.dropped`
  across the module returns nothing; `AdapterMetrics` registers
  `adapter.inbound.total` and `adapter.inbound.queue.size` but not
  `adapter.inbound.dropped`.
- **opus-47 `05-module-infochat-messaging-adapter.md` F3** (medium) — a
  misbehaving inbound handler's exception is reduced to its class name; both the
  message and the stack are dropped. **Verified 2026-06-14:**
  `SimpleXAdapter.java:716-721` logs only `e.getClass().getSimpleName()`. The
  Signal client mirrors this. Java stack traces carry no user content, so the
  stack can be logged without violating D37.

opus-48's messaging pass did not contradict either (it surfaced different,
lower items).

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The transport cap is a security load-shed; without an observable counter an
  operator under a flood has no signal it is firing.
- A Provider-code regression that throws per inbound today yields identical-shape
  WARN lines with no path to localize — the stack restores that.
