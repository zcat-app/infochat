#!/bin/bash
# prod/setup.sh — first-run setup wizard orchestrator (§7.7.2).
#
# Drives the wizard subscripts under prod/scripts/ in order, recording each
# completed step in a git-ignored .setup-state file (in the runtime dir) so a
# re-run resumes from the first incomplete step rather than restarting. The
# wizard runs the containerized prod compose stack — NOT quarkus:dev — so
# --reset is plain `docker compose down`, never the dev wrapper down.sh (§7.7.2).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$SCRIPT_DIR/runtime}"
STATE_FILE="$RUNTIME_DIR/.setup-state"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"

# Full wizard step sequence (§7.7.2 "Structure"): the orchestrator is the single
# place the step list is registered — leaf subscripts never self-register, so a
# step is in the run iff it has an entry here. Adding a step is a two-part change:
# the script under scripts/ AND its entry in this list. The 7-/8- scripts land
# with M1-385 (blocked on this ticket); their entries are wired here so the list
# is complete when those scripts arrive.
STEPS=(
  "0-doctor.sh:Preflight host checks"
  "1-profile.sh:Select hardware profile"
  "2-secrets.sh:Generate DB-role secrets"
  "3-postgres.sh:Start Postgres + bootstrap roles"
  "4-llm.sh:Configure the LLM backend"
  "5-bootstrap.sh:Install bootstrap sources"
  "6-adapter.sh:Register messaging adapters"
  "7-apps.sh:Start Collector then Provider"
  "8-verify.sh:Health-check the deployment"
)

usage() {
  echo "Usage: setup.sh [--defaults] [--reset] [-h|--help]"
  echo
  echo "First-run setup wizard. Runs each step in order, resuming from the first"
  echo "incomplete step on re-run (state recorded in $STATE_FILE)."
  echo
  echo "Steps:"
  for entry in "${STEPS[@]}"; do
    local script="${entry%%:*}"
    echo "  ${script%%-*}  ${entry##*:}  (${script})"
  done
  echo
  echo "Options:"
  echo "  --defaults  Run non-interactively, taking every default."
  echo "  --reset     docker compose down (offers -v to drop volumes) and clear state."
  echo "  -h, --help  Show this help and exit."
}

print_menu() {
  echo "infochat first-run setup wizard — steps:"
  for entry in "${STEPS[@]}"; do
    local script="${entry%%:*}"
    echo "  [${script%%-*}] ${entry##*:}"
  done
}

mark_done() { printf '%s\n' "$1" >> "$STATE_FILE"; }
is_done()   { [[ -f "$STATE_FILE" ]] && grep -qxF "$1" "$STATE_FILE"; }

do_reset() {
  # Feed secrets to compose via its own dotenv parser (--env-file), never a shell
  # `source` of secrets.env: operator-pasted values (SimpleX queue addresses with
  # '#' / '&', API keys) would otherwise truncate at '#' or execute as shell
  # (M1-389). Guarded — a --reset before the wizard minted secrets.env has no file
  # yet, and compose's ${INFOCHAT_*:-} defaults let `down` run without it.
  local env_file_args=()
  [[ -f "$SECRETS_FILE" ]] && env_file_args=(--env-file "$SECRETS_FILE")
  # Include the ollama / llamacpp profiles so a reset stops every service the
  # wizard may have started: those backends are gated under their own compose
  # profiles ([dev, ollama] and [llamacpp]), which a bare --profile prod down
  # does not match, leaving the LLM container + model-cache volume behind (M1-395).
  echo "+ docker compose -f $COMPOSE_FILE --profile prod --profile ollama --profile llamacpp down"
  docker compose -f "$COMPOSE_FILE" "${env_file_args[@]}" --profile prod --profile ollama --profile llamacpp down
  read -rp "Also drop data volumes (-v)? This deletes all DB data. [y/N]: " ans
  if [[ "$ans" == "y" || "$ans" == "Y" ]]; then
    echo "+ docker compose -f $COMPOSE_FILE --profile prod --profile ollama --profile llamacpp down -v"
    docker compose -f "$COMPOSE_FILE" "${env_file_args[@]}" --profile prod --profile ollama --profile llamacpp down -v
  fi
  if [[ -f "$STATE_FILE" ]]; then
    echo "+ rm $STATE_FILE"
    rm -f "$STATE_FILE"
  fi
  echo "reset complete."
}

defaults=0
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --reset) do_reset; exit 0 ;;
  --defaults) defaults=1 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

umask 077
mkdir -p "$RUNTIME_DIR"

print_menu
echo

# Each subscript that runs `docker compose` feeds secrets.env to compose's own
# dotenv parser via --env-file (M1-389); the orchestrator no longer sources the
# file into its environment, so an operator-pasted value containing '#' or
# '$(...)' can neither truncate nor execute as shell here.
for entry in "${STEPS[@]}"; do
  script="${entry%%:*}"
  desc="${entry##*:}"
  if is_done "$script"; then
    echo "== skip $script ($desc) — already complete"
    continue
  fi
  echo "== run $script ($desc)"
  if [[ "$defaults" -eq 1 ]]; then
    "$SCRIPT_DIR/scripts/$script" --defaults
  else
    "$SCRIPT_DIR/scripts/$script"
  fi
  mark_done "$script"
done

echo "wizard complete."
