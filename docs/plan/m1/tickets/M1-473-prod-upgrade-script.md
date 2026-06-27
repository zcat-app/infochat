---
id: M1-473
title: "prod/scripts/upgrade.sh: git-pull main + rebuild app images + ordered restart, all config/data preserved"
status: todo
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 4
files_scope:
  - prod/scripts/upgrade.sh
  - docs/design/07-deployment.md
  - SETUP_GUIDE.md
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  # DB schema rollback is NOT automated. Flyway migrates forward only
  # (07-deployment §7.11 forward-compatible note); the only safe rollback
  # across a migration is restore-from-backup, which stays a printed manual
  # step pointing at the artifact backup.sh produced. The script auto-rolls
  # back CODE (git checkout the recorded SHA + rebuild) only.
  - "DB schema downgrade / reverse-migration automation"
  # New release may add keys to the prod/config/application.properties
  # template; the operator's live prod/runtime/application.properties is a
  # copy. The script DIFFS and WARNS but never auto-merges — it cannot know
  # operator intent for a new key's value.
  - "Auto-merging new application.properties keys into prod/runtime/"
  # Postgres (pgvector/pgvector:pg16) and the LLM backends (ollama, llama.cpp)
  # are `image:` (pulled), not `build:`. An app upgrade rebuilds only the two
  # app services; bumping a pinned base-image tag is a separate compose edit +
  # `docker compose pull`, not this script's job.
  - "Upgrading the postgres / ollama / llama.cpp base images"
  # The stale §7.11 described a /opt/infochat releases+symlink+systemd jar
  # layout. That shape is not the shipped deployment and is removed, not ported.
  - "The /opt/infochat systemd/jar deployment shape"
  # No shell-script test harness exists in the repo (verified: no .bats, no
  # *test*.sh besides scripts/verify-serialized.sh). This ticket adds none.
  - "A new shell-test harness for prod/scripts"
acceptance:
  - >-
    New executable prod/scripts/upgrade.sh follows the established prod-script
    preamble convention (set -euo pipefail; SCRIPT_DIR / PROD_DIR / REPO_ROOT /
    RUNTIME_DIR / SECRETS_FILE / COMPOSE_FILE derived exactly as in
    apps.sh / 7-apps.sh / backup.sh) and accepts {-h|--help} plus a -y/--yes
    flag. Default (no -y) is interactive: it prompts for confirmation before
    each irreversible gate (backup, git pull, container recreate). With -y it
    runs the whole flow unattended. An unknown arg exits 2 with usage, matching
    apps.sh.
  - >-
    Preflight gate: the script aborts with an actionable message if `git
    status --porcelain` over TRACKED files is non-empty (operator-edited
    tracked files would block --ff-only). prod/runtime/ is gitignored so it is
    never a source of conflict and is never inspected. Also aborts loudly if
    docker is not on PATH or the compose file is absent (mirrors apps.sh
    preconditions).
  - >-
    Order of operations, each gated by a confirm in interactive mode:
    (1) run prod/scripts/backup.sh and capture the artifact paths it prints;
    (2) record the current commit via `git rev-parse HEAD` into a shell var for
    rollback; (3) `git fetch origin` then `git pull --ff-only origin main`
    from REPO_ROOT; (4) `docker compose -f COMPOSE_FILE --env-file SECRETS_FILE
    --profile prod build infochat-collector infochat-provider` while the OLD
    containers keep serving (build precedes any recreate, so a compile failure
    on new main never stops the running bot — same build-before-up separation
    7-apps.sh uses and for the same attributability reason); (5) bring the apps
    up in §Topology order — `up -d --wait --wait-timeout` the Collector (it
    runs Flyway under the §7.8.5 advisory lock) THEN `up -d` the Provider.
  - >-
    Config / data preservation is asserted by construction and stated in a
    header comment: upgrade.sh never writes any path under prod/runtime/ and
    never passes -v / down to compose, so the infochat-pgdata, ollama, and
    llamacpp-models named volumes and every prod/runtime/ bind-mount
    (application.properties, secrets.env, bootstrap-*.json, the SimpleX/Signal
    identity dirs) survive the upgrade untouched. Restart is `build` + `up -d`
    (compose recreates only the services whose image changed), never `down`.
  - >-
    Config-key drift surfaced, not merged: after the pull and before the
    recreate, the script runs a diff of prod/config/application.properties
    against prod/runtime/application.properties and prints any keys present in
    the new template but absent from the live runtime copy as a WARN telling
    the operator to merge manually. It does not edit the runtime file.
  - >-
    Auto-rollback of CODE on failure: if the build (step 4) or the
    post-restart health gate fails, the script `git checkout`s the SHA recorded
    in step 2, rebuilds the two app images from that SHA, and brings the apps
    back up in the same Collector-then-Provider order, then exits non-zero.
    Because schema rollback is NOT automated, the same failure path prints the
    exact manual DB-restore command against the step-1 backup artifact path
    (the §7.11 pg_restore procedure) as the recovery step for a migration that
    had already applied.
  - >-
    Post-restart health gate: after both apps are up the script verifies each
    is healthy (compose `ps` health, the same signal apps.sh status surfaces,
    or the /q/health/ready endpoint) and reports success with the new commit
    SHA, or triggers the rollback path above on failure.
  - >-
    docs/design/07-deployment.md §7.11 Upgrade procedure is rewritten to the
    shipped docker-compose deployment: git pull main -> rebuild the two app
    images -> backup-first -> Collector-then-Provider restart -> health check
    -> git-checkout rollback for code + restore-from-backup for schema. The
    stale /opt/infochat releases/<version> + `current` symlink + systemctl
    steps are removed (they describe a layout backup.sh's own header already
    flags as not-the-shipped-deployment). The §7.8.5 advisory-lock paragraph
    that explains why a premature second instance is rejected is preserved.
  - >-
    SETUP_GUIDE.md: prod/scripts/upgrade.sh is added to the lifecycle command
    block (alongside the apps.sh start/stop/restart/status lines, ~L279) and to
    the scripts reference table (~L511), with a one-line description and a
    pointer to §7.11 for the full procedure.
  - >-
    Full pre-existing suite (`mvn verify` from repo root) is green — this
    ticket adds no Java and no migration, so the gate is a no-regression check
    that the doc/script-only change did not disturb the wiring tests.
spec_refs:
  - docs/design/07-deployment.md  # §7.11 (rewrite target), §7.8.5 advisory lock, §7.10 backup/restore, §Topology startup order
  - docs/spec/deployment.md       # §Topology; restarts/rolling-upgrades contract
notes: >-
  Origin: user request (2026-06-27) for an operator upgrade path — "fetch
  latest main, rebuild, restart with all config (DB, LLM settings) preserved."
  Investigation found ~90% of the machinery already exists (prod/scripts/apps.sh
  lifecycle, backup.sh, 7-apps.sh build+ordered-up, compose prod profile with
  source baked into the two app images and all state in named volumes /
  prod/runtime/ bind-mounts that are gitignored so `git pull` cannot clobber
  them). This ticket is the missing glue script + the matching §7.11 doc rewrite
  (the documented procedure is stale — it still describes the /opt/infochat
  systemd/jar layout). Operator UX decisions confirmed with the user:
  confirm-at-each-gate by default with a -y unattended flag; auto-rollback of
  code on failure, manual restore-from-backup for schema.
  Alternatives considered: a `setup.sh --upgrade` flag — rejected, setup.sh is
  first-run/idempotent-resume; upgrade is a distinct lifecycle verb that belongs
  beside apps.sh and backup.sh.
---

## Summary

Add `prod/scripts/upgrade.sh`: a single named wrapper an operator runs to move a
running deployment to the latest `main` — backup, pull, rebuild the two app
images, restart Collector-then-Provider — with all data and config preserved
(named volumes + `prod/runtime/` bind-mounts, which `git pull` never touches),
auto-rollback of code on failure, and manual restore-from-backup for schema.
Rewrite the stale §7.11 upgrade procedure to match the shipped compose
deployment.

See ticket frontmatter for acceptance, scope, and the confirmed UX decisions.
