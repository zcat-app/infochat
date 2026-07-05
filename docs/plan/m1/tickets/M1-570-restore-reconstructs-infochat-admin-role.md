---
id: M1-570
title: restore.sh reconstructs the infochat_admin role before pg_restore
status: done
created: 2026-07-05
last_updated: 2026-07-05
blocked_by: []
remediates: M1-567
files_budget: 4
files_scope:
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - docs/design/07-deployment.md
complexity: medium
risk: high
security_relevant: true
migration_touch: false
round_cap: 3
out_of_scope:
  - >-
    pack.sh's DB dump. Keep the single-database `pg_dump -F c infochat`. The
    infochat_admin role is Flyway-created (V2) and reconstructable at restore
    time, so pack does NOT need to change. Switching pack to `pg_dumpall
    --roles` would carry role password hashes and CREATE-collide with the
    collector/provider roles postgres-init already mints. Do NOT change pack.sh.
  - >-
    docker/postgres-init.sh. infochat_admin is deliberately a Flyway-owned
    principal (V2 __roles), part of the migration-owned authorization boundary
    (security.md §DB roles). Do NOT move its creation into postgres-init — that
    would duplicate/contradict V2. The restore-time creation is a restore-
    specific pre-seed that compensates for a single-DB dump omitting cluster
    roles; it is NOT the canonical role definition.
  - >-
    Flyway migration content (V2 __roles and the per-table GRANT migrations).
    Unchanged. This ticket does not touch the schema or the migration set.
  - >-
    prod/scripts/backup.sh. backup.sh is a backup wrapper, not a restore path —
    it neither reconstructs roles nor runs pg_restore, so it has no analogous
    gap. Frozen contract (M1-427/M1-567). Do not touch it.
  - >-
    The DB data restore (pg_restore), model rehydration, precondition gates,
    identity untar, and app bring-up in restore.sh. This ticket adds ONE step —
    reconstruct the Flyway-created principal role(s) before pg_restore — and
    changes nothing else.
acceptance:
  - >-
    restore.sh creates the Flyway-created NOLOGIN principal role infochat_admin
    (idempotent — `CREATE ROLE infochat_admin NOLOGIN` guarded by a NOT EXISTS
    check, mirroring V2 __roles) AFTER the fresh Postgres is up (3-postgres.sh /
    postgres-init.sh mints infochat + infochat_collector + infochat_provider)
    and BEFORE the pg_restore call (restore.sh:326). Rationale: a single-DB
    pg_dump does not carry cluster-global roles, so infochat_admin is absent on
    the fresh target; the dump's ACL entries that GRANT to it then fail, and
    because pg_dump emits each object's GRANT/REVOKE set as ONE multi-statement
    command, the failure atomically rolls back the co-located infochat_collector
    / infochat_provider grants too.
  - >-
    On a restore run, pg_restore emits ZERO `role "infochat_admin" does not
    exist` errors (today it emits 8 — one per ACL entry that co-references
    infochat_admin: schema public USAGE, heartbeat, source, quarantine,
    invite_code_attempt, audit_log_view, and the approve_quarantine /
    reject_quarantine functions), and the previously-rolled-back service-role
    grants are present on the clone. Concretely: infochat_collector has
    SELECT,INSERT,UPDATE on heartbeat, SELECT on source, SELECT on quarantine;
    infochat_provider has SELECT,INSERT,UPDATE on heartbeat, SELECT,INSERT on
    invite_code_attempt, SELECT on audit_log_view, and EXECUTE on
    approve_quarantine / reject_quarantine.
  - >-
    The collector boots healthy on the restored clone — no `permission denied
    for table heartbeat` crash in AbstractInstanceLockGuard.upsertHeartbeat, and
    restore.sh's `compose up -d --wait infochat-collector` reaches Healthy
    rather than exiting 1. This end-to-end proof is HOST validation (real Docker
    + a real pack->restore round trip), NOT mvn verify — exactly as M1-567's and
    M1-569's round trips are host-validated.
  - >-
    mvn verify is green. RestoreWiringTest pins the ORDERING invariant: the
    role-reconstruction step runs after the Postgres bring-up and strictly
    before the pg_restore invocation (a regression that drops it, or reorders it
    after pg_restore, fails the build). The test asserts the invocation shape /
    order the same way RestoreWiringTest already pins restore's other gated
    steps; the real grant round trip stays host validation.
  - >-
    docs/design/07-deployment.md §7.10.1 documents that restore reconstructs the
    Flyway-created infochat_admin principal before pg_restore, and WHY: a
    single-DB pg_dump omits cluster-global roles, grants to a missing role fail
    and atomically roll back co-located service-role grants, and Flyway's no-op
    over the restored V56 history never repairs them (so the repair must happen
    at restore time, not via a later migration pass).
test_plan:
  adds: []
  modifies:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  preserves:
    - all tests currently green on main
    - >-
      the existing restore gate cases (fail-loud preconditions, M1-568 allowlist,
      M1-569 privileged untar) must still pass — this ticket adds one step, it
      does not alter the existing sequence's other steps.
  notes:
    - >-
      Faithful grant reconstruction end-to-end (pg_restore emits no role errors,
      collector boots) needs real Docker + a real dump; that is HOST validation.
      The wiring test pins the create-role-before-pg_restore ORDER only, mirroring
      M1-567/M1-569 gate-only test scope.
    - >-
      Generalization option for the implementer (PLAN): instead of hardcoding
      infochat_admin, restore.sh could parse the roles referenced in the dump's
      ACLs and create any NOLOGIN principal postgres-init did not mint. v1 has
      exactly one such role (infochat_admin); hardcoding it with a V2-anchored
      comment is acceptable if a "add future Flyway-created principals here" note
      is left. The implementer chooses; keep it simple.
spec_refs:
  - docs/design/07-deployment.md §7.10.1
  - docs/spec/security.md §DB roles
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-05
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 93
      removed: 6
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits:
  - date: 2026-07-05
    verdict: CLEAN
    base: 9634e7fa87170ad5662579b55acf0a7a771e3440
    head: working-tree (branch m1/M1-570, pre-commit)
    verdict_file: docs/plan/m1/redteam/M1-570-2026-07-05.md
    out_of_model_count: 2
    note: |
      CLEAN. CREATE ROLE infochat_admin NOLOGIN confirmed byte-identical in form to
      the canonical V2__roles.sql; grants supplied verbatim by the dump's ACLs; the
      DO-block runs as infochat (CREATEROLE, DB owner) — no escalation, strengthens
      least-privilege reconstruction. 2 out-of-model: (1) tampered bundle — out of the
      v1 threat model, role SQL is a fixed heredoc; (2) silent ACL-error tolerance in
      the pre-existing pg_restore post-check — inherited not introduced, failure is
      under-permissive/availability; optional future grant-verification ticket.
clarity_check:
  date: 2026-07-05
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-570: restore.sh reconstructs the infochat_admin role before pg_restore

## Context

Surfaced by the **M1-567 host-clone round-trip** (the second real defect it
caught, after M1-569). On a full in-place destroy+recover on the live host,
`pack.sh` succeeded (post-M1-569), but `restore.sh` **failed loud** at the
collector bring-up:

```
pg_restore: error: could not execute query: ERROR: role "infochat_admin" does not exist
  ... (x8, one per ACL entry granting to infochat_admin) ...
pg_restore: warning: errors ignored on restore: 10
...
Caused by: org.postgresql.util.PSQLException: ERROR: permission denied for table heartbeat
  at AbstractInstanceLockGuard.upsertHeartbeat(AbstractInstanceLockGuard.java:357)
→ IllegalStateException: InstanceLockGuard could not acquire its Postgres session
→ collector container exited(1) → restore.sh RESTORE_EXIT=1
```

### Root cause

1. `pack.sh` bundles the DB with `pg_dump -F c infochat` — a **single-database**
   dump. Custom-format single-DB dumps do **not** carry cluster-global role
   definitions (only `pg_dumpall` does).
2. `infochat_admin` is created by **Flyway migration V2** (`V2__roles.sql`,
   `NOLOGIN`) — it lives in the source *cluster*, not inside the `infochat`
   database dump. `postgres-init.sh` on the fresh target mints only `infochat`
   (owner), `infochat_collector`, and `infochat_provider` — **not**
   `infochat_admin`.
3. On `pg_restore`, every ACL entry that `GRANT`s to `infochat_admin` fails
   (`role ... does not exist`). Because `pg_dump` emits each object's whole
   GRANT/REVOKE set as **one multi-statement command**, the failure is atomic —
   it also rolls back the `infochat_collector` / `infochat_provider` grants
   bundled in the same entry.
4. Flyway then runs as a **no-op** over the restored `flyway_schema_history`
   (already at V56), so it never re-creates `infochat_admin` and never re-applies
   the lost grants.

Net: the clone is missing `infochat_admin` **and** the service-role grants on
`heartbeat`, `source`, `quarantine`, `invite_code_attempt`, `audit_log_view`,
and the quarantine functions. The collector dies on the first `heartbeat`
write (InstanceLockGuard), so the failure is **loud, not silent** — restore
exits non-zero rather than producing a subtly-broken clone.

### Grants rolled back (the 8 failing ACL entries)

| object | infochat_collector | infochat_provider |
|---|---|---|
| `SCHEMA public` USAGE | (masked by PUBLIC default) | (masked) |
| `heartbeat` | SELECT,INSERT,UPDATE | SELECT,INSERT,UPDATE |
| `source` | SELECT,INSERT | SELECT,INSERT |
| `quarantine` | SELECT,INSERT,UPDATE | — |
| `invite_code_attempt` | — | SELECT,INSERT |
| `audit_log_view` | — | SELECT |
| `approve_quarantine` / `reject_quarantine` fn | — | EXECUTE |

## The fix

Reconstruct the Flyway-created NOLOGIN principal `infochat_admin` **at restore
time, before `pg_restore`** — the same shape as the manual recovery proven on
the live host during the round-trip:

```sh
# restore.sh, between "3-postgres.sh" (fresh DB up) and the pg_restore call:
compose exec -T postgres sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" \
  psql -h 127.0.0.1 -U infochat -d infochat -c \
  "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='"'"'infochat_admin'"'"') \
   THEN CREATE ROLE infochat_admin NOLOGIN; END IF; END \$\$;"'
```

With the role present, the dump's ACL entries apply cleanly (grants to
`infochat_admin` succeed, and the co-located collector/provider grants land),
so the clone comes up correct on the **first** restore — no manual repair, no
whack-a-mole.

`infochat` is `CREATEROLE` (postgres-init), so it can create the NOLOGIN role
without superuser. NOLOGIN means no password / no secret is involved.

## Alternatives considered

- **`pack.sh` uses `pg_dumpall --roles-only`** and restore applies it first —
  rejected: it carries role password hashes and CREATE-collides with the
  collector/provider roles postgres-init already mints; a filtered variant is
  just this ticket's role-creation with extra moving parts. Keep pack's dump
  single-DB.
- **postgres-init.sh creates infochat_admin** — rejected: it would duplicate and
  potentially drift from V2, which owns the role as part of the authorization
  boundary. The restore-time pre-seed is explicitly a *compensation for the
  single-DB dump*, not the canonical definition.

## Provenance

Found by the M1-567 host round-trip (in-place destroy+recover on the live host),
not a red-team finding. Filed `remediates: M1-567` because M1-567 is done+merged
(e9a3a027) and immutable. The live deployment was recovered manually the same
way this ticket automates (create `infochat_admin` NOLOGIN → re-apply the dump's
ACL section → restart) and verified healthy (both adapters
`adapter_connection_status=1`, golden row counts intact). This ticket makes the
NEXT restore correct without that manual step.
