---
id: M1-393
title: "evaluate dropping SUPERUSER from the Postgres infochat owner role (the Collector holds owner creds for Flyway)"
status: pending
created: 2026-06-16
last_updated: 2026-06-16
blocked_by: []
files_budget: 2
files_scope:
  - docker/postgres-init.sh
  - docs/design/07-deployment.md
complexity: low
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - The two weak service roles (infochat_collector / infochat_provider) and their passwords — unchanged.
  - The extension creation (vector, pgcrypto) — already done by the image's bootstrap superuser in postgres-init.sh, not by the owner role.
acceptance:
  - "A determination is recorded (in the postgres-init.sh comment, with a §7.x cross-reference) of whether the infochat owner role needs SUPERUSER for the full Flyway migration set the Collector applies. If it does not, `CREATE ROLE infochat` drops SUPERUSER in favor of the minimum grants (e.g. CREATEROLE/CREATEDB + ownership) and a clean wizard run (`prod/setup.sh --defaults` through step 7) completes with the Collector reporting migrations applied. If SUPERUSER is required, the comment names the specific migration and operation that needs it."
  - "`mvn -B verify` from the repo root exits 0 (the migration set continues to apply against the test datasource)."
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
redteam_audits: []
---

# M1-393: least-privilege for the Flyway owner role

## Context

Re-verified at source 2026-06-16. `docker/postgres-init.sh:20` creates the
migration owner role as a cluster SUPERUSER:

```
CREATE ROLE infochat WITH LOGIN SUPERUSER PASSWORD '${INFOCHAT_DB_PASSWORD:?...}';
```

The Collector holds these owner credentials (`QUARKUS_DATASOURCE_OWNER_*`) to
run Flyway. A leak of that credential therefore grants full-cluster control and
bypasses every row-level-security policy the schema relies on. The extensions
are already created by the image's own bootstrap superuser in the same init
script, so the owner may not need SUPERUSER at all — this ticket determines the
minimum privilege the migration set actually requires and downgrades if safe.

This is an evaluate-and-justify ticket: the outcome is either a privilege
reduction proven by a clean migration run, or a documented reason SUPERUSER is
required.

## Acceptance / Out-of-scope

See frontmatter.

## Notes

- The Testcontainers test datasource may run with broad privileges independent
  of this init script, so `mvn verify` alone does not prove the reduced grant is
  sufficient; the determination must include a real prod-shape migration pass
  (e.g. a wizard run, or `docker compose --profile prod up --wait
  infochat-collector` with the downgraded role).

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-393-*.md
```
