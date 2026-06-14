---
id: M1-358
title: "messaging: emit the adapter.inbound.dropped{reason=oversize} counter + WARN; log inbound-handler stack traces"
status: done
created: 2026-06-14
last_updated: 2026-06-14
clarity_check:
  date: 2026-06-14
  verdict: WARN
  warnings:
    - "SECURITY-FLAG-CONSISTENT: D37 inbound-body suppression is preserved as an explicit acceptance constraint while logging is extended at the inbound boundary; consider security_relevant: true so redteam covers D37 compliance. Proceeds without the change."
    - "COMPLEXITY-RISK-CALIBRATED: risk: low is defensible (additive metrics/logging, no persistence, no auth). Informational only."
  blockers: []
blocked_by: []
files_budget: 24
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/metrics/AdapterMetrics.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalGroupHandler.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
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
  - "AdapterMetrics registers adapter.inbound.dropped{adapter, scope_kind, reason} as a counter (reason at least OVERSIZE and QUEUE_FULL). Every oversize drop detected at a decode boundary (SimpleXMessageCodec, SignalMessageCodec, SignalGroupHandler) increments it with reason=oversize. The decode-time cap check itself is unchanged (out_of_scope); the counter+WARN are emitted at the point that observes the drop with the sender and adapterMessageId in hand — for SimpleX that is the oversize-drop consumption point in SimpleXWebSocketClient (the codec surfaces the dropped-frame's sender contactId + adapterMessageId + scope so the consumer can attribute it), for Signal the codec/SignalJsonRpcClient (DM) and SignalGroupHandler (group). Both existing inbound queue-overflow drops — SimpleXWebSocketClient.dispatchAsync and SignalJsonRpcClient — are routed through the same counter with reason=queue_full."
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
reviews:
  - round: 1
    date: 2026-06-14
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 26
      added: 667
      removed: 154
  - round: 2
    date: 2026-06-14
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 26
      added: 726
      removed: 155
escalations:
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — surfaced at implementation time, pre-code. The SimpleX oversize
      drop is emitted by the static SimpleXMessageCodec.decode as
      Ignored("...exceeds-inbound-cap") and consumed only at
      SimpleXWebSocketClient.java:465-466; the SimpleX queue-overflow drop
      lives at SimpleXWebSocketClient.java:496-498. Both sites are outside
      files_scope, and out_of_scope forbids moving the decode-time
      enforcement point — so the counter + WARN for SimpleX cannot be emitted
      within the current files_scope. Signal side likely also needs
      SignalAdapter.java (SignalGroupHandler has no AdapterMetrics and is
      constructed there). files_budget: 9 is already fully consumed.
  - date: 2026-06-14
    reason: budget-breach
    reviewer_verdict_excerpt: |
      SCOPE-DRIFT-CHECK: FAIL (review round 1). The diff touches 24
      non-lifecycle files (8 production, all within files_scope; 16 test, all
      under the three scoped test directories) against files_budget: 12. The
      round-1 9→12 refine added the two structurally-required production
      consumption sites but did not account for the test-file count: the
      extractDm Optional→DmDecode sealed-interface migration plus the
      SignalGroupHandler AdapterMetrics 4th-arg addition fan out to 13
      pre-existing test files plus 3 new test files. Reviewer: "No code change
      is implied — the file set traces cleanly to the acceptance items; only
      the numeric budget is wrong."
revisions:
  - date: 2026-06-14
    reason: budget-breach refine (round 1) — widen files_scope to the structurally-required consumption sites
    snapshot:
      files_budget: 9
      files_scope_added:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
      acceptance_item_1_was: |
        AdapterMetrics registers adapter.inbound.dropped{adapter, scope_kind, reason} as a counter (reason at least OVERSIZE and QUEUE_FULL); the oversize-drop sites in SimpleXMessageCodec, SignalMessageCodec, and SignalGroupHandler increment it with reason=oversize, and the existing queue-overflow drop is routed through the same counter.
  - date: 2026-06-14
    reason: budget-breach refine (round 1, post-review) — widen files_budget 12→24 to cover the test-file fan-out; no files_scope or acceptance change, no code change
    snapshot:
      files_budget: 12
      files_scope_unchanged: true
      breach_detail: |
        Review round 1 SCOPE-DRIFT-CHECK FAIL: 24 non-lifecycle files (8
        production within files_scope + 16 test under the three scoped test
        directories) against files_budget: 12. The 9→12 refine accounted only
        for the two added production consumption sites, not the test-file count
        that the extractDm Optional→DmDecode sealed migration and the
        SignalGroupHandler metrics-arg cascade force (13 modified pre-existing
        tests + 3 new tests). Reviewer confirmed no code change is implied.
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

## Round 1 rework

Reviewer verdict (round 1): REWORK — 1 item. All substantive checks PASS
(test-integrity, out-of-scope, negative-space, acceptance, spec-conformance);
the sole FAIL is SCOPE-DRIFT-CHECK on a numeric `files_budget` breach.

1. Resolve the `files_budget` breach. The diff touches 24 non-lifecycle files
   against `files_budget: 12`: 8 production files (all within the explicit
   `files_scope` list) and 16 test files (all under the three scoped test
   directories). The round-1 9→12 refine widened the budget to admit the two
   structurally-required production consumption sites (SimpleXWebSocketClient,
   SignalAdapter) but did not account for the test-file count: the extractDm
   Optional→DmDecode sealed-interface migration plus the SignalGroupHandler
   AdapterMetrics 4th-arg addition fan out to 13 pre-existing test files plus 3
   new test files. The reviewer states no code change is implied — the file set
   traces cleanly to the acceptance items; only the numeric budget is wrong.
   Resolution requires `escalate → refine` to widen `files_budget` (frontmatter
   changes never happen silently); no code rework.
