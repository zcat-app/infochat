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
CONFIG_FILE="$RUNTIME_DIR/application.properties"
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

# Closing handoff (§7.7.2): the wizard creates the bot's admin record on Provider
# startup (AdminBootstrap @Startup, from the step-6 bootstrap-admin contact id),
# but it cannot connect the operator's PERSONAL app to the bot — that step happens
# in SimpleX/Signal, off-machine. So the unified setup finishes by telling the
# now-admin exactly how to reach the bot and issue the first invite. Reads the
# committed adapter selection (and the Signal number, which is non-secret) from
# application.properties; never sources secrets.env.
print_handoff() {
  local adapters_line signal_account a
  local -a _adapters
  adapters_line="$(grep -E '^infochat\.adapters=' "$CONFIG_FILE" 2>/dev/null | head -n1 | cut -d= -f2-)"
  echo
  echo "=============================================================="
  echo " infochat is set up — and YOU are the bootstrap admin."
  echo "=============================================================="
  echo
  echo "You do NOT need an invite code. Connect from your personal app,"
  echo "message the bot, then invite other people right from your phone."
  echo
  echo "Connect to the bot:"
  IFS=',' read -ra _adapters <<< "$adapters_line"
  for a in "${_adapters[@]}"; do
    case "$a" in
      simplex)
        echo "  SimpleX: from your personal SimpleX app, tap Connect and paste the"
        echo "           bot's contact address — the link the wizard printed during"
        echo "           setup (step 7, SimpleX provisioning); in the CLI:"
        echo "           /c <bot-address>. The bot auto-accepts the connection."
        ;;
      signal)
        signal_account="$(grep -E '^infochat\.adapters\.signal\.account=' "$CONFIG_FILE" 2>/dev/null | head -n1 | cut -d= -f2-)"
        echo "  Signal:  from your personal Signal app, send a direct message to the"
        echo "           bot's number: ${signal_account:-<the number you registered>}"
        ;;
    esac
  done
  echo
  echo "First moves once connected:"
  echo "  1. Send  /help   — confirms the bot answers you."
  echo "  2. Invite someone:"
  echo "       /invite create --adapter <app> --contact <their id>"
  echo "     The bot replies with a one-time code. Send it to them; they connect"
  echo "     to the bot and send the code on its own as their first DM to register."
  echo
  echo "Full admin walkthrough: ADMIN_GUIDE.md   ·   Using the bot: USER_GUIDE.md"
}

do_reset() {
  # Feed secrets to compose via its own dotenv parser (--env-file), never a shell
  # `source` of secrets.env: operator-pasted values (SimpleX queue addresses with
  # '#' / '&', API keys) would otherwise truncate at '#' or execute as shell
  # (M1-389). Guarded — a --reset before the wizard minted secrets.env has no file
  # yet, and compose's ${INFOCHAT_*:-} defaults let `down` run without it.
  local env_file_args=()
  [[ -f "$SECRETS_FILE" ]] && env_file_args=(--env-file "$SECRETS_FILE")
  # Include the ollama / llamacpp / llamacpp-embeddings profiles so a reset stops
  # every service the wizard may have started: those backends are gated under their
  # own compose profiles ([dev, ollama], [llamacpp], and [llamacpp-embeddings]),
  # which a bare --profile prod down does not match, leaving the LLM container +
  # model-cache volume behind (M1-395). [llamacpp-embeddings] is the M1-417
  # pure-llama.cpp embeddings shape (D49) — a SECOND llama.cpp instance under its
  # own profile, NOT covered by --profile llamacpp; omitting it here leaves that
  # container running and holding infochat_default open after the down, and pinning
  # the shared infochat-llamacpp-models volume so even the -v drop cannot remove it.
  echo "+ docker compose -f $COMPOSE_FILE --profile prod --profile ollama --profile llamacpp --profile llamacpp-embeddings down"
  docker compose -f "$COMPOSE_FILE" "${env_file_args[@]}" --profile prod --profile ollama --profile llamacpp --profile llamacpp-embeddings down
  read -rp "Also drop data volumes (-v)? This deletes all DB data. [y/N]: " ans
  if [[ "$ans" == "y" || "$ans" == "Y" ]]; then
    echo "+ docker compose -f $COMPOSE_FILE --profile prod --profile ollama --profile llamacpp --profile llamacpp-embeddings down -v"
    docker compose -f "$COMPOSE_FILE" "${env_file_args[@]}" --profile prod --profile ollama --profile llamacpp --profile llamacpp-embeddings down -v
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
print_handoff
