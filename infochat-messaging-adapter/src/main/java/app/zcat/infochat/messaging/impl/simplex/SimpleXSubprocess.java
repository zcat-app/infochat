package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns the simplex-chat OS process. Spawns it via {@link ProcessBuilder},
 * drains its stdout/stderr to SLF4J via virtual-thread loops, and runs a
 * supervisor that restarts the process on unexpected exit with
 * exponential backoff + full jitter until a profile-driven crash cap is
 * exhausted, at which point the subprocess transitions to the terminal
 * {@link State#FAILED} state and fires the throttled
 * {@code adminNotifier} hook (per acceptance items 3 + 4 of M1-103).
 *
 * <p>The subprocess and the WebSocket connection it serves form one
 * supervised unit ({@code docs/design/06-messaging.md} §6.4.6). This class
 * owns the process; the adapter rebuilds {@link SimpleXWebSocketClient}
 * after the supervisor reports each restart. The two-component split
 * keeps the unit tests for each layer independent: this class is tested
 * against {@code /bin/sleep} / {@code /bin/true} without any WebSocket
 * machinery.</p>
 *
 * <p>The backoff curve {@link #backoffDelay(int, Duration, Duration, Random)}
 * is package-private and {@code Random}-injected so tests pin the jitter
 * to zero and verify deterministic delays without wall-clock waits — the
 * pattern is taken from {@code NostrRelayConnection#backoffDelay} in the
 * collector module.</p>
 */
final class SimpleXSubprocess {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleXSubprocess.class);

    /**
     * Default v1 crash-restart cap. The "profile-driven" promise in the
     * ticket and design §6.4.6 is left for M1-105 wiring; v1 picks the
     * design's reconnection cadence: 5 consecutive failures before
     * fronting the admin notifier (see §6.4.6).
     */
    static final int DEFAULT_CRASH_CAP = 5;

    /** Grace period between SIGTERM and SIGKILL during {@link #stop()}. */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /** Lifecycle state visible to tests and to the adapter. */
    enum State {
        /** Constructed but {@link #start()} has not been called. */
        NOT_STARTED,
        /** Process is up and the supervisor is monitoring it. */
        RUNNING,
        /** Process exited; supervisor is waiting out the backoff. */
        RESTARTING,
        /** Crash cap exhausted; supervisor stopped, admin notified. */
        FAILED,
        /** {@link #stop()} returned; supervisor is no longer running. */
        STOPPED
    }

    private final List<String> command;
    private final Duration backoffBase;
    private final Duration backoffMax;
    private final int crashCap;
    private final Consumer<String> adminNotifier;
    private final Random random;

    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private final AtomicInteger adminNotifications = new AtomicInteger(0);

    private volatile Process currentProcess;
    private volatile Thread supervisor;
    private volatile boolean stopping = false;

    SimpleXSubprocess(@NonNull List<String> command,
                      @NonNull Duration backoffBase,
                      @NonNull Duration backoffMax,
                      int crashCap,
                      @NonNull Consumer<String> adminNotifier,
                      @NonNull Random random) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must be non-empty");
        }
        this.command = List.copyOf(command);
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.crashCap = crashCap;
        this.adminNotifier = adminNotifier;
        this.random = random;
    }

    /**
     * Build the simplex-chat invocation from a {@link SimpleXConfig}. The
     * flag spelling is verified against a live simplex-chat in M1-105's
     * integration ticket; the shape here matches the documented CLI surface
     * (data-dir via {@code -d}, WebSocket port via {@code --network}).
     */
    static @NonNull List<String> commandFor(@NonNull SimpleXConfig config) {
        return List.of(
                config.binary(),
                "-d", config.dataDir(),
                "--network", "ws://127.0.0.1:" + config.wsPort());
    }

    /**
     * Launch the process for the first time and start the supervisor
     * virtual thread. Returns immediately — the supervisor runs in the
     * background. Subsequent calls are a no-op (idempotent).
     */
    void start() {
        if (!state.compareAndSet(State.NOT_STARTED, State.RUNNING)) {
            return;
        }
        supervisor = Thread.ofVirtual()
                .name("simplex-subprocess-supervisor")
                .start(this::runSupervisor);
    }

    /**
     * Terminate the supervisor and the live process. Sends SIGTERM, waits
     * up to {@link #SHUTDOWN_GRACE}, then SIGKILL if the process is still
     * alive (per acceptance item 6). Idempotent.
     */
    void stop() {
        if (stopping) {
            return;
        }
        stopping = true;
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(SHUTDOWN_GRACE.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        Thread t = supervisor;
        if (t != null) {
            t.interrupt();
            try {
                t.join(SHUTDOWN_GRACE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state.compareAndSet(State.RUNNING, State.STOPPED);
        state.compareAndSet(State.RESTARTING, State.STOPPED);
    }

    @NonNull State state() {
        return state.get();
    }

    /** How many times the supervisor has restarted the process. */
    int restartCount() {
        return restartCount.get();
    }

    /** How many admin-notification calls fired (one per FAILED transition). */
    int adminNotifications() {
        return adminNotifications.get();
    }

    private void runSupervisor() {
        int consecutiveCrashes = 0;
        while (!stopping) {
            Process process;
            try {
                process = launchProcess();
            } catch (IOException e) {
                LOG.warn("simplex-chat launch failed: {}", e.getClass().getSimpleName());
                consecutiveCrashes++;
                if (handleCrashCap(consecutiveCrashes)) {
                    return;
                }
                if (!sleepForBackoff(consecutiveCrashes)) {
                    return;
                }
                restartCount.incrementAndGet();
                continue;
            }
            currentProcess = process;
            state.set(State.RUNNING);
            consecutiveCrashes = supervise(process, consecutiveCrashes);
            if (stopping || state.get() == State.FAILED) {
                return;
            }
        }
    }

    private Process launchProcess() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        // Inherit stderr into a drained stream so we never deadlock on a
        // full pipe buffer; the drainer reads it concurrently.
        pb.redirectErrorStream(false);
        Process process = pb.start();
        Thread.ofVirtual()
                .name("simplex-stdout-drain")
                .start(() -> drainStream(process.getInputStream(), false));
        Thread.ofVirtual()
                .name("simplex-stderr-drain")
                .start(() -> drainStream(process.getErrorStream(), true));
        return process;
    }

    private int supervise(Process process, int consecutiveCrashesIn) {
        int consecutiveCrashes = consecutiveCrashesIn;
        try {
            int exitCode = process.waitFor();
            if (stopping) {
                return consecutiveCrashes;
            }
            LOG.warn("simplex-chat exited unexpectedly with code {}", exitCode);
            consecutiveCrashes++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return consecutiveCrashes;
        }
        if (handleCrashCap(consecutiveCrashes)) {
            return consecutiveCrashes;
        }
        state.set(State.RESTARTING);
        if (!sleepForBackoff(consecutiveCrashes)) {
            return consecutiveCrashes;
        }
        restartCount.incrementAndGet();
        return consecutiveCrashes;
    }

    private boolean handleCrashCap(int consecutiveCrashes) {
        if (consecutiveCrashes < crashCap) {
            return false;
        }
        state.set(State.FAILED);
        // The "throttled" admin notification commitment (acceptance item 4)
        // resolves to a single notify at the FAILED transition — the
        // supervisor stops looping after this, so a subsequent flood is
        // structurally impossible.
        adminNotifications.incrementAndGet();
        try {
            adminNotifier.accept(
                    "simplex-chat subprocess crashed " + consecutiveCrashes
                            + " consecutive times; supervisor giving up");
        } catch (RuntimeException e) {
            // Notifier is operator-side wiring (M1-105); a buggy notifier
            // must not leak past the supervisor.
            LOG.warn("admin notifier threw: {}", e.getClass().getSimpleName());
        }
        return true;
    }

    private boolean sleepForBackoff(int consecutiveCrashes) {
        Duration delay = backoffDelay(consecutiveCrashes, backoffBase, backoffMax, random);
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void drainStream(InputStream in, boolean stderr) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stderr) {
                    LOG.warn("simplex-chat: {}", line);
                } else {
                    LOG.info("simplex-chat: {}", line);
                }
            }
        } catch (IOException e) {
            // Process exited; stream closed. Normal lifecycle.
        }
    }

    /**
     * Equal-jitter exponential backoff: the deterministic component
     * doubles each consecutive failure up to {@code max}; the jittered
     * delay lands in {@code [exp/2, exp]}, so the lower bound still grows
     * per attempt (no thundering herd, no tight loop). Package-private and
     * {@link Random}-injected so the curve is unit-testable without
     * wall-clock waits.
     *
     * @param attempt 1-based consecutive-failure count.
     */
    static @NonNull Duration backoffDelay(int attempt,
                                          @NonNull Duration base,
                                          @NonNull Duration max,
                                          @NonNull Random random) {
        long maxMillis = max.toMillis();
        long exp = base.toMillis();
        for (int i = 1; i < attempt && exp < maxMillis; i++) {
            exp = Math.min(maxMillis, exp * 2);
        }
        long half = exp / 2;
        long jitter = half <= 0 ? 0 : random.nextLong(half + 1);
        return Duration.ofMillis(half + jitter);
    }
}
