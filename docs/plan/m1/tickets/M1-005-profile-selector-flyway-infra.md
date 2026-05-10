---
id: M1-005
title: Profile selector + Flyway infra
status: pending
created: 2026-05-10
last_updated: 2026-05-10
blocked_by:
  - M1-001
  - M1-003
files_budget: 7
files_scope:
  - infochat-collector/pom.xml
  - infochat-provider/pom.xml
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-collector/src/main/resources/db/migration/V1__init.sql
  - infochat-collector/src/main/java/io/infochat/collector/config/InfochatProfile.java
  - infochat-provider/src/main/java/io/infochat/provider/config/InfochatProfile.java
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: true
out_of_scope:
  - any DB GRANT / role configuration (M1-006 introduces the three-role matrix; V1 migration here uses the default `infochat` user from M1-004 only)
  - any schema migration beyond CREATE EXTENSION vector + a comment marker (every entity table lands in M1-008 umbrella subtickets)
  - any advisory-lock or heartbeat code or table (M1-009 introduces them once Flyway exists)
  - any new Maven module (`infochat-core`, `infochat-llm-adapter`, `infochat-messaging-adapter` are introduced by M1-007 subtickets)
  - any SPI interface (lives in M1-007 subtickets)
  - any per-profile numeric tuning value beyond the bare minimum needed to confirm the selector wires through (concrete `laptop` / `vps` / `pi` / `remote-llm` values for context window, eval concurrency, vector index type, etc. live in design notes and are wired by their respective feature tickets)
  - any datasource pooling or connection-release tuning (the connection-release discipline in `docs/design/07-deployment.md` §7.4 belongs to the Provider's connection-using tickets, not this one)
  - any Quarkus extension other than `quarkus-flyway` and `quarkus-jdbc-postgresql` (no langchain4j, no scheduler, no messaging, no rest)
  - any change under `infochat-collector/src/test/java/` or `infochat-provider/src/test/java/` beyond what verifies the profile enum loads and Flyway applies the V1 migration on a fresh DB
  - any docker-compose.yml edit (M1-004 owns it; this ticket only adds clients connecting to it)
acceptance:
  - "grep -E '<artifactId>quarkus-flyway</artifactId>' infochat-collector/pom.xml returns at least one match"
  - "grep -E '<artifactId>quarkus-jdbc-postgresql</artifactId>' infochat-collector/pom.xml returns at least one match"
  - "grep -E '<artifactId>quarkus-jdbc-postgresql</artifactId>' infochat-provider/pom.xml returns at least one match"
  - "grep -E '<artifactId>quarkus-flyway</artifactId>' infochat-provider/pom.xml returns ZERO matches (Provider does not migrate; only Collector owns migrations in v1 — see Big-picture notes)"
  - "grep -rEn '<version>' infochat-collector/pom.xml infochat-provider/pom.xml returns zero matches inside <dependency> blocks (BOM still supplies versions, M1-001 invariant preserved)"
  - "infochat-collector/src/main/resources/db/migration/V1__init.sql exists and the file content contains the literal string 'CREATE EXTENSION IF NOT EXISTS vector' (case-sensitive grep)"
  - "infochat-collector/src/main/resources/application.properties declares `quarkus.flyway.migrate-at-start=true` AND `quarkus.datasource.db-kind=postgresql` AND `quarkus.datasource.username` AND `quarkus.datasource.password` AND `quarkus.datasource.jdbc.url` keys (one match each from grep)"
  - "infochat-provider/src/main/resources/application.properties declares the same `quarkus.datasource.*` keys but does NOT declare `quarkus.flyway.migrate-at-start` (Provider has no Flyway extension on classpath)"
  - "infochat-collector/src/main/resources/application.properties contains a Quarkus profile-override pair for the laptop profile (grep -E '^%laptop\\.' returns at least one match) AND for the vps profile (grep -E '^%vps\\.' returns at least one match) AND for the pi profile (grep -E '^%pi\\.' returns at least one match) AND for the remote-llm profile (grep -E '^%remote-llm\\.' returns at least one match) — even if the override values are stub/placeholder; the four profile namespaces must exist so later tickets have somewhere to add real values"
  - "infochat-collector/src/main/java/io/infochat/collector/config/InfochatProfile.java exists, declares an enum InfochatProfile with the four values LAPTOP, VPS, PI, REMOTE_LLM (grep matches all four enum constant names), and a CDI bean that reads `quarkus.profile` (or `infochat.profile`) at startup and fails-fast if the resolved value does not match a known enum constant"
  - "infochat-provider/src/main/java/io/infochat/provider/config/InfochatProfile.java exists with the equivalent enum and validation bean (same four enum values, same startup-fail-fast behavior)"
  - "mvn -B verify from the repo root exits 0; the existing M1-003 @QuarkusTest stubs still pass AND Quarkus DevServices auto-starts a Postgres container so Flyway's V1 migration applies in the collector test run (no @TestProfile or @TestResource needed — Quarkus default DevServices handles it)"
  - "after `mvn -pl infochat-collector test`, grep -rE 'Migrated.*successfully.*V1' infochat-collector/target/surefire-reports/ returns at least one match (or equivalent Flyway success log line; whatever Flyway prints on a successful migration during the test run)"
test_plan:
  adds:
    - infochat-collector/src/test/java/io/infochat/collector/config/InfochatProfileTest.java (verifies the enum loads and the validation bean fails-fast on an unknown profile value)
    - infochat-collector/src/test/java/io/infochat/collector/flyway/FlywayMigrationIT.java (a @QuarkusTest that confirms `flyway info` reports V1 as APPLIED after the test app boots against the DevServices Postgres)
    - infochat-provider/src/test/java/io/infochat/provider/config/InfochatProfileTest.java (provider-side mirror of the enum/validation test)
  preserves:
    - infochat-collector/src/test/java/io/infochat/collector/QuarkusBootstrapTest.java (M1-003)
    - infochat-provider/src/test/java/io/infochat/provider/QuarkusBootstrapTest.java (M1-003)
spec_refs:
  - docs/spec/architecture.md §Hardware profiles
  - docs/spec/deployment.md §Configuration surface (spec level)
  - docs/design/01-architecture.md §1.2 Module layout (Maven)
  - docs/design/01-architecture.md §1.7 Hardware profiles
  - docs/design/07-deployment.md §7.2 Hardware profiles
  - docs/design/07-deployment.md §7.4 Canonical `application.properties`
decision_refs:
  - D1
  - D34

reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-005: Profile selector + Flyway infra

## Context

Second piece of Tier-0 foundation. Once M1-004 has put a pgvector-
capable Postgres on the operator's laptop, this ticket teaches the two
Quarkus services how to talk to it: datasource wiring, Flyway
migration scaffolding (Collector-side only, see Big-picture notes),
and the profile selector that later tickets read for per-profile
sizing values. Every entity-schema and operational-table migration in
subsequent tickets depends on the Flyway directory existing and on
`migrate-at-start` being on.

The V1 placeholder migration deliberately contains only `CREATE
EXTENSION IF NOT EXISTS vector` plus a comment marker. It exists so
that Flyway has something to apply on a fresh DB (which proves the
infrastructure works end-to-end) without committing to any entity-
table shape — those land in the M1-008 umbrella.

## Definition of Done

- `quarkus-flyway` and `quarkus-jdbc-postgresql` extensions are added
  to `infochat-collector` via its pom; `quarkus-jdbc-postgresql` is
  added to `infochat-provider` but `quarkus-flyway` is NOT — only
  the Collector runs migrations in this stage of the build (see
  Big-picture notes for the v1-versus-future reasoning).
- Both modules' `application.properties` carry the
  `quarkus.datasource.*` keys pointing at the compose-managed
  Postgres from M1-004 (host `localhost`, port `5432`, db
  `infochat`, user `infochat`, password from env with a dev
  default).
- The Collector's `application.properties` additionally sets
  `quarkus.flyway.migrate-at-start=true`.
- Both modules' `application.properties` declare a per-profile
  override namespace for each of `%laptop`, `%vps`, `%pi`, and
  `%remote-llm` — even if the actual values are placeholders today.
  Later feature tickets layer real per-profile values onto these
  namespaces; the namespaces must exist now so the feature tickets'
  diffs stay focused.
- A type-safe `InfochatProfile` enum exists in each module's
  `config/` package with four values: `LAPTOP`, `VPS`, `PI`,
  `REMOTE_LLM`. A CDI startup bean reads the active Quarkus
  profile and validates it maps to one of the four enum values;
  fails fast with a clear log message naming the unknown value if
  not.
- `infochat-collector/src/main/resources/db/migration/V1__init.sql`
  exists with `CREATE EXTENSION IF NOT EXISTS vector` plus a
  comment line identifying the migration as the scaffolding
  placeholder.
- `mvn -B verify` from the repo root exits 0. The Flyway migration
  test (added by this ticket) confirms V1 reaches APPLIED state
  after a fresh DB boot.

## Implementation notes

- **Why Collector-owned Flyway in v1.** The eventual home for
  migrations is `infochat-core` (`docs/design/01-architecture.md`
  §1.2). `infochat-core` is introduced in M1-007a. Putting
  migrations there ahead of M1-007a would mean either pulling
  M1-007a forward into Tier 0 (broader scope) or living with a
  split: migrations in Collector now, migrations in core after
  M1-007a. The cleanest path is: migrations in Collector now,
  followed by a small follow-up ticket *after* M1-007a that moves
  the `db/migration/` directory into `infochat-core` and makes both
  services depend on it. That follow-up is NOT this ticket; it
  will be filed when M1-007a lands. Until then, Collector is the
  migration owner and Provider startup assumes migrations have
  already been applied (operator runs Collector first; documented
  in the laptop dev loop). This is acceptable because the laptop
  profile is the only one in M1's exit criteria; multi-instance
  ordering is a v2 concern (`docs/spec/architecture.md`
  §Deployment topology (v1) only commits to "exactly one Collector
  and exactly one Provider").
- **The `infochat.profile` vs `quarkus.profile` decision.** Quarkus
  already has a first-class profile mechanism (`%profilename.key=value`
  in application.properties, selected via `-Dquarkus.profile=NAME` or
  the `QUARKUS_PROFILE` env var). Using a *separate* `infochat.profile`
  key would create two sources of truth. This ticket therefore uses
  the built-in `quarkus.profile` mechanism with the four allowed
  values (`laptop`, `vps`, `pi`, `remote-llm`) and the `InfochatProfile`
  enum just validates that the resolved profile is one of those four.
  The enum is what later tickets inject for per-profile branching
  in Java code; the property file uses the `%profilename.` shortcut.
- **DevServices.** `quarkus-jdbc-postgresql` enables Quarkus
  DevServices, which auto-starts a Postgres container for tests.
  Combined with `quarkus.flyway.migrate-at-start=true` the test
  app boots, migrates V1, and is verifiable from a @QuarkusTest.
  No `@TestResource` or Testcontainers wiring is needed by hand.
- **Default profile for `mvn verify`.** Quarkus' `test` profile is
  the default for `mvn test`. This ticket sets `%test.quarkus.profile`
  to inherit from `laptop` (or sets a generic test profile that
  picks up the laptop overrides) so the M1-003 tests, the new
  profile-validation test, and the new Flyway IT all run under a
  coherent profile.
- **Password handling.** Use `quarkus.datasource.password=${POSTGRES_PASSWORD:infochat-dev}`
  so CI and dev work without an `.env` file but operators can
  override. The default `infochat-dev` value matches M1-004's
  compose default.
- **`CREATE EXTENSION IF NOT EXISTS vector`.** The pgvector image
  bundles the binary but does NOT auto-create the extension on
  a fresh DB. The V1 migration is the canonical place this happens;
  subsequent migrations (M1-008+) can then freely use the `vector`
  type.

## Big-picture notes

- **Migrations will move to `infochat-core` after M1-007a.** That
  is *the* follow-up implied by this ticket. File a new ticket the
  moment M1-007a is `done` — its scope is: move `db/migration/`
  from `infochat-collector/src/main/resources/` to
  `infochat-core/src/main/resources/`, add `quarkus-flyway` to
  `infochat-core/pom.xml`, make both Quarkus apps depend on
  `infochat-core` (M1-007a already did this for the entity DTOs),
  remove `quarkus-flyway` from `infochat-collector/pom.xml`, and
  delete the "Collector-runs-first" doc note. Do not bundle that
  move into this ticket or M1-007a — it is its own atomic concern.
- **Profile namespace conventions.** The four `%laptop.`, `%vps.`,
  `%pi.`, `%remote-llm.` namespaces will accumulate keys over the
  next dozen tickets (context window sizes, eval concurrency, vector
  index type, retry budgets). Keep them lexicographically sorted
  within each profile block so diffs stay clean.
- **`infochat.profile` is NOT going to exist as a separate key.**
  Future readers may look for it because the spec consistently uses
  the phrase "infochat.profile" (e.g. `CLAUDE.md` mentions it).
  That is the *concept* name; the *configuration mechanism* is
  Quarkus' built-in `quarkus.profile`. Document this clearly in the
  `InfochatProfile` enum's Javadoc so the next reader doesn't waste
  time looking for a separate property.
- **No per-profile numeric values yet.** Adding e.g.
  `%laptop.infochat.context-window=8192` here would force this
  ticket to take a stance on what the laptop value is, which is a
  design-tier choice owned by the feature ticket that introduces
  context-window enforcement. The four namespaces are empty (or
  carry only a stub key) until that ticket lands.
- **No DB role grants in V1.** M1-006 introduces the three-role
  matrix in V2/V3 migrations. V1 uses the bootstrap `infochat`
  user. This intentionally simplifies the very first migration so
  there is no chicken-and-egg between "creating the roles" and
  "the role running the migration."

## Out-of-scope expansion

- **DB GRANTs and roles** — explicitly M1-006's job. V1 here uses
  the default Postgres superuser from the compose so we can
  bootstrap without a roles file. Touching roles in this ticket
  would conflict with M1-006's diff.
- **Entity tables / schema migrations beyond V1** — every users /
  source / post / etc. table is in the M1-008 umbrella. V1 here
  is *just* the extension load and a marker comment.
- **Advisory lock / heartbeat / heartbeat table** — M1-009. The
  advisory lock needs Flyway scaffolding to exist (this ticket)
  AND DB roles (M1-006) before its own migration can run.
- **`infochat-core`, `infochat-llm-adapter`, `infochat-messaging-adapter`**
  — all M1-007 subtickets. This ticket does NOT introduce any new
  Maven module.
- **SPI interfaces of any kind** — M1-007 umbrella + subtickets.
- **Per-profile numeric tuning values** — design-tier; each value
  lives in the feature ticket that consumes it.
- **Connection-release discipline / `quarkus.datasource.jdbc.idle-removal-interval`**
  — `docs/design/07-deployment.md` §7.4 has the recipe; it belongs
  to the Provider's heavily-connection-using ticket (chat agent
  or summary), not Tier 0. Datasource here is the bare-minimum
  shape that connects.
- **Other Quarkus extensions** — no `langchain4j-*`, no
  `quarkus-scheduler`, no messaging, no REST. Each lands with its
  consuming ticket.
- **docker-compose.yml** — M1-004's territory.

## Authorized test changes

- (none — this ticket adds new tests but modifies none. The two
  M1-003 @QuarkusTest stubs continue to pass because Flyway adds
  V1 and the bootstrap apps boot against the migrated DB.)

## Alternatives considered

- **Put Flyway in `infochat-provider` and have Provider migrate.**
  Rejected: the Collector is the ingest service; the schema is
  more naturally Collector-owned (every entity table is written by
  Collector first). Provider's role is read + user-state-write
  layered on top.
- **Both services run Flyway with `migrate-at-start=true`.** Flyway
  uses table locking so concurrent migration attempts are safe
  (one wins, the other waits). But it doubles the boot-time DB
  contact and obscures who actually owns the schema. The Collector-
  only approach is simpler until `infochat-core` exists.
- **Introduce `infochat-core` here so migrations have a permanent
  home.** Rejected for scope: this ticket would balloon to ~12
  files and re-shuffle the M1-007 plan. Cleaner to land
  Collector-owned migrations now, move them after M1-007a as a
  small follow-up.
- **Use a separate `infochat.profile` config key instead of
  `quarkus.profile`.** Rejected: two sources of truth for the
  active profile is asking for drift bugs. Reuse Quarkus' built-in.
- **Defer `CREATE EXTENSION vector` to M1-008.** Rejected: the
  V1 migration needs *something* in it to prove the scaffolding
  works end-to-end. `CREATE EXTENSION vector` is the most natural
  inhabitant — it is profile-independent, schema-independent, and
  required by every later migration that touches `embedding`
  columns.
