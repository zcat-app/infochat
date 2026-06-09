---
id: M1-253
title: "Messaging-adapter consistency: virtual-thread dispatch parity"
status: done
created: 2026-06-09
last_updated: 2026-06-09
blocked_by: []
files_budget: 4
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The Signal inbound decode boundary (SignalMessageCodec / SignalGroupHandler) — owned by M1-242; this ticket touches only the adapter/transport plumbing.
  - The dispatch concurrency model (concurrency=1, FIFO ordering held by the executor) — unchanged; only the thread TYPE moves from platform to virtual.
  - The reconnect/backoff policy and the SimpleX subprocess lifecycle — unchanged; T10 is a thread-type swap, not a behavior change. The SignalJsonRpcClient reader thread (signal-jsonrpc-reader) is also unchanged — only the dispatch executor moves to virtual.
  - "The java.util.Random -> ThreadLocalRandom swap in SimpleXSubprocess (former T23) — REJECTED, not deferred. SimpleXSubprocess's injected Random is a deliberate test-determinism seam: backoffDelay(int, Duration, Duration, Random) is package-private and Random-injected so SimpleXSubprocessTest can pin jitter to 0/max and assert exact delays, and SimpleXSubprocessTest + SimpleXReconnectTest construct the subprocess with new Random(0L). ThreadLocalRandom.current() cannot be seeded or substituted, so the swap would break those (out-of-scope) tests or force weakening their deterministic assertions. The injected-Random pattern is the better, testable one; the sibling SignalSubprocess's ThreadLocalRandom divergence is accepted, not a defect."
acceptance:
  - "T10: SignalAdapter's reconnect thread is created with Thread.ofVirtual() (matching the SimpleXAdapter sibling, which already does), and the two single-thread dispatch executors (SignalJsonRpcClient, SimpleXWebSocketClient) use Thread.ofVirtual().factory() instead of Thread.ofPlatform().daemon().factory(), for cross-adapter consistency with the project's virtual-thread policy for blocking handler callbacks. (The original synchronized-pinning concern is MOOT on the JDK 25 target: JEP 491, delivered as a final feature in JDK 24, removed synchronized pinning of virtual threads, so no pinning audit is required and the existing synchronized blocks on the handler paths are fine. This is a pure consistency swap.)"
  - "The existing messaging-adapter tests stay green (no new behavioral assertion is required for these type-only swaps; if a reconnect/dispatch test exists, it must remain green)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-09
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 101
      removed: 43
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-09
  verdict: PASS
  warnings: []
  blockers: []
escalations:
  - date: 2026-06-09
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise-fail, escalated before any implementation. T23
      ("SimpleXSubprocess: java.util.Random -> ThreadLocalRandom, behavior
      equivalent, type-only swap") is not behavior-neutral: the injected Random
      is a deliberate test-determinism seam. backoffDelay(...,Random) is
      package-private and Random-injected so SimpleXSubprocessTest pins jitter
      to 0/max and asserts exact delays (5/10/20/40 ms), and
      SimpleXSubprocessTest + SimpleXReconnectTest construct with new Random(0L).
      ThreadLocalRandom cannot be seeded, so the swap would break those tests
      (both outside files_scope) or weaken their deterministic-jitter
      assertions. The clarity pre-flight PASS did not sweep these call sites.
revisions:
  - date: 2026-06-09
    reason: "premise-fail refine — drop T23 (Random swap rejected), keep T10 (virtual-thread parity)"
    snapshot:
      title: "Messaging-adapter consistency lows: virtual threads + Random"
      files_budget: 5
      files_scope:
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
        - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
      acceptance:
        - "T10: SignalAdapter's reconnect thread is created with Thread.ofVirtual() (matching the SimpleXAdapter sibling, which already does), and the two single-thread dispatch executors (SignalJsonRpcClient, SimpleXWebSocketClient) use Thread.ofVirtual().factory() instead of Thread.ofPlatform(), consistent with the project's virtual-thread policy for blocking handler callbacks. Before the change confirm the handler paths use ReentrantLock / concurrent collections (not synchronized, which pins a virtual thread); if any synchronized block pins, surface it rather than silently leaving it."
        - "T23: SimpleXSubprocess uses ThreadLocalRandom (matching its sibling) instead of a java.util.Random instance for its non-cryptographic jitter/selection use; behavior is equivalent."
        - "The existing messaging-adapter tests stay green (no new behavioral assertion is required for these type-only swaps; if a reconnect/dispatch test exists, it must remain green)."
        - "mvn -B clean verify from the repo root exits 0."
      out_of_scope:
        - "The Signal inbound decode boundary (SignalMessageCodec / SignalGroupHandler) — owned by M1-242; this ticket touches only the adapter/transport/subprocess plumbing."
        - "The dispatch concurrency model (concurrency=1, FIFO ordering held by the executor) — unchanged; only the thread TYPE moves from platform to virtual."
        - "The reconnect/backoff policy and the SimpleX subprocess lifecycle — unchanged; T10/T23 are thread-type and RNG-source swaps, not behavior changes."
---

# M1-253: Messaging-adapter consistency — virtual-thread dispatch parity

## Context

A consistency finding on the messaging-adapter transport plumbing: one adapter
does it the project-policy way, its sibling doesn't. Source:
`deep-code-review/v3/` UNIFIED-REPORT.md T10 (mimo `05#F2` + `05#F3`).

- **T10 [medium, consistency].** `SignalAdapter` reconnect uses
  `new Thread(...)` (platform) while `SimpleXAdapter` uses `Thread.ofVirtual()`;
  both dispatch executors use `Thread.ofPlatform()` for a single worker that
  calls handler callbacks documented to block on DB/LLM. The report calibrates
  this as a small consistency fix, not a throughput win (concurrency=1, FIFO
  held by the executor) — the cross-adapter inconsistency is the motivation.

The original deep-review pairing also carried **T23** (`SimpleXSubprocess`
`java.util.Random` -> `ThreadLocalRandom`). T23 was **rejected on premise-fail
refine** (2026-06-09): it is not the behavior-neutral type swap the finding
claimed. `SimpleXSubprocess`'s injected `Random` is a deliberate
test-determinism seam — `backoffDelay(int, Duration, Duration, Random)` is
package-private and `Random`-injected precisely so `SimpleXSubprocessTest` can
pin jitter to 0/max and assert exact delays, and both `SimpleXSubprocessTest`
and `SimpleXReconnectTest` construct the subprocess with `new Random(0L)`.
`ThreadLocalRandom.current()` cannot be seeded or substituted, so the swap
would break those (out-of-scope) tests or weaken their deterministic
assertions. The injected-`Random` pattern is the better, testable one; the
sibling `SignalSubprocess`'s `ThreadLocalRandom` divergence is accepted. See
`revisions:` / `escalations:` for the audit trail.

### A note on the original "synchronized pins a virtual thread" guard

The original T10 acceptance asked the implementer to confirm the handler paths
avoid `synchronized` (which "pins a virtual thread") before swapping. **This
premise is moot on the JDK 25 target.** JEP 491 — "Synchronize Virtual Threads
without Pinning", delivered as a *final* (non-preview) feature in JDK 24 —
removed carrier pinning for virtual threads blocked inside `synchronized`
methods/blocks or on a monitor. (`ReentrantLock` was the JDK 21–23 workaround;
JDK 24 fixed the underlying issue; JDK 25 inherits it. Residual pinning survives
only in native/VM frames.) The `synchronized` blocks on these handler paths
(`SignalJsonRpcClient` writer/handles monitors, `SimpleXWebSocketClient`
`sendLock`) are therefore fine, and no pinning audit is required. T10 is a pure
consistency swap.

## Acceptance

See frontmatter. In prose: move the Signal reconnect thread and the two
single-thread dispatch executors (SignalJsonRpcClient, SimpleXWebSocketClient)
to virtual threads, matching the SimpleXAdapter sibling. Existing tests stay
green; `mvn verify` is 0.

## Out-of-scope

See frontmatter. The Signal inbound decode boundary (M1-242), the dispatch
concurrency model, the reconnect/backoff policy, the SimpleX subprocess
lifecycle, the SignalJsonRpcClient reader thread, and the rejected
`Random`->`ThreadLocalRandom` swap are all untouched.

## Notes

- These are type-only swaps with no behavioral delta, so no new test is
  mandated; keep any existing reconnect/dispatch tests green.
- Virtual threads are always daemon, so the `.daemon()` / `setDaemon(true)`
  calls drop out of the swapped sites (a platform-thread-only affordance).
