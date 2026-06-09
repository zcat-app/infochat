---
id: M1-253
title: "Messaging-adapter consistency lows: virtual threads + Random"
status: pending
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 5
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Signal inbound decode boundary (SignalMessageCodec / SignalGroupHandler) — owned by M1-242; this ticket touches only the adapter/transport/subprocess plumbing.
  - The dispatch concurrency model (concurrency=1, FIFO ordering held by the executor) — unchanged; only the thread TYPE moves from platform to virtual.
  - The reconnect/backoff policy and the SimpleX subprocess lifecycle — unchanged; T10/T23 are thread-type and RNG-source swaps, not behavior changes.
acceptance:
  - "T10: SignalAdapter's reconnect thread is created with Thread.ofVirtual() (matching the SimpleXAdapter sibling, which already does), and the two single-thread dispatch executors (SignalJsonRpcClient, SimpleXWebSocketClient) use Thread.ofVirtual().factory() instead of Thread.ofPlatform(), consistent with the project's virtual-thread policy for blocking handler callbacks. Before the change confirm the handler paths use ReentrantLock / concurrent collections (not synchronized, which pins a virtual thread); if any synchronized block pins, surface it rather than silently leaving it."
  - "T23: SimpleXSubprocess uses ThreadLocalRandom (matching its sibling) instead of a java.util.Random instance for its non-cryptographic jitter/selection use; behavior is equivalent."
  - "The existing messaging-adapter tests stay green (no new behavioral assertion is required for these type-only swaps; if a reconnect/dispatch test exists, it must remain green)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-253: Messaging-adapter consistency lows

## Context

Two low/medium consistency findings on the messaging-adapter transport plumbing,
grouped because both are "one adapter does it the project-policy way, its sibling
doesn't." Source: `deep-code-review/v3/` UNIFIED-REPORT.md T10 (mimo `05#F2` +
`05#F3`) and T23 (mimo `05#F4`).

- **T10 [medium, perf/consistency].** `SignalAdapter` reconnect uses
  `new Thread(...)` (platform) while `SimpleXAdapter` uses `Thread.ofVirtual()`;
  both dispatch executors use `Thread.ofPlatform()` for a single worker that
  calls handler callbacks documented to block on DB/LLM. The report calibrates
  this as a small consistency fix, not a throughput win (concurrency=1, FIFO held
  by the executor) — the cross-adapter inconsistency is the motivation.
- **T23 [low].** `SimpleXSubprocess` uses `java.util.Random` where its sibling
  uses `ThreadLocalRandom`.

## Acceptance

See frontmatter. In prose: move the Signal reconnect and the two single-thread
dispatch executors to virtual threads (after confirming no `synchronized` pinning
on the handler paths), and switch `SimpleXSubprocess` to `ThreadLocalRandom`.
Existing tests stay green; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The Signal inbound decode boundary (M1-242), the dispatch
concurrency model, reconnect policy, and subprocess lifecycle are untouched.

## Notes

- The `synchronized`-pins-virtual-threads check is load-bearing: if a handler
  path holds a `synchronized` monitor across a blocking call, moving it to a
  virtual thread pins a carrier — surface that as a finding (it would be a
  separate fix) rather than completing the swap and leaving a pinning hazard.
- These are type-only swaps with no behavioral delta, so no new test is mandated;
  keep any existing reconnect/dispatch tests green.
</content>
</invoke>
