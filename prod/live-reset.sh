#!/bin/bash
# prod/live-reset.sh — live-run "workflow reset" (M1-536).
#
# Clears the CONTROL-plane (users / groups / invites / chat / audit / provider
# state) of a RUNNING compose deployment while PRESERVING the DATA-plane (the
# already-fetched, already-evaluated source / tag / post + embeddings / entities
# / references / price snapshots). This is the fast-iteration reset for the
# live-e2e loop (docs/plan/live-e2e/README.md): re-run the whole chat-app
# workflow from "scratch" app state WITHOUT re-fetching real feeds every time.
#
# This is NOT prod/setup.sh --reset. That path is the FULL teardown
# (`docker compose down`, and per M1-395 the LLM services too) — it destroys the
# containers and the data-plane. This script leaves every container and every
# fetched post in place; only the control-plane rows go. It is TEST-loop tooling
# and MUST NOT be run against a production deployment: it deliberately clears the
# append-only audit_log (Invariant 10 / D34), which prod never permits.
#
# The FK-safe, no-CASCADE data-only reset itself lives in
# prod/sql/reset-control-plane.sql (run under the DATABASE OWNER role, which the
# NOLOGIN application roles cannot do); this wrapper reaches that owner exactly
# the way backup.sh reaches Postgres — docker compose exec into the `postgres`
# service with PGPASSWORD=$INFOCHAT_DB_PASSWORD, -U infochat — and brackets the
# reset with the pre/post verification the ticket pins:
#   * data-plane post count captured before and asserted UNCHANGED after, and
#   * every control-plane table asserted EMPTY after.
# The script exits non-zero if either check fails.
#
# After a successful reset, restart the Provider to re-seed: with the users rows
# gone, AdminBootstrap re-creates the configured admin and the SimpleX
# admin-claim token re-arms (its single-use gate is the presence of a
# (simplex, is_admin) row — D50). Adapter identity data-dirs are never touched.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$SCRIPT_DIR"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
SQL_FILE="$PROD_DIR/sql/reset-control-plane.sql"

# The 19 control-plane tables cleared by reset-control-plane.sql — the single
# source of truth for the post-reset emptiness assertion below. Keep in sync
# with the TRUNCATE/DELETE set in that SQL file.
CONTROL_PLANE_TABLES=(
  users groups group_membership invite_code invite_code_attempt
  source_subscription scope_tag scope_preferences chat_session chat_message
  chat_memory summary_anchor summary_cache saved_post audit_log
  quarantine admin_notification_state provider_state auto_joined_group
)

usage() {
  echo "Usage: live-reset.sh [-h|--help]"
  echo "  Clear the control-plane of the running compose deployment while"
  echo "  preserving the fetched data-plane (posts + embeddings). TEST-loop"
  echo "  tooling only — NOT prod/setup.sh --reset (that is a full teardown)."
  echo "  Reachability + the DB owner password come from $SECRETS_FILE."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  "") ;;
  *) echo "FAIL: unexpected argument '$1'." >&2; usage >&2; exit 1 ;;
esac

# System-boundary preconditions: without secrets.env there is no deployment to
# reset and no owner password; without the SQL file there is nothing to run.
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found — run the setup wizard (prod/setup.sh) first." >&2
  exit 1
fi
if [[ ! -f "$SQL_FILE" ]]; then
  echo "FAIL: $SQL_FILE not found — the reset SQL is missing from the checkout." >&2
  exit 1
fi

# Run psql inside the postgres compose service as the owner role (`infochat`).
# -h 127.0.0.1 forces scram password auth (the container OS user is `postgres`,
# not the infochat role); -T disables the pseudo-TTY so stdin/stdout are clean
# (the reset SQL is streamed in via `-f -`). Args passed to this function go
# straight to psql. Mirrors backup.sh's exec pattern.
compose_psql() {
  docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
    sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" exec psql -h 127.0.0.1 -U infochat -d infochat "$@"' _ "$@"
}

# Reachability preflight (system boundary: is the deployment actually running?).
# A clear pointer beats a raw psql/compose error on the first real query.
if ! compose_psql -tAc "SELECT 1" >/dev/null 2>&1; then
  echo "FAIL: cannot reach the Postgres owner connection — is the deployment running? (prod/setup.sh)" >&2
  exit 1
fi

# Capture the data-plane size BEFORE the reset. post is partitioned; count(*) on
# the parent aggregates every partition. This is the invariant the reset must
# not disturb.
post_before="$(compose_psql -tAc "SELECT count(*) FROM post")"

echo "+ resetting control-plane (preserving $post_before data-plane posts) via prod/sql/reset-control-plane.sql"
compose_psql -v ON_ERROR_STOP=1 -f - < "$SQL_FILE"

# Verify: data-plane post count unchanged, and every control-plane table empty.
post_after="$(compose_psql -tAc "SELECT count(*) FROM post")"

# One round-trip: UNION the per-table counts, keep only the non-empty ones. The
# table list comes from CONTROL_PLANE_TABLES so it is defined in exactly one
# place. Empty output => every control-plane table is empty.
union_sql=""
for t in "${CONTROL_PLANE_TABLES[@]}"; do
  union_sql+="SELECT '$t' AS t, count(*) AS n FROM $t UNION ALL "
done
union_sql="SELECT t || '=' || n FROM (${union_sql%UNION ALL }) s WHERE n > 0 ORDER BY t"
offenders="$(compose_psql -tAc "$union_sql")"

fail=0
if [[ -n "$offenders" ]]; then
  echo "FAIL: control-plane not empty after reset:" >&2
  echo "$offenders" | sed 's/^/  /' >&2
  fail=1
fi
if [[ "$post_after" != "$post_before" ]]; then
  echo "FAIL: data-plane NOT preserved — post count changed: before=$post_before after=$post_after" >&2
  fail=1
fi
if [[ "$fail" -ne 0 ]]; then
  exit 1
fi

echo "live-reset OK: control-plane cleared (${#CONTROL_PLANE_TABLES[@]} tables empty); data-plane preserved ($post_after posts unchanged)."
echo "  Restart the Provider to re-seed the bootstrap admin and re-arm the SimpleX admin-claim token."
