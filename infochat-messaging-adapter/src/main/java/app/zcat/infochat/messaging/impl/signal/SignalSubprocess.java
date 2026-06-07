package app.zcat.infochat.messaging.impl.signal;

import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the signal-cli daemon child process lifecycle: spawn,
 * stdout/stderr capture, crash detection, exponential-backoff restart,
 * terminal failed-state transition, and SIGTERM-then-SIGKILL shutdown.
 * Pure process management — JSON-RPC framing and inbound dispatch are
 * the {@link SignalJsonRpcClient}'s responsibility.
 *
 * <p>The crash-restart loop uses {@link Process#onExit()} so the
 * watchdog does not pin a thread per subprocess — the JDK fires a
 * single callback when the underlying child terminates. The
 * {@code stopping} flag is checked first in the exit callback so a
 * {@link #stop()}-initiated SIGTERM never triggers a spurious restart
 * (the textbook ProcessBuilder restart race).</p>
 *
 * <p>State machine: {@code NEW → STARTING → RUNNING → (process
 * exits) → RESTARTING → STARTING → RUNNING → ... → STOPPED |
 * FAILED}. Transitions {@code STARTING → RUNNING} and
 * {@code (STARTING|RUNNING) → RESTARTING} use CAS so a process that
 * exits between {@code pb.start()} returning and the state being
 * stamped {@code RUNNING} cannot leave the watchdog stuck in the
 * STARTING half-state.</p>
 *
 * <p>After {@code maxRestarts} consecutive crashes the wrapper sets
 * {@link State#FAILED} and emits an ERROR log naming the cap; the
 * adapter exposes the state via {@link #state()} and Provider's
 * AdapterRegistry (M1-105) is responsible for dispatching the
 * throttled admin notification per design §6.5.6 — this module does
 * not depend on {@code infochat-core} and so does not call
 * {@code ThrottledAdminNotifier} directly.</p>
 *
 * <p>signal-cli daemon mode chosen for v1: TCP on localhost. Per
 * ticket §Notes, Unix-domain-socket and stdin/stdout JSON-RPC are
 * the alternatives; TCP is the most portable across CI (Linux,
 * macOS) and Provider deployments without requiring JDK 16+
 * UnixDomainSocketAddress on every adapter consumer. Threat surface:
 * any local-loopback process can connect; for v1 single-user
 * deployments per {@code docs/spec/security.md}'s operator-trust
 * boundary this is accepted, with operator-side mitigation via host
 * firewall when multi-tenant hosting is added.</p>
 */
final class SignalSubprocess {

    private static final Logger LOG = Logger.getLogger(SignalSubprocess.class);

    private final ProcessBuilder processBuilder;
    private final BackoffPolicy backoff;
    private final int maxRestarts;
    private final InetSocketAddress endpoint;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    private final AtomicInteger restartAttempts = new AtomicInteger();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile boolean stopping;
    @Nullable private volatile Process current;
    @Nullable private volatile ScheduledFuture<?> nextRestart;

    /**
     * Production constructor — owns its scheduler so callers do not
     * have to thread one through. The scheduler is shut down on
     * {@link #stop()}.
     */
    SignalSubprocess(ProcessBuilder pb,
                     InetSocketAddress endpoint,
                     BackoffPolicy backoff,
                     int maxRestarts) {
        this(pb, endpoint, backoff, maxRestarts, null);
    }

    /**
     * Test-friendly constructor — caller supplies a scheduler so the
     * test can shut it down deterministically.
     */
    SignalSubprocess(ProcessBuilder pb,
                     InetSocketAddress endpoint,
                     BackoffPolicy backoff,
                     int maxRestarts,
                     @Nullable ScheduledExecutorService injectedScheduler) {
        // ProcessBuilder is shared across restarts (Process is per-spawn);
        // merging stderr into stdout simplifies the drain loop to one
        // background reader.
        pb.redirectErrorStream(true);
        this.processBuilder = pb;
        this.endpoint = endpoint;
        this.backoff = backoff;
        this.maxRestarts = maxRestarts;
        if (injectedScheduler == null) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "signal-subprocess-watchdog");
                t.setDaemon(true);
                return t;
            });
            this.ownsScheduler = true;
        } else {
            this.scheduler = injectedScheduler;
            this.ownsScheduler = false;
        }
    }

    /**
     * Synchronously launch one signal-cli daemon process. Returns
     * after {@link Process#isAlive()} but does NOT wait for the
     * daemon's JSON-RPC endpoint to start listening — the
     * caller (SignalAdapter) probes the endpoint separately.
     *
     * @throws IOException if {@link ProcessBuilder#start()} fails.
     * @throws IllegalStateException if the wrapper is not in
     *         {@link State#NEW} or {@link State#STOPPED}.
     */
    void start() throws IOException {
        if (!state.compareAndSet(State.NEW, State.STARTING)
                && !state.compareAndSet(State.STOPPED, State.STARTING)) {
            throw new IllegalStateException(
                    "SignalSubprocess.start() requires NEW or STOPPED state; current=" + state.get());
        }
        stopping = false;
        restartAttempts.set(0);
        spawn();
    }

    private void spawn() throws IOException {
        Process p = processBuilder.start();
        current = p;
        Thread reader = new Thread(() -> drainAndLog(p), "signal-cli-output");
        reader.setDaemon(true);
        reader.start();
        // onExit fires on the JDK's process-reaper thread; we hand off to
        // the scheduler before doing anything expensive so the reaper is
        // not stalled. The completion stage is intentionally not awaited —
        // it completes exceptionally only on a shutdown-race rejection from
        // the scheduler, which the STOPPED/STOPPING state transitions absorb.
        var unused = p.onExit().thenAccept(exited -> scheduler.execute(() -> onProcessExit(exited)));
        // CAS so a sub-millisecond exit that fires onProcessExit BEFORE
        // this line runs (already setting state to RESTARTING) is not
        // overwritten back to RUNNING.
        state.compareAndSet(State.STARTING, State.RUNNING);
    }

    private void drainAndLog(Process p) {
        // signal-cli's merged stdout/stderr stream regularly carries
        // recipient identifiers, timestamps tied to sent messages, and
        // — at signal-cli's own DEBUG verbosity — fragments of message
        // bodies. Per D37 ("bodies of inbound chat-mode messages never
        // appear in non-audit logs, at ANY log level") we must not pass
        // the line text through the application logger. The drain still
        // runs (so the pipe doesn't fill and block signal-cli), but we
        // only emit a lightweight counter and length, never content.
        long lineCount = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) {
                lineCount++;
            }
        } catch (IOException e) {
            // Stream closes when the process terminates — expected; not
            // a wrapper-level failure.
            LOG.debugf("signal-cli output stream closed (%d lines drained)", lineCount);
            return;
        }
        LOG.debugf("signal-cli output stream EOF (%d lines drained)", lineCount);
    }

    private void onProcessExit(Process exited) {
        if (stopping) {
            return;
        }
        int exitCode = exited.exitValue();
        // CAS so a stop() interleaved between the JDK reaper firing
        // onExit and this scheduled execute() running does not flip
        // RESTARTING after STOPPED.
        if (!state.compareAndSet(State.RUNNING, State.RESTARTING)
                && !state.compareAndSet(State.STARTING, State.RESTARTING)) {
            return;
        }
        int attempt = restartAttempts.incrementAndGet();
        if (attempt > maxRestarts) {
            state.set(State.FAILED);
            LOG.errorf("signal-cli subprocess failed after %d restart attempts (last exit code %d); "
                    + "entering FAILED state. Provider's AdapterRegistry (M1-105) observes "
                    + "SignalSubprocess.state() and dispatches ThrottledAdminNotifier per "
                    + "design §6.5.6 — this module does not depend on infochat-core.",
                    maxRestarts, exitCode);
            return;
        }
        long delayMs = computeBackoffDelay(attempt);
        LOG.warnf("signal-cli subprocess exited (code=%d); scheduling restart %d/%d in %d ms",
                exitCode, attempt, maxRestarts, delayMs);
        nextRestart = scheduler.schedule(this::doRestart, delayMs, TimeUnit.MILLISECONDS);
    }

    private void doRestart() {
        if (stopping) {
            return;
        }
        if (!state.compareAndSet(State.RESTARTING, State.STARTING)) {
            return;
        }
        try {
            spawn();
        } catch (IOException e) {
            LOG.errorf(e, "Failed to restart signal-cli subprocess (attempt %d)",
                    restartAttempts.get());
            // Treat a restart-time IOException as a crash — onProcessExit
            // would have done the same if the process started and then
            // died, so we re-enter the same path.
            int attempt = restartAttempts.incrementAndGet();
            if (attempt > maxRestarts) {
                state.set(State.FAILED);
            } else {
                state.set(State.RESTARTING);
                long delayMs = computeBackoffDelay(attempt);
                nextRestart = scheduler.schedule(this::doRestart, delayMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Full-jitter backoff [0, base × factor^(attempt-1)), capped at
     * {@link BackoffPolicy#capMs()}. Per design §6.3.6: full-jitter
     * prevents thundering-herd among parallel adapter instances when
     * a shared dependency (signal-cli's signaling server) recovers.
     */
    private long computeBackoffDelay(int attempt) {
        double raw = backoff.baseMs() * Math.pow(backoff.factor(), attempt - 1);
        long upperBound = Math.min((long) raw, backoff.capMs());
        // nextLong requires a positive bound; we treat a degenerate
        // base=0 / factor=0 config as "no delay" rather than failing.
        return upperBound <= 0 ? 0L : ThreadLocalRandom.current().nextLong(0, upperBound);
    }

    /**
     * Initiate shutdown: cancel any pending restart, SIGTERM the
     * current child, give it {@code waitMs} to exit cleanly, then
     * SIGKILL if still alive. Idempotent — calling stop() repeatedly
     * is a no-op after the first call's STOPPED transition.
     */
    void stop() {
        stopping = true;
        ScheduledFuture<?> next = nextRestart;
        if (next != null) {
            next.cancel(false);
        }
        Process p = current;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        state.set(State.STOPPED);
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Force-restart a hung-but-alive daemon. {@link Process#onExit()} never
     * fires for a deadlocked child, so {@link SignalJsonRpcClient} — the only
     * component that observes JSON-RPC request timeouts — calls this after a
     * run of consecutive timeouts. Forcibly killing the child makes
     * {@code onExit} fire, routing recovery through the same
     * {@link #onProcessExit} backoff-restart path as a natural crash (and
     * counting against {@code maxRestarts}, since a wedged daemon is a
     * failure). No-op when already {@link #stop()}ping or when no child is
     * currently alive.
     */
    void restartHung() {
        if (stopping) {
            return;
        }
        Process p = current;
        if (p != null && p.isAlive()) {
            // SIGKILL, not SIGTERM: the daemon is unresponsive by definition,
            // so a graceful terminate it may ignore would only delay recovery.
            // onProcessExit then runs the normal RUNNING -> RESTARTING path.
            p.destroyForcibly();
        }
    }

    boolean isAlive() {
        Process p = current;
        return p != null && p.isAlive();
    }

    State state() {
        // The AtomicReference is seeded non-null (State.NEW) and only ever
        // CAS'd to non-null State values; NullAway models AtomicReference.get()
        // as @Nullable, so assert the invariant here.
        return Objects.requireNonNull(state.get());
    }

    int restartAttempts() {
        return restartAttempts.get();
    }

    InetSocketAddress endpoint() {
        return endpoint;
    }

    /**
     * Exponential backoff parameters. Full jitter [0, base ×
     * factor^attempt), capped at {@code capMs}.
     */
    record BackoffPolicy(long baseMs, double factor, long capMs) {

        /**
         * Laptop / VPS defaults per design §6.3.6: base 250 ms × 2,
         * 30 s cap. Hard-coded for M1-107; profile-driven values
         * land when SignalConfig grows the keys (out of this
         * ticket's files_scope).
         */
        static BackoffPolicy laptopDefault() {
            return new BackoffPolicy(250L, 2.0, 30_000L);
        }
    }

    /** SignalSubprocess lifecycle state. */
    enum State {
        /** Constructed; {@link #start()} not yet called. */
        NEW,
        /** {@link #start()} or {@link #doRestart} in flight; process not yet observed alive. */
        STARTING,
        /** Process is alive; daemon may or may not be accepting connections yet. */
        RUNNING,
        /** Process exited; waiting for the backoff delay before respawning. */
        RESTARTING,
        /** {@link #stop()} ran to completion. */
        STOPPED,
        /** Restart cap exceeded; no further restart attempts. */
        FAILED
    }
}
