---
id: M1-017
title: Relocate Flyway migrations from infochat-collector to infochat-core
status: pending
created: 2026-05-12
last_updated: 2026-05-12
blocked_by: []
files_budget: 8
files_scope:
  - infochat-core/src/main/resources/db/migration/V1__init.sql
  - infochat-core/src/main/resources/db/migration/V2__roles.sql
  - infochat-collector/src/main/resources/db/migration/V1__init.sql
  - infochat-collector/src/main/resources/db/migration/V2__roles.sql
  - infochat-provider/pom.xml
  - infochat-provider/src/main/resources/application.properties
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - V3__heartbeat.sql (M1-009 territory; V3 has never been committed and lands when M1-009 reopens, placing it directly in the new infochat-core/db/migration location — this ticket does NOT pre-create V3)
  - any change to migration file CONTENT (this is a relocation, not a rewrite — V1's CREATE EXTENSION vector and V2's three CREATE ROLE blocks ship byte-identical to their prior infochat-collector copies, save for the per-migration header comment which may be updated to reflect the new location)
  - any new Quarkus extension dependency in infochat-core/pom.xml — the module stays a plain library jar per M1-007a out_of_scope line 31 ("any Quarkus extension dependency in infochat-core/pom.xml ... this module is a plain library jar — no quarkus-arc, no quarkus-jdbc-postgresql, no quarkus-flyway here; downstream Quarkus apps pull extensions, infochat-core only carries types"). quarkus-flyway lives in the consuming services, not in infochat-core
  - any change to Collector's quarkus.flyway.migrate-at-start=true (production behavior unchanged; the operator pattern "Collector migrates first" is preserved in production)
  - any change to Provider's PRODUCTION pom — Provider's quarkus-flyway dep is test-scoped only; production Provider continues to not migrate per the existing pom comment
  - any addition or change to V1's pgvector extension load or V2's three CREATE ROLE blocks (move only)
  - any test added to infochat-core (the module is a plain library jar; tests here would require quarkus-junit5 which would pull in Quarkus extensions and violate the M1-007a invariant — Flyway behavior is tested in the consuming services' ITs)
  - any change to infochat-collector/pom.xml (Collector already has quarkus-flyway from M1-005; only its migration source directory becomes empty)
acceptance:
  - "infochat-core/src/main/resources/db/migration/V1__init.sql exists and contains a CREATE EXTENSION vector statement (grep -E 'CREATE EXTENSION.*vector' returns at least one match)"
  - "infochat-core/src/main/resources/db/migration/V2__roles.sql exists and creates the three application roles (grep -E 'CREATE ROLE infochat_collector' returns at least one match AND grep -E 'CREATE ROLE infochat_provider' returns at least one match AND grep -E 'CREATE ROLE infochat_admin' returns at least one match) AND grants schema USAGE to each (grep -E 'GRANT USAGE ON SCHEMA public TO infochat_(collector|provider|admin)' returns at least three matches)"
  - "infochat-collector/src/main/resources/db/migration/V1__init.sql no longer exists (test ! -e infochat-collector/src/main/resources/db/migration/V1__init.sql exits 0)"
  - "infochat-collector/src/main/resources/db/migration/V2__roles.sql no longer exists (same check)"
  - "infochat-provider/pom.xml adds quarkus-flyway at test scope (the matching <dependency> block contains both <artifactId>quarkus-flyway</artifactId> and <scope>test</scope> — verify by extracting the dependency element via awk '/<dependency>/,/<\\/dependency>/' and grepping that block)"
  - "infochat-provider/src/main/resources/application.properties enables Flyway under the test profile (grep -E '^%test\\.quarkus\\.flyway\\.migrate-at-start=true' returns at least one match)"
  - "infochat-provider/pom.xml's existing comment about migrations is updated — the line 'The migration ownership moves to infochat-core after M1-007a, at which point both services depend on it.' is rewritten to reflect that the relocation has happened (e.g., 'Migrations live in infochat-core (M1-017); both services see them on classpath. Production Provider does not run them; test-scoped quarkus-flyway runs them against Provider's DevServices container only.')"
  - "mvn -B verify from the repo root exits 0"
  - "After mvn -pl infochat-collector test, grep -rE 'Migrated.*successfully.*V[12]' infochat-collector/target/surefire-reports/ returns at least one match (M1-005's FlywayMigrationIT continues to assert V1 applied via the new classpath location)"
  - "After mvn -pl infochat-provider test, grep -rE 'Migrated.*successfully.*V[12]' infochat-provider/target/surefire-reports/ returns at least one match (Provider's @QuarkusTest runs apply migrations from the infochat-core classpath via its test-scoped quarkus-flyway)"
test_plan:
  adds: []
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (M1-005 — still asserts V1 in SUCCESS state; Flyway resolves migrations from the infochat-core classpath transparently)
    - infochat-collector/src/test/java/io/infochat/collector/db/DbRoleMatrixIT.java (M1-006 — still asserts the three role principals exist; V2 now applied from infochat-core but the role-creation outcome is identical)
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-collector/src/test/java/io/infochat/collector/config/InfochatProfileTest.java (M1-005)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/config/InfochatProfileTest.java (M1-005)
    - infochat-provider/src/test/java/io/infochat/provider/spi/AllSpisLoadIT.java (M1-007)
spec_refs:
  - docs/spec/architecture.md §Deployment topology
decision_refs:
  - D41
---

# M1-017: Relocate Flyway migrations from infochat-collector to infochat-core

## Context

This ticket exists because two prior tickets deferred the relocation
in prose without filing the follow-up:

- `M1-005` "Alternatives considered" rejected putting migrations in
  `infochat-core` for scope reasons, saying: *"Cleaner to land
  Collector-owned migrations now, move them after M1-007a as a small
  follow-up."*
- `M1-007a` `out_of_scope` line 30 confirmed the deferral: *"the
  migration-move-into-core follow-up is a SEPARATE ticket filed once
  M1-007a lands."*

`M1-007a` landed; the follow-up ticket was never filed. The
consequence surfaced in `M1-009`: Provider has no `quarkus-flyway` (by
design) and depends on the Collector having migrated, so Provider's
`@QuarkusTest` DevServices container has no `heartbeat` table and
`M1-009`'s Provider-side `InstanceLockGuard` crashes at startup before
any test runs. The `infochat-provider/pom.xml` comment already
documents the intended end state: *"The migration ownership moves to
infochat-core after M1-007a, at which point both services depend on
it."*

Both services already depend on `infochat-core`. Relocating the
migration files there means both modules' classpaths carry them, so:

- Collector's production startup continues to apply migrations
  (unchanged behavior — Flyway's classpath scan finds them in
  `infochat-core`'s JAR instead of `infochat-collector`'s own
  resources, which is transparent to Flyway).
- Provider opts into `quarkus-flyway` at **test scope** and runs the
  same migrations against its own DevServices container, making
  Provider's `@QuarkusTest` self-contained without cross-module
  container sharing or test-fixture duplication.

## Definition of Done

- `infochat-core/src/main/resources/db/migration/V1__init.sql` and
  `V2__roles.sql` exist in `infochat-core`, byte-identical (modulo
  header comments) to their prior `infochat-collector` copies.
- `infochat-collector/src/main/resources/db/migration/V1__init.sql`
  and `V2__roles.sql` no longer exist (the move is a delete-from-
  collector + create-in-core; git tracks the move via content
  similarity).
- `infochat-collector/pom.xml` is unchanged (Collector already has
  `quarkus-flyway` from `M1-005`; only its migration source
  directory becomes empty, which is fine — Flyway scans the full
  classpath, not just the host module).
- `infochat-provider/pom.xml` adds `quarkus-flyway` at
  `<scope>test</scope>`. The existing comment about migration
  ownership is updated to reflect the new state.
- `infochat-provider/src/main/resources/application.properties`
  declares `%test.quarkus.flyway.migrate-at-start=true` so
  Provider's `@QuarkusTest` runs apply migrations from the
  `infochat-core` classpath to the DevServices container.
- `infochat-core/pom.xml` is unchanged — no Quarkus extensions
  added (M1-007a invariant preserved).
- `mvn -B verify` from the repo root exits 0; both modules'
  surefire reports show Flyway successfully applying V1 and V2.

## Implementation notes

- Flyway's default classpath scan covers every `db/migration/V*__*.sql`
  resource on the classpath, regardless of which JAR provides it.
  Moving the files to `infochat-core` is therefore transparent to
  Flyway — both consuming services find the migrations via their
  transitive dependency on `infochat-core`.
- `infochat-core` stays a plain library jar (no Quarkus extensions).
  `quarkus-flyway` is the *extension*; the *migrations* are plain
  resource files. The extension lives in consuming services
  (Collector main scope, Provider test scope); the resources live in
  `infochat-core`. This split honors the M1-007a invariant.
- Provider's `quarkus-flyway` dependency at **test scope** means
  production Provider doesn't pull the extension at runtime. The
  production-side migration story is unchanged: the operator runs
  Collector first; Provider does not migrate in production.
- The pom comment in `infochat-provider/pom.xml` was prescient
  ("The migration ownership moves to infochat-core after M1-007a").
  This ticket makes that prediction true; the comment should be
  rewritten to describe the *current* state, not a future move.
- `M1-009` is deferred behind this ticket. When `M1-009` reopens
  after this lands, its `V3__heartbeat.sql` will be created in
  `infochat-core/src/main/resources/db/migration/V3__heartbeat.sql`
  directly — not in Collector. `M1-009`'s `files_scope` will need
  updating at reopen to reflect this. That update is not this
  ticket's responsibility.

## Big-picture notes

- This is **enabling work**, not a feature. `M1-009` is deferred
  behind this ticket. Other future tickets that add migrations
  (the M1-008 schema umbrella) automatically benefit from the new
  layout — every entity-table migration lands in `infochat-core`
  from the start.
- The forward-reference clarity-check process improvement
  (`M1-018`) is the meta-lesson from how this slip happened. That
  ticket is independent of this one; both run in parallel.
- This ticket touches no specs and no decisions — it's a design-
  level implementation move. `D41` is cited only because the
  relocation enables the "single-instance enforcement" path
  (M1-009) that D41 commits to; the relocation itself is not a
  new spec commitment.

## Out-of-scope expansion

- **V3__heartbeat.sql.** M1-009 territory. V3 is not yet on disk
  in any module's main branch; it lands when M1-009 reopens. This
  ticket touches V1 and V2 only.
- **Migration content changes.** Pure relocation — same SQL, new
  directory. Any change to the actual statements (new GRANTs, new
  CREATE TABLE, etc.) belongs in the consuming ticket (M1-008
  umbrella for new entity tables, M1-009 for heartbeat additions
  inside V3, etc.).
- **Quarkus extensions in infochat-core.** `M1-007a` `out_of_scope`
  forbids adding any Quarkus extension to `infochat-core/pom.xml`.
  This ticket honors that — only resource files move; the
  `quarkus-flyway` extension stays in consuming services.
- **Collector pom changes.** `infochat-collector` already has
  `quarkus-flyway` (from `M1-005`). No pom change is needed there;
  only the migration source directory empties out.
- **Provider main-scope Flyway dep.** Forbidden — production
  Provider must not migrate. The Provider pom adds Flyway at
  `<scope>test</scope>` only.
- **infochat-core tests.** `infochat-core` is a plain library jar
  per `M1-007a`. Adding tests there would require `quarkus-junit5`,
  which transitively pulls Quarkus extensions and violates the
  M1-007a invariant. Flyway behavior is tested in consuming
  services' existing ITs (M1-005, M1-006, plus an implicit cover
  from any Provider `@QuarkusTest` running migrations).
- **Cross-module test container sharing.** The whole point of the
  relocation is that Provider's tests stop *needing* to share with
  Collector's container — each module's DevServices container is
  now self-contained because each has Flyway and the migrations
  on classpath.

## Authorized test changes

- (none — this ticket adds no new tests and modifies no existing
  ones. `M1-005`'s `FlywayMigrationIT` continues to assert V1 was
  applied; its assertion (`MigrationState.SUCCESS` on version "1")
  holds regardless of which classpath the migration came from.
  `M1-006`'s `DbRoleMatrixIT` continues to assert the three role
  principals exist; V2 applied from `infochat-core` produces the
  same role-state outcome. Provider's existing tests
  (`QuarkusBootstrapTest`, `InfochatProfileTest`, `AllSpisLoadIT`)
  do not query DB tables, so they are unaffected by the migration
  presence — they pass identically whether Flyway ran or not, but
  with the new test-scoped `quarkus-flyway` they will additionally
  log V1/V2 application in their surefire reports, which the
  acceptance criteria above use as proof.)

## Alternatives considered

- **Init-script-based fixture in Provider tests** (a SQL file
  under `infochat-provider/src/test/resources/` referenced via
  `quarkus.datasource.devservices.init-script-path`). Rejected as
  a long-term shape: duplicates the schema across two files; if
  schemas evolve, the production migration and the test fixture
  drift silently — Provider's tests would pass against a wrong
  schema. Acceptable as a *bridge* if relocation is blocked, but
  the sustainable answer is relocation.
- **Shared DevServices container across modules.** Rejected:
  Quarkus 3.33's `DevServicesDatasourceContainerConfig` does not
  expose a `shared`/`service-name` knob (only `reuse`, which
  depends on user-machine Testcontainers config and is not
  portable across developer and CI environments).
- **Provider depends on Collector's test-jar to pick up
  migrations.** Rejected: makes Provider's tests depend on
  Collector's *test* layout for *production-shape* schema, which
  is the wrong coupling. The migrations are production-shape
  resources; they belong in a production-shape JAR
  (`infochat-core`), not behind a test-jar artifact.
- **Add `quarkus-flyway` to `infochat-core`'s pom.** Rejected:
  violates the `M1-007a` invariant that `infochat-core` is a
  plain library jar. The extension belongs with the consuming
  services that decide whether and when to migrate; the
  *migrations* are plain resources that infochat-core can carry
  without becoming Quarkus-aware.
- **Land V3 in this ticket too.** Rejected: V3 is `M1-009`'s
  scope (single-instance enforcement). Pre-creating it here
  would couple two tickets that should remain independent —
  `M1-017` is pure infrastructure; `M1-009` is feature code.
