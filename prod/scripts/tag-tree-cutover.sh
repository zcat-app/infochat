#!/bin/bash
# prod/scripts/tag-tree-cutover.sh — the operator cutover surface for the V84
# tag-tree migration (docs/design/07-deployment.md §7.14 "Cut over the tag-tree migration").
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
BOOTSTRAP_FILE="${CUTOVER_BOOTSTRAP_FILE:-$RUNTIME_DIR/bootstrap-sources.json}"
MAP_FILE="${CUTOVER_MAP_FILE:-$RUNTIME_DIR/tag-cutover-map.txt}"
WRITE_SKELETON="${CUTOVER_SKELETON:-1}"
CUTOVER_PGHOST="${CUTOVER_PGHOST:-127.0.0.1}"
CUTOVER_PGDB="${CUTOVER_PGDB:-infochat}"
CUTOVER_PGUSER="${CUTOVER_PGUSER:-infochat}"
CUTOVER_PSQL="${CUTOVER_PSQL:-psql}"

usage() {
  echo "Usage: tag-tree-cutover.sh {preflight|apply [--dry-run]|reconcile-file [--dry-run]|postflight} [-h|--help]"
  echo "  preflight              inventory every tag name the V84 migration cannot map, per"
  echo "                         surface with counts (tag / post.tags / source.bootstrap_tags /"
  echo "                         scope_tag / runtime file tags[]); exit 1 on any finding, writing"
  echo "                         the rulings skeleton unless CUTOVER_SKELETON=0."
  echo "  apply [--dry-run]      execute the rulings file (one line per unknown name,"
  echo "                         'name: <tree-leaf>' or 'name: drop'): validate totally (exit 2"
  echo "                         naming the line on any invalid shape — a map target must be a"
  echo "                         seeded leaf whose row already exists — zero mutation), then one"
  echo "                         transaction: scope_tag re-points/removals first (the FK order),"
  echo "                         tag rows retired, arrays rewritten with order-preserving dedup."
  echo "                         --dry-run prints the plan and changes nothing."
  echo "  reconcile-file [--dry-run]"
  echo "                         classify the deployed runtime bootstrap-sources.json tags[]"
  echo "                         deterministically (normalized leaves kept, V84 mapping keys"
  echo "                         converted, nostr/video dropped, every other unmapped name taken"
  echo "                         from its rulings-file line) and rewrite ONLY the tags spans;"
  echo "                         --dry-run prints the table and changes nothing."
  echo "  postflight             verify the post-migration state (history at 84, tree seed,"
  echo "                         fallback marks, leftovers, array node-membership, scope_tag"
  echo "                         orphans, runtime file tags); GREEN/RED lines, exit 1 on any RED."
  echo "Transport: psql via CUTOVER_PGHOST / CUTOVER_PGDB / CUTOVER_PGUSER (defaults"
  echo "127.0.0.1 / infochat / infochat — the compose loopback publish) or wholesale via"
  echo "CUTOVER_PSQL; password from secrets.env as PGPASSWORD environment only; runtime"
  echo "file via CUTOVER_BOOTSTRAP_FILE or \$RUNTIME_DIR/bootstrap-sources.json; rulings"
  echo "file via CUTOVER_MAP_FILE or \$RUNTIME_DIR/tag-cutover-map.txt."
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

# ── V84's frozen known-set, mirrored by NAME (never node_kind — that column arrives
# at V82 and the gate must answer on older DBs too). Mirror of V84's immutable lists:
# infochat-core/src/main/resources/db/migration/V84__tag_tree_seed_and_migration.sql
TOPS=(sport health fashion culture science tech business news others)

LEAF_NAMES=(
  football basketball hockey tennis motorsport athletics esports other-sports
  medicine nutrition fitness mental-health public-health other-health
  style beauty luxury other-fashion
  art movies music tv books gaming other-culture
  space environment biology physics research other-science
  ai software-development cybersecurity robotics hardware internet other-tech
  markets economy crypto startups personal-finance other-business
  world africa americas asia europe middle-east
  personal opinion misc
)

KEYS=(claude openai anthropic qwen google zcash malware privacy security quarkus java
      spring-io langchain4j oracle development comfyui news glmai kimiai)

declare -A IS_LEAF=()
_leaf=""
for _leaf in "${LEAF_NAMES[@]}"; do
  IS_LEAF[$_leaf]=1
done
unset _leaf

# V84's frozen lookup, key -> seeded leaf (the same mirror source as KEYS/LEAF_NAMES:
# infochat-core/src/main/resources/db/migration/V84__tag_tree_seed_and_migration.sql :156-169).
declare -A KEY_TARGET=(
  [claude]=ai [openai]=ai [anthropic]=ai [qwen]=ai [google]=ai
  [zcash]=crypto
  [malware]=cybersecurity [privacy]=cybersecurity [security]=cybersecurity
  [quarkus]=software-development [java]=software-development [spring-io]=software-development
  [langchain4j]=software-development [oracle]=software-development [development]=software-development
  [comfyui]=software-development
  [news]=world
  [glmai]=misc [kimiai]=misc
)
_key=""
for _key in "${KEYS[@]}"; do
  if [[ -z "${KEY_TARGET[$_key]:-}" ]]; then
    echo "FAIL: internal mirror error — mapping key '$_key' has no target leaf." >&2
    exit 2
  fi
done
unset _key

# args → the ('a'),('b') SQL VALUES list (names here are mirror constants or
# validator-approved [a-z0-9-] rulings keys — never free text).
sql_values() {
  local out="" x
  for x in "$@"; do
    out+="${out:+,}('$x')"
  done
  printf '%s' "$out"
}

KNOWN_TAG_VALUES="$(sql_values "${TOPS[@]}" "${LEAF_NAMES[@]}" "${KEYS[@]}")"
KNOWN_ARRAY_VALUES="$(sql_values "${LEAF_NAMES[@]}" "${KEYS[@]}")"

# The four-surface unknown-name inventory: one row per (surface, name, count) —
# every name V84's per-surface predicate would RAISE on (tag rows: tops ∪ leaves ∪
# keys; arrays: leaves ∪ keys; scope_tag follows its referenced row's name).
PREFLIGHT_SQL="SELECT 'tag' AS surface, name, 1 AS cnt
  FROM tag
 WHERE name NOT IN (VALUES $KNOWN_TAG_VALUES)
UNION ALL
SELECT 'post.tags', e.name, count(*)
  FROM post, unnest(tags) AS e(name)
 WHERE e.name NOT IN (VALUES $KNOWN_ARRAY_VALUES)
 GROUP BY e.name
UNION ALL
SELECT 'source.bootstrap_tags', e.name, count(*)
  FROM source, unnest(bootstrap_tags) AS e(name)
 WHERE e.name NOT IN (VALUES $KNOWN_ARRAY_VALUES)
 GROUP BY e.name
UNION ALL
SELECT 'scope_tag', t.name, count(*)
  FROM scope_tag st JOIN tag t ON t.id = st.tag_id
 WHERE t.name NOT IN (VALUES $KNOWN_TAG_VALUES)
 GROUP BY t.name
ORDER BY 1, 2;"

# The DB-side inventory rows (surface|name|count). A gate that cannot read the DB
# fails loud (exit 2), never silently passes.
db_inventory() {
  local out
  if ! out="$(psql_seam -qAt -F '|' -c "$PREFLIGHT_SQL")"; then
    echo "FAIL: the inventory query failed — the database is unreachable or refused the" >&2
    echo "      connection. Bring postgres up (or fix CUTOVER_PSQL) and re-run." >&2
    exit 2
  fi
  printf '%s\n' "$out"
}

# The file-side inventory rows (raw form|count): every runtime-file tag whose
# lower-cased form is no seeded leaf — the file predicate is stricter than the DB
# one (a mapping key in the file is a finding: it must be converted, not passed).
file_inventory() {
  local names f lower
  declare -A counts=()
  local order=()
  names="$(file_tag_names)"
  while IFS= read -r f; do
    if [[ -z "$f" ]]; then
      continue
    fi
    lower="$(printf '%s' "$f" | tr '[:upper:]' '[:lower:]')"
    if [[ -z "${IS_LEAF[$lower]:-}" ]]; then
      if [[ -z "${counts[$f]:-}" ]]; then
        order+=("$f")
        counts[$f]=0
      fi
      counts[$f]=$(( counts[$f] + 1 ))
    fi
  done <<< "$names"
  if [[ "${#order[@]}" -eq 0 ]]; then
    return 0
  fi
  for f in "${order[@]}"; do
    printf '%s|%s\n' "$f" "${counts[$f]}"
  done
}

# On RED, write the rulings skeleton IFF the file is absent — the rulings file is
# the review artifact, so an existing one (operator edits included) is never
# clobbered. Ruled names get ACTIVE drop lines, other unknowns commented placeholders.
write_skeleton() {
  local db_rows="$1"
  if [[ -e "$MAP_FILE" ]]; then
    echo "rulings file: $MAP_FILE — left untouched (already exists); complete it, then:"
    echo "  tag-tree-cutover.sh apply --dry-run"
    return 0
  fi
  declare -A surf_seen=() raw_seen=()
  local order=() surface name cnt fraw fcnt lower
  while IFS='|' read -r surface name cnt; do
    if [[ -z "$surface" ]]; then
      continue
    fi
    if [[ -z "${surf_seen[$name]:-}" ]]; then
      order+=("$name")
    fi
    surf_seen[$name]+="${surf_seen[$name]:+, }$surface ($cnt)"
  done <<< "$db_rows"
  while IFS='|' read -r fraw fcnt; do
    if [[ -z "$fraw" ]]; then
      continue
    fi
    lower="$(printf '%s' "$fraw" | tr '[:upper:]' '[:lower:]')"
    if [[ -z "${surf_seen[$lower]:-}" && -z "${raw_seen[$lower]:-}" ]]; then
      order+=("$lower")
    fi
    surf_seen[$lower]+="${surf_seen[$lower]:+, }file ($fcnt)"
    raw_seen[$lower]+="${raw_seen[$lower]:+, }$fraw"
  done <<< "$(file_inventory)"
  {
    cat <<'SKELETON_HEADER'
# tag-cutover rulings — one ACTIVE line per unknown tag name, reviewed before any run:
#   name: <tree-leaf>   map every occurrence of `name` onto the seeded tree leaf
#   name: drop          remove every occurrence of `name`

# Keys use the normalized (lower-cased) stored form; raw file forms are quoted in the
# per-name comments. Blank lines and lines starting with '#' are ignored.

# A map target must be a tree leaf whose ROW already exists on this database (the
# identity leaves ai/crypto/research or your own coinages) — any other tree leaf's
# row only exists after the migration: map to an existing leaf or drop instead.

# Complete every commented placeholder below (one per unknown name), review with
# `tag-tree-cutover.sh apply --dry-run`, then `tag-tree-cutover.sh apply`. Retire each
# consumed line after a successful apply (the apply prints them) — stale lines refuse.
SKELETON_HEADER
    local n ruled
    for n in ${order[@]+"${order[@]}"}; do
      if [[ "$n" == "nostr" || "$n" == "video" ]]; then
        continue
      fi
      printf '\n# %s — %s%s\n' "$n" "${surf_seen[$n]}" \
        "${raw_seen[$n]:+ — raw file form(s): ${raw_seen[$n]}}"
      printf '# %s: drop\n' "$n"
    done
    for ruled in nostr video; do
      if [[ -n "${surf_seen[$ruled]:-}" ]]; then
        printf '\n# %s — %s%s — the standing disposal ruling (pre-filled)\n' \
          "$ruled" "${surf_seen[$ruled]}" "${raw_seen[$ruled]:+ — raw file form(s): ${raw_seen[$ruled]}}"
        printf '%s: drop\n' "$ruled"
      fi
    done
  } > "$MAP_FILE"
  echo "rulings skeleton written: $MAP_FILE — complete every commented placeholder"
  echo "  (one per unknown name), then review: tag-tree-cutover.sh apply --dry-run"
}

preflight() {
  local findings=0
  local db_rows surface name cnt prev="" names=""
  db_rows="$(db_inventory)"
  while IFS='|' read -r surface name cnt; do
    if [[ -z "$surface" ]]; then
      continue
    fi
    if [[ "$surface" != "$prev" ]]; then
      if [[ -n "$names" ]]; then
        echo "$prev: $names"
        findings=1
      fi
      prev="$surface"
      names="$name ($cnt)"
    else
      names+=", $name ($cnt)"
    fi
  done <<< "$db_rows"
  if [[ -n "$names" ]]; then
    echo "$prev: $names"
    findings=1
  fi

  local fraw fcnt fnames=""
  while IFS='|' read -r fraw fcnt; do
    if [[ -z "$fraw" ]]; then
      continue
    fi
    fnames+="${fnames:+, }$fraw ($fcnt)"
    findings=1
  done <<< "$(file_inventory)"
  if [[ -n "$fnames" ]]; then
    echo "file: $fnames"
  fi

  if [[ "$findings" -eq 0 ]]; then
    echo "preflight: clean (every stored tag name maps onto the V84 tag tree)"
    return 0
  fi
  if [[ "$WRITE_SKELETON" == "1" ]]; then
    write_skeleton "$db_rows"
  fi
  return 1
}

# ── apply: the rulings file is the ONLY ruling input. Validation is total and
# completes BEFORE any mutation: malformed (any '*' catch-all included), duplicate,
# extra/stale, uncovered, non-leaf target, or a ruled-name map each refuses exit 2.
declare -A RULING_TARGET=() IN_INVENTORY=() DB_UNKNOWN=() TARGET_EXISTS=() ACTION=()
RULINGS_ORDER=()

parse_rulings() {
  local lineno=0 line name target
  while IFS= read -r line || [[ -n "$line" ]]; do
    lineno=$((lineno + 1))
    if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
      continue
    fi
    if [[ ! "$line" =~ ^[[:space:]]*([a-z0-9][a-z0-9-]{0,47})[[:space:]]*:[[:space:]]*([a-z0-9][a-z0-9-]{0,47}|drop)[[:space:]]*(#.*)?$ ]]; then
      echo "FAIL: malformed rulings line $lineno: $line" >&2
      echo "      expected 'name: <tree-leaf>' or 'name: drop' — no wildcards, one ruling per line." >&2
      exit 2
    fi
    name="${BASH_REMATCH[1]}"
    target="${BASH_REMATCH[2]}"
    if [[ -n "${RULING_TARGET[$name]:-}" ]]; then
      echo "FAIL: duplicate ruling for '$name' (line $lineno) — one line per name." >&2
      exit 2
    fi
    if [[ ( "$name" == "nostr" || "$name" == "video" ) && "$target" != "drop" ]]; then
      echo "FAIL: line $lineno maps the ruled name '$name' — its standing ruling is disposal;" >&2
      echo "      write '$name: drop'." >&2
      exit 2
    fi
    if [[ "$target" != "drop" && -z "${IS_LEAF[$target]:-}" ]]; then
      echo "FAIL: line $lineno maps '$name' to '$target', which is no seeded tree leaf." >&2
      exit 2
    fi
    RULING_TARGET[$name]="$target"
    RULINGS_ORDER+=("$name")
  done < "$MAP_FILE"
}

# Coverage against the CURRENT union inventory (four DB surfaces + runtime file):
# a ruling for a name no longer unknown is extra/stale (consumed lines are retired
# between runs); an unknown without a ruling is uncovered.
validate_coverage() {
  local db_rows="$1" surface name cnt fraw fcnt lower u
  while IFS='|' read -r surface name cnt; do
    if [[ -z "$surface" ]]; then
      continue
    fi
    IN_INVENTORY[$name]=1
    DB_UNKNOWN[$name]=1
  done <<< "$db_rows"
  while IFS='|' read -r fraw fcnt; do
    if [[ -z "$fraw" ]]; then
      continue
    fi
    lower="$(printf '%s' "$fraw" | tr '[:upper:]' '[:lower:]')"
    IN_INVENTORY[$lower]=1
  done <<< "$(file_inventory)"
  if [[ "${#RULINGS_ORDER[@]}" -gt 0 ]]; then
    for name in "${RULINGS_ORDER[@]}"; do
      if [[ -z "${IN_INVENTORY[$name]:-}" ]]; then
        echo "FAIL: ruling '$name: ${RULING_TARGET[$name]}' names no current unknown — extra or" >&2
        echo "      stale (already applied? retire the consumed line from $MAP_FILE)." >&2
        exit 2
      fi
    done
  fi
  if [[ "${#IN_INVENTORY[@]}" -gt 0 ]]; then
    while IFS= read -r u; do
      if [[ -z "${RULING_TARGET[$u]:-}" ]]; then
        echo "FAIL: unknown name '$u' has no ruling in $MAP_FILE — every unknown name" >&2
        echo "      needs exactly one line." >&2
        exit 2
      fi
    done <<< "$(printf '%s\n' "${!IN_INVENTORY[@]}" | sort)"
  fi
}

# A map ruling re-points + retires, and its target leaf row must EXIST (identity
# leaves, operator coinages): the follow-preserving rename is undeliverable
# pre-migrate, so an absent target refuses here, naming the working alternatives.
load_actions() {
  local name target maps=() rows row lineno=0
  for name in ${RULINGS_ORDER[@]+"${RULINGS_ORDER[@]}"}; do
    target="${RULING_TARGET[$name]}"
    if [[ "$target" != "drop" ]]; then
      maps+=("$target")
    fi
  done
  if [[ "${#maps[@]}" -gt 0 ]]; then
    if ! rows="$(psql_seam -qAt -c "SELECT name FROM tag WHERE name IN (VALUES $(sql_values "${maps[@]}"))")"; then
      echo "FAIL: could not read the tag table to plan the apply — the database is" >&2
      echo "      unreachable or refused the connection." >&2
      exit 2
    fi
    while IFS= read -r row; do
      if [[ -n "$row" ]]; then
        TARGET_EXISTS[$row]=1
      fi
    done <<< "$rows"
  fi
  for name in ${RULINGS_ORDER[@]+"${RULINGS_ORDER[@]}"}; do
    target="${RULING_TARGET[$name]}"
    if [[ "$target" == "drop" ]]; then
      ACTION[$name]=drop
    elif [[ -n "${TARGET_EXISTS[$target]:-}" ]]; then
      ACTION[$name]=repoint
    else
      echo "FAIL: ruling '$name: $target' maps onto a leaf whose row does not exist on this" >&2
      echo "      database yet — the pre-migration schema cannot create it. Map to a leaf that" >&2
      echo "      already has a row (ai, crypto, research, or your own) or use '$name: drop'." >&2
      exit 2
    fi
  done
}

# The ruled names as a SQL array literal + VALUES list (for the array-rewrite
# row restriction and the dry-run counts).
ruled_names_sql() {
  local arr="" vals="" name
  for name in ${RULINGS_ORDER[@]+"${RULINGS_ORDER[@]}"}; do
    arr+="${arr:+,}'$name'"
    vals+="${vals:+,}('$name')"
  done
  printf 'ARRAY[%s]::text[]|%s' "$arr" "$vals"
}

dry_run_plan() {
  local name target ruled arr vals counts label n
  echo "dry-run (no changes):"
  for name in ${RULINGS_ORDER[@]+"${RULINGS_ORDER[@]}"}; do
    target="${RULING_TARGET[$name]}"
    case "${ACTION[$name]}" in
      drop)    echo "plan: $name -> drop" ;;
      repoint) echo "plan: $name -> $target (scope_tag re-pointed, tag row retired)" ;;
    esac
  done
  if [[ "${#RULINGS_ORDER[@]}" -eq 0 ]]; then
    echo "dry-run: scope_tag rows: 0"
    echo "dry-run: tag rows: 0"
    echo "dry-run: post.tags rows: 0"
    echo "dry-run: source.bootstrap_tags rows: 0"
    return 0
  fi
  ruled="$(ruled_names_sql)"
  arr="${ruled%%|*}"
  vals="${ruled##*|}"
  if ! counts="$(psql_seam -qAt -F '|' -c "SELECT 'scope_tag', count(*)
  FROM scope_tag st JOIN tag t ON t.id = st.tag_id
 WHERE t.name IN (VALUES $vals)
UNION ALL
SELECT 'tag', count(*) FROM tag WHERE name IN (VALUES $vals)
UNION ALL
SELECT 'post.tags', count(*) FROM post WHERE tags && $arr
UNION ALL
SELECT 'source.bootstrap_tags', count(*) FROM source WHERE bootstrap_tags && $arr")"; then
    echo "FAIL: the dry-run count query failed — the database is unreachable or refused" >&2
    echo "      the connection." >&2
    exit 2
  fi
  declare -A got=()
  while IFS='|' read -r label n; do
    if [[ -n "$label" ]]; then
      got[$label]="$n"
    fi
  done <<< "$counts"
  echo "dry-run: scope_tag rows: ${got[scope_tag]:-0}"
  echo "dry-run: tag rows: ${got[tag]:-0}"
  echo "dry-run: post.tags rows: ${got[post.tags]:-0}"
  echo "dry-run: source.bootstrap_tags rows: ${got[source.bootstrap_tags]:-0}"
}

# One transaction; the order is load-bearing: scope_tag re-points/removals before
# tag-row changes (scope_tag.tag_id REFERENCES tag(id) with no cascade), arrays last.
# The array rewrite is V84's order-preserving dedup restricted to rows with a ruled name.
apply_transaction() {
  local sql="BEGIN;" name target ruled arr map_vals="" drop_vals="" map_join="" map_expr="e.name" drop_filter=""
  for name in ${RULINGS_ORDER[@]+"${RULINGS_ORDER[@]}"}; do
    target="${RULING_TARGET[$name]}"
    case "${ACTION[$name]}" in
      repoint)
        sql+="
WITH d AS (INSERT INTO scope_tag (scope_kind, scope_id, tag_id)
  SELECT DISTINCT st.scope_kind, st.scope_id, t2.id
    FROM scope_tag st JOIN tag old ON old.id = st.tag_id
   JOIN tag t2 ON t2.name = '$target'
   WHERE old.name = '$name'
ON CONFLICT (scope_kind, scope_id, tag_id) DO NOTHING RETURNING 1)
SELECT 'scope_tag re-pointed|' || count(*) FROM d;" ;&
      drop)
        sql+="
WITH d AS (DELETE FROM scope_tag WHERE tag_id IN (SELECT id FROM tag WHERE name = '$name') RETURNING 1)
SELECT 'scope_tag removed|' || count(*) FROM d;" ;;
    esac
  done
  for name in ${RULINGS_ORDER[@]+"${RULINGS_ORDER[@]}"}; do
    target="${RULING_TARGET[$name]}"
    case "${ACTION[$name]}" in
      repoint|drop)
        sql+="
WITH d AS (DELETE FROM tag WHERE name = '$name' RETURNING 1)
SELECT 'tag removed|' || count(*) FROM d;" ;;
    esac
  done
  if [[ "${#RULINGS_ORDER[@]}" -gt 0 ]]; then
    ruled="$(ruled_names_sql)"
    arr="${ruled%%|*}"
    for name in ${RULINGS_ORDER[@]+"${RULINGS_ORDER[@]}"}; do
      target="${RULING_TARGET[$name]}"
      if [[ "$target" == "drop" ]]; then
        drop_vals+="${drop_vals:+,}('$name')"
      else
        map_vals+="${map_vals:+,}('$name','$target')"
      fi
    done
    if [[ -n "$map_vals" ]]; then
      map_join="LEFT JOIN (VALUES $map_vals) AS lm(v1, leaf) ON lm.v1 = e.name"
      map_expr="COALESCE(lm.leaf, e.name)"
    fi
    if [[ -n "$drop_vals" ]]; then
      drop_filter="WHERE e.name NOT IN (VALUES $drop_vals)"
    fi
    sql+="
WITH d AS (UPDATE post p SET tags = m.mapped
  FROM (SELECT p2.id,
          (SELECT COALESCE(array_agg(x.mapped ORDER BY x.ord), '{}')
             FROM (SELECT DISTINCT ON ($map_expr) $map_expr AS mapped, e.ord
                     FROM unnest(p2.tags) WITH ORDINALITY AS e(name, ord)
                     $map_join
                     $drop_filter
                    ORDER BY $map_expr, e.ord) x) AS mapped
         FROM post p2
        WHERE p2.tags && $arr) m
 WHERE p.id = m.id RETURNING 1)
SELECT 'post.tags rewritten|' || count(*) FROM d;"
    sql+="
WITH d AS (UPDATE source s SET bootstrap_tags = m.mapped
  FROM (SELECT s2.id,
          (SELECT COALESCE(array_agg(x.mapped ORDER BY x.ord), '{}')
             FROM (SELECT DISTINCT ON ($map_expr) $map_expr AS mapped, e.ord
                     FROM unnest(s2.bootstrap_tags) WITH ORDINALITY AS e(name, ord)
                     $map_join
                     $drop_filter
                    ORDER BY $map_expr, e.ord) x) AS mapped
         FROM source s2
        WHERE s2.bootstrap_tags && $arr) m
 WHERE s.id = m.id RETURNING 1)
SELECT 'source.bootstrap_tags rewritten|' || count(*) FROM d;"
  fi
  sql+="
COMMIT;"
  local out label n
  if ! out="$(psql_seam -qAt -v ON_ERROR_STOP=1 -c "$sql")"; then
    echo "FAIL: apply transaction failed — nothing changed (rollback)." >&2
    exit 2
  fi
  declare -A sums=()
  while IFS='|' read -r label n; do
    if [[ -n "$label" ]]; then
      sums[$label]=$(( ${sums[$label]:-0} + n ))
    fi
  done <<< "$out"
  echo "scope_tag re-pointed: ${sums[scope_tag re-pointed]:-0}"
  echo "scope_tag removed: ${sums[scope_tag removed]:-0}"
  echo "tag removed: ${sums[tag removed]:-0}"
  echo "post.tags rewritten: ${sums[post.tags rewritten]:-0}"
  echo "source.bootstrap_tags rewritten: ${sums[source.bootstrap_tags rewritten]:-0}"
  echo "consumed rulings — retire these line(s) from $MAP_FILE:"
  local consumed=0
  for name in ${RULINGS_ORDER[@]+"${RULINGS_ORDER[@]}"}; do
    if [[ -n "${DB_UNKNOWN[$name]:-}" ]]; then
      echo "$name: ${RULING_TARGET[$name]}"
      consumed=1
    fi
  done
  if [[ "$consumed" -eq 0 ]]; then
    echo "  (none — no DB-side occurrences)"
  fi
}

apply() {
  local dry=0
  if [[ "${1:-}" == "--dry-run" ]]; then
    dry=1
  fi
  if [[ ! -r "$MAP_FILE" ]]; then
    echo "FAIL: rulings file not readable: $MAP_FILE" >&2
    echo "      Run preflight first — its first RED writes the skeleton there." >&2
    exit 2
  fi
  parse_rulings
  local db_rows
  db_rows="$(db_inventory)"
  validate_coverage "$db_rows"
  load_actions
  if [[ "$dry" -eq 1 ]]; then
    dry_run_plan
    return 0
  fi
  apply_transaction
}

# ── reconcile-file: the runtime file's tags[] classified and rewritten —
# deterministic, mirror-based (P12): leaves kept normalized, V84 mapping keys
# converted, nostr/video dropped, every other name from its rulings line (M1-866).
RESOLVED_TARGET=""
RESOLVED_KIND=""
resolve_tag() {
  local lower="$1"
  RESOLVED_TARGET=""
  RESOLVED_KIND=""
  if [[ -n "${IS_LEAF[$lower]:-}" ]]; then
    RESOLVED_TARGET="$lower"
    RESOLVED_KIND="leaf"
  elif [[ -n "${KEY_TARGET[$lower]:-}" ]]; then
    RESOLVED_TARGET="${KEY_TARGET[$lower]}"
    RESOLVED_KIND="key"
  elif [[ "$lower" == "nostr" || "$lower" == "video" ]]; then
    RESOLVED_TARGET="drop"
    RESOLVED_KIND="ruled"
  else
    RESOLVED_TARGET="${RULING_TARGET[$lower]}"
    RESOLVED_KIND="ruling"
  fi
}

# Rewrite ONLY the "tags": [...] spans: dedup post-mapping, order-preserved,
# normalized forms written, every other byte untouched (P11). The span guard
# extends the parser's fail-loud-never-unseen contract to the writer.
rewrite_bootstrap_file() {
  local line out="" lineno=0 term=1 read_any=0 first=1
  local prefix inner suffix raw lower target got commas
  local -a parsed=() mapped_out=()
  declare -A emitted=()
  local spans_n=0 changed=0 joined=""
  while true; do
    if IFS= read -r line; then
      term=1
    elif [[ -n "$line" ]]; then
      term=0
    else
      break
    fi
    read_any=1
    lineno=$((lineno + 1))
    if [[ "$line" =~ ^(.*\"tags\"[[:space:]]*:[[:space:]]*\[)([^]]*)(\].*)$ ]]; then
      spans_n=$((spans_n + 1))
      prefix="${BASH_REMATCH[1]}"
      inner="${BASH_REMATCH[2]}"
      suffix="${BASH_REMATCH[3]}"
      parsed=()
      while IFS= read -r raw; do
        if [[ -z "$raw" ]]; then
          continue
        fi
        parsed+=("$raw")
      done <<< "$(printf '%s\n' "$inner" | grep -o '"[^"]*"' | tr -d '"' || true)"
      commas="$(printf '%s' "$inner" | tr -cd ',' | wc -c)"
      got="${#parsed[@]}"
      if (( got != commas + 1 )) && (( got != 0 || commas != 0 )); then
        echo "FAIL: cannot read the \"tags\": [...] span of $BOOTSTRAP_FILE (line $lineno) —" >&2
        echo "       the parser contract: an unreadable span fails loud, never unseen." >&2
        exit 2
      fi
      mapped_out=()
      emitted=()
      for raw in ${parsed[@]+"${parsed[@]}"}; do
        lower="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
        resolve_tag "$lower"
        if [[ "$RESOLVED_TARGET" == "drop" ]]; then
          continue
        fi
        if [[ -n "${emitted[$RESOLVED_TARGET]:-}" ]]; then
          continue
        fi
        emitted[$RESOLVED_TARGET]=1
        mapped_out+=("$RESOLVED_TARGET")
      done
      joined=""
      for target in ${mapped_out[@]+"${mapped_out[@]}"}; do
        joined+="${joined:+, }\"$target\""
      done
      if [[ "$line" != "${prefix}${joined}${suffix}" ]]; then
        changed=1
      fi
      line="${prefix}${joined}${suffix}"
    fi
    if [[ "$first" -eq 1 ]]; then
      out="$line"
      first=0
    else
      out+=$'\n'"$line"
    fi
  done < "$BOOTSTRAP_FILE"
  if [[ "$term" -eq 1 && "$read_any" -eq 1 ]]; then
    out+=$'\n'
  fi
  printf '%s' "$out" > "$BOOTSTRAP_FILE"
  if [[ "$changed" -eq 0 ]]; then
    echo "reconcile-file: no changes — $BOOTSTRAP_FILE already carries tree-leaf tags"
    return 0
  fi
  echo "reconcile-file: rewrote $BOOTSTRAP_FILE ($spans_n tags span(s)) — the next Collector"
  echo "  boot loads the tree-named tags via the loader's upsert"
}

reconcile_file() {
  local dry=0
  if [[ "${1:-}" == "--dry-run" ]]; then
    dry=1
  fi

  local names raw lower
  names="$(file_tag_names)"
  declare -A FILE_TAG=() UNRESOLVED=()
  local file_order=()
  while IFS= read -r raw; do
    if [[ -z "$raw" ]]; then
      continue
    fi
    lower="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
    if [[ -z "${FILE_TAG[$lower]:-}" ]]; then
      FILE_TAG[$lower]="$raw"
      file_order+=("$lower")
    fi
    if [[ -z "${IS_LEAF[$lower]:-}" && -z "${KEY_TARGET[$lower]:-}" \
          && "$lower" != "nostr" && "$lower" != "video" ]]; then
      UNRESOLVED[$lower]=1
    fi
  done <<< "$names"

  if [[ "${#UNRESOLVED[@]}" -gt 0 && ! -r "$MAP_FILE" ]]; then
    echo "FAIL: rulings file not readable: $MAP_FILE" >&2
    echo "      Run preflight first — its first RED writes the skeleton there." >&2
    exit 2
  fi
  if [[ -r "$MAP_FILE" ]]; then
    parse_rulings
  fi

  # Coverage against the current union inventory (DB unknowns + the file's
  # unresolved names): exactly-once coverage, extras/stale refused (P14);
  # validation completes before any rewrite (the M1-819 posture).
  local db_rows surface name cnt u
  db_rows="$(db_inventory)"
  declare -A NEEDS_RULING=() DB_NAMES=()
  while IFS='|' read -r surface name cnt; do
    if [[ -z "$surface" ]]; then
      continue
    fi
    NEEDS_RULING[$name]=1
    DB_NAMES[$name]=1
  done <<< "$db_rows"
  if [[ "${#UNRESOLVED[@]}" -gt 0 ]]; then
    for u in "${!UNRESOLVED[@]}"; do
      NEEDS_RULING[$u]=1
    done
  fi
  if [[ "${#RULINGS_ORDER[@]}" -gt 0 ]]; then
    for name in "${RULINGS_ORDER[@]}"; do
      if [[ -z "${NEEDS_RULING[$name]:-}" ]]; then
        echo "FAIL: ruling '$name: ${RULING_TARGET[$name]}' names no current unknown — extra or" >&2
        echo "      stale (already applied? retire the consumed line from $MAP_FILE)." >&2
        exit 2
      fi
    done
  fi
  if [[ "${#NEEDS_RULING[@]}" -gt 0 ]]; then
    while IFS= read -r u; do
      if [[ -z "${RULING_TARGET[$u]:-}" ]]; then
        echo "FAIL: unknown name '$u' has no ruling in $MAP_FILE — every unknown name" >&2
        echo "      needs exactly one line." >&2
        exit 2
      fi
    done <<< "$(printf '%s\n' "${!NEEDS_RULING[@]}" | sort)"
  fi

  # The conversion table: one row per raw file tag, in file order.
  for lower in ${file_order[@]+"${file_order[@]}"}; do
    raw="${FILE_TAG[$lower]}"
    resolve_tag "$lower"
    case "$RESOLVED_KIND" in
      leaf)   echo "$raw -> $RESOLVED_TARGET (leaf, kept)" ;;
      key)    echo "$raw -> $RESOLVED_TARGET (mapping key)" ;;
      ruled)  echo "$raw -> drop (ruled disposal)" ;;
      ruling) echo "$raw -> $RESOLVED_TARGET (ruling)" ;;
    esac
  done

  if [[ "$dry" -eq 1 ]]; then
    echo "dry-run: $BOOTSTRAP_FILE was NOT changed"
    return 0
  fi
  rewrite_bootstrap_file
  echo "consumed rulings — retire these line(s) from $MAP_FILE:"
  local consumed=0
  for lower in ${file_order[@]+"${file_order[@]}"}; do
    if [[ -n "${UNRESOLVED[$lower]:-}" && -z "${DB_NAMES[$lower]:-}" ]]; then
      echo "$lower: ${RULING_TARGET[$lower]}"
      consumed=1
    fi
  done
  if [[ "$consumed" -eq 0 ]]; then
    echo "  (none)"
  fi
}

# One row per postflight check: check|value. The array checks are DB-driven (no
# static name list) and mirror the BootstrapLoader node gate's predicate on the
# file side below.
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
  apply)
    if [[ $# -gt 2 || ( $# -eq 2 && "$2" != "--dry-run" ) ]]; then
      usage >&2
      exit 2
    fi
    apply "${2:-}" ;;
  reconcile-file)
    if [[ $# -gt 2 || ( $# -eq 2 && "$2" != "--dry-run" ) ]]; then
      usage >&2
      exit 2
    fi
    reconcile_file "${2:-}" ;;
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
