# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-674/docs/plan/m1/redteam-multi/M1-674-2026-07-22-r2`
Auditors: claude, opencode, codex, kimi

## Summary

- 3 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 3 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'claude': 3}.

## Per-auditor verdicts

- **claude**: FINDINGS (3 finding(s))
- **opencode**: UNAVAILABLE (0 finding(s))
- **codex**: CLEAN (0 finding(s))
- **kimi**: CLEAN (0 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | opencode | codex | kimi | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|---|
| 1 | DOS | `infochat-messaging-adapter/.../signal/SignalJsonRpcClient.java:783` | medium | -- | -- | -- | medium | claude-only -- needs review |
| 2 | DOS | `SimpleXAdapter.java:1142-1145` | low | -- | -- | -- | low | claude-only -- needs review |
| 3 | DOS | `SimpleXAdapter.java:545-557` | low | -- | -- | -- | low | claude-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `infochat-messaging-adapter/.../signal/SignalJsonRpcClient.java:783`

**claude** (severity: medium, fix-class: other)

- PROMISE: docs/spec/messaging.md:341-357 (added by this diff): "**A
              peer-initiated connection close is a transport-death event.**
              When the peer closes (or a transport error kills) the connection
              an adapter depends on — the SimpleX bot WebSocket, the Signal
              JSON-RPC channel — the adapter MUST latch that connection as
              dead and drive recover...
- GAP (first 400 chars): The commitment is written for BOTH v1 production adapters, but the
         diff ships SimpleX code only. On the Signal side nothing latches a
         dead JSON-RPC channel:
         - infochat-messaging-adapter/.../signal/SignalJsonRpcClient.java:783
           and :812-814 — the reader loop exits silently on EOF
           (`while ((c = r.read()) != -1)`) or on IOException (caught, logged
     ...


### Cluster 2: DOS @ `SimpleXAdapter.java:1142-1145`

**claude** (severity: low, fix-class: other)

- PROMISE: docs/spec/messaging.md:355-357 (added by this diff): "sends
              attempted before recovery completes classify transient — unless
              the supervising component has terminally failed, which
              classifies permanent per the default above." The diff's own
              inline rationale restates the stake: "Once the supervisor has
              terminally FAILED nothing wil...
- GAP (first 400 chars): The new terminal-failure arm is placed BEHIND the pre-existing
         `reconnecting` branch and is shadowed by it.
         SimpleXAdapter.java:1142-1145 returns TRANSIENT whenever
         `reconnecting` is set, before the new
         `ws.isClosed() && supervisorTerminallyFailed() -> PERMANENT` arm at
         :1159-1167 is ever evaluated. And `reconnecting` is deliberately
         PARKED tru...


### Cluster 3: DOS @ `SimpleXAdapter.java:545-557`

**claude** (severity: low, fix-class: other)

- PROMISE: docs/spec/messaging.md:341-348 (added by this diff): "the adapter
              MUST latch that connection as dead and **drive recovery of the
              transport**, paced per the design-tier reconnect cadence ...
              not merely drain the in-flight command futures on the dead
              connection."
- GAP (first 400 chars): The two recovery arms share one single-flight latch, and only ONE of
         them was hardened against losing its wakeup. The new campaign waits
         for the flag rather than returning (SimpleXAdapter.java:545-557, with
         the javadoc at :529-542 explaining precisely why a notification must
         never be dropped), but the process-exit arm still drops its
         notification on con...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **claude-only**: DOS @ `infochat-messaging-adapter/.../signal/SignalJsonRpcClient.java:783` (severity medium). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `SimpleXAdapter.java:1142-1145` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `SimpleXAdapter.java:545-557` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.

