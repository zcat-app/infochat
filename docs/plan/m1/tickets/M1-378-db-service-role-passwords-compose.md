---
id: M1-378
title: "deploy: env-driven service-role passwords via docker/postgres-init.sql + compose wiring"
status: pending
created: 2026-06-15
last_updated: 2026-06-15
blocked_by: []
files_budget: 3
files_scope:
  - docker/postgres-init.sql
  - docker-compose.yml
  - .gitignore
complexity: medium
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - Any Flyway migration change — V1/V2/V31 already create the roles + vector extension and manage LOGIN/grants; this ticket only sets the service-role PASSWORDS, which Flyway cannot read from the environment.
  - The app container services (M1-379) and the LLM services (M1-380) in compose — this ticket touches only the postgres service + its init script.
  - The setup wizard and any prod/ scripts (M1-382+).
acceptance:
  - "docker/postgres-init.sql exists and, using the postgres image's ${VAR:?msg} env substitution, creates infochat_collector and infochat_provider with LOGIN and passwords from INFOCHAT_COLLECTOR_PASSWORD / INFOCHAT_PROVIDER_PASSWORD (and the infochat owner role + database) so the roles Flyway later manages already have credentials; an unset password variable makes the container exit non-zero rather than create a passwordless role (manual: `docker compose --profile prod up postgres` with one var unset exits non-zero — commit-message evidence)."
  - "docker-compose.yml mounts docker/postgres-init.sql at /docker-entrypoint-initdb.d/ and supplies the three INFOCHAT_*_PASSWORD env vars with dev-only `${VAR:-$(openssl rand -hex 24)}` defaults; the postgres service no longer hard-codes a single POSTGRES_USER=infochat role (grep -E confirms the volume mount and the three env entries)."
  - "After `docker compose --profile prod up -d postgres` with the three passwords exported, `psql 'host=localhost port=5432 user=infochat_collector dbname=infochat'` authenticates with the configured password (manual procedure; reviewer trusts the commit-message evidence that it was run)."
  - "`docker compose -f docker-compose.yml config` exits 0."
  - ".gitignore contains an entry that prevents an operator-generated secrets/runtime file from being committed (grep -E confirms a matching entry)."
  - "mvn -B verify from the repo root exits 0 (smoke check; this ticket adds no Java code)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main (this ticket touches only dev/prod infra, no Java)
spec_refs:
  - docs/design/07-deployment.md §Database role bootstrap
  - docs/spec/deployment.md §Topology
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
redteam_audits: []
---

# M1-378: env-driven service-role passwords via docker/postgres-init.sql

## Context

Verified 2026-06-15 against the migration set and the committed compose file:
Flyway already does most of the DB bootstrap — `V1__init.sql` runs
`CREATE EXTENSION vector`, `V2__roles.sql` creates `infochat_collector` /
`infochat_provider` / `infochat_admin`, and `V31` grants `LOGIN` to the two
service roles. What Flyway **cannot** do is set the service-role *passwords*: a
SQL migration cannot read the container's environment, and the passwords live
in env vars (`07-deployment.md` §7.5). The committed `docker-compose.yml` today
creates only the `infochat` superuser via `POSTGRES_USER`, so a non-`%dev`
deployment has no way to authenticate `infochat_collector` /
`infochat_provider`.

This ticket adds `docker/postgres-init.sql` to set those passwords from env at
container init (before the Collector's first Flyway pass, so it creates the
roles WITH passwords and Flyway's `IF NOT EXISTS` then no-ops), and wires the
env vars into `docker-compose.yml` with the dev-only random defaults the design
specifies. It is the first dependency of the containerized prod stack
(`07-deployment.md` §7.7.2 wizard).

The design contract is `docs/design/07-deployment.md` §Database role bootstrap.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter — Flyway role/extension creation is already correct; only the
env-driven passwords are new.

## Notes

- Reconcile the postgres image's `POSTGRES_USER` behaviour with the init
  script so the superuser/owner is created exactly once (do not both set
  `POSTGRES_USER=infochat` and `CREATE ROLE infochat` in the init script — pick
  one path; the design snippet creates the owner in the init script).
- No literal passwords in the init file; rely on `${VAR:?}` fail-loud
  substitution so a missing secret exits the container non-zero.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-378-*.md
```
