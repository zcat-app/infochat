---
id: M1-009
title: Advisory-lock single-instance enforcement + heartbeat
status: pending
created: 2026-05-11
last_updated: 2026-05-11
blocked_by:
  - M1-005
  - M1-006
files_budget: 8
files_scope:
  - infochat-collector/src/main/resources/db/migration/V3__heartbeat.sql
  - infochat-collector/src/main/java/io/infochat/collector/startup/InstanceLockGuard.java
  - infochat-collector/src/main/java/io/infochat/collector/startup/HeartbeatScheduler.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/java/io/infochat/provider/startup/InstanceLockGuard.java
  - infochat-provider/src/main/java/io/infochat/provider/startup/HeartbeatScheduler.java
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/pom.xml
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - any change to V1__init.sql or V2__roles.sql (those are committed; V3 is the only new migration)
  - any GRANT against any table other than `heartbeat` (per-entity-table grants ride with M1-008 subtickets)
  - any change to the three role principals from M1-006 (no new role, no role attribute change)
  - any `provider_state` or `collector_state` table (the spec mentions `provider_state` conceptually — the M1-008 schema umbrella owns that table; M1-009 introduces ONLY the `heartbeat` table)
  - any high-water-mark column / NewPostReconciler logic (M1-008 + provider-side reconciler ticket)
  - any LISTEN/NOTIFY channel registration (no `new_post` / `new_price_snapshot` / `quarantine_review` channel work — those land with their producing tickets)
  - any messaging adapter, scheduler-task other than the heartbeat tick, Flyway/datasource change, or LLM wiring
  - any `RestartPreventExitStatus` systemd unit file or scripts/ wrapper (the design ties exit code 42 to a unit file; the unit-file ticket lands with the operator-tooling slice and is not this ticket's responsibility — M1-009 commits only to System.exit(1) on lock-conflict, see Implementation notes)
  - any production code under `infochat-collector/src/main/java/` or `infochat-provider/src/main/java/` outside the two `startup/` packages listed in files_scope
acceptance:
  - "infochat-collector/src/main/resources/db/migration/V3__heartbeat.sql exists"
  - "V3__heartbeat.sql creates a `heartbeat` table with columns `service` (text, PRIMARY KEY), `host_id` (text NOT NULL), `pid` (integer NOT NULL), `last_seen_at` (timestamptz NOT NULL DEFAULT now()) — grep -E 'CREATE TABLE.*heartbeat' returns at least one match; grep -E 'PRIMARY KEY' returns at least one match; grep -E '\\bservice\\b' AND grep -E '\\bhost_id\\b' AND grep -E '\\bpid\\b' AND grep -E '\\blast_seen_at\\b' each return at least one match"
  - "V3__heartbeat.sql grants SELECT, INSERT, UPDATE on `heartbeat` to BOTH `infochat_collector` AND `infochat_provider` (grep -E 'GRANT .*ON heartbeat.* TO infochat_collector' returns at least one match; same for `infochat_provider`)"
  - "V3__heartbeat.sql does NOT grant DELETE on `heartbeat` to either application role (grep -iE 'GRANT.*DELETE.*ON heartbeat.*TO infochat_(collector|provider)' returns zero matches — only `infochat_admin` may delete heartbeat rows; the application code only INSERT-on-first-tick + UPDATE-on-subsequent-ticks)"
  - "infochat-collector/src/main/java/io/infochat/collector/startup/InstanceLockGuard.java exists, is annotated `@Startup` with `@Priority(50)` (matching docs/design/01-architecture.md §1.4.3 Collector table), and calls `pg_try_advisory_lock` with the hashtext-of-'infochat.collector' (grep -E 'pg_try_advisory_lock' returns at least one match; grep -E 'infochat\\.collector' returns at least one match; grep -E '@Priority\\(50\\)' returns at least one match)"
  - "infochat-provider/src/main/java/io/infochat/provider/startup/InstanceLockGuard.java exists, is annotated `@Startup` with `@Priority(50)`, and calls `pg_try_advisory_lock` with the hashtext-of-'infochat.provider' (mirror checks for the provider name)"
  - "BOTH InstanceLockGuard files: on lock-acquisition failure, log a fatal-level message that names the running instance's host_id, pid, and last_seen_at read from the heartbeat row, then call `Quarkus.asyncExit(1)` (grep -E 'asyncExit' returns at least one match per file; grep -E 'host_id|hostId' returns at least one match per file; grep -E 'last_seen_at|lastSeenAt' returns at least one match per file)"
  - "infochat-collector/src/main/java/io/infochat/collector/startup/HeartbeatScheduler.java exists, contains a method annotated `@Scheduled` with `every` configured from a property (grep -E '@Scheduled' returns at least one match; grep -E 'every' returns at least one match; grep -E 'infochat\\.heartbeat\\.interval' returns at least one match somewhere in the file's `every` expression or in a constant the annotation references)"
  - "infochat-provider/src/main/java/io/infochat/provider/startup/HeartbeatScheduler.java exists with the equivalent @Scheduled tick body"
  - "infochat-collector/src/main/resources/application.properties declares `infochat.heartbeat.interval` under the `%laptop` profile namespace (grep -E '^%laptop\\.infochat\\.heartbeat\\.interval' returns at least one match) — value is a duration like `5s`; the other three profile namespaces (`%vps`, `%pi`, `%remote-llm`) may carry the key now or defer to their feature tickets, see Implementation notes"
  - "infochat-provider/src/main/resources/application.properties declares the same `%laptop.infochat.heartbeat.interval` key"
  - "infochat-provider/pom.xml adds `quarkus-scheduler` (grep -E '<artifactId>quarkus-scheduler</artifactId>' returns at least one match) — M1-005's Provider pom did not include it"
  - "infochat-collector/pom.xml ALREADY contains `quarkus-scheduler` OR this ticket adds it (grep -E '<artifactId>quarkus-scheduler</artifactId>' returns at least one match; if M1-005 already added it, this ticket's diff to the collector pom is empty)"
  - "mvn -B verify from the repo root exits 0; the new IT (see test_plan) confirms (a) a single instance acquires the lock and writes its heartbeat row, and (b) a second test-time guard instance — simulated by calling the lock-acquisition routine twice in the same test JVM with two separate JDBC connections — observes false from the second attempt and logs the contention message"
  - "after `mvn -pl infochat-collector test`, grep -rE 'Migrated.*successfully.*V3' infochat-collector/target/surefire-reports/ returns at least one match"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/startup/InstanceLockGuardIT.java (@QuarkusTest: asserts on first guard activation the heartbeat row appears with this JVM's host_id/pid; calls `pg_try_advisory_lock` a second time via a fresh JDBC connection from the same test JVM and asserts the second call returns false; captures the log line and asserts it names the current holder's host_id and pid)
    - infochat-collector/src/test/java/io/infochat/collector/startup/HeartbeatSchedulerIT.java (@QuarkusTest: asserts last_seen_at advances after waiting one heartbeat interval — uses the laptop profile's small `5s` interval so the test runs in well under one minute)
    - infochat-provider/src/test/java/io/infochat/provider/startup/InstanceLockGuardIT.java (provider-side mirror, separate hash, separate row)
    - infochat-provider/src/test/java/io/infochat/provider/startup/HeartbeatSchedulerIT.java (provider-side mirror)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-collector/src/test/java/io/infochat/collector/config/InfochatProfileTest.java (M1-005)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-005)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/config/InfochatProfileTest.java (M1-005)
spec_refs:
  - docs/spec/architecture.md §Deployment topology (v1)
  - docs/design/01-architecture.md §1.4.3 Startup-bean ordering and single-instance enforcement
  - docs/design/07-deployment.md §7.8.5 Single-instance enforcement (pg_advisory_lock + heartbeat)
decision_refs:
  - D41

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-009: Advisory-lock single-instance enforcement + heartbeat

## Context

`docs/spec/architecture.md` §Deployment topology (v1) commits the system
to **exactly one Collector and exactly one Provider** per shared
Postgres (decision D41). That invariant is structural: a second
Collector would produce duplicate fetches, a second Provider would
duplicate periodic digests and race on the `LISTEN/NOTIFY` high-water
mark. The spec promises the invariant is **enforced**, not policy:
"makes 'exactly one' an enforced invariant, not a policy."

This ticket lands that enforcement. Each service acquires a named
`pg_advisory_lock` at `@Startup` priority 50 (per
`docs/design/01-architecture.md` §1.4.3), writes a heartbeat row
naming itself, and refuses to start if the lock is already held.
A `@Scheduled` task refreshes `last_seen_at` on the heartbeat row at
the per-profile interval so the loser of a conflict can read the
current holder's identity for a clear operator-facing log line.

The advisory lock is a *structural integrity boundary* preventing
dual-instance corruption of `LISTEN/NOTIFY` ordering and high-water-
mark invariants — which is why this ticket carries
`security_relevant: true`. The threat-actor pass should focus on the
race shape (TOCTOU between heartbeat-read and lock-acquire), the exit
path (cannot be turned off by a misconfigured property), and the
authorization surface of the `heartbeat` table (Provider must be
able to read both rows, but the application code must never DELETE).

## Definition of Done

- Flyway V3 migration creates a single `heartbeat` table — one row
  per service ("collector" / "provider") — with columns `service`
  (text PRIMARY KEY), `host_id` (text), `pid` (int), `last_seen_at`
  (timestamptz, default `now()`).
- The migration grants SELECT, INSERT, UPDATE on `heartbeat` to
  both `infochat_collector` and `infochat_provider`. **DELETE is
  not granted to either application role** — only the admin role
  may delete heartbeat rows (operator path), so an application bug
  cannot remove the contention fingerprint.
- Each service has an `InstanceLockGuard` bean under `startup/` with
  `@Startup` + `@Priority(50)`, matching the priority table in
  `docs/design/01-architecture.md` §1.4.3. The bean opens a JDBC
  connection, calls `pg_try_advisory_lock(hashtext('infochat.<service>'))`,
  and:
  - On success: upserts this instance's heartbeat row
    (`INSERT … ON CONFLICT (service) DO UPDATE SET host_id=…, pid=…,
    last_seen_at=now()`) and **holds the JDBC connection open for
    the lifetime of the JVM** so the advisory lock is not released
    by connection return-to-pool.
  - On failure (`pg_try_advisory_lock` returned `false`): reads the
    current heartbeat row, logs a fatal-level line naming the
    current holder's `host_id`, `pid`, and `last_seen_at`, then
    calls `Quarkus.asyncExit(1)`.
- Each service has a `HeartbeatScheduler` bean under `startup/`
  with a `@Scheduled(every = "{infochat.heartbeat.interval}")`
  method that updates `last_seen_at = now()` on this service's
  row.
- The `infochat.heartbeat.interval` property is declared under the
  `%laptop` profile namespace in both modules'
  `application.properties` with a small value (`5s`). The
  property's per-profile values for `%vps`, `%pi`, and
  `%remote-llm` may be added here as placeholders OR deferred to
  the feature tickets that consume those profiles — see
  Implementation notes for the call.
- `infochat-provider/pom.xml` adds the `quarkus-scheduler`
  extension (M1-005's Provider pom did not). If
  `infochat-collector/pom.xml` does not already carry it, this
  ticket adds it there too.
- `mvn -B verify` from the repo root exits 0; the new IT suite
  confirms (a) lock acquisition writes the heartbeat row, (b) a
  second acquire attempt in the same test JVM via a separate JDBC
  connection observes `false` and logs the conflict message
  naming the current holder, (c) the @Scheduled tick advances
  `last_seen_at`.

## Implementation notes

- **Hash function: `hashtext`, not a Java-side SHA-256 truncate.**
  `docs/design/07-deployment.md` §7.8.5 says "SHA-256-truncate-to-int8"
  is the conceptual specification; in practice Postgres' built-in
  `hashtext(text)` returns an int4 and the standard idiom is
  `pg_try_advisory_lock(hashtext('infochat.collector'))`. This
  keeps the hash computation server-side so two instances on
  different hosts always race for the same int. (Implementation
  choice consistent with the design intent — record this in the
  commit message under `Alternatives considered:` so the next
  reader doesn't go looking for SHA-256 code.)
- **Hold the JDBC connection open for the JVM lifetime.** Advisory
  locks are scoped to the *session* (Postgres backend), not to the
  transaction. If you acquire the lock, return the connection to
  the Quarkus Agroal pool, and the pool gives that connection to a
  different caller, the next operation on the same connection still
  sees the lock held — but if the pool *closes* the connection
  (idle-eviction), the lock is released and a second instance can
  acquire it. The clean shape is: a dedicated long-lived JDBC
  `Connection` that the `InstanceLockGuard` owns from acquire to
  JVM exit; pool idle-eviction must not touch it. The simplest
  implementation borrows a connection from the default datasource
  and never returns it — Quarkus' Agroal supports detached
  connections via `connection.close()` being suppressed (or use
  `DriverManager.getConnection(...)` directly with the same URL
  the datasource resolves to). Document the choice with a
  one-line comment in the bean explaining *why* the connection is
  long-lived.
- **Exit path: `Quarkus.asyncExit(1)`, not `System.exit(1)`.**
  The Quarkus runtime documents `Quarkus.asyncExit(int)` as the
  proper way to stop the application from a `@Startup` bean — it
  triggers a graceful shutdown of any beans already started. Use
  exit code `1` (the generic fatal); `docs/design/07-deployment.md`
  §7.8.5 mentions exit code `42` as a future systemd-coordinated
  refinement, but that ties to a `RestartPreventExitStatus=42` unit
  file that does not yet exist. Use exit code 1 in v1; the unit-
  file ticket later swaps it. Record this in the commit message so
  the future reader can connect the dots.
- **Reading the heartbeat row on conflict.** The losing instance
  must read `heartbeat WHERE service = '<this>'` to populate the
  fatal log line. The read happens AFTER `pg_try_advisory_lock`
  returns false but BEFORE the exit call. Use the same JDBC
  connection that did the `try` — it does not yet hold the lock,
  and reading the row does not need to.
- **`@Priority(50)` matters.** Quarkus runs lower-priority
  `@Startup` beans first. Priority 50 is below Flyway's implicit
  100 (`docs/design/01-architecture.md` §1.4.3) — wait, that's
  inverted. **Re-reading the design:** "Quarkus runs Flyway
  migrations before any `@Startup` bean" — Flyway is upstream of
  all `@Startup` beans regardless of priority. So the priority
  ordering matters only between this bean and other `@Startup`
  beans, which (in M1-009's scope) are none yet. The priority 50
  is the design-committed value; honor it so later beans
  (BootstrapLoader at 200, etc.) land in the documented order.
- **Per-profile heartbeat interval.** The `%laptop` value is
  required in this ticket (the test suite runs under the laptop
  profile, see M1-005's `%test.quarkus.profile=laptop` wiring).
  The `%vps`, `%pi`, `%remote-llm` values are design-tier choices
  per `docs/design/07-deployment.md` §7.2.1 (the design suggests
  10s, 30s, 10s respectively). It is acceptable EITHER to land
  all four namespaces in this ticket with those design-suggested
  values, OR to land only `%laptop` here and defer the other
  three to a profile-tuning ticket. The acceptance criteria only
  require `%laptop` for both modules; the developer chooses
  whether to land the other three values now. Keep the choice
  consistent across both modules (don't land `%vps` for one but
  not the other).
- **What the scheduler does NOT do.** The scheduler updates
  `last_seen_at`. It does NOT delete stale rows belonging to
  *other* services — the spec language treats stale rows as
  *operator-visible*, not auto-cleaned. A stale heartbeat means
  the prior holder died ungracefully; the next start's
  `pg_try_advisory_lock` will succeed (because the session
  holding the lock died too) and overwrite the row via the
  `ON CONFLICT` upsert. There is no separate "cleanup stale
  rows" path and adding one would be scope drift.
- **Test design note: two acquire attempts in one JVM.** Real
  enforcement is two *processes*. For the IT, simulate by opening
  two separate JDBC connections from the same test JVM and calling
  `pg_try_advisory_lock` on each; advisory locks are session-
  scoped so the second connection observes `false`. The test
  asserts the second return value AND captures the bean's log
  output to confirm the contention message names the holder
  identity. This is sufficient evidence that the production code
  path works — full multi-JVM enforcement is implicit in the
  Postgres-session semantics, not in the bean's code.

## Big-picture notes

- **Internal design inconsistency to note.** `docs/design/01-architecture.md`
  §1.4.3 names a single `service_heartbeat(service, host_id,
  last_beat_at)` table, while `docs/design/07-deployment.md`
  §7.8.5 names separate `provider_state` / `collector_state`
  tables with a richer column set (`holder_pid`,
  `holder_started_at`, `last_heartbeat_at`). The planning team
  resolved this inconsistency in M1-009's favour: a single
  `heartbeat` table with the column set in `acceptance` above.
  A future spec amendment will reconcile the two design notes;
  in the meantime this ticket is the authoritative shape. Note
  the inconsistency in the commit message and consider filing a
  small "reconcile §1.4.3 vs §7.8.5 heartbeat shape" docs ticket
  after the commit lands.
- **`provider_state` is a separate concept.** The spec mentions
  contention on `provider_state` (§Deployment topology). That is
  the Provider's high-water-mark table (M1-008 territory), NOT
  the heartbeat table. M1-009 does NOT create `provider_state`.
  The naming overlap with `docs/design/07-deployment.md` §7.8.5
  is part of the inconsistency above.
- **Forward-compatibility with the exit-code-42 unit file.** The
  design promises `RestartPreventExitStatus=42` so systemd does
  not restart the loser. This ticket uses exit code 1 because
  the systemd unit file is operator-tooling work owned by a
  later ticket. The eventual unit-file ticket will swap this
  bean's exit code; keep the call site centralised so the swap
  is a one-line change.
- **The lock is the integrity boundary; the heartbeat is the
  fingerprint.** A future reader may wonder why both exist when
  the lock alone enforces single-instance. The answer: the lock
  enforces; the heartbeat *names* the holder so the loser can
  log a useful message. Without the heartbeat row, the loser's
  fatal log is "another instance has the lock" with no
  identifying information — useless for an operator debugging a
  rolling-deploy hiccup.
- **Threat-actor scope on the redteam pass.** The lock is a
  promised structural integrity defense. The pass should look
  for: (a) any path that releases the lock prematurely
  (connection-pool eviction, transaction-rollback, accidental
  `pg_advisory_unlock` call); (b) any path where the heartbeat
  row write can fail silently while the lock is held (which
  would leave the loser with a stale or empty fingerprint to
  log); (c) any code path that bypasses the guard via a feature
  flag or property (none should exist — the guard is
  unconditional).

## Out-of-scope expansion

- **Edits to V1__init.sql or V2__roles.sql.** Both are committed.
  V3 is the only new migration.
- **`provider_state` table.** M1-008 territory. The naming
  overlap with `docs/design/07-deployment.md` §7.8.5's
  `provider_state` is a docs inconsistency (see Big-picture notes),
  not a directive to create the table here.
- **NewPostReconciler / high-water-mark replay.** M1-008 +
  provider-reconciler ticket. The heartbeat row written here is
  unrelated to the high-water mark.
- **LISTEN/NOTIFY channel registration.** The `new_post`,
  `new_price_snapshot`, and `quarantine_review` channels land
  with their producing tickets (ingest, asset commands, quarantine).
- **`RestartPreventExitStatus=42` systemd unit file.** Owned by
  the operator-tooling ticket. This ticket uses exit code 1; the
  unit-file ticket swaps it. See Implementation notes.
- **Auto-cleanup of stale heartbeat rows.** Out of scope. Stale
  rows are operator-visible signal; the next successful start
  upserts them.
- **GRANTs on tables other than `heartbeat`.** Per-table grants
  ride with the per-table CREATE migrations (M1-008).
- **Production code outside the two `startup/` packages.** This
  ticket is migration + lock-guard + scheduler + property keys
  only.

## Authorized test changes

- (none — this ticket adds four new IT files and modifies no
  existing test. M1-005's `FlywayMigrationIT` continues to assert
  V1 APPLIED and is unaffected by V3's additional schema-history
  row. M1-006's `DbRoleMatrixIT` continues to assert the three
  roles exist; V3's grants reference those roles by name and
  Flyway will fail-fast if a role is missing, so a regression in
  M1-006 would surface here as a Flyway error rather than a
  silent grant-against-nonexistent-role.)

## Alternatives considered

- **Use `pg_advisory_lock` (blocking) instead of
  `pg_try_advisory_lock`.** Rejected: a blocking acquire would
  hang the second instance's startup indefinitely, masking the
  misconfigured deploy rather than failing fast. The spec
  language ("fails fast with a fatal log message") commits to
  the non-blocking shape.
- **Acquire the lock inside Flyway's `beforeMigrate` hook so the
  guard runs even earlier.** Rejected: the design priority table
  puts the guard at @Startup 50, *after* Flyway runs at 100.
  Reordering would mean lock-acquisition before V3 has run,
  which would race the first-ever bootstrap of the heartbeat
  table on a fresh DB. The post-Flyway shape is the safe one.
- **Skip the heartbeat table; rely on `pg_stat_activity` for
  holder identity.** Rejected: `pg_stat_activity` rows expose
  the connecting role and client_addr but not the JVM's host_id
  or pid in a portable way; reading them requires elevated
  Postgres privileges that the Provider role does not have
  (§DB roles); and the operator-visible signal would disappear
  the moment the holder's session ends. The heartbeat row
  survives session loss and is the right durable fingerprint.
- **Use a database transaction-scoped lock
  (`pg_advisory_xact_lock`).** Rejected: transaction-scoped
  locks release at COMMIT, which means the lock is gone before
  the JVM is even past `@Startup`. Session-scoped is what the
  invariant needs.
- **Land the systemd unit file with exit code 42 in this
  ticket.** Rejected: unit files are operator tooling, not
  application code; bundling them here triples the diff and
  pulls in `/etc/systemd/system/`-shaped questions that are
  irrelevant to the single-instance invariant itself. The
  exit-code value is a one-line change when the unit-file
  ticket lands.
- **Single `heartbeat` table vs. separate `collector_heartbeat`
  + `provider_heartbeat` tables.** A single table with a
  `service` PK is simpler (one V3 migration, one grant block,
  one bean shape parameterised over service name) and matches
  `docs/design/01-architecture.md` §1.4.3's `service_heartbeat`
  shape. Separate tables would mirror
  `docs/design/07-deployment.md` §7.8.5 more literally but
  double the surface for no integrity benefit — both rows are
  read/written in the same ways. Chose the single-table form.
