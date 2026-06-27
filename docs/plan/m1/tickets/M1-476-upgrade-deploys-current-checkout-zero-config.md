---
id: M1-476
title: "upgrade.sh deploys the current checkout with zero operator config"
status: pending
created: 2026-06-27
last_updated: 2026-06-27
blocked_by: []
files_budget: 3
files_scope:
  - prod/scripts/upgrade.sh
  - prod/scripts/backup.sh
  - docs/design/07-deployment.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  # The §Upgrade section of ADMIN_GUIDE.md is added in a follow-up only AFTER
  # the operator has manually verified the fixed upgrade.sh end-to-end (the
  # whole reason this ticket exists). Not in this diff.
  - "ADMIN_GUIDE.md upgrade section"
  # The chosen trigger design compares Docker image ids (running container vs
  # freshly-built image). Baking the build commit into the image
  # (Dockerfile LABEL / build-arg) was the rejected alternative — it adds a
  # hand-maintained proxy for what Docker already knows. Not done here.
  - "Baking the build commit into the app image (Dockerfile LABEL/build-arg)"
  # The config-diff step diffs the git pull range. When the deploy is purely
  # local commits (pull is a no-op) there is no committed reference point for
  # the range without the baked build SHA above, so config-drift is not
  # surfaced for that case. Best-effort over the pull range only; unchanged.
  - "Config-drift detection across already-local commits (no pull range)"
  # Unchanged from M1-473: Flyway is forward-only; schema rollback stays a
  # printed manual restore-from-backup step, never automated.
  - "DB schema downgrade / reverse-migration automation"
  # No shell-script test harness exists in the repo (M1-473 verified this and
  # added none). This ticket adds none either.
  - "A shell-test harness for prod/scripts"
acceptance:
  - >-
    prod/scripts/backup.sh DEFAULT_BACKUP_DIR changes from /backups to
    "$RUNTIME_DIR/backups" (which resolves under prod/runtime/, gitignored and
    owned by the runtime user) so the no-arg invocation works with zero
    operator config and the existing `mkdir -p "$BACKUP_DIR"` creates it. The
    documented precedence is unchanged: positional arg > $INFOCHAT_BACKUP_DIR >
    the new default. upgrade.sh's own backup_dir fallback (used only to build
    the rollback note's artifact path) changes to the same
    "${INFOCHAT_BACKUP_DIR:-$RUNTIME_DIR/backups}" so the two scripts agree.
  - >-
    upgrade.sh no longer exits when the git pull does not move HEAD. The
    `git fetch origin` + `git pull --ff-only origin main` stay, but the
    `new_sha == original_sha → "already up to date — nothing to upgrade"`
    early-exit is removed: a no-op pull falls through to the rebuild/restart
    decision instead of ending the run.
  - >-
    upgrade.sh decides whether to restart by comparing, per app service, the
    RUNNING container's image id (`docker compose ps -q <svc>` → on a non-empty
    container id, `docker inspect -f '{{.Image}}' <cid>` with the `sha256:`
    prefix stripped; treated as empty when the service is not running) against
    the FRESHLY-BUILT image id (`docker compose ... images -q <svc>`) read
    AFTER the build. It always runs `docker compose ... build` for both app
    services (a full cache hit is cheap and yields the same image id). When
    every app service's running image id equals its freshly-built image id, the
    script prints a "no change — already deployed at <sha>; nothing to restart"
    line and exits 0 without recreating any container.
  - >-
    A service whose running image id is empty (the container is not running)
    counts as changed, so an upgrade run brings up app containers that were
    stopped rather than leaving them down.
  - >-
    Net effect, asserted in the header comment: on a deployment whose app
    images are stale relative to the local checkout, a bare
    `prod/scripts/upgrade.sh` (or `upgrade.sh -y`) rebuilds the two app images
    and restarts them in Collector-then-Provider order — with no
    operator-supplied env var and no git checkout/reset. The git pull becomes
    best-effort (advances the checkout when it is behind origin/main, no-ops
    otherwise) rather than the gate that controls whether any work happens.
  - >-
    All other M1-473 behavior is preserved: the preflight gates (secrets.env,
    clean tracked tree, docker/compose present), backup-first, the per-gate
    confirms in interactive mode, the baked-config diff print over the pull
    range, the Collector-`--wait`-then-Provider ordering under the §7.8.5
    advisory lock, the post-restart health gate, and the auto-rollback of CODE
    (git checkout the recorded SHA + rebuild + ordered restart) with the manual
    schema restore-from-backup note on build or health failure.
  - >-
    docs/design/07-deployment.md is updated: the §7.10 backup default reference
    changes from /backups to prod/runtime/backups and the cron example is made
    self-consistent with that default (the retention `find` paths match the dir
    backup.sh actually writes); §7.11 step 3 / step 5 wording is rewritten so
    the trigger is "always rebuild the current source; restart only when the
    running image differs from the freshly-built one", and the implication that
    an upgrade requires a forward pull to do anything is removed.
  - >-
    Full pre-existing suite (`mvn verify` from repo root) is green — this ticket
    adds no Java and no migration, so the gate is a no-regression check that the
    doc/script-only change did not disturb the wiring tests.
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/design/07-deployment.md §7.11"
  - "docs/design/07-deployment.md §7.10"
  - "docs/design/07-deployment.md §7.8.5"
  - "docs/spec/deployment.md §Topology"
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-476: upgrade.sh deploys the current checkout with zero operator config

## Context

M1-473 shipped `prod/scripts/upgrade.sh` as the operator's upgrade verb. Two
defects make it unusable for how this deployment is actually operated — the
operator commits to the local checkout, then wants to deploy those commits to
the source-baked app images on the same host:

1. **`upgrade.sh` does nothing when the checkout is already current.** The
   whole rebuild/restart flow is gated behind `git pull` advancing HEAD. When
   the deployment checkout *is* the dev tree and already sits at `origin/main`,
   the pull is a no-op, so the script prints "already up to date — nothing to
   upgrade" and exits — leaving stale images (and, as observed, stopped app
   containers) untouched. The rebuild only ever fires as a side effect of the
   pull moving the SHA.
2. **The backup default `/backups` is not writable** by the runtime user, so
   the no-arg backup (step 1 of the upgrade) fails before anything starts, and
   the only "fix" was to make the operator `export INFOCHAT_BACKUP_DIR` — which
   violates the zero-config bar these prod scripts are held to.

This ticket makes a bare `prod/scripts/upgrade.sh` deploy whatever is in the
checkout, with no operator-supplied env var and no git surgery. The contract is
[docs/design/07-deployment.md](../../../design/07-deployment.md) §7.11 (upgrade)
and §7.10 (backup).

## Acceptance

See the YAML `acceptance:` list. In prose, "done" means:

- `backup.sh` defaults its backup directory to a writable, gitignored
  `prod/runtime/backups` (via `$RUNTIME_DIR/backups`), with the arg /
  `$INFOCHAT_BACKUP_DIR` overrides still winning in that order; `upgrade.sh`'s
  fallback matches.
- `upgrade.sh` always rebuilds the two app images from the current source and
  decides whether to recreate containers by comparing each running container's
  image id to the freshly-built image id (normalizing the `sha256:` prefix),
  treating a not-running service as changed. A genuine no-op (every app already
  running its freshly-built image) prints "nothing to restart" and exits 0; a
  stale or stopped service triggers the ordered Collector→Provider restart and
  the health gate.
- The git pull stays but is best-effort (advance when behind, no-op otherwise)
  and no longer controls whether the upgrade does anything.
- All M1-473 safety (preflight, backup-first, ordering, health gate, code
  auto-rollback, manual schema restore note) is preserved.
- §7.10 / §7.11 docs are brought into line with the above.

## Out-of-scope

Covered by the YAML `out_of_scope:` list. In particular: the `ADMIN_GUIDE.md`
upgrade section is deliberately deferred until the operator has hand-verified
the fixed script (the originating request); baking the build commit into the
image is the *rejected* design alternative (Docker's own image id is the source
of truth here, no hand-maintained label); and config-drift surfacing remains
best-effort over the git-pull range only — a pure local-commit deploy (no pull)
has no committed reference point for that range without the baked SHA, so it is
not surfaced and that is acceptable for v1.

## Notes

- Mechanics grounded on this host (compose v5.1.4): `docker compose images -q
  <svc>` returns the built image id even when the service is not running;
  `docker compose ps -q <svc>` is empty for a stopped service; `docker inspect
  -f '{{.Image}}'` prefixes the id with `sha256:` while `images -q` does not, so
  the comparison must strip the prefix.
- The "compare image ids" approach was chosen over baking the commit SHA into
  the image (the rejected `out_of_scope` alternative) because it delegates the
  staleness question to Docker — which already answers it correctly via layer
  caching — instead of maintaining a separate proxy that can drift from reality
  (image built from a dirty tree, manual rebuild, unstamped label). It is also
  strictly less code: no Dockerfile or compose change.
- Adjacent code / existing pattern to match: `prod/scripts/apps.sh`
  (`start_apps` / ordered up), `prod/scripts/backup.sh` (preamble + RUNTIME_DIR
  derivation), and the M1-473 `upgrade.sh` structure being amended.
- Alternatives considered: (a) bake the commit SHA into the image — rejected as
  above; (b) drop the no-op detection entirely and always restart — rejected
  because the image-id compare gives the clean "nothing changed" no-op for free.
