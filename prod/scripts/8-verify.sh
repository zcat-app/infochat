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
#
# Embedding-backend probe: an absent embedding backend is a SUPPORTED degraded mode; this
# leg SURFACES, never fails (WARN line, exit unchanged). Direct probe = liveness; scan = build outcome.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
POLL_TIMEOUT=120
POLL_INTERVAL=5

# DUPLICATED from restore.sh / 4-llm.sh (restore.sh's pinned-constant note documents
# why these scripts duplicate rather than source — keep in sync). Per D54 these two
# local shapes are the only legitimate infochat.embeddings.base-url values.
OLLAMA_EMBED_URL="http://ollama:11434/v1"
LLAMACPP_EMBED_URL="http://llamacpp-embeddings:8080/v1"

# Last-wins read of a property from the runtime application.properties —
# verbatim shape of restore.sh's read_prop (same can't-source rationale).
read_prop() {
  local key="$1" escaped
  escaped="${key//./\\.}"
  sed -n "s/^${escaped}=//p" "$CONFIG_FILE" | tail -n1
}

usage() {
  echo "Usage: 8-verify.sh [--defaults] [-h|--help]"
  echo "  Poll /q/health on the Collector (8080) and Provider (8081) loopback"
  echo "  binds via docker compose exec until each reports UP or ${POLL_TIMEOUT}s"
  echo "  elapses, probe the embedding backend, then print a green/red summary."
  echo "  Exits non-zero on timeout; a degraded embedding backend is a WARN,"
  echo "  never a failure (supported degraded mode)."
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
warn_count=0
summary=()
last_health_body=""

check() {
  local label="$1" service="$2" port="$3" body
  echo "+ docker compose exec $service curl 127.0.0.1:${port}/q/health (poll up to ${POLL_TIMEOUT}s)"
  if body=$(poll_health "$service" "$port"); then
    last_health_body="$body"
    if printf '%s' "$body" | grep -q '"status": *"DOWN"'; then
      summary+=("DEGRADED  $label ($service:$port) — overall UP, some sub-checks DOWN:")
      summary+=("          $body")
    else
      summary+=("GREEN     $label ($service:$port) — UP")
    fi
  else
    last_health_body=""
    summary+=("RED       $label ($service:$port) — not UP after ${POLL_TIMEOUT}s")
    exit_code=1
  fi
}

check "Collector" infochat-collector 8080
check "Provider"  infochat-provider  8081
# The Provider check ran last, so this is its captured /q/health body — the
# embedding scan below reuses it rather than re-probing.
provider_health_body="$last_health_body"

# Embedding-backend probe — WARN-only: a dead embedder is a supported degraded
# mode, never a wizard failure (M1-818 P6) — restore.sh propagates a non-zero verify exit as a
# cutover blocker, which would wrongly block a legitimate degraded clone.
embedding_probe_note() {
  summary+=("WARN      $1")
  warn_count=$((warn_count + 1))
}

if [[ ! -f "$CONFIG_FILE" ]]; then
  embedding_probe_note "embedding probe skipped — $CONFIG_FILE not found; cannot classify the embedding backend."
else
  embed_url="$(read_prop 'infochat.embeddings.base-url')"
  embed_model="$(read_prop 'infochat.embeddings.model')"
  case "$embed_url" in
    "$OLLAMA_EMBED_URL")
      echo "+ docker compose exec ollama ollama list (embedding-backend probe)"
      if [[ -z "$embed_model" ]]; then
        embedding_probe_note "embedding probe skipped — infochat.embeddings.model is unset in $CONFIG_FILE."
      elif model_list="$(docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama exec -T ollama ollama list 2>/dev/null)" \
          && printf '%s' "$model_list" | grep -qF "$embed_model"; then
        summary+=("GREEN     embedding backend (ollama) — serving model $embed_model")
      else
        embedding_probe_note "embedding backend (ollama) is absent or not serving model $embed_model — free-text help matching and topic answers are degraded (safe degraded mode; commands and every security control keep working). Recovery: SETUP_GUIDE.md row 'Bot runs, but free-text help is dead'."
      fi
      ;;
    "$LLAMACPP_EMBED_URL")
      # Probed from the Provider container's own network view — the app's
      # DNS/endpoint truth, and the container already carries curl.
      echo "+ docker compose exec infochat-provider curl llamacpp-embeddings:8080/v1/models (embedding-backend probe)"
      if docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod exec -T infochat-provider \
          curl -fsS "http://llamacpp-embeddings:8080/v1/models" >/dev/null 2>&1; then
        summary+=("GREEN     embedding backend (llama.cpp) — /v1/models answers")
      else
        embedding_probe_note "embedding backend (llama.cpp) is absent or not serving — free-text help matching and topic answers are degraded (safe degraded mode; commands and every security control keep working). Recovery: SETUP_GUIDE.md row 'Bot runs, but free-text help is dead'."
      fi
      ;;
    "")
      embedding_probe_note "embedding probe skipped — infochat.embeddings.base-url is unset in $CONFIG_FILE."
      ;;
    *)
      embedding_probe_note "embedding probe skipped — unrecognized infochat.embeddings.base-url '$embed_url' (expected $OLLAMA_EMBED_URL or $LLAMACPP_EMBED_URL)."
      ;;
  esac

  # Boot-outcome leg: catches the backend-up-but-boot-failed case the direct
  # probe cannot. Scans the Provider's captured /q/health body for degraded
  # help-corpora entries (a failed corpus build reports false).
  if [[ -n "$provider_health_body" ]] \
      && printf '%s' "$provider_health_body" | grep -Eq '"(command_intent|topic)": *false'; then
    embedding_probe_note "Provider readiness reports a failed help-corpus build — free-text help matching and/or topic answers are degraded (safe degraded mode). Recovery: SETUP_GUIDE.md row 'Bot runs, but free-text help is dead'."
  fi
fi

echo
echo "=== deployment health summary ==="
for line in "${summary[@]}"; do
  echo "$line"
done

if [[ "$exit_code" -eq 0 && "$warn_count" -eq 0 ]]; then
  echo "all components healthy."
elif [[ "$exit_code" -eq 0 ]]; then
  echo "all services UP, but $warn_count warning(s) — see above."
else
  echo "one or more components are not healthy (see above)." >&2
fi
exit "$exit_code"
