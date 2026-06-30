#!/bin/bash
# prod/scripts/7-apps.sh — wizard step 7: bring up the containerized apps in the
# §Topology startup order — Collector first (it runs Flyway, applying the
# migration set under the advisory lock), then the Provider against the
# already-migrated schema (spec/deployment.md §Topology; design §7.7.2 step 7).
#
# Only the Collector migrates in production, so it MUST be healthy before the
# Provider starts. `up -d --wait` blocks until the Collector's compose
# healthcheck passes; the Provider's own `depends_on: infochat-collector:
# service_healthy` enforces the same ordering, but starting the two in explicit
# phases keeps the wait — and any failure — attributable to the right service.
# Idempotent: an already-healthy container returns immediately on re-run.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
# Generous: the Collector applies the full Flyway migration set on first boot
# before its readiness probe passes (healthcheck start_period 40s + retries).
WAIT_TIMEOUT=300

usage() {
  echo "Usage: 7-apps.sh [--defaults] [-h|--help]"
  echo "  Start the prod-profile Collector and wait (up to ${WAIT_TIMEOUT}s) until"
  echo "  its container healthcheck passes — only the Collector runs Flyway — then"
  echo "  start the Provider against the migrated schema. Idempotent."
  echo "  --defaults  accepted no-op (this step has no prompts)."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

# Standalone-run guard: every compose call below passes --env-file "$SECRETS_FILE",
# which errors opaquely on a missing file; fail with a pointer to the steps that
# create it (mirrors 3-postgres.sh).
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found; run the earlier wizard steps first (secrets.env is created in step 2, 2-secrets.sh)." >&2
  exit 1
fi

# --env-file feeds secrets.env to compose's dotenv parser (M1-389) so the apps'
# INFOCHAT_*_PASSWORD / *_API_KEY / *_ADMIN_CONTACT_ID interpolations and the
# Provider environment passthroughs resolve; the orchestrator no longer sources
# secrets into the environment.

# Build both images explicitly before the readiness gate below. On first run
# this is a cold multi-module Maven reactor build (the Dockerfiles run
# `mvn -am clean install` from the maven:3.9-eclipse-temurin-25 image,
# downloading the full dependency tree) that can run well over five minutes;
# building implicitly underneath `up --wait` would conflate a build failure with
# a health-check timeout. A separate `build` phase makes a build error
# attributable and keeps the readiness wait meaningful (M1-392). Idempotent:
# unchanged sources rebuild from the layer cache.
echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod build infochat-collector infochat-provider"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod build infochat-collector infochat-provider
echo "images: built."

# Provision the SimpleX bot identity (profile + address + auto-accept) AFTER the
# Provider image is built and BEFORE any app container starts: it runs the baked
# simplex-chat against the mounted data-dir, which only exists once the image is
# built, and the Provider would fail to start on a missing identity if it came up
# first. 6b self-gates — it is a no-op when simplex is not an enabled adapter —
# and aborts the wizard (exit non-zero) on a provisioning failure, so a failed
# provision stops here and never starts the apps.
echo "+ $SCRIPT_DIR/6b-simplex-provision.sh"
"$SCRIPT_DIR/6b-simplex-provision.sh"

echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod up -d --wait --wait-timeout $WAIT_TIMEOUT infochat-collector"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod up -d --wait --wait-timeout "$WAIT_TIMEOUT" infochat-collector
echo "collector: healthy (migrations applied)."

echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod up -d infochat-provider"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod up -d infochat-provider
echo "provider: started."
