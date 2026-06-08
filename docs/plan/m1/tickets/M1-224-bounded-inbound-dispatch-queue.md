---
id: M1-224
title: "Bounded inbound dispatch queue (M1-205 DoS remediation)"
status: done
created: 2026-06-08
last_updated: 2026-06-08
blocked_by: []
files_budget: 8
files_scope:
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/simplex/SimpleXWebSocketClient.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/impl/signal/SignalJsonRpcClient.java
  - docs/design/06-messaging.md
  - docs/design/09-reference.md
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
  - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - M1-205's legs 1 and 3 (maxInflightSends/maxSendsPerSecond advisory amend; the RateCapBucket maxContactBuckets key-space cap) — those are M1-205's approved diff and stay there; this ticket touches neither RateCapBucket nor the §6.2/§6.3.6 capability text
  - per-user-fair scheduling — §6.3.7 explicitly defers it; a fair scheduler is its own work item, not this DoS bound
  - the outbound send path (send serialization, handle-map bounding) — M1-188's
  - InMemoryAdapter — it dispatches inbound synchronously with no executor queue, so it has no unbounded-growth surface to bound
acceptance:
  - "The inbound dispatch executor in BOTH transport clients is bounded: SimpleXWebSocketClient and SignalJsonRpcClient construct their single-thread dispatch executor with a bounded work queue (configurable cap, default 1000) rather than the JDK default unbounded LinkedBlockingQueue — so a hostile inbound flood cannot grow the dispatch backlog without bound (redteam M1-205 DOS finding, docs/plan/m1/redteam/M1-205-2026-06-08.md)"
  - "On overflow the adapter applies a defined drop policy (drop the inbound that cannot be enqueued) and increments a drop counter / WARN log with the redacted sender contact id — named tests assert the queue depth is bounded under a synthetic flood and that overflow drops are counted rather than growing memory"
  - "design 06-messaging §6.3.7 is rewritten to describe the SHIPPED bound (bounded dispatch queue + drop policy + drop metric), replacing M1-205's interim text that asserted the unbounded queue was bounded by the downstream rate cap; the §6.3.7 text and the code agree"
  - "error code E4007 in docs/design/09-reference.md is reconciled with the shipped behavior (re-activated to describe the real overflow drop, or kept RESERVED if the drop emits no user-facing reply — whichever matches what ships)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/simplex
    - infochat-messaging-adapter/src/test/java/app/zcat/infochat/messaging/impl/signal
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/messaging.md §Failure handling
decision_refs:
  - D46
reviews:
  - round: 1
    date: 2026-06-08
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 8
      added: 463
      removed: 32
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-08
    verdict: CLEAN
    base: HEAD (fork point of m1/M1-224-bounded-inbound-dispatch-queue with main)
    head: working tree (uncommitted impl, in-review round-1 APPROVE)
    verdict_file: docs/plan/m1/redteam/M1-224-2026-06-08.md
    out_of_model_count: 2
    note: |
      Adversarial audit of the bounded-inbound-dispatch-queue DoS remediation
      against docs/spec/security.md. CLEAN: the diff ADDS a memory bound
      (bounded LinkedBlockingQueue + ThreadPoolExecutor AbortPolicy →
      drop-newest + counter + redacted WARN) and weakens no existing
      commitment. Overflow WARN logs the sender as a SHA-256 prefix (D37:
      no raw contact id, no user prose), parameterized SLF4J (no log
      injection), no Throwable interpolation. No auth/permission/ban/audit
      surfaces touched. Two OUT-OF-MODEL advisories (not findings):
      (1) log-volume amplification — each dropped delivery emits one WARN, so
      sustained flood drives unbounded WARN log output; every throttling
      promise in security.md governs admin notifications / audit rows, not
      adapter WARN logs, and the drop fires downstream of the upstream rate
      caps; operator may extend the model to coalesce overflow WARNs per
      sender/window (the droppedInboundCount counter already supports a
      periodic-summary pattern). (2) stale lifecycle comment in
      SimpleXWebSocketClient (says "created lazily" but executor is now an
      eager final field) — no behavioral/security impact. Both are advisory;
      neither blocks APPROVE.
clarity_check:
  date: 2026-06-08
  verdict: WARN
  warnings:
    - "Acceptance item 2 self-flagged by author as needing tightening: whether a synchronous throttle reply fires on overflow is unspecified. Implementer decides leaner shape (drop + counter + WARN log) vs full §6.3.7 shape (+ throttle reply); E4007 (item 4) reconciles to match. Ticket grants this latitude explicitly."
    - "Notes say run scripts/lint-ticket.py before start — ran, PASS (0 blockers, 0 warnings)."
  blockers: []
---

# M1-224: Bounded inbound dispatch queue (M1-205 DoS remediation)

## Context

`/redteam M1-205 --in-progress` returned a high-severity DOS finding
(`docs/plan/m1/redteam/M1-205-2026-06-08.md`). M1-205's leg-2 decision
**amended** design §6.3.7 to retire the long-promised bounded inbound
queue, on the justification that "the dispatch backlog is bounded by
(rate-cap survivors × in-flight handler time)." That justification is
false: the step-1.5 rate cap (`RateCapBucket.tryAcquire`, called from
`InboundRouter.route()`) runs **inside** the task dispatched onto the
adapter's single-thread `dispatchExecutor` — i.e. strictly downstream of
the executor's queue. The executor uses the JDK default **unbounded**
`LinkedBlockingQueue` (`SimpleXWebSocketClient.java:105`/`:430`;
`SignalJsonRpcClient.java:226`), so a sustained inbound flood grows that
queue without bound and OOM-kills the only user-facing service. The rate
cap bounds *work per dequeued item*, never the queue's memory.

This ticket implements the bound the threat model's DOS category requires
("resource exhaustion in the messaging adapter"). M1-205 is **deferred on
this ticket**; once this lands, M1-205 reopens and its interim §6.3.7 text
is reconciled against the shipped bound.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter. In particular, this ticket does NOT touch `RateCapBucket`
or M1-205's capability-flag / key-space work (legs 1 and 3) — those are
M1-205's approved diff. It bounds the *transport dispatch queue*, which is
a different layer (the executor between the read loop and `onMessage`).

## Notes

- Source: redteam finding on M1-205 (high DOS). Full PROMISE / GAP / REPRO
  in `docs/plan/m1/redteam/M1-205-2026-06-08.md`.
- The original §6.3.7 spec'd a bounded queue (default 1000) with
  drop-**newest** + a synchronous throttle reply + a `queue_full` drop
  metric. The implementer decides whether to restore that exact shape or a
  leaner bound (bounded queue + drop + metric, no throttle reply) — the
  hard requirement is that queue memory is bounded; the throttle reply is
  a UX nicety, not the security property. Whatever ships, §6.3.7 and the
  code must agree (M1-205 exists precisely to end "design asserting
  implementations that don't exist").
- Adjacent code: the executor construction at `SimpleXWebSocketClient`
  (`Executors.newSingleThreadExecutor`, line ~105) and `SignalJsonRpcClient`
  (line ~226). A bounded queue needs an explicit `ThreadPoolExecutor` with
  a `LinkedBlockingQueue(cap)` + a `RejectedExecutionHandler` (or a manual
  `offer`-with-drop on the enqueue path in `dispatchAsync`).
- Before `/m1-tick start M1-224`, review the sizing fields (complexity/
  risk/budget) and tighten acceptance item 2's drop-policy wording to the
  exact chosen shape; run `scripts/lint-ticket.py` on this file.
