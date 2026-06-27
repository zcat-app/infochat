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
# The data-dir defaults live under the wizard-owned runtime dir (created as the
# operator in step 2) — anchored on $PROD_DIR so they stay absolute (PROD_DIR is
# resolved via cd+pwd at line 12), which the docker-compose bind mount requires.
# A /var/lib default would be root-owned 0755, so 6b's `mkdir -p "$data_dir"`
# would abort the wizard for the non-root operator it targets (M1-440).
DEFAULT_SIMPLEX_BINARY="/usr/local/bin/simplex-chat"
DEFAULT_SIMPLEX_DATA_DIR="$PROD_DIR/runtime/simplex"
DEFAULT_SIMPLEX_WS_PORT="5225"
DEFAULT_SIGNAL_BINARY="/usr/local/bin/signal-cli"
DEFAULT_SIGNAL_DATA_DIR="$PROD_DIR/runtime/signal-cli"

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

# Escape an arbitrary value for a double-quoted secrets.env field so compose's
# --env-file dotenv parser reads it back byte-for-byte (M1-397). Order matters:
# backslash first (so the escapes we add next are not themselves re-escaped),
# then the '"' that would prematurely close the field and the '$' that some
# compose versions would treat as ${...} interpolation.
dotenv_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//\$/\\\$}"
  printf '%s' "$value"
}

# Collect a per-adapter bootstrap-admin contact id into secrets.env. The third
# arg is 1 when this is the ONLY enabled adapter: then a blank is guaranteed to
# trip the union gate (below) seconds later, so the prompt is REQUIRED and a
# blank re-prompts with the reason rather than being accepted as 'none' (M1-441).
# With 2+ adapters the property stays optional per adapter (§7.6.3) — a blank is
# legal here because another enabled adapter may supply the admin, and the union
# gate is the backstop for the all-blank case. Returns 0 when a contact ends up
# set for this adapter (captured now or on a prior run), 1 when none is set — the
# caller tallies the union (§7.6.3).
collect_admin() {
  local key="$1" adapter="$2" only_adapter="$3" val read_ok prompt
  if grep -qE "^${key}=.+" "$SECRETS_FILE"; then
    echo "skip ${key} (already set)"
    return 0
  fi
  if [[ "$only_adapter" -eq 1 ]]; then
    prompt="Bootstrap admin contact id for ${adapter} (required — the only enabled adapter, so its admin is the deployment's sole admin; last-admin protection needs it)"
  else
    prompt="Bootstrap admin contact id for ${adapter} (at least one across enabled adapters is required; blank only if another enabled adapter supplies one)"
  fi
  while true; do
    if read -rp "$prompt: " val; then read_ok=1; else read_ok=0; fi
    if [[ -n "$val" ]]; then
      # Quote the value: a SimpleX queue address carries '#' / '&' / '?' / '+' /
      # '=' — unquoted, compose's --env-file dotenv parse would truncate it at '#'
      # (M1-389), so the bootstrap-admin id reaching the Provider would be wrong.
      printf '%s="%s"\n' "$key" "$(dotenv_escape "$val")" >> "$SECRETS_FILE"
      echo "+ recorded ${key}"
      return 0
    fi
    if [[ "$only_adapter" -eq 1 ]]; then
      # Required for the sole adapter. A blank with input still to come
      # re-prompts; a blank because stdin hit EOF (Ctrl-D / exhausted) cannot
      # re-prompt productively, so fail closed rather than spin — interactive
      # input is a system boundary, so this guard belongs here.
      if [[ "$read_ok" -eq 0 ]]; then
        echo "FAIL: a bootstrap admin contact id is required for the only enabled adapter (${adapter})." >&2
        exit 1
      fi
      echo "  Required: this is the only enabled adapter, so its bootstrap admin" >&2
      echo "  is the deployment's sole admin (last-admin protection)." >&2
      continue
    fi
    echo "  no bootstrap admin set for ${adapter}"
    return 1
  done
}

# ── Adapter selection ──────────────────────────────────────────────────
adapters="$DEFAULT_ADAPTERS"
if [[ "$defaults" -eq 0 ]]; then
  echo "(The in-memory adapter is test-only and is never offered here —"
  echo "production must not mix it, §7.7.)"
  read -rp "Adapters to enable — comma-separated for multiple (options: ${VALID_ADAPTERS// /, }) [${DEFAULT_ADAPTERS}]: " answer || true
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
# Required-vs-optional bootstrap-admin prompt keys off the enabled-adapter count:
# with a single adapter its admin is the deployment's sole admin, so collect_admin
# prompts it as required (M1-441); with 2+ it stays per-adapter optional (§7.6.3).
only_adapter=0
[[ "${#chosen[@]}" -eq 1 ]] && only_adapter=1
simplex_binary=""
simplex_data_dir=""
simplex_ws_port=""
simplex_display_name=""
signal_binary=""
signal_data_dir=""
signal_account=""

for adapter in "${chosen[@]}"; do
  case "$adapter" in
    simplex)
      echo
      echo "== SimpleX adapter =="
      echo "The wizard provisions the bot's SimpleX identity for you in step 7"
      echo "(profile, contact address, auto-accept) using the simplex-chat binary"
      echo "baked into the Provider image — you do not create a queue by hand. Just"
      echo "point it at the on-disk data directory for the bot's state and the"
      echo "loopback WebSocket port, and choose the bot's display name. The Provider"
      echo "spawns simplex-chat as a subprocess and talks to it over the loopback"
      echo "WebSocket port (no session token — bot identity is in data-dir)."
      simplex_binary="$(prompt_with_default "  simplex-chat binary path" "$DEFAULT_SIMPLEX_BINARY")"
      simplex_data_dir="$(prompt_with_default "  SimpleX data-dir (bot state directory)" "$DEFAULT_SIMPLEX_DATA_DIR")"
      simplex_ws_port="$(prompt_with_default "  simplex-chat WebSocket port (loopback)" "$DEFAULT_SIMPLEX_WS_PORT")"
      # Operator-typed bot profile name consumed by 6b-simplex-provision.sh (step
      # 7) to create the profile via `--create-bot-display-name`. This is wizard
      # provisioning input, NOT runtime identity: the running adapter still derives
      # the bot contact id from simplex-chat at startup (§7.5) and never reads this.
      simplex_display_name="$(prompt_with_default "  SimpleX bot display name (the bot's profile name)" "infochat-bot")"
      # The admin contact id may be pasted as the full SimpleX address link OR
      # the bare queue id: the Provider canonicalizes a full link to the bare
      # queue id at startup (M1-465), so no hand-extraction is needed here. The
      # collect_admin prompt below captures the value verbatim — this script
      # does NOT extract the queue id (that is real URI parsing done in Java).
      echo "  Bootstrap admin: paste your full SimpleX address link (or the bare"
      echo "  queue id) — the Provider extracts the bare queue id from a full link."
      if collect_admin INFOCHAT_SIMPLEX_ADMIN_CONTACT_ID simplex "$only_adapter"; then
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
      if collect_admin INFOCHAT_SIGNAL_ADMIN_CONTACT_ID signal "$only_adapter"; then
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

# The Provider container bind-mounts each adapter's data-dir at the SAME path it
# is configured at (docker-compose.yml uses ${INFOCHAT_<NAME>_DATA_DIR:-<default>}
# for both the mount source and target); without this the mount is pinned to the
# default path and a custom data-dir lands the identity material where the
# adapter's validate() can't see it (M1-391). Emit the operator's value into
# secrets.env — compose's --env-file (7-apps.sh) interpolates it at `up`. It is
# not a secret, but secrets.env is the only --env-file the orchestrator passes to
# compose. Quoted for the dotenv parser (M1-389). Drop any prior value first so a
# re-run with a changed dir or selection cannot leave a stale mount path behind.
sed -i -e '/^INFOCHAT_SIMPLEX_DATA_DIR=/d' -e '/^INFOCHAT_SIGNAL_DATA_DIR=/d' "$SECRETS_FILE"
for adapter in "${chosen[@]}"; do
  case "$adapter" in
    simplex) printf 'INFOCHAT_SIMPLEX_DATA_DIR="%s"\n' "$(dotenv_escape "$simplex_data_dir")" >> "$SECRETS_FILE" ;;
    signal)  printf 'INFOCHAT_SIGNAL_DATA_DIR="%s"\n' "$(dotenv_escape "$signal_data_dir")" >> "$SECRETS_FILE" ;;
  esac
done

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
        # Wizard-provisioning input only: 6b-simplex-provision.sh reads this to
        # create the bot profile (--create-bot-display-name) before the Provider
        # starts. SimpleXConfig reads binary/data-dir/ws-port/bootstrap-admin via
        # explicit @ConfigProperty and never this key, so the runtime adapter
        # ignores it — the bot contact id stays derived from the identity material
        # at startup (§7.5), not from an operator-typed name.
        printf 'infochat.adapters.simplex.display-name=%s\n' "$simplex_display_name"
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
