# Cross-examination report

Run directory: `/home/infochat/infochat/.claude/worktrees/M1-674/docs/plan/m1/redteam-multi/M1-674-2026-07-23`
Auditors: claude, codex, kimi

## Summary

- 4 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 4 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'claude': 4}.

## Per-auditor verdicts

- **claude**: FINDINGS (4 finding(s))
- **codex**: CLEAN (0 finding(s))
- **kimi**: CLEAN (0 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | codex | kimi | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | DOS | `infochat-messaging-adapter/.../signal/SignalAdapter.java:404-409` | medium | -- | -- | medium | claude-only -- needs review |
| 2 | DOS | `infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java:84-85` | medium | -- | -- | medium | claude-only -- needs review |
| 3 | DOS | `infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:513-514` | low | -- | -- | low | claude-only -- needs review |
| 4 | DOS | `infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:547-555` | low | -- | -- | low | claude-only -- needs review |

## Per-cluster detail

### Cluster 1: DOS @ `infochat-messaging-adapter/.../signal/SignalAdapter.java:404-409`

**claude** (severity: medium, fix-class: other)

- PROMISE: The diff's own spec amendment (docs/spec/messaging.md, diff.patch
              lines 9-16): "**A peer-initiated connection close is a
              transport-death event.** When the peer closes (or a transport
              error kills) the connection an adapter depends on — the SimpleX
              bot WebSocket, the Signal JSON-RPC channel — the adapter MUST
              latch that connection...
- GAP (first 400 chars): The same bullet immediately withdraws the MUST for one of the two v1
         production adapters (diff.patch lines 20-26): "v1 implements the latch
         itself for the SimpleX bot WebSocket only. Signal detects a dead
         channel solely through those consecutive response timeouts, so a
         JSON-RPC channel that dies while signal-cli keeps running is not
         latched: `connected(...


### Cluster 2: DOS @ `infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java:84-85`

**claude** (severity: medium, fix-class: other)

- PROMISE: security.md §Trust boundaries item 6 — "The health endpoints are
              unauthenticated in v1 and disclose operational topology: which
              messaging adapters are enabled and up, and whether the DB is
              reachable." deployment.md §Health and observability — "the
              readiness payload names each enabled adapter with its up/down
              state ... The per-ad...
- GAP (first 400 chars): The new latch is honest on two surfaces and silently wrong on the one
         the spec names as the operator signal. The readiness payload is
         computed in
         infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java:84-85
         — `boolean terminallyFailed = adapter != null &&
         adapter.supervisorTerminallyFailed(); boolean up = entry.getV...


### Cluster 3: DOS @ `infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:513-514`

**claude** (severity: low, fix-class: other)

- PROMISE: The diff's spec amendment (docs/spec/messaging.md, diff.patch
              lines 28-31): "sends attempted before recovery completes classify
              transient — unless the supervising component has terminally
              failed, which classifies permanent per the default above." Paired
              with the pre-existing rule the diff builds on (messaging.md:338-340
              "An adap...
- GAP (first 400 chars): Two paths classify a *recoverable* peer close as PERMANENT, so the
         Provider drops the message instead of riding the outage out.
         (a) In-flight sends: `Listener.onClose` drains every pending ack future
         with `FailureCategory.PERMANENT`
         (infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:513-514
         → latchTransportDeath → failAllPending:666-672...


### Cluster 4: DOS @ `infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:547-555`

**claude** (severity: low, fix-class: other)

- PROMISE: security.md §Trust boundaries item 2 and §Authorization model —
              every inbound message is expected to traverse identity resolution,
              the transport rate cap, the invite gate, the ban check and the
              audit step; deployment.md §Health and observability makes the
              readiness payload "the operator's degraded-vs-healthy signal", and
              the rea...
- GAP (first 400 chars): `latchTransportDeath` now calls `dispatchExecutor.shutdownNow()` on
         every peer-initiated death
         (infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:547-555).
         Pre-diff a peer close only drained pending futures and the dispatcher
         kept draining its queue; post-diff the death (i) discards every one of
         up to `INBOUND_QUEUE_CAPACITY = 1_000`
  ...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **claude-only**: DOS @ `infochat-messaging-adapter/.../signal/SignalAdapter.java:404-409` (severity medium). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `infochat-provider/src/main/java/app/zcat/infochat/provider/health/AdapterReadinessCheck.java:84-85` (severity medium). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:513-514` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.
- **claude-only**: DOS @ `infochat-messaging-adapter/.../simplex/SimpleXWebSocketClient.java:547-555` (severity low). See `verdict-claude.txt` for full PROMISE/GAP/REPRO.

