package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Owns the simplex-chat OS process. Spawns it via {@link ProcessBuilder},
 * drains its stdout/stderr to SLF4J via virtual-thread loops, and runs a
 * supervisor that restarts the process on unexpected exit with
 * exponential backoff + equal jitter (delay uniform in {@code [exp/2,
 * exp]}, see {@link #backoffDelay}) until a profile-driven crash cap is
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

    /**
     * Uptime a process must accumulate before a crash for the
     * consecutive-crash streak to reset to zero. "Consecutive" means
     * "without intervening healthy uptime" (design §6.4.6): a long-lived
     * daemon that crashes once after a long session must not latch
     * monotonically toward {@link #DEFAULT_CRASH_CAP} over the host's whole
     * lifetime. 30 s is the conservative threshold from the ticket §Notes —
     * long enough that a genuine crash-loop (immediate re-exit) never clears
     * it, short enough that any real session does.
     */
    static final Duration DEFAULT_HEALTHY_UPTIME = Duration.ofSeconds(30);

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
    private final Duration healthyUptime;

    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private final AtomicInteger adminNotifications = new AtomicInteger(0);
    // "Consecutive" crash streak (design §6.4.6): reset to zero whenever a
    // process ran past healthyUptime before crashing. A field rather than a
    // runSupervisor local so the supervisor can reset it from supervise() and
    // so it is observable via consecutiveCrashes(). Mutated only on the single
    // supervisor virtual thread; AtomicInteger carries the value to readers.
    private final AtomicInteger consecutiveCrashes = new AtomicInteger(0);

    // Null until start() launches the process and its supervisor thread;
    // every read copies to a local and guards on null before use.
    private volatile @Nullable Process currentProcess;
    private volatile @Nullable Thread supervisor;
    private volatile boolean stopping = false;
    // Stamped by restartHung() before it SIGKILLs the child; supervise()
    // consumes it to skip the healthy-uptime streak reset for a
    // liveness-driven exit (a natural exit racing the stamp consumes it too).
    private volatile boolean livenessKillPending = false;
    // Seeded to a no-op so the fire site never needs a null check.
    private volatile Runnable restartListener = () -> { };
    // Off-loopback bind guard (M1-430, trust boundary #7): evaluated once on the
    // supervisor thread immediately after the FIRST launch reaches RUNNING.
    // Returns true when the chat-server port is reachable on a non-loopback
    // interface, which fails the subprocess fast (kill child + admin notify +
    // FAILED) rather than serving the credential-free WebSocket off loopback.
    // Seeded to a no-op (loopback-only) so the capability-only path and the
    // process-lifecycle tests never trip it; production wiring (SimpleXAdapter)
    // binds the real probe via onStartupBindCheck() BEFORE start(), so the
    // volatile write happens-before the supervisor thread's read.
    private volatile BooleanSupplier offLoopbackBindCheck = () -> false;

    SimpleXSubprocess(List<String> command,
                      Duration backoffBase,
                      Duration backoffMax,
                      int crashCap,
                      Consumer<String> adminNotifier,
                      Random random) {
        this(command, backoffBase, backoffMax, crashCap, adminNotifier, random,
                DEFAULT_HEALTHY_UPTIME);
    }

    // healthyUptime is injectable (package-private) so the consecutive-crash
    // reset test pins it to a few tens of ms rather than waiting out the
    // 30 s production default — the same test seam as the Random injection.
    SimpleXSubprocess(List<String> command,
                      Duration backoffBase,
                      Duration backoffMax,
                      int crashCap,
                      Consumer<String> adminNotifier,
                      Random random,
                      Duration healthyUptime) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must be non-empty");
        }
        this.command = List.copyOf(command);
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.crashCap = crashCap;
        this.adminNotifier = adminNotifier;
        this.random = random;
        this.healthyUptime = healthyUptime;
    }

    /**
     * Default basename for the {@code -d} db prefix; matches simplex-chat's
     * own default basename ({@code ~/.simplex/simplex_v1}).
     */
    static final String DB_PREFIX_BASENAME = "simplex_v1";

    /**
     * Build the simplex-chat invocation from a {@link SimpleXConfig}. Flags
     * verified against the pinned simplex-chat v6.5.4 binary (M1-429 spike).
     *
     * <p>{@code -d} takes a path PREFIX, not a directory: simplex-chat writes
     * its identity files as {@code <prefix>_chat.db} / {@code <prefix>_agent.db}.
     * The prefix is placed INSIDE the configured data-dir
     * ({@code <data-dir>/simplex_v1}) so those files land within the
     * bind-mounted directory; a bare directory prefix would write them as
     * siblings OUTSIDE the mount, making the bot's SimpleX identity (the D10
     * trust anchor) ephemeral across container recreation.</p>
     *
     * <p>{@code -p} runs the chat server — the WebSocket the adapter connects
     * to — on the configured port (v6.5.4 has no {@code --network} option).
     * The pinned v6.5.4 binary binds that server to {@code 127.0.0.1} only
     * (verified against the SHA-pinned binary, M1-429 spike: {@code ss} showed
     * a single {@code LISTEN 127.0.0.1:<port>} socket and no {@code 0.0.0.0} /
     * {@code ::} listener), so the loopback guarantee of {@code docs/spec/security.md}
     * trust boundary #7 — the unauthenticated bot WebSocket is safe only while
     * it stays loopback — rests on this default. {@code -p}'s syntax carries no
     * host argument to make the bind explicit, so a change to this launch flag
     * MUST re-verify the bind interface is still loopback.</p>
     */
    static List<String> commandFor(SimpleXConfig config) {
        String dbPrefix = Path.of(config.dataDir()).resolve(DB_PREFIX_BASENAME).toString();
        return List.of(
                config.binary(),
                "-d", dbPrefix,
                "-p", Integer.toString(config.wsPort()));
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
        destroyCurrentProcess();
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

    /**
     * SIGTERM the live child, wait up to {@link #SHUTDOWN_GRACE}, then SIGKILL
     * if it is still alive. No-op when no process is running. Shared by
     * {@link #stop()} (operator teardown) and the off-loopback bind fail path
     * (M1-430), which must close the exposed socket before latching FAILED.
     */
    private void destroyCurrentProcess() {
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
    }

    /** Force-restart a detected-deaf child (M1-890): SIGKILL routes recovery through the
     *  supervise→backoff→cap path a crash takes; livenessKillPending keeps it counting. */
    void restartHung() {
        if (stopping) {
            return;
        }
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            // SIGKILL, not SIGTERM: the child is wedged by detection, so a
            // graceful terminate it may ignore would only delay recovery.
            livenessKillPending = true;
            p.destroyForcibly();
        }
    }

    State state() {
        // The AtomicReference is seeded non-null (State.NOT_STARTED) and only
        // ever CAS'd to non-null State values; NullAway models
        // AtomicReference.get() as @Nullable, so assert the invariant here.
        return Objects.requireNonNull(state.get());
    }

    /** How many times the supervisor has restarted the process. */
    int restartCount() {
        return restartCount.get();
    }

    /**
     * Crashes in the current streak — reset to zero whenever a process runs
     * past {@code healthyUptime} before crashing (design §6.4.6), so this is
     * "crashes without intervening healthy uptime", not crashes over the
     * whole host lifetime.
     */
    int consecutiveCrashes() {
        return consecutiveCrashes.get();
    }

    /** How many admin-notification calls fired (one per FAILED transition). */
    int adminNotifications() {
        return adminNotifications.get();
    }

    private void runSupervisor() {
        // The first launchProcess() attempt belongs to start(); every later
        // iteration (crash-restart or launch-failure-retry) is a restart, and
        // a successful launch there must notify the adapter so it rebuilds
        // the WebSocket client (design §6.4.6: one supervised unit).
        boolean restartIteration = false;
        while (!stopping) {
            Process process;
            try {
                process = launchProcess();
            } catch (IOException e) {
                // A launch that never produced a live process accumulated no
                // healthy uptime, so it always counts toward the streak.
                LOG.warn("simplex-chat launch failed: {}", e.getClass().getSimpleName());
                restartIteration = true;
                int crashes = consecutiveCrashes.incrementAndGet();
                if (handleCrashCap(crashes)) {
                    return;
                }
                if (!sleepForBackoff(crashes)) {
                    return;
                }
                restartCount.incrementAndGet();
                continue;
            }
            currentProcess = process;
            state.set(State.RUNNING);
            if (restartIteration) {
                notifyRestartListener();
            } else if (offLoopbackBindCheck.getAsBoolean()) {
                // First launch only (M1-430): an off-loopback bind voids trust
                // boundary #7 — the credential-free WebSocket must stay loopback.
                // Kill the child so the exposed socket is actually closed (not
                // merely left unattached), then fail on the same terminal path
                // as crash-cap exhaustion. Re-checking on restarts is pointless:
                // the bind interface is fixed by the binary's -p default, which
                // restarting the same binary cannot change.
                destroyCurrentProcess();
                failToAdmin("simplex-chat chat-server port is reachable on a"
                        + " non-loopback interface; refusing to serve the"
                        + " credential-free WebSocket off loopback");
                return;
            }
            restartIteration = true;
            supervise(process);
            if (stopping || state.get() == State.FAILED) {
                return;
            }
        }
    }

    /**
     * Register a callback fired after each successful supervised respawn
     * (never on the initial {@link #start()} launch). This is the
     * supervisor→adapter notification the class javadoc promises: the
     * adapter rebuilds {@link SimpleXWebSocketClient} in response. Fires on
     * the supervisor virtual thread — implementations must hop to their own
     * thread before blocking (a WebSocket-ready probe here would delay
     * {@code waitFor} crash detection). Last registration wins.
     */
    void onRestart(Runnable listener) {
        this.restartListener = listener;
    }

    /**
     * Register the startup off-loopback bind check (M1-430). MUST be called
     * before {@link #start()} so the registration is visible to the supervisor
     * thread: the supervisor evaluates it exactly once, immediately after the
     * first launch reaches {@link State#RUNNING}, and a {@code true} result
     * fails the subprocess on the same terminal path as crash-cap exhaustion
     * ({@link State#FAILED} + one throttled {@code adminNotifier} call). The
     * check owns its own readiness timing — the bind interface is only
     * observable once the port is bound. Last registration wins.
     */
    void onStartupBindCheck(BooleanSupplier check) {
        this.offLoopbackBindCheck = check;
    }

    private void notifyRestartListener() {
        try {
            restartListener.run();
        } catch (RuntimeException e) {
            // Same discipline as the adminNotifier: a buggy listener must
            // not kill the supervisor loop.
            LOG.warn("restart listener threw: {}", e.getClass().getSimpleName());
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

    private void supervise(Process process) {
        long startNanos = System.nanoTime();
        int crashes;
        try {
            int exitCode = process.waitFor();
            if (stopping) {
                return;
            }
            LOG.warn("simplex-chat exited unexpectedly with code {}", exitCode);
            // A process that ran past the healthy-uptime threshold before
            // crashing breaks the streak: "consecutive" means "without
            // intervening healthy uptime" (design §6.4.6), so the prior
            // crashes are no longer consecutive with this one. A liveness
            // kill (restartHung) is exempt: the child was detected deaf,
            // not healthy — the wedge must accumulate toward the cap.
            boolean livenessKill = livenessKillPending;
            livenessKillPending = false;
            if (!livenessKill
                    && System.nanoTime() - startNanos >= healthyUptime.toNanos()) {
                consecutiveCrashes.set(0);
            }
            crashes = consecutiveCrashes.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (handleCrashCap(crashes)) {
            return;
        }
        state.set(State.RESTARTING);
        if (!sleepForBackoff(crashes)) {
            return;
        }
        restartCount.incrementAndGet();
    }

    private boolean handleCrashCap(int consecutiveCrashes) {
        if (consecutiveCrashes < crashCap) {
            return false;
        }
        // The "throttled" admin notification commitment (acceptance item 4)
        // resolves to a single notify at the FAILED transition — the
        // supervisor stops looping after this, so a subsequent flood is
        // structurally impossible.
        failToAdmin("simplex-chat subprocess crashed " + consecutiveCrashes
                + " consecutive times; supervisor giving up");
        return true;
    }

    /**
     * Shared terminal fail path: fire the throttled {@code adminNotifier} and
     * latch {@link State#FAILED}. Used by both crash-cap exhaustion
     * ({@link #handleCrashCap}) and the off-loopback bind guard (M1-430) so the
     * two failures reach the admin over one channel.
     *
     * <p>Notify (and bump the counter) BEFORE the FAILED flip: the
     * {@link State#FAILED} javadoc promises "admin notified", so an observer of
     * FAILED must see the notification already delivered. {@code state.set} is a
     * volatile write and {@link #state()} a volatile read, so everything
     * sequenced here is visible to any thread that observes FAILED. The message
     * is independent of state, so the ordering does not change what the notifier
     * receives. A buggy notifier (operator-side wiring, M1-105) must not leak
     * past the supervisor.</p>
     */
    private void failToAdmin(String message) {
        adminNotifications.incrementAndGet();
        try {
            adminNotifier.accept(message);
        } catch (RuntimeException e) {
            LOG.warn("admin notifier threw: {}", e.getClass().getSimpleName());
        }
        state.set(State.FAILED);
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

    // Per D37 (security.md §User-content logging) the bodies of inbound
    // chat-mode messages MUST NOT appear in non-audit logs. simplex-chat
    // may emit envelopes (contact ids, message bodies) on its own stdout
    // depending on its log level, so the drain reads the pipe purely to
    // prevent a deadlocked buffer and discards the bytes — at most one
    // fixed-shape marker per drain lifetime announces that output exists.
    // The marker carries no bytes from the stream. See docs/design/06-messaging.md
    // §6.4.8 for the chosen policy and rationale.
    private static void drainStream(InputStream in, boolean stderr) {
        boolean markerEmitted = false;
        byte[] buf = new byte[4096];
        try (InputStream stream = in) {
            while (true) {
                int n = stream.read(buf);
                if (n < 0) {
                    return;
                }
                if (n > 0 && !markerEmitted) {
                    markerEmitted = true;
                    if (stderr) {
                        LOG.info("simplex-chat subprocess stderr output suppressed");
                    } else {
                        LOG.info("simplex-chat subprocess stdout output suppressed");
                    }
                }
                // bytes in buf[0..n-1] are intentionally discarded.
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
    static Duration backoffDelay(int attempt,
                                          Duration base,
                                          Duration max,
                                          Random random) {
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
