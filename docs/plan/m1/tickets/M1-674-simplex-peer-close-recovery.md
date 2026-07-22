---
id: M1-674
title: "Recover SimpleX adapter from peer-closed WebSocket"
status: pending
created: 2026-07-22
last_updated: 2026-07-22
blocked_by: []
files_budget: 7
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
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
    A new test proves the failure window is operator-visible while it
    lasts: the adapter.connection.status gauge / readiness reflects the
    dead transport between the peer close and the completed recovery
    (no false-green interval).
  - mvn -pl infochat-messaging-adapter verify is green
  - >-
    docs/spec/messaging.md §Failure handling records that a peer-initiated
    WebSocket close is a transport-death event that drives recovery, not
    just pending-future drainage.
test_plan:
  adds: []
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClientTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
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
