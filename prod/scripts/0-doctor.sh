#!/bin/bash
# prod/scripts/0-doctor.sh — wizard step 0: host preflight (§7.7.2 step 0).
# Runs EVERY check, accumulates every unmet one, and prints a consolidated report
# carrying an actionable remedy per failure — so an operator fixes all of them in
# one pass instead of the fix-one/re-run/hit-the-next round-trip. Exits non-zero
# iff at least one check failed (M1-439).
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
# Minimum free disk. On first run the apps build from source (7-apps.sh), so the
# floor must cover, beyond the runtime container images and at least one local
# LLM model, the build-time footprint that the prior 10 GB predated: the
# maven:3.9-eclipse-temurin-25 build image (~1 GB) and the Maven dependency cache
# the cold multi-module reactor downloads (~1-2 GB). Budget (approx): build image
# ~1 GB + eclipse-temurin:25-jre runtime + pgvector images ~1 GB + .m2 cache
# ~2 GB + the two app images ~1 GB + one small local model ~4-5 GB + Docker
# layer/build scratch headroom — rounded up to a 15 GB floor (M1-392).
MIN_FREE_DISK_GB=15

usage() {
  echo "Usage: 0-doctor.sh [--defaults] [-h|--help]"
  echo "  Preflight: Linux host, Docker daemon, Docker Compose v2, free TCP ports"
  echo "  ($REQUIRED_PORTS), at least ${MIN_FREE_DISK_GB} GB free disk, and linger"
  echo "  enabled on rootless Docker hosts. Reports every unmet check at once,"
  echo "  each with its remedy."
  echo "  --defaults  accepted no-op (doctor has no prompts; lets the orchestrator"
  echo "              pass --defaults uniformly to every step)."
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --defaults) ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

# Accumulated failures. Each entry is a one-line symptom followed by indented
# `-> remedy:` lines; the final report prints them verbatim. Collecting rather
# than exiting at the first FAIL is the whole point of this script (M1-439).
FAILURES=()
record_failure() { FAILURES+=("$1"); }

# A listening socket bound to the port means it is in use; any ss line is a hit.
port_in_use() {
  ss -ltnH "( sport = :$1 )" 2>/dev/null | grep -q .
}

have() { command -v "$1" >/dev/null 2>&1; }

echo "+ check: Linux host"
if [[ "$(uname -s)" != "Linux" ]]; then
  record_failure "host OS is $(uname -s); the wizard supports Linux only (§7.7.2).
    -> remedy: run the wizard on a Linux host (x86-64 or arm64)."
fi

echo "+ check: Docker daemon reachable"
# Captured once and reused by the disk check below: an unreachable daemon cannot
# report its data-root, so the disk check must fall back to / rather than pass.
docker_reachable=0
if docker info >/dev/null 2>&1; then
  docker_reachable=1
else
  record_failure "Docker daemon not reachable (is Docker installed and running?).
    -> remedy: install Docker Engine (https://docs.docker.com/engine/install/),
       start it (e.g. sudo systemctl start docker), and if running docker still
       needs sudo, add yourself to the docker group
       (sudo usermod -aG docker \$USER, then log out and back in)."
fi

echo "+ check: Docker Compose v2"
if ! docker compose version >/dev/null 2>&1; then
  record_failure "Docker Compose v2 not available ('docker compose version' failed).
    -> remedy: install the Compose v2 plugin
       (e.g. sudo apt-get install docker-compose-plugin); the legacy v1
       'docker-compose' (hyphen) form is insufficient."
fi

echo "+ check: rootless Docker survives logout (linger)"
# Rootless dockerd rides the user session: logout stops user@<uid>.service and
# SIGKILLs daemon and containers unless linger is enabled. Rootful daemons are
# system services and survive logout, so the check is rootless-only (§7.7.2).
if [[ "$docker_reachable" -eq 1 ]] \
  && docker info --format '{{.SecurityOptions}}' 2>/dev/null | grep -q rootless; then
  if ! have loginctl; then
    record_failure "rootless Docker detected, but the linger check could not be verified:
    'loginctl' is not on PATH, so linger state was NOT confirmed (and is NOT
    assumed enabled).
    -> remedy: install systemd (which provides loginctl, e.g. sudo apt-get
       install systemd) and re-run on a systemd host."
  else
    linger_value="$(loginctl show-user "${USER:-$(id -un)}" -p Linger 2>/dev/null || true)"
    if [[ "$linger_value" != "Linger=yes" ]]; then
      record_failure "rootless Docker with linger disabled: on logout the user session
    (user@<uid>.service) stops and SIGKILLs rootless dockerd, taking every
    container down with it (loginctl reports: ${linger_value:-no answer}).
    -> remedy: loginctl enable-linger \$USER (usually needs no sudo; otherwise
       sudo loginctl enable-linger \$USER), then log out and back in."
    fi
  fi
fi

echo "+ check: required tools present ($REQUIRED_TOOLS)"
for tool in $REQUIRED_TOOLS; do
  if ! have "$tool"; then
    record_failure "required tool '$tool' not found on PATH.
    -> remedy: install it with your package manager
       (e.g. sudo apt-get install $tool)."
  fi
done

echo "+ check: TCP ports free ($REQUIRED_PORTS)"
# Depends on ss: port_in_use greps ss with 2>/dev/null, so an absent ss would read
# as "all ports free" (the 0-doctor.sh:67 false-pass hazard). In aggregate mode we
# report the check as UNVERIFIABLE rather than silently passing it.
if ! have ss; then
  record_failure "TCP port check could not be verified: 'ss' is not installed, so
    port availability was NOT confirmed (and is NOT assumed free).
    -> remedy: install ss (e.g. sudo apt-get install iproute2), then re-run."
else
  for port in $REQUIRED_PORTS; do
    if port_in_use "$port"; then
      record_failure "TCP port $port is already in use.
    -> remedy: stop whatever is bound to it (e.g. a host Postgres:
       sudo systemctl stop postgresql) or otherwise free port $port, then re-run."
    fi
  done
fi

echo "+ check: at least ${MIN_FREE_DISK_GB} GB free disk"
# Depends on df; and on the daemon for the filesystem to measure. With the daemon
# unreachable we cannot learn its data-root, so we measure / and say the Docker
# root is unknown rather than skip the check (a silent pass).
if ! have df; then
  record_failure "free-disk check could not be verified: 'df' is not installed.
    -> remedy: install coreutils (provides df), then re-run."
else
  if [[ "$docker_reachable" -eq 1 ]]; then
    docker_root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo /)"
    docker_root="${docker_root:-/}"
  else
    docker_root="/"
    echo "  note: Docker daemon unreachable; measuring / (Docker data-root unknown)."
  fi
  avail_kb="$(df -Pk "$docker_root" | awk 'NR==2 {print $4}')"
  avail_gb=$(( avail_kb / 1024 / 1024 ))
  if [[ "$avail_gb" -lt "$MIN_FREE_DISK_GB" ]]; then
    record_failure "only ${avail_gb} GB free on ${docker_root}; need ${MIN_FREE_DISK_GB} GB.
    -> remedy: free disk space, or move the Docker data-root to a larger
       filesystem, then re-run."
  fi
fi

if [[ ${#FAILURES[@]} -eq 0 ]]; then
  echo "doctor: all preflight checks passed."
  exit 0
fi

{
  echo ""
  echo "doctor: ${#FAILURES[@]} preflight check(s) failed; fix all of the below and re-run:"
  for failure in "${FAILURES[@]}"; do
    echo ""
    echo "FAIL: $failure"
  done
} >&2
exit 1
