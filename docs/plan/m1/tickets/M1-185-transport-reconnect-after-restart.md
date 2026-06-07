---
id: M1-185
title: "Reconnect transport after supervised subprocess restart"
status: done
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 13
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocess.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocessTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
outline_file: target/m1-tick-outline-M1-185.md
out_of_scope:
  - the inbound-dispatch threading change (M1-177) and reader/codec hardening (M1-184) — same files; this ticket adds the restart→reconnect path only
  - backoff-curve values and jitter shape (UNIFIED.md T27 reconciles the equal-vs-full-jitter and constants drift)
  - Postgres LISTEN/NOTIFY reconnect logic in the provider — different transport, already handled there
  - subprocess spawn/restart/backoff logic itself — the supervisors restart children correctly; what is missing is the callback that revives the transport client afterwards
  - constructor-signature changes to the adapters, subprocess supervisors, or transport clients — the restart notification is an additive registration hook (see Notes), so test call sites outside files_scope (AdapterCapabilityContractTest, AdapterLifecycleContractTest, MultiAdapterProductionIT) compile unchanged
  - any modification to pre-existing test files other than the four authorized under §Authorized test changes (FakeSignalCli, SignalSubprocessTest, FakeSimpleXProcess, SimpleXSubprocessTest)
acceptance:
  - "After the supervisor restarts the signal-cli subprocess, the Signal adapter's JSON-RPC transport reconnects and inbound + outbound traffic resume — SignalReconnectTest kills the fake subprocess, lets the supervisor restart it, and asserts a subsequent send succeeds and a pushed inbound frame is delivered (today c.connect() has exactly one call site in SignalAdapter.start, doRestart() only spawn()s, and the adapter is permanently dead after the first transport loss)"
  - "Same for SimpleX: after a simplex-chat subprocess restart the adapter rebuilds/reconnects its WebSocket client and traffic resumes — SimpleXReconnectTest asserts send + inbound delivery after a supervised restart, making SimpleXSubprocess's javadoc claim (\"the adapter rebuilds SimpleXWebSocketClient after the supervisor reports each restart\") true"
  - "A send attempted while the transport is down (between death and completed reconnect) fails TRANSIENT, not PERMANENT, so the provider-side retry machinery treats the outage as recoverable — SignalReconnectTest and SimpleXReconnectTest each pin the failure category during the gap (the send-path classification code is per-transport, so both adapters are covered)"
  - "Reconnect does not double-deliver or interleave with a half-dead prior connection: the old reader/listener is torn down before the new connection serves traffic — SignalReconnectTest and SimpleXReconnectTest each assert single delivery of an inbound frame pushed after reconnect (teardown/rebuild ordering is per-transport, so both adapters are covered)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalReconnectTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
  modifies:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/FakeSignalCli.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal/SignalSubprocessTest.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/FakeSimpleXProcess.java
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXSubprocessTest.java
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Failure handling
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-07
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 13
      added: 1049
      removed: 31
revisions:
  - date: 2026-06-07
    reason: clarity-fail rework
    snapshot:
      status: escalated
      clarity_check:
        date: 2026-06-07
        verdict: FAIL
        warnings:
          - "ACCEPTANCE-RUNNABLE WARN: Items 1–4 say \"a named test\" rather than naming the test class; consider specifying test class names (e.g. SignalReconnectTest, SimpleXReconnectTest)"
        blockers:
          - "TEST-CHANGES-AUTHORIZED FAIL: test_plan.modifies lists two test directories (impl/signal, impl/simplex) without an \"Authorized test changes\" section in the ticket body naming each pre-existing test class to be modified and its new expected behavior — or remove test_plan.modifies if only new test files are added"
      escalation_reason: clarity-fail
      files_budget_at_snapshot: 9
      test_plan_at_snapshot: "adds/modifies listed the two test directories, not named files"
      acceptance_at_snapshot: "items 1–4 said 'a named test' without naming test classes; items 3–4 did not state per-transport coverage"
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
escalations:
  - date: 2026-06-07
    reason: clarity-fail
    reviewer_verdict_excerpt: |
      TEST-CHANGES-AUTHORIZED FAIL: The frontmatter `test_plan.modifies` section
      lists two test directories (impl/signal, impl/simplex) without a
      corresponding "Authorized test changes" section in the ticket body. Add a
      dedicated "Authorized test changes" section that names each pre-existing
      test class that will be modified and describes its new expected behavior —
      or, if no pre-existing test files are modified (only new test files are
      added to those directories), remove the `test_plan.modifies` entries and
      retain only `test_plan.adds`.
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

## Authorized test changes

The supervisor→adapter restart notification and the fake-process
kill/restart choreography touch four pre-existing test files. Each is
authorized here with its new expected behavior:

- **FakeSignalCli** (`impl/signal`). Gains the ability to be killed and to
  accept a fresh JSON-RPC connection after the supervisor respawns it, so
  SignalReconnectTest can exercise the death→restart→reconnect sequence.
  Existing scripted-frame/handshake behavior is unchanged.
- **FakeSimpleXProcess** (`impl/simplex`). Same shape: killable, and
  accepts a fresh WebSocket connection post-restart. Existing behavior
  unchanged.
- **SignalSubprocessTest** (`impl/signal`). `doRestart()` now fires the
  registered restart listener after a successful spawn. Existing
  spawn/backoff assertions are preserved; new or adjusted assertions cover
  listener invocation.
- **SimpleXSubprocessTest** (`impl/simplex`). Same: a supervised restart
  fires the registered listener; existing supervision assertions are
  preserved.

No other pre-existing test file may be modified.

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
- The restart notification must be an additive registration hook on the
  supervisor (e.g. an `onRestart(Runnable)`-style method), NOT a
  constructor-signature change: 17 test files construct the classes this
  ticket touches, three of them outside files_scope
  (AdapterCapabilityContractTest, AdapterLifecycleContractTest,
  MultiAdapterProductionIT) — a signature change would force out-of-scope
  edits and a budget breach.
