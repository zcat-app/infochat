---
id: M1-683
title: "Fail the build when a Signal client pairs a real restart hook with no generation supplier"
status: done
created: 2026-07-23
last_updated: 2026-07-23
blocked_by: [M1-681]
files_budget: 5
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalEditFallbackTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundQueueBoundTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Removing the `() -> 0L` daemon-generation default. It is load-bearing
    for the transport-death tests, which pass a real (counting) restart
    hook precisely so they can assert the restart fires, and rely on the
    0-default making the generation gate a no-op (0 == 0). Deleting it
    would force every no-generation client — including the many no-hook
    test constructors — to thread a supplier. The gap is that a REAL hook
    can be paired with the 0-default silently, not that the default is
    wrong.
  - >-
    The daemon-generation guard itself, SignalSubprocess.generation(), and
    the SignalAdapter wiring. M1-681 delivered them and the sole production
    call site wires SignalSubprocess::generation correctly; this ticket
    adds the guard that keeps a FUTURE wiring from silently dropping it.
  - >-
    MessagingAdapter.connected()'s interface-default footgun. That is the
    same class of hazard but a different mechanism (an SPI method default,
    not a constructor param default) and is already M1-682.
acceptance:
  - >-
    A production wiring that pairs a non-no-op restart hook with the
    0-default daemon-generation supplier is rejected at build time. The
    chosen mechanism is the author's call (a merged constructor so the two
    always travel together; an assertion that a real hook implies a real
    supplier; or a contract test over the constructor shapes) — but the
    effect MUST be that `hungRestartHook` real + `daemonGeneration` absent
    cannot compile or cannot pass `mvn verify`.
  - >-
    The transport-death tests that intentionally pass a real hook with no
    supplier (they assert the restart fires and want the gate as a no-op)
    keep a supported, explicit way to do so — e.g. an explicit
    always-matching supplier — so the guard distinguishes "deliberately
    ungated in a test" from "silently ungated in production".
  - >-
    The sole current production call site (SignalAdapter.java, wiring
    SignalSubprocess::generation) is unaffected and mvn verify stays green
    with no production behavior change.
  - mvn verify is green from the repo root
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalEditFallbackTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalInboundQueueBoundTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 19
      added: 801
      removed: 54
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-23
  verdict: PASS
  warnings: []
  blockers: []
escalation_reason:
---

# M1-683: Fail the build when a Signal client pairs a real restart hook with no generation supplier

## Context

M1-681's round-3 `redteam-multi` audit
(`docs/plan/m1/redteam-multi/M1-681-2026-07-23-r3/`) returned CLEAN from
all three auditors; kimi reported this as an out-of-model SPI-hardening
candidate (verdict-kimi.txt, out-of-model item 3). It was re-verified
against the working tree on 2026-07-23 and survived a falsification pass.

M1-681 fixed RT-M1-681-r2-1 by gating the shared-subprocess restart on a
daemon generation: `SignalJsonRpcClient` reads a `LongSupplier
daemonGeneration` at connect, stamps it on the connection, and fires
`hungRestartHook` only when the stamp still equals the live generation.
The supplier defaults to `() -> 0L` in the constructors that do not take
one, which makes the gate a no-op (every connection stamps 0, so `0 == 0`
always passes and the restart fires unconditionally).

That default is **correct and load-bearing** for the tests: the
transport-death tests pass a real counting hook
(`restartCalls::incrementAndGet`) and rely on the 0-default so the restart
fires and they can assert it. Removing it is the wrong fix.

The hazard is narrower. The 5-, 6-, and 7-arg constructors all accept a
real `hungRestartHook` while defaulting `daemonGeneration` to `() -> 0L`.
The 7-arg form in particular looks complete — hook + capacity + rate
limiter — so a future production wiring that used it would pair a real
restart hook with an always-matching generation gate and **silently
reopen RT-M1-681-r2-1** (a stale reader across a supervised respawn would
SIGKILL the healthy successor). Verified today: the sole production call
site is `SignalAdapter.java:279`, and it uses the 8-arg form wiring
`SignalSubprocess::generation`, so the gate is live everywhere it matters.
Nothing structurally prevents a future miswiring — there is no
compile-time signal (the default makes the supplier optional) and no test
signal.

This is the exact class M1-682 already establishes as "an internal
default silently disables a safety property → add a build guard." M1-682
covers the `MessagingAdapter.connected()` interface default via a
reflection contract test; this ticket covers the constructor-parameter
default. Same disposition, different mechanism, so a sibling ticket rather
than a scope extension.

## Acceptance

See the frontmatter. In short: a real restart hook paired with the
0-default generation supplier must not survive `mvn verify`, while a test
that deliberately wants an ungated restart keeps an explicit way to say
so, and the current production wiring is unchanged and green.

## Out-of-scope

Removing the `() -> 0L` default, the generation guard / counter / wiring
M1-681 delivered, and the `connected()` default footgun (M1-682). See the
frontmatter for the full list and reasoning.

## Notes

- **Scope widened at start (developer self-check).** The original
  `files_scope` (2 files) assumed the hazard was confined to
  `SignalJsonRpcClient.java` and its direct unit test. A pre-implementation
  grep of every constructor call site in the package (package-private
  class, so this is exhaustive) found the identical pattern already live in
  `SignalReconnectTest.java` (real `sp::restartHung` hook, no generation
  supplier, 3 sites) — a real `SignalSubprocess`, not a mock. Closing the
  hole per acceptance item 1 (including the 7-arg "looks complete" case
  this ticket calls out as worst) requires collapsing all three risky
  overloads, which also forces a 1-line call-site update in
  `SignalEditFallbackTest.java` and `SignalInboundQueueBoundTest.java`
  (currently-safe no-op-hook callers of the overloads being reshaped).
  `files_budget`/`files_scope` widened 2→5 to cover all five files.
- The two obvious mechanisms, either acceptable: (a) collapse the
  hook-bearing constructors so a real hook cannot be supplied without a
  supplier — the two travel as one — which the tests opt out of with an
  explicit always-matching supplier; (b) a contract test that enumerates
  the constructor shapes and asserts none pairs a non-no-op hook with the
  0-default. (a) is stronger (compile-time) but more churn across the
  test call sites; (b) mirrors M1-682's runtime-reflection style. The
  author picks; the acceptance pins only the effect.
- Do NOT route this through readiness or runtime behavior — like M1-682,
  the fix belongs at build time, not as a runtime fail-closed that would
  change behavior for a legitimately-ungated test client.
- Full evidence:
  `docs/plan/m1/redteam-multi/M1-681-2026-07-23-r3/verdict-kimi.txt`,
  out-of-model item 3.
