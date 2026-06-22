#!/bin/bash
# prod/scripts/backup.sh — operator backup wrapper (§7.10 Backups, D34/D46).
#
# Cron entry point so an operator's crontab calls one named wrapper rather than
# inlining pg_dump / tar (§7.10). Produces two date-stamped artifacts in the
# backup directory:
#   - infochat-YYYYMMDD.pgc   — pg_dump -F c of the `infochat` database (custom
#                               format, pg_restore-able per the §7.10 restore
#                               procedure). Includes the audit log.
#   - adapters-YYYYMMDD.tgz   — tar of every configured per-adapter identity
#                               data-dir (the SimpleX queue keypair and the
#                               signal-cli account directory). This material is
#                               UNRECOVERABLE on loss (§7.10).
#
# Targets the SHIPPED docker-compose deployment, NOT the /opt/infochat
# systemd/jar layout the older §7.10 snippet assumed: the DB dump runs inside
# the `postgres` compose service, and the identity tar covers the operator's
# INFOCHAT_*_DATA_DIR host paths recorded in prod/runtime/secrets.env by
# 6-adapter.sh.
#
# Retention/pruning of old artifacts is NOT this script's job — it stays in the
# operator's crontab as the independent `find ... -mtime` lines shown in §7.10;
# the date-stamped names above are what those `find` patterns match.
#
# Backups contain sensitive material (the audit log inside the DB dump, the
# irreplaceable identity keypairs in the tar). Encryption-at-rest is the
# operator's responsibility (D34/§7.10) — store the backup directory encrypted.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
DEFAULT_BACKUP_DIR="/backups"

usage() {
  echo "Usage: backup.sh [BACKUP_DIR] [-h|--help]"
  echo "  Dump the compose Postgres 'infochat' DB (pg_dump -F c) and tar the"
  echo "  configured adapter identity data-dirs into BACKUP_DIR as date-stamped"
  echo "  infochat-YYYYMMDD.pgc + adapters-YYYYMMDD.tgz."
  echo "  BACKUP_DIR  precedence: arg > \$INFOCHAT_BACKUP_DIR > ${DEFAULT_BACKUP_DIR}."
  echo "  Retention stays in the crontab (the §7.10 'find ... -mtime' lines)."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac

# Backup directory: positional arg wins, then env, then the §7.10 default.
BACKUP_DIR="${1:-${INFOCHAT_BACKUP_DIR:-$DEFAULT_BACKUP_DIR}}"

# System-boundary precondition: the wizard (2-secrets.sh / 6-adapter.sh) writes
# secrets.env; without it there is no deployment to back up and no DB password
# or data-dir paths to find. Fail loud with the actionable fix rather than
# producing a half-empty backup.
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found — run the setup wizard (prod/setup.sh) first." >&2
  exit 1
fi

# Read a single value back from the dotenv-format secrets.env WITHOUT sourcing
# it. 6-adapter.sh writes DATA_DIR values quoted + escaped for compose's
# --env-file dotenv parser (dotenv_escape: \ then " then $); `source` would
# re-interpret those sequences as shell, not dotenv (the M1-389/M1-397 footgun).
# Returns the decoded value on stdout, empty when the key is absent (= adapter
# not configured). Escapes are reversed in the opposite order they were applied.
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

STAMP="$(date +%Y%m%d)"
mkdir -p "$BACKUP_DIR"

DB_ARTIFACT="$BACKUP_DIR/infochat-$STAMP.pgc"
ADAPTERS_ARTIFACT="$BACKUP_DIR/adapters-$STAMP.tgz"

# ── Postgres dump ──────────────────────────────────────────────────────
# Run pg_dump INSIDE the postgres compose service: the container already holds
# INFOCHAT_DB_PASSWORD (the `infochat` owner's password, docker/postgres-init.sh)
# in its environment, so no secret is read or echoed on the host. -h 127.0.0.1
# forces the password (scram) auth path rather than local-socket peer auth (the
# container's OS user is `postgres`, not the `infochat` role). --env-file keeps
# compose's ${INFOCHAT_*} interpolation quiet. stdout (the custom-format dump) is
# captured on the host; -T disables the pseudo-TTY so the binary stream is clean.
echo "+ pg_dump -F c infochat (via docker compose exec postgres) -> $DB_ARTIFACT"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" pg_dump -h 127.0.0.1 -U infochat -F c infochat' \
  > "$DB_ARTIFACT"

# ── Adapter identity tar ───────────────────────────────────────────────
# Back up only the data-dirs of adapters the operator actually configured —
# 6-adapter.sh writes INFOCHAT_<NAME>_DATA_DIR into secrets.env only for chosen
# adapters, so an absent var means that adapter is not enabled (skip it). A var
# that IS set but whose directory is missing on disk is a real failure (the
# identity material is gone or the path is wrong) — fail loud rather than tar an
# empty archive. Paths are stored relative to / (tar -C /) so the §7.10 restore
# step `tar -xzpf adapters-*.tgz -C /` reconstructs each dir at its original
# location with modes preserved (-p) — both clients reject world-readable keys.
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
  echo "FAIL: no configured adapter data-dir found in $SECRETS_FILE — nothing to back up." >&2
  exit 1
fi

echo "+ tar -czpf $ADAPTERS_ARTIFACT (-C /) ${adapter_rel_paths[*]}"
tar -C / -czpf "$ADAPTERS_ARTIFACT" "${adapter_rel_paths[@]}"

echo "backup complete: $DB_ARTIFACT + $ADAPTERS_ARTIFACT"
