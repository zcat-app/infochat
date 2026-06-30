#!/bin/bash
# prod/scripts/6b-simplex-provision.sh — SimpleX bot-identity provisioning,
# invoked by 7-apps.sh AFTER the Provider image is built and BEFORE any app
# container serves traffic (design §7.7.2 "What stays manual").
#
# Runs the baked simplex-chat (the binary lives only inside the Provider image,
# not on the host) against the bind-mounted data-dir to:
#   1. create the bot profile from the operator-supplied display name,
#   2. create the bot's contact address (/ad),
#   3. enable auto-accept of incoming contact requests (/auto_accept on),
# then re-query the address to surface the contact link to the operator.
#
# This is OPERATOR-RUN provisioning, distinct from the running Provider
# synthesizing identity: it executes before the Provider starts, with the
# operator's own inputs, into the operator-owned data-dir (../spec/deployment.md
# §Operator inputs item 7). The runtime adapter is unchanged — it still only
# reads its identity at startup and fails if absent.
#
# Idempotent: a second run rotates neither profile nor address — a fresh
# --create-bot-display-name is a no-op when a profile exists, and a second /ad
# reports "you already have chat address" (spike: .scratch/simplex-spike-findings.md
# items 3, 7). Success/failure is decided by PARSING STDOUT for `bad chat command`
# / error markers, NOT the exit code: a malformed simplex-chat command still
# exits 0 (spike item 5).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"

# MUST match SimpleXSubprocess.DB_PREFIX_BASENAME: -d takes a path PREFIX, not a
# directory, so simplex-chat writes <prefix>_chat.db / <prefix>_agent.db. The
# prefix is placed INSIDE the data-dir (<data-dir>/simplex_v1) so the identity
# DBs land within the bind-mounted directory the running adapter reads; a bare
# <data-dir> prefix would write them as siblings OUTSIDE the mount where the
# Provider cannot see them (spike item 2 / B2).
DB_PREFIX_BASENAME="simplex_v1"

usage() {
  echo "Usage: 6b-simplex-provision.sh [-h|--help]"
  echo "  Provision the SimpleX bot identity (profile + contact address +"
  echo "  auto-accept) by running the Provider image's baked simplex-chat against"
  echo "  the mounted data-dir, before the Provider container starts. Idempotent;"
  echo "  a no-op when 'simplex' is not an enabled adapter. Reads the data-dir and"
  echo "  display name from $CONFIG_FILE (written by 6-adapter.sh)."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

# Read a property value from application.properties (plain, unquoted — the file
# is `key=value` lines). `|| true` so a missing key under `set -o pipefail`
# (grep no-match → exit 1) yields an empty string instead of aborting.
prop() {
  grep -E "^$1=" "$CONFIG_FILE" 2>/dev/null | head -n1 | cut -d= -f2- || true
}

# Gate: only provision when simplex is an enabled adapter. 6-adapter.sh writes a
# comma-separated list with no spaces, but strip whitespace defensively (this is
# a system boundary — an operator could hand-edit the file).
adapters="$(prop 'infochat.adapters')"
adapters="${adapters// /}"
case ",${adapters}," in
  *,simplex,*) ;;
  *) echo "simplex is not an enabled adapter; nothing to provision."; exit 0 ;;
esac

data_dir="$(prop 'infochat.adapters.simplex.data-dir')"
display_name="$(prop 'infochat.adapters.simplex.display-name')"

# System-boundary validation: both are written by 6-adapter.sh, but a hand-edited
# or partially-written config must fail loudly rather than provision a nameless or
# mislocated identity.
if [[ -z "$data_dir" ]]; then
  echo "FAIL: infochat.adapters.simplex.data-dir is not set in $CONFIG_FILE." >&2
  echo "      Re-run the wizard's step 6 (6-adapter.sh) to configure SimpleX." >&2
  exit 1
fi
if [[ -z "$display_name" ]]; then
  echo "FAIL: infochat.adapters.simplex.display-name is not set in $CONFIG_FILE." >&2
  echo "      Re-run the wizard's step 6 (6-adapter.sh) to choose a bot name." >&2
  exit 1
fi
# Standalone-run guard (reached only when simplex IS enabled): run_sx passes
# --env-file "$SECRETS_FILE" to compose, which errors opaquely on a missing file;
# fail with a pointer to the step that creates it (mirrors 3-postgres.sh).
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found; run 2-secrets.sh (wizard step 2) first." >&2
  exit 1
fi

# Ensure the data-dir exists so the bind mount has a source and the prefix DBs
# can be written into it (the adapter's SimpleXConfig.validate() also requires an
# existing directory). Provisioning runs as the same uid the Provider's own
# simplex-chat subprocess runs as (the Provider image runs as root), so the
# identity DBs it writes are usable at runtime. Broad data-dir ownership rework is
# a separate concern (ticket out-of-scope).
mkdir -p "$data_dir"

db_prefix="$data_dir/$DB_PREFIX_BASENAME"

# Run one simplex-chat command in the Provider image against the mounted data-dir.
# --no-deps so the one-shot run does NOT start postgres/collector (the Provider's
# compose depends_on) — provisioning needs only the image + the data-dir mount,
# and the apps must not come up until 7-apps.sh starts them in Topology order.
# -y auto-confirms first-run DB migrations so they do not block on a prompt.
#
# Sets the global SX_OUT to the captured stdout+stderr. Aborts on a docker-level
# failure (rc != 0); detects a simplex-level failure (rc 0 but a `bad chat
# command` / error marker on stdout) and aborts there too.
SX_OUT=""
run_sx() {
  local label="$1"; shift
  # Capture without letting errexit fire on a non-zero docker exit — we want to
  # report it with context, not abort opaquely.
  set +e
  SX_OUT="$(docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod \
    run --rm --no-deps --entrypoint /usr/local/bin/simplex-chat infochat-provider \
    -d "$db_prefix" -y "$@" 2>&1)"
  local rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    echo "FAIL: 'docker compose run simplex-chat' exited $rc during the ${label} step." >&2
    echo "      The SimpleX bot identity was not fully provisioned; the Provider was not started." >&2
    exit 1
  fi
  # Parse stdout, NOT the exit code: a bad simplex-chat command still exits 0.
  # Guarded inside `if` so a no-match grep does not trip errexit/pipefail. Echo
  # only the matched marker line(s) — never the full output (D37: simplex-chat
  # output can carry contact links / message envelopes).
  if printf '%s' "$SX_OUT" | grep -qiE 'bad chat command|(^|[^a-z])error'; then
    echo "FAIL: simplex-chat rejected the ${label} command — provisioning aborted." >&2
    echo "      (simplex-chat exits 0 even on a bad command, so this is caught by" >&2
    echo "       parsing its output, not its exit code.)" >&2
    printf '%s\n' "$SX_OUT" | grep -iE 'bad chat command|error' >&2 || true
    exit 1
  fi
}

echo "+ provisioning SimpleX bot identity at ${db_prefix}_*.db (display name: ${display_name})"

# 1. Create the bot profile non-interactively (startup flag, not -e). No-op when
#    a profile already exists, so safe to re-run. Bundled with /show_address so a
#    fresh DB's interactive display-name prompt never fires (spike item 3).
run_sx "profile-create" --create-bot-display-name "$display_name" -t 3 -e "/show_address"

# 2. Create the contact address. Idempotent: a second /ad is a no-op
#    ("you already have chat address") and does not rotate the address.
run_sx "address-create" -t 10 -e "/ad"

# 3. Enable auto-accept of incoming contact requests. Auto-accept opens only the
#    transport connection — the invite gate (D44) still rejects un-invited
#    contacts, so this never bypasses registration.
run_sx "auto-accept" -t 4 -e "/auto_accept on"

# 4. Re-query the address to surface the contact link to the operator. /ad only
#    prints the link on first creation, so re-query via /show_address so the link
#    is available on a re-run too.
run_sx "show-address" -t 4 -e "/show_address"

echo "SimpleX bot identity provisioned (profile + address + auto-accept)."

# Surface the contact link transiently to the operator. D37: the raw queue
# address is NEVER written to application.properties, secrets.env, or any log —
# it is printed once to the wizard terminal here. simplex-chat output carries two
# URLs (the primary smp link and a legacy simplex.chat/contact line); take the
# first https://smp… (spike item 4).
link="$(printf '%s\n' "$SX_OUT" | grep -oE 'https://smp[^[:space:]]+' | head -n1)" || true
echo
if [[ -n "$link" ]]; then
  echo "The bot's SimpleX contact link (share it with people who should connect):"
  echo "  $link"
  echo "(Not saved anywhere — copy it now if you need it.)"
else
  echo "The bot's SimpleX address is set; connect from your personal SimpleX app"
  echo "and the bot will auto-accept."
fi
