#!/usr/bin/env bash
# Serializes full-suite verifies across the parallel per-ticket
# worktrees of this clone. A unique HTTP test port per suite (the
# test-resources quarkus.http.test-port=0 override) already prevents
# port collisions; this lock bounds PEAK MEMORY by running at most one
# integration-test suite (IT JVMs + Dev Services containers) at a
# time, blocking — not busy-polling — until the lock is free.
#
# The lockfile lives in the git common dir, which every worktree of
# this clone shares, so all worktrees contend on the same lock with no
# configuration. flock(1) releases the lock when the holding file
# descriptor closes — on ANY exit, normal or error — and the script's
# exit status is mvn's (mvn is the last command, and under `set -e` an
# mvn failure terminates the script with that same status).
#
# Invokes the repo's Maven wrapper (./mvnw, pinned to 3.9.x via
# .mvn/wrapper/maven-wrapper.properties — M1-446), not a bare `mvn`, so
# the verify gate runs the pinned toolchain rather than whatever ambient
# `mvn` the host happens to have. Resolved against this script's own
# directory so the gate works regardless of the caller's CWD.
set -eu

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

lockfile="$(git rev-parse --path-format=absolute --git-common-dir)/m1-verify.lock"

# fd 9 stays open for the rest of the script (and is inherited by
# mvn), so the lock is held for the whole build and released only when
# the script — however it ends — closes the descriptor.
exec 9>"$lockfile"
if ! flock --nonblock 9; then
    echo "verify-serialized: another verify holds $lockfile — waiting" >&2
    flock 9
fi

# Deterministic-DB hygiene (M1-535). We now HOLD the lock, so no other verify from
# this clone is running — every Quarkus TEST Dev Services container present is
# debris from a prior run whose Ryuk died with a hard-killed docker daemon.
# Repo-level quarkus.datasource.devservices.reuse=false (M1-554) makes live runs'
# containers carry org.testcontainers.sessionId and be Ryuk-reaped at session end
# — even for hard-killed JVMs (Ryuk triggers on heartbeat loss) — so this sweep
# remains only as the backstop for those daemon-level failures; nothing here is in
# use. Reaping BEFORE the lock would race a lock-holding verify and kill its live
# DB, so this must stay inside the lock. Remove the debris so a stale pile cannot
# re-trigger the host OOM that motivated M1-535. Scoped strictly to the Quarkus
# TEST label — never the operator's infochat-* compose stack. Best-effort: guarded
# so set -e never turns a docker hiccup (or docker being absent) into a verify
# failure; the verify's exit status stays mvn's.
if command -v docker >/dev/null 2>&1; then
    orphans="$(docker ps -aq --filter 'label=io.quarkus.devservice.launch-mode=TEST' 2>/dev/null || true)"
    if [ -n "$orphans" ]; then
        echo "verify-serialized: reaping orphaned Quarkus test DB container(s) from dead runs" >&2
        docker rm -f $orphans >/dev/null 2>&1 || true
    fi
fi

"$repo_root/mvnw" -B clean verify "$@"
