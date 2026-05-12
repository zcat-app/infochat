---
id: M1-006
title: DB role matrix (collector, provider, admin)
status: done
created: 2026-05-11
last_updated: 2026-05-12
blocked_by:
  - M1-005
files_budget: 4
files_scope:
  - infochat-collector/src/main/resources/db/migration/V2__roles.sql
  - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: true
out_of_scope:
  - the `heartbeat` table itself, its columns, or any GRANT on it (M1-009 introduces the table AND grants on it in its own V3 migration)
  - any `audit_log_view` view, `approve_quarantine` / `reject_quarantine` stored procedures, or any GRANT on them (those land with the M1-008 schema umbrella subticket that creates `audit_log`, the quarantine tables, and the view+procedure surface; M1-006 only creates the role principals)
  - any GRANT on any specific entity table (no entity tables exist yet — they land with the M1-008 umbrella; the per-table grants ride with each table's CREATE migration so the diff that adds the table also adds its grant pattern)
  - any change to V1__init.sql (the V1 migration is committed and uses the bootstrap `infochat` superuser; rewriting V1 would conflict with Flyway's applied-migration tracking)
  - any per-role JDBC datasource wiring in `application.properties` beyond the comment marker described in Implementation notes (real Quarkus named-datasource wiring of the three roles is deferred — see Big-picture notes)
  - `quarkus.datasource.username` / `quarkus.datasource.password` value changes (the V1-era bootstrap `infochat` superuser stays the active connecting role until the named-datasource wiring ticket lands)
  - any Java code under `src/main/java/` (this ticket is migration-only plus one integration test)
  - any docker-compose.yml edit (the compose-managed Postgres already creates the bootstrap `infochat` superuser; the three application roles are created INSIDE the DB by Flyway)
acceptance:
  - "infochat-collector/src/main/resources/db/migration/V2__roles.sql exists"
  - "grep -E 'CREATE ROLE infochat_collector' infochat-collector/src/main/resources/db/migration/V2__roles.sql returns at least one match"
  - "grep -E 'CREATE ROLE infochat_provider' infochat-collector/src/main/resources/db/migration/V2__roles.sql returns at least one match"
  - "grep -E 'CREATE ROLE infochat_admin' infochat-collector/src/main/resources/db/migration/V2__roles.sql returns at least one match"
  - "V2__roles.sql guards each CREATE ROLE with a `pg_roles`-existence check so re-running on a DB where the roles already exist is a no-op (grep -E 'pg_roles' infochat-collector/src/main/resources/db/migration/V2__roles.sql returns at least three matches — one guard per role)"
  - "V2__roles.sql contains no `CREATE TABLE` statement (grep -E '^[[:space:]]*CREATE TABLE' infochat-collector/src/main/resources/db/migration/V2__roles.sql returns zero matches — the heartbeat table is M1-009's territory)"
  - "V2__roles.sql contains no `CREATE VIEW`, no `CREATE FUNCTION`, no `CREATE PROCEDURE` (grep -E '^[[:space:]]*CREATE (VIEW|FUNCTION|PROCEDURE)' returns zero matches — the audit_log_view and approve/reject procedures are M1-008's territory)"
  - "V2__roles.sql contains no `GRANT.*ON TABLE` and no `GRANT.*ON SEQUENCE` against any specific identifier (grep -E 'GRANT.*ON (TABLE|SEQUENCE) [a-zA-Z_]' returns zero matches — per-table grants ride with each table's CREATE migration in M1-008)"
  - "V2__roles.sql grants schema-level USAGE on the `public` schema to all three roles (grep -E 'GRANT USAGE ON SCHEMA public' returns at least one match)"
  - "V2__roles.sql grants LISTEN privilege via the documented Postgres mechanism — Postgres LISTEN/NOTIFY needs no explicit GRANT (any role with a session can LISTEN); document this in a SQL comment line (grep -E 'LISTEN/NOTIFY' infochat-collector/src/main/resources/db/migration/V2__roles.sql returns at least one match)"
  - "infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java exists, is annotated `@QuarkusTest`, and asserts (against the DevServices Postgres after Flyway has run) that `SELECT rolname FROM pg_roles WHERE rolname IN ('infochat_collector','infochat_provider','infochat_admin')` returns exactly three rows"
  - "mvn -B verify from the repo root exits 0; M1-003 and M1-005 tests still pass"
  - "after `mvn -pl infochat-collector test`, grep -rE 'version \"2 - roles\"' infochat-collector/target/surefire-reports/ returns at least one match (Flyway log line confirming V2 was applied; the actual Flyway output is `Migrating schema \"public\" to version \"2 - roles\"`, which the QuarkusBootstrapTest's @QuarkusTest boot writes into its surefire report when migrate-at-start runs against the DevServices Postgres — combined with `mvn verify` exit 0, this confirms V2 applied successfully)"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (one @QuarkusTest that queries pg_roles and asserts the three role principals exist after V2 applies)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-collector/src/test/java/io/infochat/collector/config/InfochatProfileTest.java (M1-005)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-005 — must still pass; it asserts V1 APPLIED, V2 will additionally show APPLIED)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/config/InfochatProfileTest.java (M1-005)
spec_refs:
  - docs/spec/security.md §DB roles
  - docs/spec/security.md §Trust boundaries
  - docs/spec/architecture.md §Service split
decision_refs:
  - D34

reviews:
  - round: 1
    date: 2026-05-12
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 162
      removed: 33
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-05-12
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-006: DB role matrix (collector, provider, admin)

## Context

`docs/spec/security.md` §DB roles commits the deployment to three Postgres
role principals — `infochat_collector`, `infochat_provider`,
`infochat_admin` — so that a SQL-injection bug in the Provider cannot
delete posts, mutate price snapshots, alter quarantine entries, read
unredacted audit rows, or read raw quarantine originals (decision D34).
The role split is a structural integrity boundary, not a performance
tweak: it is what makes the trust-boundary list in
§Trust boundaries item 5 ("LLM ↔ system state … fixed allowlist of
read-only, scope-filtered functions") meaningful at the database tier.

This ticket lands the **role principals only** as Flyway V2. Per-table
GRANTs ride with the per-table CREATE migrations in the M1-008 umbrella
— the diff that adds a table also adds its grant pattern, so reviewers
can verify the privilege surface for each table in one place. M1-006 is
deliberately the smallest meaningful slice: three `CREATE ROLE`
statements, schema-level USAGE grants, and a sanity-check integration
test that the three principals exist after migration.

## Definition of Done

- A new Flyway migration `V2__roles.sql` exists under
  `infochat-collector/src/main/resources/db/migration/`.
- The migration creates exactly three roles: `infochat_collector`,
  `infochat_provider`, `infochat_admin`. Each `CREATE ROLE` is guarded
  by a `pg_roles`-existence check so re-running on a DB where the
  roles already exist (e.g. an operator pre-seeded them out-of-band)
  is a no-op rather than a hard failure.
- The migration grants `USAGE ON SCHEMA public` to all three roles so
  they can resolve unqualified identifiers in the per-table grants
  that ride with M1-008.
- The migration creates **no table, no view, no function, no procedure**
  and contains **no `GRANT … ON TABLE` / `GRANT … ON SEQUENCE`** against
  any specific identifier — every table-shaped grant rides with the
  table's CREATE migration in M1-008. The `heartbeat` table and its
  grants are M1-009's territory.
- A SQL comment block at the top of the file names the role purposes
  (a one-line summary per role) so an operator reading the migration
  understands the privilege model without round-tripping to the spec.
- A new `DbRoleMatrixIT` @QuarkusTest asserts that after the migrated
  schema is in place, `pg_roles` contains the three principals.
- `mvn -B verify` from the repo root exits 0.

## Implementation notes

- **Idempotent `CREATE ROLE` shape.** Postgres has no `CREATE ROLE IF
  NOT EXISTS`. The standard idiom is a `DO` block that conditions on
  `pg_roles`:
  ```sql
  DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'infochat_collector') THEN
      CREATE ROLE infochat_collector NOLOGIN;
    END IF;
  END $$;
  ```
  Repeat per role. The `NOLOGIN` attribute is the v1 choice for
  *application* roles (they are granted to login roles, see
  Big-picture notes); `infochat_admin` may stay `NOLOGIN` too — the
  operator's psql session connects as the bootstrap superuser and
  `SET ROLE infochat_admin` for operations that require the
  application-admin scope, OR `infochat_admin` is later given LOGIN
  in the named-datasource wiring ticket. Either path is design-tier;
  leave the comment marker in the migration but do not commit to
  LOGIN here.
- **`infochat_collector` and `infochat_provider` as `NOLOGIN` is OK
  for now.** The current connecting role in v1 (after M1-005) is the
  bootstrap `infochat` superuser from the docker-compose. Switching
  the JDBC username to one of these three roles is the *named-
  datasource wiring* ticket that is filed once M1-008 has created
  the entity tables and the per-table grants exist. Until that
  ticket lands, the three roles exist as **principals waiting for
  grants** — they don't yet connect; their privilege surface
  accumulates table-by-table as M1-008 progresses.
- **LISTEN/NOTIFY privileges are not GRANTable in Postgres.** Any
  role with an active session can `LISTEN` on any channel and any
  role can `NOTIFY` any channel; there is no `GRANT LISTEN`. The
  spec language in §DB roles ("`LISTEN/NOTIFY`" appears in the
  Collector and Provider role bullets) describes the *capability*
  the role uses, not a GRANT. Document this in a SQL comment line
  so the next reader doesn't go hunting for the GRANT — the
  acceptance check greps for the comment.
- **Per-table grants land with the tables, not here.** M1-008's
  schema umbrella subtickets each create a table and ATTACH its
  GRANT pattern to the role matrix in the same migration. That
  pattern keeps the privilege surface for each table reviewable in
  one place — opening any V<NN>__<table>.sql shows both the
  structure and the access surface. This ticket's job is to make
  sure the role principals are there when those grants run.
- **Default privileges (`ALTER DEFAULT PRIVILEGES`) are deferred.**
  Postgres' `ALTER DEFAULT PRIVILEGES` controls what role-shaped
  GRANTs apply automatically to *future* objects created by a
  specific role. The current dev migration runs under the bootstrap
  superuser, so any `ALTER DEFAULT PRIVILEGES FOR ROLE infochat`
  here would alter defaults for the bootstrap role only; once the
  named-datasource wiring ticket lands and migrations actually run
  under the admin role, that ticket re-evaluates the default-
  privileges pattern. Leaving it out now is the conservative call.
- **Test placement.** The integration test goes under the Collector
  module (where the migrations live). It is a @QuarkusTest so it
  reuses the DevServices Postgres that M1-005's FlywayMigrationIT
  already exercises; the test body is a single JDBC `SELECT
  rolname FROM pg_roles WHERE rolname IN (...)` and asserts row
  count = 3.
- **Comment markers in `application.properties`.** Add a one-line
  comment in both modules' application.properties pointing at the
  three roles' existence and at the named-datasource wiring ticket
  as the place where JDBC actually switches to them. This is a
  signpost for the next reader — no functional change. Acceptance
  criteria does NOT grep for these comment lines (they are a
  courtesy, not a contract); keep the diff minimal.

## Big-picture notes

- **Named-datasource wiring is a separate ticket.** The three roles
  here are principals waiting for grants and for a Quarkus
  named-datasource that connects as one of them. The wiring ticket
  is filed once M1-008's schema umbrella has landed enough tables
  that each role has something to access. Until then the bootstrap
  `infochat` superuser remains the connecting role for both
  services. Do NOT pre-empt that ticket by adding `quarkus.datasource.<name>.username`
  blocks here.
- **`audit_log_view` and the quarantine stored procs are
  forward-references.** The spec §DB roles names them; they do not
  yet exist. They land with the audit-log subticket and the
  quarantine subticket of the M1-008 umbrella. M1-006 is the
  prerequisite that makes those subtickets' GRANTs resolvable
  (the role principals exist by then).
- **`DELETE` on `source` is REVOKEd from both Collector and
  Provider** per §DB roles invariant-4 enforcement. That REVOKE
  rides with the M1-008 subticket that creates the `source` table
  (along with the soft-delete column it backs). It is NOT this
  ticket's job — but the role principal must exist before the
  REVOKE can name it, which is what M1-006 guarantees.
- **Heartbeat table grants are M1-009's job, not this ticket's.**
  M1-009 creates the `heartbeat` table in V3 and grants
  SELECT/INSERT/UPDATE to both `infochat_collector` and
  `infochat_provider` in the same migration. This keeps the
  heartbeat surface — table + grants + advisory-lock beans —
  reviewable as one diff.
- **Threat-actor scope on the redteam pass.** This ticket is
  `security_relevant: true` because the role split IS the
  authorization boundary §DB roles describes. The redteam
  agent should look for: (a) any GRANT that pre-empts the
  M1-008 per-table grant pattern, (b) any `LOGIN` attribute that
  would let a misconfigured connection bypass the
  named-datasource wiring story, (c) a role granted SUPERUSER /
  CREATEDB / CREATEROLE (none should be — only bootstrap
  `infochat` has those, from the compose).

## Out-of-scope expansion

- **The `heartbeat` table and its grants.** Entirely M1-009. The
  acceptance criteria explicitly grep for *zero* `CREATE TABLE`
  matches in V2 so any pre-emption here is caught by the reviewer.
- **`audit_log_view`, `approve_quarantine`, `reject_quarantine`.**
  M1-008 umbrella subtickets. The acceptance criteria forbid
  `CREATE VIEW` / `CREATE FUNCTION` / `CREATE PROCEDURE` in V2.
- **Per-table GRANTs against entity tables.** M1-008 umbrella.
  The acceptance criteria forbid `GRANT … ON TABLE <name>` /
  `GRANT … ON SEQUENCE <name>` in V2.
- **Edits to V1__init.sql.** Flyway tracks applied migrations by
  filename + checksum; modifying V1 after it has been applied to
  any DB is a Flyway integrity violation. New work goes in V2.
- **Named-datasource wiring in `application.properties`.** A
  one-line comment marker is allowed (courtesy signpost); the
  real `quarkus.datasource.<name>.username` blocks land with the
  named-datasource wiring ticket.
- **Java production code under `src/main/java/`.** This ticket is
  migration-only plus one IT. No production Java.
- **`docker-compose.yml`.** The bootstrap `infochat` superuser is
  already created by the postgres image's env vars (M1-004); the
  three application roles are created INSIDE the DB by Flyway.
- **`ALTER DEFAULT PRIVILEGES`.** Deferred to the named-datasource
  wiring ticket — see Implementation notes.

## Authorized test changes

- (none — this ticket adds `DbRoleMatrixIT.java` but modifies no
  existing test. M1-005's `FlywayMigrationIT` continues to pass
  because Flyway now applies V1 *and* V2 on a fresh DB; the test
  asserts V1 APPLIED and is unaffected by V2's additional row in
  the schema-history table.)

## Alternatives considered

- **Bundle the heartbeat table grants into V2.** Rejected: keeps
  the heartbeat surface fragmented across two migrations and two
  tickets. M1-009 carries the table, the grants, and the bean
  code as one reviewable unit.
- **Grant per-table privileges to all three roles in V2 with a
  blanket `GRANT ALL ON ALL TABLES IN SCHEMA public`.** Rejected:
  the spec §DB roles commits to a *least-privilege* split (Collector
  cannot DELETE source rows, Provider cannot read raw quarantine
  originals, etc.). A blanket grant would silently violate that
  commitment and the threat-actor pass would flag it as a real
  AUTH-BYPASS / PERM-ESCAL finding.
- **Make `infochat_admin` `LOGIN` here so an operator can immediately
  `psql -U infochat_admin`.** Rejected: ties the role's connection
  story to this ticket rather than to the named-datasource wiring
  ticket. The bootstrap `infochat` superuser already provides the
  operator path during v1 bring-up; the admin-role login attribute
  is a design-tier choice owned by that follow-up ticket.
- **Put V2 in `infochat-provider/src/main/resources/db/migration/`
  so the Provider also owns part of the schema.** Rejected:
  M1-005 already established the Collector as the (interim)
  migration owner; splitting migrations across two modules now
  would force a coordination story for ordering. The followup
  ticket that moves migrations into `infochat-core` (filed once
  M1-007a lands) is the right place to revisit ownership.
- **Use a single SQL statement `CREATE ROLE` without the `pg_roles`
  guard.** Rejected: leaves the migration brittle against an
  operator who pre-seeded roles via `psql -f` for an external
  reason (compliance scripts, ops-tier provisioning). The guarded
  shape is one extra DO block per role for free.
