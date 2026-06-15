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
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"

# Wizard steps implemented in this slice (M1-382), as "subscript:description".
# Steps 3-8 (postgres/llm/bootstrap/adapter/apps/verify) land in M1-383/384/385
# and extend this list.
STEPS=(
  "0-doctor.sh:Preflight host checks"
  "1-profile.sh:Select hardware profile"
  "2-secrets.sh:Generate DB-role secrets"
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
  echo "+ docker compose -f $COMPOSE_FILE --profile prod down"
  docker compose -f "$COMPOSE_FILE" --profile prod down
  read -rp "Also drop data volumes (-v)? This deletes all DB data. [y/N]: " ans
  if [[ "$ans" == "y" || "$ans" == "Y" ]]; then
    echo "+ docker compose -f $COMPOSE_FILE --profile prod down -v"
    docker compose -f "$COMPOSE_FILE" --profile prod down -v
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
