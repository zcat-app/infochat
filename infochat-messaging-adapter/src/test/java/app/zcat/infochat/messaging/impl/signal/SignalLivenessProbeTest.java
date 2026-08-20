package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.OutboundRateLimiter;

import org.junit.jupiter.api.Test;

/** Drives the Signal connected-but-deaf liveness probe (probe timeouts feed
 *  the existing {@code recordTimeout} escalation) and the connected-but-silent
 *  WARN (never a restart trigger); injected Clock (§9) and scheduler throughout. */
class SignalLivenessProbeTest {

    private static final Duration PROBE_RESPONSE_TIMEOUT = Duration.ofMillis(150);
    private static final Duration GENEROUS_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long QUEUE_WAIT_MS = 2_000;
    private static final String ACCOUNT = "+15551111111";
    private static final Instant PINNED_NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void probeTimeoutEscalatesWithoutAnyUserTraffic() throws Exception {
        // The D-8 wedge: connected-but-deaf with ZERO user-driven call()
        // traffic — the scheduled probe's timeouts must still escalate.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            ControllableProbeScheduler scheduler = new ControllableProbeScheduler();
            MutableClock clock = new MutableClock(PINNED_NOW);
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), ACCOUNT, new SignalMessageCodec(), PROBE_RESPONSE_TIMEOUT,
                    restartCalls::incrementAndGet, SignalJsonRpcClient.INBOUND_QUEUE_CAPACITY,
                    new OutboundRateLimiter(1_000_000, clock),
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.attachLivenessProbe(scheduler, clock);
            client.connect();
            try {
                assertEquals(1, scheduler.scheduleInvocations(),
                        "connect() must schedule exactly one liveness task");
                assertEquals(SignalJsonRpcClient.LIVENESS_PROBE_INTERVAL.toMillis(),
                        scheduler.initialDelayMillis(),
                        "the probe must start one cadence after connect");
                assertEquals(SignalJsonRpcClient.LIVENESS_PROBE_INTERVAL.toMillis(),
                        scheduler.periodMillis(), "the probe must re-run on the cadence");
                for (int i = 0; i < 3; i++) {
                    CountDownLatch done = scheduler.fireCaptured();
                    JsonObject probe = fake.nextOutbound(QUEUE_WAIT_MS);
                    assertEquals("version", probe.getString("method"),
                            "the probe is a version frame — the only outbound with zero user traffic");
                    assertTrue(done.await(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS),
                            "probe " + (i + 1) + " must time out against the deaf daemon");
                    if (i < 2) {
                        assertEquals(0, restartCalls.get(),
                                "a single probe timeout is a transient — no restart before the threshold");
                    }
                }
                assertEquals(1, restartCalls.get(),
                        "three consecutive probe timeouts with zero user traffic must fire exactly one restart");
                assertTrue(client.isConnected(),
                        "the probe escalation restarts the daemon; it does not latch the channel");
            } finally {
                client.disconnect();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void probeAnswerProvesDaemonAliveResetsTimeoutStreak() throws Exception {
        // P2 / existing semantics: ANY daemon answer resets
        // consecutiveTimeouts — no stale counts into a spurious SIGKILL.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            ControllableProbeScheduler scheduler = new ControllableProbeScheduler();
            MutableClock clock = new MutableClock(PINNED_NOW);
            SignalJsonRpcClient client = newClient(fake, PROBE_RESPONSE_TIMEOUT,
                    restartCalls::incrementAndGet, clock, scheduler,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.connect();
            try {
                runProbeUnanswered(fake, scheduler);
                runProbeUnanswered(fake, scheduler);
                assertEquals(0, restartCalls.get(), "two probe timeouts stay under the threshold");
                // The third probe is ANSWERED: any answer proves the daemon
                // alive and resets the streak before it can escalate.
                runProbeAnswered(fake, scheduler);
                assertEquals(0, restartCalls.get(), "a daemon answer resets the timeout streak");
                // From zero again: the streak re-climbs and only then fires.
                runProbeUnanswered(fake, scheduler);
                runProbeUnanswered(fake, scheduler);
                assertEquals(0, restartCalls.get(),
                        "the reset streak must re-climb from zero — no stale counts");
                runProbeUnanswered(fake, scheduler);
                assertEquals(1, restartCalls.get(),
                        "exactly one restart when the RESET streak reaches the threshold");
            } finally {
                client.disconnect();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void silenceAloneNeverRestarts() throws Exception {
        // P1 FAILURE-MODE: silence is a healthy idle deployment's norm —
        // WARN once per crossing, NEVER restart; restarting on silence
        // kill-loops a quiet bot toward terminal FAILED.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            ControllableProbeScheduler scheduler = new ControllableProbeScheduler();
            MutableClock clock = new MutableClock(PINNED_NOW);
            CapturingLogHandler logs = CapturingLogHandler.attach(SignalJsonRpcClient.class);
            SignalJsonRpcClient client = newClient(fake, GENEROUS_RESPONSE_TIMEOUT,
                    restartCalls::incrementAndGet, clock, scheduler,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.connect();
            try {
                // The daemon answers every probe; no user traffic arrives.
                // Below the window edge: no WARN.
                clock.advance(SignalJsonRpcClient.SILENCE_WARN_WINDOW.minusMillis(1));
                runProbeAnswered(fake, scheduler);
                assertFalse(logs.formatted().contains("inbound-silent"),
                        "no WARN before the silence window is crossed");
                // Exactly at the edge: the WARN fires.
                clock.advance(Duration.ofMillis(1));
                runProbeAnswered(fake, scheduler);
                assertTrue(logs.formatted().contains("inbound-silent"),
                        "the WARN fires exactly at the window edge");
                assertEquals(0, restartCalls.get(), "silence alone must never restart");
                assertTrue(client.isConnected(), "a silent-but-answering channel stays connected");
                // WARN-once per crossing: staying silent adds no second WARN.
                clock.advance(Duration.ofMinutes(30));
                runProbeAnswered(fake, scheduler);
                assertEquals(1, occurrences(logs.formatted(), "inbound-silent"),
                        "the WARN fires once per silence crossing, not per probe tick");
                assertEquals(0, restartCalls.get(),
                        "held silent well past the window: still no restart");
                assertTrue(client.isConnected(), "connected() stays true throughout the silence");
                // Inbound traffic re-arms the crossing: the next silent
                // window WARNs again.
                LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
                client.setInboundHandler(delivered::add);
                fake.pushNotification("receive", receiveParams("hi", 1700000001000L));
                assertNotNull(delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS),
                        "the inbound notification must deliver (and re-stamp the silence window)");
                clock.advance(SignalJsonRpcClient.SILENCE_WARN_WINDOW.plusSeconds(5));
                runProbeAnswered(fake, scheduler);
                assertEquals(2, occurrences(logs.formatted(), "inbound-silent"),
                        "inbound traffic re-arms the WARN for the next crossing");
                assertEquals(0, restartCalls.get(), "still never a restart");
            } finally {
                logs.detach();
                client.disconnect();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void warnLineCarriesCountsOnly() throws Exception {
        // P5 / D37 log hygiene: the WARN line is a fixed vocabulary of
        // counts and durations; hostile inbound bytes must not reach any
        // log line.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            ControllableProbeScheduler scheduler = new ControllableProbeScheduler();
            MutableClock clock = new MutableClock(PINNED_NOW);
            CapturingLogHandler logs = CapturingLogHandler.attach(SignalJsonRpcClient.class);
            SignalJsonRpcClient client = newClient(fake, GENEROUS_RESPONSE_TIMEOUT,
                    restartCalls::incrementAndGet, clock, scheduler,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            client.setInboundHandler(delivered::add);
            client.connect();
            try {
                fake.pushNotification("receive",
                        receiveParams("HOSTILE-BODY-MARKER-42", 1700000001000L));
                assertNotNull(delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS),
                        "the hostile frame must deliver normally");
                clock.advance(SignalJsonRpcClient.SILENCE_WARN_WINDOW.plusSeconds(7));
                runProbeAnswered(fake, scheduler);
                String all = logs.formatted();
                assertTrue(all.contains("inbound-silent"), "the WARN must fire past the window");
                long expectedSilentSeconds = SignalJsonRpcClient.SILENCE_WARN_WINDOW.toSeconds() + 7;
                assertTrue(all.contains("inbound-silent for " + expectedSilentSeconds + " s (silence window "
                                + SignalJsonRpcClient.SILENCE_WARN_WINDOW.toSeconds() + " s)"),
                        "the WARN line is the fixed-vocabulary counts-and-durations shape");
                assertFalse(all.contains("HOSTILE-BODY-MARKER-42"),
                        "no message content in any log line");
                assertFalse(all.contains("HOSTILE-NAME-MARKER"),
                        "no sender display name in any log line");
                assertFalse(all.contains("11112222-3333-4444-5555-666677778888"),
                        "no sender id in any log line");
                assertEquals(0, restartCalls.get(), "the WARN is observability only");
            } finally {
                logs.detach();
                client.disconnect();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void probeRestartIsGenerationGatedAndSingleFire() throws Exception {
        // Part A — generation gate (RT-M1-681-r2-1 on the probe path): a
        // probe timeout on a replaced daemon generation fires NO restart,
        // exactly like the two existing detectors.
        AtomicLong daemonGen = new AtomicLong(1);
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            ControllableProbeScheduler scheduler = new ControllableProbeScheduler();
            MutableClock clock = new MutableClock(PINNED_NOW);
            SignalJsonRpcClient client = newClient(fake, PROBE_RESPONSE_TIMEOUT,
                    restartCalls::incrementAndGet, clock, scheduler, daemonGen::get);
            client.connect();
            try {
                daemonGen.incrementAndGet(); // supervised respawn; this connection's child is stale
                runProbeUnanswered(fake, scheduler);
                runProbeUnanswered(fake, scheduler);
                runProbeUnanswered(fake, scheduler);
                assertEquals(0, restartCalls.get(),
                        "a probe timeout on a replaced daemon generation must fire no restart");
                runProbeUnanswered(fake, scheduler);
                runProbeUnanswered(fake, scheduler);
                runProbeUnanswered(fake, scheduler);
                assertEquals(0, restartCalls.get(),
                        "the gate holds across repeated threshold crossings");
            } finally {
                client.disconnect();
                scheduler.shutdownNow();
            }
        }
        // Part B — single fire: the probe escalation wins the
        // restartRequested CAS first; the reader-exit latch observing the
        // same death must not fire a second restart.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            ControllableProbeScheduler scheduler = new ControllableProbeScheduler();
            MutableClock clock = new MutableClock(PINNED_NOW);
            SignalJsonRpcClient client = newClient(fake, PROBE_RESPONSE_TIMEOUT,
                    restartCalls::incrementAndGet, clock, scheduler,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.connect();
            try {
                runProbeUnanswered(fake, scheduler);
                runProbeUnanswered(fake, scheduler);
                runProbeUnanswered(fake, scheduler);
                assertEquals(1, restartCalls.get(), "three probe timeouts fire the restart");
                // The restart SIGKILLs the child; here the fake severs the
                // socket, which exits the reader into its latch.
                fake.killClientConnection();
                awaitTrue("the reader exit must latch the channel dead",
                        () -> !client.isConnected());
                assertEquals(1, restartCalls.get(),
                        "the reader-exit latch must not fire a second restart for the same death");
            } finally {
                client.disconnect();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void probeRunsOffReaderAndDispatchThreadsAndDrawsPacerToken() throws Exception {
        // P3: the probe executes on the injected scheduler's thread — never
        // the reader (its parked read() would deadlock on the probe's own
        // response) or dispatch — and draws one §6.3.6 pacer token.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            ControllableProbeScheduler scheduler = new ControllableProbeScheduler();
            MutableClock clock = new MutableClock(PINNED_NOW);
            OutboundRateLimiter pacer = new OutboundRateLimiter(1_000_000, clock);
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), ACCOUNT, new SignalMessageCodec(), GENEROUS_RESPONSE_TIMEOUT,
                    () -> { }, SignalJsonRpcClient.INBOUND_QUEUE_CAPACITY, pacer,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.attachLivenessProbe(scheduler, clock);
            client.connect();
            try {
                long tokensBefore = pacer.acquiredCount();
                CountDownLatch done = scheduler.fireCaptured();
                JsonObject probe = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("version", probe.getString("method"));
                fake.respondSuccess(probe.getString("id"),
                        Json.createObjectBuilder().add("versions", Json.createArrayBuilder()).build());
                assertTrue(done.await(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS),
                        "the answered probe must complete");
                Thread executing = scheduler.lastExecutionThread();
                assertNotNull(executing, "the probe task must record its execution thread");
                assertEquals("signal-liveness-probe", executing.getName(),
                        "the probe must execute on the injected scheduler's thread");
                assertNotSame(client.readerThread(), executing,
                        "the probe must never run on the reader thread (deadlock geometry)");
                assertEquals(tokensBefore + 1, pacer.acquiredCount(),
                        "the probe frame draws exactly one §6.3.6 pacer token");
            } finally {
                client.disconnect();
                scheduler.shutdownNow();
            }
        }
    }

    private static SignalJsonRpcClient newClient(FakeSignalCli fake, Duration responseTimeout,
                                                 Runnable hook, MutableClock clock,
                                                 ControllableProbeScheduler scheduler,
                                                 LongSupplier generation) {
        SignalJsonRpcClient client = new SignalJsonRpcClient(
                fake.endpoint(), ACCOUNT, new SignalMessageCodec(), responseTimeout,
                hook, SignalJsonRpcClient.INBOUND_QUEUE_CAPACITY,
                new OutboundRateLimiter(1_000_000, clock), generation);
        client.attachLivenessProbe(scheduler, clock);
        return client;
    }

    private static void runProbeUnanswered(FakeSignalCli fake, ControllableProbeScheduler scheduler)
            throws Exception {
        CountDownLatch done = scheduler.fireCaptured();
        fake.nextOutbound(QUEUE_WAIT_MS); // consumed, never answered
        assertTrue(done.await(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS),
                "the unanswered probe must time out");
    }

    private static void runProbeAnswered(FakeSignalCli fake, ControllableProbeScheduler scheduler)
            throws Exception {
        CountDownLatch done = scheduler.fireCaptured();
        JsonObject probe = fake.nextOutbound(QUEUE_WAIT_MS);
        assertEquals("version", probe.getString("method"));
        fake.respondSuccess(probe.getString("id"),
                Json.createObjectBuilder().add("versions", Json.createArrayBuilder()).build());
        assertTrue(done.await(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS),
                "the answered probe must complete");
    }

    private static JsonObject receiveParams(String body, long timestamp) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("source", "+15557654321")
                        .add("sourceUuid", "11112222-3333-4444-5555-666677778888")
                        .add("sourceName", "HOSTILE-NAME-MARKER")
                        .add("sourceDevice", 1)
                        .add("timestamp", timestamp)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", timestamp)
                                .add("message", body)))
                .build();
    }

    private static int occurrences(String haystack, String needle) {
        return haystack.split(needle, -1).length - 1;
    }

    private static void awaitTrue(String what, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUEUE_WAIT_MS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), what);
    }
}

/** Test seam — a mutable Clock advanced only via {@link MutableClock#advance}
 *  (the SimpleXSmpSessionLivenessTest.TestClock pattern; top-level, never nested). */
final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant initial) {
        this.now = initial;
    }

    void advance(Duration delta) {
        this.now = this.now.plus(delta);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public long millis() {
        return now.toEpochMilli();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
