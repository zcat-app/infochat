#!/bin/bash
# prod/scripts/tag-tree-cutover.sh — the operator cutover surface for the V84
# tag-tree migration (docs/design/07-deployment.md §7.14 "Cut over the tag-tree migration").
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
BOOTSTRAP_FILE="${CUTOVER_BOOTSTRAP_FILE:-$RUNTIME_DIR/bootstrap-sources.json}"
CUTOVER_PGHOST="${CUTOVER_PGHOST:-127.0.0.1}"
CUTOVER_PGDB="${CUTOVER_PGDB:-infochat}"
CUTOVER_PGUSER="${CUTOVER_PGUSER:-infochat}"
CUTOVER_PSQL="${CUTOVER_PSQL:-psql}"

usage() {
  echo "Usage: tag-tree-cutover.sh {preflight|cleanup [--dry-run]|postflight} [-h|--help]"
  echo "  preflight              list nostr/video leftovers per surface (tag / post.tags /"
  echo "                         source.bootstrap_tags / scope_tag / runtime file tags[]);"
  echo "                         exit 1 when any exists — the pre-migrate inventory."
  echo "  cleanup [--dry-run]    remove exactly {nostr, video} from the four DB surfaces in"
  echo "                         one transaction (scope_tag first — the FK order); idempotent;"
  echo "                         --dry-run prints the targets and changes nothing."
  echo "  postflight             verify the post-migration state (history at 84, tree seed,"
  echo "                         fallback marks, leftovers, array node-membership, scope_tag"
  echo "                         orphans, runtime file tags); GREEN/RED lines, exit 1 on any RED."
  echo "Transport: psql via CUTOVER_PGHOST / CUTOVER_PGDB / CUTOVER_PGUSER (defaults"
  echo "127.0.0.1 / infochat / infochat — the compose loopback publish) or wholesale via"
  echo "CUTOVER_PSQL; password from secrets.env as PGPASSWORD environment only; runtime"
  echo "file via CUTOVER_BOOTSTRAP_FILE or \$RUNTIME_DIR/bootstrap-sources.json."
  echo "Requires a psql client on PATH (postgresql-client) — the one host prerequisite"
  echo "beyond Docker + Compose — or CUTOVER_PSQL set to a container-exec wrapper, e.g.:"
  echo "  docker compose exec -T postgres sh -c 'PGPASSWORD=\"\$INFOCHAT_DB_PASSWORD\" psql \"\$@\"' sh \"\$@\""
  echo "Exit codes: 0 pass / 1 findings / 2 usage-or-environment failure."
}

# Read a single value from secrets.env WITHOUT shell-interpreting it (the
# backup.sh read_dotenv_value shape: the wizard writes quoted + escaped
# values for the compose --env-file dotenv parser).
read_dotenv_value() {
  local key="$1" file="$2" line val
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  if [[ -z "$line" ]]; then
    return 0
  fi
  val="${line#"${key}"=}"
  if [[ "$val" == \"*\" ]]; then
    val="${val#\"}"
    val="${val%\"}"
  fi
  val="${val//\\\$/\$}"   # \$ -> $
  val="${val//\\\"/\"}"   # \" -> "
  val="${val//\\\\/\\}"   # \\ -> \
  printf '%s' "$val"
}

psql_seam() {
  "$CUTOVER_PSQL" -h "$CUTOVER_PGHOST" -U "$CUTOVER_PGUSER" -d "$CUTOVER_PGDB" "$@"
}

# The runtime file's tags[] names. No jq dependency: the deployed file keeps
# the template's one-array-per-line shape; a span this parser cannot read
# fails loud, never unseen.
file_tag_names() {
  if [[ ! -r "$BOOTSTRAP_FILE" ]]; then
    echo "FAIL: $BOOTSTRAP_FILE not readable — reconcile the deployment's runtime file first." >&2
    exit 2
  fi
  local declared extracted span_count
  declared="$(grep -c '"tags"' "$BOOTSTRAP_FILE" || true)"
  extracted="$(grep -o '"tags"[[:space:]]*:[[:space:]]*\[[^][]*\]' "$BOOTSTRAP_FILE" || true)"
  span_count="$(printf '%s\n' "$extracted" | grep -c . || true)"
  if [[ "$span_count" -ne "$declared" ]]; then
    echo "FAIL: cannot read every \"tags\": [...] span of $BOOTSTRAP_FILE (a tags array spans" >&2
    echo "       multiple lines) — keep the one-array-per-line shape or install jq." >&2
    exit 2
  fi
  printf '%s\n' "$extracted" \
    | sed -E 's/^"tags"[[:space:]]*:[[:space:]]*\[//; s/\]$//' \
    | grep -o '"[^"]*"' | tr -d '"' || true
}

# The four-surface leftover inventory: one row per (surface, name, count).
# Scope is deliberately the ruled two names only — any other unmapped name is
# the operator's decision, signalled by V84's own loud failure at boot.
PREFLIGHT_SQL="SELECT 'tag' AS surface, name, 1 AS cnt
  FROM tag
 WHERE name IN ('nostr','video')
UNION ALL
SELECT 'post.tags', e.name, count(*)
  FROM post, unnest(tags) AS e(name)
 WHERE e.name IN ('nostr','video')
 GROUP BY e.name
UNION ALL
SELECT 'source.bootstrap_tags', e.name, count(*)
  FROM source, unnest(bootstrap_tags) AS e(name)
 WHERE e.name IN ('nostr','video')
 GROUP BY e.name
UNION ALL
SELECT 'scope_tag', t.name, count(*)
  FROM scope_tag st JOIN tag t ON t.id = st.tag_id
 WHERE t.name IN ('nostr','video')
 GROUP BY t.name
ORDER BY 1, 2;"

preflight() {
  local findings=0 file_findings="" file_names f lower
  file_names="$(file_tag_names)"
  while IFS= read -r f; do
    if [[ -z "$f" ]]; then
      continue
    fi
    lower="$(printf '%s' "$f" | tr '[:upper:]' '[:lower:]')"
    if [[ "$lower" == "nostr" || "$lower" == "video" ]]; then
      if [[ -z "$file_findings" ]]; then
        file_findings="$f"
      else
        file_findings+=", $f"
      fi
    fi
  done <<< "$file_names"
  if [[ -n "$file_findings" ]]; then
    echo "file: $file_findings"
    findings=1
  fi

  local db_out prev="" names="" surface name _cnt
  db_out="$(psql_seam -qAt -F '|' -c "$PREFLIGHT_SQL")"
  while IFS='|' read -r surface name _cnt; do
    if [[ -z "$surface" ]]; then
      continue
    fi
    if [[ "$surface" != "$prev" ]]; then
      if [[ -n "$names" ]]; then
        echo "$prev: $names"
        findings=1
      fi
      prev="$surface"
      names="$name"
    else
      names+=", $name"
    fi
  done <<< "$db_out"
  if [[ -n "$names" ]]; then
    echo "$prev: $names"
    findings=1
  fi

  if [[ "$findings" -eq 0 ]]; then
    echo "preflight: clean (zero nostr/video occurrences)"
    return 0
  fi
  return 1
}

CLEANUP_TARGETS_SQL="SELECT 'scope_tag', count(*)
  FROM scope_tag st JOIN tag t ON t.id = st.tag_id
 WHERE t.name IN ('nostr','video')
UNION ALL
SELECT 'tag', count(*) FROM tag WHERE name IN ('nostr','video')
UNION ALL
SELECT 'post.tags', count(*) FROM post WHERE tags && ARRAY['nostr','video']::text[]
UNION ALL
SELECT 'source.bootstrap_tags', count(*) FROM source WHERE bootstrap_tags && ARRAY['nostr','video']::text[];"

# One transaction; the DELETE order is load-bearing: scope_tag.tag_id
# REFERENCES tag(id) with no cascade, so the references go before the rows.
CLEANUP_SQL="BEGIN;
WITH d AS (DELETE FROM scope_tag WHERE tag_id IN (SELECT id FROM tag WHERE name IN ('nostr','video')) RETURNING 1) SELECT 'scope_tag removed: ' || count(*) FROM d;
WITH d AS (DELETE FROM tag WHERE name IN ('nostr','video') RETURNING 1) SELECT 'tag removed: ' || count(*) FROM d;
WITH d AS (UPDATE post SET tags = array_remove(array_remove(tags, 'nostr'), 'video') WHERE tags && ARRAY['nostr','video']::text[] RETURNING 1) SELECT 'post.tags rewritten: ' || count(*) FROM d;
WITH d AS (UPDATE source SET bootstrap_tags = array_remove(array_remove(bootstrap_tags, 'nostr'), 'video') WHERE bootstrap_tags && ARRAY['nostr','video']::text[] RETURNING 1) SELECT 'source.bootstrap_tags rewritten: ' || count(*) FROM d;
COMMIT;"

cleanup() {
  local dry=0 label count
  if [[ "${1:-}" == "--dry-run" ]]; then
    dry=1
  fi
  if [[ "$dry" -eq 1 ]]; then
    echo "dry-run (no changes):"
    psql_seam -qAt -F '|' -c "$CLEANUP_TARGETS_SQL" | while IFS='|' read -r label count; do
      echo "dry-run: $label rows: $count"
    done
    return 0
  fi
  if ! psql_seam -qAt -v ON_ERROR_STOP=1 -c "$CLEANUP_SQL"; then
    echo "FAIL: cleanup transaction failed — nothing changed (rollback)." >&2
    exit 2
  fi
}

# One row per postflight check: check|value. The leftover count reuses the
# preflight probes; the array checks are DB-driven (no static name list) and
# mirror the BootstrapLoader node gate's predicate on the file side below.
POSTFLIGHT_SQL="SELECT 'history84' AS chk, count(*)::text AS val
  FROM flyway_schema_history WHERE version = '84' AND success
UNION ALL
SELECT 'tops', count(*)::text FROM tag WHERE node_kind = 'top'
UNION ALL
SELECT 'leaves', count(*)::text FROM tag WHERE node_kind = 'leaf' AND parent_name IS NOT NULL
UNION ALL
SELECT 'fallback', count(*)::text FROM tag WHERE fallback = TRUE
UNION ALL
SELECT 'nostrvideo', count(*)::text FROM (
    SELECT name FROM tag WHERE name IN ('nostr','video')
    UNION ALL
    SELECT e.name FROM post, unnest(tags) AS e(name) WHERE e.name IN ('nostr','video')
    UNION ALL
    SELECT e.name FROM source, unnest(bootstrap_tags) AS e(name) WHERE e.name IN ('nostr','video')
    UNION ALL
    SELECT t.name FROM scope_tag st JOIN tag t ON t.id = st.tag_id WHERE t.name IN ('nostr','video')
) q
UNION ALL
SELECT 'post_nonnode', count(*)::text FROM (
    SELECT DISTINCT e.name FROM post, unnest(tags) AS e(name)
     WHERE NOT EXISTS (SELECT 1 FROM tag t WHERE t.name = e.name)) q
UNION ALL
SELECT 'src_nonnode', count(*)::text FROM (
    SELECT DISTINCT e.name FROM source, unnest(bootstrap_tags) AS e(name)
     WHERE NOT EXISTS (SELECT 1 FROM tag t WHERE t.name = e.name)) q
UNION ALL
SELECT 'orphans', count(*)::text FROM scope_tag st
 WHERE NOT EXISTS (SELECT 1 FROM tag t WHERE t.id = st.tag_id);"

postflight() {
  local red=0 history84="" tops="" leaves="" fallback="" nostrvideo=""
  local post_nonnode="" src_nonnode="" orphans=""
  local db_out chk val
  db_out="$(psql_seam -qAt -F '|' -c "$POSTFLIGHT_SQL")"
  while IFS='|' read -r chk val; do
    case "$chk" in
      history84) history84="$val" ;;
      tops) tops="$val" ;;
      leaves) leaves="$val" ;;
      fallback) fallback="$val" ;;
      nostrvideo) nostrvideo="$val" ;;
      post_nonnode) post_nonnode="$val" ;;
      src_nonnode) src_nonnode="$val" ;;
      orphans) orphans="$val" ;;
    esac
  done <<< "$db_out"

  if [[ "$history84" -eq 1 ]]; then
    echo "GREEN: flyway_schema_history: version 84 applied with success"
  else
    echo "RED: flyway_schema_history: version 84 success rows = $history84 (expected 1)"
    red=1
  fi
  if [[ "$tops" -eq 9 && "$leaves" -eq 53 ]]; then
    echo "GREEN: tag tree seeded: 9 tops, 53 leaves"
  else
    echo "RED: tag tree seeded: $tops tops, $leaves leaves (expected 9, 53)"
    red=1
  fi
  if [[ "$fallback" -eq 8 ]]; then
    echo "GREEN: fallback-marked leaves: exactly 8"
  else
    echo "RED: fallback-marked leaves: $fallback (expected 8)"
    red=1
  fi
  if [[ "$nostrvideo" -eq 0 ]]; then
    echo "GREEN: zero nostr/video leftovers (tag / post.tags / source.bootstrap_tags / scope_tag)"
  else
    echo "RED: nostr/video leftovers: $nostrvideo occurrence(s) — re-run preflight for the list"
    red=1
  fi
  if [[ "$post_nonnode" -eq 0 ]]; then
    echo "GREEN: every post.tags element names a tag node"
  else
    echo "RED: post.tags carries $post_nonnode non-node element(s)"
    red=1
  fi
  if [[ "$src_nonnode" -eq 0 ]]; then
    echo "GREEN: every source.bootstrap_tags element names a tag node"
  else
    echo "RED: source.bootstrap_tags carries $src_nonnode non-node element(s)"
    red=1
  fi
  if [[ "$orphans" -eq 0 ]]; then
    echo "GREEN: zero scope_tag orphans"
  else
    echo "RED: scope_tag orphans: $orphans"
    red=1
  fi

  # File side: the BootstrapLoader gate's own predicate (file tag names an
  # existing node after the same lower-casing the loader's normalizer applies).
  local file_names nodes missing f lower
  file_names="$(file_tag_names)"
  nodes="$(psql_seam -qAt -c 'SELECT name FROM tag ORDER BY name')"
  missing=""
  while IFS= read -r f; do
    if [[ -z "$f" ]]; then
      continue
    fi
    lower="$(printf '%s' "$f" | tr '[:upper:]' '[:lower:]')"
    if ! grep -qxF "$lower" <<< "$nodes"; then
      if [[ -z "$missing" ]]; then
        missing="$f"
      else
        missing+=", $f"
      fi
    fi
  done <<< "$file_names"
  if [[ -z "$missing" ]]; then
    echo "GREEN: bootstrap-sources.json tags[] all name tag-tree nodes"
  else
    echo "RED: bootstrap-sources.json tags[] name(s) not tag-tree nodes: $missing"
    red=1
  fi

  if [[ "$red" -eq 0 ]]; then
    return 0
  fi
  return 1
}

if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "FAIL: $SECRETS_FILE not found — run the setup wizard (prod/setup.sh) first." >&2
  exit 2
fi
# The credential crossing the process boundary: PGPASSWORD is an environment
# variable on psql's side only — never argv, never echoed (M1-389/M1-397).
PGPASSWORD="$(read_dotenv_value INFOCHAT_DB_PASSWORD "$SECRETS_FILE")"
export PGPASSWORD

sub="${1:-}"
if [[ "$sub" == "-h" || "$sub" == "--help" ]]; then
  usage
  exit 0
fi
case "$sub" in
  preflight)
    if [[ $# -gt 1 ]]; then
      usage >&2
      exit 2
    fi
    preflight ;;
  cleanup)
    if [[ $# -gt 2 || ( $# -eq 2 && "$2" != "--dry-run" ) ]]; then
      usage >&2
      exit 2
    fi
    cleanup "${2:-}" ;;
  postflight)
    if [[ $# -gt 1 ]]; then
      usage >&2
      exit 2
    fi
    postflight ;;
  *)
    usage >&2
    exit 2 ;;
esac
