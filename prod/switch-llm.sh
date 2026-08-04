#!/bin/bash
# prod/switch-llm.sh — POST-setup LLM backend switcher (M1-418, reshaped by
# M1-603 to the one-service config model).
#
# A standalone maintenance tool (NOT a wizard step): re-route the deployment's
# generative LLM tasks to a different backend after the initial setup, the
# common case being a move to a remote API on a low-power host. The wizard's
# prod/scripts/4-llm.sh provisions a backend at install time and offers no
# post-setup re-route, so this fills that gap by regenerating the shared
# infochat.llm.default.{base-url,api-key} keys every task inherits (D56) plus
# the per-task model lines — one backend choice for the whole deployment. A
# task can still be pinned elsewhere by hand-editing a per-task
# infochat.llm.<task>.base-url override (per-task wins over the default), but
# this tool no longer drives mixed routing; it writes the one-service shape.
# A pinned task does NOT inherit the shared default api-key (the credential
# travels only to the default endpoint, M1-603 redteam) — a pinned route that
# needs a key also needs an explicit per-task infochat.llm.<task>.api-key.
#
# It NEVER touches infochat.embeddings.*: embeddings are locked to the 768-dim
# nomic embedder (allow-model-change=false); changing them corrupts pgvector
# retrieval and is rejected at Collector startup.
#
# Secret handling mirrors 4-llm.sh verbatim (dotenv_escape / set_secret /
# INFOCHAT_LLM_API_KEY reuse) rather than inventing new conventions: the API key
# lives only in secrets.env, and application.properties references it as the
# literal ${INFOCHAT_LLM_API_KEY}.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Unlike the wizard scripts under prod/scripts/, this tool lives in prod/ itself,
# so PROD_DIR is the script's own dir; RUNTIME_DIR/CONFIG_FILE/SECRETS_FILE still
# resolve to the same prod/runtime artifacts the wizard wrote (M1-386).
PROD_DIR="$SCRIPT_DIR"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"

# Over the compose network the apps reach the model servers by service name on
# the OpenAI-compatible /v1 base path; these are the SAME literals 4-llm.sh
# writes, so they double as the base-url -> backend classifier inputs.
OLLAMA_URL="http://ollama:11434/v1"
LLAMACPP_URL="http://llamacpp:8080/v1"
LLAMACPP_EMBED_URL="http://llamacpp-embeddings:8080/v1"

# The seven generative task config families (embeddings is deliberately
# excluded). Since M1-603 the endpoint/key are the SHARED default keys; this
# list drives the per-task model lines, the old-format-line sweep, and the
# per-task privacy disclosure — whose loud/private tier is chat AND translator
# (M1-758), not chat alone; Phase 4 carries the per-leg detail and the reason.
LLM_TASKS="security tagger entity classifier summarizer chat translator"
VALID_BACKENDS="remote ollama llamacpp"

# --- config / secret helpers (mirrored from 4-llm.sh so the two cannot drift) ---

# Read the LAST value for a property key, or empty if absent.
get_prop() {
  local key="$1" escaped
  escaped="${key//./\\.}"
  if [[ -f "$CONFIG_FILE" ]]; then
    sed -n "s/^${escaped}=//p" "$CONFIG_FILE" | tail -n1
  fi
}

# Idempotent property write: drop any existing line for the key, then append.
set_prop() {
  local key="$1" value="$2" escaped
  escaped="${key//./\\.}"
  if [[ -f "$CONFIG_FILE" ]]; then
    sed -i "/^${escaped}=/d" "$CONFIG_FILE"
  fi
  printf '%s=%s\n' "$key" "$value" >> "$CONFIG_FILE"
}

# Delete a property line entirely (used to CLEAR an api-key when the deployment
# leaves the remote backend — clearing means removing the line, not writing an
# empty value, so a local-backend config stays byte-identical to a
# wizard-generated one).
del_prop() {
  local key="$1" escaped
  escaped="${key//./\\.}"
  if [[ -f "$CONFIG_FILE" ]]; then
    sed -i "/^${escaped}=/d" "$CONFIG_FILE"
  fi
}

# Escape a value for a double-quoted secrets.env field so compose's --env-file
# dotenv parser reads it back byte-for-byte (M1-397). Order matters: backslash
# first, then the '"' that would close the field and the '$' some compose
# versions treat as ${...} interpolation.
dotenv_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//\$/\\\$}"
  printf '%s' "$value"
}

# Idempotent secrets.env write: drop any existing line for KEY, then append the
# value double-quoted + dotenv-escaped (M1-389/M1-397).
set_secret() {
  local key="$1" value="$2"
  touch "$SECRETS_FILE"
  chmod 600 "$SECRETS_FILE"
  sed -i "/^${key}=/d" "$SECRETS_FILE"
  printf '%s="%s"\n' "$key" "$(dotenv_escape "$value")" >> "$SECRETS_FILE"
}

# Classify a base-url into its backend. An unrecognized / empty url (e.g. a custom
# remote endpoint) classifies as remote — the conservative default.
classify_backend() {
  case "$1" in
    "$OLLAMA_URL") echo ollama ;;
    "$LLAMACPP_URL") echo llamacpp ;;
    *) echo remote ;;
  esac
}

# A task's EFFECTIVE base-url: the per-task override when present (old-format
# files and hand-pinned tasks), else the shared default (D56) — the same
# resolution the app performs, so the no-op detection judges the routing a
# call would actually take.
effective_url() {
  local task="$1" url
  url="$(get_prop "infochat.llm.${task}.base-url")"
  if [[ -z "$url" ]]; then
    url="$(get_prop infochat.llm.default.base-url)"
  fi
  printf '%s' "$url"
}

# A task's EFFECTIVE api-key presence: per-task line, else the default line.
effective_key_present() {
  local task="$1"
  if grep -qE "^infochat\.llm\.${task}\.api-key=" "$CONFIG_FILE"; then
    echo 1
  elif grep -qE '^infochat\.llm\.default\.api-key=' "$CONFIG_FILE"; then
    echo 1
  else
    echo 0
  fi
}

umask 077
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "FAIL: $CONFIG_FILE not found; run ./prod/setup.sh first." >&2
  exit 1
fi

# --- Phase 1: gather the deployment-level routing decision (no writes yet). -----
# The default answer is the CURRENT effective backend, classified from the
# shared default key — falling back to the chat task's per-task line so an
# old-format (pre-M1-603 per-task fan-out) file presents its real backend, not
# a spurious "remote" from an empty string.
declare -A new_model
cur_deploy_url="$(get_prop infochat.llm.default.base-url)"
if [[ -z "$cur_deploy_url" ]]; then
  cur_deploy_url="$(get_prop infochat.llm.chat.base-url)"
fi
cur_deploy_backend="$(classify_backend "$cur_deploy_url")"
read -rp "LLM backend for ALL generative tasks (${VALID_BACKENDS// /|}) [${cur_deploy_backend}]: " answer
backend="${answer:-$cur_deploy_backend}"
# System-boundary validation of free-text interactive input.
case " $VALID_BACKENDS " in
  *" $backend "*) ;;
  *) echo "FAIL: unknown backend '$backend' (expected: $VALID_BACKENDS)" >&2; exit 1 ;;
esac

needs_key=0
new_url=""
# The provider dialect the write phase will reconcile: remote picks it below;
# a local backend is openai-compatible via the ollama alias / the default.
new_provider="openai-compatible"
if [[ "$backend" == "remote" ]]; then
  needs_key=1
  # Remote provider dialect (M1-614), symmetric with prod/scripts/4-llm.sh:
  # openai-compatible (default) or deepseek (the dedicated DeepSeekProvider,
  # M1-608, which injects the DeepSeek `thinking` toggle so deepseek-v4-flash
  # runs thinking-off). Default to the CURRENT dialect so an all-Enter run over
  # a deepseek deployment stays a no-op; a non-deepseek/absent current resolves
  # to openai-compatible (the LlmRouter default).
  if [[ "$(get_prop infochat.llm.default.provider)" == "deepseek" ]]; then
    default_dialect="deepseek"
  else
    default_dialect="openai-compatible"
  fi
  read -rp "  Remote provider dialect (openai-compatible|deepseek) [${default_dialect}]: " dialect
  dialect="${dialect:-$default_dialect}"
  case " openai-compatible deepseek " in
    *" $dialect "*) ;;
    *) echo "FAIL: unknown remote provider dialect '$dialect' (expected: openai-compatible|deepseek)" >&2; exit 1 ;;
  esac
  new_provider="$dialect"
  if [[ "$cur_deploy_backend" == "remote" && -n "$cur_deploy_url" ]]; then
    read -rp "  Remote base-url [${cur_deploy_url}]: " url
    new_url="${url:-$cur_deploy_url}"
  elif [[ "$dialect" == "deepseek" ]]; then
    # deepseek speaks the OpenAI wire path at api.deepseek.com; offer it as the
    # default when there is no current remote url to preserve.
    read -rp "  Remote base-url [https://api.deepseek.com]: " url
    new_url="${url:-https://api.deepseek.com}"
  else
    read -rp "  Remote base-url (e.g. https://nano-gpt.com/api/v1): " url
    if [[ -z "$url" ]]; then
      echo "FAIL: a base-url is required for the remote backend." >&2
      exit 1
    fi
    new_url="$url"
  fi
  if [[ "$dialect" == "deepseek" ]]; then
    # deepseek pins one model for every task (deepseek-chat is deprecated
    # 2026-07-24); no per-task prompt, and no reasoning-effort key (M1-610
    # keeps thinking off).
    for task in $LLM_TASKS; do
      new_model[$task]="deepseek-v4-flash"
    done
  else
    # Models are task tuning and rarely valid across backends: a llama3.1:8b
    # left in place against a remote endpoint 400s every call (and trips the
    # startup mismatch scan), so the remote path re-prompts each task's model,
    # defaulting to its current value.
    for task in $LLM_TASKS; do
      cur_model="$(get_prop "infochat.llm.${task}.model")"
      read -rp "  ${task} model [${cur_model}]: " model
      new_model[$task]="${model:-$cur_model}"
    done
  fi
else
  new_url="$OLLAMA_URL"
  if [[ "$backend" == "llamacpp" ]]; then new_url="$LLAMACPP_URL"; fi
fi

# --- Phase 2: would anything change? An all-default run is a byte-identical
# no-op, so detect that BEFORE backing up or writing (acceptance item 2). The
# comparison runs on EFFECTIVE per-task routing (per-task line, else default),
# so an all-Enter run over an old-format file is a no-op too — the file is
# migrated to the shared-default shape only by a run that actually changes
# routing. ------------------------------------------------------------------

# A MIXED/PINNED config carries >=1 per-task infochat.llm.<task>.base-url override
# — a pre-M1-603 fan-out file, or a deliberate hand pin (e.g. chat kept on local
# ollama for privacy while the rest are remote, D56). Phase 3 sweeps EVERY
# per-task base-url/api-key line so the shared default wins, so a switch DISCARDS
# those pins. Collect the exact lines a sweep would remove (before any mutation):
# their presence gates the M1-605 consent check below and is named before any
# write. Detection is base-url presence (the acceptance condition); any adjacent
# per-task api-key line is collected too so a confirmed switch names it as swept.
pinned_lines=()
has_pinned_baseurl=0
for task in $LLM_TASKS; do
  pin_url="$(get_prop "infochat.llm.${task}.base-url")"
  if [[ -n "$pin_url" ]]; then
    has_pinned_baseurl=1
    pinned_lines+=("infochat.llm.${task}.base-url=$pin_url")
  fi
  pin_key="$(get_prop "infochat.llm.${task}.api-key")"
  if [[ -n "$pin_key" ]]; then
    pinned_lines+=("infochat.llm.${task}.api-key=$pin_key")
  fi
done

key_needs_write=0
if [[ "$needs_key" -eq 1 ]] && ! grep -qE '^INFOCHAT_LLM_API_KEY=.+' "$SECRETS_FILE" 2>/dev/null; then
  key_needs_write=1
fi
changed="$key_needs_write"
# Provider-dialect change (M1-614): a deepseek<->openai-compatible flip with
# every other value identical must still count as a change, else the switch is a
# no-op and the deployment keeps the old thinking behavior. An absent provider
# line resolves to the openai-compatible default (mirrors LlmRouter), so an
# all-Enter openai-compatible run over a provider-less file stays a no-op.
cur_provider_effective="$(get_prop infochat.llm.default.provider)"
cur_provider_effective="${cur_provider_effective:-openai-compatible}"
if [[ "$new_provider" != "$cur_provider_effective" ]]; then changed=1; fi
for task in $LLM_TASKS; do
  cur_url="$(effective_url "$task")"
  if [[ "$cur_url" != "$new_url" ]]; then changed=1; fi
  if [[ "$backend" == "remote" ]]; then
    cur_model="$(get_prop "infochat.llm.${task}.model")"
    if [[ "${new_model[$task]}" != "$cur_model" ]]; then changed=1; fi
    if [[ "$(effective_key_present "$task")" -eq 0 ]]; then changed=1; fi
  else
    if [[ "$(effective_key_present "$task")" -eq 1 ]]; then changed=1; fi
  fi
done

if [[ "$changed" -eq 0 ]]; then
  echo "No changes — the deployment is already on its selected backend. Nothing to do."
  exit 0
fi

# --- Phase 2b: consent gate — never SILENTLY sweep a hand-pinned route on an
# all-default run (M1-605). We are past the no-op exit, so this run WOULD change
# a config that carries per-task pins. An empty backend answer means the operator
# only pressed Enter, accepting the classified default — that is NOT explicit
# consent to discard a privacy-motivated LOCAL pin (or reroute an old-format
# fan-out). So name every pin a switch would sweep and REFUSE, requiring an
# explicit typed backend to proceed. A typed backend answer IS the consent and
# falls through to the switch (Phase 3 still names each swept line before writing).
if [[ "$has_pinned_baseurl" -eq 1 && -z "$answer" ]]; then
  echo "This config has hand-pinned per-task LLM routes:"
  printf '  - %s\n' "${pinned_lines[@]}"
  echo "Switching every task to '$backend' would REMOVE these pins (the shared default"
  echo "then applies). Refusing to do that on an Enter-default — nothing was written."
  echo "Re-run and TYPE the backend (${VALID_BACKENDS// /|}) to confirm the switch."
  exit 0
fi

# --- Phase 3: backup BEFORE any write, then apply. ------------------------------
ts="$(date +%Y%m%d-%H%M%S)"
cfg_bak="${CONFIG_FILE}.bak.${ts}"
cp "$CONFIG_FILE" "$cfg_bak"
sec_bak=""
if [[ -f "$SECRETS_FILE" ]]; then
  sec_bak="${SECRETS_FILE}.bak.${ts}"
  cp "$SECRETS_FILE" "$sec_bak"
fi
echo "Backed up before writing:"
echo "  $cfg_bak"
if [[ -n "$sec_bak" ]]; then echo "  $sec_bak"; fi
echo "Rollback (undo this run):"
if [[ -n "$sec_bak" ]]; then
  echo "  cp '$cfg_bak' '$CONFIG_FILE' && cp '$sec_bak' '$SECRETS_FILE'"
else
  echo "  cp '$cfg_bak' '$CONFIG_FILE'"
fi

if [[ "$key_needs_write" -eq 1 ]]; then
  read -rsp "Remote LLM API key: " llm_key
  echo
  if [[ -z "$llm_key" ]]; then
    echo "FAIL: the remote backend was selected but no INFOCHAT_LLM_API_KEY exists and none was entered." >&2
    exit 1
  fi
  set_secret INFOCHAT_LLM_API_KEY "$llm_key"
  echo "+ recorded INFOCHAT_LLM_API_KEY in secrets.env"
elif [[ "$needs_key" -eq 1 ]]; then
  echo "using INFOCHAT_LLM_API_KEY from secrets.env (reused, not re-prompted)"
fi

# A mutating run migrates an old-format file to the shared-default shape:
# stale per-task base-url/api-key lines would WIN over the default keys
# (per-task beats default, D56), silently pinning tasks to the old endpoint
# and making this switch a no-op for them. Models stay per-task.
# Name every hand-pinned line this sweep removes so a confirmed switch never
# SILENTLY discards a route (M1-605); the backup above already preserves them.
if [[ "$has_pinned_baseurl" -eq 1 ]]; then
  echo "Sweeping these hand-pinned per-task routes (superseded by the shared default):"
  printf '  - %s\n' "${pinned_lines[@]}"
fi
for task in $LLM_TASKS; do
  del_prop "infochat.llm.${task}.base-url"
  del_prop "infochat.llm.${task}.api-key"
done
set_prop infochat.llm.default.base-url "$new_url"
# Reconcile the provider dialect (M1-614): write provider=deepseek only for a
# deepseek route; every other case (openai-compatible remote, or a local
# backend) CLEARS the line so routing falls back to the openai-compatible
# default — this also drops a stale provider=deepseek when leaving DeepSeek.
if [[ "$new_provider" == "deepseek" ]]; then
  set_prop infochat.llm.default.provider deepseek
else
  del_prop infochat.llm.default.provider
fi
if [[ "$backend" == "remote" ]]; then
  set_prop infochat.llm.default.api-key '${INFOCHAT_LLM_API_KEY}'
  for task in $LLM_TASKS; do
    cur_model="$(get_prop "infochat.llm.${task}.model")"
    if [[ "${new_model[$task]}" != "$cur_model" ]]; then
      set_prop "infochat.llm.${task}.model" "${new_model[$task]}"
    fi
  done
else
  del_prop infochat.llm.default.api-key
  # Local routing touches only the endpoint + api-key; the model lines are
  # left intact. Coming FROM remote, a stale provider-native model name is
  # not a valid local model — flag it so the operator fixes it via the wizard.
  if [[ "$cur_deploy_backend" == "remote" ]]; then
    for task in $LLM_TASKS; do
      echo "Note: ${task} kept its model '$(get_prop "infochat.llm.${task}.model")'; ensure it is a valid ${backend} model (re-run prod/scripts/4-llm.sh to pull/set local models)."
    done
  fi
fi

# --- Phase 4: privacy disclosure naming exactly the now-remote tasks. -----------
# One backend for the whole deployment means remote routes ALL seven tasks; the
# exposure still differs per task: chat AND translator carry PRIVATE user text
# (loudest tier, printed above the loop); the ingest tasks run over fetched
# PUBLIC posts (topic-interest / source-list exposure, not private user data).
# A wrong claim here is a security defect, so the text is per-task, never a
# blanket "privacy sacrificed" line — and never a NEGATIVE claim ("task X sends
# nothing when Y") unless every leg of that task is gated on Y.
#
# translator earned the loud tier at M1-746 and carries SEVEN distinct legs
# (enumerated in docs/spec/security.md §Secrets handling, which is the
# authority this text is kept in sync with). Printing all seven here buries
# the decision in ~30 lines an operator skims, so the block states the two
# facts that actually drive the choice and points at SETUP_GUIDE.md for the
# breakdown. The two facts are chosen because they are the ones a reader
# CANNOT infer from the other:
#   1. it carries private user text, like chat  — legs gated on a scope's /lang;
#   2. it also ships whole post titles+bodies continuously, gated on the
#      SOURCE's language and NOT on any scope's /lang, so an all-English
#      deployment is NOT exempt (IngestTranslationWorker, @Scheduled).
# Fact 2 is the one an earlier revision of this text got backwards, claiming
# an /lang en scope "sends nothing" — never state that. translator has NO case
# branch below; the loop deliberately prints nothing for it.
if [[ "$backend" == "remote" ]]; then
  echo
  echo "PRIVACY DISCLOSURE — these tasks now call a REMOTE provider:"
  echo "  !! chat — YOUR PRIVATE MESSAGES to the bot are sent to the remote provider."
  echo "           This is the most sensitive exposure: your direct conversations."
  echo "  !! translator — carries PRIVATE user text, and runs UNATTENDED. Two things:"
  echo "           1. For any chat or group whose /lang is not English, it sends your"
  echo "              messages and what you read — including your search query, which"
  echo "              on every chat turn IS your raw message, truncated, NOT redacted."
  echo "           2. Regardless of /lang, even if every scope is English: it sends the"
  echo "              full TITLE AND BODY of every post from a non-English source, on a"
  echo "              timer, forever, with no user present. This is gated on the SOURCE's"
  echo "              language, not yours — an all-English deployment is NOT exempt."
  echo "           Full leg-by-leg list: SETUP_GUIDE.md, \"Switching your AI backend later\"."
  for task in $LLM_TASKS; do
    case "$task" in
      security)   echo "  -  security — moderation over fetched PUBLIC posts; exposes your source list / topic interests, not private user data." ;;
      tagger)     echo "  -  tagger — topic tagging over fetched PUBLIC posts; exposes your topic interests." ;;
      entity)     echo "  -  entity — entity extraction over fetched PUBLIC posts; exposes your topic interests." ;;
      classifier) echo "  -  classifier — post-kind classification over fetched PUBLIC posts; exposes your topic interests." ;;
      summarizer) echo "  -  summarizer — ingest-time abstracts of EVERY long fetched PUBLIC post (BodySummaryWorker) plus summaries of the posts you query; exposes your source list / topic interests and which posts you read." ;;
    esac
  done
fi

# --- Phase 5: bring up the backend services the new routing needs, then print
# the recreate command. up -d, NEVER restart: the API key reaches the container
# via --env-file at container-CREATE time, so a new key needs a recreate; the
# mounted application.properties is re-read on recreate (runtime bind-mount,
# M1-386). The embeddings backend (config untouched) must also be up. -----------
declare -A ensure_services
case "$backend" in
  ollama)   ensure_services[ollama]=1 ;;
  llamacpp) ensure_services[llamacpp]=1 ;;
esac
emb_url="$(get_prop infochat.embeddings.base-url)"
case "$emb_url" in
  "$OLLAMA_URL")        ensure_services[ollama]=1 ;;
  "$LLAMACPP_EMBED_URL") ensure_services[llamacpp-embeddings]=1 ;;
esac
for service in "${!ensure_services[@]}"; do
  echo "+ docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile $service up -d $service"
  docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod --profile "$service" up -d "$service"
done

echo
echo "Apply the change by RECREATING the app containers (not 'restart' — the API key"
echo "reaches the container via --env-file only at container-create time):"
echo "  docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod up -d infochat-collector infochat-provider"
