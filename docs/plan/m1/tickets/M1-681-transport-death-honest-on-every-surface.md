---
id: M1-681
title: "Make a dead transport honest on readiness and on Signal"
status: pending
created: 2026-07-23
last_updated: 2026-07-23
blocked_by: [M1-674]
files_budget: 8
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health/AdapterReadinessCheckTest.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
  - docs/spec/messaging.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The SimpleX adapter's transport-death path. M1-674 delivered the
    latch, the paced rebuild campaign and the counted inbound drop for
    the SimpleX bot WebSocket; this ticket consumes that work on the
    readiness surface and mirrors the detection half for Signal. No
    change to SimpleXWebSocketClient or SimpleXAdapter.
  - >-
    A keepalive / ping layer for either transport. Detecting a dead
    channel from its own read side (this ticket) and preventing the
    idle close that kills it (a keepalive) are separate mechanisms; the
    second is not required for the first and stays a possible follow-up.
  - >-
    Rewriting Signal's recovery ROUTE. The supervised restart-to-
    reconnect path already exists and works (`SignalAdapter` registers
    `sp.onRestart(this::onSubprocessRestart)` at :516, which spawns
    `reconnect()` at :531, and the client already holds
    `hungRestartHook` = `sp::restartHung`, wired at `SignalAdapter.java:273`).
    This ticket adds the missing DETECTION that feeds that route; it does
    not redesign it and does not add a second recovery mechanism.
  - >-
    Unifying SimpleX's in-place-rebuild recovery with Signal's
    supervised-restart recovery into one shared model. The spec already
    records the route as adapter-specific; converging them is a design
    exercise, not this fix.
  - >-
    The `adapter.connection.status` Micrometer gauge and any new metric
    or reason label. The gauge already reports the transport honestly
    for SimpleX; this ticket's observability delta is the readiness
    payload and Signal's `connected()`.
acceptance:
  - >-
    AdapterReadinessCheckTest proves the readiness payload reports an
    adapter DOWN when its transport is dead even though the adapter
    started cleanly and its supervisor has not terminally failed: the
    per-adapter `up` boolean folds `MessagingAdapter.connected()`
    alongside the existing startup snapshot and
    `supervisorTerminallyFailed()` legs. A started, non-terminally-failed
    adapter whose `connected()` is false reads false in the payload, and
    overall status is DOWN when no adapter is up.
  - >-
    AdapterReadinessCheckTest preserves the existing absent-adapter
    contract: a name present in the startup snapshot but absent from the
    live adapter map keeps its snapshot value (there is no live instance
    to consult, so no transport truth is available for it).
  - >-
    A new test in SignalJsonRpcClientTest proves the reader loop's exit
    latches the channel dead on BOTH arms — clean EOF and IOException:
    after the reader returns, `isConnected()` is false, so
    `SignalAdapter.connected()` stops reporting a dead JSON-RPC channel
    as healthy while signal-cli keeps running.
  - >-
    A new test proves the latched channel death drives recovery rather
    than only reporting it: the reader's exit fires the existing
    restart hook so the supervised restart-to-reconnect path rebuilds
    the client, with no dependence on outbound traffic. Pending
    JSON-RPC futures are drained at the latch with the closed-before-ack
    category the codebase already stamps, so no caller blocks on a
    response that can never arrive.
  - >-
    A new test proves the fix closes the no-outbound-traffic hole
    specifically: with zero `call()` invocations after the channel dies
    (the realistic case — inbound death removes the traffic that would
    have produced replies), detection still fires. The pre-fix code
    could not detect this at all, because `consecutiveTimeouts` is
    incremented only from the response-timeout arm.
  - >-
    The consecutive-response-timeout escalation is preserved unchanged
    as the detector for a hung-but-alive daemon (the deadlocked-child
    case its javadoc describes, which a live reader thread cannot
    detect). The new reader-side latch is an additional detector, not a
    replacement, and the two must not double-fire a restart for one
    death.
  - mvn verify is green from the repo root
  - >-
    docs/spec/messaging.md §Failure handling withdraws the v1 carve-out
    M1-674 added — the sentence stating that the latch is implemented
    for the SimpleX bot WebSocket only, that Signal detects a dead
    channel solely through consecutive response timeouts, and that a
    Signal channel dying while signal-cli runs is not latched. The
    transport-death bullet's latch MUST and its no-false-green sentence
    then hold for both v1 production adapters without qualification,
    which is what the bullet asserted before M1-674 had to narrow it.
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/health/AdapterReadinessCheckTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
  - docs/spec/security.md §Trust boundaries
  - docs/spec/deployment.md §Health and observability
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-681: Make a dead transport honest on readiness and on Signal

## Context

M1-674's round-3 `redteam-multi` audit returned four findings. Two were
fixed in that ticket; the two here are pre-existing conditions that fall
outside its `files_scope`, so they were dispositioned to a follow-up
(evidence: `docs/plan/m1/redteam-multi/M1-674-2026-07-23/`, findings 1
and 2, both severity medium, category DOS). Both were re-verified against
the code on 2026-07-23 and survived an explicit falsification pass; the
citations below are that pass's output, not the auditor's prose.

They are one deliverable, not two, because each is incomplete without the
other. Finding 1 is that the readiness payload never consults the
transport, so an honest `connected()` is invisible to the operator's
signal. Finding 2 is that Signal's `connected()` is not honest in the
first place, so a readiness check that folded it would still read green
for Signal. Fixing either alone leaves a false-green path open; together
they deliver one property — **a dead transport is visible on every
operator surface, for every v1 adapter, and drives recovery.** M1-674
delivered exactly that property for the SimpleX WebSocket; this ticket
finishes it for the readiness payload and for Signal.

**Finding 1 — the readiness payload is false-green during any transport
outage.** `AdapterReadinessCheck.evaluate` computes
`boolean up = entry.getValue() && !terminallyFailed`
(`AdapterReadinessCheck.java:84-85`) — "started at boot AND supervisor
not terminally FAILED". It never consults the transport. Verified: no
provider code calls `.connected()` anywhere (`grep -rn "\.connected()"
infochat-provider/src/main/java/` returns zero hits), and
`connectionState`'s only writers are `MessagingStartup.java:72,76,79`
(`reset` / `reportStarted` / `reportFailed`), all at boot — so the
snapshot leg is frozen after startup. There is no second health check to
catch it: `infochat-provider/.../health/` holds one check class, annotated
`@Readiness` only, with no `@Liveness` anywhere. So for the entire
peer-close outage M1-674 exists to handle — subprocess RUNNING, so
`supervisorTerminallyFailed()` is false — `/q/health/ready` reports the
adapter up while `connected()` and the Micrometer gauge report it down.
The class's own javadoc states the intent this misses: "otherwise a
deployment could read 'ready' with a permanently dead adapter"
(`AdapterReadinessCheck.java:26-29`).

**Finding 2 — Signal channel death is undetectable without outbound
traffic.** The strongest falsification available was that the JSON-RPC
channel might be the subprocess's stdio, making channel death and process
death the same event and the supervisor's existing exit path sufficient.
It is not: the channel is a TCP socket (`SignalJsonRpcClient.java:251`
`@Nullable private volatile Socket socket`, dialed from an
`InetSocketAddress endpoint` via the `newSocket()` seam at :344), so it
can die while signal-cli runs. `readerLoop` (:769-815) then exits on a
clean EOF (the `while ((c = r.read()) != -1)` loop simply ends) and on
`IOException` (caught, DEBUG-logged, return) **without setting any flag,
draining pending futures, or notifying anything**. `isConnected()`
(:942-943) returns `dispatchQueue != null`, and `dispatchQueue` is nulled
only by `disconnect()` (:419), so it stays true; `SignalAdapter.connected()`
(:404-409) folds it and reads the dead channel as up. No keepalive exists
(the signal package's only `ScheduledExecutorService` is
`SignalSubprocess`'s restart scheduler).

The consecutive-response-timeout escalation does not save it, for two
independently sufficient reasons — this is where the finding is stronger
than the auditor stated:

1. **No outbound traffic, no detection.** `recordTimeout()` is reachable
   only from the response-`TimeoutException` arm of `call()` (:671). A
   dead reader kills inbound, so no user message arrives, so no reply is
   attempted, so `call()` never runs and `consecutiveTimeouts` never
   increments. The silence is self-sustaining: the failure removes the
   only traffic that could detect it.
2. **With outbound traffic, the counter can freeze below threshold.**
   `HUNG_TIMEOUT_THRESHOLD` is 3 (:145). On a peer-closed socket the
   first write typically succeeds into the send buffer and its response
   never arrives (counter = 1); once the peer RSTs, subsequent writes
   throw `IOException`, which returns TRANSIENT at :661-664 **without
   touching the counter**. It can sit at 1 indefinitely.

The recovery machinery for Signal already exists and is untouched by this
ticket — `SignalAdapter.java:516` registers `sp.onRestart(this::onSubprocessRestart)`,
which spawns `reconnect()` (:531), and the client already holds
`hungRestartHook` = `sp::restartHung` (`SignalAdapter.java:273`, fired at
`SignalJsonRpcClient.java:726`). **The missing piece is detection, not
recovery**, which is what keeps this ticket small despite its reach.

## Acceptance

See the frontmatter. In short: the readiness payload folds
`connected()` so a dead transport reads DOWN; Signal's reader-loop exit
latches the channel dead on both its EOF and IOException arms, drains
pending futures and fires the existing restart hook so recovery runs
without depending on outbound traffic; the existing timeout escalation is
preserved for the hung-but-alive daemon it was written for; and the spec
withdraws the v1 Signal carve-out M1-674 had to add.

## Out-of-scope

The SimpleX transport-death path (M1-674 delivered it), a keepalive layer
for either transport, any redesign of Signal's recovery route, converging
the two adapters' recovery models, and new metrics or gauge changes. See
the frontmatter for the full list and the reasoning.

**Pre-existing tests this ticket modifies** (named per the test-integrity
rule): `AdapterReadinessCheckTest` and `SignalJsonRpcClientTest` gain
cases and may need existing cases updated, because folding `connected()`
into the readiness verdict changes what a started-but-disconnected
adapter reports. Any existing case that asserts a started adapter reads
up while its transport is down was asserting the defect; update it to the
new contract and say so in the commit. No other pre-existing test may be
weakened — if one fails for a reason not named here, escalate rather than
edit it.

## Notes

- Adjacent code / the pattern to match: `SimpleXWebSocketClient.latchTransportDeath`
  (post-M1-674) is the reference shape for the Signal latch — latch the
  closed flag, drain pending futures with the closed-before-ack category,
  tear the dispatcher down counting what it discards, then fire the
  death hook. Signal's variant is simpler: it routes recovery through the
  existing supervised restart instead of an in-place rebuild campaign, so
  it needs no backoff ladder of its own.
- Order the two legs so neither lands half-honest: making readiness fold
  `connected()` while Signal's `connected()` still lies would leave the
  Signal readiness leg false-green with the *appearance* of a fix. If the
  legs are split across rounds, do the Signal latch first.
- Watch for double-firing a restart: the reader-side latch and the
  consecutive-timeout escalation can both observe the same death. The
  timeout path already CAS-guards its hook (`recordTimeout` at :717-728,
  "CAS-then-fire so a burst of concurrent timeouts that crosses the
  threshold restarts the subprocess once"); the new path needs an
  equivalent guard rather than a second, ungated `hungRestartHook.run()`.
- `SignalSubprocess.restartHung` is the existing hook name and semantics
  ("alive but not answering"). A reader-side death is a different cause
  with the same remedy; if reusing the hook makes its name misleading,
  prefer renaming nothing and documenting the widened cause at the call
  site over inventing a parallel mechanism.
- The one out-of-model advisory worth keeping in view from the same audit
  round: a JVM-level `Error` (not `RuntimeException`) escaping a recovery
  thread would still kill it silently. Rated below findings grade in
  M1-674 (outside the threat model; `connected()` stays honest-false),
  and explicitly NOT part of this ticket's acceptance — noted only so the
  implementer does not rediscover it as new.
- Prior art and full evidence: `docs/plan/m1/redteam-multi/M1-674-2026-07-23/verdict-claude.txt`
  (findings 1 and 2 verbatim), and M1-674's ticket frontmatter, which
  carries both findings under `redteam_findings:` dated 2026-07-23.
