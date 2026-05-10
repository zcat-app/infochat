---
id: M1-004
title: Postgres + pgvector dev compose
status: pending
created: 2026-05-10
last_updated: 2026-05-10
blocked_by:
  - M1-001
files_budget: 3
files_scope:
  - docker-compose.yml
  - .gitignore
  - README.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - any Java source under infochat-collector/ or infochat-provider/
  - any application.properties edit (datasource config lands in M1-005 together with the Flyway scaffolding)
  - any Flyway migration or db/migration/* file (M1-005 introduces Flyway)
  - any advisory-lock or heartbeat code or migration (lives in M1-009 once Flyway and DB roles exist)
  - any Ollama / LLM container in the compose (those land with the LLM tickets)
  - any in-memory messaging adapter container (later, with the messaging tickets)
  - any TLS, reverse proxy, or production deployment concern (laptop profile only)
  - any Quarkus extension addition or pom.xml change
  - any scripts/ wrapper (deferred — design/07-deployment.md §7.7.1 enumerates them but they are not part of the minimal dev-compose slice)
acceptance:
  - "docker-compose.yml exists at the repo root and parses (`docker compose -f docker-compose.yml config` exits 0)"
  - "the compose file declares a single service named 'postgres' using image `pgvector/pgvector:pg16` (pinned tag, not `:latest`)"
  - "the postgres service declares a healthcheck running `pg_isready -U infochat -d infochat` (grep -E 'pg_isready' docker-compose.yml returns at least one match)"
  - "the postgres service mounts a named (non-anonymous) Docker volume for its data directory (grep -E 'volumes:' docker-compose.yml returns a match AND the volume name appears in the top-level `volumes:` block)"
  - "docker compose up -d postgres brings the container to a 'healthy' status within 60 seconds (manually verified by running `docker compose up -d postgres && timeout 60 sh -c 'until [ \"$(docker compose ps --format json postgres | jq -r .Health)\" = \"healthy\" ]; do sleep 2; done'`); the acceptance text is the procedure, not a CI gate — reviewer trusts the verbal evidence in the developer's commit message that the procedure was run"
  - "after `docker compose up -d postgres` and waiting for healthy, `docker compose exec -T postgres psql -U infochat -d infochat -c \"SELECT 1 FROM pg_available_extensions WHERE name='vector'\"` returns one row"
  - ".gitignore at the repo root contains an entry that matches any Docker-compose data-volume path or local override file the developer might create (e.g. `docker-compose.override.yml`); grep -E '^docker-compose\\.override' .gitignore returns a match"
  - "mvn -B verify from the repo root exits 0 (smoke check; this ticket adds no Java code or tests)"
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (suite has two @QuarkusTest stubs from M1-003 and remains so; this ticket only touches dev infra)
spec_refs:
  - docs/spec/deployment.md §Local development
  - docs/spec/deployment.md §Topology
  - docs/design/07-deployment.md §7.7 Local development with `docker-compose`
decision_refs:
  - D1
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

# M1-004: Postgres + pgvector dev compose

## Context

First piece of Tier-0 foundation infrastructure. Lays the local
development database so every subsequent ticket — Flyway scaffolding
(M1-005), DB roles (M1-006), advisory lock (M1-009), schema migrations
(M1-008) and on through the eval pipeline — has a running Postgres with
pgvector to test against via `mvn quarkus:dev`. The compose file will
grow over time as later tickets add Ollama (for LLM stages), the two
Quarkus services (when full compose-up is needed), and the in-memory
messaging adapter shell; those additions are explicit follow-up tickets,
not part of this slice.

This ticket is deliberately the smallest possible meaningful piece —
docker-compose only, no Java code touched, no Quarkus extension added.
The `mvn verify` run is a smoke check; the meaningful verification is
that `docker compose up -d postgres` produces a healthy pgvector-capable
DB.

## Definition of Done

- `docker-compose.yml` lives at the repo root, parses, and brings up
  exactly one service named `postgres`.
- The container image is `pgvector/pgvector:pg16` — a maintained image
  that bundles the pgvector binary on top of Postgres 16. This is the
  per-design (`docs/design/07-deployment.md` §7.7) choice of
  Postgres 16 specialised to "pgvector also available."
- A healthcheck runs `pg_isready -U infochat -d infochat` and the
  container reaches `healthy` within the compose-up window.
- The pgvector extension is available (the `CREATE EXTENSION vector`
  step itself lives with M1-005's first Flyway migration, but `SELECT 1
  FROM pg_available_extensions WHERE name='vector'` already returns a
  row at this stage because the image bundles the binary).
- A named Docker volume persists the data directory across `docker
  compose down`/`up` cycles so an operator does not lose local state
  between dev sessions.
- `.gitignore` covers `docker-compose.override.yml` so per-operator
  tweaks do not bleed into the repo.
- `mvn -B verify` from the repo root still exits 0.

## Implementation notes

- Image choice: `pgvector/pgvector:pg16` (pinned tag). `docs/design/07-deployment.md`
  §7.7 prose says "`postgres:16` with pgvector extension, init-loaded
  from `docker/postgres-init.sql`" — vanilla `postgres:16` does NOT
  ship the pgvector binary, so an init-SQL `CREATE EXTENSION vector`
  would fail without first installing the binary via a custom
  Dockerfile. The `pgvector/pgvector:pg16` image is the maintained
  upstream that bundles both. This is a design-tier specialisation,
  not a spec deviation — the spec commitment is "Postgres with
  pgvector," which both forms satisfy. Note this choice in the
  commit message under an `Alternatives considered:` trailer so the
  next developer reading the design notes understands why we
  diverged from the literal `postgres:16` token.
- Service credentials and DB name: user `infochat`, db `infochat`,
  password from an env var `${POSTGRES_PASSWORD:-infochat-dev}` so
  CI/dev defaults work without an `.env` file but operators can
  override. The default password is for dev only; production
  deployment (out of scope here) MUST set a real password via the
  env var.
- The service binds Postgres' port `5432` to the host so `mvn
  quarkus:dev` and `psql` from the host both work. Use the standard
  `5432:5432` mapping; if a developer already has Postgres on
  5432, they override via `docker-compose.override.yml` (which is
  why .gitignore covers that path).
- Named volume: e.g. `infochat-pgdata`. The top-level `volumes:`
  block declares it; the service mounts it at `/var/lib/postgresql/data`.
- The compose `version:` key is intentionally omitted — modern
  `docker compose` ignores it and emits a deprecation warning when
  present.
- The `network_mode` is left to compose's default (a project-named
  bridge network); explicit network config is design-tier and not
  needed at the single-service stage.

## Big-picture notes

- The compose file will accumulate services across later tickets.
  Each addition is a separate ticket so the diff stays reviewable:
  Ollama (LLM tickets), the two Quarkus services (when a full
  compose-up shape is wanted), the in-memory test adapter shell.
  Keep this file's structure flat and additive — service blocks
  declared at the top level, no profiles or overrides until a
  later ticket genuinely needs them.
- Spec deviation note: the design enumerates Postgres-16 + pgvector as
  the laptop-profile choice (`docs/design/01-architecture.md` §1.7;
  also `docs/design/07-deployment.md` §7.2.1). When the `vps`, `pi`,
  or `remote-llm` profiles ship in future tickets, the compose file
  is still the laptop-profile artifact — the other profiles deploy
  against externally-managed Postgres, not a compose-managed one.
- Do not introduce `scripts/dev.sh` and friends in this ticket even
  though `docs/design/07-deployment.md` §7.7.1 mentions them; that
  whole script set is a separate ticket once the compose file has
  reached its full v1 shape and there is something useful for the
  wrappers to wrap.

## Out-of-scope expansion

- **Java source** — this ticket does not touch any module under
  `infochat-collector/` or `infochat-provider/`. The next dev-loop
  tickets (M1-005 Flyway scaffolding, M1-009 advisory lock) edit Java;
  this one is pure infra.
- **`application.properties` datasource config** — wiring Quarkus to
  the compose-managed Postgres happens in M1-005 alongside the
  Flyway extension; M1-005 introduces `quarkus.datasource.*` keys and
  the `infochat.profile` selector. Adding datasource config here
  without Flyway would create a half-wired Quarkus app.
- **Flyway migration files / `db/migration/`** — M1-005 introduces
  Flyway scaffolding and the V1 placeholder migration. No migration
  exists in this ticket; `CREATE EXTENSION vector` lands in M1-005's
  first migration.
- **Advisory-lock / heartbeat** — M1-009 introduces the
  `pg_advisory_lock` startup gate and the heartbeat table (the
  latter via a Flyway migration). M1-009 is blocked on M1-005 and
  M1-006.
- **Ollama / in-memory adapter / Quarkus services in compose** —
  each is its own ticket later in the plan. The compose file should
  be designed so adding a service block is additive (no top-level
  restructure needed).
- **`docker-compose.override.yml`** — `.gitignore` covers it; the
  file is not committed.
- **Quarkus extension changes** — none. This ticket touches no pom.

## Authorized test changes

- (none — this ticket adds no tests and modifies none. The two
  @QuarkusTest stubs from M1-003 stay as they are; they do not yet
  talk to a database. `mvn -B verify` is a smoke check that the
  M1-003 suite still passes.)

## Alternatives considered

- **Vanilla `postgres:16` + init-SQL `CREATE EXTENSION`.** Rejected:
  pgvector is not in the vanilla Postgres binary, so the init SQL
  would fail. Workaround would be a custom Dockerfile that
  `apt-get install`s `postgresql-16-pgvector` — extra build step,
  extra moving part, no benefit over the maintained
  `pgvector/pgvector` image.
- **Bundle Ollama into this ticket.** Rejected: would double the
  diff size for no clear benefit. Ollama is needed only when an LLM
  stage ticket (M1-013) lands; until then it would be dead infra.
  Tickets in this plan are sized to land in one round; bundling
  bloats both this ticket and the LLM ticket.
- **Embed the compose under `docker/` rather than at the repo root.**
  Rejected: every Quarkus and Docker tutorial expects
  `docker-compose.yml` at the repo root; convention beats
  unnecessary nesting.
- **Use Testcontainers in lieu of a committed `docker-compose.yml`.**
  Rejected: Testcontainers is for test-time DB setup (and Quarkus
  DevServices already handles it for `mvn verify`). The committed
  compose is for the operator-facing dev loop (`mvn quarkus:dev`
  against a long-lived DB across sessions). They serve different
  audiences and both will exist; this ticket lands the operator-
  facing artifact.
