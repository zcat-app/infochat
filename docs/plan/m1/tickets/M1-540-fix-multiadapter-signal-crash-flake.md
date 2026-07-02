---
id: M1-540
title: Fix MultiAdapterProductionIT signal-crash timing flake
status: done
created: 2026-07-01
last_updated: 2026-07-02
blocked_by: []
clarity_check:
  date: 2026-07-01
  verdict: PASS
  warnings: []
  blockers: []
reviews:
  - round: 1
    date: 2026-07-01
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 20
      removed: 8
files_budget: 2
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Any src/main change. This is a test-timing determinism fix only —
    SignalAdapter, SignalJsonRpcClient, the real subprocess supervision, and every
    production class stay untouched. The adapters already behave correctly (the
    liveness assertion passes); only the test's pre-probe synchronization is at
    fault.
  - >-
    Any assertion change. The fix ADDS a readiness precondition (a connect
    barrier) before the existing liveness probe; it does not weaken, remove, or
    relax the assertion that Signal still processes outbound JSON-RPC after a
    SimpleX crash. Widening the barrier is not weakening the test.
  - >-
    The symmetric signalCrashDoesNotAffectSimpleX test and the other
    MultiAdapterProductionIT cases — they already barrier on transport readiness
    (sxFake.awaitClient) and are not flaky; leave them unchanged.
  - >-
    SignalReconnectTest and any other in-package FakeSignalCli caller. Widening
    awaitConnectionGeneration's visibility to public must not change its behaviour
    or these callers.
acceptance:
  - >-
    MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal awaits the Signal
    JSON-RPC connection being established (via FakeSignalCli.awaitConnectionGeneration)
    AFTER sg.start() and BEFORE the setTyping liveness probe, so the 2000 ms probe
    budget no longer includes the connect race — mirroring the sibling
    signalCrashDoesNotAffectSimpleX barrier (sxFake.awaitClient). The liveness
    assertion (nextOutbound + assertNotNull) is unchanged.
  - >-
    FakeSignalCli.awaitConnectionGeneration(int, long) is public so the
    provider-module IT can call it; behaviour is unchanged and the existing
    same-package caller (SignalReconnectTest) still compiles and passes.
  - "`mvn verify` is green from the repo root — MultiAdapterProductionIT passes and no pre-existing test regresses."
test_plan:
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/MultiAdapterProductionIT.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/verification.md §Test layers
  - docs/spec/messaging.md §Required SPI surface
decision_refs:
  - D46
---

# M1-540: Fix MultiAdapterProductionIT signal-crash timing flake

## Context

`MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal` (the cross-adapter
blast-radius test for D46's multi-adapter deployment shape) is intermittently
flaky under load: it failed a full-suite `mvn verify` with
`FakeSignalCli received no outbound JSON-RPC within 2000 ms` at
`MultiAdapterProductionIT.java:415`, yet passes in isolation on the identical
tree. The failure surfaced during the commit-time safety re-run of M1-539 (an
unrelated inmemory-only test-scope ticket) and blocked it.

Root cause: the test crashes SimpleX, then probes Signal liveness by dispatching
`setTyping` on a background virtual thread and waiting a hard 2000 ms for the
outbound JSON-RPC frame — but it never first awaits the Signal JSON-RPC
connection to be established. The 2000 ms budget therefore covers thread
scheduling + the connect race + the write; on a saturated host (e.g. a pgvector
Dev Services container starting concurrently) the connect loses the race and the
poll expires. The sibling `signalCrashDoesNotAffectSimpleX` does NOT flake
because it barriers on transport readiness first (`sxFake.awaitClient(...)`).
`FakeSignalCli` already tracks connection generation and exposes
`awaitConnectionGeneration(int, long)` (used by `SignalReconnectTest`), but that
method is package-private and unreachable from the provider-module IT.

## Acceptance

See frontmatter. Add a Signal connect-readiness barrier
(`sgFake.awaitConnectionGeneration(2, <generous timeout>)`) after `sg.start()`
and before the `setTyping` liveness probe in `simpleXCrashDoesNotAffectSignal`,
mirroring the sibling test's `awaitClient` barrier, and widen
`FakeSignalCli.awaitConnectionGeneration` to `public` so the provider IT can call
it. The existing liveness assertion is unchanged; `mvn verify` is green.

## Out-of-scope

See frontmatter. No src/main change, no assertion change, no edit to the sibling
/ other MultiAdapterProductionIT tests, no behavioural change to
`awaitConnectionGeneration` or its existing `SignalReconnectTest` caller.

## Notes

- **Generation count = 2.** `SignalAdapter.start()` does a TCP probe
  (`awaitEndpoint`) then the real JSON-RPC connect; `FakeSignalCli`'s accept loop
  increments the generation on each accept, so the established-connection state is
  generation ≥ 2. `SignalReconnectTest` awaits `generationBeforeKill + 2` for the
  same reason. Use a generous barrier timeout (the reconnect tests use 10 s) —
  the barrier just removes the connect from the probe's 2000 ms window; it does
  not need to be tight.
- **Why a barrier, not a bigger probe timeout.** Bumping the 2000 ms magic number
  would only reduce the flake probability; the barrier removes the race entirely
  and matches the pattern the non-flaky sibling test already uses.
- Adjacent code: the sibling `signalCrashDoesNotAffectSimpleX`
  (`MultiAdapterProductionIT.java` ~line 455) is the reference for the barrier
  shape. `FakeSignalCli.awaitConnectionGeneration` is at
  `FakeSignalCli.java:137`.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-540-*.md
```
