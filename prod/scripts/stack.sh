#!/bin/bash
# prod/scripts/stack.sh — stop/start/restart/status the WHOLE stack around a host
# reboot; complements apps.sh, which stays app-only (07-deployment.md §7.7.1).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
COMFYUI_COMPOSE_FILE="$REPO_ROOT/docker-compose.comfyui.yml"

# One compose() owns the full assembly: base + comfyui overlay (a base-only stop
# leaves comfyui running), never the gpu overlay (its devices: entry breaks
# creation on GPU-less hosts), four profiles, env-file when present (M1-389).
PROFILES=(--profile prod --profile ollama --profile llamacpp --profile llamacpp-embeddings)

usage() {
  echo "Usage: stack.sh {start|stop|restart|status} [-h|--help]"
  echo "  Lifecycle control for the WHOLE stack (every service the wizard can start)"
  echo "  around a host reboot. Containers and data volumes are preserved."
  echo "    start    resume the existing stopped container set — never recreates;"
  echo "             fails with a setup pointer when no containers exist."
  echo "    stop     stop every service, comfyui included."
  echo "    restart  stop then start."
  echo "    status   show compose status for the full stack."
}

compose() {
  local env_args=()
  [[ -f "$SECRETS_FILE" ]] && env_args=(--env-file "$SECRETS_FILE")
  echo "+ docker compose -f $COMPOSE_FILE -f $COMFYUI_COMPOSE_FILE ${env_args[*]} ${PROFILES[*]} $*" >&2
  docker compose -f "$COMPOSE_FILE" -f "$COMFYUI_COMPOSE_FILE" "${env_args[@]}" "${PROFILES[@]}" "$@"
}

# `start` is `compose start`, never `up`: up over four profiles would create
# BOTH LLM backends plus unprofiled comfyui and recreate drifted containers;
# start resumes exactly the existing (chosen-shape) container set.
start_stack() {
  local container_ids
  container_ids="$(compose ps -aq)"
  if [[ -z "$container_ids" ]]; then
    echo "FAIL: no stack containers to resume — the stack was never created or was torn down." >&2
    echo "      Run ./prod/setup.sh to create it first." >&2
    exit 1
  fi
  compose start
  echo "stack: started (resumed the existing container set — nothing was created)."
}

stop_stack() {
  compose stop
  echo "stack: stopped (containers preserved, data volumes intact — not a 'down')."
}

if ! command -v docker >/dev/null 2>&1; then
  echo "FAIL: docker not found on PATH" >&2
  exit 1
fi
for f in "$COMPOSE_FILE" "$COMFYUI_COMPOSE_FILE"; do
  if [[ ! -f "$f" ]]; then
    echo "FAIL: compose file not found: $f" >&2
    exit 1
  fi
done

case "${1:-}" in
  start)     start_stack ;;
  stop)      stop_stack ;;
  restart)   stop_stack; start_stack ;;
  status)    compose ps ;;
  -h|--help) usage; exit 0 ;;
  "")        echo "FAIL: missing command" >&2; usage >&2; exit 2 ;;
  *)         echo "FAIL: unknown command: ${1}" >&2; usage >&2; exit 2 ;;
esac
