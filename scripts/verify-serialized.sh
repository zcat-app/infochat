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
set -eu

lockfile="$(git rev-parse --path-format=absolute --git-common-dir)/m1-verify.lock"

# fd 9 stays open for the rest of the script (and is inherited by
# mvn), so the lock is held for the whole build and released only when
# the script — however it ends — closes the descriptor.
exec 9>"$lockfile"
if ! flock --nonblock 9; then
    echo "verify-serialized: another verify holds $lockfile — waiting" >&2
    flock 9
fi

mvn -B clean verify "$@"
