---
id: M1-008a
title: Identity, audit, last-admin trigger (§2.1)
status: pending
created: 2026-05-13
last_updated: 2026-05-13
blocked_by:
  - M1-005
  - M1-006
  - M1-017
files_budget: 8
files_scope:
  - infochat-core/pom.xml
  - infochat-core/src/main/resources/db/migration/V5__identity_audit.sql
  - infochat-core/src/test/java/io/infochat/core/schema/PostgresSchemaTestBase.java
  - infochat-core/src/test/java/io/infochat/core/schema/LastAdminTriggerTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/LastAdminConcurrentRevocationTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/AuditLogAppendOnlyTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/GroupAdminUniqueIndexTest.java
  - infochat-core/src/test/java/io/infochat/core/schema/DeletePrebanUserTest.java
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: true
out_of_scope:
  - infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java (the M1-008 umbrella's cross-cutting per-(user, scope) isolation IT — reserved for the umbrella commit per docs/process/workflow.md §Ticket-ID placeholder convention; this subticket asserts trigger correctness only, the cross-table invariant lives there)
  - any change under infochat-core/src/main/resources/db/migration/V6__*.sql or V7__*.sql (the sources/tags catalogues + joins/post tables ship in M1-008b / M1-008c respectively; this subticket is identity + audit only)
  - any Java entity class, Hibernate / Panache mapping, repository, service, or DAO (NO application code — Flyway migrations + per-table GRANTs + trigger bodies + SQL-level tests ONLY; the entity layer lands in later T1-B/C/D/E/F tickets)
  - any LISTEN/NOTIFY trigger or channel wiring (the outbox NOTIFY plumbing is T1-C / a later ticket; audit_log carries no NOTIFY in v1)
  - any Provider startup logic that consumes the schema (the @Startup admin-bootstrap bean per docs/spec/deployment.md §Bootstrap behavior is a separate Provider-side ticket; this subticket creates the table the bootstrapper will later write to)
  - any audit-log row writer or redaction-hook Java code (the redactor lives at the application layer; the closed catalogue of regex shapes per docs/spec/security.md §Secrets handling is implemented in the audit-write path, not in the schema migration)
  - any signal-cli / SimpleX-CLI / inmemory adapter-side identity wiring (the adapter integration that resolves contact_id → users row lives in the T1-E messaging tickets)
  - any retention sweep / pruner / GC schedule against audit_log or users (admin-driven retention runs under the infochat_admin role and is operator-side per docs/spec/security.md §DB roles; the schema commitment here is the append-only trigger guard plus the role grants)
  - any change to the V1..V4 migrations already on disk (M1-005, M1-006, M1-009, M1-016 own those; this subticket adds V5 only)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V5__identity_audit.sql exists and contains CREATE TABLE statements for all of: users, groups, group_membership, invite_code, audit_log (grep -E 'CREATE TABLE\\s+(users|groups|group_membership|invite_code|audit_log)' V5 returns exactly five matches)"
  - "V5 declares the (adapter, contact_id) UNIQUE on users — grep -E 'UNIQUE\\s*\\(\\s*adapter\\s*,\\s*contact_id\\s*\\)' returns at least one match (decision D46)"
  - "V5 declares the registration_state CHECK constraint with the closed four-value set — grep -E \"registration_state\\s+IN\\s*\\(\\s*'preban'\\s*,\\s*'group_only'\\s*,\\s*'invited'\\s*,\\s*'vouched'\\s*\\)\" returns at least one match (decisions D44, D45)"
  - "V5 declares the partial unique index enforcing Invariant 3 (at most one group admin per group) — grep -E 'CREATE UNIQUE INDEX\\s+\\w+\\s+ON\\s+group_membership\\s*\\(group_id\\)\\s+WHERE\\s+is_group_admin' returns at least one match"
  - "V5 declares the last-admin protection trigger function and binds it to BOTH the BEFORE UPDATE and BEFORE DELETE paths on users — grep -E 'CREATE TRIGGER\\s+\\w+\\s+BEFORE\\s+(UPDATE|DELETE)\\s+ON\\s+users' returns exactly two matches"
  - "V5's last-admin trigger function body contains LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE (the serialization requirement of Invariant 2) — grep -E 'LOCK\\s+TABLE\\s+users\\s+IN\\s+SHARE\\s+ROW\\s+EXCLUSIVE\\s+MODE' returns at least one match"
  - "V5 declares the audit_log append-only trigger that REJECTs any UPDATE or DELETE on audit_log (Invariant 10) — grep -E 'CREATE TRIGGER\\s+\\w+\\s+BEFORE\\s+(UPDATE|DELETE)\\s+ON\\s+audit_log' returns exactly two matches AND the function body contains a RAISE EXCEPTION line (grep -E 'RAISE EXCEPTION' returns at least one match in V5)"
  - "V5 declares the audit_log.target_kind CHECK constraint with the nine-value spec-closed set — grep -E \"target_kind\\s+IN\\s*\\(\\s*'user'\\s*,\\s*'group'\\s*,\\s*'source'\\s*,\\s*'post'\\s*,\\s*'invite'\\s*,\\s*'quarantine'\\s*,\\s*'asset'\\s*,\\s*'memory'\\s*,\\s*'system'\\s*\\)\" returns at least one match"
  - "V5 declares the closed set of audit_log.action verbs by emitting them in the migration's per-verb commentary (one comment line per verb, grep-checkable) — grep -cE '^\\-\\-\\s+(BOOTSTRAP_ADMIN|BOOTSTRAP_SOURCE_LOAD|BOOTSTRAP_ASSET_LOAD|GRANT_ADMIN|REVOKE_ADMIN|BAN|UNBAN|UNBAN_PREBAN_DELETE|VOUCH|INVITE_CREATE|INVITE_REVOKE|INVITE_CONSUME|PROMOTE_GROUP_ADMIN|DEMOTE_GROUP_ADMIN|ADD_SOURCE|REMOVE_SOURCE|SOURCE_ENABLE|SOURCE_DISABLE|APPROVE_QUARANTINE|REJECT_QUARANTINE|FORGET|SET_LANG|SET_TIMEZONE)\\b' V5 returns >= 23 (the count in docs/design/02-schema.md §2.1.8)"
  - "V5 declares the delete_preban_user(UUID, UUID) stored procedure and grants EXECUTE to infochat_provider — grep -E 'CREATE\\s+(OR REPLACE\\s+)?PROCEDURE\\s+delete_preban_user' returns at least one match AND grep -E 'GRANT\\s+EXECUTE\\s+ON\\s+PROCEDURE\\s+delete_preban_user' returns at least one match"
  - "V5 declares the audit_log_view and grants SELECT on it to infochat_provider (NOT on audit_log) — grep -E 'CREATE\\s+(OR REPLACE\\s+)?VIEW\\s+audit_log_view' returns at least one match AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+audit_log_view\\s+TO\\s+infochat_provider' returns at least one match"
  - "V5 grants are aligned with docs/spec/security.md §DB roles — grep -E 'GRANT\\s+INSERT(\\s*,\\s*\\w+)*\\s+ON\\s+audit_log\\s+TO\\s+infochat_(collector|provider)' returns at least two matches (one per role; INSERT-only on the raw audit_log table) AND grep -E 'GRANT\\s+SELECT\\s+ON\\s+audit_log\\s+TO\\s+infochat_provider' returns ZERO matches (the Provider's read path is the view, not the raw table)"
  - "infochat-core/pom.xml adds test-scope dependencies on Testcontainers Postgres, the Postgres JDBC driver, and Flyway core (grep -E '<artifactId>(testcontainers|postgresql|flyway-core|flyway-database-postgresql)</artifactId>' infochat-core/pom.xml returns at least three matches across the listed artifacts)"
  - "infochat-core/pom.xml configures maven-failsafe-plugin so *IT tests under infochat-core run during mvn verify (grep -E '<artifactId>maven-failsafe-plugin</artifactId>' returns at least one match in infochat-core/pom.xml) — this is the wiring stub the M1-008 umbrella IT depends on; this subticket authors it so the umbrella's diff stays at the locked 2-file budget"
  - "LastAdminTriggerTest.java exercises the single-transaction revoke path: a transaction that sets is_admin=FALSE on the only is_admin=TRUE row raises an exception containing the literal substring 'last_admin_protection' (grep -E 'last_admin_protection' in the test file returns at least one match AND the test asserts on the raised exception message)"
  - "LastAdminConcurrentRevocationTest.java opens TWO JDBC Connections against the testcontainers Postgres, seeds exactly two is_admin=TRUE rows, then runs two concurrent transactions each revoking a different admin, and asserts that EXACTLY ONE commits while the other raises 'last_admin_protection' (grep -E 'Connection\\s+\\w+1|Connection\\s+\\w+2|getConnection' returns at least two matches across the file; the test asserts the final SELECT COUNT(*) WHERE is_admin = TRUE AND is_banned = FALSE is 1, not 0)"
  - "AuditLogAppendOnlyTest.java asserts that an UPDATE or DELETE against audit_log raises an exception (the Invariant-10 trigger guard fires) — grep -E 'UPDATE\\s+audit_log|DELETE\\s+FROM\\s+audit_log' returns at least one match in the test, and the test asserts the SQLException message contains 'append-only'"
  - "GroupAdminUniqueIndexTest.java asserts that inserting a SECOND is_group_admin=TRUE row for the same group raises a unique-violation exception (the partial unique index from Invariant 3 fires)"
  - "DeletePrebanUserTest.java exercises the carve-out: the CALL delete_preban_user($id, $actor) on a row with registration_state='preban' succeeds AND writes an audit_log row with action='UNBAN_PREBAN_DELETE'; calling it against a non-preban row raises an exception with the literal substring 'is not in preban state'"
  - "mvn -B -pl infochat-core -am test exits 0; surefire reports for infochat-core show at least one test executed per the five new *Test.java files (grep -rE 'Tests run: [1-9]' infochat-core/target/surefire-reports returns at least five matches across the test classes)"
  - "mvn -B clean verify from the repo root exits 0; the existing M1-003 @QuarkusTest stubs, M1-007a IngestSpisLoadTest, and every M1-007 cross-module test continue to pass alongside the new V5 schema and its trigger tests"
test_plan:
  adds:
    - infochat-core/src/test/java/io/infochat/core/schema/PostgresSchemaTestBase.java (Testcontainers + Flyway boot helper; provides a Connection-yielding fixture the schema-level tests share, so each test class doesn't re-spin its own container)
    - infochat-core/src/test/java/io/infochat/core/schema/LastAdminTriggerTest.java (single-tx revoke + single-tx ban-the-only-admin paths; both must raise the trigger exception)
    - infochat-core/src/test/java/io/infochat/core/schema/LastAdminConcurrentRevocationTest.java (two-connection race: seed two admin rows, both transactions revoke different rows, exactly one commits per Invariant 2 serialization)
    - infochat-core/src/test/java/io/infochat/core/schema/AuditLogAppendOnlyTest.java (UPDATE and DELETE against audit_log both raise the Invariant-10 trigger guard)
    - infochat-core/src/test/java/io/infochat/core/schema/GroupAdminUniqueIndexTest.java (Invariant 3 partial unique index rejects a second is_group_admin row for the same group)
    - infochat-core/src/test/java/io/infochat/core/schema/DeletePrebanUserTest.java (carve-out happy path + non-preban rejection; verifies audit-before-effect by reading the audit_log row written by the procedure)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-core/src/test/java/io/infochat/core/ingest/IngestSpisLoadTest.java (M1-007a)
    - infochat-llm-adapter/src/test/java/io/infochat/llm/LlmSpisLoadTest.java (M1-007b)
    - infochat-messaging-adapter/src/test/java/io/infochat/messaging/MessagingSpisLoadTest.java (M1-007c)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
spec_refs:
  - docs/spec/schema.md §Identity and access
  - docs/spec/schema.md §Operational
  - docs/spec/schema.md §Invariants
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §Authorization model
  - docs/spec/security.md §Trust boundaries
  - docs/design/02-schema.md §2.1 Identity & access
  - docs/design/02-schema.md §2.1.1 users
  - docs/design/02-schema.md §2.1.2 Last-admin protection trigger
  - docs/design/02-schema.md §2.1.3 groups
  - docs/design/02-schema.md §2.1.4 group_membership
  - docs/design/02-schema.md §2.1.5 invite_code
  - docs/design/02-schema.md §2.1.6 delete_preban_user
  - docs/design/02-schema.md §2.1.7 audit_log
  - docs/design/02-schema.md §2.1.8 audit_log.action enum
  - docs/design/02-schema.md §2.1.9 audit_log_view
decision_refs:
  - D44
  - D45
  - D46
---

# M1-008a: Identity, audit, last-admin trigger (§2.1)

## Context

First subticket of the M1-008 umbrella (per `docs/process/workflow.md`
§Ticket-ID placeholder convention — the umbrella + subticket idiom).
M1-008 splits "land the MVP schema" into three substantively-disjoint
slices plus a whole-topic per-(user, scope) isolation IT on the
umbrella. This subticket lands the foundational `§2.1 Identity &
access` slice: the `users` row, the `groups` / `group_membership`
join with the at-most-one-group-admin partial unique index
(Invariant 3), the `invite_code` table that backs the DM invite
gate (decision D44), the `delete_preban_user` stored procedure that
implements the single Invariant 2 carve-out, the `audit_log` table
with its append-only Invariant 10 guard, the closed `audit_log.action`
verb set, and the `audit_log_view` redacted Provider read path.
M1-008b adds sources/tags; M1-008c adds joins + post; M1-008's
umbrella ships the cross-cutting isolation IT.

This is the load-bearing slice for the bot's authorization model.
**Invariant 2's last-admin protection trigger** is the only barrier
against an "admin-empty deployment" — a misconfigured `/revoke-admin`
or `/ban` against the last admin would leave the deployment
unrecoverable without operator-side `psql`. The trigger MUST be
trigger-layer (not just command-layer) per `docs/spec/schema.md`
§Invariants — Invariant 2, AND it MUST serialize concurrent
revocations via `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE` or
equivalent (Invariant 2's serialization clause). A naive `SELECT
COUNT(*) WHERE is_admin = true` under READ COMMITTED would allow
two concurrent transactions to both observe a pre-state of 2 and
both succeed, leaving zero admins.

This is a **schema-only** ticket. No Java entity classes, no
repositories, no services, no Provider startup logic. The
application layer lands in later Tier-1 tickets (`/grant-admin`,
`/ban`, the bootstrap-admin `@Startup` bean, the invite-code
intake path, the audit-log writer with its redaction hook). The
schema commitment here is what the application layer will write
to once it exists.

## Definition of Done

- A new Flyway migration
  `infochat-core/src/main/resources/db/migration/V5__identity_audit.sql`
  creates, in one transactional migration:
  - `users` with the (adapter, contact_id) UNIQUE per decision D46,
    the `registration_state` CHECK constraint over the closed
    four-value set per `docs/spec/schema.md` §Identity and access
    (Registration-state transitions), `probation_until` per decision
    D45, and the partial indices on `is_admin` and `is_banned`.
  - `groups` with the (adapter, upstream_group_id) UNIQUE, `timezone`
    default `'UTC'`, and the `removed_at` soft-clear column.
  - `group_membership` with the partial unique index
    `one_admin_per_group ON group_membership(group_id) WHERE
    is_group_admin = TRUE` (Invariant 3), the `removed_at` soft-clear
    column, and the `trg_clear_group_admin_on_remove` trigger that
    clears `is_group_admin` in the same transaction that sets
    `removed_at` (frees the partial-unique-index slot).
  - `invite_code` per decision D44 with the `invite_type` /
    `expected_contact_id` iff-CHECK, the `status` CHECK over the
    three-value set, and the partial index
    `idx_invite_code_pending(adapter, code) WHERE status = 'PENDING'`
    that backs the conditional-UPDATE consume.
  - The last-admin protection trigger function `trg_last_admin_protection_update`
    and a sibling `trg_last_admin_protection_delete`, BOTH containing
    `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE` at the top of
    the function body. The UPDATE trigger is bound to `BEFORE
    UPDATE ON users`; the DELETE trigger to `BEFORE DELETE ON users`.
    Each function counts `is_admin = TRUE AND is_banned = FALSE
    AND id <> NEW.id` (or `OLD.id` on the DELETE path) and raises
    an exception when the remaining count would fall below 1.
    Exception messages contain the literal substring
    `last_admin_protection` so tests can assert on it.
  - The `delete_preban_user(p_user_id UUID, p_actor_id UUID)`
    stored procedure with `LANGUAGE plpgsql`, `SECURITY DEFINER`,
    that (a) reads `registration_state` `FOR UPDATE`, (b) raises an
    exception containing `is not in preban state` when the row is
    not in `preban`, (c) writes an `audit_log` row with
    `action = 'UNBAN_PREBAN_DELETE'` (audit-before-effect, Invariant
    7), and (d) issues the `DELETE FROM users WHERE id = $1 AND
    registration_state = 'preban'`. `REVOKE ALL` from `PUBLIC`;
    `GRANT EXECUTE` to `infochat_provider`.
  - `audit_log` with the spec-closed `target_kind` CHECK over the
    nine values, the five indices listed in `docs/design/02-schema.md`
    §2.1.7, and the append-only trigger function
    `trg_audit_log_append_only` bound to BOTH `BEFORE UPDATE` and
    `BEFORE DELETE` paths. The function body raises
    `audit_log is append-only` (literal substring `append-only`).
  - A per-verb commentary block in the migration listing **every**
    action verb from `docs/design/02-schema.md` §2.1.8 as a SQL
    line-comment (`-- VERB_NAME`). This is grep-checkable and
    documents the closed enum at the migration layer; the CHECK
    constraint enforcement of `audit_log.action` is the
    application layer's job (the verb set is open-ended for v2
    additions; the schema does not pin it).
  - The `audit_log_view` view per `docs/design/02-schema.md` §2.1.9,
    using placeholder calls to `redact_contact_id(...)` and
    `redact_secrets_jsonb(...)` — the redactor function bodies are
    out of this ticket (they belong to the audit-write path's
    redaction-hook ticket) but the view DDL exists so the Provider
    role's `GRANT SELECT ON audit_log_view` resolves. **The
    redactor functions MUST be declared with stub bodies** (e.g.,
    `RETURN input;`) so the view is valid; the application-layer
    redactor ticket will `CREATE OR REPLACE FUNCTION` with the real
    body. This is the schema commitment that keeps the v1 grant
    matrix in `docs/spec/security.md` §DB roles structurally
    valid from day one.
  - **Per-table GRANTs** aligned with `docs/spec/security.md` §DB
    roles:
    - `users`, `groups`, `group_membership`: `SELECT, INSERT,
      UPDATE` to `infochat_provider` (Provider writes user state);
      `SELECT` to `infochat_collector` (read-only for ingest
      decisions). `DELETE` is **revoked** from both
      (per §DB roles — "DELETE on users is revoked from
      infochat_collector and infochat_provider"; the
      `delete_preban_user` stored proc is the single permitted
      DELETE path).
    - `invite_code`: `SELECT, INSERT, UPDATE` to
      `infochat_provider` (invite issuance + consume); no
      Collector access (Collector never touches invite codes).
    - `audit_log`: `INSERT` to both `infochat_provider` AND
      `infochat_collector` (both services emit audit rows). NO
      `SELECT` on the raw table for the Provider — that path is
      the view. `UPDATE` and `DELETE` are revoked from both
      service roles (Invariant 10).
    - `audit_log_view`: `SELECT` to `infochat_provider` only.
- `infochat-core/pom.xml` gains test-scope dependencies on:
  - `org.testcontainers:testcontainers`,
    `org.testcontainers:postgresql`, and
    `org.testcontainers:junit-jupiter` so the schema tests can
    spin up a real Postgres 16+ container.
  - `org.postgresql:postgresql` (test scope) for JDBC.
  - `org.flywaydb:flyway-core` plus
    `org.flywaydb:flyway-database-postgresql` (test scope) so the
    test helper can apply migrations to the container.
  - The `maven-failsafe-plugin` is wired so `*IT.java` files run
    under `mvn verify` (the M1-008 umbrella's
    `PerScopeIsolationIT` depends on this wiring; this subticket
    authors the wiring as part of its own pom.xml edit so the
    umbrella's diff stays at the locked 2-file budget).
- Five new SQL-level test classes under
  `infochat-core/src/test/java/io/infochat/core/schema/`:
  - `PostgresSchemaTestBase.java` — shared Testcontainers
    Postgres + Flyway-apply helper (`@Container` lifecycle, a
    `Connection` factory, a `truncateAll` reset between tests).
    Used by every schema-level test in this ticket and by the
    umbrella IT.
  - `LastAdminTriggerTest.java` — single-tx revoke and single-tx
    ban-the-only-admin paths; both must raise the trigger
    exception with `last_admin_protection` in the message.
  - `LastAdminConcurrentRevocationTest.java` — two-JDBC-connection
    race: seed exactly two admins, two transactions concurrently
    revoke different rows, exactly one commits per the
    SHARE ROW EXCLUSIVE serialization. The final
    `SELECT COUNT(*) WHERE is_admin = TRUE AND is_banned = FALSE`
    is asserted to be 1 (not 0 — that would be the bug Invariant
    2's serialization clause forbids).
  - `AuditLogAppendOnlyTest.java` — `UPDATE audit_log` and
    `DELETE FROM audit_log` both raise the Invariant-10 trigger
    guard; the SQLException message contains `append-only`.
  - `GroupAdminUniqueIndexTest.java` — inserting a second
    `is_group_admin = TRUE` row for the same group raises a
    unique-violation exception (Invariant 3's partial unique
    index fires).
  - `DeletePrebanUserTest.java` — the procedure deletes a
    `registration_state = 'preban'` row and writes the
    `UNBAN_PREBAN_DELETE` audit row in the same transaction;
    against a non-preban row it raises with `is not in preban
    state`.
- `mvn -B clean verify` from the repo root exits 0. All M1-003,
  M1-007, M1-007a/b/c tests continue to pass; the five new test
  classes execute against a Testcontainers Postgres and pass.

## Implementation notes

- **One migration file, one transaction.** Flyway runs each
  migration in a single transaction by default; the entire `V5`
  body — tables, triggers, views, GRANTs — applies atomically.
  Splitting it across `V5a__users.sql` / `V5b__groups.sql` etc. is
  rejected: the migrations are versioned by integer, and a
  cross-file partial failure leaves the schema in a half-applied
  state Flyway has no way to recover from. Keep it one file.
- **Migration version is V5.** V1..V4 already live on disk under
  `infochat-core/src/main/resources/db/migration/`:
  `V1__init.sql` (M1-005), `V2__roles.sql` (M1-006),
  `V3__heartbeat.sql` (M1-009), `V4__nologin.sql` (M1-016). The
  next free integer is V5. M1-008b will take V6 and M1-008c will
  take V7; do not collapse those ranges.
- **Triggers are the load-bearing safety surface.** The last-admin
  trigger function MUST `LOCK TABLE users IN SHARE ROW EXCLUSIVE
  MODE` at the top of its body — not after the count, not
  conditionally, not as a comment. The lock comes first so
  concurrent transactions serialize at the `LOCK` call and only
  then proceed to the count. The lock mode is `SHARE ROW
  EXCLUSIVE`: it blocks other writers (the property we need —
  another `/revoke-admin` transaction must wait) while still
  permitting concurrent `SELECT` (Provider's read paths against
  `users` must not stall during a revoke). `ACCESS EXCLUSIVE`
  would over-lock; `ROW EXCLUSIVE` (the default for `UPDATE`)
  under-locks and is exactly the unsafe case Invariant 2 forbids.
- **The DELETE trigger is defense-in-depth.** A `preban` row never
  has `is_admin = true` (bootstrap-seeded admins are `vouched`
  per `docs/spec/deployment.md` §Bootstrap behavior; pre-ban rows
  are minted by `/ban <unknown>` and never carry the admin flag),
  so the DELETE-path count check always passes for the
  `delete_preban_user` carve-out. But: an operator running raw
  SQL under the Admin role concurrent with a `/revoke-admin`
  could still trip Invariant 2 if the DELETE trigger weren't
  serializing on the same lock. The DELETE trigger exists so the
  carve-out plus operator-psql DELETE paths participate in the
  same serialization window.
- **The `delete_preban_user` procedure is `SECURITY DEFINER`.**
  The Provider role's grant matrix per `docs/spec/security.md` §DB
  roles **revokes** `DELETE ON users` — only the Admin role has
  raw DELETE. The procedure runs with the rights of its definer
  (Admin) so the Provider can invoke the carve-out path without
  carrying raw DELETE privilege. The Provider role gets `EXECUTE`
  on the procedure, nothing more.
- **The audit_log_view's redactor functions are stubs.** The view
  DDL references `redact_contact_id(text)` and
  `redact_secrets_jsonb(jsonb)`. The real implementations carry
  the closed regex catalogue from `docs/spec/security.md`
  §Secrets handling and live in a later application-tier ticket
  (the audit-write redactor hook). For this subticket, declare
  both functions with stub bodies (`RETURN input;` for the text
  variant, `RETURN input;` for the jsonb variant) and `CREATE OR
  REPLACE FUNCTION` so the application-tier ticket can supersede
  them without a schema migration. This keeps the v1 grant
  matrix structurally valid (the view is grantable, the Provider
  role can `SELECT` against it) from day one without pulling the
  redactor catalogue into this ticket's scope.
- **Per-table GRANTs land in the migration that creates the
  table.** M1-006 created the three roles (`infochat_collector`,
  `infochat_provider`, `infochat_admin`) but explicitly excluded
  per-table GRANTs from its scope (M1-006 §out_of_scope). Each
  schema-introducing subticket of M1-008 carries its own GRANTs
  in the same migration that creates the tables; this keeps the
  grant decisions colocated with the table DDL rather than
  buried in a separate "grants" migration.
- **No NOTIFY triggers here.** The outbox NOTIFY plumbing (a
  `new_post` trigger on `post`, etc.) is T1-C's territory.
  `audit_log` itself carries no NOTIFY in v1 — the Provider's
  `/audit` reads through the view on demand.
- **Testcontainers Postgres image.** Use the `pgvector/pgvector`
  image (or `postgres:16-alpine` with a `CREATE EXTENSION` step
  in the test helper) — `pgvector` is not exercised by V5 itself
  but later migrations (M1-008c's `post` partitioning and a
  later Tier-1 ticket's `post_embedding`) will need it, and
  baselining the test container on a pgvector-capable image now
  avoids a re-baseline later. The exact tag is impl-choice;
  document it inline in the `PostgresSchemaTestBase`.
- **Test container reuse.** `PostgresSchemaTestBase` should start
  ONE container per JVM (`@Container static`) and reset state
  between tests via `TRUNCATE` over the test's tables, not via
  re-spin. Re-spinning per test class would multiply the test
  suite's wall-clock cost by the number of test classes; with
  containers reused, each new class costs only the cost of a
  fresh `TRUNCATE`.
- **Concurrency test method.** Use `java.util.concurrent.Executors`
  + `CountDownLatch` so the two transactions reach `LOCK TABLE`
  simultaneously rather than racing through driver-side
  scheduling. Both threads call `Connection.setAutoCommit(false)`,
  perform their `UPDATE`, then sit on the latch before issuing
  `COMMIT`. After the latch releases, exactly one transaction
  commits cleanly; the other's commit raises an
  `SQLException` whose message contains
  `last_admin_protection`. The test asserts on the message and
  on the final `SELECT COUNT(*)`.
- **The `audit_log.action` verb catalogue is documented at the
  migration layer.** Per `docs/design/02-schema.md` §2.1.8 the
  v1 verb set is closed; extending it is a design-note edit.
  Encoding the set as a SQL `CHECK` constraint on the column
  would make a v2 extension a migration-touch operation rather
  than a code change, which is the wrong trade-off (the verb
  set is open-ended for v2 additions; the schema's commitment
  is the TABLE shape, not the verb closure). Instead, list the
  verbs as line comments (`-- VERB_NAME`) in the migration so
  grep can keep the documentation honest. The application-layer
  audit-write path is responsible for emitting only the closed
  set.
- **No `CHECK` constraint on `audit_log.action` itself.** Per the
  above point, the verb set is documented but not constrained
  at the schema layer. The matching test corpus that keeps the
  application path honest lives in the audit-write ticket
  (later — out of scope here).
- **Audit-before-effect inside `delete_preban_user`.** The
  procedure body MUST issue the `INSERT INTO audit_log` BEFORE
  the `DELETE FROM users`, in the same transaction. Reversing
  the order (DELETE then INSERT) would lose audit-before-effect
  on a transaction-aborted DELETE — the failure mode Invariant
  7 exists to prevent.
- **No application-tier audit-write helper.** The procedure
  contains the audit row INSERT inline. The Java-side audit
  writer (with redaction-hook, request-id propagation, etc.)
  lives in a later ticket; the procedure-internal INSERT is the
  single audit-write path for the carve-out and does not depend
  on the Java writer.

## Big-picture notes

- **This subticket is the keystone for the authorization model.**
  Every privileged command (`/grant-admin`, `/revoke-admin`,
  `/ban`, `/unban`, `/promote`, `/demote`, `/source-disable`,
  `/quarantine approve` / `reject`, `/forget`, `/lang`, etc.)
  reads `users.is_admin`, writes to `audit_log`, or — for the
  group-admin paths — reads `group_membership.is_group_admin`.
  A defect here propagates to every privileged path. That is
  why this ticket is `complexity: high`, `risk: high`,
  `round_cap: 3` (per `CLAUDE.md` §M1 workflow — the high
  bands authorize the third round), and `security_relevant:
  true` (threat-actor should review the diff after APPROVE).
- **Concurrent revocation is the non-obvious failure mode.** A
  single-transaction `/revoke-admin` against the last admin is
  the easy case; the trigger raises and the operator gets a
  friendly error. The dangerous case is the race: two admins
  on different devices both running `/revoke-admin` against the
  other at almost the same instant. Without serialization both
  transactions read the pre-state of two admins, both decide
  the count would remain at 1, both commit, and the deployment
  has zero admins. `LOCK TABLE users IN SHARE ROW EXCLUSIVE
  MODE` at the top of the trigger function serializes those
  two transactions at the lock acquisition; the second
  transaction sees the first's pending change after the lock
  is released and correctly fails the count check.
  `LastAdminConcurrentRevocationTest` is the regression test
  for this exact scenario.
- **The `audit_log_view` stubs are temporary scaffolding.** The
  real redactor functions land in the audit-write ticket. Until
  they do, the stubs return the input unchanged. A reader who
  greps for `redact_contact_id` after this subticket lands will
  find both the view reference and the stub function; they
  should NOT add the regex catalogue here. The next ticket that
  touches the redactor path is the audit-write hook, and it
  supersedes the stubs in one focused diff.
- **Subticket isolation against M1-008b and M1-008c.** This
  subticket touches only V5 and the identity/audit test files.
  M1-008b adds V6 (sources/tags) and its own test files;
  M1-008c adds V7 (joins/post) and its own test files. The
  `files_scope` lists are disjoint by construction. The
  umbrella M1-008's `blocked_by: [M1-008a, M1-008b, M1-008c]`
  enforces the all-three-merged-before-umbrella ordering.
- **The umbrella's per-(user, scope) isolation IT depends on
  this ticket's `users` and `groups` tables.** Specifically the
  IT seeds two users (A, B), two scopes (DM and group:G), and
  walks the per-scope joins from M1-008c. Without this
  subticket's tables, the IT cannot insert its fixtures. The
  M1-008 umbrella's `blocked_by` enforces this.
- **The `infochat_listen` role from M1-006 is not granted any
  identity-table privilege here.** Its role per
  `docs/spec/security.md` §DB roles is `LISTEN/NOTIFY` only.
  Granting `SELECT` would broaden it beyond spec.

## Out-of-scope expansion

- **The M1-008 umbrella's per-(user, scope) isolation IT
  (`infochat-core/src/test/java/io/infochat/core/schema/PerScopeIsolationIT.java`)**
  is reserved for the umbrella commit. The umbrella + subticket
  idiom (`docs/process/workflow.md` §Ticket-ID placeholder
  convention) exists so cross-cutting verification ships as its
  own reviewable unit. Pre-empting it here — for example by
  writing a test against V5 that also seeds rows in the V7
  joins — would erase the umbrella's reason to exist.
- **The V6 (sources/tags) and V7 (joins/post) migrations.**
  M1-008b and M1-008c. This subticket's V5 file does not
  forward-reference `source`, `post`, `source_subscription`,
  or any of the M1-008b/c table names.
- **Any Java entity class, repository, service, DAO, or
  application-layer audit-write helper.** The schema is the
  commitment. The application layer (the audit-write redaction
  hook, the bootstrap-admin `@Startup` bean, the invite-code
  intake parser, the `/grant-admin` / `/revoke-admin` handlers,
  the `/ban` / `/unban` command paths) lands in later
  Tier-1/Tier-3 tickets.
- **LISTEN/NOTIFY plumbing.** T1-C's territory. `audit_log` has
  no NOTIFY trigger in v1; `users` / `groups` / `group_membership`
  don't either.
- **Provider startup logic.** The `@Startup` admin-bootstrap
  bean (per `docs/spec/deployment.md` §Bootstrap behavior) lives
  on the Provider side and is a separate later ticket. This
  subticket creates the table the bootstrapper will later
  write to.
- **Audit-log row writer and the redactor catalogue.** The
  audit-write path with the closed regex shape catalogue
  (`docs/spec/security.md` §Secrets handling) is an
  application-layer ticket. This subticket declares stub
  redactor functions so the view DDL resolves; the real
  catalogue lands later.
- **Retention sweep / pruner / GC against audit_log or users.**
  Admin-driven retention runs under `infochat_admin` and is
  operator-side per `docs/spec/security.md` §DB roles. The
  schema commitment is the append-only trigger guard plus the
  role grants; there is no scheduled cleanup in v1.
- **Adapter integration code.** The path that resolves
  `(adapter, contact_id)` from an inbound adapter event to a
  `users` row lives in the T1-E messaging tickets.
- **Modifications to V1..V4.** Those migrations are owned by
  M1-005, M1-006, M1-009, M1-016 and are frozen. This subticket
  adds V5 only.

## Authorized test changes

- (none — this subticket adds six new test files in
  `infochat-core` and modifies no pre-existing tests. The five
  M1-003 / M1-007 / M1-007a/b/c tests continue to pass
  unchanged.)

## Alternatives considered

- **Encode `audit_log.action` as a Postgres ENUM type.**
  Tempting because the verb set is closed today. Rejected:
  Postgres ENUMs require a migration to extend; the verb set
  is intentionally open-ended for v2 additions (per
  `docs/design/02-schema.md` §2.1.8 — "extending the catalogue
  is a design-note edit"). A TEXT column with the verbs
  documented as line comments lets the application layer add
  a verb in a code-only change. The application's audit-write
  helper is the closure-enforcer.
- **Add a `CHECK (action IN (...))` constraint over the verb
  set.** Same objection as the ENUM. Rejected.
- **Implement the redactor catalogue here so the view's
  redaction is real on day one.** Rejected: the redactor
  regexes live in `docs/spec/security.md` §Secrets handling
  and the corresponding `docs/design/04-security.md` §4.10;
  bundling them into the schema migration would force this
  subticket to carry the full secrets-redaction test corpus,
  ballooning the diff well past `files_budget: 8`. The stub
  redactor functions keep the view structurally valid; the
  audit-write hook ticket supersedes the bodies.
- **Use a single-row LOCK (`SELECT ... FOR UPDATE`) instead of
  `LOCK TABLE`.** Acceptable per `docs/spec/schema.md`
  §Invariants — Invariant 2 ("Acceptable implementations: take
  a table-level lock on `users` covering the admin rows for
  the duration of the trigger body ... or read the count under
  `SELECT … FOR UPDATE` against the admin rows"). The design
  notes' chosen implementation is `LOCK TABLE users IN SHARE
  ROW EXCLUSIVE MODE` because it is easier to reason about
  (one lock, one mode, one place) and the contention window is
  tiny (only privileged-mutation paths touch `users` with an
  UPDATE/DELETE). A row-level FOR-UPDATE implementation would
  need careful ordering across rows to avoid a deadlock when
  two transactions revoke each other; the table-level lock
  sidesteps the ordering problem. Either choice meets the
  invariant; we pick the simpler one.
- **Run the migration tests as `@QuarkusTest`.** Rejected:
  `infochat-core` is a plain library jar (no Quarkus
  extensions in production scope per M1-007a's §Definition of
  Done). Adding test-scope `quarkus-junit5` here would pull in
  the Quarkus ecosystem just to launch a Testcontainers
  Postgres, which plain JUnit 5 + the Testcontainers JUnit
  extension can do directly. The plain-JUnit shape keeps the
  test invocation simple (`mvn -pl infochat-core test`) and
  the test wall-clock fast (no Quarkus bootstrap).
- **Split V5 into V5a__users.sql, V5b__groups.sql,
  V5c__invite.sql, V5d__audit.sql.** Rejected: Flyway versions
  are integers; an interleaved split would either re-base
  V6/V7 or use sub-version suffixes (which Flyway supports but
  the project hasn't adopted). One V5 file under one
  transaction is the simpler, atomic shape; a half-applied
  V5 on partial failure is exactly the failure mode Flyway's
  per-migration transaction is designed to prevent.
- **Use `pgTAP` for the SQL-level tests.** Rejected: pgTAP is
  not currently in the test stack and adding it would require
  bundling the pgTAP extension into the test Postgres image
  plus a pgTAP test runner. Plain JDBC tests from JUnit are
  enough for the assertions this ticket needs (exception
  message substrings, post-condition COUNT queries, two-
  connection races). The handoff explicitly authorizes this
  trade-off.
