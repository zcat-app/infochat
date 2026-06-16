#!/bin/bash
# prod/scripts/6-adapter.sh — wizard step 6: for each chosen messaging adapter,
# drive its out-of-band registration, capture the on-disk identity material into
# the runtime config/secrets, collect each adapter's bootstrap-admin contact id,
# and enforce the non-empty admin union the Provider requires to start (§7.7.2
# step 6, §7.6.3). SimpleX queue creation and Signal phone/captcha enrolment stay
# manual (§7.7 operator note, 06-messaging.md §6.5.1); this script only sequences
# those steps and captures their output — it does not automate the registration.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"

# The closed v1 production adapter set (D46). The in-memory adapter is test-only
# (§7.7) and is never offered here — production must not mix it with simplex/signal.
VALID_ADAPTERS="simplex signal"
DEFAULT_ADAPTERS="simplex"
# Default binary paths follow the adapters' documentary config reference
# (06-messaging.md §6.4 / §6.5); the bot identity lives under the data-dir,
# which is the same on-disk state directory the operator registered out-of-band.
DEFAULT_SIMPLEX_BINARY="/usr/local/bin/simplex-chat"
DEFAULT_SIMPLEX_DATA_DIR="/var/lib/infochat/simplex"
DEFAULT_SIMPLEX_WS_PORT="5225"
DEFAULT_SIGNAL_BINARY="/usr/local/bin/signal-cli"
DEFAULT_SIGNAL_DATA_DIR="/var/lib/infochat/signal-cli"

usage() {
  echo "Usage: 6-adapter.sh [--defaults] [-h|--help]"
  echo "  For each chosen messaging adapter (${VALID_ADAPTERS// /|}), drive its"
  echo "  out-of-band registration, capture the binary path + data-dir (+ the"
  echo "  Signal account) into the runtime config, collect its bootstrap-admin"
  echo "  contact id, and enforce a non-empty admin union (§7.6.3) before writing"
  echo "  infochat.adapters and the per-adapter blocks into application.properties."
  echo "  --defaults  take ${DEFAULT_ADAPTERS} and the default dirs without prompting"
  echo "              (still pauses for the registration values a human must supply)."
}

defaults=0
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) defaults=1 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

# umask 077 + explicit chmod so secrets.env is owner-only regardless of caller
# umask (mirrors 2-secrets.sh — the bootstrap-admin contact ids captured below
# live here and are referenced from application.properties as ${VAR}).
umask 077
mkdir -p "$RUNTIME_DIR"
touch "$SECRETS_FILE"
chmod 600 "$SECRETS_FILE"

# Prompt for a value, falling back to a default; under --defaults take the default
# without prompting. The value is returned on stdout (captured by the caller); the
# read prompt itself goes to stderr, so $(...) capture leaves the prompt visible.
prompt_with_default() {
  local prompt="$1" def="$2" answer
  if [[ "$defaults" -eq 1 ]]; then
    printf '%s' "$def"
    return 0
  fi
  read -rp "$prompt [$def]: " answer || true
  printf '%s' "${answer:-$def}"
}

# Prompt for a REQUIRED non-secret value (e.g. the Signal account / phone
# number) and return it on stdout. Always prompts — even under --defaults: the
# value comes from the out-of-band registration a human must perform and has no
# sensible default — and fails closed if left empty (the adapter's boot-time
# config validation requires it, e.g. SignalConfig requires a non-empty
# .account). Visible input: unlike a secret, the value is written into
# application.properties, not secrets.env.
prompt_required() {
  local prompt="$1" val
  read -rp "$prompt: " val || true
  if [[ -z "$val" ]]; then
    echo "FAIL: a value is required for the adapter ('${prompt#"${prompt%%[![:space:]]*}"}')." >&2
    exit 1
  fi
  printf '%s' "$val"
}

# Collect an OPTIONAL per-adapter bootstrap-admin contact id into secrets.env.
# Returns 0 when a contact ends up set for this adapter (whether captured now or
# on a prior run), 1 when none is set — the caller tallies the union (§7.6.3).
collect_admin() {
  local key="$1" adapter="$2" val
  if grep -qE "^${key}=.+" "$SECRETS_FILE"; then
    echo "skip ${key} (already set)"
    return 0
  fi
  read -rp "Bootstrap admin contact id for ${adapter} (optional; blank for none): " val || true
  if [[ -n "$val" ]]; then
    printf '%s=%s\n' "$key" "$val" >> "$SECRETS_FILE"
    echo "+ recorded ${key}"
    return 0
  fi
  echo "  no bootstrap admin set for ${adapter}"
  return 1
}

# ── Adapter selection ──────────────────────────────────────────────────
adapters="$DEFAULT_ADAPTERS"
if [[ "$defaults" -eq 0 ]]; then
  echo "Available adapters: ${VALID_ADAPTERS// /, } (the in-memory adapter is"
  echo "test-only and is never offered here — production must not mix it, §7.7)."
  read -rp "Enable which adapters? (comma-separated) [${DEFAULT_ADAPTERS}]: " answer || true
  adapters="${answer:-$DEFAULT_ADAPTERS}"
fi

# Normalize "a, b" → tokens, validate each against the closed set (D46), dedupe
# preserving order. System-boundary check on free-text interactive input.
chosen=()
IFS=',' read -ra raw <<< "$adapters"
for a in "${raw[@]}"; do
  a="$(echo "$a" | tr -d '[:space:]')"
  [[ -z "$a" ]] && continue
  case " $VALID_ADAPTERS " in
    *" $a "*) ;;
    *) echo "FAIL: unknown adapter '$a' (expected: ${VALID_ADAPTERS// /, })." >&2; exit 1 ;;
  esac
  case " ${chosen[*]:-} " in *" $a "*) continue ;; esac
  chosen+=("$a")
done
if [[ "${#chosen[@]}" -eq 0 ]]; then
  echo "FAIL: at least one adapter must be enabled (${VALID_ADAPTERS// /, }); the in-memory adapter is test-only (§7.7)." >&2
  exit 1
fi

# ── Per-adapter registration + capture ─────────────────────────────────
# admin_union tallies how many chosen adapters supply a bootstrap admin; the gate
# below refuses to proceed when it is zero. The binary / data-dir / port / account
# values are gathered now and written to application.properties only after the
# gate passes (the keys the Provider actually reads — 06-messaging.md §6.4/§6.5,
# ProductionAdapterBeans / SimpleXConfig / SignalConfig).
admin_union=0
simplex_binary=""
simplex_data_dir=""
simplex_ws_port=""
signal_binary=""
signal_data_dir=""
signal_account=""

for adapter in "${chosen[@]}"; do
  case "$adapter" in
    simplex)
      echo
      echo "== SimpleX adapter =="
      echo "Create the bot's SimpleX messaging queue out-of-band with simplex-cli"
      echo "(§7.7 operator note, 06-messaging.md §6.5.1), then point the wizard at"
      echo "the simplex-chat binary and the resulting on-disk data directory. The"
      echo "Provider spawns simplex-chat as a subprocess and talks to it over the"
      echo "loopback WebSocket port (no session token — bot identity is in data-dir)."
      simplex_binary="$(prompt_with_default "  simplex-chat binary path" "$DEFAULT_SIMPLEX_BINARY")"
      simplex_data_dir="$(prompt_with_default "  SimpleX data-dir (bot state directory)" "$DEFAULT_SIMPLEX_DATA_DIR")"
      simplex_ws_port="$(prompt_with_default "  simplex-chat WebSocket port (loopback)" "$DEFAULT_SIMPLEX_WS_PORT")"
      if collect_admin INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID simplex; then
        admin_union=$((admin_union + 1))
      fi
      ;;
    signal)
      echo
      echo "== Signal adapter =="
      echo "Register the bot's Signal phone number out-of-band with signal-cli"
      echo "(phone number + captcha — §7.7 operator note, 06-messaging.md §6.5.1),"
      echo "then point the wizard at the signal-cli binary and account data dir,"
      echo "and supply the registered account identifier (the bot's phone number)."
      signal_binary="$(prompt_with_default "  signal-cli binary path" "$DEFAULT_SIGNAL_BINARY")"
      signal_data_dir="$(prompt_with_default "  Signal data-dir (signal-cli account directory)" "$DEFAULT_SIGNAL_DATA_DIR")"
      signal_account="$(prompt_required "  Signal account (registered phone number, e.g. +15551234567)")"
      if collect_admin INFOCHAT_SIGNAL_ADMIN_CONTACT_ID signal; then
        admin_union=$((admin_union + 1))
      fi
      ;;
  esac
done

# ── Non-empty admin union (§7.6.3) — the load-bearing security gate ─────
# Provider refuses to start unless at least one enabled adapter supplies a
# bootstrap admin (last-admin protection only works if one admin row exists
# somewhere). Gate BEFORE writing the adapters config so a refused run leaves no
# half-written block a resume would treat as complete.
if [[ "$admin_union" -eq 0 ]]; then
  echo "FAIL: no bootstrap admin contact id was supplied for any chosen adapter." >&2
  echo "      The union of bootstrap admins across enabled adapters MUST be" >&2
  echo "      non-empty or the Provider refuses to start (§7.6.3)." >&2
  exit 1
fi

# ── Write the runtime config (§7.4 byte-shape) — last side effect ──────
echo "+ write infochat.adapters and per-adapter blocks to $CONFIG_FILE"
# Idempotent: drop any prior adapters lines so a re-run with a different selection
# cannot leave stale per-adapter properties behind.
if [[ -f "$CONFIG_FILE" ]]; then
  sed -i -e '/^infochat\.adapters=/d' -e '/^infochat\.adapters\./d' "$CONFIG_FILE"
fi

# The bot contact-id property is intentionally NEVER written: it is derived from
# the adapter's own identity material at startup, not operator-typed (§7.5).
# Secrets/admin contacts are referenced via ${VAR}; their values live in
# secrets.env. The bootstrap-admin line is emitted only when that adapter has an
# admin set, keeping the property optional per adapter (§7.6.3).
{
  printf 'infochat.adapters=%s\n' "$(IFS=,; echo "${chosen[*]}")"
  for adapter in "${chosen[@]}"; do
    case "$adapter" in
      simplex)
        printf 'infochat.adapters.simplex.binary=%s\n' "$simplex_binary"
        printf 'infochat.adapters.simplex.data-dir=%s\n' "$simplex_data_dir"
        printf 'infochat.adapters.simplex.ws-port=%s\n' "$simplex_ws_port"
        if grep -qE '^INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID=.+' "$SECRETS_FILE"; then
          printf 'infochat.adapters.simplex.bootstrap-admin-contact-id=%s\n' '${INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID}'
        fi
        ;;
      signal)
        printf 'infochat.adapters.signal.binary=%s\n' "$signal_binary"
        printf 'infochat.adapters.signal.data-dir=%s\n' "$signal_data_dir"
        printf 'infochat.adapters.signal.account=%s\n' "$signal_account"
        if grep -qE '^INFOCHAT_SIGNAL_ADMIN_CONTACT_ID=.+' "$SECRETS_FILE"; then
          printf 'infochat.adapters.signal.bootstrap-admin-contact-id=%s\n' '${INFOCHAT_SIGNAL_ADMIN_CONTACT_ID}'
        fi
        ;;
    esac
  done
} >> "$CONFIG_FILE"

echo "adapters configured: ${chosen[*]} (bootstrap admin union: ${admin_union} adapter(s))"
