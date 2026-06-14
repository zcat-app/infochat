---
id: M1-371
title: "collector: give stream dispatch keys a distinct type so they cannot collide with FetchScheduler source keys"
status: pending
created: 2026-06-14
last_updated: 2026-06-14
blocked_by: []
files_budget: 5
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/StreamSourceSupervisor.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/stream/nostr/NostrStreamSource.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/stream
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - The FetchScheduler polled-source keyspace itself — unchanged; this ticket only makes the stream-supervisor surface refuse a foreign bare-long key, it does not unify the two keyspaces.
  - The worker lifecycle, drain/flush budget, and registration semantics of StreamSourceSupervisor — unchanged apart from the key parameter type.
acceptance:
  - "StreamSourceSupervisor.register / stop / drainAll and the registrations map key on a distinct stream-dispatch handle type (e.g. a small record/value wrapper) rather than a bare long. The Nostr registrar mints typed handles; the auto-disable path passes typed handles. A polled FetchScheduler source key (a bare long) can no longer be passed to supervisor.stop — it is a compile-time type error."
  - "The 'collision is only documented' caveat in StreamSourceSupervisor.stop's javadoc is replaced by the type-level guarantee (or trimmed to reflect that the type now prevents it)."
  - "Existing stream tests in infochat-collector/src/test/java/app/zcat/infochat/collector/stream are updated to the typed handle and a test confirms a typed handle round-trips through register→stop."
  - "mvn -B clean verify from the repo root exits 0."
test_plan:
  modifies:
    - infochat-collector/src/test/java/app/zcat/infochat/collector/stream (typed-handle call-site updates + round-trip test)
  preserves:
    - all tests currently green on main
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-371: distinct stream dispatch-key type

## Context

Deep-review v7 (opus-48) collector finding **F3** (MAINTAINABILITY). Verified at
source 2026-06-14 — **real but latent, severity adjusted to low**:

`NostrStreamSource`'s registrar mints `nextDispatchKey` from 1
(`.../stream/nostr/NostrStreamSource.java:399-444`) and `FetchScheduler` mints an
independent 1-based source keyspace; both are bare `long`. `StreamSourceSupervisor.stop(long)`
(`.../stream/StreamSourceSupervisor.java:119-138`) documents the collision in
prose: a key minted in another keyspace passed here "would collide with, and
stop, an unrelated stream this supervisor happens to hold under the same numeric
key."

**No live collision exists today** — `supervisor.stop` is only ever called from
the Nostr auto-disable path with keys from the stream keyspace; `FetchScheduler`
never touches the supervisor. So this is a documented footgun guarded only by
caller discipline, not an active bug. A distinct handle type makes the wrong call
a compile error and removes the prose caveat. Worth doing for type-safety;
**not a beta blocker.**

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- A minimal value wrapper (record holding the long) is enough; the goal is type
  distinctness, not a new abstraction layer. Keep `dispatchKeyBySource` keyed by
  source UUID; only the supervisor-facing key type changes.
