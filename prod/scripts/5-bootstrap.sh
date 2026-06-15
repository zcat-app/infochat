#!/bin/bash
# prod/scripts/5-bootstrap.sh — wizard step 5: seed the runtime bootstrap-sources
# file from the committed template and optionally wire an assets file (§7.7.2 step 5).
#
# Copies prod/config/bootstrap-sources.json into the runtime dir only when no
# runtime sources file exists yet — a resumed run never clobbers an operator's
# edited sources file (§7.7.2 idempotency). Asset commands are opt-in (§7.6.2,
# file-state semantics): infochat.bootstrap.assets-file is written only when the
# operator supplies a path; --defaults leaves assets disabled.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
SOURCES_TEMPLATE="$PROD_DIR/config/bootstrap-sources.json"
SOURCES_RUNTIME="$RUNTIME_DIR/bootstrap-sources.json"

usage() {
  echo "Usage: 5-bootstrap.sh [--defaults] [-h|--help]"
  echo "  Copy the committed bootstrap-sources.json template into the runtime dir"
  echo "  if none is present (never overwriting an existing one), then optionally"
  echo "  wire infochat.bootstrap.assets-file for opt-in asset commands."
  echo "  --defaults  leave asset commands disabled (non-interactive)."
}

defaults=0
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) defaults=1 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

# Idempotent property write: drop any existing line for the key, then append the
# new value, mirroring 1-profile.sh so a resumed run replaces rather than
# duplicates each line.
set_prop() {
  local key="$1" value="$2"
  local escaped="${key//./\\.}"
  if [[ -f "$CONFIG_FILE" ]]; then
    sed -i "/^${escaped}=/d" "$CONFIG_FILE"
  fi
  printf '%s=%s\n' "$key" "$value" >> "$CONFIG_FILE"
}

umask 077
mkdir -p "$RUNTIME_DIR"

# Never clobber an existing runtime sources file — a resumed run preserves any
# operator edits (§7.7.2 "No generated [value] is overwritten").
if [[ -f "$SOURCES_RUNTIME" ]]; then
  echo "skip bootstrap-sources.json (already present in runtime dir)"
else
  echo "+ cp $SOURCES_TEMPLATE $SOURCES_RUNTIME"
  cp "$SOURCES_TEMPLATE" "$SOURCES_RUNTIME"
fi

# Asset commands are opt-in (§7.6.2): wire the property only when the operator
# names a file. Under --defaults assets stay disabled (no property written).
assets_file=""
if [[ "$defaults" -eq 0 ]]; then
  read -rp "Optional bootstrap-assets.json path (blank to disable asset commands) [blank]: " assets_file
fi
if [[ -n "$assets_file" ]]; then
  echo "+ set infochat.bootstrap.assets-file=$assets_file in $CONFIG_FILE"
  set_prop infochat.bootstrap.assets-file "$assets_file"
else
  echo "asset commands disabled (no assets file configured)"
fi

echo "bootstrap sources ready: $SOURCES_RUNTIME"
