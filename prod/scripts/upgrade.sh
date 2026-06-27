#!/bin/bash
# prod/scripts/upgrade.sh — deploy a CONFIGURED infochat deployment to the
# current checkout: backup → (best-effort) pull → rebuild the two app images
# from the current source → restart Collector then Provider WHEN their image
# changed — preserving ALL data and config.
#
# ZERO-CONFIG: a bare `upgrade.sh` (or `-y`) needs NO operator-supplied env var
# and NO git checkout/reset. It rebuilds from whatever the checkout currently
# holds and redeploys the two app images if they are stale relative to that
# source. The `git pull` is best-effort — it ADVANCES the checkout when it is
# behind origin/main, but it is NOT the gate for whether the upgrade does work.
# This deployment is operated by committing to the local checkout, so the
# checkout is routinely already at origin/main while the running images are
# stale; the rebuild/restart decision is therefore a docker image-id comparison
# (running container vs freshly-built image, Step 5), not "did the pull move
# HEAD". (M1-476)
#
# This is the operator's upgrade verb, sitting beside apps.sh (lifecycle) and
# backup.sh (backups). setup.sh is first-run/idempotent-resume; upgrade is a
# distinct lifecycle action, so it is its own named script rather than a
# setup.sh flag.
#
# WHAT IS PRESERVED, and why nothing here can lose it:
#   - Database  → the infochat-pgdata named volume.
#   - LLM model caches → the infochat-ollama / infochat-llamacpp-models volumes.
#   - Config + secrets + bootstrap files + SimpleX/Signal identities → the
#     prod/runtime/ bind-mounts.
# The two app services BUILD their source into the image; Postgres and the LLM
# backends are pulled `image:`s. So an app upgrade rebuilds ONLY the two app
# images. This script never runs `down`, never passes `-v`, and never writes any
# path under prod/runtime/ — so every volume and every mounted config file
# survives untouched. prod/runtime/ is gitignored, so `git pull` cannot clobber
# operator config either. The restart is `build` (old containers keep serving)
# then `up -d` (compose recreates only the changed services) — minimal downtime.
#
# PRECONDITION — must already be set up. Upgrade is only meaningful on a
# deployment that has been through prod/setup.sh; secrets.env presence is that
# signal (mirrors backup.sh). On a fresh host the script aborts and tells the
# operator to run setup, rather than half-upgrading nothing.
#
# FAILURE HANDLING — auto-rollback of CODE. A build failure or a failed
# post-restart health check rolls the working tree back to the pre-upgrade
# commit, rebuilds, and restarts. Schema rollback is NOT automated (Flyway
# migrates forward only, §7.11): if a migration from the failed upgrade already
# applied, the script prints the manual pg_restore recovery against the backup
# it took in step 1.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
CONFIG_RUNTIME="$RUNTIME_DIR/application.properties"
# The committed config reference is the BAKED module defaults (config ordinal
# 250), which we diff across the upgrade's two commits — there is NO
# prod/config/application.properties template (config is two-layer: baked
# defaults vs the mounted prod/runtime/ override at 260, §7.6.2). Repo-relative
# paths for `git -C "$REPO_ROOT" diff -- ...`.
BAKED_CONFIG_FILES=(
  infochat-collector/src/main/resources/application.properties
  infochat-provider/src/main/resources/application.properties
  infochat-messaging-adapter/src/main/resources/application.properties
)
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
BACKUP_SCRIPT="$SCRIPT_DIR/backup.sh"
# Generous: a cold rebuild + Collector Flyway migration set can exceed five
# minutes before the healthcheck passes. Mirrors apps.sh / 7-apps.sh.
WAIT_TIMEOUT=300
APP_SERVICES=(infochat-collector infochat-provider)

ASSUME_YES=0

usage() {
  echo "Usage: upgrade.sh [-y|--yes] [-h|--help]"
  echo "  Upgrade a configured deployment to the latest origin/main:"
  echo "  backup -> git pull --ff-only -> rebuild the two app images ->"
  echo "  restart Collector then Provider. All data and config are preserved"
  echo "  (named volumes + prod/runtime/ bind-mounts are never touched)."
  echo "    -y, --yes  run unattended (skip the per-gate confirmations)."
  echo "    -h, --help show this help and exit."
  echo "  Must be run on a deployment that has already been through setup.sh."
  echo "  On build/health failure the code is rolled back automatically; a DB"
  echo "  schema change is NOT auto-reverted (restore from the step-1 backup)."
}

# Single place the compose flags live: prod profile (both apps are profile-
# gated) plus the secrets env-file so INFOCHAT_*_PASSWORD / *_API_KEY
# interpolations resolve on any container recreate (mirrors apps.sh). secrets.env
# is guaranteed present by the preflight, so it is always passed.
compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod "$@"
}

# Per-gate confirmation. Returns success (proceed) when --yes was given or the
# operator answers y/Y; failure otherwise so callers can abort the upgrade.
confirm() {
  [[ "$ASSUME_YES" -eq 1 ]] && return 0
  local reply
  read -r -p "$1 [y/N] " reply
  [[ "$reply" == [yY]* ]]
}

# Bring the apps up in the §Topology order: Collector first WITH --wait so its
# healthcheck (which runs Flyway under the §7.8.5 advisory lock) must pass before
# the Provider starts against the migrated schema. Mirrors 7-apps.sh.
start_apps() {
  # Explicit `|| return 1` per step: this function is called as `if ! start_apps`,
  # and bash suppresses `set -e` for the whole body of a function invoked in a
  # condition — without these, a Collector failure would fall through to the
  # Provider step and the caller would only see the Provider's exit code.
  echo "+ starting Collector (waiting up to ${WAIT_TIMEOUT}s for healthy)"
  compose up -d --wait --wait-timeout "$WAIT_TIMEOUT" infochat-collector || return 1
  echo "+ starting Provider"
  compose up -d infochat-provider || return 1
}

# Poll a service's container health until healthy or WAIT_TIMEOUT. docker inspect
# (not `compose ps --format`) reads the health status portably across compose
# versions and needs no jq. A service with no healthcheck reports "none" and is
# treated as not-yet-confirmed until the timeout (both app services DO declare
# healthchecks, so this is the normal path).
wait_healthy() {
  local svc="$1" deadline=$((SECONDS + WAIT_TIMEOUT)) cid state
  while (( SECONDS < deadline )); do
    cid="$(compose ps -q "$svc" 2>/dev/null || true)"
    if [[ -n "$cid" ]]; then
      state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo none)"
      [[ "$state" == "healthy" ]] && return 0
    fi
    sleep 3
  done
  return 1
}

# Image id the RUNNING container for $1 is currently using, with docker's
# `sha256:` prefix stripped so it compares equal to `compose images -q` (which
# omits the prefix). Empty when the service has no running container — which the
# restart decision treats as "changed", so a stopped app gets (re)started rather
# than left down. The container pins the image id it started with, so this stays
# accurate to "what is serving" even after a rebuild repoints the :latest tag. (M1-476)
running_image_id() {
  local svc="$1" cid
  cid="$(compose ps -q "$svc" 2>/dev/null || true)"
  if [[ -z "$cid" ]]; then
    return 0
  fi
  docker inspect -f '{{.Image}}' "$cid" 2>/dev/null | sed 's/^sha256://' || true
}

# Image id `compose build` produced/tagged for service $1 (already prefix-free).
built_image_id() {
  compose images -q "$1" 2>/dev/null || true
}

# Auto-rollback of CODE: check out the pre-upgrade commit (detached HEAD —
# deliberately, to avoid the forbidden `git reset --hard`; the operator restores
# the branch once the cause is fixed and re-runs upgrade), rebuild from it, and
# restart. $1 is the pre-upgrade SHA; $2 is the DB backup artifact path for the
# manual schema-restore note.
rollback() {
  local sha="$1" db_artifact="$2"
  echo "+ rolling back working tree to $sha and rebuilding" >&2
  git -C "$REPO_ROOT" checkout "$sha"
  compose build "${APP_SERVICES[@]}"
  start_apps
  echo "rolled back to $sha." >&2
  echo "NOTE: code is rolled back, but a Flyway migration that already applied during the" >&2
  echo "      failed upgrade is NOT reverted (migrations are forward-only, §7.11)." >&2
  echo "      If the schema changed, restore the database from the pre-upgrade backup:" >&2
  echo "        docs/design/07-deployment.md §7.11 (pg_restore the dump) — artifact:" >&2
  echo "        ${db_artifact}" >&2
}

main() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -y|--yes)  ASSUME_YES=1 ;;
      -h|--help) usage; exit 0 ;;
      *)         echo "FAIL: unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
  done

  # ── Preflight (system-boundary validation; abort before anything irreversible) ──
  # (a) Configured? secrets.env presence is the "has been through setup" signal
  # (mirrors backup.sh). This is upgrade.sh's OWN first check so the failure is
  # attributable here and fires before backup/pull/build.
  if [[ ! -f "$SECRETS_FILE" ]]; then
    echo "FAIL: $SECRETS_FILE not found — this deployment is not set up yet." >&2
    echo "      Run prod/setup.sh first; upgrade only applies to a configured deployment." >&2
    exit 1
  fi
  # (c) Tooling and compose file present (mirrors apps.sh).
  if ! command -v docker >/dev/null 2>&1; then
    echo "FAIL: docker not found on PATH" >&2; exit 1
  fi
  if ! command -v git >/dev/null 2>&1; then
    echo "FAIL: git not found on PATH" >&2; exit 1
  fi
  if [[ ! -f "$COMPOSE_FILE" ]]; then
    echo "FAIL: compose file not found: $COMPOSE_FILE" >&2; exit 1
  fi
  # (b) Clean TRACKED tree — operator-edited tracked files would block --ff-only.
  # --untracked-files=no ignores untracked/gitignored paths (prod/runtime/ lives
  # there), so only real tracked modifications abort the upgrade.
  if [[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)" ]]; then
    echo "FAIL: tracked files have uncommitted changes — commit or stash them first" >&2
    echo "      (git pull --ff-only would refuse). Changed files:" >&2
    git -C "$REPO_ROOT" status --porcelain --untracked-files=no >&2
    exit 1
  fi

  local original_sha
  original_sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  echo "infochat upgrade — current commit ${original_sha}"

  # ── Step 1: backup first (the real rollback path for a schema change) ──
  local backup_dir db_artifact
  # Mirror the default backup.sh computes ("$RUNTIME_DIR/backups", gitignored and
  # writable) so the rollback note below points at where backup.sh actually
  # wrote. (M1-476)
  backup_dir="${INFOCHAT_BACKUP_DIR:-$RUNTIME_DIR/backups}"
  # Reconstruct the DB artifact path backup.sh will write (same naming), so the
  # rollback note can point at it WITHOUT reading anything back from prod/runtime/.
  db_artifact="$backup_dir/infochat-$(date +%Y%m%d).pgc"
  if ! confirm "Run backup (DB dump + adapter identities) into ${backup_dir} before upgrading?"; then
    echo "aborted: backup declined — nothing changed." >&2; exit 1
  fi
  "$BACKUP_SCRIPT" "$backup_dir"

  # ── Step 2: pull latest main (best-effort; NOT the gate) ──
  # The pull ADVANCES the checkout when it is behind origin/main, but a no-op
  # pull does NOT end the run: whether to rebuild/restart is decided in Step 5 by
  # comparing the running image to the freshly-built one, so a checkout that is
  # already current still redeploys its (possibly stale) images. (M1-476)
  if ! confirm "git fetch + pull --ff-only origin main?"; then
    echo "aborted: pull declined — nothing changed." >&2; exit 1
  fi
  git -C "$REPO_ROOT" fetch origin
  git -C "$REPO_ROOT" pull --ff-only origin main
  local new_sha
  new_sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  if [[ "$new_sha" == "$original_sha" ]]; then
    echo "checkout already at ${original_sha} (no new origin/main commits) — continuing; the rebuild below redeploys the current source if the running images are stale."
  else
    echo "pulled ${original_sha} -> ${new_sha}"
  fi

  # ── Step 3: show what config changed this release (informational; never edits) ──
  # Config is two-layer (§7.6.2): the baked module defaults (ordinal 250) carry
  # every key with a working default; the operator's mounted prod/runtime/ file
  # (260) overrides a small subset. So a NEW key ships with its baked default and
  # needs no merge — the real upgrade risk is a key the operator overrides that a
  # release RENAMED or REMOVED, whose override then silently stops taking effect.
  # We surface that by diffing the baked files across the upgrade's two commits
  # and listing the operator's overrides to reconcile. (There is no
  # prod/config/application.properties to diff against, and diffing the override
  # subset vs the full baked set would be pure noise.)
  echo
  echo "Config changes in this release (baked defaults ${original_sha:0:9}..${new_sha:0:9}):"
  local baked_diff
  baked_diff="$(git -C "$REPO_ROOT" diff "$original_sha".."$new_sha" -- "${BAKED_CONFIG_FILES[@]}" || true)"
  if [[ -n "$baked_diff" ]]; then
    echo "$baked_diff"
    echo
    echo "Your overrides in $CONFIG_RUNTIME — reconcile against the diff above; a key"
    echo "you override that was renamed/removed silently stops taking effect:"
    # `|| true`: this is an informational diagnostic — it must never abort the
    # upgrade (pipefail would otherwise propagate an unreadable-file error).
    grep -oE '^[A-Za-z0-9._-]+' "$CONFIG_RUNTIME" | sort -u | sed 's/^/  /' || true
    echo "New keys ship with working defaults baked into the image — no merge needed."
  else
    echo "  no config changes this release."
  fi

  # ── Step 4: rebuild the two app images (old containers keep serving) ──
  if ! confirm "Rebuild the app images at ${new_sha}? (apps keep running on the old image during the build)"; then
    echo "aborted: build declined. Code is pulled but not rebuilt; re-run upgrade.sh to continue." >&2
    exit 1
  fi
  # Snapshot what each app is running BEFORE the rebuild, so Step 5 can tell a
  # real image change (or a stopped app) from a no-op cache-hit rebuild.
  local -a running_before=()
  local svc
  for svc in "${APP_SERVICES[@]}"; do
    running_before+=("$(running_image_id "$svc")")
  done
  if ! compose build "${APP_SERVICES[@]}"; then
    echo "FAIL: build failed at ${new_sha} — rolling back code." >&2
    rollback "$original_sha" "$db_artifact"
    exit 1
  fi

  # ── Step 5: restart Collector then Provider — only if the image changed ──
  # A full cache-hit rebuild yields the SAME image id, so when every app is
  # already running its freshly-built image, recreating containers would be pure
  # churn (and needless downtime) — print and exit. A differing id (source
  # changed) OR an empty running id (the app is stopped) counts as changed and
  # triggers the ordered restart. (M1-476)
  local changed=0 i
  for i in "${!APP_SERVICES[@]}"; do
    if [[ "${running_before[$i]}" != "$(built_image_id "${APP_SERVICES[$i]}")" ]]; then
      changed=1
    fi
  done
  if [[ "$changed" -eq 0 ]]; then
    echo "no change — both apps are already running the freshly-built image at ${new_sha}; nothing to restart."
    exit 0
  fi
  if ! confirm "Restart the apps onto the new image (Collector, then Provider)?"; then
    echo "aborted: restart declined. New images are built but not deployed; re-run upgrade.sh or use apps.sh restart." >&2
    exit 1
  fi
  if ! start_apps; then
    echo "FAIL: app start failed — rolling back code." >&2
    rollback "$original_sha" "$db_artifact"
    exit 1
  fi

  # ── Step 6: post-restart health gate (docker compose ps health) ──
  if wait_healthy infochat-collector && wait_healthy infochat-provider; then
    echo "upgrade complete: now at ${new_sha} (both apps healthy)."
  else
    echo "FAIL: post-restart health check did not report both apps healthy — rolling back code." >&2
    rollback "$original_sha" "$db_artifact"
    exit 1
  fi
}

# Call main LAST. Because bash parses a function body in full when it reads the
# `main() { ... }` definition above — before `main "$@"` runs — the `git pull` in
# step 2 can rewrite this very file on disk without affecting the already-parsed
# execution. A bare top-level script (no function wrapper) would be re-read
# line-by-line and could misbehave when it pulls a new version of itself.
main "$@"
