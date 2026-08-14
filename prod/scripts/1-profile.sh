#!/bin/bash
# prod/scripts/1-profile.sh — wizard step 1: pick the hardware profile (§7.2, D27)
# and record quarkus.profile into the runtime application.properties (§7.7.2).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
DEFAULT_PROFILE="laptop"
VALID_PROFILES="laptop vps pi remote-llm"

usage() {
  echo "Usage: 1-profile.sh [--defaults] [-h|--help]"
  echo "  Prompt for the hardware profile (${VALID_PROFILES// /|}, default"
  echo "  ${DEFAULT_PROFILE}) and write quarkus.profile into the runtime"
  echo "  application.properties."
  echo "  Changing the profile does NOT re-route the LLM — post-setup LLM"
  echo "  re-routes use prod/switch-llm.sh."
  echo "  --defaults  take ${DEFAULT_PROFILE} without prompting (non-interactive)."
}

defaults=0
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) defaults=1 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

profile="$DEFAULT_PROFILE"
if [[ "$defaults" -eq 0 ]]; then
  # Explain the profile up front: it is a sizing knob (model size, concurrency,
  # timeouts), and it also encodes where the LLM runs — local for laptop/vps/pi vs
  # cloud for remote-llm — so an operator does not read it as hardware-only and
  # then collide with the step-4 backend choice. Axes stay coupled per D27.
  echo "Hardware profile — tunes infochat's sizing (AI model size, concurrency, timeouts)"
  echo "to your machine and to where the AI runs:"
  echo "  laptop | vps | pi  — run the AI locally on this machine"
  echo "  remote-llm         — run the AI in the cloud via an API (you give a key in step 4)"
  read -rp "Hardware profile [${DEFAULT_PROFILE}]: " answer
  profile="${answer:-$DEFAULT_PROFILE}"
fi

# Validate the operator's free-text answer against the closed set (D27) — a
# system-boundary check on interactive input.
case " $VALID_PROFILES " in
  *" $profile "*) ;;
  *) echo "FAIL: unknown profile '$profile' (expected: $VALID_PROFILES)" >&2; exit 1 ;;
esac

umask 077
mkdir -p "$RUNTIME_DIR"
echo "+ set quarkus.profile=$profile in $CONFIG_FILE"
# Replace any existing quarkus.profile line so a re-run is idempotent.
if [[ -f "$CONFIG_FILE" ]]; then
  sed -i '/^quarkus\.profile=/d' "$CONFIG_FILE"
fi
printf 'quarkus.profile=%s\n' "$profile" >> "$CONFIG_FILE"
echo "profile recorded: $profile"
