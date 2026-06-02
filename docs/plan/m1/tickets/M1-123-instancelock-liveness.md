---
id: M1-123
title: "InstanceLockGuard held-session liveness + collector/provider dedup"
status: pending
created: 2026-06-02
last_updated: 2026-06-02
blocked_by: []
files_budget: 8
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/startup
  - infochat-provider/src/main/java/app/zcat/infochat/provider/startup
  - infochat-core/src/main/java/app/zcat/infochat/core
  - infochat-core/src/test/java/app/zcat/infochat/core
complexity: high
risk: high
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - the HeartbeatScheduler last_seen_at refresh — it already exists and works; this ticket does NOT "add a heartbeat"
  - any change to the advisory-lock key or the single-instance acquisition logic itself
  - infochat-provider command/digest code; infochat-collector eval/fetch code
acceptance:
  - "The lock-owning (held) JDBC session is periodically liveness-probed: a scheduled check re-verifies advisory-lock ownership on the held connection (not a transient pool connection) and SELECT 1 confirms the connection is alive"
  - "On lost ownership or a dead held connection the process calls Quarkus.asyncExit(1) rather than continuing as a zombie"
  - "TCP keepalive / setNetworkTimeout is set on the held lock connection"
  - "The InstanceLockGuard logic is shared (single copy in infochat-core) rather than duplicated byte-for-byte between collector and provider"
  - "A test simulates a lost/closed held connection and asserts the liveness probe triggers the exit path (e.g. via an injectable exit hook)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-core/src/test/java/app/zcat/infochat/core
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Deployment topology (v1)
  - docs/spec/architecture.md §Inter-service communication
decision_refs:
  - D41
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-123: InstanceLockGuard held-session liveness + collector/provider dedup

## Context

`InstanceLockGuard` holds the single-instance advisory lock on a long-lived
JDBC connection (`heldConnection.setAutoCommit(true)`, borrowed outside the
pool). `HeartbeatScheduler.tick()` **already refreshes** `last_seen_at` every
interval — but on a *transient pool connection*, so the held lock-owning session
is never liveness-probed. If that session dies server-side (PG restart, NAT
reaping, `idle_in_transaction_session_timeout`, keepalive loss) the advisory
lock releases while the JVM keeps running as a zombie, and the heartbeat on the
healthy pool connection *masks* the dead holder from a second acquirer's
staleness check. D41 single-instance enforcement underpins the outbox
rehydrator and the advisory-locked FetchScheduler enumeration.

## Acceptance

See frontmatter. The fix targets **the held session**, not the heartbeat:
periodically re-verify advisory-lock ownership / `SELECT 1` on the lock-owning
connection and `Quarkus.asyncExit(1)` on loss; set TCP keepalive. The
byte-for-byte duplicate guard is consolidated into `infochat-core` so the fix
lands once.

## Out-of-scope

See frontmatter. **This is not "add a heartbeat scheduler"** — that already
exists. A ticket written to the mimo-audit literal description would change
nothing. The plan-writer pass must confirm the de-dup move keeps both services'
`@Startup` ordering intact.

## Notes

- Source: `docs/plan/audit/opus-48-handout.md` §A9 (LOCK-LIVENESS, Medium,
  GROUNDED — mechanism corrected) + F-MAINT-54 (dedup); `opus-47-full-handout.md`
  §F-MAINT-02, F-MAINT-54.
- Loci: `InstanceLockGuard.java:76,84` (collector + provider twins),
  `HeartbeatScheduler.java:30-38`.
- Plan-writer pass recommended: the held-connection liveness probe interacts
  with the existing heartbeat scheduler and the de-dup move across two modules.
