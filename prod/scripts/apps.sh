#!/bin/bash
# prod/scripts/apps.sh — lifecycle control for the running infochat apps
# (Collector + Provider) after first-run setup.
#
# This is the post-setup analogue of wizard step 7 (7-apps.sh): once
# `setup.sh` has built images and provisioned secrets, an operator uses this
# one named wrapper to stop and start the bot rather than re-running the wizard
# or memorising the compose flags. It targets ONLY the two app services.
#
# Postgres and the LLM services (llamacpp / ollama) are intentionally left
# running — they are heavy (model load) and long-lived, so leaving them up
# keeps `start` fast and the database continuously available. To stop the
# WHOLE stack, use `setup.sh --reset` (a plain `docker compose down`) or
# `docker compose stop` directly.
#
# `stop` preserves the containers and the named data volumes (infochat-pgdata
# et al.), so no posts, users, or migrations are lost across a stop/start cycle
# — `stop` is not `down`.
#
# Both app services are gated behind the compose `prod` profile, so every
# invocation passes `--profile prod`; `--env-file secrets.env` is passed (when
# present) so the INFOCHAT_*_PASSWORD / *_API_KEY interpolations resolve to the
# real wizard-provisioned secrets if `start` has to recreate a container —
# without it compose would fall back to the empty `${VAR:-}` defaults and the
# app would fail DB auth (mirrors 7-apps.sh, §7.7.2 step 7).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
# Generous: on a cold start the Collector applies the full Flyway migration set
# before its healthcheck passes (start_period + retries). Mirrors 7-apps.sh.
WAIT_TIMEOUT=300

APP_SERVICES=(infochat-collector infochat-provider)

usage() {
  echo "Usage: apps.sh {start|stop|restart|status} [-h|--help]"
  echo "  Lifecycle control for the running infochat apps (Collector + Provider)"
  echo "  after first-run setup. Leaves Postgres and the LLM services running."
  echo "    start    create-or-start both apps; wait up to ${WAIT_TIMEOUT}s for the"
  echo "             Collector healthcheck (it runs Flyway) before the Provider."
  echo "    stop     stop both apps; containers and data volumes are preserved."
  echo "    restart  stop then start — re-reads mounted config such as"
  echo "             bootstrap-sources.json."
  echo "    status   show compose status/health for both apps."
}

# Single place the compose flags live: prod profile (both apps are profile-
# gated) plus the secrets env-file when it exists.
compose() {
  local env_args=()
  [[ -f "$SECRETS_FILE" ]] && env_args=(--env-file "$SECRETS_FILE")
  docker compose -f "$COMPOSE_FILE" "${env_args[@]}" --profile prod "$@"
}

start_apps() {
  echo "+ starting Collector (waiting up to ${WAIT_TIMEOUT}s for healthy) then Provider"
  # Naming both lets compose honour provider's depends_on collector
  # (service_healthy): Collector comes up and passes its healthcheck before the
  # Provider starts. --wait blocks until the named services are up.
  compose up -d --wait --wait-timeout "$WAIT_TIMEOUT" "${APP_SERVICES[@]}"
  echo "apps: up."
}

stop_apps() {
  echo "+ stopping Provider then Collector"
  compose stop "${APP_SERVICES[@]}"
  echo "apps: stopped (containers preserved, data volumes intact — not a 'down')."
}

# Operator entry point — validate the two hard prerequisites with a clear
# message rather than a raw error mid-command.
if ! command -v docker >/dev/null 2>&1; then
  echo "FAIL: docker not found on PATH" >&2
  exit 1
fi
if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "FAIL: compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

case "${1:-}" in
  start)     start_apps ;;
  stop)      stop_apps ;;
  restart)   stop_apps; start_apps ;;
  status)    compose ps "${APP_SERVICES[@]}" ;;
  -h|--help) usage; exit 0 ;;
  "")        echo "FAIL: missing command" >&2; usage >&2; exit 2 ;;
  *)         echo "FAIL: unknown command: ${1}" >&2; usage >&2; exit 2 ;;
esac
