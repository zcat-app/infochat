#!/bin/bash
# prod/scripts/3-postgres.sh — wizard step 3: start the prod-profile Postgres
# service and block until its container healthcheck passes (§7.7.2 step 3).
#
# The service-role password bootstrap runs inside docker/postgres-init.sh on
# first container init (§7.7); this script only brings the container up and
# waits. `up -d --wait` is idempotent: an already-healthy container returns
# immediately, so a resumed wizard run does not error.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
WAIT_TIMEOUT=120

usage() {
  echo "Usage: 3-postgres.sh [--defaults] [-h|--help]"
  echo "  Start the prod-profile Postgres service and wait (up to ${WAIT_TIMEOUT}s)"
  echo "  until its container healthcheck passes. Idempotent: an already-healthy"
  echo "  container returns immediately."
  echo "  --defaults  accepted no-op (this step has no prompts)."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

echo "+ docker compose -f $COMPOSE_FILE --profile prod up -d --wait --wait-timeout $WAIT_TIMEOUT postgres"
docker compose -f "$COMPOSE_FILE" --profile prod up -d --wait --wait-timeout "$WAIT_TIMEOUT" postgres
echo "postgres: healthy."
