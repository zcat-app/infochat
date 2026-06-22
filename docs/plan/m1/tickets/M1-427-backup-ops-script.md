---
id: M1-427
title: Operator backup ops script (prod/scripts/backup.sh)
status: pending
created: 2026-06-22
last_updated: 2026-06-22
blocked_by: []
files_budget: 5
files_scope:
  - prod/scripts/backup.sh
  - docs/design/07-deployment.md
  - SETUP_GUIDE.md
  # Test approach for a prod shell script is deferred to start-time (clarity /
  # plan): the M1-418 precedent is a JUnit/ProcessBuilder harness under a module
  # src/test/java tree, because no bats harness or prod/scripts/test/ exists and
  # only the mvn-verify-integrated path runs in the gate. Add the chosen test
  # path here at start if a harness is feasible.
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # This is a standalone, operator-cron-invoked upkeep script — NOT a wizard step
  # (prod/setup.sh never registers it; §7.7.1 lists it as production upkeep,
  # separate from the dev inner loop). Do not restructure the compose stack or the
  # wizard.
  - prod/setup.sh
  - docker-compose.yml
  - infochat-collector/src/main/java/**
  - infochat-provider/src/main/java/**
  # Retention/pruning of old backups stays in the operator's crontab (the `find`
  # lines in §7.10), NOT inside the script.
acceptance:
  - prod/scripts/backup.sh exists, is executable, starts `set -euo pipefail`, and
    passes shellcheck clean.
  - Running it produces two artifacts in a backup directory — (a) a PostgreSQL dump
    of the `infochat` database in custom format (`pg_dump -F c`), and (b) a tar of
    every configured per-adapter bot-identity data-dir (the SimpleX queue keypair
    and the signal-cli account directory — the unrecoverable material from §7.10).
  - The script works against the SHIPPED docker-compose deployment, not the
    `/opt/infochat` systemd/jar layout the §7.10 snippet assumes — the DB dump runs
    against the compose `postgres` service (e.g. via `docker compose exec`), and
    the identity tar covers the operator's `INFOCHAT_SIMPLEX_DATA_DIR` /
    `INFOCHAT_SIGNAL_DATA_DIR` paths sourced from `prod/runtime/secrets.env`.
  - The backup directory is configurable (env or arg) with a sensible default;
    artifact names carry a date stamp so the crontab `find ... -mtime` retention
    lines in §7.10 still match.
  - Adapters that are not enabled are skipped without error (the tar covers only
    configured data-dirs); a missing data-dir fails loud rather than silently
    producing an empty tar.
  - docs/design/07-deployment.md §7.10 is reconciled to the compose deployment —
    the literal `pg_dump -U infochat ... /opt/infochat` commands are updated to the
    container reality, and the doc no longer references the script as if it already
    exists.
  - SETUP_GUIDE.md "Back up your data" section points at the script and shows a
    sample cron entry (the script call + the independent retention `find` lines).
test_plan:
  adds:
    # - test approach TBD at start (see files_scope note; mirror M1-418's
    #   ProcessBuilder harness if a module test tree is the only gated path).
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Backups, rotation, secrets
decision_refs:
  - D34
  - D46
---

# M1-427: Operator backup ops script (prod/scripts/backup.sh)

## Context

`docs/design/07-deployment.md` §7.10 (Backups) and §7.7.1 (the ops-scripts file
map) name `prod/scripts/backup.sh` as the recommended cron entry point — "so the
operator's crontab calls one named wrapper rather than inlining `pg_dump` / `tar`
invocations" — but the script **does not exist**: `prod/scripts/` currently holds
only the wizard subscripts (`0-doctor.sh` … `8-verify.sh`). The operator guides
(SETUP_GUIDE §"Back up your data") deliberately do NOT tell operators to run it
yet for that reason. This ticket creates the script and closes the doc-vs-reality
gap.

**Deployment-model reconciliation (the real work).** The §7.10 snippet assumes a
systemd/jar deployment (`pg_dump -U infochat ...`, `tar -C /opt/infochat ...
adapters/`). The shipped deployment is docker-compose: the database runs in the
`postgres` compose service against the `infochat-pgdata` volume, and the adapter
identity material lives at the operator-chosen `INFOCHAT_*_DATA_DIR` paths
bind-mounted into the Provider (docker-compose.yml). The script — and the §7.10
commands — must target that reality, not the `/opt/infochat` layout.

## Acceptance

See the YAML `acceptance:`. In short: a shellcheck-clean, `set -euo pipefail`
wrapper that dumps the compose Postgres DB (custom format) and tars the configured
adapter identity dirs into a configurable, date-stamped backup directory; retention
stays in the crontab; §7.10 and the SETUP_GUIDE backup section are reconciled to it.

## Out-of-scope

Not a wizard step — do not touch `prod/setup.sh` or restructure
`docker-compose.yml`. No app-code changes. Retention/pruning of old backups stays
in the operator's crontab (§7.10's `find` lines), not in the script. The restore
procedure (§7.10) and the disaster-recovery table (§7.15) already exist as docs and
are not re-implemented here.

## Notes

- **Backups contain sensitive material** (the audit log inside the DB dump, and the
  irreplaceable identity keypairs in the tar). Encryption-at-rest is the operator's
  responsibility per D34/§7.10 — out of scope for the script itself, but the
  SETUP_GUIDE pointer must keep saying "encrypted at rest".
- **Test approach (complexity:medium).** Settle at start whether a ProcessBuilder /
  scripted-run harness like M1-418's `SwitchLlmWiringTest` can assert the script's
  invocations inside `mvn verify`; if no gated path fits, document that and rely on
  shellcheck (state it explicitly — no silent skip).
- Mirror the existing wizard conventions for reading `secrets.env` values
  (`INFOCHAT_*_DATA_DIR`) rather than inventing new ones.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-427-*.md
```
