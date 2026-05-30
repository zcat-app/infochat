package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;

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
}
