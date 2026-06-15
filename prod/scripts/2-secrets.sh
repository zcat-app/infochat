#!/bin/bash
# prod/scripts/2-secrets.sh — wizard step 2: mint DB-role passwords + optional
# LLM API key into the git-ignored runtime secrets.env (§7.7.2).
#
# Generates the three service-role passwords via `openssl rand` (the frozen
# INFOCHAT_*_PASSWORD env-var contract from M1-378 / docker/postgres-init.sh) and
# writes them to a mode-0600 secrets.env in the runtime directory. Idempotent: a
# value already present is never regenerated (§7.7.2 "No generated secret is
# overwritten"). Secrets never enter a committed file — prod/config/
# secrets.env.example is the only tracked copy.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"

usage() {
  echo "Usage: 2-secrets.sh [--defaults] [-h|--help]"
  echo "  Generate the three DB-role passwords (openssl rand) into a mode-0600"
  echo "  secrets.env in the runtime dir, skipping any value already present,"
  echo "  then optionally prompt for a remote LLM API key."
  echo "  --defaults  leave the optional LLM API key blank (non-interactive)."
}

defaults=0
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) defaults=1 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

# umask 077 so the dir/file are owner-only from birth; the explicit chmod makes
# the 0600 contract hold regardless of the caller's umask.
umask 077
mkdir -p "$RUNTIME_DIR"
touch "$SECRETS_FILE"
chmod 600 "$SECRETS_FILE"

# Append KEY=<openssl rand> only if KEY has no non-empty value yet, so a resumed
# run never overwrites an already-minted secret.
ensure_secret() {
  local key="$1"
  if grep -qE "^${key}=.+" "$SECRETS_FILE"; then
    echo "skip ${key} (already set)"
    return 0
  fi
  echo "+ generate ${key} (openssl rand -hex 24)"
  printf '%s=%s\n' "$key" "$(openssl rand -hex 24)" >> "$SECRETS_FILE"
}

ensure_secret INFOCHAT_DB_PASSWORD
ensure_secret INFOCHAT_COLLECTOR_PASSWORD
ensure_secret INFOCHAT_PROVIDER_PASSWORD

# Optional remote LLM API key — local Ollama/llama.cpp setups leave it blank.
if grep -qE "^INFOCHAT_LLM_API_KEY=.+" "$SECRETS_FILE"; then
  echo "skip INFOCHAT_LLM_API_KEY (already set)"
elif [[ "$defaults" -eq 1 ]]; then
  echo "skip INFOCHAT_LLM_API_KEY (--defaults: blank for local LLM)"
else
  # -s: do not echo the key to the terminal (shoulder-surf / terminal-capture
  # hardening for a credential); the trailing echo restores the newline -s eats.
  read -rsp "Remote LLM API key (blank for local Ollama/llama.cpp) [blank]: " llm_key
  echo
  if [[ -n "$llm_key" ]]; then
    printf 'INFOCHAT_LLM_API_KEY=%s\n' "$llm_key" >> "$SECRETS_FILE"
    echo "+ recorded INFOCHAT_LLM_API_KEY"
  fi
fi

echo "secrets.env ready: $SECRETS_FILE (mode $(stat -c %a "$SECRETS_FILE"))"
