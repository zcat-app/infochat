---
id: M1-427
title: Operator backup ops script (prod/scripts/backup.sh)
status: done
created: 2026-06-22
last_updated: 2026-06-22
blocked_by: []
files_budget: 4
files_scope:
  - prod/scripts/backup.sh
  - docs/design/07-deployment.md
  - SETUP_GUIDE.md
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
  # No gated JUnit/ProcessBuilder harness (see Notes "Test approach"): a thin
  # pg_dump+tar wrapper does not warrant the M1-418 SwitchLlmWiringTest machinery.
acceptance:
  - prod/scripts/backup.sh exists, is executable, starts `set -euo pipefail`, and
    passes shellcheck clean (author-run, matching the existing prod/scripts
    convention; the build has no shell-lint gate to add it to).
  - Running it produces two artifacts in a backup directory — (a) a PostgreSQL dump
    of the `infochat` database in custom format (`pg_dump -F c`), and (b) a tar of
    every configured per-adapter bot-identity data-dir (the SimpleX queue keypair
    and the signal-cli account directory — the unrecoverable material from §7.10).
  - The two artifacts are exactly the inputs the §7.10 restore procedure consumes —
    the custom-format dump is `pg_restore`-able, and the identity tar preserves file
    modes (§7.10 restore step 3 warns both clients reject world-readable keys, so
    `tar` must not widen permissions). A backup that cannot be restored is the
    failure mode this script exists to prevent; the success bar is restorability,
    not merely "a file was produced".
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
    the literal `pg_dump -U infochat ... /opt/infochat` commands AND the
    `/opt/infochat/current/scripts/backup.sh` cron path are updated to the
    container reality, and the doc no longer references the script as if it already
    exists.
  - SETUP_GUIDE.md "Back up your data" section points at the script and shows a
    sample cron entry (the script call + the independent retention `find` lines).
    The existing "encrypted at rest" guidance and the `infochat-pgdata` /
    identity-dir / secrets enumeration stay intact.
test_plan:
  adds:
    # No gated test. shellcheck (author-run) covers syntax/quoting; restorability
    # is verified by a manual pg_dump → pg_restore round-trip on a NON-PROD DB
    # before declaring done (the §7.10 checklist already mandates "tested on a
    # non-prod DB first"). A ProcessBuilder harness asserting shell string
    # assembly was considered and rejected as disproportionate for a two-command
    # wrapper; a real restore round-trip — the only test that proves the script's
    # value — needs live Postgres + compose and is run by hand, recorded in the
    # commit message. This is an explicit decision, not a silent skip.
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Backups, rotation, secrets
decision_refs:
  - D34
  - D46
clarity_check:
  date: 2026-06-22
  verdict: WARN
  warnings:
    - "ACCEPTANCE-RUNNABLE item 1: shellcheck is author-run, not CI-gated (ticket acknowledges this; reviewer runs shellcheck on the diff)."
    - "SECURITY-FLAG-CONSISTENT: script handles sensitive backup material (identity keypairs, audit-log DB dump); security_relevant:true would be more accurate. Acceptance item 3 (mode-preserving tar) + encrypted-at-rest pointer address the practical risks; /redteam to run on the diff before merge."
  blockers: []
reviews:
  - round: 1
    date: 2026-06-22
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 172
      removed: 16
redteam_findings: []
redteam_audits:
  - date: 2026-06-22
    verdict: CLEAN
    base: 039a5df21d22946261ad75bc5e2c470588fdad59
    head: WORKING-TREE (in-review, pre-commit; --in-progress)
    verdict_file: docs/plan/m1/redteam/M1-427-2026-06-22.md
    out_of_model_count: 0
    note: |
      Pre-commit adversarial pass triggered by the clarity SECURITY-FLAG-CONSISTENT
      WARN (script handles identity keypairs + audit-log DB dump). CLEAN — no gap
      between the threat model and the diff; the secrets-handling surface
      (container-env DB password, never-sourced secrets.env, mode-preserving
      identity tar, operator-owned encryption-at-rest) holds. No remediation.
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
adapter identity dirs into a configurable, date-stamped backup directory; the
artifacts are restorable via the existing §7.10 restore steps (custom-format dump,
mode-preserving tar); retention stays in the crontab; §7.10 and the SETUP_GUIDE
backup section are reconciled to it.

## Out-of-scope

Not a wizard step — do not touch `prod/setup.sh` or restructure
`docker-compose.yml`. No app-code changes. Retention/pruning of old backups stays
in the operator's crontab (§7.10's `find` lines), not in the script. The restore
procedure (§7.10) and the disaster-recovery table (§7.15) already exist as docs and
are not re-implemented here. No gated test harness (see Notes).

## Notes

- **Backups contain sensitive material** (the audit log inside the DB dump, and the
  irreplaceable identity keypairs in the tar). Encryption-at-rest is the operator's
  responsibility per D34/§7.10 — out of scope for the script itself, but the
  SETUP_GUIDE pointer must keep saying "encrypted at rest".
- **Test approach (resolved).** No gated JUnit/ProcessBuilder harness. The M1-418
  `SwitchLlmWiringTest` precedent fits a script with real branching/output logic;
  this is a two-command `pg_dump` + `tar` wrapper, and the test that actually
  proves its worth is a `pg_dump` → `pg_restore` round-trip against live Postgres,
  which is heavier and flakier than the thing it guards. Gate it with shellcheck
  (syntax/quoting) plus a manual restore round-trip on a non-prod DB, recorded in
  the commit message. Stated explicitly so this is a decision, not a silent skip.
- **Restorability is the success bar**, not "a file appeared". A dump that
  `pg_restore` rejects, or a tar that widens key permissions so the clients refuse
  the restored identity, is a failed backup even though both artifacts exist.
- Mirror the existing wizard conventions for reading `secrets.env` values
  (`INFOCHAT_*_DATA_DIR`) rather than inventing new ones.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-427-*.md
```
