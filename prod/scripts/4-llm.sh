#!/bin/bash
# prod/scripts/4-llm.sh — wizard step 4: provision the operator-chosen LLM
# backend and write the runtime LLM/embeddings config (§7.7.2 step 4).
#
# Branches on the backend choice (ollama | llamacpp | remote):
#   ollama   — start the ollama compose service and `ollama pull` the active
#              profile's security / chat / embedding models (§5.7), then point
#              the shared infochat.llm.default.base-url (inherited by every
#              task, D56) + infochat.embeddings.base-url at the ollama service
#              over the compose network.
#   llamacpp — fetch the chosen generative GGUF into the model volume and serve
#              it from the llama.cpp instance at llamacpp:8080 (§7.4); embeddings
#              run on a SEPARATE backend (a second llama.cpp instance or the
#              co-running Ollama nomic embedder), never the generative GGUF (D49).
#   remote   — route the seven generative tasks (via the shared default keys)
#              to a remote OpenAI-compatible endpoint (base-url + API key minted
#              into secrets.env, §7.3); embeddings still run LOCALLY — a
#              co-started Ollama nomic embedder is pulled, never the remote
#              endpoint (D54).
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
# The pure-llama.cpp embeddings shape serves the nomic embedder from a SECOND
# llama.cpp instance (D49); the apps reach it by its compose service name.
LLAMACPP_EMBED_URL="http://llamacpp-embeddings:8080/v1"
WAIT_TIMEOUT=120
# One-shot image used to populate the llama.cpp model volume; pinned per the
# M1-004 tag-pinning precedent. Writing into a Docker named volume requires a
# container that mounts it, so the GGUF download runs in this throwaway.
CURL_IMAGE="curlimages/curl:8.11.1"

# Curated, checksum-pinned GGUFs the llamacpp branch defaults to (M1-417). Enter
# accepts each with its SHA-256 ENFORCED (not skippable); a custom URL overrides
# and falls back to the optional-SHA prompt (operator-trusted TLS fetch, M1-394).
# Generative is gemma QAT-Q4 (quant-aware-trained, so Q4 size keeps near-BF16
# quality); embeddings is the 768-dim nomic-embed-text-v1.5 (same family as the
# Ollama nomic the fleet uses, so vectors are cross-deployment compatible).
LLAMACPP_GEN_GGUF_URL="https://huggingface.co/unsloth/gemma-4-E4B-it-qat-GGUF/resolve/main/gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf"
LLAMACPP_GEN_GGUF_FILE="gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf"
LLAMACPP_GEN_GGUF_SHA="b3052f962d6449b4eb2075733c068bdec1c51eadb7b237e6c3157bfbb7b1dae0"
LLAMACPP_EMB_GGUF_URL="https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.f16.gguf"
LLAMACPP_EMB_GGUF_FILE="nomic-embed-text-v1.5.f16.gguf"
LLAMACPP_EMB_GGUF_SHA="f7af6f66802f4df86eda10fe9bbcfc75c39562bed48ef6ace719a251cf1c2fdb"
# The Ollama-embeddings shape points embeddings at the nomic model on the
# co-running ollama service — always nomic-class + 768-dim, independent of the
# profile's generative model table (§5.7).
NOMIC_OLLAMA_MODEL="nomic-embed-text"
EMBEDDINGS_DIMENSION=768

# The seven LLM tasks share one endpoint — since M1-603 (D56) written ONCE as
# infochat.llm.default.{base-url,api-key}, which every task inherits; only the
# model differs (security vs chat vs embedding) per §5.7, so LLM_TASKS drives
# the per-task model writes and the old-format-line sweeps below. embeddings
# is handled alongside but lives under its own infochat.embeddings.* prefix.
LLM_TASKS="security tagger entity classifier summarizer chat translator"

usage() {
  echo "Usage: 4-llm.sh [--defaults] [-h|--help]"
  echo "  Provision the chosen LLM backend (${VALID_BACKENDS// /|}, default"
  echo "  ${DEFAULT_BACKEND}) and write infochat.llm.* + infochat.embeddings.* into"
  echo "  the runtime application.properties."
  echo "  Post-setup LLM re-routes (no re-provisioning) use prod/switch-llm.sh."
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

# Prompt for one per-task timing key (skipped under --defaults, which takes the
# recommendation unchanged) and validate the answer as a positive integer — a
# system-boundary check on interactive input, same posture as the backend
# validation. The validated value lands in the variable named by $3.
prompt_timing() {
  local key="$1" default="$2" __var="$3" answer=""
  if [[ "$defaults" -eq 0 ]]; then
    read -rp "${key} [${default}]: " answer
  fi
  answer="${answer:-$default}"
  if ! [[ "$answer" =~ ^[1-9][0-9]*$ ]]; then
    echo "FAIL: ${key} must be a positive integer (got '${answer}')." >&2
    exit 1
  fi
  printf -v "$__var" '%s' "$answer"
}

# Migrate an old-format runtime file (per-task base-url/api-key fan-out,
# pre-M1-603) whenever a run rewrites the routing: a stale per-task line
# would WIN over the shared default keys (per-task beats default, D56), so
# leaving it in place silently pins that task to the OLD endpoint and turns
# the backend switch into a no-op for it. Idempotent; never touches the
# default keys themselves (the task-name loop cannot match "default").
sweep_per_task_routes() {
  local task
  for task in $LLM_TASKS; do
    sed -i -e "/^infochat\.llm\.${task}\.base-url=/d" \
           -e "/^infochat\.llm\.${task}\.api-key=/d" "$CONFIG_FILE"
  done
}

# Point every LLM task (via the shared default, D56) + embeddings at one
# endpoint base-url.
set_all_base_urls() {
  local url="$1"
  sweep_per_task_routes
  set_prop infochat.llm.default.base-url "$url"
  set_prop infochat.embeddings.base-url "$url"
}

# Escape an arbitrary value for a double-quoted secrets.env field so compose's
# --env-file dotenv parser reads it back byte-for-byte (M1-397). Order matters:
# backslash first (so the escapes we add next are not themselves re-escaped),
# then the '"' that would prematurely close the field and the '$' that some
# compose versions would treat as ${...} interpolation.
dotenv_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//\$/\\\$}"
  printf '%s' "$value"
}

# Idempotent secrets.env write (M1-417): drop any existing line for KEY, then
# append the value double-quoted + dotenv-escaped so compose's --env-file parser
# reads it back byte-for-byte (M1-389/M1-397). The operator-chosen GGUF filenames
# flow to compose through this channel exactly like the adapter data-dirs (M1-391);
# compose builds the in-container LLAMA_ARG_MODEL path from them.
set_secret() {
  local key="$1" value="$2"
  touch "$SECRETS_FILE"
  chmod 600 "$SECRETS_FILE"
  sed -i "/^${key}=/d" "$SECRETS_FILE"
  printf '%s="%s"\n' "$key" "$(dotenv_escape "$value")" >> "$SECRETS_FILE"
}

# Remove the remote-backend credentials + provider a prior `remote` run wrote,
# for a re-run that switches AWAY to a local backend (M1-530): the seven
# infochat.llm.<task>.api-key lines + any infochat.embeddings.api-key from
# application.properties (a local backend carries no key), the
# infochat.llm.default.provider line (M1-614 — a local backend is
# openai-compatible via the default), and INFOCHAT_LLM_API_KEY from secrets.env
# (referenced by nothing once remote is de-selected). Mirrors the
# adapter-admin de-selection reconcile in 6-adapter.sh and the embeddings-api-key
# clear M1-529 added inside the remote branch. Idempotent — a no-op on a fresh run
# with no prior remote credentials. Both callers (ollama/llamacpp) reach this only
# after secrets.env is guaranteed to exist (the ollama-branch guard / the llamacpp
# set_secret); the remote branch legitimately writes these keys and must NOT call it.
clear_remote_llm_creds() {
  sed -i -e '/^infochat\.llm\..*\.api-key=/d' -e '/^infochat\.embeddings\.api-key=/d' "$CONFIG_FILE"
  # A prior `remote` run may have written infochat.llm.default.provider (deepseek
  # or openai-compatible, M1-614). A local ollama/llamacpp backend is
  # openai-compatible via the default, so a lingering provider=deepseek would
  # route a localhost endpoint through the DeepSeek thinking-toggle path — drop
  # it so the local backend falls back to the openai-compatible default cleanly.
  sed -i '/^infochat\.llm\.default\.provider=/d' "$CONFIG_FILE"
  sed -i '/^INFOCHAT_LLM_API_KEY=/d' "$SECRETS_FILE"
}

# Point only the seven LLM tasks (via the shared default, not embeddings) at
# one endpoint. The llamacpp branch wires embeddings to a SEPARATE backend (a
# second llama.cpp instance or Ollama), so unlike set_all_base_urls it leaves
# infochat.embeddings.base-url for the caller to set (M1-417).
set_llm_base_urls() {
  local url="$1"
  sweep_per_task_routes
  set_prop infochat.llm.default.base-url "$url"
}

# Derive a clean GGUF filename from a URL: strip #fragment then ?query (a signed
# URL like model.gguf?token=x must not carry the token into the model id), then
# basename. In a well-formed URL the fragment is last (path?query#fragment).
gguf_basename() {
  local url="$1" path
  path="${url%%#*}"
  path="${path%%\?*}"
  basename "$path"
}

# Fetch a GGUF into the llama.cpp model volume and verify its SHA-256. Presence
# probe, download, digest, and removal all run in no-shell argv-only containers so
# the operator-supplied filename/URL cannot inject (M1-394). A non-empty expected
# checksum is enforced — a mismatch removes the file and fails the wizard; an
# empty expected (a custom override that skipped the optional prompt) downloads
# without verification. $1 url, $2 filename, $3 expected-sha256 ("" to skip).
fetch_gguf() {
  local url="$1" file="$2" expected="$3" actual
  if docker run --rm -v infochat-llamacpp-models:/models --entrypoint ls "$CURL_IMAGE" "/models/$file" >/dev/null 2>&1; then
    echo "skip GGUF download ($file already present)"
  else
    echo "+ download $url -> volume infochat-llamacpp-models/$file"
    # -u 0:0: curlimages/curl runs as a non-root user, but a freshly-created
    # named volume's root dir is owned by root — a non-root write to /models
    # is denied. Run the write (and the mismatch-rm below) as root; curl -o
    # leaves the GGUF world-readable, so the non-root llama.cpp server still
    # reads it. The presence/checksum probes stay non-root (reads only). The
    # download runs in the host netns with name-only proxy-env forwarding:
    # reachability is proven on the host path, so the fetch uses it.
    if ! docker run --rm -u 0:0 --network host -e HTTP_PROXY -e HTTPS_PROXY -e ALL_PROXY -e NO_PROXY -v infochat-llamacpp-models:/models "$CURL_IMAGE" -fL -o "/models/$file" "$url"; then
      echo "FAIL: download of $url failed over the host's own network path (the path the preflight checked)." >&2
      echo "      Check host connectivity: VPN, proxy, or firewall. If you use a proxy, export" >&2
      echo "      HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY (the download uses them) and re-run." >&2
      exit 1
    fi
  fi
  if [[ -n "$expected" ]]; then
    actual="$(docker run --rm -v infochat-llamacpp-models:/models --entrypoint sha256sum "$CURL_IMAGE" "/models/$file" | awk '{print $1}')"
    expected="$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')"
    if [[ "$actual" != "$expected" ]]; then
      echo "FAIL: GGUF checksum mismatch (expected $expected, got $actual); removing $file." >&2
      docker run --rm -u 0:0 -v infochat-llamacpp-models:/models --entrypoint rm "$CURL_IMAGE" -f "/models/$file"
      exit 1
    fi
    echo "GGUF checksum verified ($expected)"
  fi
}

# Preflight one GGUF URL with a host HEAD before any download: the fetch runs
# on the host's own network path (M1-808), so the host probe is same-path.
# Network-class exits abort with guidance; a malformed URL (3) hard-fails; an
# HTTP refusal (22) only warns (P10).
preflight_gguf_url() {
  local url="$1" rc
  echo "+ HEAD $url"
  if curl -fsSLI -o /dev/null --max-time 60 "$url"; then
    return 0
  else
    rc=$?
    if [[ "$rc" == 6 || "$rc" == 7 || "$rc" == 28 ]]; then
      echo "FAIL: cannot reach $url over the host's own network path (the path the download uses)." >&2
      echo "      Check host connectivity: VPN, proxy, or firewall. If you use a proxy, export" >&2
      echo "      HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY (the download uses them) and re-run." >&2
      exit 1
    fi
    if [[ "$rc" == 3 ]]; then
      echo "FAIL: $url is a malformed URL — the probe never reached the network (curl exit 3)." >&2
      echo "      This looks like a file path was pasted instead of a full download URL." >&2
      echo "      Paste the full https:// download URL, or press Enter for the pinned default." >&2
      exit 1
    fi
    echo "WARN: $url answered but refused the HEAD probe (curl exit $rc) — reachability confirmed; continuing." >&2
  fi
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

# §5.7 canonical per-profile model table. tagger/entity/classifier/summarizer/
# chat/translator share the "chat" model in every profile; security and embeddings
# differ. remote-llm has no local models (provider-side) — it pairs with the
# remote backend, not ollama/llamacpp.
case "$profile" in
  laptop) security_model="llama3.2:3b"; chat_model="llama3.1:8b"; embedding_model="nomic-embed-text" ;;
  vps)    security_model="llama3.2:3b"; chat_model="llama3.2:3b"; embedding_model="nomic-embed-text" ;;
  pi)     security_model="llama3.2:1b"; chat_model="llama3.2:1b"; embedding_model="nomic-embed-text" ;;
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
    # Standalone-run guard: this branch's FIRST action is a compose call with
    # --env-file "$SECRETS_FILE" and no prior set_secret (unlike the llamacpp/remote
    # branches, which create secrets.env themselves), so a missing file errors
    # opaquely. Fail with a pointer to the step that creates it (mirrors
    # 3-postgres.sh). Branch-local on purpose — a top-level guard would wrongly
    # reject the llamacpp/remote branches that mint secrets.env on the fly.
    if [[ ! -f "$SECRETS_FILE" ]]; then
      echo "FAIL: $SECRETS_FILE not found; run 2-secrets.sh (wizard step 2) first." >&2
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
    # Switch-away-from-remote reconcile (M1-530): drop any remote api-key config a
    # prior `remote` run left — a local ollama backend carries no key.
    clear_remote_llm_creds
    set_all_base_urls "$OLLAMA_URL"
    set_prop infochat.llm.security.model "$security_model"
    for task in tagger entity classifier summarizer chat translator; do
      set_prop "infochat.llm.${task}.model" "$chat_model"
    done
    set_prop infochat.embeddings.model "$embedding_model"
    echo "ollama backend ready: models pulled, endpoint $OLLAMA_URL"
    ;;
  llamacpp)
    # llama.cpp serves ONE model per instance, so the generative model and the
    # fixed 768-dim nomic embedder need separate servers (D49). This branch wires
    # the generative GGUF and offers two embeddings shapes: a second llama.cpp
    # instance (pure-llama.cpp) or the Ollama nomic embedder running alongside.
    if [[ "$defaults" -eq 1 ]]; then
      echo "FAIL: --defaults cannot drive the interactive llamacpp wizard (embeddings-backend choice + GGUF overrides); run interactively." >&2
      exit 1
    fi
    # llamacpp is a LOCAL backend, so — like the ollama branch's guard above — it is
    # a mismatched choice on the remote-llm profile, which carries no local models
    # (§5.7) and pairs with the remote backend. Reject it symmetrically so the
    # documented invariant ("remote-llm profile must pick remote") is enforced on
    # both local backends, not just ollama.
    if [[ -z "$chat_model" ]]; then
      echo "FAIL: profile '$profile' has no local models; choose the 'remote' backend." >&2
      exit 1
    fi

    # --- Generative GGUF: pinned default (Enter) or custom override URL. ---
    # Decision-time twin of the SETUP_GUIDE.md step-4 note (F-live-8): the
    # compose service pins LLAMA_ARG_REASONING=off, so a reasoning-tuned GGUF
    # runs but never thinks — and the timeout/token recommendations assume that.
    echo "Note: thinking/reasoning is disabled on the llama.cpp server — a reasoning-tuned model will run but will not 'think', and the timeout/token recommendations below assume that."
    read -rp "Generative GGUF — paste a full download URL, or press Enter for the pinned default ($LLAMACPP_GEN_GGUF_FILE): " gen_override
    if [[ -n "$gen_override" ]]; then
      gen_url="$gen_override"
      gen_file="$(gguf_basename "$gen_override")"
      # Custom override keeps the optional-SHA prompt (operator-trusted TLS fetch).
      read -rp "Generative GGUF SHA-256 (blank to skip integrity check): " gen_sha
    else
      gen_url="$LLAMACPP_GEN_GGUF_URL"
      gen_file="$LLAMACPP_GEN_GGUF_FILE"
      gen_sha="$LLAMACPP_GEN_GGUF_SHA"   # pinned: enforced, not skippable
      echo "using pinned generative GGUF: $gen_file"
    fi

    # --- Embeddings backend: a second llama.cpp instance or co-running Ollama. ---
    read -rp "Embeddings backend (llamacpp|ollama) [llamacpp]: " emb_backend
    emb_backend="${emb_backend:-llamacpp}"
    case " llamacpp ollama " in
      *" $emb_backend "*) ;;
      *) echo "FAIL: unknown embeddings backend '$emb_backend' (expected: llamacpp|ollama)" >&2; exit 1 ;;
    esac

    if [[ "$emb_backend" == "llamacpp" ]]; then
      # Pinned nomic default, or a custom override that MUST stay 768-dim. The
      # wizard cannot read a GGUF's true dimension without loading it, so a custom
      # override is gated on an explicit operator confirmation — the real backstop
      # is EmbeddingMetadataStartupGuard, which refuses Collector startup on a
      # (model,dimension) mismatch (allow-model-change=false). Acceptance item 7.
      read -rp "Embeddings GGUF — paste a full download URL, or press Enter for the pinned default ($LLAMACPP_EMB_GGUF_FILE, 768-dim): " emb_override
      if [[ -n "$emb_override" ]]; then
        echo "WARNING: a custom embeddings model MUST produce ${EMBEDDINGS_DIMENSION}-dimensional vectors" >&2
        echo "         (infochat.embeddings.dimension=$EMBEDDINGS_DIMENSION, allow-model-change=false)." >&2
        echo "         A different dimension is rejected at Collector startup and cannot" >&2
        echo "         be silently accepted." >&2
        read -rp "Type 'yes' to confirm the override embedder is ${EMBEDDINGS_DIMENSION}-dim: " emb_confirm
        if [[ "$emb_confirm" != "yes" ]]; then
          echo "FAIL: embeddings override not confirmed ${EMBEDDINGS_DIMENSION}-dim; aborting." >&2
          exit 1
        fi
        emb_url="$emb_override"
        emb_file="$(gguf_basename "$emb_override")"
        read -rp "Embeddings GGUF SHA-256 (blank to skip integrity check): " emb_sha
      else
        emb_url="$LLAMACPP_EMB_GGUF_URL"
        emb_file="$LLAMACPP_EMB_GGUF_FILE"
        emb_sha="$LLAMACPP_EMB_GGUF_SHA"   # pinned: enforced, not skippable
        echo "using pinned embeddings GGUF: $emb_file"
      fi
    fi

    # Same-path preflight BEFORE the first fetch: the download runs on the
    # host's own network path (M1-808), so the host probe is the check the
    # download will fail. No new prompts — drives feed positional stdin.
    preflight_gguf_url "$gen_url"
    if [[ "$emb_backend" == "llamacpp" ]]; then
      preflight_gguf_url "$emb_url"
    fi

    # --- Provision. Download GGUFs + mint the secrets.env filenames BEFORE
    # compose up, so the --env-file interpolation of INFOCHAT_LLAMACPP_*_GGUF
    # (the LLAMA_ARG_MODEL paths) resolves at container start (M1-389). ---
    fetch_gguf "$gen_url" "$gen_file" "$gen_sha"
    set_secret INFOCHAT_LLAMACPP_GGUF "$gen_file"
    # Persist the URL + SHA too (not just the filename) so restore.sh can re-fetch a
    # CUSTOM generative GGUF on a fresh host: the model volume is not in the pack.sh
    # bundle, and a custom model has no pinned constant to recover from (M1-571). For
    # the pinned default these mirror restore.sh's own constants (a harmless duplicate
    # its pinned recovery path ignores); for a custom override they are the ONLY
    # recovery source. $gen_sha may be empty (operator skipped the custom SHA prompt).
    set_secret INFOCHAT_LLAMACPP_GGUF_URL "$gen_url"
    set_secret INFOCHAT_LLAMACPP_GGUF_SHA "$gen_sha"
    echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile llamacpp up -d llamacpp"
    docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile llamacpp up -d llamacpp

    if [[ "$emb_backend" == "llamacpp" ]]; then
      fetch_gguf "$emb_url" "$emb_file" "$emb_sha"
      set_secret INFOCHAT_LLAMACPP_EMBED_GGUF "$emb_file"
      # Persist the embedder's URL + SHA too (M1-571), same rationale as the generative
      # GGUF above — so a CUSTOM llama.cpp embedder is recoverable on restore. Written
      # ONLY in this llamacpp-embeddings branch; the ollama-embeddings shape serves nomic
      # from Ollama and has no llama.cpp embed GGUF to recover.
      set_secret INFOCHAT_LLAMACPP_EMBED_GGUF_URL "$emb_url"
      set_secret INFOCHAT_LLAMACPP_EMBED_GGUF_SHA "$emb_sha"
      echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile llamacpp-embeddings up -d llamacpp-embeddings"
      docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile llamacpp-embeddings up -d llamacpp-embeddings
      emb_base_url="$LLAMACPP_EMBED_URL"
      emb_model="$emb_file"
    else
      # Minimal Ollama-embeddings hook (D49): start ollama alongside llamacpp and
      # pull only the nomic embedder. The full ollama backend (model table, every
      # task) is the `ollama)` branch; here Ollama serves embeddings only.
      echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile ollama up -d ollama"
      docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama up -d ollama
      echo "+ wait for ollama daemon (up to ${WAIT_TIMEOUT}s)"
      deadline=$(( SECONDS + WAIT_TIMEOUT ))
      until docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama exec -T ollama ollama list >/dev/null 2>&1; do
        if (( SECONDS >= deadline )); then
          echo "FAIL: ollama daemon not ready after ${WAIT_TIMEOUT}s." >&2
          exit 1
        fi
        sleep 2
      done
      echo "+ ollama pull $NOMIC_OLLAMA_MODEL"
      docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama exec -T ollama ollama pull "$NOMIC_OLLAMA_MODEL"
      emb_base_url="$OLLAMA_URL"
      emb_model="$NOMIC_OLLAMA_MODEL"
    fi

    # --- Config. Generative GGUF on every LLM task; embeddings on its own
    # backend, NEVER the generative GGUF (acceptance 6). dimension pinned 768 so
    # the generated config is self-describing and matches allow-model-change=false. ---
    # Switch-away-from-remote reconcile (M1-530): drop any remote api-key config a
    # prior `remote` run left — a local llamacpp backend carries no key.
    clear_remote_llm_creds
    set_llm_base_urls "$LLAMACPP_URL"
    for task in $LLM_TASKS; do
      set_prop "infochat.llm.${task}.model" "$gen_file"
    done
    set_prop infochat.embeddings.base-url "$emb_base_url"
    set_prop infochat.embeddings.model "$emb_model"
    set_prop infochat.embeddings.dimension "$EMBEDDINGS_DIMENSION"
    echo "llamacpp backend ready: generative $gen_file via $LLAMACPP_URL; embeddings $emb_model via $emb_base_url"
    ;;
  remote)
    if [[ "$defaults" -eq 1 ]]; then
      echo "FAIL: --defaults cannot configure the remote backend; run interactively." >&2
      exit 1
    fi
    # Privacy disclosure BEFORE the operator commits to a remote endpoint: the
    # operator must see what leaves the machine before typing the URL/key, not
    # after. At setup the remote backend routes the seven GENERATIVE tasks — chat
    # included — to the remote endpoint (set_llm_base_urls below); embeddings run
    # LOCALLY on a co-started Ollama nomic embedder and never leave the machine
    # (D54). switch-llm.sh can later re-route the deployment back to a local
    # backend — for ALL generative tasks at once, not one at a time (M1-603/D56
    # made it one backend choice for the whole deployment); a single task can
    # still be kept local by hand-pinning its per-task base-url, but EVERY
    # switch that proceeds deletes such pins (:352-355) and only announces them
    # afterwards. The M1-605 gate refuses an Enter-default run over a pinned
    # config and exits without writing, so "declining" means not switching at
    # all — there is no prompt that lets an operator switch AND keep a pin.
    # Do not restate this as a declinable consent prompt: the printed block
    # below says the opposite because the opposite is what the code does.
    # Embeddings are always local and switch-llm.sh never touches them.
    #
    # This block and prod/switch-llm.sh Phase 4 are the two RUNTIME renderings
    # of docs/spec/security.md §Secrets handling; SETUP_GUIDE.md §"Switching
    # your AI backend later" is the long form both point at. This one prints
    # EARLIER and to MORE operators — an operator who picks remote here and
    # never re-routes sees only this text — so it must not be the weaker of
    # the two. Never state or imply that an English-only deployment sends
    # nothing through translator: the ingest leg is gated on the SOURCE's
    # language, not on any scope's /lang.
    echo
    echo "PRIVACY DISCLOSURE — choosing 'remote' sends the following GENERATIVE tasks"
    echo "to the remote provider (embeddings are NOT sent — see the last line):"
    echo "  !! chat — YOUR PRIVATE MESSAGES to the bot are sent to the remote provider."
    echo "           This is the most sensitive exposure: your direct conversations."
    echo "  -  security / tagger / entity / classifier — moderation, tagging, entity"
    echo "     extraction and post-kind classification over fetched PUBLIC posts;"
    echo "     exposes your source list / topic interests, not private user data."
    echo "  -  summarizer — ingest-time abstracts of EVERY long fetched PUBLIC post"
    echo "     (BodySummaryWorker, on a timer, no user present) plus summaries of the"
    echo "     posts you query; exposes your source list / topic interests and which"
    echo "     posts you read."
    echo "  !! translator — carries PRIVATE user text, and runs UNATTENDED. Two things:"
    echo "           1. For any chat or group whose /lang is not English, it sends your"
    echo "              messages and what you read — including your search query, which"
    echo "              on every chat turn IS your raw message, truncated, NOT redacted."
    echo "           2. Regardless of /lang, even if every scope is English: it sends the"
    echo "              full TITLE AND BODY of every post from a non-English source, on a"
    echo "              timer, forever, with no user present. This is gated on the SOURCE's"
    echo "              language, not yours — an all-English deployment is NOT exempt."
    echo "           Full leg-by-leg list: SETUP_GUIDE.md, \"Switching your AI backend later\"."
    echo "  -  embeddings — run LOCALLY: a small Ollama nomic embedder is started on"
    echo "     this machine, so the post content for vectorization NEVER leaves it (D54)."
    echo "To keep chat (and every generative task) local too, pick 'ollama'/'llamacpp'"
    echo "now, or switch the whole deployment back to a local backend later with"
    echo "./prod/switch-llm.sh, which re-routes ALL generative tasks at once. To keep"
    echo "just one task (say translator) local while the rest go remote, hand-pin its"
    echo "infochat.llm.<task>.base-url in the runtime application.properties. TWO"
    echo "CAVEATS: every switch-llm.sh run that PROCEEDS deletes such pins (its only"
    echo "guard is refusing an Enter-default run over a pinned config and writing"
    echo "nothing — there is no way to switch AND keep a pin), so re-apply the pin"
    echo "after any switch; and a pinned task does NOT inherit the shared api-key"
    echo "(set the per-task infochat.llm.<task>.api-key if the pinned route needs one)."
    echo
    # Remote provider dialect (M1-614): openai-compatible (default — the generic
    # OpenAI-family path: NanoGPT, OpenAI, OpenRouter) or deepseek (the dedicated
    # DeepSeekProvider, M1-608, which injects the DeepSeek `thinking` toggle so
    # deepseek-v4-flash — which defaults thinking-ON — runs non-thinking and does
    # not burn the max-tokens budget). The generic openai-compatible adapter cannot
    # send `thinking` unconditionally, which is why deepseek is its own dialect.
    # The choice drives infochat.llm.default.provider + the generative model below.
    DEFAULT_REMOTE_PROVIDER="openai-compatible"
    VALID_REMOTE_PROVIDERS="openai-compatible deepseek"
    read -rp "Remote provider dialect (${VALID_REMOTE_PROVIDERS// /|}) [${DEFAULT_REMOTE_PROVIDER}]: " remote_provider
    remote_provider="${remote_provider:-$DEFAULT_REMOTE_PROVIDER}"
    case " $VALID_REMOTE_PROVIDERS " in
      *" $remote_provider "*) ;;
      *) echo "FAIL: unknown remote provider dialect '$remote_provider' (expected: $VALID_REMOTE_PROVIDERS)" >&2; exit 1 ;;
    esac
    # deepseek speaks the OpenAI wire path at api.deepseek.com, so offer that as
    # the default base-url (Enter accepts it); the operator can still point at a
    # DeepSeek-compatible gateway on another host. openai-compatible has no single
    # canonical endpoint, so its base-url stays required.
    if [[ "$remote_provider" == "deepseek" ]]; then
      read -rp "Remote base-url [https://api.deepseek.com]: " base_url
      base_url="${base_url:-https://api.deepseek.com}"
    else
      read -rp "Remote OpenAI-compatible base-url (e.g. https://nano-gpt.com/api/v1): " base_url
      if [[ -z "$base_url" ]]; then
        echo "FAIL: a base-url is required for the remote backend." >&2
        exit 1
      fi
    fi
    # Generative model for all seven tasks: pinned deepseek-v4-flash for deepseek
    # (deepseek-chat is deprecated 2026-07-24), else the operator-entered model.
    # Writing the model closes the pre-existing gap where remote tasks kept the
    # profile's baked local model names — a local name against a remote endpoint
    # 400s every call and trips the M1-577 provider/model mismatch scan.
    if [[ "$remote_provider" == "deepseek" ]]; then
      remote_model="deepseek-v4-flash"
    else
      read -rp "Remote model name (applied to all generative tasks, e.g. gpt-4o-mini): " remote_model
      if [[ -z "$remote_model" ]]; then
        echo "FAIL: a model name is required for the remote backend." >&2
        exit 1
      fi
    fi
    # The API key is a secret, so it lives in secrets.env (§7.3 — secrets never
    # enter application.properties), reusing any value a prior step-4 run already
    # recorded. application.properties references it by env var for Quarkus to
    # expand at boot.
    if grep -qE '^INFOCHAT_LLM_API_KEY=.+' "$SECRETS_FILE" 2>/dev/null; then
      echo "using INFOCHAT_LLM_API_KEY from secrets.env (already recorded)"
    else
      read -rsp "Remote LLM API key: " llm_key
      echo
      # The remote backend authenticates with this key, so an empty one is a setup
      # error we surface now — mirroring the base-url check above — rather than
      # letting it boot and fail later as an opaque 401 from the provider.
      if [[ -z "$llm_key" ]]; then
        echo "FAIL: the remote backend requires an API key (none entered)." >&2
        exit 1
      fi
      touch "$SECRETS_FILE"
      chmod 600 "$SECRETS_FILE"
      # Quote the value for compose's --env-file dotenv parse (M1-389): a '#'
      # or whitespace in the key is data, not a comment / field break.
      printf 'INFOCHAT_LLM_API_KEY="%s"\n' "$(dotenv_escape "$llm_key")" >> "$SECRETS_FILE"
      echo "+ recorded INFOCHAT_LLM_API_KEY in secrets.env"
    fi
    # Embeddings run locally even with a remote chat backend (D54): the embedding
    # model is frozen 768-dim nomic (allow-model-change=false) and commercial
    # remote endpoints don't serve nomic-embed-text at 768-dim, so route ONLY the
    # seven generative tasks remote and co-start a local Ollama nomic embedder. This
    # mirrors the llamacpp branch's ollama-embeddings hook; it MUST run after the
    # API-key block above, which guarantees secrets.env exists for --env-file.
    echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile ollama up -d ollama"
    docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama up -d ollama
    echo "+ wait for ollama daemon (up to ${WAIT_TIMEOUT}s)"
    deadline=$(( SECONDS + WAIT_TIMEOUT ))
    until docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama exec -T ollama ollama list >/dev/null 2>&1; do
      if (( SECONDS >= deadline )); then
        echo "FAIL: ollama daemon not ready after ${WAIT_TIMEOUT}s." >&2
        exit 1
      fi
      sleep 2
    done
    echo "+ ollama pull $NOMIC_OLLAMA_MODEL"
    docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile ollama exec -T ollama ollama pull "$NOMIC_OLLAMA_MODEL"

    # Generative tasks → remote endpoint + API key (one shared default each,
    # D56; set_llm_base_urls swept any old-format per-task lines); embeddings
    # → local Ollama nomic.
    set_llm_base_urls "$base_url"
    set_prop infochat.llm.default.api-key '${INFOCHAT_LLM_API_KEY}'
    # Provider dialect + generative model on every task (M1-614): deepseek routes
    # through the DeepSeekProvider (passes the M1-577 guard cleanly, in the remote
    # set); openai-compatible is written explicitly for a self-describing config
    # even though it equals the LlmRouter default. No reasoning-effort key is
    # written — deepseek runs thinking-off by default (M1-610 keeps it off).
    set_prop infochat.llm.default.provider "$remote_provider"
    for task in $LLM_TASKS; do
      set_prop "infochat.llm.${task}.model" "$remote_model"
    done
    # Drop any infochat.embeddings.api-key a prior (mis-wired) remote run wrote:
    # embeddings now hit the local Ollama nomic endpoint and carry no key, so a
    # lingering ${INFOCHAT_LLM_API_KEY} reference would be stale and contradict the
    # local-embeddings disclosure above (D54). Idempotent, like set_prop.
    sed -i '/^infochat\.embeddings\.api-key=/d' "$CONFIG_FILE"
    set_prop infochat.embeddings.base-url "$OLLAMA_URL"
    set_prop infochat.embeddings.model "$NOMIC_OLLAMA_MODEL"
    set_prop infochat.embeddings.dimension "$EMBEDDINGS_DIMENSION"
    echo "remote backend ready: generative tasks via $base_url (provider=$remote_provider, model=$remote_model); embeddings via local Ollama $OLLAMA_URL"
    ;;
esac

# --- Per-task LLM timing: chat + summarizer timeout-ms / max-tokens (F-live-5).
# The in-app defaults (timeout-ms 30000, max-tokens 1024) only fit a fast
# backend: prose tasks on a slow local host need a longer timeout and a tighter
# output cap, sized TOGETHER so max-tokens × per-token decode time + prompt
# processing < timeout-ms (the M1-548 invariant) — otherwise the client cancels
# a finishable reply and the doomed retries congest the shared server.
# Recommendations are keyed backend-first (a remote API answers prose in
# seconds regardless of profile; a 240 s timeout there would hide a real
# outage for minutes), then profile for the local backends. The vps values are
# host-proven (2026-07-03 live run: 600-token cap at 143 s decode + 13.5 s
# prefill < 240 s); pi is provisional (~1 tok/s decode); laptop assumes
# vps-class CPU. Only the two prose tasks are collected — eval-task outputs
# are a handful of tokens and ride the in-app defaults.
if [[ "$backend" == "remote" ]]; then
  chat_timeout_default=60000;  chat_maxtok_default=1024
  summ_timeout_default=60000;  summ_maxtok_default=1024
else
  case "$profile" in
    pi) chat_timeout_default=480000; chat_maxtok_default=400
        summ_timeout_default=480000; summ_maxtok_default=300 ;;
    *)  chat_timeout_default=240000; chat_maxtok_default=600
        summ_timeout_default=240000; summ_maxtok_default=400 ;;
  esac
fi
if [[ "$defaults" -eq 0 ]]; then
  echo
  echo "Per-task LLM timing for the two prose tasks (chat, summarizer). Enter keeps"
  echo "the recommended value for the '$backend' backend on the '$profile' profile;"
  echo "size overrides so max-tokens × per-token decode time + prompt processing"
  echo "stays under timeout-ms."
fi
prompt_timing infochat.llm.chat.timeout-ms       "$chat_timeout_default" chat_timeout
prompt_timing infochat.llm.chat.max-tokens       "$chat_maxtok_default"  chat_maxtok
prompt_timing infochat.llm.summarizer.timeout-ms "$summ_timeout_default" summ_timeout
prompt_timing infochat.llm.summarizer.max-tokens "$summ_maxtok_default"  summ_maxtok
set_prop infochat.llm.chat.timeout-ms "$chat_timeout"
set_prop infochat.llm.chat.max-tokens "$chat_maxtok"
set_prop infochat.llm.summarizer.timeout-ms "$summ_timeout"
set_prop infochat.llm.summarizer.max-tokens "$summ_maxtok"
echo "+ wrote LLM timing: chat ${chat_timeout} ms / ${chat_maxtok} tokens; summarizer ${summ_timeout} ms / ${summ_maxtok} tokens"
