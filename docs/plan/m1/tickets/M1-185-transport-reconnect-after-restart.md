---
id: M1-185
title: "Reconnect transport after supervised subprocess restart"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 9
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
complexity: high
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - the inbound-dispatch threading change (M1-177) and reader/codec hardening (M1-184) — same files; this ticket adds the restart→reconnect path only
  - backoff-curve values and jitter shape (UNIFIED.md T27 reconciles the equal-vs-full-jitter and constants drift)
  - Postgres LISTEN/NOTIFY reconnect logic in the provider — different transport, already handled there
  - subprocess spawn/restart/backoff logic itself — the supervisors restart children correctly; what is missing is the callback that revives the transport client afterwards
acceptance:
  - "After the supervisor restarts the signal-cli subprocess, the Signal adapter's JSON-RPC transport reconnects and inbound + outbound traffic resume — a named test kills the fake subprocess, lets the supervisor restart it, and asserts a subsequent send succeeds and a pushed inbound frame is delivered (today c.connect() has exactly one call site in SignalAdapter.start, doRestart() only spawn()s, and the adapter is permanently dead after the first transport loss)"
  - "Same for SimpleX: after a simplex-chat subprocess restart the adapter rebuilds/reconnects its WebSocket client and traffic resumes — a named test asserts send + inbound delivery after a supervised restart, making SimpleXSubprocess's javadoc claim (\"the adapter rebuilds SimpleXWebSocketClient after the supervisor reports each restart\") true"
  - "A send attempted while the transport is down (between death and completed reconnect) fails TRANSIENT, not PERMANENT, so the provider-side retry machinery treats the outage as recoverable — a named test pins the failure category during the gap"
  - "Reconnect does not double-deliver or interleave with a half-dead prior connection: the old reader/listener is torn down before the new connection serves traffic — a named test asserts single delivery of an inbound frame pushed after reconnect"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-185: Reconnect transport after supervised subprocess restart

## Context

Both supervisors restart their child process but nothing reconnects the
transport client that died with it. `c.connect()` is called exactly once
(SignalAdapter.java:229, in `start()`); `SignalSubprocess.doRestart()` only
`spawn()`s — there are zero callbacks from supervisor to adapter (the only
hook is the adminNotifier pager). When the old socket dies the reader loop
exits on IOException and nothing redials: the adapter is permanently dead
after the first subprocess crash, while the supervisor happily keeps the
new child alive. SimpleX has the same shape, and SimpleXSubprocess's own
javadoc (SimpleXSubprocess.java:29-30) promises a rebuild mechanism that
does not exist ("the adapter rebuilds SimpleXWebSocketClient after the
supervisor reports each restart"). Unified finding M4 (high),
`deep-code-review/v2/UNIFIED.md` §2.

## Acceptance

See frontmatter. The contract: a supervised restart yields a working
adapter; the outage window fails TRANSIENT; no double-delivery across the
reconnect.

## Out-of-scope

See frontmatter. This ticket overlaps files with M1-177/M1-184 (Signal read
path) — not blocked, but prefer sequencing after M1-177 lands to avoid a
rebase on the dispatch change.

## Notes

- Source: `UNIFIED.md` §3 T9 under `deep-code-review/v2/` (kimi-folder msg
  F5).
- The restart→rebuild contract is already designed: design
  06-messaging §6.4.6 treats subprocess + connection as one supervised
  unit, and the SimpleXSubprocess javadoc describes the adapter-rebuilds
  shape. The missing piece is the supervisor→adapter restart notification
  and the adapter-side rebuild handler.
- On reconnect, SignalJsonRpcClient already wholesale-clears its handle and
  pending maps (:209-214) — reuse that path rather than duplicating
  teardown.
