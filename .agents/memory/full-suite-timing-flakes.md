---
name: full-suite-timing-flakes
description: "Three full-verify flake classes, all now fixed by construction: M1-615 (SimpleX connect race + cancel-IT race, 2026-07-12) and M1-655 (MultiAdapterProductionIT stand-in-daemon reconnect churn, 2026-07-18)."
metadata: 
  type: project
  modified: 2026-07-18T17:34:40.041Z
---

CLOSED by **M1-615** (merged to main @`73cede3a`, 2026-07-12; PUSHED 2026-07-13). The
two tests that used to flake on a full repo-root `mvn verify` (confirmed
2026-07-07, recurred 2026-07-12) are fixed by construction, test-code only:

- `SimpleXAdapterIdentityDerivationTest.routesGroupMentionByMemberIdAfterStart`
  now calls `fake.awaitClient(WAIT)` after `adapter.start()` (the connect race
  — "client has not connected yet" — is gone; same guard the restart sibling
  `groupMentionRoutingSurvivesRestart` always had).
- `StopToolQueryCancellationIT.stopAbortsInFlightToolQuery` had TWO race
  windows behind the one "statement timeout" signature: (1) pg_cancel_backend
  is DISCARDED by PostgreSQL against a still-idle backend (armed latch fired
  before ps.execute() reached the server) and (2) cancel arriving after the
  %test 5s backstop. Fixed by gating /stop on pg_stat_activity showing the
  slow query state=active on the registered pid, plus `SET LOCAL
  statement_timeout = 15000` inside the transaction armToolConnection opened
  (transaction-scoped; shared %test 5s untouched). Discriminating assertions
  in both tests are byte-unchanged.

**A THIRD flake class surfaced 2026-07-18 and is CLOSED by M1-655** (merged
`77eefd88`): `MultiAdapterProductionIT.simpleXCrashDoesNotAffectSignal`
("received no outbound JSON-RPC within 2000 ms" — the M1-540 error string,
recurring). Root cause was NOT a barrier gap: the `/bin/sleep` stand-in
daemon chokes on the adapter arg list and exits code 1 instantly, so the
subprocess supervisors' crash recovery runs DURING the test — each respawn
fires reconnect(), which disconnects the live JSON-RPC/WS connection
mid-probe; the probe write is absorbed without retry (setTyping is
best-effort). Fixed by a generated stay-alive stand-in script (bounded
`sleep 3600`). **Generalizable hazard:** any test that hands a supervised
subprocess a binary that dies at launch runs production crash recovery
concurrently with its own body; barriers cannot fix that — only a healthy
child can. **`SignalAdapterIdentityDerivationTest` carries the same dying
`/bin/sleep` stand-in but is NOT at risk — verified 2026-07-18, do not
"fix" it:** the mechanism needs an outbound write AND a timed transport
await, and that test has neither (it asserts via direct in-process
`groupHandler().handleReceive(...)` calls on a synchronously-populated
list; no `nextOutbound`/`awaitFrame`). Churn runs in the background and
touches nothing it asserts; `awaitEndpoint` probes the FAKE's port, which
`FakeSignalCli` binds independently of the child, so a dead child cannot
fail `start()` either. **Use that two-part test — outbound write + timed
await — to triage any other dying-stand-in site; the pattern alone is not
enough to flake.**

**How to apply:** a failure in any of these three on full verify is now a
REAL regression — investigate, do not re-run-and-shrug. The widen-window-
alone fix would NOT have worked for the cancel race: window (1) means a
lost cancel, which no timeout widening cures — remember the
idle-backend-discard semantics if a similar pg_cancel race appears
elsewhere. Related: [[clean-verify-monitoring]] (build+live-stack flakes are a
DIFFERENT cause and still apply).
