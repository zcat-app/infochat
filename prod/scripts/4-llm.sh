#!/bin/bash
# prod/scripts/4-llm.sh — wizard step 4: provision the operator-chosen LLM
# backend and write the runtime LLM/embeddings config (§7.7.2 step 4).
#
# Branches on the backend choice (ollama | llamacpp | remote):
#   ollama   — start the ollama compose service and `ollama pull` the active
#              profile's security / chat / embedding models (§5.7), then point
#              every infochat.llm.* + infochat.embeddings.* base-url at the
#              ollama service over the compose network.
#   llamacpp — start the llama.cpp compose service, fetch the operator-supplied
#              GGUF into the model volume, and point the OpenAI-compatible
#              base-url at llamacpp:8080 (§7.4).
#   remote   — collect a remote OpenAI-compatible base-url + API key (the key
#              minted into secrets.env, §7.3); no local model pull.
# Model names follow the active profile recorded by 1-profile.sh, never a
# hard-coded profile (§5.7 is the canonical table). Re-running is idempotent:
# `ollama pull` skips an already-present model and each property line is
# replaced, not duplicated.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"

DEFAULT_BACKEND="ollama"
VALID_BACKENDS="ollama llamacpp remote"
# llama.cpp + Ollama both speak the OpenAI-compatible API the LLM adapter uses
# (§7.4); /v1 is that API's base path. Over the compose network the apps reach
# the model servers by service name, not localhost (the baked config's
# localhost:11434 is the host-dev value, wrong inside a container).
OLLAMA_URL="http://ollama:11434/v1"
LLAMACPP_URL="http://llamacpp:8080/v1"
WAIT_TIMEOUT=120
# One-shot image used to populate the llama.cpp model volume; pinned per the
# M1-004 tag-pinning precedent. Writing into a Docker named volume requires a
# container that mounts it, so the GGUF download runs in this throwaway.
CURL_IMAGE="curlimages/curl:8.11.1"

# The six per-task LLM config families share one endpoint; only the model
# differs (security vs chat vs embedding) per §5.7. embeddings is handled
# alongside but lives under its own infochat.embeddings.* prefix.
LLM_TASKS="security tagger entity summarizer chat translator"

usage() {
  echo "Usage: 4-llm.sh [--defaults] [-h|--help]"
  echo "  Provision the chosen LLM backend (${VALID_BACKENDS// /|}, default"
  echo "  ${DEFAULT_BACKEND}) and write infochat.llm.* + infochat.embeddings.* into"
  echo "  the runtime application.properties."
  echo "  --defaults  take ${DEFAULT_BACKEND} for the active profile, no prompts."
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

# Point every LLM task + embeddings at one endpoint base-url.
set_all_base_urls() {
  local url="$1" task
  for task in $LLM_TASKS; do
    set_prop "infochat.llm.${task}.base-url" "$url"
  done
  set_prop infochat.embeddings.base-url "$url"
}

umask 077
mkdir -p "$RUNTIME_DIR"

# Read the profile 1-profile.sh recorded; the LLM model defaults are profile-driven.
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "FAIL: $CONFIG_FILE not found; run 1-profile.sh (wizard step 1) first." >&2
  exit 1
fi
profile="$(sed -n 's/^quarkus\.profile=//p' "$CONFIG_FILE" | tail -n1)"
if [[ -z "$profile" ]]; then
  echo "FAIL: quarkus.profile not set in $CONFIG_FILE; run 1-profile.sh first." >&2
  exit 1
fi

# §5.7 canonical per-profile model table. tagger/entity/summarizer/chat/
# translator share the "chat" model in every profile; security and embeddings
# differ. remote-llm has no local models (provider-side) — it pairs with the
# remote backend, not ollama/llamacpp.
case "$profile" in
  laptop) security_model="llama3.2:3b"; chat_model="llama3.1:8b"; embedding_model="nomic-embed-text" ;;
  vps)    security_model="llama3.2:3b"; chat_model="llama3.2:3b"; embedding_model="nomic-embed-text" ;;
  pi)     security_model="llama3.2:1b"; chat_model="llama3.2:1b"; embedding_model="all-minilm:33m" ;;
  remote-llm) security_model=""; chat_model=""; embedding_model="" ;;
  *) echo "FAIL: unknown profile '$profile' in $CONFIG_FILE" >&2; exit 1 ;;
esac

backend="$DEFAULT_BACKEND"
if [[ "$defaults" -eq 0 ]]; then
  read -rp "LLM backend (${VALID_BACKENDS// /|}) [${DEFAULT_BACKEND}]: " answer
  backend="${answer:-$DEFAULT_BACKEND}"
fi
# Validate the operator's free-text answer against the closed set — a
# system-boundary check on interactive input.
case " $VALID_BACKENDS " in
  *" $backend "*) ;;
  *) echo "FAIL: unknown backend '$backend' (expected: $VALID_BACKENDS)" >&2; exit 1 ;;
esac

case "$backend" in
  ollama)
    # remote-llm carries no local model names (§5.7), so a local backend is a
    # mismatched choice — fail clearly rather than `ollama pull ""`.
    if [[ -z "$chat_model" ]]; then
      echo "FAIL: profile '$profile' has no local models; choose the 'remote' backend." >&2
      exit 1
    fi
    # --env-file feeds secrets.env to compose's dotenv parser (M1-389) — the
    # orchestrator no longer sources it into the environment; all compose calls
    # below carry it so the full file's ${INFOCHAT_*} interpolations resolve.
    echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile ollama up -d ollama"
    docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama up -d ollama
    # Ollama declares no compose healthcheck, so poll its API until the daemon
    # answers before issuing pulls (exec needs the container actually serving).
    echo "+ wait for ollama daemon (up to ${WAIT_TIMEOUT}s)"
    deadline=$(( SECONDS + WAIT_TIMEOUT ))
    until docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama exec -T ollama ollama list >/dev/null 2>&1; do
      if (( SECONDS >= deadline )); then
        echo "FAIL: ollama daemon not ready after ${WAIT_TIMEOUT}s." >&2
        exit 1
      fi
      sleep 2
    done
    # `ollama pull` is idempotent (skips an already-present model). Dedup the
    # three names so a profile that reuses one (e.g. vps security==chat) pulls
    # it once.
    for model in $(printf '%s\n%s\n%s\n' "$security_model" "$chat_model" "$embedding_model" | sort -u); do
      echo "+ ollama pull $model"
      docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama exec -T ollama ollama pull "$model"
    done
    set_all_base_urls "$OLLAMA_URL"
    set_prop infochat.llm.security.model "$security_model"
    for task in tagger entity summarizer chat translator; do
      set_prop "infochat.llm.${task}.model" "$chat_model"
    done
    set_prop infochat.embeddings.model "$embedding_model"
    echo "ollama backend ready: models pulled, endpoint $OLLAMA_URL"
    ;;
  llamacpp)
    if [[ "$defaults" -eq 1 ]]; then
      echo "FAIL: --defaults cannot pick a GGUF for the llamacpp backend; run interactively." >&2
      exit 1
    fi
    read -rp "GGUF model URL to download into the llama.cpp model volume: " gguf_url
    if [[ -z "$gguf_url" ]]; then
      echo "FAIL: a GGUF URL is required for the llamacpp backend." >&2
      exit 1
    fi
    gguf_file="$(basename "$gguf_url")"
    echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile llamacpp up -d llamacpp"
    docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile llamacpp up -d llamacpp
    # Populate the named model volume via a one-shot container that mounts it.
    # The presence probe runs `ls` as the entrypoint (argv, no shell) so the
    # operator-supplied filename cannot inject; skip the download when present.
    if docker run --rm -v infochat-llamacpp-models:/models --entrypoint ls "$CURL_IMAGE" "/models/$gguf_file" >/dev/null 2>&1; then
      echo "skip GGUF download ($gguf_file already present)"
    else
      echo "+ download $gguf_url -> volume infochat-llamacpp-models/$gguf_file"
      docker run --rm -v infochat-llamacpp-models:/models "$CURL_IMAGE" -fL -o "/models/$gguf_file" "$gguf_url"
    fi
    set_all_base_urls "$LLAMACPP_URL"
    # llama.cpp serves one GGUF; record its filename as the model id for every
    # task so the adapter's model field is populated (§7.4).
    for task in $LLM_TASKS; do
      set_prop "infochat.llm.${task}.model" "$gguf_file"
    done
    set_prop infochat.embeddings.model "$gguf_file"
    echo "llamacpp backend ready: $gguf_file fetched, endpoint $LLAMACPP_URL"
    ;;
  remote)
    if [[ "$defaults" -eq 1 ]]; then
      echo "FAIL: --defaults cannot configure the remote backend; run interactively." >&2
      exit 1
    fi
    read -rp "Remote OpenAI-compatible base-url (e.g. https://nano-gpt.com/api/v1): " base_url
    if [[ -z "$base_url" ]]; then
      echo "FAIL: a base-url is required for the remote backend." >&2
      exit 1
    fi
    # The API key is a secret, so it lives in secrets.env (§7.3 — secrets never
    # enter application.properties), reusing any value 2-secrets.sh already
    # minted. application.properties references it by env var for Quarkus to
    # expand at boot.
    if grep -qE '^INFOCHAT_LLM_API_KEY=.+' "$SECRETS_FILE" 2>/dev/null; then
      echo "using INFOCHAT_LLM_API_KEY from secrets.env (set at step 2)"
    else
      read -rsp "Remote LLM API key: " llm_key
      echo
      if [[ -n "$llm_key" ]]; then
        touch "$SECRETS_FILE"
        chmod 600 "$SECRETS_FILE"
        # Quote the value for compose's --env-file dotenv parse (M1-389): a '#'
        # or whitespace in the key is data, not a comment / field break.
        printf 'INFOCHAT_LLM_API_KEY="%s"\n' "$llm_key" >> "$SECRETS_FILE"
        echo "+ recorded INFOCHAT_LLM_API_KEY in secrets.env"
      fi
    fi
    set_all_base_urls "$base_url"
    for task in $LLM_TASKS; do
      set_prop "infochat.llm.${task}.api-key" '${INFOCHAT_LLM_API_KEY}'
    done
    set_prop infochat.embeddings.api-key '${INFOCHAT_LLM_API_KEY}'
    echo "remote backend ready: endpoint $base_url"
    ;;
esac
