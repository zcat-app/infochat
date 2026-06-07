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
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
class SimpleXSubprocessTest {

    private static final String SLEEP = pickBinary("/bin/sleep", "/usr/bin/sleep");
    private static final String TRUE = pickBinary("/bin/true", "/usr/bin/true");
    private static final String SH = pickBinary("/bin/sh", "/usr/bin/sh");

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
    void restartFiresRegisteredListener() throws Exception {
        // The restart→rebuild contract (M1-185): every successful launch
        // after the first fires the registered listener so the adapter can
        // rebuild the WebSocket client that died with the previous child.
        // /bin/true exits ~immediately, so each supervised restart is a
        // successful launch and must fire.
        AtomicInteger restartNotifications = new AtomicInteger();
        SimpleXSubprocess sub = new SimpleXSubprocess(
                List.of(TRUE),
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                /* crashCap */ 4,
                msg -> { /* unused */ },
                new Random(0L));
        sub.onRestart(restartNotifications::incrementAndGet);
        sub.start();
        try {
            awaitCountAtLeast(restartNotifications, 1, Duration.ofSeconds(2));
            assertTrue(restartNotifications.get() >= 1,
                    "listener must fire after a successful supervised restart; fired "
                            + restartNotifications.get() + " times");
        } finally {
            sub.stop();
        }
    }

    @Test
    void listenerNotFiredOnInitialLaunch() throws Exception {
        // The listener contract is restart-only: the first launch belongs
        // to start(), where the adapter builds its first WebSocket client
        // itself.
        AtomicInteger restartNotifications = new AtomicInteger();
        SimpleXSubprocess sub = new SimpleXSubprocess(
                List.of(SLEEP, "30"),
                Duration.ofMillis(5),
                Duration.ofMillis(20),
                /* crashCap */ 5,
                msg -> { /* unused */ },
                new Random(0L));
        sub.onRestart(restartNotifications::incrementAndGet);
        sub.start();
        try {
            awaitState(sub, SimpleXSubprocess.State.RUNNING, Duration.ofSeconds(2));
            // Settle window: a misbehaving implementation that fires on the
            // initial launch would have ticked the counter by now.
            Thread.sleep(300);
            assertEquals(0, restartNotifications.get(),
                    "initial launch must not fire the restart listener");
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

    @Test
    void drainStreamEmitsLifecycleEventsOnly() throws Exception {
        // M1-119 acceptance item 1: the application log receives only a
        // fixed-shape lifecycle marker (one per drain lifetime) — never
        // the raw byte content the subprocess emitted on stdout. The drain
        // still consumes the pipe so the subprocess does not deadlock on
        // a full buffer, but the bytes themselves go nowhere.
        CapturingLogHandler logCapture = CapturingLogHandler.attach(SimpleXSubprocess.class);
        try {
            SimpleXSubprocess sub = new SimpleXSubprocess(
                    List.of(SH, "-c", "echo DRAIN-MARKER-CHECK-BYTES; sleep 60"),
                    Duration.ofMillis(5),
                    Duration.ofMillis(20),
                    /* crashCap */ 5,
                    msg -> { /* unused */ },
                    new Random(0L));
            sub.start();
            try {
                awaitLogContains(logCapture,
                        "subprocess stdout output suppressed",
                        Duration.ofSeconds(2));
            } finally {
                sub.stop();
            }
            String captured = logCapture.formatted();
            assertTrue(captured.contains("subprocess stdout output suppressed"),
                    "expected fixed-shape suppression marker; captured: " + captured);
            assertFalse(captured.contains("DRAIN-MARKER-CHECK-BYTES"),
                    "raw subprocess output bytes must not reach the log; captured: "
                            + captured);
            long stdoutMarkers = captured.split(
                    "subprocess stdout output suppressed", -1).length - 1;
            assertEquals(1L, stdoutMarkers,
                    "exactly one stdout marker per drain lifetime; captured: " + captured);
        } finally {
            logCapture.detach();
        }
    }

    @Test
    void drainStreamDoesNotLeakSubprocessOutput() throws Exception {
        // M1-119 acceptance item 2: sentinel-string proof that a byte
        // sequence the test owns, written to the fake subprocess's stdout,
        // does NOT appear anywhere in the captured application log.
        String sentinel = "REDTEAM-SENTINEL-XXXXX";
        CapturingLogHandler logCapture = CapturingLogHandler.attach(SimpleXSubprocess.class);
        try {
            SimpleXSubprocess sub = new SimpleXSubprocess(
                    List.of(SH, "-c", "echo " + sentinel + "; sleep 60"),
                    Duration.ofMillis(5),
                    Duration.ofMillis(20),
                    /* crashCap */ 5,
                    msg -> { /* unused */ },
                    new Random(0L));
            sub.start();
            try {
                // Wait for the suppression marker — proves the drain has
                // already read past the sentinel-bearing line.
                awaitLogContains(logCapture,
                        "subprocess stdout output suppressed",
                        Duration.ofSeconds(2));
            } finally {
                sub.stop();
            }
            String captured = logCapture.formatted();
            assertFalse(captured.contains(sentinel),
                    "subprocess stdout bytes must not reach the log; captured: "
                            + captured);
        } finally {
            logCapture.detach();
        }
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

    private static void awaitCountAtLeast(AtomicInteger counter,
                                          int target,
                                          Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (counter.get() >= target) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    private static void awaitLogContains(CapturingLogHandler capture,
                                         String needle,
                                         Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (capture.formatted().contains(needle)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError(
                "expected captured log to contain `" + needle + "` within "
                        + timeout + "; captured: " + capture.formatted());
    }

    /**
     * Test-only JUL handler that records every {@link LogRecord} a target
     * named logger publishes. Attaches to BOTH the jboss-logmanager Logger
     * and the JUL Logger so the capture is robust to whether
     * jboss-logmanager is the active LogManager under surefire (the same
     * pattern as {@code InboundRouterContactIdRedactionTest} in
     * infochat-provider — see CLAUDE.md feedback memory
     * "avoid-test-inner-classes": one named inner per file is within the
     * &gt;3 rule of thumb).
     */
    private static final class CapturingLogHandler extends Handler {

        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final org.jboss.logmanager.Logger jbossLogger;
        private final Logger julLogger;

        private CapturingLogHandler(org.jboss.logmanager.Logger jbossLogger,
                                    Logger julLogger) {
            this.jbossLogger = jbossLogger;
            this.julLogger = julLogger;
            jbossLogger.addHandler(this);
            julLogger.addHandler(this);
        }

        static CapturingLogHandler attach(Class<?> target) {
            org.jboss.logmanager.Logger jboss =
                    LogContext.getLogContext().getLogger(target.getName());
            Logger jul = Logger.getLogger(target.getName());
            return new CapturingLogHandler(jboss, jul);
        }

        void detach() {
            jbossLogger.removeHandler(this);
            julLogger.removeHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }

        String formatted() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord r : records) {
                sb.append(r.getLevel()).append(": ").append(r.getMessage()).append("; ");
            }
            return sb.append("]").toString();
        }
    }
}
