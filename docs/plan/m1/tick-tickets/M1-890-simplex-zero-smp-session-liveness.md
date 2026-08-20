---
id: M1-890
title: "SimpleX zero-SMP-session liveness + child restart"
status: done
created: 2026-08-20
last_updated: 2026-08-20
flow: tick
reproduction: >-
  SimpleXSmpSessionLivenessTest.sustainedZeroSessionsLatchesAndRestarts
  (child of a 2+ decomposition — analysis
  docs/plan/m1/tick-analysis/transport-liveness-instrumentation.md; run RED
  at `start` per workflow §0: compile-red against the not-yet-existing
  poll/codec/hook APIs). The wrong behavior it states: a
  simplex-chat subprocess that is alive (supervisor RUNNING) with a healthy
  loopback WebSocket but ZERO upstream SMP sessions — connected-but-deaf,
  nothing can reach the bot — fires NO detector and NO recovery: the WS
  terminal-event latch fires only on onClose/onError
  (SimpleXWebSocketClient.java:556-565), the rebuild campaign only on that
  latch (SimpleXAdapter.java:586-717), and the subprocess supervisor watches
  process exit only (SimpleXSubprocess.java — no restart-on-demand hook
  exists; grep 'restartHung' in the simplex package returns nothing). Live
  evidence: .scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md §10 (:787-831) —
  REPRODUCED and root-caused at fresh stack start (host resolver answers
  AAAA-first for *.simplex.im, the rootless container has no IPv6 route,
  simplex-chat does not fall back to IPv4); subprocess respawns, provider
  recreates, and full compose restarts all kept the wedge; inbound was
  silently lost (at-most-once, docs/spec/messaging.md:461-464) until the
  test-only extra_hosts IPv4 pins were applied.
analysis_ref: docs/plan/m1/tick-analysis/transport-liveness-instrumentation.md
blocked_by: []
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXMessageCodec.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSmpSessionLivenessTest.java
  - docs/spec/messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The Signal adapter — M1-889 owns the Signal liveness probe, the silence
    WARN, and the Signal detector sentences of the spec bullet. No
    signal-package file is touched here.
  - >-
    The IPv6/DNS environment root fix — shipping extra_hosts pins into the
    repo compose (explicitly rejected: the pins hardcode third-party relay
    IPs and are a test-checkout-only workaround, plan §10 :828-831), a
    simplex-chat upstream change, or any resolver/compose-network change.
    This ticket DETECTS and routes recovery; it cannot make a no-IPv4-route
    environment connect.
  - >-
    Changes to the M1-674 WS peer-close latch or the rebuild campaign's
    single-flight/pacing logic — the new detector SKIP-reads while the WS
    is latched dead so the two arms never run concurrently (analysis P9).
  - >-
    New Micrometer metrics, gauge labels, or readiness-payload fields —
    the wedge surfaces through the existing connected() fold, the
    subprocess FAILED state + throttled adminNotifier, and WARN logs.
  - >-
    A general simplex-chat command API expansion — exactly ONE new
    codec command/response pair (the session-count query), gated by the
    step-0 verification below.
acceptance:
  - "STEP-0 GATE (analysis P7 — ASSUMPTION A1) — live probe, run before
    any fix code: against the pinned simplex-chat binary, enumerate its
    bot-WS command set and confirm a command that reports the agent's
    upstream SMP session/subscription count; record the command + response
    shape in the commit message. If NO such command exists on the pinned
    binary, STOP and escalate — do not substitute a /proc/net/tcp parse or
    an agent-SQLite read (platform fragility, container-netns assumptions,
    DB-lock contention with the live agent)."
  - "REPRODUCTION, now passing: SimpleXSmpSessionLivenessTest.sustainedZeroSessionsLatchesAndRestarts —
    with the subprocess RUNNING, the WS up, and the session poll answering
    zero for the threshold number of consecutive polls, the adapter treats
    the transport as dead: connected() reports false (no false green,
    messaging.md:449-451), and exactly one supervised child restart is
    driven through the new restart-on-demand hook (FAILURE-MODE: a poll
    loop that re-fires the restart per poll — unlatched — fails this test;
    an always-reporting-connected latch that never flips connected() fails
    it too)."
  - "P8 boot grace: SimpleXSmpSessionLivenessTest.bootGraceZeroSessionsFireNothing —
    zero-session readings INSIDE the grace rule (subprocess freshly RUNNING,
    fewer than the consecutive threshold) fire nothing: the test asserts no
    WARN, no latch, no restart — a slow first connect must not be killed
    into a restart loop."
  - "P9 no double-detect: SimpleXSmpSessionLivenessTest.pollSkipsWhileWebSocketDead
    asserts that with the WS latched dead (peer close), the poll does not
    run and does not latch a second death — the M1-674 rebuild campaign
    owns recovery; and
    SimpleXSmpSessionLivenessTest.pollCommandErrorIsNotZeroSessions asserts
    a failed poll command (sendCommand MessagingException / chatCmdError)
    is a transport fault the existing routes own, never a zero-session
    reading."
  - "P6 restart accounting: SimpleXSmpSessionLivenessTest.livenessRestartCountsTowardCrashCap
    asserts the liveness-driven restart routes through the SAME supervised
    exit path as a crash — SIGKILL the child, let onExit drive the backoff
    respawn, count it against the crash cap; on cap exhaustion the
    supervisor latches FAILED and fires its one throttled adminNotifier
    call (asserted via the existing Consumer<String> seam). No unbounded
    retry loop: the deterministic environment wedge (plan §10 — respawns
    did not clear it) terminates in honest FAILED + readiness DOWN + admin
    notification, not in silent spinning."
  - "P4 (engineering-rules §9) — grep-gate plus pinned-clock tests: no new
    Instant.now() appears in the ticket's main-source diff; the poll
    cadence, grace, and consecutive-threshold windows read an injected
    Clock (production Clock.systemUTC() per the module pattern,
    OutboundRateLimiter.java:36-37); the tests pin a controllable clock
    and assert the threshold boundary exactly."
  - "P5 log hygiene: SimpleXSmpSessionLivenessTest.warnLineCarriesCountsOnly
    asserts the zero-session WARN line against a fixed-vocabulary
    expectation — counts and window values only (no contact ids, no
    content, no exception messages; security.md §Secrets handling D37;
    SimpleXWebSocketClient.java:617-623 is the in-module precedent)."
  - "Spec amendment — verified by probe (git diff docs/spec/messaging.md
    shows the edit confined to §Failure handling's transport-death
    bullet's SimpleX route sentence(s), :436-438, and matching the
    user-approved text; rides-the-diff per engineering-rules §12: exact
    wording approved by the user at implementation time; rule-text only,
    no dates, ticket IDs, or report citations): the route gains the rule
    that the adapter detects a sustained zero-upstream-session state in
    the supervised subprocess and drives recovery through the supervised
    subprocess restart (the same backoff/cap path a crash takes), latched
    operator-visible like any transport death."
  - "mvn verify from repo root is green (SimpleXReconnectTest, the M1-674 latch tests, and AdapterReadinessCheckTest all pass UNMODIFIED — the new detector is additive)."
test_plan:
  adds:
    - >-
      infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSmpSessionLivenessTest.java
      — sustainedZeroSessionsLatchesAndRestarts (reproduction),
      bootGraceZeroSessionsFireNothing, pollSkipsWhileWebSocketDead,
      pollCommandErrorIsNotZeroSessions, livenessRestartCountsTowardCrashCap,
      warnLineCarriesCountsOnly. Plus a codec wire-fixture test for the new
      session-count command/response pair (recorded frame shape, the
      existing decode-test pattern).
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
  - docs/spec/deployment.md §Health and observability
decision_refs:
  - D37
reviews:
  - round: 1
    date: 2026-08-20
    verdict: REWORK
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "8 files changed, 841 insertions(+), 16 deletions(-)"
    findings: "1 low rework item, 0 critical/high"
    verdict_file: .scratch/tick-review-M1-890-r1.txt
  - round: 2
    date: 2026-08-20
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "round-2 fix hunks: 2 files changed, 28 insertions(+), 1 deletion(-) over the round-1 tree (full diff: 8 files, +869/-17)"
    findings: "0 rework items, 0 critical/high; round-1 item SATISFIED"
    verdict_file: .scratch/tick-review-M1-890-r2.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-890: SimpleX zero-SMP-session liveness + child restart

## Context

Live defect (D-8 family, "D-11" in plan §10): at fresh stack start the
supervised simplex-chat subprocess held ZERO SMP TCP sessions — AAAA-first
resolver answers, no IPv6 route in the rootless container, no IPv4 fallback
in simplex-chat — while the supervisor saw process RUNNING and the loopback
WebSocket stayed up. Zero inbound, zero errors, and respawns/recreates/full
restarts all kept the wedge; only test-runtime extra_hosts IPv4 pins
recovered inbound (plan §10 :787-831). The WS-rebuild recovery route
(messaging.md:436-438, M1-674) cannot see this shape: the WS is healthy —
the deafness is UPSTREAM of it, inside the subprocess. Inbound is
at-most-once: the wedge is silent message loss. Shared analysis:
`analysis_ref:`. This ticket is the SimpleX leg; M1-889 is the Signal leg.

## Root cause

Proven live (plan §10): the supervisor's signals are process liveness and WS
terminal events; the subprocess's upstream session state is observable to no
one in-process (grep-verified: the codec's only query command is
`/show_address`, SimpleXMessageCodec.java:203; no session/subscription
introspection exists anywhere in the repo). And there is no restart-on-
demand hook on SimpleXSubprocess (grep: no `restartHung` in the simplex
package) — SignalSubprocess.restartHung (:390-396) is the model: SIGKILL the
child so the exit path drives the supervised backoff restart, counting
against the cap.

## Pitfalls

Numbered consistently with the analysis document.

- P4: injected Clock for cadence/grace/threshold (§9); no `Instant.now()`
  in any gate.
- P5: D37 log hygiene — counts and windows only in WARN lines; class-name-
  only for throwables.
- P6: recovery route is the CHILD RESTART, not a WS rebuild — a rebuilt WS
  against a zero-session child is still deaf. The restart must share the
  crash path's backoff and cap accounting (a wedged child is a failure);
  the deterministic environment wedge terminates in honest FAILED + throttled
  admin notify, never an unbounded retry loop.
- P7: ASSUMPTION A1 — the session-count query is UNVERIFIED against the
  pinned binary. Step 0 is the probe; no such command → STOP and escalate.
  Never substitute /proc parsing or an agent-DB read.
- P8: boot grace — a fresh subprocess legitimately has zero sessions until
  it connects; the detector needs a consecutive-zero threshold gated on
  RUNNING + WS up, or every slow boot restarts into a loop.
- P9: no double-detect — the poll skips while the WS is latched dead or
  absent (M1-674's arm owns recovery then), and a failed poll command is a
  transport fault, not a zero-session reading.
- P10: §12 spec discipline — rule-text only, user-approved wording, confined
  to the bullet's SimpleX route sentence(s) (M1-889 owns the Signal
  sentences; the siblings never touch the same lines).

## Approach

Derived from spec_refs: messaging.md §Failure handling commits the
transport-death stance — latch, supervised recovery, operator-visible outage,
no false green (:429-464) — and names SimpleX's recovery route as
adapter-specific (:436-438). This ticket extends that route with the
upstream-session liveness trigger; the recovery itself (supervised restart
with backoff/cap/FAILED + admin notify) is the path the spec and
SimpleXSubprocess already have for crashes.

- **Files to touch:** `files_scope:` (three simplex-package classes, one new
  test class, one spec bullet).
- **Steps, in order:**
  1. STEP-0 GATE (P7): probe the pinned simplex-chat binary's bot-WS command
     set for a session/subscription-count query; record the outcome in the
     commit message. No command → escalate, no fix code.
  2. Write SimpleXSmpSessionLivenessTest's drives + the codec wire-fixture
     test — run RED on main (workflow §0).
  3. `SimpleXMessageCodec`: the one command + response pair (encode the
     query, decode the count) in the existing corrId-envelope shape.
  4. `SimpleXSubprocess`: the restart-on-demand hook mirroring
     SignalSubprocess.restartHung (:390-396) — no-op when stopping, SIGKILL
     the live child, let onExit drive backoff/cap/FAILED + adminNotifier.
  5. `SimpleXAdapter`: the scheduled poll (own scheduler thread, injectable
     clock; started with the WS, stopped at close) — skip when the WS is
     null/latched dead or the subprocess is not RUNNING (P8/P9); on the
     consecutive-zero threshold: latch the transport dead (so connected()
     and readiness go honest through the existing fold), WARN (P5), and fire
     the restart hook once per latched episode.
  6. Spec amendment (acceptance item 8) — draft rule-text; user approves
     wording before it lands (§12).
  7. `mvn verify` from repo root.
- **Controls to preserve (§10):** the M1-674 latch's drain-ordering,
  counted+logged discarded inbound, and local-close suppression; the rebuild
  campaign's single-flight CAS (the poll's skip-when-dead is what keeps the
  two arms from racing); the subprocess's SIGTERM-grace-then-SIGKILL stop
  semantics (the hook reuses the exit path, never stop()); the throttled
  single adminNotifier call on FAILED; SimpleXLoopbackProbe's startup guard
  (untouched — a restarted child re-runs it through the existing spawn path).
- **Pitfall→mitigation:** P6→step 4's shared exit path + acceptance item 5;
  P7→step 1's gate; P8→step 5's threshold + item 3; P9→step 5's skip + item
  4; P4→item 6; P5→item 7; P10→item 8.

## Definition of done

A sustained zero-SMP-session subprocess is detected without any user traffic:
the transport latches dead (connected()/readiness honest), one supervised
child restart per latched episode runs through the same backoff/cap path as
a crash, cap exhaustion lands in FAILED + one throttled admin notification,
boot grace and WS-dead windows fire nothing, the WARN is D37-clean, the spec
names the new trigger in user-approved rule-text, and the full suite is
green with no pre-existing test modified.

## Verification

- P6 → SimpleXSmpSessionLivenessTest.livenessRestartCountsTowardCrashCap
  (acceptance 5) — drives the restart to cap exhaustion; asserts FAILED +
  exactly one throttled adminNotifier call and no further respawns.
- P7 → acceptance item 1 (step-0 gate, recorded in the commit) + the codec
  wire-fixture test — a recorded response frame decodes to the count; a
  mutation that drops the count field fails the fixture.
- P8 → bootGraceZeroSessionsFireNothing (acceptance 3) — zero readings
  inside the grace rule assert no WARN/latch/restart; the (threshold+1)-th
  consecutive zero at a pinned clock fires exactly once.
- P9 → pollSkipsWhileWebSocketDead + pollCommandErrorIsNotZeroSessions
  (acceptance 4) — failure-mode drives: dead WS → no poll, no second latch;
  chatCmdError → no zero-session interpretation.
- P4 → acceptance item 6 — pinned-clock boundary assertions + the
  Instant.now() grep-gate over the diff.
- P5 → warnLineCarriesCountsOnly (acceptance 7) — fixed-vocabulary assertion.
- P10 → acceptance item 8 — git-diff scope probe on the spec file.
- Reproduction → acceptance item 2.

## Out-of-scope

Named in `out_of_scope:` — Signal (M1-889); the IPv6/DNS root fix and the
extra_hosts pins (detection + recovery routing only; this ticket cannot make
a no-IPv4-route environment connect, and must not pretend otherwise);
M1-674's latch/campaign internals; new metrics or readiness fields; any
codec expansion beyond the one gated command pair. No pre-existing test is
modified — if one fails for a reason not named here, escalate rather than
edit it (§8).

## Census

Class: connected-but-deaf detection coverage across transport adapters
(re-runnable: `grep -rn 'latchTransportDeath\|recordTimeout\|restartHung'
infochat-messaging-adapter/src/main/java/`).

| Site | Disposition |
|---|---|
| SimpleXWebSocketClient WS terminal-event latch | already exists (M1-674) — untouched |
| SimpleX zero-SMP-session deafness | FIXED (this ticket) |
| SimpleXSubprocess crash-exit supervision | already exists — the new hook reuses its exit path |
| Signal connected-but-deaf (live reader, silent channel) | defer: M1-889 (the sibling ticket) |
| SignalJsonRpcClient reader-exit latch + timeout escalation | already exist (M1-681) — untouched |
| InMemoryAdapter | out-of-scope: transportless SPI double — no wire to go deaf; `connected()` default true is its documented contract (MessagingAdapter.java:391-394) |

## Round 1 rework

REWORK ITEMS (verbatim from `.scratch/tick-review-M1-890-r1.txt`):

1. Finding 1: add the latched-window TRANSIENT send assertion to
   SimpleXSmpSessionLivenessTest.sustainedZeroSessionsLatchesAndRestarts
   (right after the latch assertion at
   SimpleXSmpSessionLivenessTest.java:92-93), asserting
   adapter.send(...) throws MessagingException with
   FailureCategory.TRANSIENT while the supervisor is still alive;
   evaluated via the named test passing under `mvn verify` and failing
   when SimpleXAdapter.java:1319 is flipped to PERMANENT.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-890-simplex-zero-smp-session-liveness.md
```
