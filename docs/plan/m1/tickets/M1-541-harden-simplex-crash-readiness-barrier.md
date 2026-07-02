---
id: M1-541
title: Harden signalCrashDoesNotAffectSimpleX readiness barrier
status: done
created: 2026-07-02
last_updated: 2026-07-02
clarity_check:
  date: 2026-07-02
  verdict: PASS
  warnings: []
  blockers: []
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
      files: 3
      added: 12
      removed: 8
blocked_by: []
files_budget: 1
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any src/main change. This is a test-timing determinism fix only; the adapters
    behave correctly.
  - >-
    Any assertion change. Only the readiness-barrier timeout value changes; the
    post-crash liveness probe (awaitFrame) and its assertNotNull are untouched.
  - >-
    The Signal-side barrier in the sibling simpleXCrashDoesNotAffectSignal — it was
    already hardened to a 10 s connect barrier in M1-540; this ticket brings the
    SimpleX-side barrier to parity. No other MultiAdapterProductionIT test changes.
  - >-
    The ~16 messaging-adapter unit-test timing constants (QUEUE_WAIT_MS = 2_000 /
    WAIT = Duration.ofSeconds(2) in Signal*Test / SimpleX*Test). Those are
    lower-risk — plain JUnit with in-process fakes, no @QuarkusTest / Dev Services
    container load, and most are preceded by an awaitClient readiness barrier — and
    are not observed flaking. A 16-file defensive bump is disproportionate here;
    file a separate ticket if a flake is ever observed in that module. This ticket
    fixes only the barrier in the high-load provider IT that shares a host with Dev
    Services containers.
acceptance:
  - >-
    In MultiAdapterProductionIT.signalCrashDoesNotAffectSimpleX, the pre-crash
    SimpleX WebSocket readiness barrier is raised from
    sxFake.awaitClient(Duration.ofSeconds(2)) to a generous timeout (≥ 10 s,
    matching the 10 s Signal-side barrier M1-540 added to the sibling test), so the
    SimpleX connect cannot race the barrier under host load. The post-crash probe
    (awaitFrame) and the assertNotNull liveness assertion are unchanged.
  - "`mvn verify` is green from the repo root — MultiAdapterProductionIT passes and no pre-existing test regresses."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/verification.md §Test layers
decision_refs:
  - D46
---

# M1-541: Harden signalCrashDoesNotAffectSimpleX readiness barrier

## Context

M1-540 fixed a load-induced timing flake in
`MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal` by adding a 10 s Signal
JSON-RPC connect-readiness barrier before its liveness probe. A scan for the same
pattern (a readiness/probe wait for an async event, tight budget, high-load
context) found one close structural twin: the sibling
`signalCrashDoesNotAffectSimpleX` barriers on SimpleX WebSocket readiness before
its crash+probe via `sxFake.awaitClient(Duration.ofSeconds(2))` — a **2 s** connect
barrier in the same `@QuarkusTest` IT that shares a host with Dev Services
containers. That is the identical failure mode M1-540 addressed (a readiness wait
timing out under bursty load), just on the connect barrier rather than the probe;
M1-540 left it at 2 s while hardening only the Signal side to 10 s. This ticket
brings the SimpleX-side barrier to parity so the asymmetry cannot bite. It has not
been observed flaking — this is a proactive hardening of the closest twin to an
already-observed flake.

## Acceptance

See frontmatter. Raise the `sxFake.awaitClient(Duration.ofSeconds(2))` barrier in
`signalCrashDoesNotAffectSimpleX` to `Duration.ofSeconds(10)` (parity with the
M1-540 Signal-side barrier). The post-crash probe and assertion are unchanged.
`mvn verify` is green.

## Out-of-scope

See frontmatter. No src/main change, no assertion change, no edit to the sibling
test's already-hardened barrier or the other MultiAdapterProductionIT cases, and
explicitly NOT the ~16 lower-risk messaging-adapter unit-test timing constants
(plain JUnit, no Dev Services, mostly barriered, not observed flaking — a separate
ticket if ever needed).

## Notes

- **Barrier, not probe.** The flake risk is the *connect* barrier
  (`awaitClient`) racing the SimpleX WS handshake under load, exactly as the
  Signal connect raced in M1-540. The post-crash `awaitFrame(Duration.ofSeconds(2))`
  probe (line ~493) runs after the barrier and measures only the frame flush of an
  already-connected client, so it stays at 2 s — mirroring M1-540, which kept its
  probe budget (`nextOutbound(2000)`) and only widened the barrier.
- **Why 10 s.** Matches the value M1-540 and `SignalReconnectTest` use for connect
  barriers; a barrier just needs to outlast worst-case host load, it is not a tight
  budget.
- Adjacent code: `MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal`
  (`sgFake.awaitConnectionGeneration(2, 10_000)`, added by M1-540) is the reference.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-541-*.md
```
