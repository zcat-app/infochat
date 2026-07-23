---
id: M1-681
title: "Make a dead transport honest on readiness and on Signal"
status: done
created: 2026-07-23
last_updated: 2026-07-23
blocked_by: [M1-674]
files_budget: 11
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health/AdapterReadinessCheckTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/health/FakeReadinessAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalConnection.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
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
    A superseded reader cannot damage the connection that replaced it — by
    construction, not by lock discipline. Per-connection transport state
    (socket, writer, pending-request map, inbound dispatcher, the death
    latch flag and the restart-request flag) moves onto one carrier object,
    `SignalConnection`, which `connect()` swaps as a unit. A reader that
    outlived `disconnect()`'s bounded 2 s join then holds a reference to
    its OWN connection only and has no path to a later one: it cannot latch
    it closed, drain its in-flight calls closed-before-ack, or shut down its
    dispatcher. This replaces the shared-mutable-field arrangement the first
    implementation copied from `SimpleXWebSocketClient` — the shape SimpleX
    gets free from building a fresh client per rebuild, and the one thing
    Signal's reuse-one-client-across-reconnects lifecycle does not.
  - >-
    The supervised-restart hook is the one effect a per-connection carrier
    cannot scope, because the subprocess is SHARED: a superseded reader
    firing it would SIGKILL the child out from under the live connection.
    It is gated on the daemon GENERATION the dying connection was built for
    still being the live generation, not merely on the connection still
    being current. `SignalSubprocess` exposes a monotonic per-spawn
    generation; `connect()` stamps the live generation onto the connection;
    the reader-exit latch and the consecutive-timeout escalation each fire
    the restart only when that stamp still equals the subprocess's current
    generation. The hook runs outside any lock (it is caller-injected and
    kills a child process, so holding a lifecycle lock across it is a
    deadlock hazard). A `conn == current` check is NOT sufficient on its
    own: the supervised restart respawns the child (generation advances)
    up to 15 s before `reconnect()` retires the dead connection, so for
    that whole window a stale reader's `conn` still equals `current` while
    a healthy successor daemon is already running — firing then SIGKILLs
    it and burns the supervisor's restart budget toward the terminal
    FAILED state the readiness surface reports. A test drives that window
    deterministically: with the daemon generation advanced but the dead
    connection still current, a reader exit must fire no restart; and with
    the generation matching, it must.
  - >-
    `SignalJsonRpcClientTest.readIoExceptionLatchesChannelDead` — one of
    this ticket's own reader-exit tests — is hardened against a
    reader-startup race it carried since the first implementation. It
    injected the IOException by flipping an `AtomicBoolean` and then
    pushing a byte; if the reader thread reached its first `read()` AFTER
    the flip, it threw before any data arrived, exited, and closed the
    socket before the push could write (SocketException, surfaced under
    sustained full-suite load, ~1 run in 3 on this host). The fault is now
    keyed to a SENTINEL byte the stream throws on, so the reader always
    blocks in the real `read()` until the byte arrives — deterministic, no
    startup race. This is the ONE detection test that changes and it is a
    timing fix, not a contract change: it still proves an IOException on
    the read side latches the channel dead. The other five reader-exit
    tests are unmodified.
  - >-
    A new test drives the stale-reader case deterministically and with no
    test seam in production code: connect, publish a REPLACEMENT connection
    while the first reader is still parked in read(), then sever the first
    socket from the client side through the existing `newSocket()` seam.
    The replacement must survive intact — connected, its in-flight call
    resolving from the wire rather than closed-before-ack, its dispatcher
    still delivering inbound, and no restart fired. Five of the six
    reader-exit detection tests pass UNMODIFIED (the sixth,
    `readIoExceptionLatchesChannelDead`, gets a timing-only hardening — see
    the dedicated item below); the detection contract is unchanged, so any
    contract-level edit to them would signal a regression, not an update.
  - >-
    `FakeSignalCli.killClientConnection()` stops silently no-opping when it
    races the accept. On loopback `Socket.connect()` returns from the
    kernel's accept backlog before `server.accept()` returns, so a test that
    connects and immediately kills can arrive while `clientConn` is still
    null — the close is skipped, no FIN is sent, and the client's reader
    stays parked in `read()` until the test's own await expires. It awaits
    the accepted connection on the same bounded pattern `awaitWriter()`
    already uses. The no-op is pre-existing; the flake is not — only the
    connect-then-immediately-kill shape THIS ticket introduces reaches it
    (observed failing `peerEofLatchesChannelDead` during implementation).
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
    - infochat-provider/src/test/java/app/zcat/infochat/provider/health/FakeReadinessAdapter.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClientTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
  preserves:
    - all tests currently green on main except the named authorized
      modifications (SignalReconnectTest.sendDuringOutageFailsTransient
      updates to the post-latch contract; FakeReadinessAdapter gains an
      overloaded constructor with unchanged existing-call behavior)
spec_refs:
  - docs/spec/messaging.md §Failure handling
  - docs/spec/security.md §Trust boundaries
  - docs/spec/deployment.md §Health and observability
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-23
    verdict: REWORK
    checks:
      scope_drift: FAIL
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 15
      added: 1582
      removed: 179
  - round: 2
    date: 2026-07-23
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 1310
      removed: 178
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - id: RT-M1-681-r2-1
    date: 2026-07-23
    audit: docs/plan/m1/redteam-multi/M1-681-2026-07-23-r2/
    reporter: claude
    category: DOS
    severity: medium
    status: fixed-in-band
    summary: |
      The per-connection restart guard used `conn == current`, which is
      insufficient in production. The supervised restart respawns the
      signal-cli child (a healthy successor is alive) up to 15 s BEFORE
      reconnect() retires the dead connection — doRestart() spawns the new
      child before firing restartListener, and reconnect() blocks on
      awaitEndpoint (ENDPOINT_PROBE_TIMEOUT = 15 s) before c.disconnect()
      nulls `current`. For that whole window a stale reader's `conn` still
      equals `current` while a healthy new daemon runs, so its exit fires
      restartHung → destroyForcibly on the successor. Each spurious kill
      counts toward SUBPROCESS_MAX_RESTARTS = 5 without resetting the
      streak (victim lived < 30 s healthy-uptime), so a repeat drives the
      supervisor to terminal FAILED — the adapter is permanently down, and
      in a single-adapter deployment the whole bot is offline. All premises
      verified against source (SignalSubprocess.java:263/285/372-383,
      SignalAdapter.java:547/562, backoff base 250 ms). NEW in this diff:
      pre-M1-681 a reader exit fired nothing. Out of the threat model
      (local thread scheduling on a loopback channel) but an orphan this
      ticket's own change created, so fixed in-band — the 2026-07-23
      round-2 refine replaced the `conn == current` guard with a daemon-
      generation guard.
    out_of_model_note: |
      Two further claude-only findings were falsified and NOT filed.
      (low, DOS, SignalJsonRpcClient:1009-1012) "wedged-but-alive daemon
      holding the socket open reads false-green" — explicitly out_of_scope
      (the keepalive/ping bullet); the timeout escalation is the sanctioned
      detector for a hung-alive daemon; the auditor concedes it is "a
      residual the diff narrows rather than a regression." (low, DOS,
      :875-878) "no dampening on latch restarts" — inaccurate: the
      SignalSubprocess backoff (250 ms x2, cap 30 s, max 5) plus the
      per-connection restartRequested CAS already dampen; its only real
      content is "spurious kills burn budget", which IS the medium finding
      above. codex and kimi both returned CLEAN.
redteam_audits:
  - date: 2026-07-23
    verdict: CLEAN
    auditors: [claude, codex, kimi]
    base: main
    head: m1/M1-681-make-a-dead-transport-honest-o
    verdict_file: docs/plan/m1/redteam-multi/M1-681-2026-07-23-r3/cross-examination.md
    findings_count: 0
    out_of_model_count: 3
    note: |
      Round-3 re-audit of the daemon-generation guard (round-2 FINDINGS was
      remediated by it, so that audit was invalidated). All three auditors
      CLEAN, zero finding clusters. Three out-of-model items, each
      dispositioned:
        - Restart-budget exhaustion is not adversary-reachable (claude):
          confirmatory — explicitly notes RT-M1-681-r2-1 is FIXED in this
          diff by the daemon-generation guard. No action.
        - Future-adapter false-green via MessagingAdapter.connected()
          default (claude): already tracked as M1-682. No action.
        - The gate's check-then-act nanosecond window (kimi): the residual
          TOCTOU documented in latchTransportDeath's own comment. Not
          adversary-steerable, self-limiting to one restart-budget unit,
          and for the reader-exit path the respawn that could bump the
          generation is triggered BY the death firing now, so it is a
          backoff (>= hundreds of ms) in the future and cannot slip into
          the gap. Closing it needs a lock held across the SIGKILL hook —
          the exact deadlock hazard the design avoids. Out-of-model across
          all four rounds; no ticket.
        - Wedged-but-alive daemon residual (kimi): explicitly out_of_scope
          (the keepalive/ping bullet); previously adjudicated in r2. No
          ticket.
        - The 7-arg constructor's `() -> 0L` generation default silently
          disables the RT-M1-681-r2-1 gate if a future wiring pairs it with
          a real restart hook (kimi): real future-misuse footgun, zero
          current breach (the sole production call site wires
          SignalSubprocess::generation). Same class as M1-682, filed the
          same way as M1-683 rather than folded.
  - date: 2026-07-23
    verdict: FINDINGS
    auditors: [claude, codex, kimi]
    base: main
    head: m1/M1-681-make-a-dead-transport-honest-o
    verdict_file: docs/plan/m1/redteam-multi/M1-681-2026-07-23-r2/cross-examination.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Round-2 re-audit of the per-connection redesign (round-1 CLEAN was
      invalidated by the src/ change). claude FINDINGS (3 clusters, all
      claude-only), codex CLEAN, kimi CLEAN. Falsification: cluster 1
      (medium) survived as RT-M1-681-r2-1 above — the `conn == current`
      guard was insufficient against the 15 s respawn-before-retire window;
      fixed in-band via a daemon-generation guard. Clusters 2 and 3 (both
      low) were falsified as a documented out-of-scope residual and an
      inaccurate no-dampening claim respectively (see the finding's
      out_of_model_note). kimi restated the round-1 MessagingAdapter
      .connected() default-true hazard already tracked as M1-682.
  - date: 2026-07-23
    verdict: CLEAN
    auditors: [claude, codex, kimi]
    base: main
    head: m1/M1-681-make-a-dead-transport-honest-o
    verdict_file: docs/plan/m1/redteam-multi/M1-681-2026-07-23/cross-examination.md
    findings_count: 0
    out_of_model_count: 3
    note: |
      3-auditor redteam-multi (opencode excluded at the user's direction).
      All three returned CLEAN with zero finding clusters, so there was
      nothing to cross-examine. Three out-of-model items were raised and
      each was put through an independent falsification pass:
        - Zombie-reader TOCTOU in the new latch (claude AND kimi,
          independently). Survived falsification as a real — and NEW —
          reliability defect: disconnect() never nulls `socket`, so the
          latch's identity guard can pass on stale state and then mutate a
          fresh connection. Out of the threat model (local thread
          scheduling on a loopback channel, not adversary-steerable), but
          it is an orphan THIS ticket's own change created, so it is fixed
          in-band here rather than deferred: the 2026-07-23 round-1 refine
          added the atomic-ownership acceptance item for it.
        - MessagingAdapter.connected()'s interface default of true (kimi).
          Survived: the default is deliberate and correct for
          transportless adapters, but this ticket widened its blast radius
          from a wrong metric to a false-green readiness payload, with no
          compile-time or test signal for a transport adapter that
          inherits it. Filed as M1-682.
        - Readiness may flap while a daemon crash-loops (claude). Did NOT
          survive as a defect: truthful reporting is this ticket's explicit
          intent (acceptance item 1) and the flap is a direct corollary of
          the messaging.md text this ticket already lands. No ticket filed.
      One in-band correction came out of the audit: claude observed that
      the spec sentence this ticket added claimed the restart fires "at
      most once per death, whichever detector observes it first", which
      overstates a guard that is one-directional by design. The sentence
      was rewritten to describe the actual deference direction.
clarity_check:
  date: 2026-07-23
  verdict: WARN
  warnings:
    - >-
      SPEC-REFS-RESOLVABLE: spec_ref 'docs/spec/security.md §Trust
      boundaries' is AMBIGUOUS (matches lines 38 and 233); anchor
      resolution picks one by the depth heuristic.
    - >-
      Self-check (post outline-fail refine): all code claims re-verified
      against the working tree, including the refine's new ones —
      SignalReconnectTest.sendDuringOutageFailsTransient blanket
      TRANSIENT assertion and outage-premise comment (:58-93),
      FakeReadinessAdapter final with no connected() override,
      MessagingAdapter.connected() interface default true (:362-364),
      attachSubprocess registering onRestart (SignalAdapter.java:516),
      disconnect()'s closed-before-ack PERMANENT drain (:409-412).
  blockers: []
escalation_reason:
outline_file: target/m1-tick-outline-M1-681.md
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
preserved for the hung-but-alive daemon it was written for; the latch's
ownership check is atomic with the state it gates, so a stale reader
cannot latch a later connection; and the spec withdraws the v1 Signal
carve-out M1-674 had to add.

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
new contract and say so in the commit.

Two further authorized modifications (added by the 2026-07-23 outline-fail
refine — the original scope made the ticket unimplementable without them):

- `SignalReconnectTest.sendDuringOutageFailsTransient` pins the
  pre-latch contract: it severs the connection via
  `FakeSignalCli.killClientConnection()`, immediately sends, and asserts
  the failure is always TRANSIENT on the premise (its own comment) that
  "no reconnect is coming". Post-latch, both halves of that premise are
  false: the latch fires `restartHung`, and `attachSubprocess` registers
  `onRestart` (`SignalAdapter.java:516`), so a supervised
  respawn+reconnect really does run inside that test's wiring; and the
  raced send's future can be drained with the closed-before-ack PERMANENT
  category — an outcome `docs/spec/messaging.md` §Failure handling
  explicitly blesses ("a send that raced the close can learn permanent").
  The put-future-then-drain vs drain-then-put interleaving is a genuine
  coin toss on loopback, so the unmodified test would fail
  intermittently. Update its raced-send expectation (either category is
  legal; assert the failure is one of the two, or force one interleaving
  deterministically) and rewrite its outage-premise comments to the
  post-latch contract. This is a contract update, not a weakening — the
  test was asserting the absence of the very detection this ticket adds.
- `FakeReadinessAdapter` is `final`, does not override `connected()`, and
  so inherits the interface default `true` (`MessagingAdapter.java:362-364`)
  — acceptance item 1's DOWN case is untestable with it as-is. Add an
  overloaded constructor taking a `connected` parameter; the existing
  3-arg constructor delegates with `connected=true` so the seven existing
  `AdapterReadinessCheckTest` cases and `ReadinessPayloadShapeTest` are
  untouched.

No other pre-existing test may be weakened — if one fails for a reason
not named here, escalate rather than edit it.

## Round 1 rework

Reviewer round 1: REWORK, one item (SCOPE-DRIFT). Every code/test/spec
check PASSED. The FAIL is commit hygiene: the follow-up ticket files
`M1-682-*` and `M1-683-*` are committed on this branch and outside
`files_scope`, so the M1-681 squash would carry two other tickets'
definitions. Fix: remove both from this branch; they land on `main` as a
separate `process:` commit (CLAUDE.md commit-prefix rule: pure-doc
follow-up tickets bypass the ticket flow). No change to the fix or tests.

## Notes

- Adjacent code / the pattern to match: `SimpleXWebSocketClient.latchTransportDeath`
  (post-M1-674) is the reference shape for the Signal latch — latch the
  closed flag, drain pending futures with the closed-before-ack category,
  tear the dispatcher down counting what it discards, then fire the
  death hook. Signal's variant is simpler in its RECOVERY ROUTE: it goes
  through the existing supervised restart instead of an in-place rebuild
  campaign, so it needs no backoff ladder of its own. It is HARDER in
  state ownership, and the 2026-07-23 round-1 refine exists because this
  note originally said only the first half. `SimpleXAdapter` builds a
  fresh `SimpleXWebSocketClient` per rebuild (`SimpleXAdapter.java:394`),
  so every SimpleX latch mutation is scoped to one connection for free.
  Signal reuses ONE client across reconnects, so latch state copied
  field-for-field from SimpleX becomes shared across connections, and a
  reader that outlived `disconnect()`'s bounded join can reach a
  connection it does not own. Mirror the SHAPE of SimpleX's latch; do not
  mirror its state layout — give Signal the per-connection carrier
  SimpleX gets from its lifecycle.
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
- `readerLoop` has a THIRD exit path beyond the two arms the acceptance
  names: the `return` inside the oversize-line drain when
  `skipToNewline` hits EOF (`SignalJsonRpcClient.java:799-803`). That is
  a real transport death that is neither the clean-EOF loop end nor the
  `IOException` catch, so wire the latch to loop exit generally (e.g. a
  `finally` around the reader body), not to the two named arms
  individually.
- The one out-of-model advisory worth keeping in view from the same audit
  round: a JVM-level `Error` (not `RuntimeException`) escaping a recovery
  thread would still kill it silently. Rated below findings grade in
  M1-674 (outside the threat model; `connected()` stays honest-false),
  and explicitly NOT part of this ticket's acceptance — noted only so the
  implementer does not rediscover it as new.
- Prior art and full evidence: `docs/plan/m1/redteam-multi/M1-674-2026-07-23/verdict-claude.txt`
  (findings 1 and 2 verbatim), and M1-674's ticket frontmatter, which
  carries both findings under `redteam_findings:` dated 2026-07-23.
