---
id: M1-402
title: "messaging: clear reconnecting on SimpleX derivation failure"
status: pending
created: 2026-06-19
last_updated: 2026-06-19
blocked_by: []
files_budget: 3
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXAdapter.java
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The MessagingException catch arm — a genuine transport failure where the subprocess is still sick and awaiting the next supervised restart IS the correct recovery; unchanged.
  - The close-race branch (subprocess == null after rebuild) that already clears reconnecting and tears down the fresh client; unchanged.
  - The supervisor restart lifecycle and SimpleXSubprocess — only the adapter's IllegalStateException handling in reconnect() changes; no new restart trigger is added.
acceptance:
  - "In SimpleXAdapter.reconnect(), the IllegalStateException catch arm (post-restart identity re-derivation rejected a non-well-formed address, AFTER waitForWebSocketReady + rebuildWebSocket already succeeded so a live client exists) sets reconnecting = false, so a healthy live subprocess is no longer left wedged with every send classified TRANSIENT-forever and no restart coming to clear the flag."
  - "A new test in SimpleXReconnectTest drives a reconnect whose post-restart identity-derivation reply is a non-well-formed address and asserts that after reconnect() returns, the adapter is not stuck classifying sends TRANSIENT due to a leftover reconnecting flag (the rebuilt transport serves)."
  - "The existing successful-re-derivation reconnect test remains green (a clean reconnect still ends with reconnecting cleared and the adapter serving)."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex/SimpleXReconnectTest.java (malformed post-restart address case)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-402: clear reconnecting on SimpleX derivation failure

## Context

Deep-review full (2026-06-19) messaging-adapter finding **F1**
(MAINTAINABILITY-RULES-DRIFT). Verified at source 2026-06-19:

`SimpleXAdapter.reconnect()`
(`infochat-messaging-adapter/.../impl/simplex/SimpleXAdapter.java:387-450`) has two
catch arms documented as "the same posture", but their recovery contracts differ.
The `MessagingException` arm (433-438) is a transport failure — the subprocess is
still sick, so the supervisor fires another `onRestart` and the next `reconnect()`
clears `reconnecting`; "await the next supervised restart" is real. The
`IllegalStateException` arm (439-446) is reached only AFTER
`waitForWebSocketReady` and `rebuildWebSocket()` have already succeeded — a fresh,
connected client is live in `this.webSocket`; the only failure is
`deriveAndAdoptIdentity` rejecting a malformed address from a *healthy, running*
subprocess. A healthy subprocess never fires another `onRestart`, so the promised
"next supervised restart" never comes, and `reconnecting` stays stuck `true`:
`requireConnected()` (≈831) throws TRANSIENT on every send while a live socket sits
idle, and `supervisorTerminallyFailed()` is false so the readiness probe still
reports the adapter ready.

The same class already recognizes this exact bug shape: the close-race branch
(417-428) clears `reconnecting` with the comment "leaving it set here is what left
a closed adapter classifying post-close sends TRANSIENT forever." The
`IllegalStateException` arm needs the same treatment. The existing
`SimpleXReconnectTest` exercises only the successful re-derivation, so the trap is
untested.

## Acceptance

See frontmatter. The fix clears `reconnecting` in the `IllegalStateException` arm:
the live rebuilt transport carries DM and previously-anchored group traffic
immediately, and the prior group anchor stays in place (only fresh group-mention
recognition is degraded until a genuine restart re-derives the anchor) — strictly
better than a total send outage.

## Out-of-scope

See frontmatter. Only the `IllegalStateException` arm changes; the transport-failure
arm's "await next restart" recovery is correct and stays.

## Notes

- Adjacent code: the close-race branch (SimpleXAdapter.java:417-428) is the existing
  in-class precedent for "clear reconnecting wherever a usable/terminal state exists
  so the flag never outlives the reconnect."
- Alternatives considered: (B) bounded retry of the derivation inside reconnect() —
  rejected, adds a loop to a deliberately single-shot-per-restart method for a path
  that only fires on wire-contract drift; (C) latch PERMANENT via closedForGood —
  rejected, throws away a live transport over a non-fatal group-anchor problem.
