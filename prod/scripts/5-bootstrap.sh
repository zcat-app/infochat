#!/bin/bash
# prod/scripts/5-bootstrap.sh — wizard step 5: seed the runtime bootstrap-sources
# and bootstrap-assets files from the committed templates (§7.7.2 step 5).
#
# Copies prod/config/bootstrap-sources.json into the runtime dir only when no
# runtime sources file exists yet — a resumed run never clobbers an operator's
# edited sources file (§7.7.2 idempotency). Asset commands ship ENABLED by
# default (§7.6.2): on plain Enter the bundled zcash+monero
# prod/config/bootstrap-assets.json is copied into the runtime dir (self-heal,
# never clobbering an existing one). The operator may instead supply a custom
# path (copied into the runtime location), or disable assets entirely (which
# REMOVES the property so a stale path can't trip the fail-fast).
# --defaults wires the bundled asset defaults non-interactively.
#
# The wizard writes infochat.bootstrap.{sources,assets}-file as the BARE
# BASENAME, never a host-absolute path: docker-compose bind-mounts each runtime
# file at /app/<basename> (the fast-jar workdir), so the Collector/Provider
# resolve the basename relative to their CWD inside the container. A host path
# such as $RUNTIME_DIR/... does not exist inside the container and would crash
# startup — the failure mode this step's mount + basename contract prevents.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
SOURCES_TEMPLATE="$PROD_DIR/config/bootstrap-sources.json"
SOURCES_RUNTIME="$RUNTIME_DIR/bootstrap-sources.json"
ASSETS_TEMPLATE="$PROD_DIR/config/bootstrap-assets.json"
ASSETS_RUNTIME="$RUNTIME_DIR/bootstrap-assets.json"
# In-container basenames the loaders resolve against the fast-jar workdir
# (/app). docker-compose mounts $SOURCES_RUNTIME / $ASSETS_RUNTIME at these
# paths, so the config must reference the basename — not the host path.
SOURCES_BASENAME="bootstrap-sources.json"
ASSETS_BASENAME="bootstrap-assets.json"

usage() {
  echo "Usage: 5-bootstrap.sh [--defaults] [-h|--help]"
  echo "  Copy the committed bootstrap-sources.json template into the runtime dir"
  echo "  if none is present (never overwriting an existing one), then enable"
  echo "  asset commands: wire infochat.bootstrap.assets-file to the bundled"
  echo "  zcash+monero defaults, a custom path, or disable them."
  echo "  --defaults  wire the bundled asset defaults non-interactively."
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

# Remove a property entirely. Disabling a feature must leave the property UNSET
# (not present-but-empty) so the Collector reads it as "off" instead of failing
# fast on a stale or blank path (§7.6.2 file-state semantics).
del_prop() {
  local key="$1"
  local escaped="${key//./\\.}"
  if [[ -f "$CONFIG_FILE" ]]; then
    sed -i "/^${escaped}=/d" "$CONFIG_FILE"
  fi
}

umask 077
mkdir -p "$RUNTIME_DIR"

# Fail early with a clear message rather than a raw I/O error mid-write if the
# runtime dir or an existing config file is not writable (wrong owner, read-only
# mount). This one check guards every copy and property write below.
if [[ ! -w "$RUNTIME_DIR" ]]; then
  echo "FAIL: runtime dir not writable: $RUNTIME_DIR" >&2
  exit 1
fi
if [[ -e "$CONFIG_FILE" && ! -w "$CONFIG_FILE" ]]; then
  echo "FAIL: config file not writable: $CONFIG_FILE" >&2
  exit 1
fi

# Never clobber an existing runtime sources file — a resumed run preserves any
# operator edits (§7.7.2 "No generated [value] is overwritten").
if [[ -f "$SOURCES_RUNTIME" ]]; then
  echo "skip bootstrap-sources.json (already present in runtime dir)"
else
  echo "+ cp $SOURCES_TEMPLATE $SOURCES_RUNTIME"
  cp "$SOURCES_TEMPLATE" "$SOURCES_RUNTIME"
fi
# Pin the property to the in-container basename (BootstrapLoader resolves it
# against the fast-jar workdir /app, where compose mounts $SOURCES_RUNTIME). The
# code default is the same basename, but writing it keeps the generated config
# self-contained instead of depending on an implicit in-code default.
echo "+ set infochat.bootstrap.sources-file=$SOURCES_BASENAME in $CONFIG_FILE"
set_prop infochat.bootstrap.sources-file "$SOURCES_BASENAME"

# Asset commands ship enabled by default (§7.6.2). Plain Enter keeps the bundled
# zcash+monero defaults; the operator can name their own file or answer "no".
enable_assets=1
custom_assets_path=""
if [[ "$defaults" -eq 0 ]]; then
  read -rp "Enable crypto asset commands (zcash, monero)? [Yes/no]: " answer
  case "${answer,,}" in
    n|no) enable_assets=0 ;;
    *)    enable_assets=1 ;;
  esac
  if [[ "$enable_assets" -eq 1 ]]; then
    read -rp "Path to a custom bootstrap-assets.json (blank = bundled zcash+monero defaults): " custom_assets_path
  fi
fi

# The container ALWAYS bind-mounts $ASSETS_RUNTIME at /app/$ASSETS_BASENAME, so
# the runtime file must always exist — otherwise Docker materialises the bind
# source as an empty directory. Self-heal the bundled default first (never
# clobbering an operator-edited runtime file), regardless of the enable choice
# below, so the mount source is always a real file even when assets are disabled.
if [[ ! -r "$ASSETS_TEMPLATE" ]]; then
  echo "FAIL: bundled assets template not readable: $ASSETS_TEMPLATE" >&2
  exit 1
fi
if [[ -f "$ASSETS_RUNTIME" ]]; then
  echo "skip bootstrap-assets.json (already present in runtime dir)"
else
  echo "+ cp $ASSETS_TEMPLATE $ASSETS_RUNTIME"
  cp "$ASSETS_TEMPLATE" "$ASSETS_RUNTIME"
fi

if [[ "$enable_assets" -eq 0 ]]; then
  # Drop any prior value so the Collector sees the property as unset (= disabled),
  # never a stale path. This also clears a value left by an earlier run. The
  # runtime file stays in place (dormant) so the unconditional mount is still safe.
  del_prop infochat.bootstrap.assets-file
  echo "asset commands disabled"
else
  if [[ -n "$custom_assets_path" ]]; then
    # The container mounts only the runtime copy, so a custom file must be placed
    # there. Copy it into the runtime location (overwriting the bundled default)
    # so the single mounted path carries the operator's content. Fail now on an
    # unreadable path rather than letting the Collector fail-fast at first boot.
    if [[ ! -r "$custom_assets_path" ]]; then
      echo "FAIL: custom bootstrap-assets.json not readable: $custom_assets_path" >&2
      exit 1
    fi
    echo "+ cp $custom_assets_path $ASSETS_RUNTIME"
    cp "$custom_assets_path" "$ASSETS_RUNTIME"
  fi
  echo "+ set infochat.bootstrap.assets-file=$ASSETS_BASENAME in $CONFIG_FILE"
  set_prop infochat.bootstrap.assets-file "$ASSETS_BASENAME"
fi

echo "bootstrap sources ready: $SOURCES_RUNTIME"
