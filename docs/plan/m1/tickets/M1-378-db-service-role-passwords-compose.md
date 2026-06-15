---
id: M1-378
title: "deploy: env-driven service-role passwords via docker/postgres-init.sh + compose wiring"
status: done
created: 2026-06-15
last_updated: 2026-06-15
clarity_check:
  date: 2026-06-15
  verdict: WARN
  warnings:
    - "Acceptance items 1 and 3 rely on commit-message evidence for the most critical behavioral assertions (fail-loud on unset variable; end-to-end psql authentication); inherently manual infra checks, no independent reviewer verification path beyond trusting the author's claimed run."
  blockers: []
blocked_by: []
files_budget: 4
files_scope:
  - docker/postgres-init.sh
  - docker-compose.yml
  - .gitignore
  - docs/design/07-deployment.md
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
  - "docker/postgres-init.sh exists: a `set -eu` shell script piping a heredoc to `psql -v ON_ERROR_STOP=1` so the SHELL (not psql) evaluates `${VAR:?msg}`. It creates the infochat owner role + database and infochat_collector / infochat_provider with LOGIN and passwords from INFOCHAT_DB_PASSWORD / INFOCHAT_COLLECTOR_PASSWORD / INFOCHAT_PROVIDER_PASSWORD, so the roles Flyway later manages already have credentials. An unset OR empty password variable makes the container exit non-zero (the `:?` colon form fails on both) rather than create a passwordless/unusable role. NOTE: a bare `.sql` init file does NOT receive this substitution — empirically verified during M1-378 against pgvector/pgvector:pg16 — only a `.sh` does (manual: `docker compose --profile prod up postgres` with one var unset exits non-zero — commit-message evidence)."
  - "docker-compose.yml mounts docker/postgres-init.sh at /docker-entrypoint-initdb.d/ and wires the three INFOCHAT_*_PASSWORD env vars (INFOCHAT_DB_PASSWORD, INFOCHAT_COLLECTOR_PASSWORD, INFOCHAT_PROVIDER_PASSWORD) as pass-throughs with an empty default (`${VAR:-}`) — no baked secret: an operator's exported value (or a local .env / the wizard's secrets.env) is used verbatim, while an unset var resolves to empty and trips the init script's `${VAR:?}` fail-loud guard (item 1). Compose performs NO command substitution in `${VAR:-}` defaults (verified: `${VAR:-$(openssl rand -hex 24)}` renders the literal `$$(openssl rand -hex 24)`, not a random value), so randomness is the wizard's job (`2-secrets.sh`, shell `openssl rand`), not the compose file's. The postgres service no longer hard-codes a single POSTGRES_USER=infochat role (grep -E confirms the volume mount and the three env entries)."
  - "docs/design/07-deployment.md §Database role bootstrap is corrected to match this implementation: the snippet shows a docker/postgres-init.sh psql-heredoc wrapper (not a bare .sql), the prose no longer claims the postgres image substitutes `${VAR:?}` inside .sql init files, and the three filename references (the §7.7 repo-layout diagram, §7.7.2 step 3, the §7.7.2 dependencies list) read postgres-init.sh (grep -E confirms zero remaining 'postgres-init.sql' references in the file)."
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
reviews:
  - round: 1
    date: 2026-06-15
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 135
      removed: 43
escalations:
  - date: 2026-06-15
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise-fail surfaced during implementation, before any review.
      Acceptance item 1 and the cited design contract (07-deployment.md
      §Database role bootstrap, lines 567-577) prescribe creating the service
      roles via "the postgres image's ${VAR:?msg} env substitution" inside
      docker/postgres-init.sql. Empirically falsified against pgvector/pgvector:pg16
      (docker 29.5.1): the postgres entrypoint runs *.sql init files through psql,
      which does NOT perform shell ${VAR} substitution. With the password var
      UNSET, the container did NOT exit non-zero — it created the role with the
      LITERAL password string '${TEST_PW:?TEST_PW is required}' and reported
      "PostgreSQL init process complete". This is the opposite of the ticket's
      security intent (fail-loud, no unusable role). Shell ${VAR:?} substitution
      only fires for *.sh init files, not *.sql.
revisions:
  - date: 2026-06-15
    reason: "premise-fail refine — the postgres image does NOT perform shell ${VAR:?} substitution inside *.sql init files (only *.sh), empirically falsified against pgvector/pgvector:pg16 (see escalations[0]). Switch the init artifact docker/postgres-init.sql -> docker/postgres-init.sh (a psql -v ON_ERROR_STOP=1 heredoc so the SHELL evaluates ${VAR:?}, failing loud on unset OR empty), add docs/design/07-deployment.md to files_scope to correct the wrong §Database role bootstrap snippet + its 3 filename references, files_budget 3->4, reword acceptance items 1-2, and add an acceptance item for the design-note correction. Behavioral goal unchanged (fail-loud service-role password bootstrap); only the mechanism and the design-note contract change. ALT A (.sh heredoc) chosen over a .sql + psql \\getenv variant for simplicity/robustness (user decision, 2026-06-15)."
    prior_values: |
      title (pre-refine): "deploy: env-driven service-role passwords via docker/postgres-init.sql + compose wiring"
      files_budget (pre-refine): 3
      files_scope (pre-refine):
        - docker/postgres-init.sql
        - docker-compose.yml
        - .gitignore
      acceptance item 1 (pre-refine): "docker/postgres-init.sql exists and, using the postgres image's ${VAR:?msg} env substitution, creates infochat_collector and infochat_provider with LOGIN and passwords ... an unset password variable makes the container exit non-zero rather than create a passwordless role (... commit-message evidence)."
      acceptance item 2 (pre-refine): "docker-compose.yml mounts docker/postgres-init.sql at /docker-entrypoint-initdb.d/ ... (grep -E confirms the volume mount and the three env entries)."
  - date: 2026-06-15
    reason: "second premise-fail refine, surfaced during implementation — the design's/acceptance's dev default `${VAR:-$(openssl rand -hex 24)}` does NOT work: docker compose performs no command substitution in interpolation defaults (empirically `docker compose config` renders the literal `$$(openssl rand -hex 24)`). Worse, ANY compose-level default makes the var never-empty at the container, so the init.sh `${VAR:?}` fail-loud guard (item 1) can never fire, and a compose-level `${VAR:?}` would instead break item 4 (`docker compose config` exits 0). Resolved (user decision 2026-06-15): wire the three vars as empty pass-throughs `${VAR:-}` so the operator's value wins, an unset var reaches the container empty and trips init.sh's fail-loud guard (item 1 ✓), and `compose config` still exits 0 (item 4 ✓). Item 2's `${VAR:-$(openssl …)}` dev-default clause replaced by the pass-through description; items 1, 3, 4, 5, 6 unchanged. Dev supplies the three vars via exported env or a local git-ignored .env."
    prior_values: |
      acceptance item 2 (pre-2nd-refine): "... supplies the three INFOCHAT_*_PASSWORD env vars ... with dev-only `${VAR:-$(openssl rand -hex 24)}` defaults; ..."
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-15
    verdict: CLEAN
    base: main
    head: m1/M1-378-db-service-role-passwords-compose
    verdict_file: docs/plan/m1/redteam/M1-378-2026-06-15.md
    out_of_model_count: 2
    note: |
      In-progress audit run between /m1-tick commit and merge. CLEAN — no
      findings. Diff is infra-only (postgres-init.sh + compose + design note);
      all five sensitive-surface inventories were empty. Two advisory
      out-of-model observations recorded in the verdict file; advisory only,
      no remediation required to merge.
---

# M1-378: env-driven service-role passwords via docker/postgres-init.sh

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

This ticket adds `docker/postgres-init.sh` to set those passwords from env at
container init (before the Collector's first Flyway pass, so it creates the
roles WITH passwords and Flyway's `DO`-block `IF NOT EXISTS` role guard then
no-ops), and wires the env vars into `docker-compose.yml` with the dev-only
random defaults the design specifies. It is the first dependency of the
containerized prod stack (`07-deployment.md` §7.7.2 wizard).

The init artifact is a `.sh`, not a `.sql`: the official postgres image runs
`*.sql` init files through `psql`, which does NOT expand shell `${VAR}`
references, so a `.sql` file would create roles with a literal `${...}` password
and never fail loud. Shell `${VAR:?}` substitution only fires for `*.sh` init
files (empirically verified during M1-378 against pgvector/pgvector:pg16).

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
- No literal passwords in the init file; rely on the SHELL's `${VAR:?}`
  fail-loud expansion (the reason the init artifact is a `.sh`, not a `.sql` —
  psql does not expand `${VAR}`) so a missing or empty secret exits the
  container non-zero.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-378-*.md
```
