#!/bin/bash
# prod/live-seed.sh — live-run synthetic post-corpus seed (M1-537).
#
# Loads a deterministic, ALREADY-EVALUATED post corpus into a RUNNING compose
# deployment's database so the content commands (/summary, /follow-tag,
# /save→/saved, digests) return stable rows without fetching a real feed or
# paying for an LLM. This is the synthetic "future" half of the live-e2e data
# strategy (docs/plan/live-e2e/README.md §3); it composes with M1-536's
# control-plane reset (prod/live-reset.sh), which preserves any once-fetched
# real corpus in place. Run this after a reset (or against a fresh DB).
#
# The corpus + its deterministic-UID scheme live in
# prod/sql/seed-synthetic-corpus.sql (row shapes reused from the M1-413 test
# fixture). It runs under the DATABASE OWNER role (`infochat`): post/source/tag
# are collector-owned and the NOLOGIN provider role has SELECT-only on them
# (M1-163). This wrapper reaches that owner exactly the way live-reset.sh /
# backup.sh do — docker compose exec into the `postgres` service with
# PGPASSWORD=$INFOCHAT_DB_PASSWORD, -U infochat.
#
# TIMESTAMPS ARE THE CLOCK, not a mock. The seeded rows' published_at/ready_at
# are set to `now() - <offset-minutes>` so a tester places them inside or
# outside a given /summary window deterministically without touching the prod
# Clock (hardcoded Clock.systemUTC()). --offset-minutes defaults to 30 (within
# the last hour → inside a 24h window); pass a larger value to place rows out.
#
# IDEMPOTENT: re-running upserts the flat rows on their natural keys and
# delete-then-inserts the partitioned posts by uid, so a second run neither
# duplicates rows nor errors and exits 0.
#
# After load, the script runs a post-load verification (mirroring the
# deterministic retrieval path) and exits non-zero if it does not hold:
#   * exactly the 3 seeded READY posts are retrievable for the subscribed
#     (dm, seed-user) scope inside a 24h window,
#   * the 2 non-READY control posts (RAW, QUARANTINED) are excluded, and
#   * 2 of the 3 READY posts have a NULL embedding (embedding-optional path).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$SCRIPT_DIR"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
SQL_FILE="$PROD_DIR/sql/seed-synthetic-corpus.sql"

# Default window offset: within the last hour, so the seeded READY posts fall
# inside a /summary -w 24h window at load time.
OFFSET_MINUTES=30

usage() {
  echo "Usage: live-seed.sh [--offset-minutes N] [-h|--help]"
  echo "  Load the deterministic synthetic post corpus into the running compose"
  echo "  deployment's database (owner role). Idempotent; safe to re-run."
  echo "  --offset-minutes N  place the seeded posts' published_at/ready_at N"
  echo "                      minutes before now (default 30 → inside a 24h"
  echo "                      window). A large value (e.g. 1500) places them out."
  echo "  Reachability + the DB owner password come from $SECRETS_FILE."
}

# Arg parsing (system boundary): validate --offset-minutes is a non-negative
# integer so the psql make_interval(mins => ...) substitution cannot be injected
# or malformed.
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --offset-minutes)
      OFFSET_MINUTES="${2:-}"; shift 2 || true ;;
    --offset-minutes=*)
      OFFSET_MINUTES="${1#*=}"; shift ;;
    *) echo "FAIL: unexpected argument '$1'." >&2; usage >&2; exit 1 ;;
  esac
done
if ! [[ "$OFFSET_MINUTES" =~ ^[0-9]+$ ]]; then
  echo "FAIL: --offset-minutes must be a non-negative integer (got '$OFFSET_MINUTES')." >&2
  exit 1
fi

# System-boundary preconditions: without secrets.env there is no deployment to
# seed and no owner password; without the SQL file there is nothing to run.
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found — run the setup wizard (prod/setup.sh) first." >&2
  exit 1
fi
if [[ ! -f "$SQL_FILE" ]]; then
  echo "FAIL: $SQL_FILE not found — the seed SQL is missing from the checkout." >&2
  exit 1
fi

# Run psql inside the postgres compose service as the owner role (`infochat`).
# -h 127.0.0.1 forces scram password auth; -T disables the pseudo-TTY so
# stdin/stdout are clean. Args passed to this function go straight to psql.
# Mirrors live-reset.sh / backup.sh's exec pattern.
compose_psql() {
  docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
    sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" exec psql -h 127.0.0.1 -U infochat -d infochat "$@"' _ "$@"
}

# Reachability preflight (system boundary: is the deployment actually running?).
if ! compose_psql -tAc "SELECT 1" >/dev/null 2>&1; then
  echo "FAIL: cannot reach the Postgres owner connection — is the deployment running? (prod/setup.sh)" >&2
  exit 1
fi

echo "+ seeding synthetic corpus (published_at/ready_at = now() - ${OFFSET_MINUTES}m) via prod/sql/seed-synthetic-corpus.sql"
compose_psql -v ON_ERROR_STOP=1 -v offset_minutes="$OFFSET_MINUTES" -f - < "$SQL_FILE"

# ── Post-load verification: prove the corpus is wired into the deterministic
# retrieval SQL path. All three queries key off the seeded source's natural key
# (kind, identifier) and the `m1-537-` uid prefix, so the checks stay decoupled
# from the fixed UUIDs in the SQL file.

# 1. READY posts retrievable for the subscribed (dm, seed-user) scope inside a
#    24h window — the core deterministic path (subscription → source → READY).
ready_retrievable="$(compose_psql -tAc "
  SELECT count(*)
    FROM post p
    JOIN source s ON s.id = p.source_id
    JOIN source_subscription ss
      ON ss.source_id = s.id AND ss.scope_kind = 'dm'
   WHERE s.kind = 'rss' AND s.identifier = 'm1-537-seed-source'
     AND s.deleted_at IS NULL
     AND p.status = 'READY'
     AND p.published_at >= now() - interval '24 hours'
     AND p.uid LIKE 'm1-537-%'")"

# 2. Non-READY control posts present but excluded from the READY path.
non_ready_excluded="$(compose_psql -tAc "
  SELECT count(*) FROM post
   WHERE uid IN ('m1-537-raw', 'm1-537-quarantined') AND status <> 'READY'")"

# 3. Embedding-optional path: READY seed posts with NO embedding row.
null_embedding_ready="$(compose_psql -tAc "
  SELECT count(*) FROM post p
   WHERE p.uid LIKE 'm1-537-ready-%'
     AND NOT EXISTS (SELECT 1 FROM post_embedding e WHERE e.post_id = p.id)")"

fail=0
if [[ "$ready_retrievable" != "3" ]]; then
  echo "FAIL: expected 3 READY posts retrievable for the seeded scope, got $ready_retrievable." >&2
  fail=1
fi
if [[ "$non_ready_excluded" != "2" ]]; then
  echo "FAIL: expected 2 non-READY control posts (RAW, QUARANTINED), got $non_ready_excluded." >&2
  fail=1
fi
if [[ "$null_embedding_ready" != "2" ]]; then
  echo "FAIL: expected 2 READY posts with a NULL embedding, got $null_embedding_ready." >&2
  fail=1
fi
if [[ "$fail" -ne 0 ]]; then
  exit 1
fi

echo "live-seed OK: 3 READY posts retrievable for the subscribed (dm, seed-user) scope"
echo "  (2 with NULL embedding), 2 non-READY control posts excluded from the READY path."
echo "  Re-run any time — the loader is idempotent. Adjust --offset-minutes to move the window."
