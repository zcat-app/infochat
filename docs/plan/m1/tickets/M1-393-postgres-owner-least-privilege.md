---
id: M1-393
title: "evaluate dropping SUPERUSER from the Postgres infochat owner role (the Collector holds owner creds for Flyway)"
status: done
created: 2026-06-16
last_updated: 2026-06-18
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
reviews:
  - round: 1
    date: 2026-06-18
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 67
      removed: 13
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-06-17
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 1: the positive-outcome path (SUPERUSER removed) is verified by a manual prod-shape wizard run that cannot be reproduced from the diff alone; treat the wizard-run result as a developer attestation rather than a reproducible automated check."
  blockers: []
redteam_findings: []
redteam_audits:
  - date: 2026-06-18
    verdict: CLEAN
    base: 7f619226^
    head: 7f619226
    verdict_file: docs/plan/m1/redteam/M1-393-2026-06-18.md
    out_of_model_count: 1
    note: |
      Adversarial review of the SUPERUSER->CREATEROLE owner-role downgrade
      (docker/postgres-init.sh + docs/design/07-deployment.md §7.7). CLEAN:
      no critical/high/medium/low findings. One out-of-model observation,
      advisory only — no remediation ticket required.
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

## Pause note (2026-06-18)

Implementation is **complete** on branch `m1/M1-393-postgres-owner-least-privilege`
(working tree): `docker/postgres-init.sh` drops SUPERUSER → CREATEROLE + adds the
two `WITH ADMIN OPTION` grants; the determination is recorded in
`docs/design/07-deployment.md §7.7`. The positive path was proven end-to-end by
running the actual modified init script in a real `pgvector/pgvector:pg16`
container and replaying all 51 migrations (V1..V51) as the non-SUPERUSER owner —
clean. (Acceptance item 1 satisfied as a developer attestation per the clarity
WARN.)

**Paused, not done:** acceptance item 2 (`mvn -B verify` exits 0) is blocked by a
pre-existing flaky test on `main` — `EmbeddingWorkerIT.postAlreadyEmbeddedIsNot`
`PickedUpByEnumeratePending`, filed as M1-398 (reproduced 3/3 including on a
clean main checkout; M1-393's docs+shell diff cannot affect it). **Resume:** once
M1-398 lands and `main` is green, run `/m1-tick review M1-393` (no code rework
needed).
