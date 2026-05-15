---
id: M1-027
title: Provider catch-up (provider_state + NewPostReconciler + new_post listener)
status: done
created: 2026-05-15
last_updated: 2026-05-15
clarity_check:
  date: 2026-05-15
  verdict: WARN
  warnings:
    - "ACCEPTANCE-VS-DOD-CONSISTENT (item 21): HETEROGENEOUS-AGGREGATE — the failsafe-reports grep bundles three structurally-different IT classes (ProviderStateDaoIT, NewPostReconcilerIT, NewPostListenerIT) into one ≥3 count; a regression that drops one IT class could still satisfy the aggregate from the surviving two. Recommended fix: split into three per-element items, each asserting the per-IT-class failsafe report file (e.g. 'grep -E ''Tests run: [1-9]'' infochat-provider/target/failsafe-reports/TEST-io.infochat.provider.outbox.ProviderStateDaoIT.xml returns at least one match' and similarly for the other two)."
    - "DoD vs docs/design/02-schema.md §2.9.2 discrepancy on the first-boot insert: the ticket's DoD writes cursor_low_kind='post' and cursor_low_id='' while §2.9.2 shows cursor_low_kind='' and cursor_low_id='' (both empty). Acceptance item 7 does NOT pin cursor_low_kind, so the discrepancy escapes the suite. The developer must reconcile with §2.9.2 before implementing: if 'post' is intentional update the design note (process: commit); if '' is correct, correct the DoD's VALUES clause."
  blockers: []
reviews:
  - round: 1
    date: 2026-05-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 9
      added: 1306
      removed: 11
blocked_by:
  - M1-008c
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration/V9__provider_state.sql
  - infochat-provider/src/main/java/io/infochat/provider/outbox/ProviderStateDao.java
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostReconciler.java
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostListener.java
  - infochat-provider/src/main/java/io/infochat/provider/outbox/NewPostHandler.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/ProviderStateDaoIT.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerIT.java
  - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - any `new_price_snapshot` channel listener, asset cache, or `(asset, sub_verb)` cache invalidation (Tier-2 T2-H per decision D39 — best-effort, flush-on-Postgres-reconnect, no `provider_state` row; see docs/design/01-architecture.md §1.3.3 and docs/design/02-schema.md §2.9.1)
  - any `quarantine_review` channel listener, reconciler, admin notifier, or quarantine state-machine wiring (M2 territory per docs/design/01-architecture.md §1.5 — M1 ships the `new_post` channel only; the `quarantine_review` reconciler lands alongside the admin quarantine-review commands in M2)
  - any real cache-invalidation, periodic-digest recompute, or downstream T1-F consumer logic inside `NewPostHandler` (T1-F territory; this ticket ships a STUB handler that logs the event and advances the cursor so the catch-up + listener correctness argument is independently testable)
  - any Collector-side outbox emit, `PostPersister`, `EvalQueueProducer`, `FetchScheduler`, or `OutboxRehydrator` code (M1-028 territory; the two T1-C tickets share no runtime code and can be implemented in either order)
  - any actual `post.status → READY` transition logic — that fires from T1-D's eval pipeline stage 5 (`docs/design/01-architecture.md` §1.3.4 step 5), which sets `status='READY'`, `ready_at=now()`, and emits `pg_notify('new_post', payload)`; this ticket's tests insert READY rows directly via JDBC and emit `NOTIFY` from the test harness
  - any change to V1..V8 Flyway migrations (frozen; this ticket adds V9 only — re-grep the migration directory at `/m1-tick start` time and pick the next free integer if M1-021 has landed in the interim, in which case slide this migration to V10)
  - any messaging adapter / CommandRouter / `/help` / first-command implementation work (T1-E and T1-F territory)
  - any Stage 1 / Stage 2 / tagger / entity-extractor / embedding-worker logic (T1-D)
  - any modification to the V7 `post` table schema authored in M1-008c (this ticket consumes the table; if the consumer reveals a missing column, escalate via the workflow rather than mutating V7 inline)
  - any Java entity class, Panache mapping, or JPA repository for `post` or `provider_state` (raw JDBC only — the `provider_state` row has exactly two SQL shapes (read + CAS update); the `post` catch-up scan is one SELECT; pre-empting an entity layer here is scope drift)
  - any infochat-collector module change (this ticket is provider-side only; the listener and reconciler live in `infochat-provider`)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V9__provider_state.sql exists and creates the provider_state table per docs/design/02-schema.md §2.9.2 — grep -E 'CREATE TABLE\\s+provider_state' V9__provider_state.sql returns at least one match"
  - "V9 declares the five columns per §2.9.2 — grep -E 'channel\\s+TEXT\\s+NOT NULL' V9__provider_state.sql returns at least one match AND grep -E 'cursor_high\\s+TIMESTAMPTZ\\s+NOT NULL' V9__provider_state.sql returns at least one match AND grep -E 'cursor_low_kind\\s+TEXT\\s+NOT NULL' V9__provider_state.sql returns at least one match AND grep -E 'cursor_low_id\\s+TEXT\\s+NOT NULL' V9__provider_state.sql returns at least one match AND grep -E 'updated_at\\s+TIMESTAMPTZ\\s+NOT NULL\\s+DEFAULT\\s+now\\(\\)' V9__provider_state.sql returns at least one match"
  - "V9 declares the singleton-row-per-channel constraint per docs/spec/schema.md §Operational (Provider state) and §2.9.2 — grep -E 'UNIQUE\\s*\\(\\s*channel\\s*\\)' V9__provider_state.sql returns at least one match"
  - "V9 grants the Provider role write privileges on provider_state (the Provider owns the cursor) — grep -E 'GRANT\\s+SELECT\\s*,\\s*INSERT\\s*,\\s*UPDATE\\s+ON\\s+provider_state\\s+TO\\s+infochat_provider' V9__provider_state.sql returns at least one match"
  - "V9 grants the Collector role SELECT-only on provider_state (collector-side diagnostic / admin reads only — the Collector never writes the cursor) — grep -E 'GRANT\\s+SELECT\\s+ON\\s+provider_state\\s+TO\\s+infochat_collector' V9__provider_state.sql returns at least one match"
  - "V9 revokes DELETE on provider_state from both service roles (the row is upserted, never deleted) per the per-table GRANT discipline in docs/spec/security.md §DB roles — grep -E 'REVOKE\\s+DELETE\\s+ON\\s+provider_state\\s+FROM\\s+(infochat_collector|infochat_provider|PUBLIC)' V9__provider_state.sql returns at least one match"
  - "V9 emits the first-boot insert for the `new_post` channel per docs/design/02-schema.md §2.9.2 First-boot insert — grep -E \"INSERT\\s+INTO\\s+provider_state\" V9__provider_state.sql returns at least one match AND grep -E \"'new_post'\" V9__provider_state.sql returns at least one match AND grep -E \"'epoch'::TIMESTAMPTZ\" V9__provider_state.sql returns at least one match AND grep -E 'ON CONFLICT\\s*\\(\\s*channel\\s*\\)\\s+DO NOTHING' V9__provider_state.sql returns at least one match"
  - "V9 does NOT seed a `quarantine_review` row — the quarantine reconciler lands in M2 per docs/design/01-architecture.md §1.5 — grep -E \"'quarantine_review'\" V9__provider_state.sql returns zero matches"
  - "ProviderStateDao.java exists and is the SOLE write path to provider_state — grep -E 'public\\s+class\\s+ProviderStateDao' ProviderStateDao.java returns at least one match AND grep -rE 'UPDATE\\s+provider_state' infochat-provider/src/main/java/ returns matches ONLY inside ProviderStateDao.java (no other production class issues an UPDATE against provider_state)"
  - "ProviderStateDao.java carries the compare-and-swap update verbatim from docs/design/02-schema.md §2.9.2 — the SQL string contains the compound-cursor predicate, NOT the cursor_high-only form — grep -E '\\(cursor_high\\s*,\\s*cursor_low_kind\\s*,\\s*cursor_low_id\\s*\\)\\s*<\\s*\\(' ProviderStateDao.java returns at least one match"
  - "ProviderStateDao.java updates ALL four mutable column values atomically in the CAS branch per §2.9.2 — grep -E 'cursor_high\\s*=' ProviderStateDao.java returns at least one match AND grep -E 'cursor_low_kind\\s*=' ProviderStateDao.java returns at least one match AND grep -E 'cursor_low_id\\s*=' ProviderStateDao.java returns at least one match AND grep -E 'updated_at\\s*=\\s*now\\(\\)' ProviderStateDao.java returns at least one match"
  - "NewPostReconciler.java is a Quarkus @Startup bean at @Priority(250) per docs/design/01-architecture.md §1.4.3 Provider table — grep -E '@Startup' NewPostReconciler.java returns at least one match AND grep -E '@Priority\\s*\\(\\s*250\\s*\\)' NewPostReconciler.java returns at least one match"
  - "NewPostReconciler.java issues the catch-up scan with the compound-cursor predicate from docs/design/01-architecture.md §1.5 Principle 2 and docs/design/02-schema.md §2.9.2 — grep -E \"status\\s*=\\s*'READY'\" NewPostReconciler.java returns at least one match AND grep -E '\\(ready_at\\s*,\\s*id\\)\\s*>\\s*\\(' NewPostReconciler.java returns at least one match AND grep -E 'ORDER BY\\s+ready_at\\s*,\\s*id' NewPostReconciler.java returns at least one match"
  - "NewPostHandler.java exposes one entry point that processes a single (post_id, ready_at) pair and is the SHARED code path between the catch-up reconciler and the live NOTIFY listener (so push and catch-up advance the cursor identically per docs/spec/architecture.md §Inter-service communication §Catch-up — 'and feeds those rows into the same handler that processes live NOTIFY new_post payloads') — grep -E 'public\\s+class\\s+NewPostHandler' NewPostHandler.java returns at least one match AND both NewPostReconciler.java and NewPostListener.java declare `@Inject` (or equivalent CDI lookup) of NewPostHandler — grep -E 'NewPostHandler' NewPostReconciler.java returns at least one match AND grep -E 'NewPostHandler' NewPostListener.java returns at least one match"
  - "NewPostHandler.java advances the cursor in the SAME DB transaction as its side effect per docs/spec/architecture.md §Inter-service communication §Catch-up ('the high-water mark advances both fields in the same DB transaction as the side effect it triggers, making processing idempotent') — grep -E '@Transactional|TransactionManager|UserTransaction|setAutoCommit\\s*\\(\\s*false\\s*\\)' NewPostHandler.java returns at least one match AND the handler invokes ProviderStateDao's CAS update inside that transactional boundary (the same method or @Transactional-wrapped public entry point)"
  - "NewPostListener.java is a Quarkus @ApplicationScoped bean that LISTENs on the `new_post` channel — grep -E '@ApplicationScoped' NewPostListener.java returns at least one match AND grep -E 'LISTEN\\s+new_post|getNotifications|PGNotification|pg_notify|\"new_post\"' NewPostListener.java returns at least one match"
  - "NewPostListener.java starts its listen loop AFTER NewPostReconciler completes (so a NOTIFY arriving mid-catch-up cannot be processed before older READY posts) per docs/spec/architecture.md §Inter-service communication §Catch-up — NewPostListener declares @Priority strictly greater than 250 OR explicitly waits on NewPostReconciler completion (e.g. via @DependsOn equivalent / @Observes a reconciler-finished CDI event) — grep -E '@Priority\\s*\\(\\s*(2[6-9][0-9]|[3-9][0-9]{2,})' NewPostListener.java returns at least one match OR grep -E '@Observes|NewPostReconciler' NewPostListener.java returns at least one match"
  - "ProviderStateDaoIT.java is a @QuarkusTest integration test against a real Postgres (Quarkus DevServices acceptable) and asserts: (a) the CAS update is a NO-OP when the supplied cursor `(ready_at, id)` is `<=` the stored cursor (a slow processor cannot roll back a fast one's mark — docs/spec/schema.md §Operational Provider state 'compare-and-swap so a slow processor cannot roll back a fast one's mark'); (b) the CAS update SUCCEEDS when the supplied cursor is strictly `>` the stored cursor and updates all four column values atomically; (c) a second `INSERT INTO provider_state … ON CONFLICT (channel) DO NOTHING` against the seeded `new_post` row is a no-op (the first-boot race guard from §2.9.2) — grep -E '@Test' ProviderStateDaoIT.java returns at least three matches"
  - "NewPostReconcilerIT.java is a @QuarkusTest that boots the Provider against a clean DB seeded with V1..V9, inserts N READY post rows directly via JDBC with controlled (ready_at, id) values, runs NewPostReconciler, and asserts: (a) NewPostHandler processed every row exactly once in (ready_at, id) order; (b) the final provider_state cursor matches the last row's (ready_at, id); (c) re-running the reconciler is an IDEMPOTENT no-op (zero additional handler invocations) per docs/spec/architecture.md §Inter-service communication §Catch-up 'a duplicate NOTIFY or a repeated catch-up pass for the same row produces no additional side effect' — grep -E '@Test' NewPostReconcilerIT.java returns at least three matches"
  - "NewPostListenerIT.java is a @QuarkusTest that emits `NOTIFY new_post` from the test harness via JDBC and asserts: (a) the listener wakes and invokes NewPostHandler with the payload's (ready_at, post_id); (b) the cursor advances to that (ready_at, post_id); (c) a DUPLICATE NOTIFY with the same payload (cursor `<=` stored) produces NO additional handler call (the CAS no-op rejects the duplicate at the cursor level, satisfying the idempotency promise from docs/spec/architecture.md §Inter-service communication §Catch-up); the test uses a real Postgres-emitted NOTIFY (not an in-process mock) so the LISTEN side and the JDBC notification surface are exercised end-to-end — grep -E '@Test' NewPostListenerIT.java returns at least three matches AND grep -E 'pg_notify|NOTIFY\\s+new_post' NewPostListenerIT.java returns at least one match"
  - "mvn -B -pl infochat-provider -am verify exits 0; failsafe reports show ProviderStateDaoIT, NewPostReconcilerIT, and NewPostListenerIT executed (grep -rE 'Tests run: [1-9]' infochat-provider/target/failsafe-reports returns at least three new matches across the three new IT classes)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003, M1-007, M1-007a/b/c, M1-008, M1-008a/b/c, M1-009, M1-017, M1-022, M1-023, M1-024, M1-025, and M1-026 tests continue to pass alongside the new V9 migration and the Provider catch-up code"
test_plan:
  adds:
    - infochat-provider/src/test/java/io/infochat/provider/outbox/ProviderStateDaoIT.java (@QuarkusTest IT exercising compare-and-swap correctness + first-boot insert idempotency against a real Postgres)
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostReconcilerIT.java (@QuarkusTest IT seeding READY post rows + asserting catch-up shape and re-run idempotency)
    - infochat-provider/src/test/java/io/infochat/provider/outbox/NewPostListenerIT.java (@QuarkusTest IT emitting real pg_notify and asserting handler invocation + duplicate-NOTIFY idempotency)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-017 — V9 must apply cleanly alongside V1..V8)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 / M1-017)
    - infochat-collector/src/test/java/io/infochat/collector/startup/InstanceLockGuardIT.java (M1-009)
    - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java (M1-009)
    - all M1-008a / M1-008b / M1-008c *Test.java classes (schema tests; the V9 migration extends but does not modify the V5/V6/V7 surface)
    - M1-022 BootstrapSourcesParserTest + BootstrapLoaderIT
    - M1-023 RssFetcherTest + RssFeedParserTest
    - M1-024 / M1-025 / M1-026 infochat-ssrf tests
spec_refs:
  - docs/spec/architecture.md §Inter-service communication
  - docs/spec/architecture.md §Deployment topology (v1)
  - docs/spec/architecture.md §Architectural principles
  - docs/spec/schema.md §Posts and derivatives
  - docs/spec/schema.md §Operational
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §DB roles
  - docs/design/01-architecture.md §1.4.3 Startup-bean ordering and single-instance enforcement
  - docs/design/01-architecture.md §1.5 Architectural principles (design-tier additions)
  - docs/design/02-schema.md §2.9.1 LISTEN / NOTIFY channels
  - docs/design/02-schema.md §2.9.2 provider_state
decision_refs:
  - D3
  - D4
  - D41
---

# M1-027: Provider catch-up (provider_state + NewPostReconciler + new_post listener)

## Context

First of two T1-C outbox/NOTIFY tickets (the second is M1-028, the
Collector-side outbox sink and rehydrator). This ticket lands the
**Provider half** of the inter-service push/catch-up contract from
`docs/spec/architecture.md` §Inter-service communication: the per-channel
high-water-mark cursor (`provider_state`), the startup reconciler that
replays `READY` posts since the cursor (`NewPostReconciler`), the live
LISTEN/NOTIFY listener that wakes on new events (`NewPostListener`), and
the shared single-row handler (`NewPostHandler`) that both code paths
funnel through so push and catch-up advance the cursor identically.

The two-ticket carve-out (Option A) was picked at the top of this
authoring session per the operator's JIT handoff: the Provider side and
the Collector side have different review focus. The Provider side is
"catch-up correctness + compound-cursor compare-and-swap + first-boot
race + same-transaction-as-side-effect idempotency"; the Collector side
is "scheduler cadence + persist-before-enqueue + crash-recovery
rehydrator." Bundling both in one ticket would force the reviewer to
chase two unrelated correctness arguments in a single diff with a
14-16-file footprint. Two ~7-8-file tickets fit the medium-complexity
profile cleanly. The two tickets are independent runnable-now — they
share no runtime code and can be implemented in either order.

`provider_state` is a new schema-layer commitment authored here for the
first time. Its shape is `(channel TEXT, cursor_high TIMESTAMPTZ,
cursor_low_kind TEXT, cursor_low_id TEXT, updated_at TIMESTAMPTZ)`
keyed by `UNIQUE (channel)` — one row per LISTEN/NOTIFY channel,
schema-enforced singleton, channel-agnostic shape with per-channel
cursor interpretation (`docs/spec/schema.md` §Operational — Provider
state; `docs/design/02-schema.md` §2.9.2). v1's closed-list channels are
`new_post`, `quarantine_review`, and `new_price_snapshot`; this ticket
seeds the `new_post` row only — `quarantine_review` lands in M2
alongside the admin quarantine-review commands (per
`docs/design/01-architecture.md` §1.5), and `new_price_snapshot` does
NOT maintain a `provider_state` row at all (flush-on-Postgres-reconnect
is the correctness mechanism per `docs/design/02-schema.md` §2.9.1).

The handler is a **stub** in T1-C scope: it logs the event, advances
the cursor in the same DB transaction, and returns. T1-F wires the real
cache-invalidation / digest-recompute consumers downstream. Documenting
the stub boundary explicitly in this ticket is load-bearing — the
reviewer's negative-space check should NOT flag the missing T1-F logic
as scope drift, and the test suite should NOT assert any side effect
beyond the cursor advance.

## Definition of Done

- A new Flyway migration
  `infochat-core/src/main/resources/db/migration/V9__provider_state.sql`
  creates the `provider_state` table per `docs/design/02-schema.md`
  §2.9.2:
  - `channel TEXT NOT NULL`
  - `cursor_high TIMESTAMPTZ NOT NULL`
  - `cursor_low_kind TEXT NOT NULL`
  - `cursor_low_id TEXT NOT NULL`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
  - `UNIQUE (channel)` — the schema-layer singleton-row-per-channel
    enforcement from `docs/spec/schema.md` §Operational ("A `UNIQUE`
    constraint on `channel` enforces the singleton-row-per-channel
    semantics at the schema layer").
- **Per-table GRANTs** aligned with `docs/spec/security.md` §DB roles
  and the handoff's locked decision:
  - `GRANT SELECT, INSERT, UPDATE ON provider_state TO infochat_provider`
    — the Provider owns the cursor and is the only role that writes it.
  - `GRANT SELECT ON provider_state TO infochat_collector` — collector-side
    admin / diagnostic reads only (e.g. `/status` on the Collector
    surface would consult this row to answer "is the Provider caught
    up?" — the read path is admin-only, the Collector never writes).
  - `REVOKE DELETE ON provider_state FROM infochat_collector,
    infochat_provider, PUBLIC` — the row is upserted, never deleted;
    defense-in-depth complement of the singleton `UNIQUE (channel)`.
- **First-boot insert for the `new_post` channel** emitted by the
  migration so the row exists before any Provider code runs:
  ```sql
  INSERT INTO provider_state (channel, cursor_high, cursor_low_kind, cursor_low_id, updated_at)
  VALUES ('new_post', 'epoch'::TIMESTAMPTZ, '', '', now())
  ON CONFLICT (channel) DO NOTHING;
  ```
  The `ON CONFLICT (channel) DO NOTHING` is load-bearing per
  `docs/spec/schema.md` §Operational ("two fresh Provider instances
  starting concurrently both attempt the insert, exactly one wins, and
  the winning instance owns the cursor — no duplicate rows can be
  produced by the first-insert race"). The migration's INSERT is one
  contributor to that race; a future Provider-side `@Startup`
  first-boot guard would be another. Either path is safe because the
  guard is at the schema layer.
- The migration does **NOT** seed a `quarantine_review` row. M1 ships
  the `new_post` channel reconciler only; `quarantine_review` lands in
  M2 (`docs/design/01-architecture.md` §1.5: "M1 only ships the
  `new_post` reconciler; the `quarantine_review` reconciler lands in
  M2 alongside the admin quarantine-review commands").
- `ProviderStateDao.java` is a narrow JDBC wrapper around two SQL
  shapes from `docs/design/02-schema.md` §2.9.2:
  1. **Read cursor**: `SELECT cursor_high, cursor_low_kind, cursor_low_id
     FROM provider_state WHERE channel = ?`.
  2. **Compare-and-swap update** verbatim from §2.9.2:
     ```sql
     UPDATE provider_state
        SET cursor_high     = :new_high,
            cursor_low_kind = :new_kind,
            cursor_low_id   = :new_id,
            updated_at      = now()
      WHERE channel = :ch
        AND (cursor_high, cursor_low_kind, cursor_low_id)
            < (:new_high, :new_kind, :new_id);
     ```
  The compound-cursor predicate (NOT `cursor_high` alone) is
  load-bearing per `docs/spec/schema.md` §Operational: "The compound
  cursor (not `cursor_high` alone) ensures two events sharing a
  high-key value are both processed on catch-up." The DAO is the SOLE
  production code path that writes `provider_state`; both the listener
  and the reconciler advance the cursor through this DAO via
  `NewPostHandler`.
- `NewPostHandler.java` is the unit-of-work processor for one
  `(post_id, ready_at)` event. In T1-C scope this is a **stub**:
  log the event at INFO level with the cursor key, then invoke
  `ProviderStateDao.advanceCursor(ready_at, "post", post_id.toString())`.
  The handler MUST advance the cursor **in the same DB transaction
  as its side effect** per `docs/spec/architecture.md`
  §Inter-service communication §Catch-up: "the high-water mark advances
  both fields in the same DB transaction as the side effect it
  triggers, making processing idempotent." In T1-C the side effect is
  the log line; once T1-F adds real cache-invalidation logic, that
  logic lives inside the same `@Transactional` boundary so the
  invariant survives. The handler MUST be the shared code path between
  the reconciler and the listener — neither bean talks to the DAO
  directly; both inject the handler.
- `NewPostReconciler.java` is a Quarkus `@Startup` bean at
  `@Priority(250)` per `docs/design/01-architecture.md` §1.4.3 Provider
  table. On startup:
  1. Read the current cursor via `ProviderStateDao.readCursor("new_post")`.
  2. Run the catch-up scan per `docs/design/01-architecture.md` §1.5
     and `docs/design/02-schema.md` §2.9.2:
     ```sql
     SELECT id, ready_at FROM post
      WHERE status = 'READY'
        AND (ready_at, id) > (:cursor_high, :cursor_low_id)
      ORDER BY ready_at, id;
     ```
  3. For each row, invoke `NewPostHandler.handle(post_id, ready_at)`.
     The handler's `@Transactional` boundary advances the cursor
     atomically with its side effect; idempotency is the CAS
     no-op for cursor values `<=` the stored cursor.
  4. Log a one-line INFO summary: `NewPostReconciler: caught up <N>
     posts from cursor=(ready_at=<old_high>, id=<old_low>) to
     cursor=(ready_at=<new_high>, id=<new_low>)`.
- `NewPostListener.java` is a Quarkus `@ApplicationScoped` bean that
  starts AFTER `NewPostReconciler` finishes (so a NOTIFY arriving
  mid-catch-up cannot be processed before older READY posts). The
  listener:
  - Holds a dedicated `Connection` (NOT borrowed from the Quarkus
    pool — LISTEN is connection-scoped) and issues `LISTEN new_post`
    once at startup.
  - Runs a virtual-thread worker loop that calls
    `connection.unwrap(org.postgresql.PGConnection.class)
    .getNotifications(<timeout_ms>)` and dispatches each notification
    to `NewPostHandler.handle(...)`.
  - Parses the NOTIFY payload as `{ready_at, post_id}` per
    `docs/design/02-schema.md` §2.9.1 (cursor-only payload; the
    payload format is JSON or a simple comma-separated cursor string
    — pick at implementation time, document in Implementation
    notes; the M1-028 outbox emit must match).
- Three integration tests:
  - `ProviderStateDaoIT.java` (`@QuarkusTest` against Quarkus
    DevServices Postgres) — exercises CAS correctness and first-boot
    insert idempotency.
  - `NewPostReconcilerIT.java` (`@QuarkusTest`) — seeds READY rows
    directly via JDBC, runs the reconciler, asserts catch-up shape
    and re-run idempotency.
  - `NewPostListenerIT.java` (`@QuarkusTest`) — emits real
    `pg_notify('new_post', '...')` from the test harness via JDBC,
    asserts handler invocation, cursor advance, and duplicate-NOTIFY
    idempotency.
- `mvn -B clean verify` from the repo root exits 0. All prior tests
  continue to pass alongside the new V9 migration and the Provider
  catch-up code.

## Implementation notes

- **Option A (two tickets) chosen at the top of this authoring
  session.** Per the operator's JIT handoff, T1-C is split into M1-027
  (this ticket, Provider catch-up) and M1-028 (Collector outbox +
  rehydrator). The two tickets are independent runnable-now and share
  no runtime code. Option B (one combined ticket) was rejected for
  review-focus reasons documented in the handoff.
- **Migration version is V9.** V1..V8 already live on disk per
  M1-005, M1-006, M1-009, M1-016, M1-017, M1-008a/b/c, and M1-022. If
  a later authoring session lands M1-021's identity/audit redteam
  remediation migration as V9 before this ticket starts, slide this
  ticket's migration to V10 — re-grep the migration directory at
  `/m1-tick start` time and pick the next free integer. The slug
  `provider_state` is the invariant; the numeric prefix is allocated
  mechanically. The acceptance items are written against `V9`
  literally; the developer-agent re-numbers them mechanically if the
  prefix changes.
- **LISTEN/NOTIFY transport: standard `org.postgresql` JDBC driver,
  not pgjdbc-ng.** The project's `pom.xml` already pulls in
  `org.postgresql:postgresql` via the Quarkus pgsql connector;
  adding pgjdbc-ng would introduce a parallel driver dependency and
  cross-driver connection-pool ambiguity. The standard pgjdbc
  driver exposes LISTEN/NOTIFY via
  `connection.unwrap(PGConnection.class).getNotifications(timeoutMs)`
  which blocks the calling thread for up to `timeoutMs` ms waiting
  for a notification. With JDK 25 virtual threads (per the
  `project_quarkus_jdk25` auto-memory) a dedicated worker that
  blocks in `getNotifications` is cheap and ergonomic; the
  blocking-style loop is the right shape for this codebase.
- **The listener holds its own `Connection`.** LISTEN is
  connection-scoped: a notification fired on connection A is NOT
  delivered to a LISTEN registered on connection B. The listener
  bean owns one `Connection` for its full lifetime, registered via
  `@Inject DataSource dataSource;` + `dataSource.getConnection()`
  on `@PostConstruct` (NOT borrowed from a request-scoped pool).
  On `@PreDestroy` the connection is closed cleanly.
- **The NOTIFY payload format.** Per `docs/design/02-schema.md`
  §2.9.1 the `new_post` payload is `{ready_at, post_id}` (cursor
  only). The wire format (JSON string vs. compact `<iso8601>|<uuid>`)
  is an implementation choice; pick the JSON form so future cursor
  shapes can extend without a wire-format break. The M1-028 outbox
  emit MUST use the same format — the parser and the emitter share
  one helper class (named after the format choice) to keep them in
  sync. **If the implementer chooses a non-JSON form**, document
  the choice in commit messages so M1-028's reviewer can verify
  alignment.
- **`@Priority` for the listener vs. the reconciler.** The
  reconciler is `@Startup` `@Priority(250)`. The listener MUST
  start AFTER (numerically greater priority, or explicit
  dependency). Two acceptable shapes:
  1. `@Startup` `@Priority(260)` on `NewPostListener` — the priority
     ordering guarantees the reconciler's `@PostConstruct` returns
     before the listener's begins.
  2. `NewPostListener` observes a CDI event the reconciler fires
     after catch-up completes (`@Observes ReconcilerFinished`).
  Shape (1) is simpler and matches the existing `@Priority`-based
  ordering pattern in `docs/design/01-architecture.md` §1.4.3; the
  acceptance items accept either.
- **The handler's `@Transactional` boundary.** The handler advances
  the cursor in the same DB transaction as its side effect per
  spec. In Quarkus this is `@Transactional` on the public handler
  method; the CAS update inside `ProviderStateDao.advanceCursor`
  runs on the same JDBC connection the transaction manager bound
  to the calling thread. The reconciler invokes
  `handler.handle(...)` once per row inside a per-row transaction
  (NOT one big transaction across all rows — a single bulk
  transaction would lock `provider_state` for the duration of the
  catch-up and a duplicate NOTIFY arriving mid-catch-up would
  block).
- **Stub handler boundary.** The handler's side effect in T1-C is
  the log line, intentionally. T1-F wires the real consumers.
  Documenting this in the handler's JDoc helps the future reviewer
  not flag the stub as incomplete: T1-F will add cache-invalidation
  logic inside the existing `@Transactional` boundary so the
  same-transaction invariant survives without a refactor.
- **Raw JDBC, not Panache.** This ticket adds no entity classes.
  `provider_state` has exactly two SQL shapes (read + CAS update);
  the catch-up scan against `post` is one SELECT; the listener's
  payload parse is a string→record split. Pre-empting an entity
  layer for the cursor row would add a Panache or Hibernate mapping
  for a single column quintuple and obscure the CAS update's
  compound-cursor SQL behind an ORM idiom. Future tickets that
  introduce entities for `post` (e.g. T1-F's `/summary` handler)
  can attach to the underlying table; the DAO here is intentionally
  thin and SQL-first.
- **`@QuarkusTest` against Quarkus DevServices Postgres.** The
  existing `FlywayMigrationIT` (M1-017) and `DbRoleMatrixIT`
  (M1-006) demonstrate the pattern; the three new ITs follow the
  same shape. DevServices spins up a pgvector-enabled container on
  test startup, runs Flyway V1..V9, and tears down at JVM exit.
- **`ProviderStateDaoIT.advanceCursor`-no-op assertion.** The
  CAS-no-op behavior is asserted as follows: seed the row with
  cursor=(ready_at=T+10s, id="b"), call
  `advanceCursor(ready_at=T+5s, "post", "a")` (strictly earlier),
  re-read the row, assert the cursor is still (T+10s, "b"). Then
  call `advanceCursor(ready_at=T+10s, "post", "b")` (equal to the
  stored), re-read, assert still (T+10s, "b") — the CAS predicate
  is `<`, not `<=`, so equal-cursor is also a no-op.
- **`NewPostListenerIT` emits NOTIFY via JDBC.** The test acquires
  its own JDBC `Connection` (separate from the listener bean's
  connection) and issues `SELECT pg_notify('new_post',
  '{"ready_at":"<iso>","post_id":"<uuid>"}')`. The listener bean's
  worker thread is woken inside the JDBC driver's
  `getNotifications` block and dispatches to the handler;
  the test uses `Awaitility` (already on the test classpath) to
  poll for the cursor advance with a reasonable timeout (e.g.
  5 seconds).

## Big-picture notes

- **Security-relevant.** The cursor is a single point of failure for
  the inter-service correctness guarantee. A buggy CAS update or a
  missing `ON CONFLICT (channel) DO NOTHING` on the first-boot
  insert could either lose READY posts permanently (cursor advances
  past unprocessed rows) or double-process them (two cursors race,
  both win locally, both advance). The acceptance items pin the
  exact SQL shapes from `docs/design/02-schema.md` §2.9.2 verbatim
  so a regression here is caught at review time. The per-table
  GRANT discipline is also a redteam-relevant surface: a Provider
  with `DELETE` privilege could drop the cursor row and force a
  full historical replay on every restart; the explicit
  `REVOKE DELETE` closes that path.
- **The `new_post` cursor is the inter-service correctness
  guarantee.** Per `docs/spec/architecture.md` §Architectural
  principles 2, "Outbox + LISTEN/NOTIFY + high-water mark.
  Postgres provides durability and push semantics without an
  external broker." NOTIFY is the latency optimization; the
  high-water mark is the correctness guarantee. If NOTIFY is
  dropped on a connection blip, the next reconciler run catches
  up; if NOTIFY is delivered twice, the CAS no-op rejects the
  duplicate. The two halves work together — neither is sufficient
  alone.
- **The Provider role does NOT have INSERT on `post`.** The
  reconciler reads `post` via SELECT only. If a future ticket
  adds a Provider-side path that writes to `post` (e.g. an admin
  `/quarantine approve` flow that lifts redactions), that ticket
  is responsible for re-checking the grant matrix; this ticket
  cements the read-only contract for the `new_post` reconciler.
- **Channel-agnostic shape, per-channel interpretation.** The
  `(channel, cursor_high, cursor_low_kind, cursor_low_id)` shape
  works for `new_post` (cursor_low_kind = 'post', cursor_low_id =
  post id), `quarantine_review` (cursor_low_kind ∈
  {'quarantine', 'post'}, per the channel's tagged payload), and
  any future channel that needs a compound cursor. The
  schema-level shape is the cross-channel commitment; this ticket
  seeds the `new_post` row and leaves the shape ready for M2's
  `quarantine_review` reconciler to add its own row on first boot.
- **No NOTIFY on `provider_state` itself.** The cursor row is read
  on startup (reconciler) and on every NOTIFY/catch-up event
  (handler); there is no NOTIFY trigger on `provider_state` updates.
  The Collector has no need to be woken when the cursor advances —
  it neither reads nor cares about the value.
- **Subticket isolation against M1-028.** M1-028 lives under
  `infochat-collector/.../outbox/` and `infochat-collector/.../fetch/`.
  This ticket lives under `infochat-provider/.../outbox/`. The two
  `files_scope` lists are disjoint at the file path level. The
  tickets are runnable in parallel once both have started — neither
  depends on the other's code at compile time (the only shared
  artifact is the NOTIFY payload format, which is a string contract
  on the wire; the parser here and the emitter in M1-028 each carry
  their own copy of the format helper).
- **`@Priority(250)` is the design-tier commitment.** Per
  `docs/design/01-architecture.md` §1.4.3 Provider table, the bean
  ordering is 50 (InstanceLockGuard) → 100 (Flyway) → 200
  (AdminBootstrap) → 250 (NewPostReconciler) → 300 (AdapterRegistry)
  → 400 (CommandRouter). The reconciler MUST run before
  `AdapterRegistry` so the first inbound message after startup
  sees a caught-up Provider; the listener (started after the
  reconciler) means the very first `READY` post the Provider sees
  in steady-state is reached via NOTIFY, not via the next restart.
- **The handler stub is intentional and bounded.** T1-F wires the
  real consumers. The stub's contract (log + advance) is enough to
  make the catch-up + listener correctness story testable in
  isolation; the test suite asserts the cursor advances, the
  handler is invoked once per event, and duplicate events are
  rejected at the cursor level. The "what does the system do with
  a new post" semantics — cache invalidation, group-digest
  recompute, periodic-digest cache flush — are T1-F territory and
  attach to this handler in a focused diff.

## Out-of-scope expansion

- **`new_price_snapshot` channel listener, asset cache, or
  `(asset, sub_verb)` cache invalidation.** Tier-2 (T2-H, decision
  D39). That channel is best-effort and does NOT maintain a
  `provider_state` row per `docs/design/02-schema.md` §2.9.1 — the
  correctness mechanism is "flush-on-Postgres-reconnect", not a
  high-water mark. This ticket does NOT seed a `new_price_snapshot`
  row in the migration and does NOT add a listener for it.
- **`quarantine_review` channel listener, reconciler, or admin
  notifier.** M2 territory per `docs/design/01-architecture.md`
  §1.5 ("M1 only ships the `new_post` reconciler; the
  `quarantine_review` reconciler lands in M2 alongside the admin
  quarantine-review commands"). The migration does NOT seed a
  `quarantine_review` row.
- **Real cache-invalidation, periodic-digest recompute, or T1-F
  consumer logic inside `NewPostHandler`.** T1-F territory. This
  ticket ships the STUB handler whose side effect is the log
  line; the cursor-advance invariant lives inside the
  `@Transactional` boundary so T1-F's additions don't require a
  refactor.
- **Collector-side outbox emit, `PostPersister`,
  `EvalQueueProducer`, `FetchScheduler`, or `OutboxRehydrator`.**
  M1-028 territory. The two T1-C tickets share no runtime code.
- **Actual `post.status → READY` transition logic.** T1-D
  territory. T1-D's eval pipeline stage 5
  (`docs/design/01-architecture.md` §1.3.4 step 5) sets
  `status='READY'`, `ready_at=now()`, and emits the
  `pg_notify('new_post', payload)`. This ticket's tests INSERT
  READY rows directly via JDBC and emit NOTIFY from the test
  harness; no application code in this ticket flips the status.
- **Messaging adapter, CommandRouter, `/help`, or first-command
  implementation.** T1-E and T1-F territory. The Provider in this
  ticket is "headless" — it boots, reconciles, listens, and logs;
  it does not yet talk to any user.
- **Stage 1 / Stage 2 / tagger / entity-extractor /
  embedding-worker logic.** T1-D territory.
- **Modifications to V1..V8 migrations.** Frozen. This ticket
  adds V9 only.
- **Java entity classes, Panache mappings, or JPA repositories**
  for `post` or `provider_state`. Raw JDBC only — the cursor row
  has two SQL shapes and the catch-up scan is one SELECT; an ORM
  layer is overkill and obscures the compound-cursor CAS.
- **Any `infochat-collector` module change.** This ticket is
  Provider-side only.

## Authorized test changes

- (none — this ticket adds three new IT files under
  `infochat-provider/src/test/java/io/infochat/provider/outbox/` and
  one Flyway migration under `infochat-core`. No pre-existing tests
  are modified. The V9 migration applies cleanly alongside V1..V8;
  `FlywayMigrationIT` (M1-017) continues to pass without edit.)

## Alternatives considered

- **Option B: one combined ticket covering both T1-C halves.**
  Rejected at the top of this authoring session per the operator's
  handoff. Bundling Provider catch-up and Collector outbox into one
  ticket forces the reviewer to chase two unrelated correctness
  arguments (cursor-CAS semantics + persist-before-enqueue) in a
  14-16-file diff. Two ~7-8-file tickets fit the medium-complexity
  profile cleanly and let the two halves be implemented in either
  order.
- **Use `pgjdbc-ng` for async LISTEN/NOTIFY.** Rejected: the
  project's `pom.xml` already pulls in `org.postgresql:postgresql`
  via the Quarkus pgsql connector; adding pgjdbc-ng would introduce
  a parallel driver dependency and cross-driver connection-pool
  ambiguity. The standard pgjdbc driver's
  `PGConnection.getNotifications(timeoutMs)` is a blocking call
  that fits cleanly into a JDK 25 virtual-thread worker —
  ergonomic, no extra dep.
- **Read-and-process all catch-up rows inside one big DB
  transaction.** Rejected: a single transaction across N catch-up
  rows holds an `UPDATE` lock on `provider_state` for the duration
  of the entire scan; a duplicate NOTIFY arriving mid-catch-up
  blocks until the bulk commit. Per-row transactions are the
  correct shape — each row's side effect and cursor advance are
  atomic, the next row's CAS reads the just-committed cursor, and
  concurrent NOTIFY arrivals are short-circuited by the CAS no-op
  rather than blocked.
- **Skip the migration's first-boot insert and rely on a
  Provider-side `@Startup` guard.** Rejected: the migration's
  `INSERT … ON CONFLICT (channel) DO NOTHING` is the schema-layer
  defense and is the simplest possible shape. A Java-side guard
  would still need the same `ON CONFLICT` to be race-safe; doubling
  the guard is fine, but moving it out of the migration adds a
  startup ordering concern (the reconciler at `@Priority(250)`
  must run after the guard) that the migration-level insert
  eliminates.
- **`cursor_high`-only CAS predicate (drop the compound shape).**
  Rejected on spec grounds. Per `docs/spec/schema.md` §Operational:
  "The compound cursor (not `cursor_high` alone) ensures two events
  sharing a high-key value are both processed on catch-up — the
  earlier event advances the mark to itself, the later event
  advances it to itself in the same transaction as its side
  effect." Two `READY` posts with identical `ready_at` (clock
  resolution, batch transitions, partition boundary) would
  silently lose one to a `cursor_high`-only predicate. The
  compound-cursor predicate is non-negotiable.
- **Store the cursor as a single composite column type (e.g.
  `JSONB` or a PostgreSQL composite type).** Rejected: the four
  separate columns (`cursor_high`, `cursor_low_kind`,
  `cursor_low_id`, `updated_at`) match the spec's per-channel
  cursor interpretation table directly and let the CAS predicate
  use the standard SQL tuple-`<`-comparison without a custom
  operator. A JSONB cursor would require a comparison function and
  obscure the predicate shape that's load-bearing for the
  same-transaction invariant.
- **Emit the NOTIFY payload as a compact `<iso8601>|<uuid>` string
  instead of JSON.** Acceptable but the M1-028 emit MUST match;
  the shared format helper class is the integration point. JSON
  was picked here because cursor extensions in future channels
  (e.g. `quarantine_review`'s tagged shape) extend cleanly without
  a wire-format break; a positional string format would force a
  new format for every cursor shape. The acceptance items accept
  either form so long as the helper class is shared.
- **Defer the `provider_state` migration to M1-028 (the Collector
  side).** Rejected on ownership grounds. The Provider owns the
  cursor row (per `docs/spec/security.md` §DB roles — the Provider
  has SELECT + INSERT + UPDATE); landing the migration with the
  Provider's first consumer keeps the schema commitment and the
  read/write code path in the same ticket. The Collector reads
  the row only for diagnostic purposes (T2-B or later).
