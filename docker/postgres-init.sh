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
CREATE ROLE infochat WITH LOGIN SUPERUSER PASSWORD '${INFOCHAT_DB_PASSWORD:?INFOCHAT_DB_PASSWORD is required}';
CREATE ROLE infochat_collector WITH LOGIN PASSWORD '${INFOCHAT_COLLECTOR_PASSWORD:?INFOCHAT_COLLECTOR_PASSWORD is required}';
CREATE ROLE infochat_provider WITH LOGIN PASSWORD '${INFOCHAT_PROVIDER_PASSWORD:?INFOCHAT_PROVIDER_PASSWORD is required}';
CREATE DATABASE infochat OWNER infochat;
\c infochat
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
EOSQL
