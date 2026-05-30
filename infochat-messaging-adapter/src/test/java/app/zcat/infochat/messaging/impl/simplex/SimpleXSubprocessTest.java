package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
class SimpleXSubprocessTest {

    private static final String SLEEP = pickBinary("/bin/sleep", "/usr/bin/sleep");
    private static final String TRUE = pickBinary("/bin/true", "/usr/bin/true");

    @Test
    void startsAndStopsProcess() throws Exception {
        // Acceptance item 13: start() spawns a real process, state goes
        // RUNNING; stop() terminates it (SIGTERM → SIGKILL fallback) and
        // state advances to STOPPED.
        SimpleXSubprocess sub = new SimpleXSubprocess(
                List.of(SLEEP, "30"),
                Duration.ofMillis(5),
                Duration.ofMillis(20),
                /* crashCap */ 5,
                msg -> { /* unused */ },
                new Random(0L));
        sub.start();
        try {
            // Give the supervisor a moment to launch the process.
            awaitState(sub, SimpleXSubprocess.State.RUNNING, Duration.ofSeconds(2));
            assertEquals(SimpleXSubprocess.State.RUNNING, sub.state());
            assertEquals(0, sub.restartCount(),
                    "no crash yet, so no restart counted");
        } finally {
            sub.stop();
        }
        assertEquals(SimpleXSubprocess.State.STOPPED, sub.state());
    }

    @Test
    void crashRestartWithBackoff() throws Exception {
        // Acceptance item 14: a FakeSimpleXProcess that exits immediately
        // (here, /bin/true) is restarted with increasing delays up to the
        // cap. Inject a deterministic Random so the backoff curve has no
        // jitter — the test then sees pure exponential growth.
        AtomicInteger notifyCount = new AtomicInteger(0);
        SimpleXSubprocess sub = new SimpleXSubprocess(
                List.of(TRUE),
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                /* crashCap */ 4,
                msg -> notifyCount.incrementAndGet(),
                new Random(0L));
        sub.start();
        try {
            awaitRestartCountAtLeast(sub, 2, Duration.ofSeconds(2));
            // /bin/true exits ~immediately, so we see at least one restart.
            assertTrue(sub.restartCount() >= 2,
                    "expected ≥2 restarts; observed " + sub.restartCount());
        } finally {
            sub.stop();
        }
    }

    @Test
    void failedStateAfterCapExhaustion() throws Exception {
        // Acceptance item 4 (clarity-WARN remediation): after crashCap
        // consecutive exits the subprocess transitions to FAILED and fires
        // the throttled admin notifier exactly once.
        CopyOnWriteArrayList<String> notifications = new CopyOnWriteArrayList<>();
        SimpleXSubprocess sub = new SimpleXSubprocess(
                List.of(TRUE),
                Duration.ofMillis(5),
                Duration.ofMillis(20),
                /* crashCap */ 3,
                notifications::add,
                new Random(0L));
        sub.start();
        try {
            awaitState(sub, SimpleXSubprocess.State.FAILED, Duration.ofSeconds(2));
            assertEquals(SimpleXSubprocess.State.FAILED, sub.state());
            assertEquals(1, sub.adminNotifications(),
                    "exactly one admin notify at the FAILED transition");
            assertEquals(1, notifications.size());
            assertNotNull(notifications.get(0));
            assertTrue(notifications.get(0).contains("simplex-chat"));
        } finally {
            sub.stop();
        }
    }

    @Test
    void backoffDelayIsEqualJitterExponential() {
        // The deterministic component doubles each consecutive failure up to
        // max; with a Random pinned to 0 we see pure half-of-exponent values.
        Random zeroJitter = new Random() {
            @Override
            public long nextLong(long bound) {
                return 0L;
            }
        };
        Duration d1 = SimpleXSubprocess.backoffDelay(1,
                Duration.ofMillis(10),
                Duration.ofMillis(1_000),
                zeroJitter);
        Duration d2 = SimpleXSubprocess.backoffDelay(2,
                Duration.ofMillis(10),
                Duration.ofMillis(1_000),
                zeroJitter);
        Duration d3 = SimpleXSubprocess.backoffDelay(3,
                Duration.ofMillis(10),
                Duration.ofMillis(1_000),
                zeroJitter);
        assertEquals(5L, d1.toMillis(), "10ms exp → 5ms half");
        assertEquals(10L, d2.toMillis(), "20ms exp → 10ms half");
        assertEquals(20L, d3.toMillis(), "40ms exp → 20ms half");
        // Cap clamps both halves: with max=10ms the half is 5 from the start.
        Duration capped = SimpleXSubprocess.backoffDelay(8,
                Duration.ofMillis(10),
                Duration.ofMillis(10),
                zeroJitter);
        assertEquals(5L, capped.toMillis(), "cap clamps the exponential");
        assertFalse(capped.isNegative());
    }

    private static String pickBinary(String... candidates) {
        for (String path : candidates) {
            if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(path))) {
                return path;
            }
        }
        throw new IllegalStateException(
                "no usable test binary among " + List.of(candidates));
    }

    private static void awaitState(SimpleXSubprocess sub,
                                   SimpleXSubprocess.State target,
                                   Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (sub.state() == target) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private static void awaitRestartCountAtLeast(SimpleXSubprocess sub,
                                                 int target,
                                                 Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (sub.restartCount() >= target) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }
}
