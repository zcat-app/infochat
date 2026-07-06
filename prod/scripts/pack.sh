#!/bin/bash
# prod/scripts/pack.sh — host-clone migration bundler (§7.10.1 "Migrating to
# another device", M1-567). Produces ONE transferable archive that reconstructs
# the entire deployment on another host: the `infochat` DB (pg_dump -F c —
# includes the audit log), every configured adapter identity data-dir (the
# SimpleX queue keypair + the signal-cli account tree, UNRECOVERABLE on loss),
# prod/runtime/application.properties, prod/runtime/secrets.env, and the
# bootstrap files. restore.sh on the target unpacks it into an exact clone.
#
# pack.sh is a SUPERSET of backup.sh, not a replacement. backup.sh (the
# cron-invoked upkeep wrapper) DELIBERATELY excludes config + secrets (§7.10) —
# its two-artifact output and retention story are untouched (M1-567
# out-of-scope). pack.sh additionally bundles application.properties + secrets
# + bootstrap files because a host CLONE needs the DB passwords and the
# admin/LLM config that backup.sh omits; without them the target cannot stand up.
#
# READ-ONLY on the source: pack.sh only dumps and copies — it never mutates or
# deletes source state, so it is safe to run against a still-running deployment.
# Decommissioning the source is a SEPARATE, post-verification operator step
# (apps.sh stop, then optionally setup.sh --reset --hard), never part of packing
# (M1-567): a corrupt bundle or a failed target bring-up must still leave a
# working source.
#
# The bundle is the single HIGHEST-VALUE artifact this system emits — it carries
# every secret at once. Created 0600 under a 077 umask; encryption for TRANSFER
# and STORAGE stays the operator's responsibility (D34/§7.10). secrets.env is
# never `source`d and bundle contents are never echoed (M1-389/M1-397).
set -euo pipefail

# Every file this script creates (the staging tree, the nested identity tar, the
# final archive) holds secret material; a 077 umask makes them 0600/0700 from
# birth, closing the window before the explicit chmod 600 on the final archive.
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
DEFAULT_OUT_DIR="$RUNTIME_DIR/migration"

# The adapter identity dirs are written root:root by the ROOT Provider container,
# and signal-cli locks its account store to mode 0700 — so a non-root host `tar`
# cannot READ them (M1-569). We tar them as root INSIDE a throwaway container (the
# same in-container-privilege pattern as the pg_dump below), so no interactive
# sudo is needed. The image only has to ship GNU tar (busybox tar under-preserves
# modes); we reuse the pinned postgres image the deployment already requires rather
# than introduce a new dependency — any GNU-tar image works, so drift from the
# compose `image:` tag is harmless (it would just pull a second image).
IDENTITY_TAR_IMAGE="pgvector/pgvector:pg16"

usage() {
  echo "Usage: pack.sh [OUT_DIR] [-h|--help]"
  echo "  Bundle the whole deployment (DB dump + adapter identities + config +"
  echo "  secrets + bootstrap files) into ONE 0600 archive in OUT_DIR:"
  echo "    infochat-migration-YYYYMMDD.tgz"
  echo "  Transfer it to the target host and run restore.sh there."
  echo "  OUT_DIR  precedence: arg > \$INFOCHAT_MIGRATION_DIR > ${DEFAULT_OUT_DIR}."
  echo "  The archive contains EVERY secret — transfer and store it encrypted."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac

OUT_DIR="${1:-${INFOCHAT_MIGRATION_DIR:-$DEFAULT_OUT_DIR}}"

# System-boundary precondition: secrets.env is written by the wizard
# (2-secrets.sh / 6-adapter.sh) and carries the DB password plus the adapter
# data-dir paths. Without it there is no deployment to clone. Fail loud with the
# actionable fix rather than emitting a half-empty bundle (mirrors backup.sh).
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found — run the setup wizard (prod/setup.sh) first." >&2
  exit 1
fi

# Read one value from the dotenv-format secrets.env WITHOUT sourcing it —
# 6-adapter.sh writes DATA_DIR values quoted + escaped for compose's --env-file
# dotenv parser (dotenv_escape: \ then " then $); `source` would re-interpret
# those escapes as shell (the M1-389/M1-397 footgun). Returns the decoded value
# on stdout, empty when the key is absent (= adapter not configured). Escapes
# are reversed in the opposite order they were applied. (Verbatim from backup.sh
# — pack.sh duplicates rather than factors out because backup.sh's contract is
# frozen out-of-scope, M1-567.)
read_dotenv_value() {
  local key="$1" file="$2" line val
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  [[ -z "$line" ]] && return 0
  val="${line#"${key}"=}"
  if [[ "$val" == \"*\" ]]; then
    val="${val#\"}"; val="${val%\"}"
  fi
  val="${val//\\\$/\$}"   # \$ -> $
  val="${val//\\\"/\"}"   # \" -> "
  val="${val//\\\\/\\}"   # \\ -> \
  printf '%s' "$val"
}

# The two config files a deployment cannot run without; fail loud if either is
# missing (a clone without them is useless). bootstrap-assets.json is optional
# (only the asset feature writes it) and copied conditionally below.
APP_PROPS="$RUNTIME_DIR/application.properties"
SOURCES_FILE="$RUNTIME_DIR/bootstrap-sources.json"
for required in "$APP_PROPS" "$SOURCES_FILE"; do
  if [[ ! -f "$required" ]]; then
    echo "FAIL: $required not found — is this a configured deployment? Run prod/setup.sh." >&2
    exit 1
  fi
done

STAMP="$(date +%Y%m%d)"
mkdir -p "$OUT_DIR"
BUNDLE="$OUT_DIR/infochat-migration-$STAMP.tgz"

# Staging tree UNDER OUT_DIR (same filesystem) so secrets never transit a
# world-shared /tmp and the final wrap is a local operation. Removed on ANY exit
# (success, failure, or interrupt) so half-built staging never lingers with
# secret content.
STAGING="$(mktemp -d "$OUT_DIR/.infochat-pack.XXXXXX")"
trap 'rm -rf "$STAGING"' EXIT
mkdir -p "$STAGING/db" "$STAGING/runtime"

# ── (a) Postgres dump ───────────────────────────────────────────────────
# pg_dump -F c INSIDE the postgres compose service so no secret is read on the
# host (the container already holds INFOCHAT_DB_PASSWORD); -h 127.0.0.1 forces
# the password (scram) auth path since the container OS user is `postgres`, not
# the `infochat` role; -T keeps the binary custom-format stream clean. Custom
# format is pg_restore-able and includes the audit log. Requires a RUNNING
# source deployment — pack.sh backs up live state, it does not start containers
# (mirrors backup.sh).
echo "+ pg_dump -F c infochat (via docker compose exec postgres) -> db/infochat.pgc"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" pg_dump -h 127.0.0.1 -U infochat -F c infochat' \
  > "$STAGING/db/infochat.pgc"

# ── (b) Adapter identity tar ────────────────────────────────────────────
# Nested tar of every CONFIGURED adapter data-dir, stored relative to / (-C /)
# with modes preserved (-p) so restore reconstructs each dir at its original
# absolute path with the strict perms both clients require. An absent
# INFOCHAT_<NAME>_DATA_DIR means that adapter is not enabled (skip it); a var
# that IS set but whose dir is missing on disk is a real failure — fail loud,
# never pack an empty identity (mirrors backup.sh). This nested tgz is what makes
# the clone the SAME bot: the same Signal number without re-registration and the
# same SimpleX contact link so nobody has to reconnect.
adapter_rel_paths=()
for key in INFOCHAT_SIMPLEX_DATA_DIR INFOCHAT_SIGNAL_DATA_DIR; do
  dir="$(read_dotenv_value "$key" "$SECRETS_FILE")"
  if [[ -z "$dir" ]]; then
    echo "  skip ${key} (adapter not configured)"
    continue
  fi
  if [[ ! -d "$dir" ]]; then
    echo "FAIL: ${key}=$dir is configured but the directory does not exist." >&2
    exit 1
  fi
  adapter_rel_paths+=("${dir#/}")
done

if [[ "${#adapter_rel_paths[@]}" -eq 0 ]]; then
  echo "FAIL: no configured adapter data-dir found in $SECRETS_FILE — nothing to clone." >&2
  exit 1
fi

# Bind-mount each configured data-dir READ-ONLY at its absolute host path — exactly
# how the Provider mounts them (docker-compose.yml adapter volumes) — so `tar -C /`
# inside the container sees the same rel paths and the archive is byte-identical to
# the old host-tar layout (restore + the M1-568 extraction allowlist both depend on
# that layout). ADAPTER-AGNOSTIC: SimpleX and Signal dirs go through the identical
# privileged path, no per-adapter special-case and no reliance on SimpleX's
# incidental 0644. `-p` preserves modes into the archive; `--entrypoint tar`
# bypasses the postgres image's default entrypoint (the fetch_gguf precedent).
echo "+ tar identities as root in-container (-C /) ${adapter_rel_paths[*]} -> identities.tgz"
tar_mounts=()
for rel in "${adapter_rel_paths[@]}"; do
  tar_mounts+=(-v "/$rel:/$rel:ro")
done
docker run --rm -u 0:0 "${tar_mounts[@]}" --entrypoint tar "$IDENTITY_TAR_IMAGE" \
  -C / -czpf - "${adapter_rel_paths[@]}" > "$STAGING/identities.tgz"

# ── (c)+(d)+(e) config, secrets, bootstrap files ────────────────────────
# Straight copies (cp -p preserves the source's 0600 on secrets.env). These are
# exactly what backup.sh omits and a host clone needs: the DB passwords, the LLM
# API key, the admin config, and the seed sources/assets.
echo "+ copy application.properties + secrets.env + bootstrap files -> runtime/"
cp -p "$APP_PROPS" "$STAGING/runtime/application.properties"
cp -p "$SECRETS_FILE" "$STAGING/runtime/secrets.env"
cp -p "$SOURCES_FILE" "$STAGING/runtime/bootstrap-sources.json"
ASSETS_FILE="$RUNTIME_DIR/bootstrap-assets.json"
if [[ -f "$ASSETS_FILE" ]]; then
  cp -p "$ASSETS_FILE" "$STAGING/runtime/bootstrap-assets.json"
fi

# ── wrap into ONE archive ───────────────────────────────────────────────
# -C "$STAGING" . tars only the staging contents (db/, identities.tgz, runtime/);
# the output archive lives in OUT_DIR — staging's PARENT — so it is never swept
# into its own tar.
echo "+ tar bundle -> $BUNDLE"
tar -C "$STAGING" -czf "$BUNDLE" .
chmod 600 "$BUNDLE"

cat >&2 <<WARN

========================================================================
  WARNING - SECRET MATERIAL
  $BUNDLE
  is the single highest-value artifact this system can emit. It contains:
    - the DB passwords (owner + both service roles)
    - the LLM API key, if a remote backend is configured
    - the FULL audit log (inside the DB dump)
    - the UNRECOVERABLE per-adapter identity keys (Signal + SimpleX)
  Anyone with this file can impersonate the bot and read everything it has
  stored. It is written 0600; encryption for TRANSFER and STORAGE is YOUR
  responsibility (D34/§7.10) - e.g. age/gpg the file before moving it.
  When it has served its purpose, dispose of it: prod/scripts/shred-bundle.sh <bundle>
========================================================================
WARN

echo "pack complete: $BUNDLE"
