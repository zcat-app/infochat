---
id: M1-674
title: "Recover SimpleX adapter from peer-closed WebSocket"
status: done
created: 2026-07-22
last_updated: 2026-07-23
blocked_by: []
files_budget: 7
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
  - docs/spec/messaging.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The Signal adapter. Signal's `restartHung` escalation (consecutive
    response timeouts force a supervisor restart) already covers the
    analogous wedged-daemon case; the audit confirmed the gap is
    SimpleX-specific in practice. Unifying the two adapters' recovery
    models is a design exercise, not this fix.
  - >-
    A keepalive/ping layer for the bot WebSocket. Preventing the
    server-side idle close would reduce how often the gap fires but does
    not close it (a daemon-internal connection error takes the same path);
    it may be a follow-up, not a substitute.
  - >-
    Changes to the process-exit restart path, which already works
    (SimpleXSubprocess restarts on exit; the adapter rebuilds via
    onRestart). This ticket adds the missing peer-close arm, not a rework
    of the exit arm.
acceptance:
  - >-
    A new test in SimpleXWebSocketClientTest proves a peer-initiated close
    (and separately an onError) latches the client closed: `isClosed()`
    returns true afterwards, so `SimpleXAdapter.connected()` no longer
    reports a dead transport as healthy.
  - >-
    A new test proves the peer-close event reaches the supervised unit:
    either the adapter rebuilds the WebSocket against the still-alive
    subprocess, or the event routes through the subprocess supervisor's
    restart path (the SignalSubprocess.restartHung precedent) — pick one
    and state it in the commit. After the simulated peer close, a
    subsequent send is exercised against a live transport again without
    any process exit having occurred.
  - >-
    Peer-close-triggered rebuild attempts are paced by the §6.4.6
    network-failure backoff ladder (docs/design/06-messaging.md §6.4.6:
    exponential toward the 60 s steady state, counter reset on successful
    reconnect), never an immediate hot retry; a new test pins the pacing,
    with the backoff base injectable (package-private seam, the
    SimpleXSubprocess constructor precedent) so the test runs at
    millisecond scale.
  - >-
    A new test proves the failure window is operator-visible while it
    lasts: the adapter.connection.status gauge / readiness reflects the
    dead transport between the peer close and the completed recovery
    (no false-green interval).
  - mvn -pl infochat-messaging-adapter verify is green
  - >-
    docs/spec/messaging.md §Failure handling records that a peer-initiated
    WebSocket close is a transport-death event that drives recovery, not
    just pending-future drainage, and that recovery attempts follow the
    design-tier §6.4.6 network-failure backoff cadence. The bullet states
    that the recovery route is adapter-specific — SimpleX rebuilds the
    WebSocket in place, Signal escalates a dead channel through its
    supervised restart — so it does not promise the SimpleX mechanism for
    the Signal channel this ticket leaves out of scope. It does not assert
    the latch itself, or the no-false-green reporting property, for the
    Signal JSON-RPC channel either: the bullet records that v1 delivers
    both for the SimpleX bot WebSocket only, and that Signal's
    channel-death detection is limited to the consecutive-response-timeout
    escalation — so a Signal channel that dies while signal-cli keeps
    running is not latched and `connected()` may report it up until an
    outbound send times out. Stating the limit is the whole delivery for
    Signal; no Signal code changes.
  - >-
    A transport-death notification arriving while a prior recovery campaign
    (or the process-exit reconnect arm) still holds the single-flight flag
    is never dropped: the recovery entry waits for the flag instead of
    returning, and once it holds the flag it re-checks liveness so a
    notification already answered by the holder exits without disturbing a
    live client. The wedged end state the notifier's one-shot nature makes
    unrecoverable — client closed, subprocess RUNNING, reconnecting false,
    no recovery scheduled — is unreachable.
  - >-
    The recovery campaign survives an unchecked exception from a rebuild
    attempt: the attempt counts against the ladder, pacing continues, the
    reconnecting flag is never left latched by a dead thread, and the
    failure reaches the application logger under D37 (exception class name
    only, never the message). The injectable backoff ladder is validated
    non-empty at construction — the SimpleXSubprocess "command must be
    non-empty" idiom — and a new test pins that validation.
  - >-
    A send on a dead transport whose subprocess supervisor has terminally
    FAILED classifies PERMANENT unconditionally: the classification is not
    shadowed by the `reconnecting` flag, which a failed exit-arm rebuild
    parks true with no path clearing it on the FAILED transition. A new
    test pins that a send with `reconnecting` set and the supervisor
    FAILED is PERMANENT, not TRANSIENT.
  - >-
    The recovery campaign does not abandon on a merely transient
    subprocess state. While the supervisor is between processes (not
    RUNNING, not terminally FAILED) the campaign keeps its paced watch
    instead of returning, so a restart notification that loses the
    single-flight CAS is harmless — the campaign itself picks the
    respawned child up, which is what the ownership-split javadoc already
    claimed. It returns only when the adapter is torn down or the
    supervisor has terminally FAILED, whereupon the item above classifies
    sends PERMANENT. A new test pins that a campaign observing a
    non-RUNNING subprocess rebuilds once that subprocess is RUNNING again,
    with no restart notification delivered.
  - >-
    A peer-initiated transport death does not silently discard accepted
    inbound: the queued-but-undelivered deliveries the dispatcher
    teardown discards are added to the adapter's dropped-inbound counter
    — the same counter the readiness payload exposes as
    `<name>.dropped-inbound` — and one WARN records the discarded depth
    (count only; no message content, no contact ids). The deliberate
    local-close teardown stays uncounted (a shutdown, not an outage).
    No new Micrometer reason label: `AdapterMetrics.DropReason` and its
    §6.3.7/§6.3.10 label domain are outside this ticket's files_scope,
    so observability rides the counter plus the log line. A new test
    pins the counted drop: a transport death with queued inbound raises
    the counter by exactly the queue depth and emits the WARN.
  - >-
    docs/spec/messaging.md no longer contradicts the drain path it
    ships beside: the transient-classification sentence is scoped to
    sends attempted after the latch, and the transport-death bullet
    states that commands already in flight when the connection dies
    drain with the closed-before-ack PERMANENT convention both adapters
    stamp, so a send racing the close can learn permanent even though
    recovery completes moments later. The §"Per-category digest
    delivery attribution (D63)" calibration sentence ("SimpleX
    classifies a send on a closed or not-yet-started WebSocket as an
    IMMEDIATE PERMANENT") is restated to match the post-latch behavior
    — new sends transient while recovery is pending; the in-flight
    drain and the terminally-failed-supervisor case permanent — while
    preserving D63's justification. Spec text only; the
    FailureCategory the drain stamps is unchanged (cross-adapter
    convention — SignalJsonRpcClient cites the same category).
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
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
      files: 6
      added: 1714
      removed: 25
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
      files: 7
      added: 1737
      removed: 36
overrides: []
aborted_attempts: []
reopens: []
redteam_findings:
  - date: 2026-07-22
    auditor: claude
    category: DOS
    severity: medium
    promise: |
      docs/spec/messaging.md §Failure handling (the commitment this
      diff itself lands): "A peer-initiated connection close is a
      transport-death event. ... the adapter MUST latch that
      connection as dead and drive recovery of the transport, paced
      per the design-tier reconnect cadence ... and sends attempted
      before recovery completes classify transient — unless the
      supervising component has terminally failed, which classifies
      permanent per the default above." Paired with the adjacent
      pre-existing bullet: "An adapter cannot silently drop
      messages. Either delivery succeeds, the caller learns it
      didn't (after the bounded retry budget), or the failure is
      permanent and surfaced immediately." And security.md §Threat
      model: "The Provider is exposed to the internet through every
      enabled messaging adapter" — the SimpleX adapter is the only
      user-facing surface, so its availability is what these
      recovery commitments protect.
    gap: |
      Lost-wakeup race: the one-shot death notification can be
      permanently swallowed while a recovery campaign is finishing,
      leaving a dead transport that nothing will ever rebuild while
      sends keep classifying TRANSIENT "recovery pending".
      Mechanics, all in the diff:
      - SimpleXWebSocketClient.java:547-553 (latchTransportDeath): the
        transport-death notifier fires AT MOST ONCE per client instance
        (`peerInitiated = !closed` latch). There is no re-fire, and
        nothing in the adapter polls `isClosed()` to re-arm recovery.
      - SimpleXAdapter.java:516-520 (recoverFromTransportDeath): a
        notification thread that loses the `reconnectInFlight` CAS
        returns silently — the death event is consumed with no recovery
        scheduled ("The exit arm (or an earlier campaign) is already
        recovering" — but a campaign on its success tail is FINISHING,
        not recovering the new death).
      - SimpleXAdapter.java:563-583 + 599-601: the winning campaign
        holds `reconnectInFlight` across `rebuildWebSocket()` (fresh
        client live and able to die from `ws.start()` at
        SimpleXAdapter.java:391 onward), `buildGroupHandler()`, the
        teardown guard, the LOG.info, and the return — the flag is
        released only in the finally at line 600. A peer close of the
        fresh WebSocket landing anywhere in that window fires the fresh
        client's only notification into a failing CAS.
      - The exit arm has the same tail window: reconnect()
        (SimpleXAdapter.java:426-478) holds the flag from line 426
        until the finally at line 477, with the fresh client live from
        line 446.
      End state after the race: `webSocket.isClosed()` true, subprocess
      RUNNING, `closedForGood` false, `reconnecting` false. No thread,
      timer, or event remains that can rebuild the transport (the
      notifier for the dead client is spent; the process-exit arm only
      fires on subprocess respawn, which never happens because the
      process is healthy). requireConnected's new arm
      (SimpleXAdapter.java:1090-1103) then throws TRANSIENT "SimpleX
      WebSocket was closed by the peer; recovery pending" on every send,
      indefinitely — the caller is told the failure is recoverable and
      retries forever against an outage only a subprocess death or a
      full Provider restart can end. That contradicts both halves of the
      promise: recovery is NOT being driven, and the failure is
      effectively permanent while being surfaced as transient.
    repro: |
      1. Run the SimpleX adapter against a simplex-chat daemon whose
         bot-WS endpoint is flapping — the exact motivating scenario
         the diff's own WS_RECONNECT_BACKOFF comment names ("a daemon
         that kills every fresh WebSocket", SimpleXAdapter.java:164-171).
      2. First peer close → notifier fires → campaign wins the CAS,
         sleeps a rung, rebuilds. Handshake completes (ws.start()
         returns inside rebuildWebSocket at line 391).
      3. The daemon closes the fresh WebSocket within the tail window
         (during buildGroupHandler / guard / LOG.info / return /
         finally — milliseconds, but sampled once per ~1 s cycle
         because a completed handshake resets the ladder, so a
         sustained flap episode hits it with near-certainty).
      4. latchTransportDeath fires the fresh client's single
         notification; the spawned recovery thread loses the CAS at
         line 517 and exits; the finishing campaign releases the flag
         and exits.
      5. From then on: adapter.connection.status reads 0 and every
         Provider send gets TRANSIENT "recovery pending" forever,
         while no recovery exists. The SimpleX adapter — the
         deployment's recommended high-assurance admin surface — is
         down until an operator restarts the Provider or the healthy
         daemon happens to die. The diff shipped specifically to close
         this "dead transport, nothing rebuilds it" state (audit
         finding MSG-1) and reintroduces it through the handoff race.
    suggested_fix_class: other
  - date: 2026-07-22
    auditor: kimi
    category: DOS
    severity: medium
    promise: |
      The diff's own spec amendment (docs/spec/messaging.md,
      §Failure handling, new bullet): "the adapter MUST latch that
      connection as dead and drive recovery of the transport, paced
      per the design-tier reconnect cadence (design notes §6.4.6 /
      §6.5.8), not merely drain the in-flight command futures on the
      dead connection." Backed by the adjacent standing commitment:
      "An adapter cannot silently drop messages. Either delivery
      succeeds, the caller learns it didn't (after the bounded retry
      budget), or the failure is permanent and surfaced immediately."
    gap: |
      recoverFromTransportDeath (SimpleXAdapter.java:515-601) clears the
      `reconnecting` flag only on its five enumerated happy/lifecycle
      exits (lines ~534, ~538, ~548, ~559, ~576-578) and its `finally`
      (line ~599) resets only `reconnectInFlight`. The retry `catch`
      (line ~584) covers MessagingException ONLY. Any unchecked
      throwable escaping waitForWebSocketReady / rebuildWebSocket /
      buildGroupHandler (lines ~563-565) — e.g. an NPE or
      IllegalStateException from the WS-builder internals racing a
      concurrent close(), or an IndexOutOfBounds from the
      unvalidated package-private empty-ladder seam — kills the
      virtual thread with `reconnecting` left latched true. Nothing
      re-arms recovery after that: the death notifier is a one-shot
      per client (latchTransportDeath's peerInitiated check,
      SimpleXWebSocketClient.java:551-559), the current client is
      already latched dead, and no new client exists to die again —
      so no further terminal event can ever fire while the
      simplex-chat process stays alive (the exact MSG-1 shape).
      requireConnected's new arm (SimpleXAdapter.java:1098-1106)
      then classifies every send TRANSIENT indefinitely — neither
      success, nor a learned-permanent failure — and the transport
      is never rebuilt until an unrelated process death (exit arm)
      or an operator restart. The method's own comment ("cleared on
      every exit path") is false for the unchecked-throw path.
      Secondary: the dead thread's uncaught exception goes to the
      JVM default handler on stderr, bypassing the application
      logger and its SafeLog discipline (security.md §User content
      in exceptions) — no WARN/INFO records the wedge at all.
    repro: |
      (1) simplex-chat daemon peer-closes the bot WebSocket (the
      documented idle-close case); onClose latches the client and
      fires the notifier; the campaign thread starts, CAS-acquires
      reconnectInFlight, raises reconnecting, sleeps the 1 s rung.
      (2) During rebuildWebSocket() an unchecked exception escapes
      (any runtime fault in the un-shown rebuild path — the catch at
      line ~584 only names MessagingException). (3) The finally
      frees reconnectInFlight but reconnecting stays true; the
      thread dies; stack trace lands on stderr only. (4) The daemon
      is healthy and serving the port; no process exit ever occurs,
      so the exit arm never runs and no second death event can fire.
      (5) Every subsequent Provider send throws TRANSIENT ("recovery
      pending") forever — the caller retries per its bounded budget,
      learns nothing permanent, and the adapter never recovers: a
      recoverable blip has been converted into a permanent outage of
      the exact class this diff ships to fix, invisible in the
      application log.
    suggested_fix_class: other
  - date: 2026-07-22
    auditor: kimi
    category: DOS
    severity: low
    promise: |
      The same new messaging.md bullet names two channels: "When the
      peer closes (or a transport error kills) the connection an
      adapter depends on — the SimpleX bot WebSocket, the Signal
      JSON-RPC channel — the adapter MUST latch that connection as
      dead and drive recovery of the transport."
    gap: |
      The delivery implements the latch + paced rebuild for SimpleX only
      (SimpleXWebSocketClient.latchTransportDeath,
      SimpleXAdapter.recoverFromTransportDeath). No Signal-side
      change appears anywhere in the diff, and per the audit rule
      the diff is the only evidence of behavior — "Signal already
      handles it elsewhere" cannot be assumed. If the Signal
      JSON-RPC channel can die while the signal-cli process survives
      (channel death not coincident with process death), the
      commitment the diff itself writes is undelivered for that
      adapter and the pre-M1-674 false-green + no-recovery shape
      persists there. Conditional on Signal's channel being
      process-coupled (in which case the existing supervisor path
      satisfies the MUST), this reduces to a spec-precision gap: the
      bullet promises for a named channel with no qualifying note.
    repro: |
      An operator reads the amended messaging.md and relies on
      peer-close recovery on both production adapters. On the Signal
      adapter the JSON-RPC channel peer-closes while signal-cli
      stays alive; nothing in the diff latches that death or paces a
      rebuild, connected() keeps reporting the dead transport as
      healthy (false green), and inbound/outbound silently stall —
      the same MSG-1 failure mode, one adapter over.
    suggested_fix_class: other
  - date: 2026-07-22
    auditor: claude
    category: DOS
    severity: medium
    promise: |
      The transport-death bullet this diff lands
      (docs/spec/messaging.md §Failure handling): "When the peer
      closes (or a transport error kills) the connection an adapter
      depends on — the SimpleX bot WebSocket, the Signal JSON-RPC
      channel — the adapter MUST latch that connection as dead ...
      The outage stays operator-visible for as long as it lasts
      (`connected()` / `adapter.connection.status` report the dead
      transport — no false green)." Backing security.md §Trust
      boundaries item 6, whose health surface must disclose "which
      messaging adapters are enabled and up".
    gap: |
      The round-1 remediation qualified only the *route* half of the
      bullet ("recovery route is adapter-specific"). The latch MUST and
      the no-false-green sentence still cover both named channels
      unqualified, and Signal delivers neither:
      - SignalJsonRpcClient.readerLoop exits silently on EOF (the
        `while ((c = r.read()) != -1)` loop simply ends) and on
        IOException (caught, DEBUG-logged, return, :811-813). No flag
        is set, no pending drain, no notifier, no hook.
      - `isConnected()` (:942-944) returns `dispatchQueue != null`, and
        `dispatchQueue` is nulled only by `disconnect()`. A dead reader
        thread leaves it non-null.
      - SignalAdapter.connected() (:404-409) = subprocess RUNNING &&
        client.isConnected(), so daemon-alive + channel-dead reads TRUE
        and `adapter.connection.status` stays 1 — the exact false green
        the new bullet forbids.
      - The only escalation is recordTimeout(), reachable solely from
        the response TimeoutException in call(). A write on a dead
        socket throws IOException and returns TRANSIENT without
        touching `consecutiveTimeouts`, so an idle bot never escalates
        at all.
      Signal code is out_of_scope for this ticket, so the delivered fix
      is spec-side: the bullet must not assert the latch and the
      no-false-green property for a channel that implements neither.
    repro: |
      Operator enables the Signal adapter. signal-cli stays RUNNING but
      drops the JSON-RPC socket (idle reset / listener recycle).
      readerLoop returns; the reader thread dies; no inbound
      notification is demultiplexed again. The health surface still
      reads `adapter.connection.status{adapter="signal"} == 1`. With no
      outbound traffic — or with sends failing at the write — the
      hung-restart hook never fires, so the deaf state persists until a
      human notices. An operator reading the amended messaging.md would
      have been told this cannot happen.
    suggested_fix_class: other
  - date: 2026-07-22
    auditor: claude
    category: DOS
    severity: low
    promise: |
      The bullet this diff lands: "sends attempted before recovery
      completes classify transient — unless the supervising component
      has terminally failed, which classifies permanent per the default
      above." Restated inline at SimpleXAdapter.java:1155-1158: "Once
      the supervisor has terminally FAILED nothing will ever rebuild
      the transport, and TRANSIENT would loop Provider's retry
      forever".
    gap: |
      The new terminal-failure arm is shadowed by the pre-existing
      `reconnecting` branch. requireConnected() returns TRANSIENT at
      SimpleXAdapter.java:1142-1145 whenever `reconnecting` is set,
      before the `ws.isClosed() && supervisorTerminallyFailed() ->
      PERMANENT` arm at :1159-1167 is ever evaluated. And `reconnecting`
      is parked true by the exit arm's failed rebuild (:483-488,
      "`reconnecting` stays set ... until the next restart notification
      (or the supervisor's FAILED transition)") — but nothing clears it
      on that FAILED transition: the five assignment sites are :456,
      :478, :481 (reconnect) and :575, :660 (the campaign), none of
      which runs once the supervisor has latched FAILED. The campaign
      cannot re-arm either, because it only spawns on a fresh
      transport-death notification and the one client is already latched
      dead. So the spec sentence this diff adds is unreachable in
      exactly the state it was written for.
    repro: |
      (1) simplex-chat crashes; the supervisor respawns and fires
      onRestart; reconnect() sets `reconnecting = true` (:456) and its
      rebuild fails on the ready probe — the flag stays set. (2) The
      daemon crash-loops until the cap is exhausted; the supervisor
      latches State.FAILED and admin-notifies. (3) Every subsequent
      send hits :1142 and gets TRANSIENT "reconnecting after a
      subprocess restart" forever, instead of the PERMANENT the new
      spec sentence promises, so outbound work burns its retry ladder
      against a transport that can never come back and no caller ever
      learns the failure is permanent.
    suggested_fix_class: other
  - date: 2026-07-22
    auditor: claude
    category: DOS
    severity: low
    promise: |
      The bullet this diff lands: "the adapter MUST latch that
      connection as dead and drive recovery of the transport, paced per
      the design-tier reconnect cadence ... not merely drain the
      in-flight command futures on the dead connection."
    gap: |
      The round-1 remediation hardened the campaign's *entry* against a
      lost wakeup (:545-557) but not its *abandon tail*. The two
      recovery arms share one single-flight latch and the exit arm still
      drops its notification on contention: reconnect() does
      `if (!reconnectInFlight.compareAndSet(false, true)) return;`
      (:439-441). The campaign's own comment claims the drop is covered
      ("the campaign's next paced attempt picks the respawned child
      up"), but the campaign returns outright whenever it observes a
      non-RUNNING subprocess (:591-597) and only releases the flag
      afterwards in its finally (:655-662). The decision to abandon and
      the release are not atomic with respect to an incoming restart
      notification, so both arms can drop the same event.
    repro: |
      (1) simplex-chat crashes; the WS terminal event latches the client
      dead and spawns the campaign, which takes `reconnectInFlight` and
      sleeps a rung. (2) The campaign wakes, reads sub.state() ==
      RESTARTING at :591-592 and commits to return. (3) In the window
      between that read and the finally at :655-662, the supervisor sets
      RUNNING and fires notifyRestartListener(); the spawned reconnect()
      CAS-fails at :439 and returns. (4) End state: subprocess RUNNING,
      webSocket latched dead, `reconnecting` false, `reconnectInFlight`
      false, no thread rebuilding — the MSG-1 shape this ticket exists
      to close. Every send returns TRANSIENT "recovery pending" although
      no recovery is pending, until an unrelated daemon crash.
    suggested_fix_class: other
  - date: 2026-07-23
    auditor: claude
    category: DOS
    severity: medium
    promise: |
      security.md §Trust boundaries item 6 — "The health endpoints are
      unauthenticated in v1 and disclose operational topology: which
      messaging adapters are enabled and up, and whether the DB is
      reachable." deployment.md §Health and observability — "the
      readiness payload names each enabled adapter with its up/down
      state ... The per-adapter names stay in the payload because they
      are the operator's degraded-vs-healthy signal". The diff's own
      spec amendment (docs/spec/messaging.md, diff.patch lines 26-29)
      claims: "Where the latch exists the outage stays
      operator-visible for as long as it lasts (`connected()` /
      `adapter.connection.status` report the dead transport — no false
      green)".
    gap: |
      The new latch is honest on two surfaces and silently wrong on the
      one the spec names as the operator signal. The readiness payload
      is computed in
      infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java:84-85
      — `boolean terminallyFailed = adapter != null &&
      adapter.supervisorTerminallyFailed(); boolean up = entry.getValue()
      && !terminallyFailed;` — i.e. "started at boot AND supervisor not
      terminally FAILED". It never consults the transport truth the diff
      just introduced. `SimpleXAdapter.supervisorTerminallyFailed()`
      (infochat-messaging-adapter/.../simplex/SimpleXAdapter.java:723-726)
      returns false whenever the subprocess is RUNNING, which is exactly
      the state this diff exists to handle ("the simplex-chat process is
      still alive but its WebSocket died", SimpleXAdapter.java:508-511).
      So for the entire peer-close outage — and for the unbounded case
      where every rebuild attempt fails and the campaign paces forever
      at the 60 s steady rung (SimpleXAdapter.java:51-53, 209-298) —
      `/q/health/ready` reports `simplex: true` and overall UP while
      `connected()` (SimpleXAdapter.java:748-751) and the Micrometer
      gauge report 0. No admin notification covers the gap either:
      `adminNotifier` fires only on the supervisor's FAILED transition
      (SimpleXSubprocess.java:438-446), which never happens here, and
      the recovery campaign notifies nobody on repeated failure. The
      readiness class's own javadoc states the intent this misses:
      "otherwise a deployment could read 'ready' with a permanently
      dead adapter" (AdapterReadinessCheck.java:26-29).
    repro: |
      1. Deployment runs SimpleX as its only adapter; the readiness
      port is probed by systemd/k8s/an uptime monitor per deployment.md.
      2. The simplex-chat daemon closes the bot WebSocket while the
      process stays up (server-side idle close, a daemon-internal
      connection error, or any condition an adversary can provoke by
      keeping the daemon busy).
      3. `Listener.onClose` latches the client dead
      (SimpleXWebSocketClient.java:513-514, 547-555). The Provider is
      now deaf: no inbound is delivered, every send throws TRANSIENT
      (SimpleXAdapter.java:1185-1188).
      4. The prober GETs the readiness endpoint: status UP,
      `simplex: true`, because the subprocess is RUNNING. Nothing
      restarts the service, nothing pages the operator, and if the
      daemon's WS server never comes back the campaign retries silently
      every 60 s forever.
      5. Result: an attacker (or a plain daemon bug) obtains a total,
      indefinite outage of the deployment's only user-facing surface —
      including the ban-reply path, `/invite` registration and every
      throttled admin notification — that the spec-designated
      degraded-vs-healthy signal reports as healthy.
    suggested_fix_class: other
  - date: 2026-07-23
    auditor: claude
    category: DOS
    severity: medium
    promise: |
      The diff's own spec amendment (docs/spec/messaging.md, diff.patch
      lines 9-16): "**A peer-initiated connection close is a
      transport-death event.** When the peer closes (or a transport
      error kills) the connection an adapter depends on — the SimpleX
      bot WebSocket, the Signal JSON-RPC channel — the adapter MUST
      latch that connection as dead and drive recovery of the
      transport". Also security.md §Trust boundaries item 6 (health
      discloses "which messaging adapters are enabled and up") and
      §Authorization model step 3.5, which relies on a delivered
      "throttled admin notification (one per group creation, not per
      subsequent @mention)".
    gap: |
      The same bullet immediately withdraws the MUST for one of the two
      v1 production adapters (diff.patch lines 20-26): "v1 implements
      the latch itself for the SimpleX bot WebSocket only. Signal
      detects a dead channel solely through those consecutive response
      timeouts, so a JSON-RPC channel that dies while signal-cli keeps
      running is not latched: `connected()` reports it up until an
      outbound send times out, and with no outbound traffic on that
      adapter the escalation never fires at all." The code confirms it:
      `SignalAdapter.connected()`
      (infochat-messaging-adapter/.../signal/SignalAdapter.java:404-409)
      returns true whenever the supervised process is RUNNING and
      `client.isConnected()` — a flag whose javadoc says it "only flips
      on an explicit disconnect/reconnect" — and the only death detector
      is `consecutiveTimeouts` incremented from the outbound send path
      (SignalJsonRpcClient.java:210, 718-723). No inbound-side liveness
      check, no notifier, no recovery campaign. The readiness payload
      (AdapterReadinessCheck.java:84-85) folds only
      `SignalAdapter.supervisorTerminallyFailed()`
      (SignalAdapter.java:379-382), which is false while signal-cli
      runs, so Signal's dead channel is false-green on BOTH surfaces
      the SimpleX arm made honest.
    repro: |
      1. Signal-only (or Signal-plus-idle-SimpleX) deployment;
      signal-cli runs and its JSON-RPC socket dies (peer close,
      proxy/socket teardown, daemon-internal error) without the process
      exiting.
      2. Inbound stops arriving. No user message reaches the Provider,
      so no reply is ever attempted, so no `sendCommand` runs, so
      `consecutiveTimeouts` never increments and the hung-restart hook
      never fires. The channel is dead forever.
      3. `connected()` = true, `adapter.connection.status` = 1,
      readiness = UP, no admin notification.
      4. Any once-only, non-retried alert that would have gone out on
      that adapter is lost with it — notably the step-3.5 group-approval
      notification, which is "throttled: fires only on row creation" and
      is never re-sent, so a group can sit pending with no admin ever
      told.
      5. An adversary who can provoke a daemon-side channel error
      therefore gets an indefinite, silent, monitoring-invisible DoS of
      a v1 production adapter, with the spec MUST for exactly this case
      written in the same diff.
    suggested_fix_class: other
  - date: 2026-07-23
    auditor: claude
    category: DOS
    severity: low
    promise: |
      The diff's spec amendment (docs/spec/messaging.md, diff.patch
      lines 28-31): "sends attempted before recovery completes classify
      transient — unless the supervising component has terminally
      failed, which classifies permanent per the default above." Paired
      with the pre-existing rule the diff builds on
      (messaging.md:338-340 "An adapter cannot silently drop messages")
      and messaging.md:389 ("The retry queue does not re-attempt
      permanent" failures).
    gap: |
      Two paths classify a *recoverable* peer close as PERMANENT, so
      the Provider drops the message instead of riding the outage out.
      (a) In-flight sends: `Listener.onClose` drains every pending ack
      future with `FailureCategory.PERMANENT`
      (infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:513-514
      → latchTransportDeath → failAllPending:666-672), and
      `sendCommand` rethrows that category verbatim
      (SimpleXWebSocketClient.java:332-339). The `onError` arm drains
      TRANSIENT; the `onClose` arm — the very event the diff redefines
      as "transport-death ... drive recovery" — drains PERMANENT.
      (b) TOCTOU between the new guard and the client:
      `requireConnected()` reads `ws.isClosed()` and returns the client
      (SimpleXAdapter.java:1185-1189); the latch can flip in the window
      before `sendCommand` re-reads `closed` and throws PERMANENT
      (SimpleXWebSocketClient.java:311-313). The adapter's own test
      helper concedes the window ("A send issued before the
      transport-death latch is visible ... is categorised by the client
      rather than by requireConnected", SimpleXReconnectTest new helper
      awaitReconnectingClassification).
    repro: |
      1. A user @mentions the bot in a group the bot has never seen;
      step 3.5 creates the `groups` row and the Provider sends the
      once-only throttled admin notification with the
      `/approve-group <uuid>` string.
      2. The simplex-chat daemon closes the bot WebSocket while that
      send is awaiting its ack (or in the microsecond window after
      requireConnected passed).
      3. The caller receives PERMANENT, so per messaging.md:389 the
      retry queue does not re-attempt it — even though the recovery
      campaign rebuilds the transport ~1 s later and the very next send
      succeeds.
      4. security.md step 3.5 guarantees no re-notification ("No admin
      re-notification (throttled: fires only on row creation)"), so the
      group stays pending with no admin ever informed, and the
      activating user only ever sees "pending admin approval". The same
      window silently discards fixed ban replies, invite-required
      replies and chat answers that the spec says are
      transient-retryable.
    suggested_fix_class: other
  - date: 2026-07-23
    auditor: claude
    category: DOS
    severity: low
    promise: |
      security.md §Trust boundaries item 2 and §Authorization model —
      every inbound message is expected to traverse identity
      resolution, the transport rate cap, the invite gate, the ban
      check and the audit step; deployment.md §Health and observability
      makes the readiness payload "the operator's degraded-vs-healthy
      signal", and the readiness class exposes `<name>.dropped-inbound`
      precisely "so a silently overflowing queue is visible on the
      readiness payload without log scraping"
      (AdapterReadinessCheck.java:31-41).
    gap: |
      `latchTransportDeath` now calls `dispatchExecutor.shutdownNow()`
      on every peer-initiated death
      (infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:547-555).
      Pre-diff a peer close only drained pending futures and the
      dispatcher kept draining its queue; post-diff the death (i)
      discards every one of up to `INBOUND_QUEUE_CAPACITY = 1_000`
      (SimpleXWebSocketClient.java:83) already-accepted inbound
      messages and (ii) *interrupts* the message currently being
      processed on the dispatch thread mid-pipeline. Unlike the
      queue-overflow drop path — which increments
      `droppedInboundCount`, emits
      `metrics.inboundDropped(..., QUEUE_FULL)` and logs a WARN
      (SimpleXWebSocketClient.java:635-640) — this bulk drop increments
      no counter, emits no metric and logs nothing, so it is invisible
      on the `dropped-inbound` readiness field that exists for exactly
      this purpose and invisible in the logs.
    repro: |
      1. Traffic is queued on the single dispatch thread (each inbound
      does identity resolution plus DB work, per
      SimpleXWebSocketClient.java:73-83).
      2. The peer closes the WebSocket. Up to 1 000 queued user
      commands — including any queued `/invite` consume attempt, admin
      command or ban check — are dropped, and the in-flight one is
      interrupted mid-run.
      3. Senders receive nothing at all (no reply, no error), and the
      operator sees no counter, metric or log line distinguishing this
      from "nobody sent anything". An adversary able to provoke
      repeated peer closes can therefore erase other users' in-flight
      traffic with zero forensic trace.
    suggested_fix_class: other
redteam_audits:
  - date: 2026-07-22
    round: 1
    mode: redteam-multi
    auditors: [claude, opencode, codex, kimi]
    verdict: FINDINGS
    base: ef4ee456
    head: working-tree
    evidence: docs/plan/m1/redteam-multi/M1-674-2026-07-22/
    findings_count: 3
    out_of_model_count: 4
    note: |
      Pre-review redteam gate at the /m1-tick run step 4 checkpoint,
      run in multi-auditor mode per user directive. Audited the
      uncommitted branch tip (zero-commit working-tree diff against the
      fork point). claude FINDINGS(1), kimi FINDINGS(2), codex CLEAN,
      opencode UNAVAILABLE (900 s timeout — 3-of-4 coverage). No cluster
      was corroborated by two auditors, so every finding was
      developer-verified against the code before disposition: all three
      CONFIRMED. Two are real recovery-wedge defects that reintroduce the
      MSG-1 end state this ticket exists to close (lost-wakeup handoff
      race; unchecked-throw campaign death), one is a spec-precision gap
      in the bullet this diff lands. Four out-of-model observations
      (loopback port squat during the outage, handshake-only ladder
      reset, daemon-flap availability lever, peer close-reason strings in
      drained exceptions) are advisory and not part of this remediation.
  - date: 2026-07-22
    round: 2
    mode: redteam-multi
    auditors: [claude, opencode, codex, kimi]
    verdict: FINDINGS
    base: ef4ee456
    head: working-tree
    evidence: docs/plan/m1/redteam-multi/M1-674-2026-07-22-r2/
    findings_count: 3
    out_of_model_count: 3
    note: |
      Re-audit of the round-1 remediation, per the rule that an in-branch
      fix invalidates the audit it answers. claude FINDINGS(3), codex
      CLEAN, kimi CLEAN, opencode UNAVAILABLE (900 s timeout — 3-of-4
      coverage); zero clusters corroborated by two auditors, so every
      finding was developer-verified against the code before disposition.
      All three CONFIRMED. Two are round-1 remediations that closed only
      half their window — the Signal over-promise (finding C) was
      qualified for the recovery *route* but not for the latch MUST or
      the no-false-green sentence, and the lost-wakeup fix (finding A)
      hardened the campaign's entry but not its abandon tail. The third
      is a branch-ordering defect in requireConnected that makes this
      diff's own terminal-failure PERMANENT arm unreachable. Three
      out-of-model observations (loopback WS-port hijack on rebuild,
      the Listener/onTransportDeath test-seam visibility widening, and
      stale calibration prose at messaging.md §"closed or not-yet-started
      WebSocket") are advisory.
      Audited diff is byte-identical to the current working tree
      (verified 2026-07-23 against `git diff ef4ee456 -- <the 5 code and
      spec paths>`), so the findings are live against the tree under
      escalation.
  - date: 2026-07-23
    round: 3
    mode: redteam-multi
    auditors: [claude, codex, kimi]
    verdict: FINDINGS
    base: ef4ee456
    head: working-tree
    evidence: docs/plan/m1/redteam-multi/M1-674-2026-07-23/
    findings_count: 4
    out_of_model_count: 2
    note: |
      Re-audit of the round-2 remediation, per the rule that an
      in-branch fix invalidates the audit it answers. opencode excluded
      per user directive (burned its 900 s timeout in rounds 1 and 2);
      all 3 invoked auditors returned. claude FINDINGS(4), codex CLEAN,
      kimi CLEAN — zero clusters corroborated, so every finding was
      developer-verified against the code and then adversarially
      falsified; all four survived. The two medium findings are
      pre-existing conditions outside files_scope: the readiness
      payload (AdapterReadinessCheck) never consults the transport, so
      /q/health/ready stays green through a peer-close outage, and
      Signal channel death is false-green on both surfaces with the
      timeout counter able to stick below its threshold forever. Of the
      two lows, the PERMANENT drain on peer-close (Listener.onClose)
      pre-exists on main but now contradicts the transient-
      classification sentence this diff adds, and the shutdownNow()
      bulk inbound drop with no counter/metric/log is the one finding
      genuinely introduced by this diff; both are in files_scope. Two
      out-of-model observations (loopback WS-port squat during the
      rebuild loop, Listener test-seam visibility widening) are
      advisory. Escalated 2026-07-23 via
      /m1-tick escalate M1-674 redteam-finding.
  - date: 2026-07-23
    round: 4
    mode: redteam-multi
    auditors: [claude, codex, kimi]
    verdict: CLEAN
    base: ef4ee456
    head: working-tree
    evidence: docs/plan/m1/redteam-multi/M1-674-2026-07-23-r2/
    out_of_model_count: 2
    note: |
      Re-audit of the round-3 remediation (escalation resolved by refine:
      acceptance items 11 and 12; the two out-of-scope medium findings
      were dispositioned to the user, not remediated here). claude CLEAN,
      codex CLEAN, kimi UNAVAILABLE (exit 1 — 2-of-3 coverage, treated
      as no data). Two out-of-model advisories from claude, both
      self-rated below findings grade: campaign death by JVM Error
      (outside the threat model; connected() stays honest-false) and the
      peer-controlled close-reason string in the drain exception message
      (pre-existing, loopback trust boundary 7, SafeLog strips it).
      Full-suite verify green against the audited tree:
      m1-tick-test-M1-674-r4.log.
clarity_check:
  date: 2026-07-22
  verdict: PASS
  warnings:
    - >-
      Self-check: acceptance item 2's no-process-exit clause forces the
      adapter-side rebuild route, which never touches
      SimpleXSubprocessTest.java while needing SimpleXReconnectTest.java
      (the adapter-level recovery/readiness harness); user-approved scope
      swap applied, plus a backoff-pacing acceptance item per design
      §6.4.6 — an unpaced rebuild would contradict the documented
      reconnect cadence and hot-loop against a repeat-closing daemon.
      User declined follow-up tickets.
  blockers: []
escalation_reason:
---

# M1-674: Recover SimpleX adapter from peer-closed WebSocket

## Context

The 2026-07-22 full-repo security audit (`.scratch/kimi-audit.md`, finding
MSG-1) verified that if simplex-chat closes (or fatally breaks) the bot
WebSocket while its process stays alive, no component notices:
`SimpleXWebSocketClient.java:484-498` (`Listener.onClose`/`onError`) only
drains pending futures; the client's `closed` flag (`:258-283`) is set only
by the local `close()`; so `SimpleXAdapter.connected()` (`:515-519`) keeps
returning true and `adapter.connection.status` readiness stays green while
every send fails and inbound delivery is dead. The supervisor
(`SimpleXSubprocess.java:379-408`) restarts only on process exit, so
nothing ever rebuilds the transport or notifies the admin — a permanently
deaf adapter with a false-green readiness signal, the "permanently deaf
adapter with no restart trigger" class the module itself documents as
severe elsewhere (M1-358). The module-6 premise check confirmed no
Provider-side watchdog masks it (the Provider never calls
`adapter.connected()` anywhere; readiness folds only the startup snapshot
plus `supervisorTerminallyFailed()`). LOW severity — no attacker trigger
(loopback-only channel, co-located peer, trust boundary #7) — reported as
an availability-hardening gap. The realistic trigger is a server-side idle
timeout on the bot channel: the client runs no ping/keepalive anywhere in
the module (grepped).

## Acceptance

See the frontmatter. A peer close latches the client closed (honest
`connected()`), drives an actual recovery (rebuild or supervisor restart),
and is visible on the readiness surface while unrecovered; the spec
records the transport-death semantics.

## Out-of-scope

The Signal adapter, a keepalive layer, and the working process-exit
restart path. See the frontmatter.

## Notes

- The minimal honest-signal half (latch `closed = true` in
  `onClose`/`onError`) is one line; the design decision is the recovery
  route. Adapter-side WS rebuild against the live subprocess is cheaper
  than a full process restart; the supervisor route reuses the proven
  `restartHung` shape. Either closes the finding; pick per code fit and
  record the choice in the commit.
- Finding detail, falsification history, and the Signal comparison: the
  audit report (`kimi-audit.md` under `.scratch/`) §MSG-1 (module 4),
  plus the module-6 MSG-1-watchdog thread resolution.
- Refined 2026-07-22 at start (user decision via the pre-flight
  self-check): recovery route pinned to the adapter-side WS rebuild
  (item 2's no-process-exit clause rules the supervisor route out);
  files_scope swaps SimpleXSubprocessTest.java for
  SimpleXReconnectTest.java; rebuild pacing follows the documented
  §6.4.6 network-failure ladder (new acceptance item) — recovery with
  no pacing would contradict the design doc and spin hot against a
  daemon that re-closes every fresh WebSocket. SimpleXSubprocess.java
  stays in files_scope (the recovery arm reads its state() so the
  process-exit arm keeps ownership of process-death recoveries) but is
  expected unmodified. No follow-up tickets (user decision).
- Refined 2026-07-22 after the redteam-multi gate (escalation reason
  `redteam-finding`, evidence
  `docs/plan/m1/redteam-multi/M1-674-2026-07-22/`). Three findings, all
  developer-verified against the code before dispositioning; two are real
  defects that reintroduce the MSG-1 end state this ticket exists to close,
  and both fixes fall inside the existing files_scope, so the escalation
  resolves as an in-branch refine rather than a decompose or a new ticket:
  the lost-wakeup handoff race and the unchecked-throw campaign death
  become acceptance items 7 and 8, and the Signal over-promise folds into
  acceptance item 6 as a qualifying clause on the spec bullet (no Signal
  code — the Signal adapter stays out of scope). The four out-of-model
  observations (loopback port squat during the outage, handshake-only
  ladder reset, daemon-flap availability lever, peer close-reason strings
  in drained exceptions) are advisory and explicitly not part of this
  remediation. The remediation invalidates the audit that prompted it, so
  the new diff is re-audited before review.
- Refined 2026-07-23 after the round-2 redteam-multi re-audit (escalation
  reason `redteam-finding`, evidence
  `docs/plan/m1/redteam-multi/M1-674-2026-07-22-r2/`). Three findings, all
  developer-verified against the code before dispositioning, all inside the
  existing `files_scope`, so this resolves as a second in-branch refine.
  Two are round-1 remediations that closed only half their window: the
  Signal over-promise was qualified for the recovery *route* but not for
  the latch MUST or the no-false-green sentence (folds into acceptance item
  6 — spec text only, still no Signal code), and the lost-wakeup fix
  hardened the campaign's entry but not its abandon tail (acceptance item
  10). The third is a branch-ordering defect that makes this diff's own
  terminal-failure PERMANENT arm unreachable (acceptance item 9). Item 10's
  route was chosen over re-checking-after-release because it is the smaller
  change and it makes the exit arm's dropped CAS provably harmless rather
  than merely narrowing its window. The three out-of-model observations
  (loopback WS-port hijack on rebuild, the Listener/onTransportDeath
  test-seam visibility widening, stale calibration prose at messaging.md
  §"closed or not-yet-started WebSocket") are advisory and not part of this
  remediation.
- Refined 2026-07-23 after the round-3 redteam-multi re-audit (escalation
  reason `redteam-finding`, evidence
  `docs/plan/m1/redteam-multi/M1-674-2026-07-23/`). Four findings, all
  claude-only (codex and kimi CLEAN), all developer-verified against the
  code and then adversarially falsified before dispositioning; all four
  survived. Two resolve in-branch: the `shutdownNow()` bulk inbound drop
  with no counter/metric/log — the one finding this diff introduced —
  becomes acceptance item 11 (counted into the readiness-visible
  dropped-inbound counter plus one WARN; no new Micrometer reason label
  because `AdapterMetrics.DropReason` is outside files_scope), and the
  in-flight-drain PERMANENT contradiction becomes acceptance item 12,
  resolved spec-side by narrowing the transient-classification sentence
  rather than flipping the closed-before-ack category, which is a
  deliberate cross-adapter convention; the stale D63 calibration prose
  (an out-of-model advisory from round 2) folds into the same spec edit.
  The two medium findings — the readiness payload never consulting the
  transport (`AdapterReadinessCheck`, infochat-provider) and Signal
  channel death being false-green on both surfaces — are pre-existing
  conditions outside this ticket's files_scope and are NOT carried by
  this refine; their lifecycle disposition (follow-up tickets vs.
  accepted gap) stays with the user, against the standing
  no-follow-up-tickets decision that predates these findings. The two
  out-of-model observations (loopback WS-port squat during the rebuild
  loop, Listener test-seam visibility widening) remain advisory.
- Remediation test coverage, stated plainly: item 8's ladder validation is
  pinned by `emptyReconnectBackoffLadderIsRejectedAtConstruction`. Item 7's
  handoff race and item 8's unchecked-throw arm are closed by construction
  and are NOT pinned by a test. Both need a thread to act inside the window
  between a holder's last liveness check and its release of the
  single-flight flag — microseconds wide, and `SimpleXAdapter` is `final`
  with no seam that can widen it (the ladder constructor is the only
  injection point, and it paces attempts, not the tail). A test would have
  to win that race, i.e. be flaky, which the full-suite flake discipline
  rules out. The argument for each fix is at its site instead: the campaign
  javadoc for the waiting entry, the catch-arm comment for the unchecked
  throw.
