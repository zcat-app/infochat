#!/usr/bin/env bash
# Bounds how many full-suite verifies run at once across the parallel
# per-ticket worktrees of this clone. A unique HTTP test port per suite
# (the test-resources quarkus.http.test-port=0 override) already
# prevents port collisions; this gate bounds PEAK MEMORY, since each
# concurrent suite costs ~5 GB (failsafe JVM + Maven + Dev Services
# containers). Three slots, not one: the original mutex was sized for a
# 16 GB laptop, and one verify at a time is a large waste of a host with
# 32 cores and 61 GB.
#
# Concurrency is only safe on a host whose kernel ephemeral port range
# does NOT overlap docker's published-port range. Under rootless docker
# they overlap by default (both 32768-60999) and rootlesskit's real
# host-side bind then loses to a test JVM that already holds the port —
# `RootlessKit PortManager.AddPort(): bind: address already in use`.
# Split them (host side moved to 40000-60999 in
# /etc/sysctl.d/99-docker-port-split.conf) before raising SLOT_COUNT.
#
# The slot lockfiles live in the git common dir, which every worktree of
# this clone shares, so all worktrees contend on the same slots with no
# configuration. flock(1) releases a slot when the holding file
# descriptor closes — on ANY exit, normal or error — and the script's
# exit status is mvn's (mvn is the last command, and under `set -e` an
# mvn failure terminates the script with that same status).
#
# A free slot is claimed by polling the set every 5s rather than
# blocking, because flock(1) cannot wait on "whichever of these three
# frees first". At one wakeup per 5s against a ~5 min build the poll
# costs nothing.
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

common_dir="$(git rev-parse --path-format=absolute --git-common-dir)"

# Rootless-docker port-split check (2026-08-15 incident): the rootless
# daemon picks each container's published host port from the ephemeral
# range of its OWN network namespace, which it copies from the host at
# daemon start — a host-level split sysctl silently dies at every daemon
# restart. With the bands overlapping, container publishes race live
# host sockets and random ITs fail at container startup with
# "RootlessKit PortManager.AddPort(): … bind: address already in use"
# (engineering-rules §5 environment class; full recipe:
# .agents/memory/rootless-docker-port-split.md, DEVELOPER.md
# §Troubleshooting). Read-only, needs no root, WARN only — the operator
# owns the sudo fix. PID trap (cost this repo an afternoon): the
# rootlesskit PARENT stays in the host netns under --detach-netns — the
# daemon's netns belongs to its CHILD process, so discovery must compare
# ns/net inodes, never trust "the rootlesskit pid". Reading the child's
# sysctl via /proc/<pid>/root/proc works without root.
host_lo="$(awk '{print $1}' /proc/sys/net/ipv4/ip_local_port_range 2>/dev/null || true)"
host_hi="$(awk '{print $2}' /proc/sys/net/ipv4/ip_local_port_range 2>/dev/null || true)"
my_netns="$(readlink /proc/self/ns/net 2>/dev/null || true)"
daemon_pid=""
if [ -n "$host_lo" ] && [ -n "$host_hi" ] && [ -n "$my_netns" ]; then
    for p in $(pgrep -f 'rootlesskit|dockerd' 2>/dev/null | sort -n); do
        [ -r "/proc/$p/ns/net" ] || continue
        [ "$(readlink "/proc/$p/ns/net" 2>/dev/null)" != "$my_netns" ] || continue
        if [ -r "/proc/$p/root/proc/sys/net/ipv4/ip_local_port_range" ]; then
            daemon_pid="$p"
            break
        fi
    done
fi
if [ -n "$daemon_pid" ]; then
    ns_lo="$(awk '{print $1}' "/proc/$daemon_pid/root/proc/sys/net/ipv4/ip_local_port_range" 2>/dev/null || true)"
    ns_hi="$(awk '{print $2}' "/proc/$daemon_pid/root/proc/sys/net/ipv4/ip_local_port_range" 2>/dev/null || true)"
    if [ -n "$ns_lo" ] && [ -n "$ns_hi" ]; then
        if [ "$ns_lo" -le "$host_hi" ] && [ "$host_lo" -le "$ns_hi" ]; then
            echo "verify-serialized: WARNING: rootless docker netns port range ($ns_lo-$ns_hi, pid $daemon_pid) overlaps the host range ($host_lo-$host_hi) — container publishes will race live sockets (random 'address already in use' IT failures; resets at every daemon restart)." >&2
            echo "verify-serialized: live fix (sudo): sudo nsenter -t $daemon_pid -n sysctl -w net.ipv4.ip_local_port_range=\"32768 39999\" — target THIS pid (the daemon-netns child), NOT the rootlesskit parent, and never the host itself (the host side belongs to /etc/sysctl.d/99-docker-port-split.conf)." >&2
        fi
    fi
fi

# One lockfile per slot, each on its own descriptor. The descriptors stay
# open for the rest of the script (and are inherited by mvn), so a claimed
# slot is held for the whole build and released only when the script —
# however it ends — closes the descriptor. Slots are unrolled onto fds
# 9/8/7 because a descriptor number must be a literal to `exec`; raising
# SLOT_COUNT means adding a line here, which is the point at which the
# port-range precondition in the header must be re-checked.
exec 9>"$common_dir/m1-verify.lock.1"
exec 8>"$common_dir/m1-verify.lock.2"
exec 7>"$common_dir/m1-verify.lock.3"
slot_fds="9 8 7"

held_fd=""
while [ -z "$held_fd" ]; do
    for fd in $slot_fds; do
        if flock --nonblock "$fd"; then
            held_fd="$fd"
            break
        fi
    done
    if [ -z "$held_fd" ]; then
        echo "verify-serialized: all 3 verify slots busy — waiting" >&2
        sleep 5
    fi
done

# Deterministic-DB hygiene (M1-535). The sweep below deletes every Quarkus TEST
# Dev Services container, so it may only run when this process is the ONLY
# verify on the clone — under the former mutex that was implied by holding the
# lock, but a semaphore does not imply it. Reconstruct the guarantee by taking
# the OTHER slots too: if all three are ours, no concurrent verify exists and
# nothing swept can be live. The extra slots are released immediately after, so
# a peer waiting on one is delayed by the sweep alone. Every Quarkus TEST
# container present is then debris from a prior run whose Ryuk died with a
# hard-killed docker daemon.
# Repo-level quarkus.datasource.devservices.reuse=false (M1-554) makes live runs'
# containers carry org.testcontainers.sessionId and be Ryuk-reaped at session end
# — even for hard-killed JVMs (Ryuk triggers on heartbeat loss) — so this sweep
# remains only as the backstop for those daemon-level failures; nothing here is in
# use. Reaping outside the slot we hold would race a concurrent verify and kill
# its live DB, so this must stay inside it. Remove the debris so a stale pile
# cannot re-trigger the host OOM that motivated M1-535. Scoped strictly to the
# Quarkus TEST label — never the operator's infochat-* compose stack.
# Best-effort: guarded so set -e never turns a docker hiccup (or docker being
# absent) into a verify failure; the verify's exit status stays mvn's.
borrowed_fds=""
exclusive=1
for fd in $slot_fds; do
    [ "$fd" = "$held_fd" ] && continue
    if flock --nonblock "$fd"; then
        borrowed_fds="$borrowed_fds $fd"
    else
        exclusive=0
    fi
done

if [ "$exclusive" = "1" ] && command -v docker >/dev/null 2>&1; then
    orphans="$(docker ps -aq --filter 'label=io.quarkus.devservice.launch-mode=TEST' 2>/dev/null || true)"
    if [ -n "$orphans" ]; then
        echo "verify-serialized: reaping orphaned Quarkus test DB container(s) from dead runs" >&2
        docker rm -f $orphans >/dev/null 2>&1 || true
    fi
fi

for fd in $borrowed_fds; do
    flock --unlock "$fd"
done

# Optional progress tick. Default ON; opt out with VERIFY_TICK=0. The
# sampler is a background subshell reading the tmpfile mvn is being
# teed into; it exits when the trap fires on script exit. The tick is
# informational — it must never change the script's exit status or
# block mvn, so every command in the sampler is best-effort.
if [ "${VERIFY_TICK:-1}" != "0" ]; then
    # VERIFY_TICK_ONLY=1 → suppress mvn's stdout flood for the interactive
    # / agent-watching-TUI case: the full log goes to a durable file the
    # caller retrieves, and only [verify-tick] progress lines (stderr) plus
    # a final result summary (stdout) reach the terminal. Default (unset)
    # keeps mvn's full output on stdout for the SKILL.md `> log 2>&1`
    # capture pattern and CI stdout parsers — unchanged.
    if [ "${VERIFY_TICK_ONLY:-0}" = "1" ]; then
        mkdir -p "$repo_root/.scratch"
        tick_log="$repo_root/.scratch/verify-$$.log"
        tick_only=1
    else
        tick_log="$(mktemp -t verify-tick-XXXXXX.log)"
        tick_only=0
    fi
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
        # removed in TICK_ONLY mode (tick_log is the durable full log the
        # caller retrieves); in default mode it is a tmpfile. The `|| true`
        # and `2>/dev/null` keep cleanup silent under `set -e` even if the
        # sampler already exited (e.g. very fast mvn failure).
        kill "$tick_pid" 2>/dev/null || true
        wait "$tick_pid" 2>/dev/null || true
        [ "$tick_only" = "1" ] || rm -f "$tick_log"
    }
    trap cleanup_tick EXIT

    # `tee` streams mvn's output to tick_log (sampler reads it). In
    # TICK_ONLY mode `>/dev/null` suppresses the stdout flood so only ticks
    # (stderr) reach the terminal; default mode still flows mvn's full
    # output to stdout for the caller's `> log` capture. PIPESTATUS[0]
    # captures mvn's exit, since the pipeline's own exit is tee's (always 0
    # under no pipefail). The explicit `ec=` capture + `exit "$ec"` keeps
    # the script's exit contract (exit status is mvn's) intact under `set -e`.
    if [ "$tick_only" = "1" ]; then
        "$repo_root/mvnw" -B clean verify "$@" 2>&1 | tee "$tick_log" >/dev/null
    else
        "$repo_root/mvnw" -B clean verify "$@" 2>&1 | tee "$tick_log"
    fi
    ec=${PIPESTATUS[0]}
    cleanup_tick
    trap - EXIT
    # TICK_ONLY: the caller's stdout never saw mvn's output — print a
    # one-screen result summary (build verdict + final test counts) and the
    # full-log path so the caller can copy it to target/ for review/commit.
    # The Tests-run anchor is end-anchored to match ONLY the failsafe/surefire
    # summary line, never the per-class `-- in <class>` lines.
    if [ "$tick_only" = "1" ]; then
        grep -E 'BUILD SUCCESS|BUILD FAILURE|^\[INFO\] (Reactor Summary|Total time)|^\[(INFO|WARNING)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$tick_log"
        printf 'verify exit: %s\nfull log: %s\n' "$ec" "$tick_log"
    fi
    exit "$ec"
fi

"$repo_root/mvnw" -B clean verify "$@"

