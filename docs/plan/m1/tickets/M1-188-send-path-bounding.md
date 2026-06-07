---
id: M1-188
title: "Serialize SimpleX WS sends + bound Signal handle map"
status: done
created: 2026-06-07
last_updated: 2026-06-07
clarity_check:
  date: 2026-06-07
  verdict: PASS
  warnings: []
  blockers: []
blocked_by: []
files_budget: 6
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - SimpleXAdapter's MAX_TRACKED_HANDLES LRU — ADJUDICATED CORRECT (documented M1-148 trade-off); the audit disproved the claim that its eviction of open handles is the bug — do NOT "fix" the SimpleX side
  - maxInflightSends / maxSendsPerSecond enforcement and the bounded inbound queue — UNIFIED.md T28's rate-limit decision ticket (mediums batch, not yet filed)
  - inbound dispatch threading (M1-177) and reconnect (M1-185) — same files, different concern; prefer sequencing after M1-177
  - backoff/jitter constants (UNIFIED.md T27)
acceptance:
  - "Concurrent sends on one SimpleX WebSocket connection all transmit: a named test fires N concurrent sends through a fake WebSocket enforcing the JDK's one-outstanding-sendText rule and asserts all N frames are transmitted and acked (today the returned send future is deliberately discarded at :186/:238 — 'var unused = ws.sendText(...)' — so a rejected overlapping send is never observed and the frame is silently lost into a 30s ack-timeout stall)"
  - "A send that collides with an in-progress send is queued or retried, never failed PERMANENT (today the synchronous IllegalStateException path is misclassified PERMANENT 'closed concurrently') — a named test pins the no-PERMANENT-on-collision behavior"
  - "The Signal handle map is bounded: a named test performs more fire-once (never-finalized) sends than the cap and asserts openHandleCount() never exceeds it (today entries are removed only by finalizeHandle or wholesale clear on reconnect — fire-once replies, the common case, leak until disconnect)"
  - "An evicted Signal handle behaves exactly like an unknown handle (PERMANENT 'unknown handle' on update/finalize) — the same documented outcome SimpleX eviction and a Provider restart produce — a named test pins it"
  - "SimpleX handle-map behavior is unchanged (its existing LRU tests stay green unmodified)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/messaging.md §Message handles
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
      files: 7
      added: 611
      removed: 27
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-188: Serialize SimpleX WS sends + bound Signal handle map

## Context

Two send-path bounding defects (unified findings M7 + M6,
`deep-code-review/v2/UNIFIED.md` §2):

1. **SimpleX concurrent sendText collision (M7).** The JDK WebSocket allows
   one outstanding text send; SimpleXWebSocketClient deliberately discards
   the send future (`var unused = ws.sendText(envelopeJson, true)` at :186
   and :238). A second send racing the first gets an exceptionally-completed
   future nobody reads — the frame is never transmitted and the caller
   stalls into the 30s ack timeout (TRANSIENT); the synchronous
   IllegalStateException variant is misclassified PERMANENT (:207-217).
2. **Signal handle map leak (M6).** SignalJsonRpcClient registers a handle
   on every send (:226) and removes it only in finalizeHandle (:242) or on
   reconnect wholesale-clear (:214). Fire-once replies — the common case —
   are never finalized and accumulate for the life of the connection.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — the adjudication is binding: SimpleX's LRU
(MAX_TRACKED_HANDLES = 1024, documented M1-148 trade-off: "an evicted handle
behaves exactly like an unknown one … the same outcome a Provider restart
produces") is the correct documented shape that Signal must mirror; the
competing claim that SimpleX's eviction was the defect was disproven against
the code.

## Notes

- Source: `UNIFIED.md` §3 T12 under `deep-code-review/v2/` (kimi-folder msg
  F3 for the sendText collision; opus-48 msg F3 for the Signal leak;
  opus-47's inversion INVALID per the §2 adjudication).
- The SimpleX trade-off comment block (SimpleXAdapter.java:89-105) is the
  rationale text to mirror on the Signal side.
- This ticket shares SignalJsonRpcClient/SimpleXWebSocketClient with
  M1-177/M1-185 — not blocked, but prefer sequencing after M1-177 to avoid
  rebasing across the dispatch change.

## Suggested direction (unverified hypothesis)

Serialize ws.sendText per connection (a small send queue or a lock that
awaits the prior send's future before issuing the next), and add an
access-order LRU cap on the Signal handles map mirroring SimpleX's
MAX_TRACKED_HANDLES (proposed by the kimi-folder and opus-48 runs).

Per CLAUDE.md §Verify before recommending, treat this as a hypothesis:
falsify it against the code before adopting (what would make it wrong?
is there a simpler alternative meeting the same acceptance?). Adopting,
adapting, or replacing it is the implementer's call as long as every
acceptance item holds; a replacement that changes files_scope goes
through the escalate path.
