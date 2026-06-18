#!/bin/bash
# docker/postgres-init.sh — service-role password bootstrap (M1-378).
#
# Runs once at first container init from /docker-entrypoint-initdb.d/, BEFORE the
# Collector's first Flyway pass. Creates the infochat owner role + database and
# the two service roles WITH passwords read from the environment, so the roles
# that Flyway's V2 DO-block "IF NOT EXISTS" guard later checks already exist; the
# V4/V31 ALTER ROLE ... NOLOGIN/LOGIN toggling does not touch the password.
#
# WHY a .sh and not a .sql: the postgres image runs *.sql init files through psql,
# which does NOT expand shell ${VAR}. Only *.sh init files are shell-evaluated, so
# the ${VAR:?} fail-loud expansion below works here — an unset OR empty password
# secret (the :? colon form fails on both) aborts the script and exits the
# container non-zero. A .sql would instead store the literal string '${...}' as
# the password and complete init silently. (Verified vs pgvector/pgvector:pg16,
# M1-378.)
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
-- The Collector holds these owner creds to run Flyway, so the owner's privilege
-- set is the blast radius of a leak. The owner is CREATEROLE, NOT SUPERUSER:
-- the full V1..V51 migration set was replayed as a LOGIN/CREATEROLE-only owner
-- and applied cleanly (M1-393; determination + method in
-- docs/design/07-deployment.md §7.7 "Database role bootstrap"). CREATEROLE is
-- needed because V2 creates infochat_admin and V4/V31 toggle the service roles'
-- LOGIN attribute; SUPERUSER is not, so a leaked owner credential can damage
-- only this database (no cluster takeover, no BYPASSRLS, no OS-level access via
-- COPY FROM PROGRAM). The owner does NOT need the extension-install privilege:
-- vector/pgcrypto are created below by the image's bootstrap superuser, so V1's
-- CREATE EXTENSION IF NOT EXISTS vector is a no-op skip when Flyway runs it.
CREATE ROLE infochat WITH LOGIN CREATEROLE PASSWORD '${INFOCHAT_DB_PASSWORD:?INFOCHAT_DB_PASSWORD is required}';
CREATE ROLE infochat_collector WITH LOGIN PASSWORD '${INFOCHAT_COLLECTOR_PASSWORD:?INFOCHAT_COLLECTOR_PASSWORD is required}';
CREATE ROLE infochat_provider WITH LOGIN PASSWORD '${INFOCHAT_PROVIDER_PASSWORD:?INFOCHAT_PROVIDER_PASSWORD is required}';
-- On PG16 a CREATEROLE (non-superuser) role may only ALTER roles it administers.
-- The two service roles are created here by the bootstrap superuser, so without
-- this grant V4/V31's ALTER ROLE ... NOLOGIN/LOGIN fail with "permission denied
-- to alter role". The grant does not change the service roles' own attributes,
-- privileges, or passwords. (M1-393.)
GRANT infochat_collector TO infochat WITH ADMIN OPTION;
GRANT infochat_provider  TO infochat WITH ADMIN OPTION;
CREATE DATABASE infochat OWNER infochat;
\c infochat
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
EOSQL
