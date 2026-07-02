---
id: M1-542
title: Include cause chain in D37 inbound-handler stack log
status: pending
created: 2026-07-02
last_updated: 2026-07-02
blocked_by: []
files_budget: 4
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXInboundHandlerStackLogTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundHandlerStackLogTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - infochat-provider/**
  - any extraction of stackWithoutMessage into a shared utility (the two
    per-adapter copies stay as-is; this ticket changes their body only)
  - the WHEN of suppression — the catch sites in onInbound / group-invitation
    handling and their D37 drop-the-message behavior are unchanged
  - any file not listed in files_scope
acceptance:
  - SimpleXInboundHandlerStackLogTest.stackRenderingIncludesCauseChainFramesButNoMessages
    passes — a throwable with a chained cause (and a cause-of-cause) renders
    each level's class name and stack frames, with a "Caused by:" marker per
    level, and the rendered string contains NONE of the getMessage() text of
    ANY level.
  - SignalInboundHandlerStackLogTest.stackRenderingIncludesCauseChainFramesButNoMessages
    passes — same assertion against SignalJsonRpcClient.stackWithoutMessage.
  - The existing stackRenderingCarriesClassAndFramesButNotTheMessage test in
    both classes still passes (top-level class+frames present, message absent).
  - A self-referential / cyclic cause chain terminates (no infinite loop);
    rendering is bounded.
  - mvn verify is green.
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXInboundHandlerStackLogTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundHandlerStackLogTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Secrets handling
decision_refs:
  - D37
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-542: Include cause chain in D37 inbound-handler stack log

## Context

Live-e2e Phase 4b (finding F-live-2, `docs/plan/live-e2e/HANDOFF.md` §Live
findings) hit a real inbound-handler crash whose root cause was **invisible in
the logs**. `SimpleXAdapter.stackWithoutMessage(Throwable)` — and its identical
twin `SignalJsonRpcClient.stackWithoutMessage` — renders only the TOP throwable's
class name and stack frames; it never walks `getCause()`. When the actual failure
is a wrapped cause (here, an ARC bean-creation `RuntimeException` wrapping the real
reason), the log shows a bare top-level class with no explanation, and a Provider
inbound-handler bug cannot be diagnosed from prod logs.

The D37 constraint these methods enforce is "log the stack, never the message"
(`docs/spec/security.md` §Secrets handling): `getMessage()`/`toString()` may carry
inbound chat body bytes, but a `Throwable`'s class name and its `StackTraceElement`s
(class/method/file/line) carry no user content. That same argument extends to the
CAUSE chain: a cause's class name and frames are equally content-free, so they can
be appended safely — while a cause's message must remain suppressed exactly as the
top-level message is. This ticket makes both loggers walk and render the full cause
chain (frames yes, messages no), which restores diagnosability for F-live-1 and
every future inbound-handler failure.

## Acceptance

- New test `stackRenderingIncludesCauseChainFramesButNoMessages` in BOTH
  `SimpleXInboundHandlerStackLogTest` and `SignalInboundHandlerStackLogTest`:
  build a throwable with a chained cause and a cause-of-cause, each constructed
  with a DISTINCT sentinel message string; assert the rendered output (a) contains
  each level's class name and at least one of its stack frames, (b) contains a
  "Caused by:" marker for each cause level, and (c) contains NONE of the three
  sentinel message strings.
- The existing `stackRenderingCarriesClassAndFramesButNotTheMessage` test in both
  classes continues to pass unchanged in intent (top-level class + frames present,
  message absent).
- A cyclic cause chain (a throwable that is its own cause, or an A→B→A cycle)
  terminates and renders a bounded string — no `StackOverflowError`/infinite loop.
- `mvn verify` is green.

## Out-of-scope

The two `stackWithoutMessage` copies are intentionally NOT unified into a shared
helper here — that is a refactor beyond this diagnosability fix and would pull a
new shared class into scope; the surgical change is to each method body. The catch
sites (`onInbound`, the group-invitation handler) and their D37 "suppress the
message, drop the inbound" behavior are unchanged — this ticket only enriches what
the localizing log line renders. No Provider-side code changes. The existing
top-level tests are extended-alongside (new method added), not rewritten; the
pre-existing method keeps its current assertions.

## Notes

- Adjacent code / existing pattern: `SimpleXAdapter.stackWithoutMessage`
  (SimpleXAdapter.java:799) and `SignalJsonRpcClient.stackWithoutMessage`
  (SignalJsonRpcClient.java:978) — mirror the same change in both.
- Implementation shape: after rendering the top throwable's class + frames, loop
  `t = t.getCause()` appending `"\nCaused by: " + t.getClass().getName()` then its
  frames, until `getCause()` is null. Guard against cycles the way
  `Throwable.printStackTrace` does — track visited throwables in an identity set
  (or bound the depth) so a self-referential cause cannot loop forever.
- The security-relevant flag is set because this edits a D37 secret-redaction
  boundary: the threat-actor lens should confirm no cause-level `getMessage()`/
  `toString()` output can reach the log, only class names + `StackTraceElement`s.
- F-live-1 (the actual inbound crash this reveals) is tracked separately as M1-543,
  which is `blocked_by` this ticket — the cause it needs is only visible once this
  lands and the live DM is re-sent.
