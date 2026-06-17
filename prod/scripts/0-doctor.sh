#!/bin/bash
# prod/scripts/0-doctor.sh — wizard step 0: host preflight (§7.7.2 step 0).
# Verifies, in order, that the host can run the containerized prod stack and
# exits non-zero naming the FIRST unmet check.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Host-published ports the prod compose stack binds: only Postgres
# (127.0.0.1:5432). The collector/provider services declare no `ports:` mapping
# — they are reached over the compose network and bind their HTTP on in-container
# loopback only, so their HTTP ports are never published to the host.
REQUIRED_PORTS="5432"
# External tools the wizard's later steps invoke (ss here, openssl in 2-secrets,
# curl in 8-verify, df in the disk check below); a missing tool must fail loud,
# not silently — `port_in_use` swallows a missing `ss` as "port free".
REQUIRED_TOOLS="openssl ss curl df"
# Minimum free disk for the container images plus at least one local LLM model.
MIN_FREE_DISK_GB=10

usage() {
  echo "Usage: 0-doctor.sh [--defaults] [-h|--help]"
  echo "  Preflight: Linux host, Docker daemon, Docker Compose v2, free TCP ports"
  echo "  ($REQUIRED_PORTS), and at least ${MIN_FREE_DISK_GB} GB free disk."
  echo "  --defaults  accepted no-op (doctor has no prompts; lets the orchestrator"
  echo "              pass --defaults uniformly to every step)."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

# A listening socket bound to the port means it is in use; any ss line is a hit.
port_in_use() {
  ss -ltnH "( sport = :$1 )" 2>/dev/null | grep -q .
}

echo "+ check: Linux host"
if [[ "$(uname -s)" != "Linux" ]]; then
  echo "FAIL: host OS is $(uname -s); the wizard supports Linux only (§7.7.2)." >&2
  exit 1
fi

echo "+ check: Docker daemon reachable"
if ! docker info >/dev/null 2>&1; then
  echo "FAIL: Docker daemon not reachable (is Docker installed and running?)." >&2
  exit 1
fi

echo "+ check: Docker Compose v2"
if ! docker compose version >/dev/null 2>&1; then
  echo "FAIL: Docker Compose v2 not available ('docker compose version' failed)." >&2
  exit 1
fi

echo "+ check: required tools present ($REQUIRED_TOOLS)"
# Must run before the port loop: port_in_use relies on ss, which it greps with
# 2>/dev/null, so an absent ss would read as "all ports free" (a false pass).
for tool in $REQUIRED_TOOLS; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "FAIL: required tool '$tool' not found on PATH." >&2
    exit 1
  fi
done

echo "+ check: TCP ports free ($REQUIRED_PORTS)"
for port in $REQUIRED_PORTS; do
  if port_in_use "$port"; then
    echo "FAIL: TCP port $port is already in use." >&2
    exit 1
  fi
done

echo "+ check: at least ${MIN_FREE_DISK_GB} GB free disk"
# Check the filesystem Docker stores images/volumes on (falls back to / if the
# daemon does not report a root dir).
docker_root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo /)"
docker_root="${docker_root:-/}"
avail_kb="$(df -Pk "$docker_root" | awk 'NR==2 {print $4}')"
avail_gb=$(( avail_kb / 1024 / 1024 ))
if [[ "$avail_gb" -lt "$MIN_FREE_DISK_GB" ]]; then
  echo "FAIL: only ${avail_gb} GB free on ${docker_root}; need ${MIN_FREE_DISK_GB} GB." >&2
  exit 1
fi

echo "doctor: all preflight checks passed."
