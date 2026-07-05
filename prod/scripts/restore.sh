#!/bin/bash
# prod/scripts/restore.sh — host-clone reconstructor (§7.10.1 "Migrating to
# another device", M1-567). The other half of pack.sh: on a FRESH target host
# (Docker + a clean checkout at the SAME absolute repo path as the source), it
# unpacks a pack.sh bundle into an exact clone. The DB and BOTH messaging
# identities are restored from the bundle; only the LLM models are
# (re)downloaded. Net effect: the same bot (same Signal number without
# re-registration, same SimpleX contact link so nobody reconnects), the same
# posts/users/audit log, on a new machine.
#
# This automates the manual §7.10 restore steps 1-5, which stay as the
# under-the-hood description. The load-bearing ordering: bring Postgres up ALONE
# and pg_restore the dump into the FRESH database BEFORE the Collector's first
# Flyway pass. Starting the Collector first lets Flyway migrate an empty DB, and
# the dump's schema then collides with the Flyway-migrated one (§7.10 "restore
# into a fresh DB"). So restore.sh cannot lean on the normal 7-apps.sh ordering;
# it sequences Postgres -> pg_restore -> models -> Collector -> Provider itself.
#
# SINGLE-OWNER CUTOVER (operator-observed, NOT lock-enforced): exactly one
# instance may own each messaging identity at a time. The M1-009 advisory lock
# does NOT protect a clone — that lock is per-DATABASE, and the clone restores
# into its OWN restored DB, so the two hosts hold two independent locks and both
# would start. Signal treats signal-cli as the account's single primary device
# and a SimpleX queue has one legitimate owner; two live consumers corrupt
# session/ratchet state. Stop the source before the clone connects; decommission
# the source only after the clone is verified healthy. restore.sh prints this
# reminder at the end (and pack.sh stays read-only precisely so packing a still-
# running source is safe).
set -euo pipefail
# Files this script writes before their explicit modes are set (staging tree
# holding the bundle's secrets, the placed secrets.env) carry secret material;
# 077 makes them 0600/0700 from birth.
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
POSTGRES_SCRIPT="$SCRIPT_DIR/3-postgres.sh"
VERIFY_SCRIPT="$SCRIPT_DIR/8-verify.sh"
COLLECTOR_WAIT_TIMEOUT=300
OLLAMA_WAIT_TIMEOUT=120

# Pinned default GGUFs (M1-417). DUPLICATED from prod/scripts/4-llm.sh: restore
# cannot source 4-llm.sh (it is interactive and rewrites the config restore just
# laid down), and 4-llm.sh's contract is frozen out-of-scope (M1-567). These
# MUST stay in sync with 4-llm.sh's constants — a follow-up ticket should factor
# them into a shared sourced lib both read. Restore recovers a PINNED GGUF by
# matching the persisted filename to *_FILE here and re-fetching from the paired
# *_URL/*_SHA; a CUSTOM GGUF's URL was never persisted by 4-llm.sh (only the
# filename), so on a fresh host restore fails loud rather than guess.
LLAMACPP_GEN_GGUF_FILE="gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf"
LLAMACPP_GEN_GGUF_URL="https://huggingface.co/unsloth/gemma-4-E4B-it-qat-GGUF/resolve/main/gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf"
LLAMACPP_GEN_GGUF_SHA="b3052f962d6449b4eb2075733c068bdec1c51eadb7b237e6c3157bfbb7b1dae0"
LLAMACPP_EMB_GGUF_FILE="nomic-embed-text-v1.5.f16.gguf"
LLAMACPP_EMB_GGUF_URL="https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.f16.gguf"
LLAMACPP_EMB_GGUF_SHA="f7af6f66802f4df86eda10fe9bbcfc75c39562bed48ef6ace719a251cf1c2fdb"
CURL_IMAGE="curlimages/curl:8.11.1"
# The compose-network OpenAI-compatible endpoints (4-llm.sh) restore matches the
# restored config against to decide which local LLM services to (re)provision.
OLLAMA_URL="http://ollama:11434/v1"
LLAMACPP_URL="http://llamacpp:8080/v1"
LLAMACPP_EMBED_URL="http://llamacpp-embeddings:8080/v1"

usage() {
  echo "Usage: restore.sh <bundle.tgz> [-h|--help]"
  echo "  Reconstruct a deployment on a FRESH host from a pack.sh bundle:"
  echo "  place config + secrets + identities, pg_restore the DB into a fresh"
  echo "  database BEFORE Flyway, re-provision models, then start and verify."
  echo "  Run from a clean checkout at the SAME absolute repo path as the source."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  "") echo "FAIL: no bundle given." >&2; usage >&2; exit 2 ;;
esac
BUNDLE="$1"

# Single place the prod compose flags live (upgrade.sh precedent). Used only
# AFTER secrets.env is placed (postgres/apps steps); the early gates below run
# no compose command that needs it.
compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" --profile prod "$@"
}

# Read one value from the dotenv-format secrets.env WITHOUT sourcing it — the
# M1-389/M1-397 footgun. Returns the decoded value on stdout, empty when absent.
# (Verbatim from backup.sh; duplicated because backup.sh's contract is frozen
# out-of-scope, M1-567.)
read_dotenv_value() {
  local key="$1" file="$2" line val
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  [[ -z "$line" ]] && return 0
  val="${line#"${key}"=}"
  if [[ "$val" == \"*\" ]]; then
    val="${val#\"}"; val="${val%\"}"
  fi
  val="${val//\\\$/\$}"
  val="${val//\\\"/\"}"
  val="${val//\\\\/\\}"
  printf '%s' "$val"
}

# Last-wins read of a property from the RESTORED application.properties. Mirrors
# 4-llm.sh's inline `sed -n 's/^key=//p' | tail -n1` idiom (4-llm.sh has a
# set_prop writer but no reader). The key's literal dots are escaped for the sed
# LHS so they match literally, not as any-char.
read_prop() {
  local key="$1" escaped
  escaped="${key//./\\.}"
  sed -n "s/^${escaped}=//p" "$CONFIG_FILE" | tail -n1
}

# Fetch a GGUF into the llama.cpp model volume and verify its SHA-256. Presence
# probe, download, digest, and removal all run in no-shell argv-only containers
# (M1-394). Skip-if-present makes it idempotent. (Verbatim from 4-llm.sh; see
# the pinned-constant note above for why this is duplicated, not sourced.)
fetch_gguf() {
  local url="$1" file="$2" expected="$3" actual
  if docker run --rm -v infochat-llamacpp-models:/models --entrypoint ls "$CURL_IMAGE" "/models/$file" >/dev/null 2>&1; then
    echo "  skip GGUF download ($file already present)"
  else
    echo "  + download $url -> volume infochat-llamacpp-models/$file"
    docker run --rm -u 0:0 -v infochat-llamacpp-models:/models "$CURL_IMAGE" -fL -o "/models/$file" "$url"
  fi
  if [[ -n "$expected" ]]; then
    actual="$(docker run --rm -v infochat-llamacpp-models:/models --entrypoint sha256sum "$CURL_IMAGE" "/models/$file" | awk '{print $1}')"
    expected="$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')"
    if [[ "$actual" != "$expected" ]]; then
      echo "FAIL: GGUF checksum mismatch (expected $expected, got $actual); removing $file." >&2
      docker run --rm -u 0:0 -v infochat-llamacpp-models:/models --entrypoint rm "$CURL_IMAGE" -f "/models/$file"
      exit 1
    fi
    echo "  GGUF checksum verified ($expected)"
  fi
}

# Ensure a llama.cpp GGUF (by persisted filename) is in the model volume. A
# PINNED default is auto-recovered from the constants above; a CUSTOM filename
# has no recoverable URL (4-llm.sh persists only the filename), so unless the
# model volume survived, restore fails loud with the actionable fix (Risks #2).
ensure_gguf() {
  local file="$1"
  case "$file" in
    "$LLAMACPP_GEN_GGUF_FILE") fetch_gguf "$LLAMACPP_GEN_GGUF_URL" "$file" "$LLAMACPP_GEN_GGUF_SHA" ;;
    "$LLAMACPP_EMB_GGUF_FILE") fetch_gguf "$LLAMACPP_EMB_GGUF_URL" "$file" "$LLAMACPP_EMB_GGUF_SHA" ;;
    *)
      if docker run --rm -v infochat-llamacpp-models:/models --entrypoint ls "$CURL_IMAGE" "/models/$file" >/dev/null 2>&1; then
        echo "  GGUF $file already in the model volume (custom; skipping fetch)"
      else
        echo "FAIL: $file is a CUSTOM llama.cpp GGUF whose download URL was never persisted" >&2
        echo "      (4-llm.sh stores only the filename). Fetch it manually into the" >&2
        echo "      'infochat-llamacpp-models' Docker volume, or re-run prod/setup.sh step 4" >&2
        echo "      (4-llm.sh) on this host to re-provision, then re-run restore.sh." >&2
        echo "      Pinned default GGUFs are auto-recovered; only custom overrides need this." >&2
        exit 1
      fi
      ;;
  esac
}

# ── preconditions: fail LOUD and EARLY, before any mutation ─────────────
if ! command -v docker >/dev/null 2>&1; then
  echo "FAIL: docker not found on PATH." >&2; exit 1
fi
if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "FAIL: compose file not found: $COMPOSE_FILE (run restore.sh from the project checkout)." >&2; exit 1
fi
if [[ ! -f "$BUNDLE" ]]; then
  echo "FAIL: bundle not found: $BUNDLE" >&2; exit 1
fi

# Target must be FRESH. A pre-existing secrets.env means this host already has a
# configured deployment; restore would clobber it. Refuse rather than overwrite
# (mirrors the "restore onto a fresh host" contract).
if [[ -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE already exists — this host is already configured." >&2
  echo "      restore.sh reconstructs a clone on a FRESH host. Use a clean host, or" >&2
  echo "      tear this deployment down first (prod/setup.sh --reset --hard removes the DB)." >&2
  exit 1
fi

# Unpack the outer archive into a temp staging tree (reversible — cleaned on any
# exit). All remaining gates read from staging so nothing on the target is
# touched until every precondition passes.
STAGING="$(mktemp -d "$PROD_DIR/.infochat-restore.XXXXXX")"
trap 'rm -rf "$STAGING"' EXIT
echo "+ unpack bundle -> staging"
if ! tar -C "$STAGING" -xzf "$BUNDLE"; then
  echo "FAIL: could not unpack $BUNDLE (corrupt or not a pack.sh bundle)." >&2
  exit 1
fi

# Bundle integrity: the pieces a clone cannot be reconstructed without. A
# missing piece means a truncated/corrupt/incompatible bundle — fail loud.
DB_DUMP="$STAGING/db/infochat.pgc"
STAGED_SECRETS="$STAGING/runtime/secrets.env"
STAGED_CONFIG="$STAGING/runtime/application.properties"
STAGED_SOURCES="$STAGING/runtime/bootstrap-sources.json"
STAGED_IDENTITIES="$STAGING/identities.tgz"
for required in "$DB_DUMP" "$STAGED_SECRETS" "$STAGED_CONFIG" "$STAGED_SOURCES" "$STAGED_IDENTITIES"; do
  if [[ ! -f "$required" ]]; then
    echo "FAIL: bundle is missing ${required#"$STAGING"/} — corrupt or not a pack.sh bundle." >&2
    exit 1
  fi
done

# Target Postgres must be FRESH. docker/postgres-init.sh bakes the service-role
# passwords from secrets.env only on FIRST volume init; a pre-existing
# infochat-pgdata volume would keep its OLD passwords (the restored secrets would
# not authenticate) AND already hold data (pg_restore would collide). The
# name-substring filter is project-name independent (the volume is compose-
# namespaced <project>_infochat-pgdata). This one gate covers both the
# password-mismatch and non-empty-DB preconditions (§Acceptance item 4).
if [[ -n "$(docker volume ls --filter name=infochat-pgdata -q)" ]]; then
  echo "FAIL: a Postgres data volume (*infochat-pgdata) already exists on this host." >&2
  echo "      restore.sh requires a FRESH database so the restored secrets.env passwords" >&2
  echo "      are baked at first init and the dump does not collide with existing data." >&2
  echo "      Remove it (docker volume rm) on a spare host, or use a clean host." >&2
  exit 1
fi

# Same-absolute-path constraint (v1). identities.tgz stores each adapter dir
# relative to / (pack.sh: tar -C /), so a configured INFOCHAT_<NAME>_DATA_DIR
# =/a/b/c must appear as an 'a/b/c/' entry in the tar. They agree by construction
# when pack.sh built the bundle; a MISMATCH means a hand-edited bundle or an
# attempted relocation to a different absolute path — which v1 does not support
# (§7.10.1). Fail loud rather than restore the identity to one path while the
# config expects another (a silent adapter breakage).
tar_entries="$(tar -tzf "$STAGED_IDENTITIES")"
for key in INFOCHAT_SIMPLEX_DATA_DIR INFOCHAT_SIGNAL_DATA_DIR; do
  dir="$(read_dotenv_value "$key" "$STAGED_SECRETS")"
  [[ -z "$dir" ]] && continue
  rel="${dir#/}"
  if ! printf '%s\n' "$tar_entries" | grep -qF "$rel/"; then
    echo "FAIL: identity path mismatch — $key=$dir has no matching entry in the bundle." >&2
    echo "      v1 requires the clone to reconstruct identity dirs at the SAME absolute" >&2
    echo "      path; relocating to a different path is a follow-up (§7.10.1). Aborted" >&2
    echo "      before any change to this host." >&2
    exit 1
  fi
done

echo "all preconditions passed; reconstructing the clone."

# ── place config, secrets, identities (mutation begins) ─────────────────
mkdir -p "$RUNTIME_DIR"
echo "+ place application.properties + secrets.env + bootstrap files -> $RUNTIME_DIR"
cp -p "$STAGED_CONFIG" "$CONFIG_FILE"
cp -p "$STAGED_SECRETS" "$SECRETS_FILE"
chmod 600 "$SECRETS_FILE"
cp -p "$STAGED_SOURCES" "$RUNTIME_DIR/bootstrap-sources.json"
if [[ -f "$STAGING/runtime/bootstrap-assets.json" ]]; then
  cp -p "$STAGING/runtime/bootstrap-assets.json" "$RUNTIME_DIR/bootstrap-assets.json"
fi

# Reconstruct each adapter identity dir at its original absolute path with modes
# preserved (-p) — both clients reject world-readable keys. This is what makes
# the clone the SAME bot. (Mirrors the §7.10 restore step `tar -xzpf ... -C /`.)
echo "+ restore adapter identities (-C /, modes preserved)"
tar -C / -xzpf "$STAGED_IDENTITIES"

# ── Postgres up ALONE, then pg_restore BEFORE Flyway ────────────────────
# 3-postgres.sh brings up only postgres and waits healthy; on this fresh volume
# postgres-init.sh mints the roles + empty `infochat` DB + extensions from the
# just-placed secrets.env passwords. The Collector (which runs Flyway) stays down
# until the dump is loaded.
echo "+ start Postgres alone (3-postgres.sh)"
"$POSTGRES_SCRIPT"

# pg_restore the custom-format dump into the fresh `infochat` DB, in-container as
# the owner (mirrors backup.sh's pg_dump exec; no host Postgres client, no secret
# on the host). The DB already has vector/pgcrypto (postgres-init) and the dump
# also carries them, so pg_restore may log a few "already exists" notices — those
# are expected and non-fatal, so we do NOT pass --exit-on-error and we capture the
# status instead of letting `set -e` abort on an ignorable notice. Correctness is
# then confirmed by the schema-landed check below, independent of the exit code.
echo "+ pg_restore db/infochat.pgc into the fresh infochat DB (before Flyway)"
set +e
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" pg_restore -h 127.0.0.1 -U infochat --no-owner -d infochat' \
  < "$DB_DUMP"
restore_status=$?
set -e

# Confirm the restore actually landed the schema: a migrated infochat DB always
# has tables in `public` (app tables + flyway_schema_history). Empty means the
# restore did not populate the DB — a real failure regardless of pg_restore's
# exit code (which tolerates the ignorable extension notices above). `\dt` needs
# no SQL string literal, avoiding nested-quote hazards.
placed_tables="$(docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" psql -h 127.0.0.1 -U infochat -d infochat -tAqc "\dt"' 2>/dev/null || true)"
if [[ -z "$placed_tables" ]]; then
  echo "FAIL: pg_restore did not populate any tables (pg_restore exit was $restore_status)." >&2
  echo "      The dump may be corrupt or from an incompatible schema. Restore aborted." >&2
  exit 1
fi
echo "  DB restored (schema present)."

# ── re-provision models from the RESTORED backend config ────────────────
# Idempotent, and WITHOUT re-running the interactive 4-llm.sh (which would
# re-prompt and rewrite the config restore just laid down). The backend is read
# back from the restored config's endpoints (4-llm.sh chooses it by prompt; here
# there is no operator, so we infer it from what was persisted).
rehydrate_models() {
  local chat_url emb_url gen_backend emb_backend
  chat_url="$(read_prop 'infochat.llm.chat.base-url')"
  emb_url="$(read_prop 'infochat.embeddings.base-url')"

  case "$chat_url" in
    "$OLLAMA_URL")   gen_backend=ollama ;;
    "$LLAMACPP_URL") gen_backend=llamacpp ;;
    *)               gen_backend=remote ;;
  esac
  case "$emb_url" in
    "$OLLAMA_URL")        emb_backend=ollama ;;
    "$LLAMACPP_EMBED_URL") emb_backend=llamacpp ;;
    *)
      echo "FAIL: restored infochat.embeddings.base-url is neither the Ollama nor the" >&2
      echo "      llama.cpp-embeddings endpoint ('$emb_url'). The config looks corrupt." >&2
      exit 1
      ;;
  esac
  echo "  generative backend: $gen_backend; embeddings backend: $emb_backend"

  # llama.cpp GGUFs first (into the volume; the servers load them on start), then
  # start each llama.cpp server it needs (mirrors 4-llm.sh's fetch-then-up order).
  if [[ "$gen_backend" == "llamacpp" ]]; then
    ensure_gguf "$(read_dotenv_value INFOCHAT_LLAMACPP_GGUF "$SECRETS_FILE")"
    echo "  + start llama.cpp generative server"
    compose --profile llamacpp up -d llamacpp
  fi
  if [[ "$emb_backend" == "llamacpp" ]]; then
    ensure_gguf "$(read_dotenv_value INFOCHAT_LLAMACPP_EMBED_GGUF "$SECRETS_FILE")"
    echo "  + start llama.cpp embeddings server"
    compose --profile llamacpp-embeddings up -d llamacpp-embeddings
  fi

  # Ollama models (generative and/or embeddings). Collect the unique set, start
  # the daemon, wait until it answers, then pull each (ollama pull is idempotent).
  local ollama_models=()
  if [[ "$gen_backend" == "ollama" ]]; then
    ollama_models+=("$(read_prop 'infochat.llm.security.model')")
    ollama_models+=("$(read_prop 'infochat.llm.chat.model')")
  fi
  if [[ "$emb_backend" == "ollama" ]]; then
    ollama_models+=("$(read_prop 'infochat.embeddings.model')")
  fi
  if [[ "${#ollama_models[@]}" -gt 0 ]]; then
    echo "  + start ollama daemon"
    compose --profile ollama up -d ollama
    echo "  + wait for ollama daemon (up to ${OLLAMA_WAIT_TIMEOUT}s)"
    local deadline=$(( SECONDS + OLLAMA_WAIT_TIMEOUT ))
    until compose --profile ollama exec -T ollama ollama list >/dev/null 2>&1; do
      if (( SECONDS >= deadline )); then
        echo "FAIL: ollama daemon not ready after ${OLLAMA_WAIT_TIMEOUT}s." >&2
        exit 1
      fi
      sleep 2
    done
    local model
    for model in $(printf '%s\n' "${ollama_models[@]}" | sort -u); do
      [[ -z "$model" ]] && continue
      echo "  + ollama pull $model"
      compose --profile ollama exec -T ollama ollama pull "$model"
    done
  fi

  if [[ "$gen_backend" == "remote" ]]; then
    echo "  generative backend is remote — no model download (embeddings handled above)."
  fi
}
echo "+ re-provision models from the restored backend config"
rehydrate_models

# ── build images, then start Collector (healthy) then Provider ──────────
# Mirrors 7-apps.sh's build -> collector(--wait) -> provider ordering, but does
# NOT run 6b-simplex-provision.sh: that step MINTS a SimpleX identity, and a
# clone already has the identity restored from the bundle — re-provisioning could
# create a NEW SimpleX address and break the "same contact link" clone guarantee.
echo "+ build app images"
compose build infochat-collector infochat-provider
echo "+ start Collector (wait up to ${COLLECTOR_WAIT_TIMEOUT}s for healthy — it runs Flyway, an idempotent no-op over the restored schema)"
compose up -d --wait --wait-timeout "$COLLECTOR_WAIT_TIMEOUT" infochat-collector
echo "+ start Provider"
compose up -d infochat-provider

# ── verify (§7.10 step 5, split per the clarity WARN) ───────────────────
# The HTTP-health part is automatable (8-verify.sh polls /q/health on both apps
# and surfaces a per-adapter DEGRADED). The /audit and /summary checks are
# bot-chat slash commands reachable only through a live Signal/SimpleX
# conversation, so they are printed as a manual operator follow-up rather than
# shelled out. A health failure is surfaced but does not abort the reminder below.
echo "+ verify deployment health (8-verify.sh)"
verify_status=0
"$VERIFY_SCRIPT" || verify_status=$?

cat <<'DONE'

========================================================================
  CLONE RECONSTRUCTED
  Manual verification (bot-chat, §7.10 step 5 — run from a configured admin):
    - /audit    shows recent events
    - /summary  returns content
    - each enabled adapter reaches adapter.connection.status=1
  (The HTTP health of both apps was just checked automatically above.)

  SINGLE-OWNER CUTOVER — do this in order:
    1. Stop the SOURCE host so it no longer touches Signal/SimpleX (the M1-009
       advisory lock does NOT span the two hosts — it is per-database, and this
       clone has its own restored DB).
    2. Confirm THIS clone is healthy (above + the manual checks).
    3. Only THEN decommission the source (apps.sh stop; optionally
       setup.sh --reset --hard).
  Two live instances on one messaging identity corrupt session/ratchet state.
========================================================================
DONE

if [[ "$verify_status" -ne 0 ]]; then
  echo "NOTE: automated health verification reported a problem (see above) — resolve before cutover." >&2
  exit "$verify_status"
fi
echo "restore complete."
