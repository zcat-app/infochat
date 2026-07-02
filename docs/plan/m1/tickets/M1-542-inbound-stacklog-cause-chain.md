---
id: M1-542
title: Include cause chain in D37 inbound-handler stack log
status: done
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
  - A cause chain deeper than the cap (5 levels) is depth-bounded: at most 5
    levels render (4 "Caused by:" markers) followed by an explicit truncation
    marker, and no level's message leaks — pinned by
    stackRenderingDepthCapsCauseChain in both test classes. (redteam-finding
    rework: bounds log size to the spec's SafeLog depth-5 precedent.)
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
reviews:
  - round: 1
    date: 2026-07-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 164
      removed: 18
  - round: 2
    date: 2026-07-02
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 300
      removed: 20
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-02
    category: DOS
    severity: low
    promise: |
      spec §"User content in exceptions": SafeLog truncates the cause chain to
      class names, depth-capped at 5 (bounds exception-log size).
    gap: |
      The rewritten stackWithoutMessage walks the FULL cause chain with no depth
      cap and emits every level's full stack frames. Cycle guard prevents infinite
      loops but not unbounded depth on an acyclic chain.
    repro: |
      A deep-but-acyclic wrapped-exception chain renders every level's frames into
      one log line, unbounded by the spec's depth-5 cap. Not attacker-driven
      (nesting depth is internal call structure, not inbound content); no user
      content leaks (frames are content-free, suppression proven by tests). A
      defense-in-depth / resilience gap, not a reachable exploit.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-07-02
    verdict: FINDINGS
    base: main
    head: m1/M1-542-inbound-stacklog-cause-chain
    verdict_file: docs/plan/m1/redteam/M1-542-2026-07-02.md
    findings_count: 1
    out_of_model_count: 1
    note: |
      1 low DOS/defense-in-depth finding (unbounded cause-chain depth). Not a
      content leak and not a strict spec violation (depth-5 binds SafeLog, not the
      full-frame localization logger). User chose refine; remediated in-branch with
      a depth cap (5) + truncation marker. Re-audited CLEAN (entry below).
  - date: 2026-07-02
    verdict: CLEAN
    base: main
    head: m1/M1-542-inbound-stacklog-cause-chain
    verdict_file: docs/plan/m1/redteam/M1-542-2026-07-02-r2.md
    out_of_model_count: 1
    note: |
      Post-remediation re-audit of the round-2 tip. CLEAN — the depth cap closed
      the round-1 low finding; remediation is purely bounding (no new content path).
clarity_check:
  date: 2026-07-02
  verdict: WARN
  warnings:
    - "Acceptance item 4 (cyclic cause chain terminates) does not name a test method/class verifying the cycle property; add a named method or fold it into item 1's test. Resolved in implementation via a named stackRenderingTerminatesOnCyclicCause test in both classes."
  blockers: []
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
- A cause chain deeper than 5 levels is truncated: at most 5 levels render (4
  "Caused by:" markers), then an explicit truncation marker; no level's message
  leaks. Pinned by `stackRenderingDepthCapsCauseChain` in both test classes.
  (Added on redteam-finding rework — the low DOS finding that unbounded depth
  could bloat a log line; bounds it to the spec's SafeLog depth-5 precedent.)
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
