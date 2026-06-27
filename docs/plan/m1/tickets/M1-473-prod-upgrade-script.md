---
id: M1-473
title: "prod/scripts/upgrade.sh: git-pull main + rebuild app images + ordered restart, all config/data preserved"
status: done
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
clarity_check:
  date: 2026-06-27
  verdict: WARN
  warnings:
    - "Item 7 health gate named three alternative mechanisms; narrowed to one (docker compose ps health) in this revision."
    - "spec_refs section titles were in YAML comments (stripped at parse); moved into the YAML values."
escalations:
  - date: 2026-06-27
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A (premise-fail — surfaced during implementation, not a review).
      Acceptance item 5 (config-key drift) named a diff of
      prod/config/application.properties against the runtime copy. That
      template file DOES NOT EXIST: the config model is two-layer (baked-in
      module-default application.properties at config ordinal 250 vs. the
      mounted prod/runtime/ override at 260), settled by commit 9c0a6ea0
      ("document config layering") + 07-deployment.md §7.6.2. Only the
      bootstrap JSONs and secrets.env have committed prod/config/ templates;
      application.properties never has one. The clarity pre-flight rated item 5
      PASS ("mechanism ... is unambiguous") without verifying the referenced
      path resolves on disk — a false-premise miss we both passed over.
revisions:
  - date: 2026-06-27
    reason: "premise-fail refine (round 1): item 5 config-drift premise was false"
    snapshot:
      acceptance_item_5_old: |
        Config-key drift surfaced, not merged: after the pull and before the
        recreate, the script runs a diff of prod/config/application.properties
        against prod/runtime/application.properties and prints any keys present
        in the new template but absent from the live runtime copy as a WARN
        telling the operator to merge manually. It does not edit the runtime file.
      why_invalid: |
        prod/config/application.properties does not exist. Config is two-layer
        (baked module defaults at ordinal 250 vs the mounted prod/runtime/
        override at 260), per 07-deployment.md §7.6.2 and commit 9c0a6ea0. A
        diff against a non-existent template never fires; diffing the runtime
        override (~28 keys) against the baked defaults (~176 keys) would be pure
        noise (the override is intentionally a subset). Replaced by a git diff of
        the baked module application.properties across the upgrade's two commits
        plus an operator-override-key listing (new item 5 below).
reviews:
  - round: 1
    date: 2026-06-27
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 5
      added: 448
      removed: 38
out_of_scope:
  # DB schema rollback is NOT automated. Flyway migrates forward only
  # (07-deployment §7.11 forward-compatible note); the only safe rollback
  # across a migration is restore-from-backup, which stays a printed manual
  # step pointing at the artifact backup.sh produced. The script auto-rolls
  # back CODE (git checkout the recorded SHA + rebuild) only.
  - "DB schema downgrade / reverse-migration automation"
  # The script shows the operator the baked-config diff across the upgrade's two
  # commits and lists their override keys, but never edits the runtime override
  # file — it cannot know operator intent for a new/changed key's value, and the
  # config model is layered (new keys ship with baked defaults; no merge needed).
  - "Auto-merging or editing prod/runtime/application.properties"
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
    Preflight gate. The script aborts BEFORE any backup/pull/build when:
    (a) the deployment is not yet configured — `prod/runtime/secrets.env` is
    absent — with the actionable message to run `prod/setup.sh` first (upgrade
    is only meaningful on an already-set-up deployment; mirrors backup.sh's
    secrets.env precondition). This runs as upgrade.sh's OWN first check, not
    only via the delegated backup.sh, so the failure is attributable to upgrade
    and fires before anything irreversible;
    (b) `git status --porcelain` over TRACKED files is non-empty (operator-
    edited tracked files would block --ff-only) — prod/runtime/ is gitignored
    so it is never a source of conflict and is never inspected;
    (c) docker is not on PATH or the compose file is absent (mirrors apps.sh
    preconditions). Each abort exits non-zero with a one-line actionable fix.
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
    Config awareness across the upgrade, using the committed baked-config
    reference (there is NO prod/config/application.properties template — config
    is two-layer, baked defaults at ordinal 250 vs the mounted prod/runtime/
    override at 260, §7.6.2). After the pull, the script runs
    `git diff <pre-upgrade-sha>..<post-upgrade-sha> -- ` the three baked module
    application.properties files (collector, provider, messaging-adapter) and,
    when non-empty, prints that diff under a header announcing the config keys
    this release added / removed / changed-default. It then lists the operator's
    own override property names (read from prod/runtime/application.properties)
    and reminds them to reconcile — in particular, any key they override that
    this release removed or renamed silently goes dead (the override targets a
    key the new build no longer reads). When the baked config did not change
    between the two commits it prints a single "no config changes this release"
    line. The script never writes under prod/runtime/ and never edits the
    runtime override file.
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
    app service reports a healthy container state via `docker compose ps`
    (the same health signal apps.sh status surfaces; both app services already
    declare compose healthchecks) and reports success with the new commit SHA,
    or triggers the rollback path above on failure. The Collector's
    `up -d --wait` in step 5 is the primary gate (a migration/boot failure
    fails the wait); this final ps check confirms both services settled healthy
    after the Provider also started.
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
  # §7.11 is the rewrite target; §7.8.5 (advisory lock) and §7.10 (backup/restore)
  # are preserved/referenced; §7.7.2 / §7.9 carry the Collector-then-Provider
  # startup ordering this script reuses.
  - "docs/design/07-deployment.md §7.11"
  - "docs/design/07-deployment.md §7.8.5"
  - "docs/design/07-deployment.md §7.10"
  - "docs/spec/deployment.md §Topology"
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
