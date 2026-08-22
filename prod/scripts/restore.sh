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
# the source only after the clone is verified healthy. The Provider is the
# identity consumer, so its start is GATED on operator consent — an interactive
# y/N defaulting to No, or --source-stopped for unattended runs (M1-582); the
# Collector holds no messaging identity and starts ungated. The cutover-order
# reminder still prints at the end. (pack.sh never mutates the source, but the
# RECOMMENDED order is stop-first even for packing — a live pack can tar the
# identity stores mid-write; see pack.sh's header.)
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
# The identity untar runs as root inside a throwaway container (M1-569): the source
# dirs are root:root and signal-cli's store is 0700, so a non-root host untar cannot
# recreate the ownership/modes the daemons require. Same in-container-privilege
# pattern as pg_restore — no interactive sudo. Only needs GNU tar (busybox tar
# under-preserves modes); reuse the pinned postgres image the deployment already
# requires (`docker run` pulls it just-in-time, then the postgres service reuses the
# cached image). Any GNU-tar image works, so drift from the compose tag is harmless.
IDENTITY_TAR_IMAGE="pgvector/pgvector:pg16"
# The compose-network OpenAI-compatible endpoints (4-llm.sh) restore matches the
# restored config against to decide which local LLM services to (re)provision.
OLLAMA_URL="http://ollama:11434/v1"
LLAMACPP_URL="http://llamacpp:8080/v1"
LLAMACPP_EMBED_URL="http://llamacpp-embeddings:8080/v1"

usage() {
  echo "Usage: restore.sh [--source-stopped] <bundle.tgz> [-h|--help]"
  echo "  Reconstruct a deployment on a FRESH host from a pack.sh bundle:"
  echo "  place config + secrets + identities, pg_restore the DB into a fresh"
  echo "  database BEFORE Flyway, re-provision models, then start and verify."
  echo "  Run from a clean checkout at the SAME absolute repo path as the source."
  echo "  --source-stopped  assert the SOURCE host's apps are stopped: skip the"
  echo "                    interactive single-owner prompt before the Provider"
  echo "                    start (required for unattended/non-TTY runs)."
}

# Mirrors shred-bundle.sh's flag loop — the house pattern for consent-carrying
# scripts (M1-582).
SOURCE_STOPPED=no
BUNDLE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --source-stopped) SOURCE_STOPPED=yes; shift ;;
    *)
      if [[ -n "$BUNDLE" ]]; then
        echo "FAIL: exactly one bundle expected (got '$BUNDLE' and '$1')." >&2
        usage >&2
        exit 2
      fi
      BUNDLE="$1"; shift ;;
  esac
done
if [[ -z "$BUNDLE" ]]; then
  echo "FAIL: no bundle given." >&2; usage >&2; exit 2
fi

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

# Boundary validation (M1-584) for an operator-configured adapter data-dir before
# it becomes a writable identity bind-mount target. Two refusals:
#   - ':' — docker's -v mount-spec separator; a data-dir containing one yields a
#     mis-parsed `-v` and an obscure docker error, so refuse with a named message.
#   - a clearly-system prefix — under the M1-569 ROOT in-container untar the mount
#     `-v /$rel:/$rel` is writable, so a value like /etc/cron.d would let the root
#     tar write root-owned files onto the host. This denylist restores an explicit
#     equivalent of the EACCES property M1-568's non-root untar gave for free. A
#     COHERENTLY tampered bundle (matching secrets.env + tar member) is still
#     out-of-model — supply-chain, excluded by security.md, and game-over via the
#     DB creds regardless; this is the cheap M1-568/M1-565 defense-in-depth gate,
#     a denylist of clearly-system prefixes being the widest gate that breaks no
#     legitimate operator layout. Literal-prefix match (no realpath): the EACCES
#     equivalent it restores only ever protected the direct system path too.
SYSTEM_DATA_DIR_PREFIXES=(/etc /root /boot /bin /sbin /lib /lib64 /dev /proc /sys /var/lib/docker)
reject_unsafe_data_dir() {
  local key="$1" dir="$2" prefix
  if [[ "$dir" == *:* ]]; then
    echo "FAIL: $key=$dir contains ':' — docker's -v mount-spec separator; an identity" >&2
    echo "      data-dir must not contain a colon (it would mis-parse the bind-mount)." >&2
    exit 1
  fi
  for prefix in "${SYSTEM_DATA_DIR_PREFIXES[@]}"; do
    if [[ "$dir" == "$prefix" || "$dir" == "$prefix"/* ]]; then
      echo "FAIL: $key=$dir resolves under the system prefix $prefix — refusing to build a" >&2
      echo "      writable identity mount there. Configure adapter data-dirs outside system" >&2
      echo "      directories (${SYSTEM_DATA_DIR_PREFIXES[*]})." >&2
      exit 1
    fi
  done
}

# The one true return-to-FRESH recipe, shared by the already-configured gate and
# the post-mutation partial-state note (M1-581) so both name the SAME actionable
# fix. setup.sh --reset --hard is NOT this recipe: its do_reset removes
# containers/network/pgdata (and, opted-in, model caches) only — secrets.env
# survives, so restore.sh's fresh-host gate still refuses, and setup.sh then
# falls through into the interactive wizard. PRINTED, never executed: removing
# identity material is the operator's deliberate act — it may be the only copy
# on this host (M1-581 out-of-scope: no auto-cleanup).
print_fresh_host_recipe() {
  {
    echo "      To return this host to FRESH so restore.sh can run:"
    echo "        (these steps DESTROY this host's deployment state — only proceed if it"
    echo "        is a failed/aborted restore or otherwise disposable)"
    echo "        1. remove the placed runtime files: secrets.env, application.properties,"
    echo "           and the bootstrap-*.json files under $RUNTIME_DIR"
    echo "        2. remove each restored adapter identity dir (the INFOCHAT_*_DATA_DIR"
    echo "           paths in secrets.env; root-owned, so use a root container):"
    echo "             docker run --rm -u 0:0 -v /<data-dir>:/<data-dir> \\"
    echo "               --entrypoint rm $IDENTITY_TAR_IMAGE -rf /<data-dir>"
    echo "        3. remove the Postgres data volume:"
    echo "             docker volume rm \"\$(docker volume ls --filter name=infochat-pgdata -q)\""
    echo "      (setup.sh --reset --hard is NOT this recipe: it keeps secrets.env — the"
    echo "      fresh-host gate would still refuse — and launches the interactive setup wizard.)"
  } >&2
}

# What the operator does after the single-owner gate below withholds the
# Provider start (M1-582). NOT a failure recipe: the clone's data is complete
# and healthy at that point — only the identity-consuming Provider was withheld
# until the source host is stopped.
print_provider_withheld_note() {
  {
    echo "      The clone's data is fully in place (DB restored, identities placed) and"
    echo "      the Collector is up — only the Provider, the messaging-identity consumer,"
    echo "      was withheld. Once the SOURCE host's apps are stopped (on it:"
    echo "      prod/scripts/apps.sh stop), start the Provider here and verify:"
    echo "        docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod up -d infochat-provider"
    echo "        $VERIFY_SCRIPT"
    echo "      Unattended runs assert the source is stopped up front with:"
    echo "        restore.sh --source-stopped <bundle>"
  } >&2
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
    # No-shell host-netns download: the fetch uses the path the host's own
    # network proves, with the host's proxy env forwarded name-only (unset
    # vars are silently omitted, set -u safe).
    if ! docker run --rm -u 0:0 --network host -e HTTP_PROXY -e HTTPS_PROXY -e ALL_PROXY -e NO_PROXY -v infochat-llamacpp-models:/models "$CURL_IMAGE" -fL -o "/models/$file" "$url"; then
      echo "FAIL: download of $url failed over the host's own network path (the path the download uses)." >&2
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
    echo "  GGUF checksum verified ($expected)"
  fi
}

# Ensure a llama.cpp GGUF is in the model volume. A PINNED default is auto-recovered
# from the constants above; a CUSTOM GGUF is recovered from the URL + SHA that 4-llm.sh
# persisted into secrets.env (INFOCHAT_LLAMACPP_GGUF_URL/_SHA, M1-571), which the caller
# passes in. An OLDER bundle (pre-M1-571) has no persisted URL, so a custom GGUF that is
# not already in the (surviving) model volume still fails loud with the actionable fix.
# $1 filename, $2 persisted-url ("" if none), $3 persisted-sha ("" to skip integrity).
ensure_gguf() {
  local file="$1" persisted_url="$2" persisted_sha="$3"
  case "$file" in
    "$LLAMACPP_GEN_GGUF_FILE") fetch_gguf "$LLAMACPP_GEN_GGUF_URL" "$file" "$LLAMACPP_GEN_GGUF_SHA" ;;
    "$LLAMACPP_EMB_GGUF_FILE") fetch_gguf "$LLAMACPP_EMB_GGUF_URL" "$file" "$LLAMACPP_EMB_GGUF_SHA" ;;
    *)
      if docker run --rm -v infochat-llamacpp-models:/models --entrypoint ls "$CURL_IMAGE" "/models/$file" >/dev/null 2>&1; then
        echo "  GGUF $file already in the model volume (custom; skipping fetch)"
      elif [[ -n "$persisted_url" ]]; then
        # Custom GGUF recovered from the URL 4-llm.sh persisted at setup (M1-571). fetch_gguf
        # verifies the SHA when non-empty; an empty SHA keeps the operator-trusted TLS fetch.
        echo "  recovering custom GGUF $file from persisted URL (M1-571)"
        fetch_gguf "$persisted_url" "$file" "$persisted_sha"
      else
        # No setup.sh/4-llm.sh advice here: 4-llm.sh would re-prompt and rewrite the
        # config this restore just laid down (see rehydrate_models), and the fresh-host
        # gates make a plain restore.sh re-run impossible — the old message advised
        # exactly that dead end (M1-581).
        echo "FAIL: $file is a CUSTOM llama.cpp GGUF whose download URL was never persisted" >&2
        echo "      (bundle predates M1-571; 4-llm.sh stored only the filename). Fetch the" >&2
        echo "      GGUF manually into the 'infochat-llamacpp-models' Docker volume:" >&2
        echo "        docker run --rm -u 0:0 --network host -e HTTP_PROXY -e HTTPS_PROXY -e ALL_PROXY -e NO_PROXY \\" >&2
        echo "          -v infochat-llamacpp-models:/models \\" >&2
        echo "          $CURL_IMAGE -fL -o \"/models/$file\" \"<your-gguf-url>\"" >&2
        echo "      then EITHER return this host to fresh (recipe in the partial-state note" >&2
        echo "      below) and re-run restore.sh — the fetched model survives in its volume" >&2
        echo "      and is reused — OR finish bring-up manually in restore.sh's own order" >&2
        echo "      (each command carries -f, --env-file, and the profiles restore.sh uses):" >&2
        echo "        docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile llamacpp up -d llamacpp" >&2
        echo "        docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod --profile llamacpp-embeddings up -d llamacpp-embeddings" >&2
        echo "        docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod build infochat-collector infochat-provider" >&2
        echo "        docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod up -d --wait --wait-timeout $COLLECTOR_WAIT_TIMEOUT infochat-collector" >&2
        echo "        docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE --profile prod up -d infochat-provider" >&2
        echo "        $VERIFY_SCRIPT" >&2
        echo "      Pinned default GGUFs are auto-recovered; only custom overrides from a" >&2
        echo "      pre-M1-571 bundle need this." >&2
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
  echo "      return this one to fresh first:" >&2
  print_fresh_host_recipe
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
# Same rel paths ("${dir#/}", matching pack.sh's adapter_rel_paths) drive both the
# consistency gate here AND the extraction allowlist below (M1-568) — one source
# of truth so a validated dir is exactly a dir we extract.
#
# The match is EXACT-LINE (grep -x): pack.sh names each data-dir as a tar member,
# so GNU tar always lists exactly 'a/b/c/'. A substring match would false-pass a
# hand-repacked bundle whose members sit under a prefix (backup/a/b/c/ contains
# 'a/b/c/' as a substring), deferring the inevitable failure to the extraction
# step — AFTER mutation began, breaking this gate's "Aborted before any change"
# promise (M1-581).
identity_rel_paths=()
for key in INFOCHAT_SIMPLEX_DATA_DIR INFOCHAT_SIGNAL_DATA_DIR; do
  dir="$(read_dotenv_value "$key" "$STAGED_SECRETS")"
  [[ -z "$dir" ]] && continue
  # M1-584: refuse a colon or clearly-system data-dir BEFORE any mount is built —
  # ahead of the tar-consistency grep, so an obviously-dangerous value is rejected
  # without doing the listing work (and the FAIL names the offending key).
  reject_unsafe_data_dir "$key" "$dir"
  rel="${dir#/}"
  if ! printf '%s\n' "$tar_entries" | grep -qxF -- "$rel/"; then
    echo "FAIL: identity path mismatch — $key=$dir has no matching entry in the bundle." >&2
    echo "      v1 requires the clone to reconstruct identity dirs at the SAME absolute" >&2
    echo "      path; relocating to a different path is a follow-up (§7.10.1). Aborted" >&2
    echo "      before any change to this host." >&2
    exit 1
  fi
  identity_rel_paths+=("$rel")
done

echo "all preconditions passed; reconstructing the clone."

# ── partial-state note: mutation begins below (M1-581) ──────────────────
# From the first cp onward a failure leaves this host partially restored, and
# the fresh-host gates refuse a plain re-run — so every post-mutation failure
# must tell the operator what landed and how to retry. Two hooks cover bash's
# two failure shapes: the ERR trap fires on set -e aborts (an unguarded command
# failing; set -E extends it into functions), the EXIT-status hook on the
# script's own explicit `exit 1` fail-loud paths (an explicit exit never raises
# ERR). The flag single-prints the note when both fire for one failure. The
# note DELETES NOTHING — restored identity material may be the only copy on
# this host; the operator executes the recipe deliberately (M1-581
# out-of-scope: no auto-cleanup).
PLACED=()
partial_note_printed=0
print_partial_state_note() {
  [[ "$partial_note_printed" -eq 1 ]] && return 0
  partial_note_printed=1
  {
    echo ""
    echo "PARTIAL RESTORE: this run failed after mutation began. Placed so far:"
    local item
    for item in "${PLACED[@]}"; do
      echo "  - $item"
    done
    echo "      Nothing was deleted automatically — inspect before removing anything;"
    echo "      the restored identity dirs may be the only copy on this host."
  } >&2
  print_fresh_host_recipe
  {
    echo ""
    echo "      HOW TO VERIFY what landed before teardown or retry:"
    echo "        $VERIFY_SCRIPT"
    echo "      A failing Collector names its cause in its log"
    echo "      (docker compose logs infochat-collector):"
    echo "        - FlywayValidateException = the dump's applied migrations drift from this"
    echo "          checkout (any earlier Flyway-history check names the drifted versions)"
    echo "        - 'no password was provided' (SCRAM) on a MANUAL bring-up = compose was"
    echo "          started without --env-file $SECRETS_FILE, so the \${INFOCHAT_*_PASSWORD:-}"
    echo "          pass-throughs blank out (restore.sh's own compose calls always carry"
    echo "          --env-file)"
  } >&2
}
set -E
trap 'print_partial_state_note' ERR
trap 'status=$?; rm -rf "$STAGING"; if [[ "$status" -ne 0 ]]; then print_partial_state_note; fi' EXIT

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
PLACED+=("config + secrets + bootstrap files in $RUNTIME_DIR")

# Reconstruct each adapter identity dir at its original absolute path with OWNERSHIP
# and modes preserved — both clients reject world-readable keys, and signal-cli's
# account store is root:root 0700. This is what makes the clone the SAME bot.
#
# Run the untar as ROOT inside a throwaway container (M1-569): a non-root host untar
# cannot recreate root:root ownership, so the restored identity would not match the
# source and the signal-cli daemon would reject it. Bind-mount each allowlisted
# data-dir READ-WRITE at its absolute host path (exactly how the Provider mounts
# them, docker-compose.yml adapter volumes); `tar -C /` inside the container writes
# THROUGH the mount to the host path, so ONLY the mounted data-dirs are writable on
# the host — the container's own `/` is ephemeral (--rm). `--entrypoint tar` bypasses
# the postgres image's default entrypoint (the fetch_gguf precedent).
#
# Extract naming ONLY the allowlisted data-dir paths validated by the gate above:
# GNU tar extracts each named directory member recursively and IGNORES every other
# member. For a bundle whose secrets.env is HONEST, the named-member allowlist plus
# the mount scoping below bound the EXTRA members a tampered identities.tgz might
# smuggle (etc/cron.d/..., root/.ssh/...): they are not among the named members, so
# they are never extracted. What this does NOT stop is a COHERENTLY tampered bundle:
# the writable mount target `-v /$rel:/$rel` is derived from the SAME
# attacker-controlled secrets.env value the allowlist is built from, so a tamper that
# sets INFOCHAT_<NAME>_DATA_DIR=/etc/cron.d with a matching tar member passes the
# consistency gate and the ROOT tar (M1-569) writes there — the M1-568-era incidental
# backstop (a NON-root untar dying on EACCES against root-owned system dirs) no longer
# exists. The reject_unsafe_data_dir denylist above restores an explicit equivalent of
# that EACCES property for the clearly-system prefixes; a coherently tampered bundle
# stays an out-of-model, supply-chain concern either way (security.md keeps the bundle
# trusted). So the allowlist is LOAD-BEARING for the honest-config extra-member case,
# NOT a guarantee against a coherent tamper — this comment must not over-claim.
# The empty-array guard stays load-bearing: `tar -x` with NO members extracts
# EVERYTHING, which would reopen exactly the hole this closes — so a bundle that
# carries identities.tgz but names no configured adapter is a malformed/tampered
# bundle and is rejected rather than extract-all'd.
echo "+ restore adapter identities as root in-container (-C /, ownership+modes preserved, allowlisted paths only)"
if [[ "${#identity_rel_paths[@]}" -eq 0 ]]; then
  echo "FAIL: bundle carries identities.tgz but secrets.env names no configured adapter" >&2
  echo "      data-dir — a malformed or tampered bundle. Refusing to extract (an empty" >&2
  echo "      allowlist would extract every member). Aborted." >&2
  exit 1
fi
tar_mounts=()
for rel in "${identity_rel_paths[@]}"; do
  tar_mounts+=(-v "/$rel:/$rel")
done
docker run --rm -u 0:0 -i "${tar_mounts[@]}" --entrypoint tar "$IDENTITY_TAR_IMAGE" \
  -C / -xzpf - "${identity_rel_paths[@]}" < "$STAGED_IDENTITIES"
for rel in "${identity_rel_paths[@]}"; do
  PLACED+=("identity dir /$rel (root-owned)")
done

# ── Postgres up ALONE, then pg_restore BEFORE Flyway ────────────────────
# 3-postgres.sh brings up only postgres and waits healthy; on this fresh volume
# postgres-init.sh mints the roles + empty `infochat` DB + extensions from the
# just-placed secrets.env passwords. The Collector (which runs Flyway) stays down
# until the dump is loaded.
echo "+ start Postgres alone (3-postgres.sh)"
"$POSTGRES_SCRIPT"
PLACED+=("Postgres data volume (fresh init; service-role passwords baked from restored secrets)")

# ── reconstruct the Flyway-created infochat_admin role BEFORE pg_restore ──
# A single-database `pg_dump -F c` (pack.sh) carries NO cluster-global roles, so the
# NOLOGIN principal infochat_admin — created by Flyway V2 (V2__roles.sql), NOT by
# postgres-init.sh (which mints only infochat + infochat_collector + infochat_provider)
# — is absent on this fresh target. The dump's ACL entries that GRANT to infochat_admin
# would then fail "role does not exist"; and because pg_dump emits each object's whole
# GRANT/REVOKE set as ONE multi-statement command, that failure atomically ROLLS BACK the
# co-located infochat_collector / infochat_provider grants (heartbeat, source, quarantine,
# invite_code_attempt, audit_log_view, the quarantine functions) too — the Collector then
# dies on its first heartbeat write. Flyway is a no-op over the restored (already-V56)
# history and never repairs it, so the role must exist BEFORE pg_restore, not after (M1-570).
# `infochat` is CREATEROLE (postgres-init), so no superuser is needed; NOLOGIN means no
# password/secret. Idempotent (NOT EXISTS guard, mirroring V2 __roles). ON_ERROR_STOP makes
# a failed creation fatal under `set -e`. SQL on stdin (a quoted heredoc) sidesteps the
# nested $$/'literal' quoting hazards of an inline -c. NB: v1 has exactly one Flyway-created
# principal — add any future ones to this DO block.
echo "+ reconstruct Flyway-created infochat_admin role (before pg_restore)"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" psql -h 127.0.0.1 -U infochat -d infochat -v ON_ERROR_STOP=1' <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'infochat_admin') THEN
    CREATE ROLE infochat_admin NOLOGIN;
  END IF;
END $$;
SQL

# pg_restore the custom-format dump into the fresh `infochat` DB, in-container as
# the owner (mirrors backup.sh's pg_dump exec; no host Postgres client, no secret
# on the host). postgres-init pre-creates vector/pgcrypto (owned by the bootstrap
# superuser), so the dump's COMMENT ON EXTENSION statements, restored as the
# non-owner `infochat`, always fail "must be owner of extension" — expected and
# harmless (the extensions exist; only the cosmetic comments are skipped). We
# therefore do NOT pass --exit-on-error, but the tolerance is BOUNDED (M1-580):
# stderr is captured to a file while tee'd live to the console, and a non-zero
# exit is accepted ONLY when every error line matches that enumerable ignorable
# set — anything else aborts below, BEFORE image build and bring-up. The fd
# shuffle keeps stdout on the console untouched; only stderr flows through tee
# into the capture file, then back to the console via >&2.
echo "+ pg_restore db/infochat.pgc into the fresh infochat DB (before Flyway)"
PG_RESTORE_STDERR="$STAGING/pg_restore.stderr"
# The ERR hook fires on ANY failing command regardless of set +e, so it must be
# disarmed around this deliberately-tolerated non-zero exit: pg_restore exiting 1
# with only the two extension-COMMENT notices is a HEALTHY restore (M1-580), and
# a partial-state note here would be false. A REAL failure is re-raised by the
# bounded gate below as exit 1, which the EXIT-status hook reports — after the
# gate's own FAIL message, in reading order (M1-581).
trap - ERR
set +e
{
  docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
    sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" pg_restore -h 127.0.0.1 -U infochat --no-owner -d infochat' \
    < "$DB_DUMP" 2>&1 1>&3 | tee "$PG_RESTORE_STDERR" >&2
  restore_status=${PIPESTATUS[0]}
} 3>&1
set -e
trap 'print_partial_state_note' ERR

# Bounded error gate (M1-580): on a non-zero exit, every line pg_restore itself
# flagged as an error must match the known-ignorable set — currently exactly the
# two extension-COMMENT ownership notices the 2026-07-05 live round-trip
# produced. Context lines ("Command was: ...", the closing "errors ignored on
# restore: N" warning) are not error lines and are not gated. LC_ALL=C pins
# byte-wise matching regardless of host locale (the in-container pg_restore
# comes from the pinned pgvector/pgvector:pg16 image, so the message strings are
# stable). Ignored notices are always printed with their count — never silenced
# — and any residue, or a non-zero exit with NO recognizable pg_restore error
# line (e.g. the compose transport itself died), fails the restore loud.
if [[ "$restore_status" -ne 0 ]]; then
  IGNORABLE_RESTORE_ERRORS='^pg_restore: error: could not execute query: ERROR:[[:space:]]+must be owner of extension (pgcrypto|vector)$'
  ignored_errors="$(LC_ALL=C grep -E '^pg_restore: error:' "$PG_RESTORE_STDERR" | LC_ALL=C grep -E "$IGNORABLE_RESTORE_ERRORS" || true)"
  residue_errors="$(LC_ALL=C grep -E '^pg_restore: error:' "$PG_RESTORE_STDERR" | LC_ALL=C grep -Ev "$IGNORABLE_RESTORE_ERRORS" || true)"
  if [[ -n "$residue_errors" || -z "$ignored_errors" ]]; then
    echo "FAIL: pg_restore exited $restore_status with errors beyond the known-ignorable" >&2
    echo "      extension COMMENT notices. Failing lines:" >&2
    if [[ -n "$residue_errors" ]]; then
      printf '%s\n' "$residue_errors" >&2
    else
      echo "      (no recognizable 'pg_restore: error:' line was captured — the docker/" >&2
      echo "      compose transport itself may have failed; full stderr is above)" >&2
    fi
    echo "      The clone is INCOMPLETE — the database holds a partial restore. Do NOT" >&2
    echo "      cut over. See docs/design/07-deployment.md §7.10.1 (bounded pg_restore" >&2
    echo "      error tolerance) for recovering from the partial state. Aborted before" >&2
    echo "      image build and bring-up." >&2
    exit 1
  fi
  ignored_count="$(printf '%s\n' "$ignored_errors" | LC_ALL=C grep -c .)"
  echo "  pg_restore exited $restore_status; all $ignored_count error line(s) match the known-ignorable"
  echo "  extension-COMMENT set (postgres-init pre-creates vector/pgcrypto). Ignored:"
  printf '%s\n' "$ignored_errors"
fi

# Backstop behind the error gate above (M1-580 — no longer the sole success
# criterion): confirm the restore actually landed the schema — a migrated
# infochat DB always has tables in `public` (app tables + flyway_schema_history).
# Empty catches the one failure shape the error gate cannot see: a restore that
# populated nothing yet exited 0 with no error lines. `\dt` needs no SQL string
# literal, avoiding nested-quote hazards.
placed_tables="$(docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" psql -h 127.0.0.1 -U infochat -d infochat -tAqc "\dt"' 2>/dev/null || true)"
if [[ -z "$placed_tables" ]]; then
  echo "FAIL: pg_restore did not populate any tables (pg_restore exit was $restore_status)." >&2
  echo "      The dump may be corrupt or from an incompatible schema. Restore aborted." >&2
  exit 1
fi
echo "  DB restored (schema present)."
PLACED+=("database contents (pg_restore complete)")

# ── Flyway history gate (M1-819): dump-vs-checkout migration drift ──────
# The dump's history checksums were computed against the source host's migration
# files; any difference here (even comments) crash-loops the Collector at boot.

# Flyway's checksum recomputed without Flyway — CRC32 over each line's bytes
# concatenated WITHOUT line terminators, a leading UTF-8 BOM dropped, printed
# as a signed int; pinned to the pinned flyway-core by RestoreFlywayChecksumIT.
flyway_checksum() {
  LC_ALL=C awk '
    function xor(a, b,   r, p) {
      r = 0; p = 1
      while (a > 0 || b > 0) {
        if (a % 2 != b % 2) r += p
        a = int(a / 2); b = int(b / 2); p *= 2
      }
      return r
    }
    BEGIN {
      for (i = 0; i < 256; i++) {
        c = i
        for (j = 0; j < 8; j++) c = (c % 2 == 1) ? xor(int(c / 2), 3988292384) : int(c / 2)
        table[i] = c
      }
      for (i = 1; i < 256; i++) ord[sprintf("%c", i)] = i
      crc = 4294967295
    }
    NR == 1 && index($0, "\357\273\277") == 1 { $0 = substr($0, 4) }
    {
      line = $0
      gsub(/\r/, "", line)
      n = length(line)
      for (i = 1; i <= n; i++) {
        crc = xor(table[xor(crc % 256, ord[substr(line, i, 1)])], int(crc / 256))
      }
    }
    END {
      crc = xor(crc, 4294967295)
      if (crc >= 2147483648) crc -= 4294967296
      printf "%d\n", crc
    }
  ' "$1"
}

echo "+ validate restored Flyway history against this checkout's migrations"
MIGRATION_DIR="$REPO_ROOT/infochat-core/src/main/resources/db/migration"
# No `|| true` on this probe: a gate that cannot read the history cannot pass it —
# a failed SELECT aborts through the normal ERR/EXIT path. The redirect target keeps
# the failing exit OUTSIDE a command substitution (its inherited ERR trap double-prints).
HISTORY_FILE="$STAGING/flyway-history.psv"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" psql -h 127.0.0.1 -U infochat -d infochat -tAqc "SELECT version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank"' > "$HISTORY_FILE"
drifted_msgs=()
drifted_fixes=()
absent_msgs=()
checked=0
# Only rows that can bite: failed rows are re-applied by Flyway, baseline and
# non-SQL rows have no file to match; an applied version ABSENT from this
# checkout is a newer-bundle defect, distinct from checksum drift.
while IFS='|' read -r version script checksum success; do
  [[ "$success" == t ]] || continue
  [[ "$script" =~ ^V.*\.sql$ ]] || continue
  checked=$((checked + 1))
  if [[ ! -f "$MIGRATION_DIR/$script" ]]; then
    absent_msgs+=("V$version ($script)")
    continue
  fi
  actual="$(flyway_checksum "$MIGRATION_DIR/$script")"
  if [[ "$actual" != "$checksum" ]]; then
    drifted_msgs+=("V$version ($script): dump history=$checksum checkout=$actual")
    drifted_fixes+=("UPDATE flyway_schema_history SET checksum = $actual WHERE version = '$version';")
  fi
done < "$HISTORY_FILE"
if [[ "${#absent_msgs[@]}" -gt 0 ]]; then
  echo "FAIL: the restored Flyway history lists applied migrations this checkout does" >&2
  echo "      not ship — the bundle comes from a NEWER revision than this checkout:" >&2
  for row in "${absent_msgs[@]}"; do
    echo "        $row" >&2
  done
  echo "      A newer bundle cannot be restored onto an older checkout; re-run from a" >&2
  echo "      checkout at the source host's revision. Aborted before model rehydration" >&2
  echo "      and image build." >&2
  exit 1
fi
if [[ "${#drifted_msgs[@]}" -gt 0 ]]; then
  echo "FAIL: Flyway checksum drift between the restored history and this checkout —" >&2
  echo "      the dump applied migration files whose content differs from this tree:" >&2
  for row in "${drifted_msgs[@]}"; do
    echo "        $row" >&2
  done
  echo "      Recovery options:" >&2
  echo "        (a) Re-run the restore from a checkout at the source host's revision —" >&2
  echo "            dump and migrations then match by construction (preferred)." >&2
  echo "        (b) Deliberate repair: if you CONFIRM this checkout's migration content" >&2
  echo "            is what the restored DB should run against, apply the flyway-repair" >&2
  echo "            equivalent to the restored DB, then re-run bring-up:" >&2
  for fix in "${drifted_fixes[@]}"; do
    echo "              $fix" >&2
  done
  echo "            A mismatch can also mean a genuine semantic change — option (b)" >&2
  echo "            blesses this checkout's content; use it only when the edit is known" >&2
  echo "            to be cosmetic." >&2
  exit 1
fi
echo "  Flyway history matches this checkout ($checked applied SQL migration(s) checked)."

# ── inherited failed-state probe (M1-822) ──────────────────────────────
# A source-host D42 ladder trip migrates with the dump as status='failed' rows the
# fetcher never selects back; surface them WARN-and-continue (P7), failed probe → skip note (P10).
failed_pair_rows=0
failed_source_count=0
failed_pairs=()
INHERITED_STATE_FILE="$STAGING/inherited-failed-state.psv"
if ! docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" psql -h 127.0.0.1 -U infochat -d infochat -tAq -v inherited_failed_probe=1' \
  <<'SQL' > "$INHERITED_STATE_FILE" 2>/dev/null
SELECT 'PAIR|' || asset || '|' || sub_verb || '|' || consecutive_failures || '|' || COALESCE(last_failure_at::text, '') || '|' || COALESCE(is_default, false)::text
  FROM asset_config
 WHERE status = 'failed'
UNION ALL
SELECT 'SOURCES|' || count(*)::text || '||||'
  FROM source
 WHERE status = 'failed' AND deleted_at IS NULL;
SQL
then
  echo "NOTE: inherited-failed-state probe failed (informational) — continuing without the inherited-failure report." >&2
fi
while IFS='|' read -r kind f1 f2 f3 f4 f5; do
  case "$kind" in
    PAIR)
      failed_pair_rows=$((failed_pair_rows + 1))
      failed_pairs+=("$f1|$f2|$f3|$f4|$f5")
      ;;
    SOURCES)
      failed_source_count="$f1"
      ;;
  esac
done < "$INHERITED_STATE_FILE"
if [[ "$failed_pair_rows" -gt 0 || "$failed_source_count" -gt 0 ]]; then
  echo "WARN: the restored DB inherited failed operational state from the source host — the" >&2
  echo "      D42 fetch-failure ladder tripped there and the state migrated with the dump." >&2
  echo "      The fetcher never retries failed rows; the clone is faithful, so this is a" >&2
  echo "      warning, not a failure — recovery is operator-side:" >&2
  for pair in "${failed_pairs[@]}"; do
    IFS='|' read -r asset sub_verb failures last_failure is_default <<< "$pair"
    echo "        $asset/$sub_verb: consecutive_failures=$failures, last_failure_at=$last_failure" >&2
    if [[ "$is_default" == t ]]; then
      echo "          bare /$asset resolves to this default pair — the bare command is the" >&2
      echo "          dead surface while explicit sub-verbs may work" >&2
    fi
    echo "          recovery: /asset-enable $asset $sub_verb" >&2
    echo "          host-level fallback if the Provider is down or unreachable — the §10.8b" >&2
    echo "          UPDATE (docs/design/10-asset-commands.md):" >&2
    echo "            UPDATE asset_config SET status='active', consecutive_failures=0" >&2
    echo "              WHERE asset='$asset' AND sub_verb='$sub_verb';" >&2
  done
  if [[ "$failed_source_count" -gt 0 ]]; then
    echo "        $failed_source_count failed source(s) — re-enable each with /source-enable." >&2
  fi
fi

# ── derivative retention floor (§7.10.1) ─────────────────────────────────
# A bundle older than the shipped derivative retention (4d) would let the first
# prune tick DROP the restored derivative partitions — never regenerated.

# Raise the placed config's three derivative retention-days keys to cover the
# restored age (+30d grace, raise-only — never lowering an operator override)
# BEFORE the Collector start below; read-only probe, WARN-and-continue.

DERIVATIVE_AGE_FILE="$STAGING/derivative-partition-ages.psv"
RETENTION_FLOOR_APPLIED=0
if docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" exec -T postgres \
  sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" psql -h 127.0.0.1 -U infochat -d infochat -tAq -v derivative_age_probe=1' \
  <<'SQL' > "$DERIVATIVE_AGE_FILE" 2>/dev/null
SELECT p.relname || '|' || c.relname
  FROM pg_inherits i
  JOIN pg_class c ON c.oid = i.inhrelid
  JOIN pg_class p ON p.oid = i.inhparent
  JOIN pg_namespace n ON n.oid = p.relnamespace
 WHERE p.relname = ANY (ARRAY['post_embedding','post_entity','post_reference'])
   AND n.nspname = current_schema()
 ORDER BY 1;
SQL
then
  declare -A oldest_part=()
  while IFS='|' read -r parent child; do
    [[ "$child" =~ ^${parent}_[0-9]{6}$ ]] || continue
    if [[ -z "${oldest_part[$parent]:-}" || "$child" < "${oldest_part[$parent]}" ]]; then
      oldest_part[$parent]="$child"
    fi
  done < "$DERIVATIVE_AGE_FILE"
  now_epoch="$(date -u +%s)"
  floor_keys=()
  for parent in post_embedding post_entity post_reference; do
    child="${oldest_part[$parent]:-}"
    [[ -n "$child" ]] || continue
    suffix="${child: -6}"
    end_epoch="$(date -u -d "${suffix:0:4}-${suffix:4:2}-01 +1 month" +%s)"
    age_days=$(( (now_epoch - end_epoch + 86399) / 86400 ))
    # current/future-month partitions are pruner-protected already (the
    # active-month floor guard) — only a positive age needs a raised horizon.
    [[ "$age_days" -gt 0 ]] || continue
    required=$((age_days + 30))
    key="infochat.partitions.retention-days.${parent//_/-}"
    effective="$(read_prop "$key")"
    [[ -n "$effective" ]] || effective=4
    if [[ "$required" -gt "$effective" ]]; then
      lapse="$(date -u -d "@$((end_epoch + required * 86400))" +%Y-%m-%d)"
      floor_keys+=("$key=$required")
      echo "WARN: derivative retention floor applied to $CONFIG_FILE — the restored" >&2
      echo "      $parent partitions (oldest: $child) are ${age_days}d past their partition" >&2
      echo "      end; the effective retention ${effective}d would let the Collector's first" >&2
      echo "      prune tick DROP them (dropped derivative partitions never regenerate, and" >&2
      echo "      restored in-flight rows retry their embedding INSERTs into them every tick" >&2
      echo "      — on remote profiles that burns the paid embedding API):" >&2
      echo "        $key=$required (raise-only; the floor lapses $lapse, after which the" >&2
      echo "        oldest partition becomes prunable again)" >&2
      echo "        - keep the floor to preserve the restored $parent partitions, or" >&2
      echo "        - lower $key back to ${effective} to accept the drop" >&2
      RETENTION_FLOOR_APPLIED=1
    fi
  done
  if [[ "${#floor_keys[@]}" -gt 0 ]]; then
    # an unterminated staged config would fuse its last line with the marker —
    # # mid-line is not a comment in a properties file
    if [[ -n "$(tail -c1 "$CONFIG_FILE")" ]]; then
      printf '\n' >> "$CONFIG_FILE"
    fi
    {
      printf '# derivative retention floor applied by restore.sh (§7.10.1) — raise-only; keep, or lower to accept pruning\n'
      for entry in "${floor_keys[@]}"; do
        printf '%s\n' "$entry"
      done
    } >> "$CONFIG_FILE"
  fi
else
  echo "WARN: derivative-age probe failed (informational) — the restore-time derivative" >&2
  echo "      retention floor was NOT evaluated; if this bundle is older than the derivative" >&2
  echo "      retention (4d shipped), the Collector's first prune tick may DROP its restored" >&2
  echo "      derivative partitions. List them manually (likewise post_entity," >&2
  echo "      post_reference), then raise the retention-days keys on $CONFIG_FILE by hand" >&2
  echo "      (§7.10.1):" >&2
  echo "        docker compose -f $COMPOSE_FILE --env-file $SECRETS_FILE exec -T postgres \\" >&2
  echo "          sh -c 'PGPASSWORD=\"\$INFOCHAT_DB_PASSWORD\" psql -h 127.0.0.1 -U infochat -d infochat -c \"\\d post_embedding\"'" >&2
fi

# ── re-provision models from the RESTORED backend config ────────────────
# Idempotent, and WITHOUT re-running the interactive 4-llm.sh (which would
# re-prompt and rewrite the config restore just laid down). The backend is read
# back from the restored config's endpoints (4-llm.sh chooses it by prompt; here
# there is no operator, so we infer it from what was persisted).
rehydrate_models() {
  local gen_url emb_url gen_backend emb_backend
  # The generative endpoint is the shared default key since M1-603 (D56);
  # a pre-M1-603 dump instead carries per-task lines, so fall back to the
  # chat task's — without the fallback an old dump would classify as
  # 'remote' and an ollama-backend restore would never re-pull its models.
  gen_url="$(read_prop 'infochat.llm.default.base-url')"
  if [[ -z "$gen_url" ]]; then
    gen_url="$(read_prop 'infochat.llm.chat.base-url')"
  fi
  emb_url="$(read_prop 'infochat.embeddings.base-url')"

  case "$gen_url" in
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
    ensure_gguf "$(read_dotenv_value INFOCHAT_LLAMACPP_GGUF "$SECRETS_FILE")" \
      "$(read_dotenv_value INFOCHAT_LLAMACPP_GGUF_URL "$SECRETS_FILE")" \
      "$(read_dotenv_value INFOCHAT_LLAMACPP_GGUF_SHA "$SECRETS_FILE")"
    echo "  + start llama.cpp generative server"
    compose --profile llamacpp up -d llamacpp
  fi
  if [[ "$emb_backend" == "llamacpp" ]]; then
    ensure_gguf "$(read_dotenv_value INFOCHAT_LLAMACPP_EMBED_GGUF "$SECRETS_FILE")" \
      "$(read_dotenv_value INFOCHAT_LLAMACPP_EMBED_GGUF_URL "$SECRETS_FILE")" \
      "$(read_dotenv_value INFOCHAT_LLAMACPP_EMBED_GGUF_SHA "$SECRETS_FILE")"
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
PLACED+=("LLM models in their Docker volumes (reused as-is on a re-run)")

# ── build images, then start Collector (healthy) then Provider ──────────
# Mirrors 7-apps.sh's build -> collector(--wait) -> provider ordering, but does
# NOT run 6b-simplex-provision.sh: that step MINTS a SimpleX identity, and a
# clone already has the identity restored from the bundle — re-provisioning could
# create a NEW SimpleX address and break the "same contact link" clone guarantee.
echo "+ build app images"
build_rc=0
compose build infochat-collector infochat-provider || build_rc=$?
if [[ "$build_rc" -ne 0 ]]; then
  echo "FAIL: image build failed over the host's own network path (builds run host-network, M1-810)." >&2
  echo "      Check host connectivity: VPN, proxy, or firewall. If you use a proxy, export" >&2
  echo "      HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY — the Docker builder forwards them to" >&2
  echo "      the build steps automatically — and re-run. No infochat container depends on" >&2
  echo "      container DNS: downloads and builds both use the host path." >&2
  exit "$build_rc"
fi
echo "+ start Collector (wait up to ${COLLECTOR_WAIT_TIMEOUT}s for healthy — it runs Flyway, an idempotent no-op over the restored schema)"
if ! compose up -d --wait --wait-timeout "$COLLECTOR_WAIT_TIMEOUT" infochat-collector; then
  echo "FAIL: the Collector did not become healthy within ${COLLECTOR_WAIT_TIMEOUT}s." >&2
  echo "      Bounded Collector log excerpt (docker compose logs --tail 60):" >&2
  if ! compose logs --tail 60 infochat-collector 2>/dev/null; then
    echo "      (collecting the log excerpt failed — see the Collector's own logs)" >&2
  fi
  echo "      Named signatures:" >&2
  echo "        - FlywayValidateException = the dump's applied migrations drift from this checkout" >&2
  echo "        - 'no password was provided' (SCRAM) on a MANUAL bring-up = compose was started" >&2
  echo "          without --env-file $SECRETS_FILE, so the \${INFOCHAT_*_PASSWORD:-} pass-throughs" >&2
  echo "          blank out (restore.sh's own compose calls always carry --env-file)" >&2
  exit 1
fi

# The clone is fully placed once the Collector is up: every bundle artifact
# (DB, identities, config) has landed, so from here a failure is a health
# problem on a COMPLETE clone (the verify NOTE below), not partial state — the
# return-to-fresh recipe would be wrong advice. In particular the single-owner
# gate below stops here BY DESIGN when consent is withheld; that deliberate
# stop must not print the recipe. Disarm back to the plain staging cleanup
# (M1-581/M1-582).
set +E
trap - ERR
trap 'rm -rf "$STAGING"' EXIT

# ── single-owner consent gate before the Provider start (M1-582) ────────
# The Provider is the messaging-identity consumer: the moment it starts, this
# clone connects to the restored Signal/SimpleX identity, and if the SOURCE
# host is still running there are two live consumers on one identity —
# session/ratchet corruption on UNRECOVERABLE state (header note). A banner
# printed afterwards cannot close that window, so the start itself is gated on
# operator consent: --source-stopped for unattended runs, or an interactive
# y/N defaulting to No (TTY-checked — the shred-bundle.sh consent precedent).
# The Collector holds no messaging identity, so it is already up regardless.
cat >&2 <<'GATE'

========================================================================
  SINGLE-OWNER GATE - the next step starts the Provider, which connects
  to the restored Signal/SimpleX identity. Exactly ONE instance may own
  that identity at a time; the M1-009 advisory lock does NOT span hosts
  (it is per-database, and this clone has its own restored DB). If the
  SOURCE host is still running, two live consumers will corrupt
  session/ratchet state on the UNRECOVERABLE identity.
========================================================================
GATE
if [[ "$SOURCE_STOPPED" == yes ]]; then
  echo "  --source-stopped: operator asserts the source host is stopped; starting the Provider."
elif [[ ! -t 0 ]]; then
  echo "PROVIDER NOT STARTED: single-owner confirmation required — no interactive" >&2
  echo "      terminal and --source-stopped was not given." >&2
  print_provider_withheld_note
  exit 1
else
  read -r -p "Is the SOURCE host stopped — start the Provider now? [y/N] " answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *)
      echo "PROVIDER NOT STARTED: declined by operator." >&2
      print_provider_withheld_note
      exit 1
      ;;
  esac
fi
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

  OPERATOR LOGIN ROLES ARE NOT CLONED: a single-database dump carries no
  cluster-global roles, so personal operator LOGIN roles and their
  memberships (CREATE ROLE ops_... LOGIN; GRANT infochat_admin TO ops_...,
  the V43-documented workflow) must be re-created by hand on this clone —
  psql admin access via those roles silently stops working otherwise. This
  is the one SILENT divergence from the exact-clone promise; everything
  else fails loud.

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

# The banner above is static; the inherited-failure count repeats it when the WARN
# block fired (the operator reads the banner at cutover time, possibly long after
# the WARN scrolled) — only then, so a clean clone stays silent (M1-822).
if [[ "$failed_pair_rows" -gt 0 || "$failed_source_count" -gt 0 ]]; then
  echo "  NOTE: this clone inherited $failed_pair_rows failed asset pair(s) and $failed_source_count failed source(s)" >&2
  echo "        from the source host — the WARN block above names the recovery actions (/asset-enable; §10.8b UPDATE fallback; /source-enable)." >&2
fi

# Same late-reading discipline for the retention floor: the note prints only
# when a floor was applied — a clean clone stays silent.
if [[ "$RETENTION_FLOOR_APPLIED" -eq 1 ]]; then
  echo "  NOTE: a derivative retention floor was applied to $CONFIG_FILE for this bundle's age" >&2
  echo "        — the WARN block above names the tables, floors, lapse dates, and how to lower" >&2
  echo "        a key back to accept the drop." >&2
fi

if [[ "$verify_status" -ne 0 ]]; then
  echo "NOTE: automated health verification reported a problem (see above) — resolve before cutover." >&2
  exit "$verify_status"
fi
echo "restore complete."
