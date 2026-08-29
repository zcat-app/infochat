#!/bin/bash
# fam-replica-restore.sh — dump, restore, and pin-fingerprint an isolated
# broad-leg replica postgres; instance values never commit (§13) and live in
# the gitignored operator store (D34).

# Deployment-identifying values never commit (engineering-rules §13): the
# source container, target compose project and target host port are REQUIRED
# flags with no defaults; values live in the gitignored operator store (D34).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MIGRATION_DIR="$REPO_ROOT/infochat-core/src/main/resources/db/migration"
BENCH_DIR="${REPLICA_RESTORE_BENCH_DIR:-$REPO_ROOT/.bench/replica-restore}"

# Pinned public tags (the prod restore script's pinned-image precedent): the
# deployment's own pgvector postgres image, and a flyway CLI major-matched to
# the pinned flyway-core (12.0.0, RestoreFlywayChecksumIT).
PG_IMAGE="pgvector/pgvector:pg16"
FLYWAY_IMAGE="flyway/flyway:12"

IN_DUMP="/tmp/replica-restore.pgc"

# The world fingerprint anchors on the en eval scope (the runner's
# RetrievalEvalRunnerIT.dbFingerprint posture); the render is
# ready=N;max_ready_at=<UTC micros>+00;uid_sha256=<hex>.

# The five scope UUIDs are the committed fixture set (scripts/eval-scopes-seed.sql).
EVAL_EN_SCOPE="99a41442-61e2-4c48-962d-26092c3995a7"

SOURCE_CONTAINER=""
TARGET_PROJECT=""
TARGET_PORT=""

usage() {
  cat <<'USAGE'
Usage: scripts/fam-replica-restore.sh <verb> [flags] [args]

Verbs:
  dump                          READ-ONLY in-container pg_dump -F c of the
                                source postgres (password from the container
                                env, no secret on the host; the output's
                                PGDMP magic is checked). The dump lands under
                                the work dir (gitignored operator posture).
  restore <dump.pgc>            Bring up an ISOLATED postgres (own compose
                                project, own volume, own network, loopback
                                publish) and load the dump: postgres alone,
                                admin-role reconstruction, transfer of the
                                binary dump, in-container pg_restore (never
                                stdin), flyway-history verification with no
                                app boot, eval-scope seed, pin readout LAST.
  fingerprint                   Print the pinned readout (world fingerprint,
                                embedding coverage, embedding identity,
                                scope-language census, 5/0/0 probe). Two
                                consecutive reads must be byte-identical.

Required flags (NO defaults — a missing flag exits non-zero naming it; the
concrete values live only in the gitignored operator store):
  -c, --source-container NAME  dump: the source postgres container.
      --project NAME           restore/fingerprint: the isolated compose
                                project. The container (<project>-postgres),
                                volume (<project>_pgdata) and network
                                (<project>_default) derive from it.
  -p, --port PORT              restore: the replica host port (loopback-only).
                                REFUSED: 15432 and 25432 (reserved fence
                                ports: the frozen test stack and the live
                                source instance).

Options:
  -h, --help    This usage.

Isolation fences (all fail loud, naming the offending value): reserved ports,
a non-fresh target volume, a missing dump, any foreign container attached to
the replica network. The replica joins no other instance's networks and no
app service is ever booted against it.
USAGE
}

die() {
  echo "FAIL: $*" >&2
  exit 1
}

require_flag() {
  # §13: instance-shaped flags carry no committed default — a missing value
  # fails loud naming the flag whose value lives in the operator store.
  if [[ -z "$1" ]]; then
    die "missing required flag: $2 (no default; the concrete value lives in the gitignored operator store)"
  fi
}

rand_password() {
  head -c 32 /dev/urandom | sha256sum | awk '{print $1}'
}

# Text SQL travels as a stdin pipe to in-container psql (the prod restore
# script's text-SQL-over-stdin shape): no transfer step, no pseudo-TTY flag.

# Each leg carries a step=<marker> variable so the ordering of the psql EXEC
# argv lines is unambiguous to the wiring test.
in_psql_stdin() {
  local step="$1" args="${2:-}"
  docker exec -i "$TARGET_CONTAINER" \
    sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" psql -h 127.0.0.1 -U infochat -d infochat -v ON_ERROR_STOP=1 -v step='"$step"' '"$args"
}

in_psql_query() {
  local step="$1" query="$2"
  docker exec "$TARGET_CONTAINER" \
    sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" psql -h 127.0.0.1 -U infochat -d infochat -v step='"$step"' -tAqc "'"$query"'"'
}

gen_replica_compose() {
  local bootstrap_pw db_pw c_pw p_pw
  bootstrap_pw="$(rand_password)"
  db_pw="$(rand_password)"
  c_pw="$(rand_password)"
  p_pw="$(rand_password)"
  REPLICA_DB_PW="$db_pw"
  cat > "$BENCH_DIR/docker-compose.yml" <<COMPOSE
services:
  postgres:
    image: ${PG_IMAGE}
    container_name: ${TARGET_CONTAINER}
    environment:
      POSTGRES_PASSWORD: ${bootstrap_pw}
      INFOCHAT_DB_PASSWORD: ${db_pw}
      INFOCHAT_COLLECTOR_PASSWORD: ${c_pw}
      INFOCHAT_PROVIDER_PASSWORD: ${p_pw}
    ports:
      - "127.0.0.1:${TARGET_PORT}:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ${REPO_ROOT}/docker/postgres-init.sh:/docker-entrypoint-initdb.d/postgres-init.sh:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U infochat -d infochat"]
      interval: 5s
      timeout: 3s
      retries: 24
volumes:
  pgdata:
COMPOSE
  chmod 600 "$BENCH_DIR/docker-compose.yml"
}

gen_admin_role_sql() {
  # The dump's ACLs grant to the Flyway-V2-created NOLOGIN principal, which a
  # single-DB dump cannot carry; reconstruct it BEFORE the restore so the
  # co-located service-role grants do not roll back (the M1-570 shape).
  cat > "$BENCH_DIR/admin-role.sql" <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'infochat_admin') THEN
    CREATE ROLE infochat_admin NOLOGIN;
  END IF;
END $$;
SQL
}

gen_pin_read_sql() {
  # The runner's exact world predicate (WORLD_WHERE mirrors
  # SearchPostsTool.worldPredicateSql) with the en eval scope inlined; the
  # render matches the runner's fingerprint render byte-for-byte.
  local world="p.status = 'READY'
   AND (EXISTS (SELECT 1 FROM source s_w
                 WHERE s_w.id = p.source_id AND s_w.source_origin = 'bootstrap'
                   AND s_w.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w
                                    WHERE e_w.scope_kind = 'dm'
                                      AND e_w.scope_id = '${EVAL_EN_SCOPE}'
                                      AND e_w.source_id = s_w.id))
     OR p.source_id IN (SELECT source_id FROM source_subscription
                         WHERE scope_kind = 'dm'
                           AND scope_id = '${EVAL_EN_SCOPE}'))"
  cat > "$BENCH_DIR/pin-read.sql" <<SQL
SELECT 'world_fingerprint|ready=' || count(*) || ';max_ready_at=' ||
       to_char(max(p.ready_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00;uid_sha256=' ||
       encode(sha256(convert_to(string_agg(p.uid, '' ORDER BY p.uid), 'UTF8')), 'hex')
  FROM post p
 WHERE ${world};

SELECT 'world_embedding_coverage|' ||
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM post_embedding pe
                                      WHERE pe.post_id = p.id)) || '/' || count(*)
  FROM post p
 WHERE ${world};

SELECT 'embedding_metadata|' || model_identifier || '|' || dimension
  FROM embedding_metadata;

SELECT 'scope_language_census|' || language || '|' || count(*)
  FROM scope_preferences GROUP BY language ORDER BY language;

SELECT 'eval_scopes|' || count(*) FROM scope_preferences
 WHERE scope_kind = 'dm' AND scope_id IN ('99a41442-61e2-4c48-962d-26092c3995a7',
    '1213f0bd-723c-41ff-8d3e-89aaaf00dca4', 'f568a11b-ca60-436a-832d-ec24a55bfe88',
    'd7fb2b75-29e0-46ff-93cb-93fa055d953e', '5e2578ce-c5c6-4bc3-9b66-e392802090b8');

SELECT 'eval_scope_subscriptions|' || count(*) FROM source_subscription
 WHERE scope_kind = 'dm' AND scope_id IN ('99a41442-61e2-4c48-962d-26092c3995a7',
    '1213f0bd-723c-41ff-8d3e-89aaaf00dca4', 'f568a11b-ca60-436a-832d-ec24a55bfe88',
    'd7fb2b75-29e0-46ff-93cb-93fa055d953e', '5e2578ce-c5c6-4bc3-9b66-e392802090b8');

SELECT 'eval_scope_exclusions|' || count(*) FROM source_exclusion
 WHERE scope_kind = 'dm' AND scope_id IN ('99a41442-61e2-4c48-962d-26092c3995a7',
    '1213f0bd-723c-41ff-8d3e-89aaaf00dca4', 'f568a11b-ca60-436a-832d-ec24a55bfe88',
    'd7fb2b75-29e0-46ff-93cb-93fa055d953e', '5e2578ce-c5c6-4bc3-9b66-e392802090b8');
SQL
}

# Flyway's checksum recomputed without Flyway, duplicated from the frozen
# prod restore script (the M1-819 gate); pinned to flyway-core 12.0.0 by
# RestoreFlywayChecksumIT.
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

cmd_dump() {
  require_flag "$SOURCE_CONTAINER" "--source-container"
  mkdir -p "$BENCH_DIR"
  chmod 700 "$BENCH_DIR" 2>/dev/null || true
  local out ts
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  out="$BENCH_DIR/source-db-$ts.pgc"
  echo "+ read-only pg_dump of the source container (in-container; no secret on the host) -> $out"
  # No pseudo-TTY flag: the current docker dropped it as redundant and the
  # custom-format binary stream must stay clean.
  docker exec "$SOURCE_CONTAINER" \
    sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" pg_dump -h 127.0.0.1 -U infochat -F c infochat' \
    > "$out"
  chmod 600 "$out"
  if [[ ! -s "$out" ]]; then
    die "pg_dump produced an empty dump: $out"
  fi
  if [[ "$(head -c 5 "$out")" != "PGDMP" ]]; then
    die "dump is not postgres custom format (expected PGDMP magic): $out"
  fi
  echo "dump: $out"
  echo "sha256: $(sha256sum "$out" | awk '{print $1}')"
  echo "bytes: $(wc -c < "$out" | awk '{print $1}')"
}

refuse_reserved_port() {
  case "$1" in
    15432) die "REFUSING to target port 15432: reserved for the frozen test stack. The replica must never publish or target it." ;;
    25432) die "REFUSING to target port 25432: reserved for the live source instance. Measurements never ride live instances; the replica is a separate isolated postgres." ;;
  esac
}

cmd_restore() {
  local dump="$1"
  require_flag "$TARGET_PROJECT" "--project"
  require_flag "$TARGET_PORT" "--port"
  if [[ ! -f "$dump" ]]; then
    die "dump file not found: $dump"
  fi
  refuse_reserved_port "$TARGET_PORT"
  mkdir -p "$BENCH_DIR"
  chmod 700 "$BENCH_DIR" 2>/dev/null || true

  if docker volume ls -q | grep -Fx "$TARGET_VOLUME" >/dev/null; then
    die "target volume $TARGET_VOLUME already exists — refusing a non-fresh target. Clear it first: docker compose -p $TARGET_PROJECT -f $BENCH_DIR/docker-compose.yml down -v (then docker volume rm $TARGET_VOLUME if it survives)"
  fi

  gen_replica_compose
  gen_admin_role_sql
  gen_pin_read_sql

  echo "+ bring up the ISOLATED postgres alone (own project/network/volume, loopback 127.0.0.1:$TARGET_PORT)"
  docker compose -p "$TARGET_PROJECT" -f "$BENCH_DIR/docker-compose.yml" up -d --wait postgres

  local member_count
  member_count="$(docker network inspect --format '{{len .Containers}}' "$TARGET_NETWORK")"
  if [[ "$member_count" != "1" ]]; then
    local members
    members="$(docker network inspect --format '{{range .Containers}}{{.Name}} {{end}}' "$TARGET_NETWORK")"
    die "REFUSING restore — $member_count containers are attached to $TARGET_NETWORK ($members). Only $TARGET_CONTAINER may be attached; tear the foreign container down before restoring."
  fi

  echo "+ reconstruct the dump's NOLOGIN principal before the restore (text SQL over stdin)"
  in_psql_stdin admin_role < "$BENCH_DIR/admin-role.sql" >/dev/null

  echo "+ transfer the binary dump in, pg_restore the IN-CONTAINER path (never stdin)"
  docker cp "$dump" "$TARGET_CONTAINER:$IN_DUMP"

  local restore_status=0 stderr_file="$BENCH_DIR/pg-restore.stderr"
  set +e
  {
    docker exec "$TARGET_CONTAINER" \
      sh -c 'PGPASSWORD="$INFOCHAT_DB_PASSWORD" pg_restore -h 127.0.0.1 -U infochat --no-owner -d infochat '"$IN_DUMP" \
      2>&1 1>&3 | tee "$stderr_file" >&2
    restore_status=${PIPESTATUS[0]}
  } 3>&1
  set -e
  if [[ "$restore_status" -ne 0 ]]; then
    local ignorable='^pg_restore: error: could not execute query: ERROR:[[:space:]]+must be owner of extension (pgcrypto|vector)$'
    local ignored residue
    ignored="$(LC_ALL=C grep -E '^pg_restore: error:' "$stderr_file" | LC_ALL=C grep -E "$ignorable" || true)"
    residue="$(LC_ALL=C grep -E '^pg_restore: error:' "$stderr_file" | LC_ALL=C grep -Ev "$ignorable" || true)"
    if [[ -n "$residue" || -z "$ignored" ]]; then
      echo "FAIL: pg_restore exited $restore_status with errors beyond the known-ignorable" >&2
      echo "      extension-COMMENT set (postgres-init pre-creates vector/pgcrypto). Failing lines:" >&2
      if [[ -n "$residue" ]]; then
        printf '%s\n' "$residue" >&2
      else
        echo "      (no recognizable pg_restore error line — the docker transport itself may have failed)" >&2
      fi
      die "restore incomplete — the replica is NOT pinned; re-create the target from fresh before retrying"
    fi
    echo "  pg_restore exited $restore_status; all error line(s) match the known-ignorable extension-COMMENT set. Ignored:"
    printf '%s\n' "$ignored"
  fi

  echo "+ verify the restored flyway history against this checkout's migrations"
  local history
  history="$(in_psql_query history_probe "SELECT version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank")"
  local -A applied=()
  local absent_msgs=() drifted_msgs=() drifted_fixes=() checked=0
  while IFS='|' read -r version script checksum success; do
    [[ "$success" == "t" ]] || continue
    # quarkus-flyway records script names path-prefixed (db/migration/V1__init.sql);
    # the flyway CLI records them bare — accept both, always verify the basename.
    script="${script##*/}"
    [[ "$script" =~ ^V.*\.sql$ ]] || continue
    checked=$((checked + 1))
    if [[ ! -f "$MIGRATION_DIR/$script" ]]; then
      absent_msgs+=("V$version ($script)")
      continue
    fi
    local actual
    actual="$(flyway_checksum "$MIGRATION_DIR/$script")"
    if [[ "$actual" != "$checksum" ]]; then
      drifted_msgs+=("V$version ($script): dump history=$checksum checkout=$actual")
      drifted_fixes+=("UPDATE flyway_schema_history SET checksum = $actual WHERE version = '$version';")
    fi
    applied["$version"]=1
  done <<< "$history"
  if [[ "${#absent_msgs[@]}" -gt 0 ]]; then
    echo "FAIL: the restored flyway history lists applied migrations this checkout does" >&2
    echo "      not ship — the dump comes from a NEWER revision than this checkout:" >&2
    for row in "${absent_msgs[@]}"; do
      echo "        $row" >&2
    done
    die "re-run from a checkout at the source revision"
  fi
  if [[ "${#drifted_msgs[@]}" -gt 0 ]]; then
    echo "FAIL: flyway checksum drift between the restored history and this checkout:" >&2
    for row in "${drifted_msgs[@]}"; do
      echo "        $row" >&2
    done
    echo "      Recovery options:" >&2
    echo "        (a) re-run from a checkout at the source host's revision" >&2
    echo "        (b) deliberate repair (only for known-cosmetic edits):" >&2
    for fix in "${drifted_fixes[@]}"; do
      echo "              $fix" >&2
    done
    die "history verification failed"
  fi
  echo "  applied history verified ($checked SQL migration(s) checked)"

  local pending=() f v base
  for f in "$MIGRATION_DIR"/V*.sql; do
    base="${f##*/}"
    v="${base%%__*}"
    v="${v#V}"
    if [[ -z "${applied[$v]:-}" ]]; then
      pending+=("$base")
    fi
  done
  if [[ "${#pending[@]}" -gt 0 ]]; then
    echo "+ apply ${#pending[@]} pending migration(s) via the flyway CLI container (NO app boot)"
    docker run --rm --network "$TARGET_NETWORK" \
      -v "$MIGRATION_DIR":/flyway/sql/flyway:ro \
      -e "FLYWAY_URL=jdbc:postgresql://$TARGET_CONTAINER:5432/infochat" \
      -e "FLYWAY_USER=infochat" \
      -e "FLYWAY_PASSWORD=$REPLICA_DB_PW" \
      "$FLYWAY_IMAGE" -connectRetries=10 migrate
  else
    echo "  restored history already at this checkout's head"
  fi

  echo "+ seed the five instance-agnostic eval scopes (scripts/eval-scopes-seed.sql, over stdin)"
  local seed_out
  seed_out="$(in_psql_stdin seed_apply "-tAq" < "$REPO_ROOT/scripts/eval-scopes-seed.sql")"
  printf '%s\n' "$seed_out"
  if ! grep -qx 'eval_scopes|5' <<< "$seed_out" \
      || ! grep -qx 'eval_scope_subscriptions|0' <<< "$seed_out" \
      || ! grep -qx 'eval_scope_exclusions|0' <<< "$seed_out"; then
    die "eval-scope seed probe expected 5/0/0, observed:
$seed_out"
  fi

  echo "+ pin readout (LAST: the pin describes the replica's END state; text SQL over stdin)"
  local pin_out
  pin_out="$(in_psql_stdin pin_read "-tAq" < "$BENCH_DIR/pin-read.sql")"
  printf '%s\n' "$pin_out"

  local record
  record="$BENCH_DIR/run-record-$(date -u +%Y%m%dT%H%M%SZ).txt"
  {
    echo "replica restore run record (operator-local, D34)"
    echo "utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "dump: $dump"
    echo "dump_sha256: $(sha256sum "$dump" | awk '{print $1}')"
    echo "port: 127.0.0.1:$TARGET_PORT"
    echo "project: $TARGET_PROJECT (own volume $TARGET_VOLUME, own network $TARGET_NETWORK)"
    echo "--- pin ---"
    printf '%s\n' "$pin_out"
  } > "$record"
  chmod 600 "$record"
  echo "run record: $record"
}

cmd_fingerprint() {
  require_flag "$TARGET_PROJECT" "--project"
  mkdir -p "$BENCH_DIR"
  gen_pin_read_sql
  in_psql_stdin pin_read "-tAq" < "$BENCH_DIR/pin-read.sql"
}

VERB=""
POSITIONAL=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -p|--port) TARGET_PORT="$2"; shift 2 ;;
    -c|--source-container) SOURCE_CONTAINER="$2"; shift 2 ;;
    --project) TARGET_PROJECT="$2"; shift 2 ;;
    -*) echo "FAIL: unknown option: $1" >&2; usage >&2; exit 2 ;;
    *)
      if [[ -z "$VERB" ]]; then
        VERB="$1"
      else
        POSITIONAL+=("$1")
      fi
      shift ;;
  esac
done
if [[ -z "$VERB" ]]; then
  usage >&2
  exit 2
fi

# Compose namespacing: the container keeps an explicit derived name so exec
# addressing never depends on compose's index scheme.
TARGET_CONTAINER="${TARGET_PROJECT}-postgres"
TARGET_VOLUME="${TARGET_PROJECT}_pgdata"
TARGET_NETWORK="${TARGET_PROJECT}_default"

case "$VERB" in
  dump) cmd_dump "${POSITIONAL[@]+"${POSITIONAL[@]}"}" ;;
  restore)
    if [[ "${#POSITIONAL[@]}" -lt 1 ]]; then
      usage >&2
      exit 2
    fi
    cmd_restore "${POSITIONAL[0]}" ;;
  fingerprint) cmd_fingerprint "${POSITIONAL[@]+"${POSITIONAL[@]}"}" ;;
  *)
    echo "FAIL: unknown verb: $VERB" >&2
    usage >&2
    exit 2
    ;;
esac
