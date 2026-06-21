#!/bin/bash
# prod/switch-llm.sh — POST-setup per-task LLM backend switcher (M1-418).
#
# A standalone maintenance tool (NOT a wizard step): re-route any generative LLM
# task to a different backend after the initial setup, the common case being a
# move to a remote API on a low-power host. The wizard's prod/scripts/4-llm.sh
# provisions a backend at install time and offers no post-setup re-route, so this
# fills that gap by regenerating only the per-task infochat.llm.<task>.* config —
# no app-code change, since each task's config family is already independent and
# the shipped %remote-llm profile is itself a mix.
#
# It prompts per generative task (security tagger entity summarizer chat
# translator) for remote | ollama | llamacpp, defaulting to the task's CURRENT
# backend (classified from its base-url). It NEVER touches infochat.embeddings.*:
# embeddings are locked to the 768-dim nomic embedder (allow-model-change=false);
# changing them corrupts pgvector retrieval and is rejected at Collector startup.
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

# The six generative task config families (embeddings is deliberately excluded).
LLM_TASKS="security tagger entity summarizer chat translator"
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

# Delete a property line entirely (used to CLEAR an api-key when a task leaves the
# remote backend — clearing means removing the line, not writing an empty value,
# so a local-backend config stays byte-identical to a wizard-generated one).
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

umask 077
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "FAIL: $CONFIG_FILE not found; run ./prod/setup.sh first." >&2
  exit 1
fi

# --- Phase 1: gather the per-task routing decisions (no writes yet). ------------
declare -A chosen new_url new_model
needs_key=0
for task in $LLM_TASKS; do
  cur_url="$(get_prop "infochat.llm.${task}.base-url")"
  cur_backend="$(classify_backend "$cur_url")"
  read -rp "Backend for ${task} (${VALID_BACKENDS// /|}) [${cur_backend}]: " answer
  backend="${answer:-$cur_backend}"
  # System-boundary validation of free-text interactive input.
  case " $VALID_BACKENDS " in
    *" $backend "*) ;;
    *) echo "FAIL: unknown backend '$backend' for ${task} (expected: $VALID_BACKENDS)" >&2; exit 1 ;;
  esac
  chosen[$task]="$backend"
  if [[ "$backend" == "remote" ]]; then
    cur_model="$(get_prop "infochat.llm.${task}.model")"
    if [[ "$cur_backend" == "remote" && -n "$cur_url" ]]; then
      read -rp "  ${task} remote base-url [${cur_url}]: " url
      new_url[$task]="${url:-$cur_url}"
    else
      read -rp "  ${task} remote base-url (e.g. https://nano-gpt.com/api/v1): " url
      if [[ -z "$url" ]]; then
        echo "FAIL: a base-url is required to route ${task} to remote." >&2
        exit 1
      fi
      new_url[$task]="$url"
    fi
    read -rp "  ${task} model [${cur_model}]: " model
    new_model[$task]="${model:-$cur_model}"
    needs_key=1
  fi
done

# --- Phase 2: would anything change? An all-default run is a byte-identical
# no-op, so detect that BEFORE backing up or writing (acceptance item 2). A line
# is only rewritten when its value actually differs, which keeps unchanged lines
# (including the entire infochat.embeddings.* block) exactly in place. ----------
key_needs_write=0
if [[ "$needs_key" -eq 1 ]] && ! grep -qE '^INFOCHAT_LLM_API_KEY=.+' "$SECRETS_FILE" 2>/dev/null; then
  key_needs_write=1
fi
changed="$key_needs_write"
for task in $LLM_TASKS; do
  backend="${chosen[$task]}"
  cur_url="$(get_prop "infochat.llm.${task}.base-url")"
  cur_key="$(get_prop "infochat.llm.${task}.api-key")"
  has_key=0
  if grep -qE "^infochat\.llm\.${task}\.api-key=" "$CONFIG_FILE"; then has_key=1; fi
  if [[ "$backend" == "remote" ]]; then
    cur_model="$(get_prop "infochat.llm.${task}.model")"
    if [[ "${new_url[$task]}" != "$cur_url" ]]; then changed=1; fi
    if [[ "$cur_key" != '${INFOCHAT_LLM_API_KEY}' ]]; then changed=1; fi
    if [[ "${new_model[$task]}" != "$cur_model" ]]; then changed=1; fi
  else
    desired_url="$OLLAMA_URL"
    if [[ "$backend" == "llamacpp" ]]; then desired_url="$LLAMACPP_URL"; fi
    if [[ "$cur_url" != "$desired_url" ]]; then changed=1; fi
    if [[ "$has_key" -eq 1 ]]; then changed=1; fi
  fi
done

if [[ "$changed" -eq 0 ]]; then
  echo "No changes — every task is already on its selected backend. Nothing to do."
  exit 0
fi

# --- Phase 3: backup BEFORE any write, then apply only the changed lines. -------
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
    echo "FAIL: a remote task was selected but no INFOCHAT_LLM_API_KEY exists and none was entered." >&2
    exit 1
  fi
  set_secret INFOCHAT_LLM_API_KEY "$llm_key"
  echo "+ recorded INFOCHAT_LLM_API_KEY in secrets.env"
elif [[ "$needs_key" -eq 1 ]]; then
  echo "using INFOCHAT_LLM_API_KEY from secrets.env (reused, not re-prompted)"
fi

for task in $LLM_TASKS; do
  backend="${chosen[$task]}"
  cur_url="$(get_prop "infochat.llm.${task}.base-url")"
  cur_key="$(get_prop "infochat.llm.${task}.api-key")"
  cur_backend="$(classify_backend "$cur_url")"
  has_key=0
  if grep -qE "^infochat\.llm\.${task}\.api-key=" "$CONFIG_FILE"; then has_key=1; fi
  if [[ "$backend" == "remote" ]]; then
    cur_model="$(get_prop "infochat.llm.${task}.model")"
    if [[ "${new_url[$task]}" != "$cur_url" ]]; then
      set_prop "infochat.llm.${task}.base-url" "${new_url[$task]}"
    fi
    if [[ "$cur_key" != '${INFOCHAT_LLM_API_KEY}' ]]; then
      set_prop "infochat.llm.${task}.api-key" '${INFOCHAT_LLM_API_KEY}'
    fi
    if [[ "${new_model[$task]}" != "$cur_model" ]]; then
      set_prop "infochat.llm.${task}.model" "${new_model[$task]}"
    fi
  else
    desired_url="$OLLAMA_URL"
    if [[ "$backend" == "llamacpp" ]]; then desired_url="$LLAMACPP_URL"; fi
    if [[ "$cur_url" != "$desired_url" ]]; then
      set_prop "infochat.llm.${task}.base-url" "$desired_url"
    fi
    if [[ "$has_key" -eq 1 ]]; then
      del_prop "infochat.llm.${task}.api-key"
    fi
    # Per acceptance, local routing touches only base-url + api-key; the model
    # line is left intact. Coming FROM remote, that stale model name may not be a
    # valid local model — flag it so the operator can fix it via the wizard.
    if [[ "$cur_backend" == "remote" ]]; then
      echo "Note: ${task} kept its model '$(get_prop "infochat.llm.${task}.model")'; ensure it is a valid ${backend} model (re-run prod/scripts/4-llm.sh to pull/set local models)."
    fi
  fi
done

# --- Phase 4: dynamic privacy disclosure naming exactly the now-remote tasks. ---
# The exposure differs per task: chat carries PRIVATE user DMs (loudest); the
# ingest tasks run over fetched PUBLIC posts (topic-interest / source-list
# exposure, not private user data). A wrong claim here is a security defect, so
# the text is per-task, never a blanket "privacy sacrificed" line.
remote_tasks=""
for task in $LLM_TASKS; do
  if [[ "${chosen[$task]}" == "remote" ]]; then remote_tasks="$remote_tasks $task"; fi
done
if [[ -n "${remote_tasks// /}" ]]; then
  echo
  echo "PRIVACY DISCLOSURE — these tasks now call a REMOTE provider:"
  for task in $remote_tasks; do
    if [[ "$task" == "chat" ]]; then
      echo "  !! chat — YOUR PRIVATE MESSAGES to the bot are sent to the remote provider."
      echo "           This is the most sensitive exposure: your direct conversations."
    fi
  done
  for task in $remote_tasks; do
    case "$task" in
      security)   echo "  -  security — moderation over fetched PUBLIC posts; exposes your source list / topic interests, not private user data." ;;
      tagger)     echo "  -  tagger — topic tagging over fetched PUBLIC posts; exposes your topic interests." ;;
      entity)     echo "  -  entity — entity extraction over fetched PUBLIC posts; exposes your topic interests." ;;
      summarizer) echo "  -  summarizer — summaries of the posts you query; exposes which topics / posts you read." ;;
      translator) echo "  -  translator — translation of the bot's replies to you; exposes the bot-reply text (which can echo your queries)." ;;
    esac
  done
fi

# --- Phase 5: bring up the backend services the new routing needs, then print
# the recreate command. up -d, NEVER restart: the API key reaches the container
# via --env-file at container-CREATE time, so a new key needs a recreate; the
# mounted application.properties is re-read on recreate (runtime bind-mount,
# M1-386). The embeddings backend (config untouched) must also be up. -----------
declare -A ensure_services
for task in $LLM_TASKS; do
  case "${chosen[$task]}" in
    ollama)   ensure_services[ollama]=1 ;;
    llamacpp) ensure_services[llamacpp]=1 ;;
  esac
done
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
echo "  docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod up -d collector provider"
