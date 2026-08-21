package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** M1-890 reproduction + pitfall tests: the zero-upstream-SMP-session wedge (live D-11).
 *  Wiring mirrors {@link SimpleXReconnectTest}; the poll is driven by direct
 *  {@code pollUpstreamSessions()} calls with a pinned {@link TestClock}. */
@DisabledOnOs(OS.WINDOWS)
class SimpleXSmpSessionLivenessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String SH = pickBinary("/bin/sh", "/usr/bin/sh");
    private static final int THRESHOLD = SimpleXAdapter.ZERO_SESSION_THRESHOLD;

    /** Recorded on the pinned v7.0.0.11 binary: fresh DB, no subscriptions. */
    private static final String ZERO_SUBS_FRAME = """
            {"corrId":"%s","resp":{"type":"agentSubs","activeSubs":{},"pendingSubs":{},"removedSubs":{}}}
            """;

    /** Recorded on the pinned v7.0.0.11 binary: healthy bot holding its contact-address subscription. */
    private static final String HEALTHY_SUBS_FRAME = """
            {"corrId":"%s","resp":{"type":"agentSubs","activeSubs":{"smp://PQUV2eL0t7OStZOoAsPEV2QYWt4-xilbakvGUGOItUo=@smp6.simplex.im,bylepyau3ty4czmn77q4fglvperknl4bi2eb2fdy2bh4jxtf32kf73yd.onion":1},"pendingSubs":{},"removedSubs":{}}}
            """;

    /** Recorded on the pinned v7.0.0.11 binary: a rejected command's chatCmdError. */
    private static final String CMD_ERROR_FRAME = """
            {"corrId":"%s","resp":{"type":"chatCmdError","chatError":{"type":"error","errorType":{"type":"commandError","message":"Failed reading: empty"}}}}
            """;

    @TempDir
    Path tempDir;

    @Test
    void sustainedZeroSessionsLatchesAndRestarts() throws Exception {
        // The reproduction: subprocess RUNNING, WS up, poll answers zero for
        // the threshold run → transport latched dead (connected() false) and
        // exactly one supervised child restart per latched episode.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            CapturingLogHandler logs = CapturingLogHandler.attach(SimpleXAdapter.class);
            TestClock clock = new TestClock(Instant.parse("2026-08-20T00:00:00Z"));
            SimpleXAdapter adapter = newAdapter(fake, clock, List.of(Duration.ofMillis(50)));
            SimpleXSubprocess sub = newStayAliveSubprocess(5, Duration.ofSeconds(30));
            sub.start();
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = startSubsResponder(fake, ZERO_SUBS_FRAME, responderFailure);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                assertTrue(adapter.connected(), "precondition: a live transport reads connected");

                // Below the consecutive threshold nothing fires (boot grace).
                pollExpectingZero(adapter, THRESHOLD - 1, responderFailure);
                assertTrue(adapter.connected(),
                        "sub-threshold zero readings must not latch the transport");
                assertEquals(0, sub.restartCount(),
                        "sub-threshold zero readings must not restart the child");

                // The threshold-th consecutive zero latches and restarts once.
                pollExpectingZero(adapter, 1, responderFailure);
                assertFalse(adapter.connected(),
                        "a sustained zero-session reading must latch the transport dead");
                // Latched-pending-restart window: the supervisor is still RUNNING,
                // so a send must classify TRANSIENT (retry rides out the restart),
                // never PERMANENT (messaging.md:460-462).
                MessagingException latchedFailure = assertThrows(MessagingException.class,
                        () -> adapter.send(outbound("latched-window")));
                assertEquals(FailureCategory.TRANSIENT, latchedFailure.category(),
                        "a send during the latched-pending-restart window is transient");
                awaitRestartCountAtLeast(sub, 1, WAIT);
                assertEquals(1, countZeroSessionWarns(logs),
                        "the latch WARNs once per episode");

                // FAILURE-MODE guard: polls inside the latched episode must not
                // re-fire the restart per poll — the episode is latched until
                // the child actually goes through a restart.
                for (int i = 0; i < 3; i++) {
                    adapter.pollUpstreamSessions();
                }
                Thread.sleep(300);
                assertEquals(1, sub.restartCount(),
                        "an unlatched poll loop would keep firing restarts inside one episode");
                assertEquals(1, countZeroSessionWarns(logs),
                        "no second WARN inside the latched episode");

                // New incarnation = new episode; the generation barrier lands
                // the rebuilt client's handshake before the next poll, or a
                // poll frame can race the fake's clientSocket swap.
                awaitSubprocessState(sub, SimpleXSubprocess.State.RUNNING, WAIT);
                fake.awaitClientGeneration(2, WAIT);
                pollExpectingZero(adapter, THRESHOLD, responderFailure);
                awaitRestartCountAtLeast(sub, 2, WAIT);
                assertEquals(2, countZeroSessionWarns(logs),
                        "each wedged incarnation latches and restarts exactly once");
            } finally {
                responder.interrupt();
                adapter.close();
                sub.stop();
                logs.detach();
            }
        }
    }

    @Test
    void subsResponderSurvivesFakeReaderErrorMarker() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            TestClock clock = new TestClock(Instant.parse("2026-08-20T00:00:00Z"));
            SimpleXAdapter adapter = newAdapter(fake, clock, List.of(Duration.ofMillis(50)));
            SimpleXSubprocess sub = newStayAliveSubprocess(5, Duration.ofSeconds(30));
            sub.start();
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = startSubsResponder(fake, ZERO_SUBS_FRAME, responderFailure);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                fake.enqueueReceivedFrame("__READER_ERROR__:IOException");
                assertEquals(SimpleXAdapter.SessionPollOutcome.ZERO_SESSIONS,
                        adapter.pollUpstreamSessions());
            } finally {
                responder.interrupt();
                adapter.close();
                sub.stop();
            }
        }
    }

    @Test
    void deadSubsResponderFailsFastNamingTheHarnessFault() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            TestClock clock = new TestClock(Instant.parse("2026-08-20T00:00:00Z"));
            SimpleXAdapter adapter = newAdapter(fake, clock, List.of(Duration.ofMillis(50)));
            SimpleXSubprocess sub = newStayAliveSubprocess(5, Duration.ofSeconds(30));
            sub.start();
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = startSubsResponder(fake, ZERO_SUBS_FRAME, responderFailure);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                fake.enqueueReceivedFrame("{not-json");
                AssertionError failure = assertThrows(AssertionError.class,
                        () -> pollExpectingZero(adapter, 1, responderFailure));
                assertTrue(failure.getMessage() != null
                                && failure.getMessage().contains("subs responder"),
                        failure.toString());
            } finally {
                responder.interrupt();
                adapter.close();
                sub.stop();
            }
        }
    }

    @Test
    void bootGraceZeroSessionsFireNothing() throws Exception {
        // P8: a freshly RUNNING subprocess legitimately holds zero sessions
        // until it connects; fewer-than-threshold consecutive zero readings
        // (pinned clock) fire nothing, the threshold-th fires exactly once.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            CapturingLogHandler logs = CapturingLogHandler.attach(SimpleXAdapter.class);
            TestClock clock = new TestClock(Instant.parse("2026-08-20T00:00:00Z"));
            SimpleXAdapter adapter = newAdapter(fake, clock, List.of(Duration.ofMillis(50)));
            SimpleXSubprocess sub = newStayAliveSubprocess(5, Duration.ofSeconds(30));
            sub.start();
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = startSubsResponder(fake, ZERO_SUBS_FRAME, responderFailure);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                pollExpectingZero(adapter, THRESHOLD - 1, responderFailure);
                assertTrue(adapter.connected());
                assertEquals(0, sub.restartCount());
                assertEquals(0, countZeroSessionWarns(logs),
                        "the grace window must not WARN — a slow first connect is normal");

                pollExpectingZero(adapter, 1, responderFailure);
                assertFalse(adapter.connected());
                awaitRestartCountAtLeast(sub, 1, WAIT);
                assertEquals(1, countZeroSessionWarns(logs),
                        "the threshold crossing fires exactly once");
            } finally {
                responder.interrupt();
                adapter.close();
                sub.stop();
                logs.detach();
            }
        }
    }

    @Test
    void pollSkipsWhileWebSocketDead() throws Exception {
        // P9: with the WS latched dead (peer close), the M1-674 rebuild
        // campaign owns recovery — the poll must not run, must not latch a
        // second death. A far-out ladder rung keeps the dead window stable.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            CapturingLogHandler logs = CapturingLogHandler.attach(SimpleXAdapter.class);
            TestClock clock = new TestClock(Instant.parse("2026-08-20T00:00:00Z"));
            SimpleXAdapter adapter = newAdapter(fake, clock, List.of(Duration.ofSeconds(30)));
            SimpleXSubprocess sub = newStayAliveSubprocess(5, Duration.ofSeconds(30));
            sub.start();
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = startSubsResponder(fake, ZERO_SUBS_FRAME, responderFailure);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                fake.killClientConnection();
                awaitDisconnected(adapter, WAIT);
                for (int i = 0; i < THRESHOLD + 2; i++) {
                    assertEquals(SimpleXAdapter.SessionPollOutcome.SKIPPED,
                            adapter.pollUpstreamSessions(),
                            "a latched-dead WebSocket must skip the poll");
                }
                assertEquals(0, sub.restartCount(),
                        "the zero-session arm must not restart while M1-674 owns recovery");
                assertEquals(0, countZeroSessionWarns(logs),
                        "no zero-session WARN on top of the peer-close latch");
            } finally {
                responder.interrupt();
                adapter.close();
                sub.stop();
                logs.detach();
            }
        }
    }

    @Test
    void pollCommandErrorIsNotZeroSessions() throws Exception {
        // P9: a failed poll command (here the recorded chatCmdError, surfacing
        // as a MessagingException out of sendCommand) is a transport fault the
        // existing routes own — never a zero-session reading.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            CapturingLogHandler logs = CapturingLogHandler.attach(SimpleXAdapter.class);
            TestClock clock = new TestClock(Instant.parse("2026-08-20T00:00:00Z"));
            SimpleXAdapter adapter = newAdapter(fake, clock, List.of(Duration.ofMillis(50)));
            SimpleXSubprocess sub = newStayAliveSubprocess(5, Duration.ofSeconds(30));
            sub.start();
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = startSubsResponder(fake, CMD_ERROR_FRAME, responderFailure);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                for (int i = 0; i < THRESHOLD + 2; i++) {
                    assertEquals(SimpleXAdapter.SessionPollOutcome.FAULT,
                            adapter.pollUpstreamSessions(),
                            "a failed poll command is a fault, not a zero-session reading");
                }
                assertTrue(adapter.connected(),
                        "command errors must not latch the transport dead");
                assertEquals(0, sub.restartCount());
                assertEquals(0, countZeroSessionWarns(logs));
            } finally {
                responder.interrupt();
                adapter.close();
                sub.stop();
                logs.detach();
            }
        }
    }

    @Test
    void livenessRestartCountsTowardCrashCap() throws Exception {
        // P6: the liveness restart counts against the crash cap. healthyUptime
        // =ZERO makes the streak reset deterministic: without the liveness-kill
        // marker every exit would reset and the wedged child would restart forever.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            TestClock clock = new TestClock(Instant.parse("2026-08-20T00:00:00Z"));
            SimpleXAdapter adapter = newAdapter(fake, clock, List.of(Duration.ofMillis(50)));
            List<String> adminNotices = new CopyOnWriteArrayList<>();
            SimpleXSubprocess sub = new SimpleXSubprocess(
                    List.of(SH, "-c", "exec sleep 30"),
                    Duration.ofMillis(10),
                    Duration.ofMillis(50),
                    /* crashCap */ 2,
                    adminNotices::add,
                    new Random(0L),
                    Duration.ZERO);
            sub.start();
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = startSubsResponder(fake, ZERO_SUBS_FRAME, responderFailure);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);

                pollExpectingZero(adapter, THRESHOLD, responderFailure);
                awaitRestartCountAtLeast(sub, 1, WAIT);
                awaitSubprocessState(sub, SimpleXSubprocess.State.RUNNING, WAIT);
                // Land the rebuilt client's handshake before episode 2 — see
                // sustainedZeroSessionsLatchesAndRestarts.
                fake.awaitClientGeneration(2, WAIT);
                pollExpectingZero(adapter, THRESHOLD, responderFailure);
                awaitSubprocessState(sub, SimpleXSubprocess.State.FAILED, WAIT);
                assertEquals(2, sub.consecutiveCrashes(),
                        "both liveness kills must count toward the cap");
                assertEquals(1, sub.restartCount(),
                        "the cap-exhausting exit must not respawn");
                assertEquals(1, sub.adminNotifications());
                assertEquals(1, adminNotices.size(),
                        "FAILED fires the one throttled adminNotifier call");
                Thread.sleep(300);
                assertEquals(1, sub.restartCount(),
                        "no further respawns after the terminal failure");

                MessagingException failure = assertThrows(MessagingException.class,
                        () -> adapter.send(outbound("post-failure")));
                assertEquals(FailureCategory.PERMANENT, failure.category(),
                        "the terminally-failed supervisor classifies sends PERMANENT");
            } finally {
                responder.interrupt();
                adapter.close();
                sub.stop();
            }
        }
    }

    @Test
    void warnLineCarriesCountsOnly() throws Exception {
        // P5 / D37: the zero-session WARN is fixed-vocabulary — poll count and
        // sustained window only; no server keys, contact ids, or exceptions.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            CapturingLogHandler logs = CapturingLogHandler.attach(SimpleXAdapter.class);
            TestClock clock = new TestClock(Instant.parse("2026-08-20T00:00:00Z"));
            SimpleXAdapter adapter = newAdapter(fake, clock, List.of(Duration.ofMillis(50)));
            SimpleXSubprocess sub = newStayAliveSubprocess(5, Duration.ofSeconds(30));
            sub.start();
            AtomicReference<Throwable> responderFailure = new AtomicReference<>();
            Thread responder = startSubsResponder(fake, ZERO_SUBS_FRAME, responderFailure);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                pollExpectingZero(adapter, 1, responderFailure);
                clock.advance(Duration.ofSeconds(30));
                pollExpectingZero(adapter, 1, responderFailure);
                clock.advance(Duration.ofSeconds(30));
                pollExpectingZero(adapter, 1, responderFailure);
                awaitRestartCountAtLeast(sub, 1, WAIT);
                String captured = logs.formatted();
                assertTrue(captured.contains("zero upstream SMP sessions")
                                && captured.contains(" 3") && captured.contains(" 60"),
                        "the WARN carries the poll count and the sustained window; got: "
                                + captured);
                assertFalse(captured.contains("smp://") || captured.contains(".onion")
                                || captured.contains("@"),
                        "the WARN must not carry server keys or contact-shaped ids; got: "
                                + captured);
            } finally {
                responder.interrupt();
                adapter.close();
                sub.stop();
                logs.detach();
            }
        }
    }

    // -- codec wire fixtures (step-0 recordings, pinned v7.0.0.11) -----------

    @Test
    void getSubsCommandEncodesTheRecordedEnvelope() {
        assertEquals("{\"corrId\":\"c1\",\"cmd\":\"/get subs\"}",
                SimpleXMessageCodec.encodeGetSubsCommand("c1"));
    }

    @Test
    void agentSubsZeroRecordedFrameDecodesToZeroCounts() {
        SimpleXMessageCodec.DecodedFrame decoded =
                SimpleXMessageCodec.decode(ZERO_SUBS_FRAME.formatted("p0"));
        SimpleXMessageCodec.AgentSubs subs =
                assertInstanceOf(SimpleXMessageCodec.AgentSubs.class, decoded);
        assertEquals("p0", subs.corrId());
        assertEquals(0, subs.activeSubscriptions());
        assertEquals(0, subs.pendingSubscriptions());
    }

    @Test
    void agentSubsHealthyRecordedFrameDecodesToSummedCounts() {
        SimpleXMessageCodec.DecodedFrame decoded =
                SimpleXMessageCodec.decode(HEALTHY_SUBS_FRAME.formatted("p1"));
        SimpleXMessageCodec.AgentSubs subs =
                assertInstanceOf(SimpleXMessageCodec.AgentSubs.class, decoded);
        assertEquals("p1", subs.corrId());
        assertEquals(1, subs.activeSubscriptions());
        assertEquals(0, subs.pendingSubscriptions());
    }

    @Test
    void agentSubsWithoutCountFieldsIsACommandError() {
        // Mutation of the recorded shape: dropping activeSubs must fail the
        // fixture as a PERMANENT CommandError — an Ignored would strand the
        // polling caller until its ack timeout.
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(
                "{\"corrId\":\"p0\",\"resp\":{\"type\":\"agentSubs\",\"pendingSubs\":{}}}");
        SimpleXMessageCodec.CommandError error =
                assertInstanceOf(SimpleXMessageCodec.CommandError.class, decoded);
        assertEquals(FailureCategory.PERMANENT, error.category());
        assertEquals("agentSubs-without-counts", error.detail());
    }

    // -- choreography helpers ------------------------------------------------

    private SimpleXAdapter newAdapter(FakeSimpleXProcess fake, TestClock clock,
                                      List<Duration> wsReconnectBackoff) {
        // binary/dataDir are never exercised: start() (where cfg.validate()
        // lives) is not called; only wsPort() is read by rebuildWebSocket().
        SimpleXConfig cfg = new SimpleXConfig(
                "/usr/bin/simplex-chat", tempDir.toString(), fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused here */ },
                wsReconnectBackoff,
                clock);
    }

    /** Supervised child that stays alive until killed ({@code exec sleep 30}):
     *  the wedge shape is a LIVE process, and each respawn lands in the same wedge. */
    private SimpleXSubprocess newStayAliveSubprocess(int crashCap, Duration healthyUptime) {
        return new SimpleXSubprocess(
                List.of(SH, "-c", "exec sleep 30"),
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                crashCap,
                msg -> { /* unused */ },
                new Random(0L),
                healthyUptime);
    }

    /** Background responder: answer every {@code /get subs} poll frame with the
     *  given recorded response (corrId echoed); other frames are ignored. */
    private static Thread startSubsResponder(FakeSimpleXProcess fake, String responseTemplate,
                                             AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().name("fake-simplex-subs-responder").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                String envelope;
                try {
                    envelope = fake.awaitFrame(Duration.ofSeconds(15));
                } catch (InterruptedException e) {
                    return;
                } catch (IllegalStateException e) {
                    failure.compareAndSet(null, e);
                    return;
                }
                if (!envelope.startsWith("{")) {
                    continue;
                }
                JsonNode corrId;
                JsonNode cmd;
                try {
                    JsonNode root = MAPPER.readTree(envelope);
                    corrId = root.get("corrId");
                    cmd = root.get("cmd");
                } catch (JsonProcessingException | RuntimeException e) {
                    failure.compareAndSet(null, e);
                    return;
                }
                if (corrId == null || cmd == null || !cmd.asText().startsWith("/get subs")) {
                    continue;
                }
                try {
                    fake.sendFrame(responseTemplate.formatted(corrId.asText()));
                } catch (java.io.IOException e) {
                    // The answer raced the client-generation swap; the new
                    // generation will issue the poll again.
                    continue;
                } catch (RuntimeException e) {
                    failure.compareAndSet(null, e);
                    return;
                }
            }
        });
    }

    /** Drive the poll until {@code zeros} ZERO_SESSIONS outcomes accumulate,
     *  tolerating SKIPPED (WS rebuild / restart in flight) with a short retry. */
    private static void pollExpectingZero(SimpleXAdapter adapter, int zeros,
                                          AtomicReference<Throwable> responderFailure)
            throws InterruptedException {
        int seen = 0;
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (seen < zeros && System.nanoTime() < deadline) {
            assertResponderAlive(responderFailure);
            SimpleXAdapter.SessionPollOutcome outcome = adapter.pollUpstreamSessions();
            assertResponderAlive(responderFailure);
            if (outcome == SimpleXAdapter.SessionPollOutcome.ZERO_SESSIONS) {
                seen++;
            } else if (outcome == SimpleXAdapter.SessionPollOutcome.SKIPPED) {
                TimeUnit.MILLISECONDS.sleep(20);
            } else {
                throw new AssertionError("expected a zero-session poll outcome, got " + outcome);
            }
        }
        assertResponderAlive(responderFailure);
        if (seen < zeros) {
            throw new AssertionError("only " + seen + " of " + zeros
                    + " expected zero-session polls landed within " + WAIT);
        }
    }

    private static void assertResponderAlive(AtomicReference<Throwable> responderFailure) {
        Throwable failure = responderFailure.get();
        if (failure != null) {
            throw new AssertionError("subs responder failed: " + failure, failure);
        }
    }

    private static int countZeroSessionWarns(CapturingLogHandler logs) {
        int count = 0;
        String captured = logs.formatted();
        int from = 0;
        while (true) {
            int at = captured.indexOf("zero upstream SMP sessions", from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + 1;
        }
    }

    private static void awaitDisconnected(SimpleXAdapter adapter, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (adapter.connected() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertFalse(adapter.connected(), "the peer close never latched the transport dead");
    }

    private static void awaitSubprocessState(SimpleXSubprocess sub,
                                             SimpleXSubprocess.State target,
                                             Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (sub.state() == target) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError("subprocess stayed " + sub.state()
                + " within " + timeout.toMillis() + " ms; expected " + target);
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
        throw new AssertionError("supervisor performed " + sub.restartCount()
                + " restarts; expected ≥" + target + " within " + timeout);
    }

    private static OutboundMessage outbound(String text) {
        return new OutboundMessage(
                new ScopeRef.Dm("alice-queue-addr"), text, Instant.now(), "corr-" + text);
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
}

/** Test seam — a mutable Clock advanced only via {@link #advance(Duration)}
 *  (the OutboundRateLimiterTest.TestClock pattern; top-level, never nested). */
final class TestClock extends Clock {

    private Instant now;

    TestClock(Instant initial) {
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
