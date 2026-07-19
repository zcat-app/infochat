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
#
# Optional progress tick (process/verify-tick): emits a 30s stderr
# progress line while mvn runs. Format:
#   [verify-tick] 57% (4/7 modules) · in infochat-llm-adapter · 89 test classes · elapsed 2:15
# Default ON (a long verify with no progress signal is the failure mode this
# was added for). Set VERIFY_TICK=0 to opt out — e.g. for a CI-shaped caller
# that pipes stdout through a parser and cannot tolerate extra lines.
# Writes to stderr so the caller's `> log 2>&1` captures it alongside
# mvn output; `tail -f log` then shows live ticks. The sampler reads a
# mkstemp'd tee of mvn's output — no shared state with mvn itself, killed
# via trap on script exit (normal or killed), so a SIGKILL'd verify
# cannot leak the sampler.
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

# Optional progress tick. Default ON; opt out with VERIFY_TICK=0. The
# sampler is a background subshell reading the tmpfile mvn is being
# teed into; it exits when the trap fires on script exit. The tick is
# informational — it must never change the script's exit status or
# block mvn, so every command in the sampler is best-effort.
if [ "${VERIFY_TICK:-1}" != "0" ]; then
    tick_log="$(mktemp -t verify-tick-XXXXXX.log)"
    tick_start=$(date +%s)

    # Subshell, not a function, so it can be backgrounded cleanly and
    # killed by PID. Reads tick_log every 30s; writes one progress line
    # to stderr. The line shape (module % + test-class count + elapsed)
    # is what an 11-minute verify actually surfaces — Maven emits
    # `[INFO] Building <module> <version> [N/M]` per reactor module (7
    # buckets, coarse) and `[INFO] Running <class>` per test class
    # (hundreds of buckets, fine). Two granularities because either
    # alone misleads: module-only hides test progress inside a long
    # module (infochat-provider's 8 min has no module boundary);
    # test-class-only hides whether mvn has even reached the test phase.
    (
        while true; do
            sleep 30
            now=$(date +%s); elapsed=$((now - tick_start))
            mins=$((elapsed / 60)); secs=$((elapsed % 60))
            latest="$(grep -E 'Building .*\[[0-9]+/[0-9]+\]' "$tick_log" 2>/dev/null | tail -1 || true)"
            if [ -n "$latest" ]; then
                cur="$(printf '%s' "$latest" | sed -E 's/.*\[([0-9]+)\/([0-9]+)\].*/\1/')"
                total="$(printf '%s' "$latest" | sed -E 's/.*\[([0-9]+)\/([0-9]+)\].*/\2/')"
                mod="$(printf '%s' "$latest" | awk '{print $3}')"
                pct=$((cur * 100 / total))
            else
                pct=0; cur="-"; total="-"; mod="(waiting or starting)"
            fi
            tcount="$(grep -cE '^\[INFO\] Running ' "$tick_log" 2>/dev/null || printf 0)"
            printf '[verify-tick] %d%% (%s/%s modules) · in %s · %s test classes · elapsed %d:%02d\n' \
                "$pct" "$cur" "$total" "$mod" "$tcount" "$mins" "$secs" >&2
        done
    ) &
    tick_pid=$!

    cleanup_tick() {
        # Kill the sampler first so it cannot read a half-deleted file,
        # then wait so the kill is settled, then remove the tmpfile. The
        # `|| true` and `2>/dev/null` keep cleanup silent under `set -e`
        # even if the sampler already exited (e.g. very fast mvn failure).
        kill "$tick_pid" 2>/dev/null || true
        wait "$tick_pid" 2>/dev/null || true
        rm -f "$tick_log"
    }
    trap cleanup_tick EXIT

    # `tee` streams mvn's output to BOTH the caller's stdout (live —
    # existing `> log` redirect still works) AND tick_log (sampler
    # reads). PIPESTATUS[0] captures mvn's exit, since the pipeline's
    # own exit is tee's (always 0 under no pipefail). The explicit
    # `ec=` capture + `exit "$ec"` keeps the script's exit contract
    # (exit status is mvn's) intact under `set -e`.
    "$repo_root/mvnw" -B clean verify "$@" 2>&1 | tee "$tick_log"
    ec=${PIPESTATUS[0]}
    cleanup_tick
    trap - EXIT
    exit "$ec"
fi

"$repo_root/mvnw" -B clean verify "$@"

