# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-681/docs/plan/m1/redteam-multi/M1-681-2026-07-23-r2`
Auditors: claude, codex, kimi

## Summary

- 3 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 3 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'claude': 3}.

## Per-auditor verdicts

- **claude**: FINDINGS (3 finding(s))
- **codex**: CLEAN (0 finding(s))
- **kimi**: CLEAN (0 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | codex | kimi | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | DOS | `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:875` | medium | -- | -- | medium | claude-only -- needs review |
| 2 | DOS | `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:1009-1012` | low | -- | -- | low | claude-only -- needs review |
| 3 | DOS | `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:875-878` | low | -- | -- | low | claude-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:875`

**claude** (severity: medium, fix-class: other)

- PROMISE: security.md §Trust boundaries item 6 — "Health/management HTTP
              surface → network. The health endpoints are unauthenticated in
              v1 and disclose operational topology: which messaging adapters
              are enabled and up, and whether the DB is reachable." The
              readiness surface this diff wires MessagingAdapter.connected()
              into is the operator...
- GAP (first 400 chars): The new transport-death latch guards the shared-subprocess SIGKILL
         with a CONNECTION-generation check, but the daemon generation
         advances strictly EARLIER than the connection generation, so the
         guard does not cover the case its own comment claims ("a reader
         whose connection has already been retired must not fire it — that
         would kill the daemon out from ...


### Cluster 2: DOS @ `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:1009-1012`

**claude** (severity: low, fix-class: other)

- PROMISE: security.md §Trust boundaries item 6 — "The health endpoints are
              unauthenticated in v1 and disclose operational topology: which
              messaging adapters are enabled and up, and whether the DB is
              reachable."
- GAP (first 400 chars): The diff makes readiness honest for exactly one death shape — the
         reader exits. It leaves the complementary shape reporting a false
         green: a signal-cli child that is alive and holds the socket open
         but has stopped delivering inbound (wedged receive loop) never
         produces a reader exit, so nothing latches.
         - infochat-messaging-adapter/src/main/java/app/zca...


### Cluster 3: DOS @ `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:875-878`

**claude** (severity: low, fix-class: rate-limit)

- PROMISE: security.md §Trust boundaries item 6 — "...disclose operational
              topology: which messaging adapters are enabled and up..."
              (the deployment's availability signal), read together with the
              threat model's framing that "The Provider is exposed to the
              internet through every enabled messaging adapter... Adversaries
              can send arbitrary te...
- GAP (first 400 chars): The latch escalates EVERY peer-initiated reader exit to a SIGKILL of
         the shared daemon with no dampening: no minimum interval between
         latch-driven restarts, no per-connection or per-window cap, and no
         cheaper in-place reconnect tier (SimpleX, by contrast, rebuilds its
         WebSocket in place against the still-running daemon — messaging.md
         §Failure handling, ...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **claude-only**: DOS @ `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:875` (severity medium). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:1009-1012` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java:875-878` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.

