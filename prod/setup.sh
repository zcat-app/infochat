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
  "5-bootstrap.sh:Install bootstrap sources and asset defaults"
  "6-adapter.sh:Register messaging adapters"
  "7-apps.sh:Start Collector then Provider"
  "8-verify.sh:Health-check the deployment"
)

usage() {
  echo "Usage: setup.sh [--defaults] [--reset [--hard]] [-h|--help]"
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
  echo "  --reset     Tear down any existing deployment (keeping your data), clear"
  echo "              wizard state, then run setup. Prints nothing if there is"
  echo "              nothing to remove. Combine with --defaults to re-setup"
  echo "              non-interactively."
  echo "  --hard      Only with --reset: ALSO drop the database volume (deletes"
  echo "              the database). Without it, your data is kept. Downloaded LLM"
  echo "              model caches are ALWAYS kept (never re-downloaded by a reset);"
  echo "              remove them by hand if you truly need to."
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

# Tear down an existing deployment so the wizard that follows starts clean, then
# RETURN to the caller (the orchestrator continues into the step loop — a reset is
# "clean up if needed, then set up", M1-464). The first argument is the literal
# "hard" when the operator passed --reset --hard, which additionally drops the
# DATABASE volume (pgdata) only; absent it, the database is kept too. Either way
# the LLM model caches (infochat-llamacpp-models, infochat-ollama) are NEVER
# removed — they hold multi-GB GGUFs the wizard reuses (4-llm.sh fetch_gguf's
# presence check / `ollama pull` idempotency), so wiping them would force a
# needless re-download. Crucially, do_reset is SILENT on
# a clean host: it probes for each kind of leftover before issuing any compose
# command or printing any line, so a reset with nothing to remove neither prints a
# removal message nor asks anything (the reported annoyance: removal noise + a [y/N]
# volume prompt on every run regardless of state).
do_reset() {
  local hard="${1:-}"
  # Feed secrets to compose via its own dotenv parser (--env-file), never a shell
  # `source` of secrets.env: operator-pasted values (SimpleX queue addresses with
  # '#' / '&', API keys) would otherwise truncate at '#' or execute as shell
  # (M1-389). Guarded — a --reset before the wizard minted secrets.env has no file
  # yet, and compose's ${INFOCHAT_*:-} defaults let the probes/down run without it.
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
  local profiles=(--profile prod --profile ollama --profile llamacpp --profile llamacpp-embeddings)

  # Probe before acting (M1-464). `compose ps -aq` over the four profiles lists this
  # project's containers — exactly the set `down` would remove; the network probe
  # catches a leftover network with no container still attached.
  local container_ids
  container_ids="$(docker compose -f "$COMPOSE_FILE" "${env_file_args[@]}" "${profiles[@]}" ps -aq 2>/dev/null || true)"
  local network_name="${COMPOSE_PROJECT_NAME:-$(basename "$REPO_ROOT")}_default"
  local has_runtime=0
  if [[ -n "$container_ids" ]] || docker network inspect "$network_name" >/dev/null 2>&1; then
    has_runtime=1
  fi
  # --hard drops ONLY the database (pgdata) volume — never the model caches. A
  # blanket `down -v` would also remove infochat-llamacpp-models and the ollama
  # cache (both compose-managed, not external), forcing a multi-GB GGUF
  # re-download; the model caches are reused across resets via 4-llm.sh. So the
  # teardown is always plain `down` (containers + network), and --hard removes
  # the pgdata volume explicitly afterwards. pgdata is compose-managed with no
  # `name:` pin, so its real name is <project>_infochat-pgdata, mirroring the
  # network-name derivation above.
  local pgdata_volume="${COMPOSE_PROJECT_NAME:-$(basename "$REPO_ROOT")}_infochat-pgdata"
  local has_pgdata=0
  if [[ "$hard" == "hard" ]] && docker volume inspect "$pgdata_volume" >/dev/null 2>&1; then
    has_pgdata=1
  fi

  if [[ "$has_runtime" -eq 1 ]]; then
    echo "+ docker compose -f $COMPOSE_FILE ${profiles[*]} down"
    docker compose -f "$COMPOSE_FILE" "${env_file_args[@]}" "${profiles[@]}" down
  fi
  # Remove pgdata after `down` has detached the postgres container. Safe even
  # when nothing was running (an orphaned volume from a prior kept reset).
  if [[ "$has_pgdata" -eq 1 ]]; then
    echo "+ docker volume rm $pgdata_volume"
    docker volume rm "$pgdata_volume" >/dev/null
  fi

  # Clear resume state so the wizard below runs from the first step; silent when
  # there is nothing to clear.
  if [[ -f "$STATE_FILE" ]]; then
    echo "+ rm $STATE_FILE"
    rm -f "$STATE_FILE"
  fi
}

defaults=0
reset=0
hard=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --reset) reset=1 ;;
    --hard) hard=1 ;;
    --defaults) defaults=1 ;;
    *) usage >&2; exit 2 ;;
  esac
  shift
done
# --hard only makes sense as a modifier of --reset; alone it would imply silently
# wiping data volumes outside any reset, which the no-accidental-wipe invariant
# forbids. Reject it loudly rather than ignore it.
if [[ "$hard" -eq 1 && "$reset" -eq 0 ]]; then
  echo "--hard is only valid together with --reset (it wipes data volumes during a reset)." >&2
  exit 2
fi

umask 077
mkdir -p "$RUNTIME_DIR"

# A reset cleans up any existing deployment first, then falls through into the
# wizard below (M1-464) — so `--reset` is one command that tears down and sets up.
if [[ "$reset" -eq 1 ]]; then
  if [[ "$hard" -eq 1 ]]; then do_reset hard; else do_reset; fi
fi

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
