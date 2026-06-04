---
id: M1-166
title: "Fix SimpleXSubprocess FAILED-before-notify race (flaky test)"
status: done
created: 2026-06-04
last_updated: 2026-06-04
blocked_by: []
files_budget: 2
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    SimpleXSubprocessTest.java — the existing failedStateAfterCapExhaustion
    test already encodes the invariant and is the acceptance proof; the
    recommended production reorder makes it deterministic WITHOUT editing it.
    Do not weaken/delete its assertions and do not add sleeps to mask the race.
  - >-
    The test-only alternative (awaitNotificationCount helper in the test):
    rejected — it leaves the "FAILED means notified" invariant violated.
  - Any other method/behavior in SimpleXSubprocess (drain, backoff math, stop(), restart counting)
  - infochat-provider/**, infochat-collector/**, and all non-messaging-adapter modules
acceptance:
  - >-
    SimpleXSubprocessTest.failedStateAfterCapExhaustion passes deterministically:
    handleCrashCap() increments adminNotifications and delivers
    adminNotifier.accept(...) (keeping the existing try/catch) BEFORE
    state.set(State.FAILED), so any thread that observes State.FAILED sees the
    admin notification already delivered — restoring the State.FAILED javadoc
    contract ("supervisor stopped, admin notified").
  - >-
    The reorder is the only change to SimpleXSubprocess; the existing
    SimpleXSubprocessTest.crashRestartWithBackoff and
    SimpleXSubprocessTest.startsAndStopsProcess still pass (they read
    state/restartCount, not the notifications list).
  - mvn -B clean verify from the repo root exits 0
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []

reviews:
  - round: 1
    date: 2026-06-04
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 3
      added: 19
      removed: 8
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-06-04
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-166: Fix SimpleXSubprocess FAILED-before-notify race (flaky test)

## Context

`SimpleXSubprocessTest.failedStateAfterCapExhaustion` is intermittently red
in the `infochat-messaging-adapter` surefire run:

```
[ERROR] SimpleXSubprocessTest.failedStateAfterCapExhaustion:99 expected: <1> but was: <0>
```

The flake pre-exists on `main` and is **not** caused by the NullAway work in
M1-164a. Root cause is a publication-ordering bug in
`SimpleXSubprocess.handleCrashCap(int)` (L243–263). The supervisor thread runs:

```
247  state.set(State.FAILED);          // observable to the test thread NOW
252  adminNotifications.incrementAndGet();
254  adminNotifier.accept(...);        // appends to the test's notifications list
```

`awaitState(FAILED)` returns the instant it sees `state == FAILED` (set at
L247), but the counter bump (L252) and the notify delivery (L254) happen
*after* the state flip. A test-thread read landing in the L247→L254 window sees
`FAILED` with an empty notifications list — so L99 (`notifications.size() == 1`)
fails with size 0 while L97 (`adminNotifications() == 1`) usually wins the race.

This also contradicts the `State.FAILED` enum javadoc (L62–63: "Crash cap
exhausted; supervisor stopped, admin notified") — reaching `FAILED` is supposed
to *mean* the admin has already been notified. The spec contract is
`docs/spec/messaging.md` §Failure handling ("the throttled-admin-notification
path fires" on terminal cap exhaustion).

## Acceptance

See frontmatter. In short: in `handleCrashCap()`, do the counter bump and the
guarded `adminNotifier.accept(...)` FIRST, then `state.set(State.FAILED)` last,
so that any observer of `State.FAILED` is guaranteed to see the notify already
delivered. `AtomicReference.set`/`get` are volatile write/read, so writes
sequenced before the `FAILED` flip on the supervisor thread are visible to any
test thread that observes `FAILED`, making both assertions deterministic. The
notify message is a fixed string independent of `state`, so the reorder is
behavior-preserving for the production notifier path.

## Out-of-scope

See frontmatter. The fix is the single reorder inside `handleCrashCap` (keeping
the existing try/catch). The existing test is the acceptance proof and must not
be edited — editing a pre-existing test to mask a race is a test-integrity
violation (`docs/process/engineering-rules-verbatim.md` §8). The lower-blast-
radius test-only alternative (poll the notifications list after `awaitState`)
was considered and rejected because it leaves the FAILED-means-notified
invariant violated in production.

## Notes

- SUT: `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java` §handleCrashCap (L243–263).
- Reproduction (do NOT commit it): temporarily wrap the test body in
  `@RepeatedTest(2000)` and run once to surface sporadic size-0 failures; or a
  shell loop of single-method runs. The window is narrow — single runs usually pass.
- Design contract: `docs/design/06-messaging.md` (subprocess crash → respawn,
  admin notified after Nx) and `docs/spec/messaging.md` §Failure handling.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-166-simplex-failed-notify-ordering.md
```
