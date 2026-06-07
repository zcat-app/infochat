package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Verifies the {@link SignalSubprocess} lifecycle (start/stop) and
 * crash-restart-with-backoff loop against a real OS subprocess. The
 * fake binary is {@code /bin/sh} executing a short script — present
 * on Linux + macOS development boxes and CI agents but not on
 * Windows, so the suite is OS-gated. Wall-clock margins are
 * generous (≥5× per design's flake-pad heuristic) to absorb CI
 * scheduling jitter.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class SignalSubprocessTest {

    // Aggressive backoff so the cap-exceeded transition completes
    // within a few hundred milliseconds — the production defaults
    // (base 250 ms) would slow the test by ~10×.
    private static final SignalSubprocess.BackoffPolicy FAST_BACKOFF =
            new SignalSubprocess.BackoffPolicy(/* baseMs */ 10, /* factor */ 2.0, /* capMs */ 200);

    private static final InetSocketAddress NEVER_PROBED =
            new InetSocketAddress("127.0.0.1", 0);

    @Test
    void startsAndStopsProcess() throws Exception {
        // A subprocess that lives for 30 s so the test can observe it
        // alive, then asserts stop() terminates it within a bounded
        // wait. Sleep is implemented in /bin/sh so we do not depend
        // on a separate sleep binary path.
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "sleep 30");
        SignalSubprocess sp = new SignalSubprocess(
                pb, NEVER_PROBED, FAST_BACKOFF, /* maxRestarts */ 0);
        sp.start();
        assertTrue(sp.isAlive(), "subprocess must be alive immediately after start()");
        assertEquals(SignalSubprocess.State.RUNNING, sp.state());
        sp.stop();
        long deadline = System.nanoTime() + 3_000_000_000L; // 3 s
        while (sp.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(sp.isAlive(), "subprocess must be dead within 3 s of stop()");
        assertEquals(SignalSubprocess.State.STOPPED, sp.state());
    }

    @Test
    void crashRestartWithBackoff() throws Exception {
        // A subprocess that exits immediately with rc=1; the wrapper
        // should re-spawn maxRestarts=3 times with exponential
        // backoff, then transition to FAILED. Total expected wall
        // time: ~3 * (avg jitter) + spawn overhead — bounded by the
        // 5 s deadline.
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "exit 1");
        int maxRestarts = 3;
        SignalSubprocess sp = new SignalSubprocess(
                pb, NEVER_PROBED, FAST_BACKOFF, maxRestarts);
        sp.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (sp.state() != SignalSubprocess.State.FAILED && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(
                SignalSubprocess.State.FAILED,
                sp.state(),
                "wrapper must transition to FAILED after restart cap is exceeded");
        assertTrue(
                sp.restartAttempts() > maxRestarts,
                "restart attempts (" + sp.restartAttempts() + ") must exceed cap (" + maxRestarts + ")");
        // After stop, no further restarts are scheduled — calling
        // stop() against the FAILED state is idempotent.
        sp.stop();
        assertEquals(SignalSubprocess.State.STOPPED, sp.state());
    }

    @Test
    void stopDuringRunDoesNotTriggerRestart() throws Exception {
        // The classic ProcessBuilder restart race: stop() sends
        // SIGTERM, the process exits, onExit fires, and a naive
        // implementation schedules a restart that races stop()'s
        // cleanup. The `stopping` flag must be checked first so the
        // SIGTERM-induced exit is consumed without restart.
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "sleep 30");
        SignalSubprocess sp = new SignalSubprocess(
                pb, NEVER_PROBED, FAST_BACKOFF, /* maxRestarts */ 5);
        sp.start();
        assertTrue(sp.isAlive());
        sp.stop();
        // Give the watchdog a chance to misbehave: if onExit
        // scheduled a restart, restartAttempts would tick up over
        // the next half-second.
        Thread.sleep(500);
        assertEquals(
                0,
                sp.restartAttempts(),
                "stop()-induced exit must not be counted as a crash");
        assertEquals(SignalSubprocess.State.STOPPED, sp.state());
    }

    @Test
    void restartFiresRegisteredListenerAfterSuccessfulSpawn() throws Exception {
        // The restart→reconnect contract (M1-185): each successful respawn
        // in doRestart() fires the registered listener so the adapter can
        // revive the JSON-RPC transport that died with the previous child.
        // An exit-1 script crashes immediately, so every restart iteration
        // spawns successfully and then exits — each spawn must fire.
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "exit 1");
        SignalSubprocess sp = new SignalSubprocess(
                pb, NEVER_PROBED, FAST_BACKOFF, /* maxRestarts */ 3);
        AtomicInteger restartNotifications = new AtomicInteger();
        sp.onRestart(restartNotifications::incrementAndGet);
        sp.start();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (restartNotifications.get() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(restartNotifications.get() >= 1,
                    "listener must fire after a successful supervised respawn; fired "
                            + restartNotifications.get() + " times");
        } finally {
            sp.stop();
        }
    }

    @Test
    void listenerNotFiredOnInitialStart() throws Exception {
        // The listener contract is restart-only: the initial start() spawn
        // is the adapter's own connect path, not a reconnect trigger.
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "sleep 30");
        SignalSubprocess sp = new SignalSubprocess(
                pb, NEVER_PROBED, FAST_BACKOFF, /* maxRestarts */ 5);
        AtomicInteger restartNotifications = new AtomicInteger();
        sp.onRestart(restartNotifications::incrementAndGet);
        sp.start();
        try {
            assertTrue(sp.isAlive());
            // Settle window: a misbehaving implementation that fires on the
            // initial spawn would have ticked the counter by now.
            Thread.sleep(300);
            assertEquals(0, restartNotifications.get(),
                    "initial start() must not fire the restart listener");
        } finally {
            sp.stop();
        }
    }
}
