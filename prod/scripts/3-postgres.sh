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
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
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

# Guard the standalone-run case: the wizard always runs step 2 first, but this
# step can be invoked on its own (the guide documents that), and compose's
# --env-file errors opaquely on a missing file. Fail with a pointer to step 2
# instead, mirroring 4-llm.sh's missing-config guard.
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found; run 2-secrets.sh (wizard step 2) first." >&2
  exit 1
fi

# --env-file feeds the INFOCHAT_*_PASSWORD values to compose's dotenv parser
# (M1-389) so postgres-init's ${VAR:?} bootstrap guards see the minted secrets;
# the orchestrator no longer sources secrets.env into the environment.
echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod up -d --wait --wait-timeout $WAIT_TIMEOUT postgres"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod up -d --wait --wait-timeout "$WAIT_TIMEOUT" postgres
echo "postgres: healthy."
