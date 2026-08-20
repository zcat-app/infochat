---
id: M1-889
title: "Signal connected-but-deaf liveness probe + silence WARN"
status: done
created: 2026-08-20
last_updated: 2026-08-20
flow: tick
reproduction: >-
  SignalLivenessProbeTest.probeTimeoutEscalatesWithoutAnyUserTraffic —
  written and run RED at start (2026-08-20, compile-failed against the
  pre-probe API: attachLivenessProbe/LIVENESS_PROBE_INTERVAL/
  SILENCE_WARN_WINDOW did not exist; the wrong behavior it names: a
  connected-but-deaf JSON-RPC channel — reader thread alive and parked in
  read(), socket ESTABLISHED, daemon alive and serving OTHER connections,
  zero inbound and zero outbound user traffic — fires NO detector, so
  nothing ever escalates to the supervised restart. Verified gap, code-first:
  the reader-death latch fires only on reader exit (SignalJsonRpcClient.java
  :835-845 `finally` → latchTransportDeath), and recordTimeout() is reachable
  only from call()'s response-timeout arm (:724) — a deaf-inbound deployment
  generates no outbound traffic, so the silence is self-sustaining (the
  class's own javadoc documents the mechanism, :898-902). Live evidence:
  .scratch/LIVE-E2E-DEFECT-REPORT-2026-08.md D-8 (:227-245) and
  .scratch/LIVE-E2E-REGRESSION-PLAN-2026-08.md §9 preamble (:510-518) —
  daemon receiving + broadcasting receive notifications to fresh probe
  connections, adapter ESTABLISHED, zero dispatches since 20:16 UTC, only a
  provider recreate recovered. Inbound is at-most-once
  (docs/spec/messaging.md:461-464): the wedge is silent message loss.
analysis_ref: docs/plan/m1/tick-analysis/transport-liveness-instrumentation.md
blocked_by: []
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalConnection.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalLivenessProbeTest.java
  - docs/spec/messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The SimpleX adapter — M1-890 owns the zero-SMP-session detector, the
    SimpleXSubprocess restart hook, and the SimpleX sentence of the spec
    amendment. No simplex-package file is touched here.
  - >-
    A keepalive / ping layer to PREVENT idle closes — prevention is a
    separate mechanism both M1-674 and M1-681 deferred; this ticket is
    detection + recovery routing only.
  - >-
    Any change to the two existing detectors: the reader-exit
    latchTransportDeath arms and the consecutive-timeout threshold/CAS
    semantics are preserved as-is (the probe FEEDS recordTimeout; it does
    not re-tune HUNG_TIMEOUT_THRESHOLD or the deference direction).
  - >-
    New Micrometer metrics, gauge labels, or readiness-payload fields —
    the outage surfaces through the existing connected() fold and WARN
    logs (M1-681's out_of_scope stance kept; AdapterReadinessCheck and
    its tests are untouched).
  - >-
    The daemon-side root cause of D-8's fan-out wedge (signal-cli
    internals; the pre-recreate log tail was lost) and any signal-cli
    upgrade. Detection here is mechanism-agnostic by design.
  - >-
    The IPv6/DNS environment issue and the test-checkout extra_hosts
    pins — SimpleX-surface concerns, out of this ticket entirely.
acceptance:
  - "REPRODUCTION, now passing: SignalLivenessProbeTest.probeTimeoutEscalatesWithoutAnyUserTraffic —
    with a connected channel whose fake daemon never answers and never
    notifies and ZERO user-driven call() traffic, the scheduled liveness
    probe times out and the failure routes through recordTimeout(), so
    after HUNG_TIMEOUT_THRESHOLD probe timeouts exactly one
    hungRestartHook fire escalates to the supervised restart (FAILURE-MODE:
    a probe implementation that bypasses recordTimeout — e.g. firing the
    hook on the first timeout — fails this test; that bypass would restart
    on a single transient blip, the over-trigger the threshold exists to
    suppress, SignalJsonRpcClient.java:135-141)."
  - "Probe success resets the streak (P2, existing semantics
    SignalJsonRpcClient.java:231-235), asserted by
    SignalLivenessProbeTest.probeAnswerProvesDaemonAliveResetsTimeoutStreak —
    a fake daemon that answers the probe after earlier timeouts leaves
    consecutiveTimeouts at zero and fires no restart (a wedged-then-recovered
    daemon must not accumulate stale counts into a spurious SIGKILL)."
  - "P1 FAILURE-MODE: SignalLivenessProbeTest.silenceAloneNeverRestarts —
    a connected, answering-but-silent channel held past the silence window
    (fixed injected Clock) logs the connected-but-silent WARN and fires NO
    restart; connected() stays true. Zero inbound is the normal state of an
    idle deployment — silence must never be a restart trigger, or a quiet
    night kill-loops a healthy bot toward terminal FAILED
    (RT-M1-681-r2-1's restart-budget lesson)."
  - "P5 log hygiene: SignalLivenessProbeTest.warnLineCarriesCountsOnly
    asserts the connected-but-silent WARN line against a fixed-vocabulary
    expectation — counts and window duration only (no sender ids, no
    content, no exception messages; security.md §Secrets handling D37;
    analysis P5)."
  - "P2/RT-M1-681-r2-1 regression drive: SignalLivenessProbeTest.probeRestartIsGenerationGatedAndSingleFire —
    a probe timeout with the daemon generation advanced past the
    connection's stamp fires NO restart, and a probe timeout racing a
    reader death fires AT MOST one (the restartRequested CAS holds for the
    probe path exactly as for the two existing detectors)."
  - "P3: the probe is a paced wire frame on its own scheduler —
    SignalLivenessProbeTest.probeRunsOffReaderAndDispatchThreadsAndDrawsPacerToken
    asserts the probe's call executes on the injected scheduler thread
    (never the reader or signal-inbound-dispatch thread — the dispatch hop
    exists to break exactly this deadlock geometry, class javadoc :58-69)
    and that the outbound pacer's token count advanced by the probe frame
    (the §6.3.6 one-token-per-frame invariant, :220-225)."
  - "P4 (engineering-rules §9) — grep-gate plus fixed-clock tests: no new
    Instant.now() appears in the ticket's main-source diff (every
    time-based gate it adds — probe cadence, silence window — reads an
    injected Clock; production wires Clock.systemUTC() per the module
    pattern, OutboundRateLimiter.java:36-37), and the tests pin a
    fixed/controllable clock and assert boundary behavior at the exact
    window edge."
  - "Spec amendment — verified by probe (git diff docs/spec/messaging.md
    shows the edit confined to §Failure handling's transport-death
    bullet's Signal sentences, :439-447, and matching the user-approved
    text; rides-the-diff per engineering-rules §12: exact wording approved
    by the user at implementation time; rule-text only, no dates, ticket
    IDs, or report citations): the bullet extends Signal's detector
    enumeration from two detectors to three, adding the active liveness
    probe (a daemon that answers nothing on the live channel escalates
    through the same consecutive-failure restart, with no dependence on
    user traffic) and stating that a connected-but-silent inbound window is
    WARN-surfaced without itself triggering recovery."
  - "mvn verify from repo root is green (the M1-681 reader-exit test set, SignalReconnectTest, and AdapterReadinessCheckTest all pass UNMODIFIED — the new detector is additive)."
test_plan:
  adds:
    - >-
      infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalLivenessProbeTest.java
      — probeTimeoutEscalatesWithoutAnyUserTraffic (reproduction),
      probeAnswerProvesDaemonAliveResetsTimeoutStreak,
      silenceAloneNeverRestarts, warnLineCarriesCountsOnly,
      probeRestartIsGenerationGatedAndSingleFire,
      probeRunsOffReaderAndDispatchThreadsAndDrawsPacerToken.
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
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY PASS, MAINTAINABILITY WARN, SCOPE PASS"
    diff_stats: "9 files changed, 653 insertions(+), 37 deletions(-)"
    verdict_file: .scratch/tick-review-M1-889-r1.txt
    note: >-
      All checks PASS; MAINTAINABILITY WARN is informational only — 9
      comment runs over the 3-line cap, all in the two NEW test files
      (javadoc contracts / pitfall-trap explanations; content §11 permits),
      left for the driver to trim or keep. SCOPE PASS dispositions the two
      unplanned files: SignalMessageCodec.encodeVersion (the probe frame
      every probe acceptance item requires) and ControllableProbeScheduler
      (the planned test class's scheduler double). Post-APPROVE the driver
      trimmed the 9 over-cap comment runs (comment lines only, diff vs the
      r1 tree verified comment-only); probes: tick-comment-cap 0 runs over
      the trimmed diff, `mvnw -B -pl infochat-messaging-adapter -am
      test-compile` exit 0; the r1 green log remains the log of record.
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
---

# M1-889: Signal connected-but-deaf liveness probe + silence WARN

## Context

Live defect D-8: the Signal adapter sat connected-but-deaf — signal-cli
receiving and broadcasting `receive` notifications to fresh connections,
adapter TCP ESTABLISHED, reader/dispatch threads alive, health UP, zero
inbound dispatches, zero error logs — and NO supervisor detector fired; only
a provider recreate recovered (defect report D-8; plan §9 preamble). The
spec's transport-death bullet gives Signal two detectors (messaging.md
:439-447); this wedge is a third shape neither sees. Inbound is
at-most-once, so the wedge is silent message loss. Shared analysis:
`analysis_ref:`. This ticket is the Signal leg; M1-890 is the SimpleX leg.

## Root cause

Detection gap, verified against code: the reader-exit latch
(SignalJsonRpcClient.java:835-845) needs the reader to EXIT — in the wedge
it is alive, parked in `read()`. The consecutive-timeout escalation needs
OUTBOUND traffic — `recordTimeout()` is reachable only from `call()`'s
response-timeout arm (:724) — and a deaf-inbound deployment generates none:
the failure removes the only traffic that could detect it (self-sustaining
silence, the same mechanism the class javadoc documents at :898-902 for the
dead-reader sibling). The daemon-side trigger (why fan-out stopped on the
wedged connection) is NOT pinned — the pre-recreate log tail was lost — and
is out of scope: detection + recovery routing is mechanism-agnostic.

## Pitfalls

Numbered consistently with the analysis document.

- P1: silence ≠ deafness — zero inbound is a healthy idle deployment's
  normal state; silence is WARN-surface only, NEVER a restart trigger
  (restart-budget exhaustion → terminal FAILED is the RT-M1-681-r2-1
  lesson).
- P2: no parallel restart path — probe timeouts call the EXISTING
  `recordTimeout()` and inherit its threshold, the `restartRequested` CAS,
  and the daemon-generation gate; a probe answer RESETS the streak (any
  answer proves alive, :231-235).
- P3: probe placement — own scheduler thread (injectable, the
  SignalSubprocess injectedScheduler pattern, SignalSubprocess.java:128/141),
  never the reader or dispatch thread; the probe frame draws an outbound
  pacer token like every wire frame (:220-225) and carries the existing
  responseTimeout.
- P4: injected Clock for the cadence and the silence window (§9);
  production Clock.systemUTC(), tests pin it.
- P5: D37 log hygiene — WARN carries counts/durations/categories only; no
  sender ids, no content, no exception messages.
- P10: §12 spec discipline — rule-text only; user approves the wording at
  implementation.
- P11: no new health bit — `connected()` keeps its channel-liveness meaning;
  the probe's restart escalates through the existing latch/subprocess state,
  so readiness and the gauge go honest through M1-681's path unchanged.

## Approach

Derived from spec_refs: messaging.md §Failure handling commits Signal's
recovery route (supervised restart, :436-439) and the no-false-green /
transient-during-recovery semantics (:447-453); this ticket adds the missing
DETECTION that feeds that route — exactly the shape M1-681 took ("the
missing piece is detection, not recovery").

- **Files to touch:** `files_scope:` (two signal-package classes, adapter
  wiring, one new test class, one spec bullet).
- **Steps, in order:**
  1. Write SignalLivenessProbeTest's six drives — run RED on main (workflow
     §0). The probe drives `connect()` + the fake daemon (FakeSignalCli)
     through the existing test seams; the scheduler and clock are injected.
  2. `SignalConnection`: add the last-inbound-activity stamp (the reader
     stamps it per received line — a byte-level liveness signal, so ANY
     inbound frame resets the silence window, not just dispatched DMs).
  3. `SignalJsonRpcClient`: a scheduled liveness task (started at
     `connect()`, stopped at `disconnect()`/latch) that (a) issues a minimal
     JSON-RPC probe through the normal paced `call()` path with the existing
     responseTimeout — a response resets `consecutiveTimeouts`, a
     `TimeoutException` calls `recordTimeout()` and nothing else; and (b)
     compares `now(clock) - lastInboundActivity` against the silence window
     and WARNs once per crossing (counts only, P5). The probe skips when
     `!isConnected()` — a latched-dead channel is already handled.
  4. `SignalAdapter.start()` wiring: pass the scheduler + Clock.systemUTC()
     (the existing full-constructor seam; keep every constructor's explicit
     arguments — no implicit defaults, the M1-683 discipline).
  5. Spec amendment (acceptance item 8) — draft the detector-enumeration
     sentence as rule-text; the user approves the exact wording before it
     lands (§12).
  6. `mvn verify` from repo root.
- **Controls to preserve (§10):** latchTransportDeath's drain-before-
  dispatcher-shutdown ordering and closed-before-ack PERMANENT stamps; the
  counted+logged inbound drop; the restartRequested CAS one-directional
  deference and the generation gate (probe path inherits both — never a raw
  second `hungRestartHook.run()`); the two existing detectors byte-for-byte;
  the readiness fold and payload shape (no new fields); FakeSignalCli's
  existing seams.
- **Pitfall→mitigation:** P1→step 3(b) is WARN-only + acceptance item 3;
  P2→step 3(a) routes through recordTimeout + acceptance items 1/2/5;
  P3→step 3's scheduler/pacer discipline + item 6; P4→item 7; P5→item 4;
  P10→item 8; P11→no `connected()` change anywhere in the diff.

## Definition of done

A connected-but-deaf Signal channel with zero user traffic escalates to the
supervised restart through the existing threshold/CAS/generation machinery;
a connected-but-silent-but-answering channel WARNs and never restarts; the
WARN is D37-clean; the probe is paced and off the reader/dispatch threads;
all time gates run on an injected Clock; the spec's detector enumeration
names the third detector in user-approved rule-text; the full suite is green
with no pre-existing test modified.

## Verification

- P1 → SignalLivenessProbeTest.silenceAloneNeverRestarts (acceptance 3) —
  feeds the code a silent-but-answering fake daemon past the pinned-clock
  window; asserts WARN + zero restarts + connected() true.
- P2 → acceptance items 1, 2, 5 — threshold exactness, streak reset on any
  answer, generation gate + single-fire CAS under a probe/reader-death race.
- P3 → acceptance item 6 — thread identity + pacer token assertions.
- P4 → acceptance item 7 — fixed-clock boundary tests + the Instant.now()
  grep-gate over the diff.
- P5 → acceptance item 4 — fixed-vocabulary WARN assertion; hostile-input
  coverage: the fake daemon's frames carry adversarial sender shapes and no
  byte of them reaches the WARN.
- P10 → acceptance item 8 — git-diff scope probe on the spec file.
- P11 → AdapterReadinessCheckTest and the M1-681 reader-exit set pass
  UNMODIFIED (acceptance 9): any change to connected()'s meaning breaks them.
- Reproduction → acceptance item 1.

## Out-of-scope

Named in `out_of_scope:` — SimpleX (M1-890 owns it, including the SimpleX
sentence of the spec bullet; this ticket's spec edit is confined to the
Signal detector sentences so the siblings never touch the same lines); the
keepalive layer; any re-tuning of the existing detectors; new
metrics/readiness fields; the daemon-side D-8 trigger; the IPv6/extra_hosts
surface. No pre-existing test is modified — if one fails for a reason not
named here, escalate rather than edit it (§8).

## Census

Class: connected-but-deaf detection coverage across transport adapters
(re-runnable: `grep -rn 'latchTransportDeath\|recordTimeout\|restartHung'
infochat-messaging-adapter/src/main/java/`).

| Site | Disposition |
|---|---|
| SignalJsonRpcClient reader-exit latch | already exists (M1-681) — untouched |
| SignalJsonRpcClient consecutive-timeout escalation | already exists — FEEDING it is this ticket's mechanism |
| Signal connected-but-deaf (live reader, silent channel) | FIXED (this ticket) |
| SimpleXWebSocketClient WS terminal-event latch | already exists (M1-674) — untouched |
| SimpleX zero-SMP-session deafness | defer: M1-890 (the sibling ticket) |
| InMemoryAdapter | out-of-scope: transportless SPI double — no wire to go deaf; `connected()` default true is its documented contract (MessagingAdapter.java:391-394) |

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-889-transport-liveness-instrumentation.md
```
