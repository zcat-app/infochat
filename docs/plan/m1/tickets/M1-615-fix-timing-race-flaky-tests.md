---
id: M1-615
title: "Fix two full-suite timing-race flaky tests"
status: pending
created: 2026-07-12
last_updated: 2026-07-12
blocked_by: []
files_budget: 4
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/StopToolQueryCancellationIT.java
  - >-
    infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
provenance: >-
  Two pre-existing test-level races documented in the
  full-suite-timing-flakes project memory (first confirmed 2026-07-07 during
  M1-583/M1-584; the memory's own guidance was "if they recur often enough to
  matter, file a ticket to fix the two races"). Surfaced again 2026-07-12 on
  the M1-611 pre-review verify: StopToolQueryCancellationIT failed under a
  concurrent full-suite build with the documented statement_timeout-won-the-
  race signature. Both flakes pass on an isolation re-run, so the standing
  workaround has been "re-run the class" — which taxes every verify and trains
  reviewers to wave away red as "just the flake", masking real regressions.
  This ticket removes the races by construction. Neither race is in a diff
  M1-611 (or any recent ticket) touched; they are latent test-timing bugs.
out_of_scope:
  - >-
    Production behavior. Do NOT change CancellationService, InFlightTracker,
    the /stop wiring, the profile statement_timeout GUC application, or any
    production SimpleX adapter / reconnect / group-handler code. Both races are
    in the TEST and its fixture; the fix stays in test code.
  - >-
    Weakening either discriminating assertion. StopToolQueryCancellationIT
    must still assert the abort came from pg_cancel_backend ("canceling
    statement due to user request") and NOT accept the statement_timeout
    signal; SimpleXAdapterIdentityDerivationTest must still assert member-id
    routing. Loosening/deleting the assertion to make the race "pass" is a
    test-integrity violation (engineering-rules-verbatim.md §8), not a fix.
  - >-
    Lowering or globally changing the shared %test statement_timeout
    (application.properties:503 %test.infochat.stop.statement-timeout=5s) in a
    way that alters other tests' behavior. If the cancel IT needs a wider
    window, widen it scoped to that IT's own slow-query connection, not the
    shared profile value.
  - >-
    The other pre-existing flakes and any unrelated test. This ticket is
    exactly the two named tests below.
acceptance:
  - >-
    NAMED TEST. StopToolQueryCancellationIT.stopAbortsInFlightToolQuery no
    longer depends on pg_cancel_backend beating the 5s statement_timeout
    backstop under load: the fix removes the wall-clock race by construction
    (e.g. the IT's slow-query connection runs under a statement_timeout
    comfortably larger than the worst-case cancel-arrival latency, so
    pg_cancel deterministically wins), while the assertion that the abort
    signal is "canceling statement due to user request" (not statement
    timeout) is UNCHANGED. The test still fails if pg_cancel_backend does not
    reach the backend.
  - >-
    NAMED TEST. SimpleXAdapterIdentityDerivationTest.routesGroupMentionByMemberIdAfterStart
    awaits the fake client's connection before sending frames (mirroring the
    sibling routesGroupMentionByMemberIdAfterRestart, which already calls
    fake.awaitClient(...) before its sends), so it never throws
    "IllegalStateException: client has not connected yet". The member-id
    routing assertion is unchanged.
  - >-
    mvn verify is green from the repo root, and neither test's diff loosens or
    removes its discriminating assertion (reviewer checks the diff, not just a
    green run).
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/StopToolQueryCancellationIT.java
    - >-
      infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapterIdentityDerivationTest.java
  preserves:
    - all other tests currently green on main
spec_refs:
  - docs/spec/commands.md §Conversation control
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-615: Fix two full-suite timing-race flaky tests

## Context

Two provider/messaging tests fail intermittently on a full repo-root
`mvn verify` but pass on an isolation re-run — genuine test-level races that
surface under full-suite load/parallelism. They have been carried as a
"re-run the class" note in the `full-suite-timing-flakes` memory rather than
fixed, which is technical debt: a non-deterministic test does not reliably
prove what it claims, taxes every run, and conditions reviewers to dismiss red
as noise (the failure mode that lets a real regression through). This ticket
fixes both races so a red result means a real red.

The cancel IT (`StopToolQueryCancellationIT`) verifies the
`docs/spec/commands.md` §Conversation control contract that an in-flight chat
tool query is cancellable via `pg_cancel_backend(pid)`; the fix must keep that
proof intact.

## The two races

1. **`StopToolQueryCancellationIT.stopAbortsInFlightToolQuery`** runs
   `SELECT pg_sleep(30)` on an armed connection under the `%test`
   `statement_timeout` of 5s (application.properties:503), then issues `/stop`
   (`pg_cancel_backend`) and asserts the abort message contains "user request"
   (line 112). Under full-suite load the cancel is scheduled late enough that
   the **5s statement_timeout backstop wins the race** — the abort message is
   "canceling statement due to statement timeout" and the assertion fails. The
   query IS aborted either way (no correctness bug), but the test's whole point
   is to prove the `pg_cancel_backend` path, so accepting the timeout signal
   would be weakening it. Fix direction: give this IT's slow-query connection a
   `statement_timeout` window large enough that `pg_cancel_backend` reliably
   wins even under load, without touching the shared `%test` value or the
   assertion.

2. **`SimpleXAdapterIdentityDerivationTest.routesGroupMentionByMemberIdAfterStart`**
   calls `fake.sendFrame(...)` (lines 70–71) before the fake SimpleX process's
   client socket has connected, intermittently throwing
   `IllegalStateException: client has not connected yet`. The sibling method
   `routesGroupMentionByMemberIdAfterRestart` already guards its sends with
   `fake.awaitClient(WAIT)` (line ~109). Fix direction: add the same
   connect-await before the sends in the flaky method.

## Acceptance

- `StopToolQueryCancellationIT.stopAbortsInFlightToolQuery` passes
  deterministically: the timing dependence on beating the 5s
  `statement_timeout` is removed by construction, and the "user request"
  discriminator assertion is unchanged (the test still fails if
  `pg_cancel_backend` does not reach the backend).
- `SimpleXAdapterIdentityDerivationTest.routesGroupMentionByMemberIdAfterStart`
  awaits the fake client connection before sending frames and no longer throws
  "client has not connected yet"; the member-id routing assertion is unchanged.
- `mvn verify` is green from the repo root; neither diff loosens or removes its
  discriminating assertion.

## Out-of-scope

Production code is untouched — these are test/fixture timing bugs, not
behavioral defects, so `CancellationService`, `InFlightTracker`, `/stop`
wiring, the `statement_timeout` GUC application, and all production SimpleX
adapter/reconnect/handler code stay as-is. The discriminating assertions in
both tests stay intact — the fix makes the *setup* deterministic, it does not
relax what is asserted (relaxing an assertion to dodge a race is a
test-integrity violation, not a fix). The shared `%test` statement_timeout
value is not globally lowered; any widening for the cancel IT is scoped to that
IT's own connection so other tests are unaffected.

## Notes

- Memory: `full-suite-timing-flakes` (both races, isolation-green, fix
  directions). The memory also names a third-party concern — a *green
  full-suite log is still required to proceed on other tickets* — which this
  ticket exists to make trustworthy.
- Adjacent code to mirror for race 2: the sibling method
  `routesGroupMentionByMemberIdAfterRestart` in the same class already does the
  `fake.awaitClient(...)`-before-send handshake.
- For race 1, prefer widening the IT's own window over any change that could
  perturb the shared `%test` profile; the assertion is the value, the wait is
  incidental.
</content>
