#!/bin/bash
# prod/scripts/8-verify.sh — wizard step 8: health smoke of the running
# deployment (design §7.7.2 step 8). Polls /q/health on each app's main loopback
# HTTP port (Collector 8080 / Provider 8081 — the §7.12.1 shipped per-module
# defaults, bound to container loopback), reached inside the container via
# `docker compose exec` — the same loopback bind the Collector's compose
# healthcheck uses — until each reports UP or a timeout elapses, then prints a
# green/red summary naming any unhealthy component.
#
# Provider readiness reports UP once at least one adapter is connected (design
# §Bootstrap behavior): an overall-UP body whose per-adapter sub-checks include a
# DOWN is a degraded-but-up deployment — surfaced as a note, NOT a failure
# (ticket Notes). Exit is non-zero iff a service never reaches UP before the
# timeout.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
POLL_TIMEOUT=120
POLL_INTERVAL=5

usage() {
  echo "Usage: 8-verify.sh [--defaults] [-h|--help]"
  echo "  Poll /q/health on the Collector (8080) and Provider (8081) loopback"
  echo "  binds via docker compose exec until each reports UP or ${POLL_TIMEOUT}s"
  echo "  elapses, then print a green/red summary. Exits non-zero on timeout."
  echo "  --defaults  accepted no-op (this step has no prompts)."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

# Standalone-run guard: poll_health passes --env-file "$SECRETS_FILE" to compose,
# which errors opaquely on a missing file; fail with a pointer to the steps that
# create it (mirrors 3-postgres.sh).
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found; run the earlier wizard steps first (secrets.env is created in step 2, 2-secrets.sh)." >&2
  exit 1
fi

# Poll one service's /q/health from inside its container until curl sees an UP
# (HTTP 200; `curl -f` fails on the 503 a not-yet-ready service returns) or the
# per-service deadline passes. On success the UP body is echoed to stdout so the
# caller can scan it for degraded sub-checks; returns non-zero on timeout.
poll_health() {
  local service="$1" port="$2" body
  local deadline=$(( SECONDS + POLL_TIMEOUT ))
  while (( SECONDS < deadline )); do
    if body=$(docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod exec -T "$service" \
                curl -fsS "http://127.0.0.1:${port}/q/health" 2>/dev/null); then
      printf '%s' "$body"
      return 0
    fi
    sleep "$POLL_INTERVAL"
  done
  return 1
}

exit_code=0
summary=()

check() {
  local label="$1" service="$2" port="$3" body
  echo "+ docker compose exec $service curl 127.0.0.1:${port}/q/health (poll up to ${POLL_TIMEOUT}s)"
  if body=$(poll_health "$service" "$port"); then
    if printf '%s' "$body" | grep -q '"status": *"DOWN"'; then
      summary+=("DEGRADED  $label ($service:$port) — overall UP, some sub-checks DOWN:")
      summary+=("          $body")
    else
      summary+=("GREEN     $label ($service:$port) — UP")
    fi
  else
    summary+=("RED       $label ($service:$port) — not UP after ${POLL_TIMEOUT}s")
    exit_code=1
  fi
}

check "Collector" infochat-collector 8080
check "Provider"  infochat-provider  8081

echo
echo "=== deployment health summary ==="
for line in "${summary[@]}"; do
  echo "$line"
done

if [[ "$exit_code" -eq 0 ]]; then
  echo "all components healthy."
else
  echo "one or more components are not healthy (see above)." >&2
fi
exit "$exit_code"
