---
id: M1-017
title: Relocate Flyway migrations from infochat-collector to infochat-core
status: pending
created: 2026-05-12
last_updated: 2026-05-12
blocked_by: []
files_budget: 8
files_scope: []
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope: []
acceptance: []
test_plan:
  adds: []
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
clarity_check: {}
---

# M1-017: Relocate Flyway migrations from infochat-collector to infochat-core

## Context

> **Skeleton.** Sizing, `acceptance`, `out_of_scope`, and `spec_refs` are
> intentionally empty. The user must flesh them out before
> `/m1-tick start M1-017` will pass the clarity pre-flight.

This ticket exists because two prior tickets deferred the relocation in
prose without filing the follow-up:

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
  (unchanged behavior).
- Provider can opt into `quarkus-flyway` at **test scope** and run the
  same migrations against its own DevServices container, making
  Provider's `@QuarkusTest` self-contained without cross-module
  container sharing or test-fixture duplication.

## Definition of Done

> Provisional — refine before `/m1-tick start`.

- `infochat-core/src/main/resources/db/migration/V1__init.sql`,
  `V2__roles.sql`, `V3__heartbeat.sql` exist in `infochat-core`.
- `infochat-collector/src/main/resources/db/migration/` no longer
  contains these files (or the directory itself is gone).
- Collector continues to apply all three migrations on startup
  (existing `quarkus.flyway.migrate-at-start=true` behavior preserved;
  Flyway picks up migrations from any classpath location under
  `db/migration`).
- Provider's `pom.xml` declares `quarkus-flyway` at test scope.
- Provider's `application.properties` enables Flyway under the test
  profile (`%test.quarkus.flyway.migrate-at-start=true`) so Provider's
  `@QuarkusTest` runs apply the same migrations to its DevServices
  container.
- Provider's pom comment about "migration ownership moves to
  infochat-core after M1-007a" is updated to reflect the new reality
  (now true).
- `mvn -B verify` from the repo root exits 0 — every existing test
  still passes; nothing in `M1-005`'s `FlywayMigrationIT` or
  `M1-006`'s `DbRoleMatrixIT` breaks.

## Implementation notes

> Provisional hints; not a recipe.

- `M1-009`'s partial work currently puts `V3__heartbeat.sql` under
  Collector. When `M1-009` reopens after this ticket lands, the V3
  file relocates to `infochat-core` (or stays where it was put if
  this ticket lands first).
- Flyway searches the classpath for `db/migration/V*__*.sql` by
  default. Putting migrations in a dependency JAR works out of the
  box; no per-module override needed.
- Provider getting Flyway at *test* scope (not main) preserves the
  production invariant from the Provider pom comment: "Provider does
  not migrate in production" — only Collector's startup runs Flyway
  in v1. Tests are the exception.
- `infochat-core` is a plain library jar today (no Quarkus
  extensions, per `M1-007a` `out_of_scope`). Moving the *.sql files
  alone respects that — Flyway-the-extension stays with the
  consuming services (Collector main scope, Provider test scope).
  No `quarkus-flyway` dep in `infochat-core` itself.

## Big-picture notes

- This is **enabling work**, not a feature. `M1-009` is deferred
  behind this ticket; once this lands, `M1-009` reopens and the
  ticket-clarity recheck on reopen should pass (assuming
  `M1-009`'s frontmatter is then valid against the relocated
  layout).
- The forward-reference clarity-check process improvement
  (`M1-018`) is the meta-lesson from how this slip happened. That
  ticket is independent of this one; both run in parallel.

## Out-of-scope expansion

> To be filled in. Likely includes:
>
> - any change to migration content (this is a relocation, not a
>   rewrite).
> - any new Quarkus extension in `infochat-core` (the module remains
>   a plain library jar — see `M1-007a`).
> - any change to Collector's `quarkus.flyway.migrate-at-start=true`
>   behavior (the production-side migration story is unchanged).

## Authorized test changes

- (none yet — to be filled in before start)

## Alternatives considered

- **Init-script-based fixture in Provider tests** (a SQL file under
  `infochat-provider/src/test/resources/` referenced via
  `quarkus.datasource.devservices.init-script-path`). Rejected as a
  long-term shape: duplicates the V3 schema across two files; if the
  schema evolves, the production migration and the test fixture
  drift silently — Provider's tests would pass against a wrong
  schema. Acceptable as a *bridge* if relocation is blocked, but the
  sustainable answer is relocation.
- **Shared DevServices container across modules.** Rejected:
  Quarkus 3.33's `DevServicesDatasourceContainerConfig` does not
  expose a `shared`/`service-name` knob (only `reuse`, which depends
  on user-machine Testcontainers config). Not a portable mechanism.
- **Provider depends on Collector's test-jar to pick up migrations.**
  Rejected: makes Provider's tests depend on Collector's *test*
  layout for *production-shape* schema, which is the wrong coupling.
