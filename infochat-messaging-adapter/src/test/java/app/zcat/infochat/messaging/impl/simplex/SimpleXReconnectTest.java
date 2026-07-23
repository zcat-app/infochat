package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the M1-185 restart→rebuild contract for the SimpleX transport:
 * after the supervisor restarts the simplex-chat subprocess, the adapter
 * rebuilds its WebSocket client (the {@link SimpleXWebSocketClient} is
 * terminal after close, so revival is a fresh instance) and traffic
 * resumes; a send during the gap fails TRANSIENT via the adapter's
 * {@code reconnecting} flag; and an inbound frame pushed after the
 * rebuild is delivered exactly once.
 *
 * <p>Wiring: full 3-arg {@link SimpleXAdapter} constructor with a
 * {@link SimpleXConfig} whose ws-port is the fake's (the constructor
 * never validates; only {@code start()} calls {@code cfg.validate()}),
 * plus the package-private {@code attachSubprocess}/{@code
 * rebuildWebSocket} seams instead of {@code start()} (which needs a
 * real simplex-chat binary). The supervised restart is driven by a real
 * {@link SimpleXSubprocess} over a flag-file shell script: the child
 * waits for a DIE file, exits exactly once, and the respawned child
 * {@code exec sleep 30} — so the test controls the death timing and the
 * supervisor performs exactly one restart through its production
 * supervise→backoff→relaunch→listener path.</p>
 */
@DisabledOnOs(OS.WINDOWS)
class SimpleXReconnectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final String SH = pickBinary("/bin/sh", "/usr/bin/sh");

    @TempDir
    Path tempDir;

    @Test
    void sendDuringOutageFailsTransient() throws Exception {
        // The fake is closed BEFORE the supervised restart, so the
        // reconnect handler's WebSocket-ready probe cannot succeed: the
        // `reconnecting` window is held open deterministically (no racing
        // the rebuild) while the send's category is asserted.
        FakeSimpleXProcess fake = new FakeSimpleXProcess();
        fake.start();
        SimpleXAdapter adapter = newAdapter(fake);
        SimpleXSubprocess sub = newOneShotSubprocess();
        sub.start();
        try {
            adapter.attachSubprocess(sub);
            adapter.rebuildWebSocket();
            fake.awaitClient(WAIT);
            fake.close();
            touchDieFlag();
            awaitRestartCountAtLeast(sub, 1, Duration.ofSeconds(5));
            // Settle window: the restart listener fires within
            // milliseconds of the relaunch; after it the reconnect thread
            // sits in the (unsatisfiable) ready probe with `reconnecting`
            // set, so the gap below is wide and stable.
            Thread.sleep(500);
            MessagingException failure = null;
            try {
                adapter.send(outbound("during-outage"));
            } catch (MessagingException e) {
                failure = e;
            }
            assertNotNull(failure, "send into the reconnect gap must fail");
            assertEquals(FailureCategory.TRANSIENT, failure.category(),
                    "gap send must classify TRANSIENT so Provider's retry"
                            + " machinery treats the outage as recoverable");
        } finally {
            adapter.close();
            fake.close();
        }
    }

    @Test
    void closeMidReconnectClassifiesSubsequentSendsPermanent() throws Exception {
        // U-14: park a reconnect in its (unsatisfiable) WebSocket-ready probe
        // so the `reconnecting` flag is held true (the same deterministic
        // mechanism as sendDuringOutageFailsTransient), then close() the
        // adapter while that flag is still set. close() owns no part of
        // `reconnecting` — the parked reconnect still holds it set — so the
        // terminal closedForGood guard, checked BEFORE reconnecting in
        // requireConnected, is what must classify the post-close send
        // PERMANENT. Without that ordering the stale reconnecting flag would
        // yield TRANSIENT, looping the Provider's retry forever against a
        // transport that will never come back.
        FakeSimpleXProcess fake = new FakeSimpleXProcess();
        fake.start();
        SimpleXAdapter adapter = newAdapter(fake);
        SimpleXSubprocess sub = newOneShotSubprocess();
        sub.start();
        try {
            adapter.attachSubprocess(sub);
            adapter.rebuildWebSocket();
            fake.awaitClient(WAIT);
            fake.close();
            touchDieFlag();
            awaitRestartCountAtLeast(sub, 1, Duration.ofSeconds(5));
            // Settle: the reconnect thread is now parked in the (unsatisfiable)
            // ready probe with `reconnecting` set.
            Thread.sleep(500);
            // Precondition — while open and reconnecting, the gap send is TRANSIENT.
            MessagingException duringOutage = assertThrows(MessagingException.class,
                    () -> adapter.send(outbound("pre-close")));
            assertEquals(FailureCategory.TRANSIENT, duringOutage.category(),
                    "an open, reconnecting adapter classifies the gap send TRANSIENT");
            // Close mid-reconnect: closedForGood latches; the parked reconnect
            // still owns the (set) reconnecting flag.
            adapter.close();
            MessagingException afterClose = assertThrows(MessagingException.class,
                    () -> adapter.send(outbound("post-close")));
            assertEquals(FailureCategory.PERMANENT, afterClose.category(),
                    "a closed adapter must classify sends PERMANENT even while a"
                            + " reconnect left the reconnecting flag set");
        } finally {
            adapter.close();
            fake.close();
        }
    }

    @Test
    void sendSucceedsAfterSupervisedRestart() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            SimpleXSubprocess sub = newOneShotSubprocess();
            sub.start();
            Thread responder = startSendResponder(fake);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                int generationBeforeKill = fake.clientGeneration();
                // Production death shape: the process dies and the WS
                // connection it served dies with it.
                fake.killClientConnection();
                touchDieFlag();
                // Restart listener → reconnect: flag, close old client,
                // ready probe, fresh client handshake (generation +1).
                fake.awaitClientGeneration(generationBeforeKill + 1, Duration.ofSeconds(10));
                MessageHandle handle = sendUntilSuccess(adapter, 10_000);
                assertNotNull(handle,
                        "send must succeed once the WebSocket client was rebuilt"
                                + " after the supervised restart");
            } finally {
                responder.interrupt();
                adapter.close();
            }
        }
    }

    @Test
    void inboundDeliveredExactlyOnceAfterReconnect() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            adapter.setInboundHandler(delivered::add);
            SimpleXSubprocess sub = newOneShotSubprocess();
            sub.start();
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                int generationBeforeKill = fake.clientGeneration();
                fake.killClientConnection();
                touchDieFlag();
                fake.awaitClientGeneration(generationBeforeKill + 1, Duration.ofSeconds(10));
                fake.sendFrame(inboundFrame("after-reconnect", "inbound-r1"));
                InboundMessage first = delivered.poll(5_000, TimeUnit.MILLISECONDS);
                assertNotNull(first,
                        "inbound pushed after the rebuild must reach the handler");
                assertEquals("after-reconnect", first.text());
                // Exactly once: a half-dead prior listener/dispatcher would
                // surface a duplicate within this settle window.
                assertNull(delivered.poll(400, TimeUnit.MILLISECONDS),
                        "inbound frame must be delivered exactly once across the reconnect");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void peerCloseRecoversWithoutProcessExit() throws Exception {
        // M1-674 acceptance item 2: simplex-chat stays alive but its bot
        // WebSocket dies (the audit's MSG-1 gap) — the adapter must rebuild
        // the WS against the still-alive subprocess, and a subsequent send
        // must run against a live transport with NO process exit involved.
        // Route recorded per the ticket: adapter-side rebuild, not the
        // supervisor restart path (which would exit the process).
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake, MS_SCALE_BACKOFF);
            SimpleXSubprocess sub = newStayAliveSubprocess();
            sub.start();
            Thread responder = startSendResponder(fake);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                int generationBeforeKill = fake.clientGeneration();
                // Peer-initiated death: the connection is severed while the
                // process (and its server socket) stay up.
                fake.killClientConnection();
                fake.awaitClientGeneration(generationBeforeKill + 1, Duration.ofSeconds(10));
                MessageHandle handle = sendUntilSuccess(adapter, 10_000);
                assertNotNull(handle,
                        "send must succeed once the WebSocket was rebuilt against"
                                + " the still-alive subprocess");
                assertEquals(0, sub.restartCount(),
                        "peer-close recovery must not involve a process restart");
                assertEquals(SimpleXSubprocess.State.RUNNING, sub.state(),
                        "the subprocess must have stayed alive across the recovery");
            } finally {
                responder.interrupt();
                adapter.close();
            }
        }
    }

    @Test
    void peerCloseRebuildWaitsTheFirstBackoffRungBeforeReconnecting() throws Exception {
        // M1-674 pacing acceptance item: the FIRST rebuild attempt already
        // waits the ladder's first rung — never an immediate hot retry — so
        // a daemon that re-closes every fresh WebSocket is paced by at least
        // one rung per rebuild. Lower-bound-only assertion: a timing upper
        // bound flakes, and the pacing property IS the lower bound.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            Duration firstRung = Duration.ofMillis(400);
            SimpleXAdapter adapter = newAdapter(fake, List.of(firstRung));
            SimpleXSubprocess sub = newStayAliveSubprocess();
            sub.start();
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                int generationBeforeKill = fake.clientGeneration();
                long killedAtNanos = System.nanoTime();
                fake.killClientConnection();
                fake.awaitClientGeneration(generationBeforeKill + 1, Duration.ofSeconds(10));
                long elapsedMs = (System.nanoTime() - killedAtNanos) / 1_000_000;
                assertTrue(elapsedMs >= firstRung.toMillis(),
                        "rebuild handshake landed " + elapsedMs + " ms after the peer"
                                + " close; the campaign must wait the first backoff rung"
                                + " (" + firstRung.toMillis() + " ms) before its first"
                                + " attempt");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void peerCloseWindowIsVisibleOnConnectionStatusGauge() throws Exception {
        // M1-674 acceptance item 3: between the peer close and the completed
        // recovery the adapter.connection.status gauge reads 0 — the outage
        // is operator-visible, no false-green interval. The ladder's only
        // rung is far longer than the test, so recovery cannot complete and
        // the dead window is held open deterministically.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake, List.of(Duration.ofSeconds(30)));
            SimpleXSubprocess sub = newStayAliveSubprocess();
            sub.start();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new AdapterMetrics(registry).bindAdapter(adapter);
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                assertEquals(1.0, connectionStatus(registry),
                        "precondition: a live transport reads 1 on the gauge");
                fake.killClientConnection();
                awaitDisconnectedGauge(registry, WAIT);
                assertFalse(adapter.connected(),
                        "connected() must report the dead transport");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void emptyReconnectBackoffLadderIsRejectedAtConstruction() {
        // M1-674: the recovery campaign indexes the ladder on every attempt,
        // so an empty one throws IndexOutOfBounds on the recovery thread —
        // and a campaign thread that dies is unrecoverable: the death
        // notifier is one-shot per client, the current client is already
        // latched dead, and no further terminal event can fire while the
        // simplex-chat process stays alive. Rejecting the ladder at
        // construction keeps a mis-wired seam a loud wiring failure instead
        // of a silent permanent outage (redteam-multi 2026-07-22).
        SimpleXConfig cfg = new SimpleXConfig(
                "/usr/bin/simplex-chat", tempDir.toString(), 5225);
        HttpClient http = HttpClient.newHttpClient();
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new SimpleXAdapter(cfg, http, msg -> { /* unused */ }, List.of()));
        assertTrue(failure.getMessage().contains("non-empty"),
                "the rejection must name the empty ladder; got: " + failure.getMessage());
    }

    @Test
    void terminalSupervisorFailureOutranksTheParkedReconnectingFlag() throws Exception {
        // M1-674 acceptance item 9: `reconnecting` is parked true by a failed
        // rebuild and no path clears it on the supervisor's FAILED transition,
        // so a terminal-failure arm classified BEHIND that flag would answer
        // every send TRANSIENT forever — the Provider retrying against a
        // transport that can never come back. The terminal arm must therefore
        // be classified ahead of it (redteam-multi 2026-07-22).
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            // A single 30 s rung: the campaign raises `reconnecting` and then
            // sleeps past the end of the test, so the flag is deterministically
            // still set when the supervisor reaches FAILED.
            SimpleXAdapter adapter = newAdapter(fake, List.of(Duration.ofSeconds(30)));
            SimpleXSubprocess sub = newCrashLoopingSubprocess();
            sub.start();
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                fake.killClientConnection();
                awaitReconnectingClassification(adapter, Duration.ofSeconds(5));
                // Every incarnation now exits immediately, so the supervisor
                // burns its crash cap and gives up terminally.
                touchDieFlag();
                awaitSubprocessState(sub, SimpleXSubprocess.State.FAILED, Duration.ofSeconds(10));
                MessagingException afterFailure = assertThrows(MessagingException.class,
                        () -> adapter.send(outbound("post-failure")));
                assertEquals(FailureCategory.PERMANENT, afterFailure.category(),
                        "a terminally-failed supervisor must classify sends PERMANENT even"
                                + " while the parked `reconnecting` flag is still set");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void campaignAloneCarriesRecoveryAcrossASubprocessRestartWindow() throws Exception {
        // M1-674 acceptance item 10: the exit arm drops its restart
        // notification whenever it loses the single-flight CAS to a running
        // campaign, so a campaign that abandoned the moment it observed a
        // not-RUNNING subprocess would leave the respawned child with a
        // latched-dead transport and nothing rebuilding it. Unhooking the
        // restart listener models that dropped notification exactly: here the
        // campaign is the only arm that can recover, and it must carry the
        // transport across the whole restart window (redteam-multi
        // 2026-07-22).
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake, List.of(Duration.ofMillis(100)));
            // Restart backoff far wider than the ladder rung, so the campaign
            // is guaranteed to observe the not-RUNNING window rather than
            // sleeping through it.
            SimpleXSubprocess sub = newOneShotSubprocess(
                    Duration.ofMillis(1_500), Duration.ofMillis(1_500));
            sub.start();
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                // Last registration wins: the adapter's exit arm is unhooked,
                // standing in for the notification it would have dropped.
                sub.onRestart(() -> { /* the dropped restart notification */ });
                int generationBeforeKill = fake.clientGeneration();
                // Process first, transport second: the campaign then starts
                // inside the restart window and its first attempt necessarily
                // observes a not-RUNNING subprocess.
                touchDieFlag();
                awaitSubprocessState(sub, SimpleXSubprocess.State.RESTARTING,
                        Duration.ofSeconds(5));
                fake.killClientConnection();
                fake.awaitClientGeneration(generationBeforeKill + 1, Duration.ofSeconds(20));
                assertEquals(1, sub.restartCount(),
                        "the recovery must have spanned a real supervised restart");
            } finally {
                adapter.close();
            }
        }
    }

    // -- choreography helpers ------------------------------------------------

    /** Ladder for the recovery tests: a single fast rung (steady 50 ms). */
    private static final List<Duration> MS_SCALE_BACKOFF = List.of(Duration.ofMillis(50));

    private SimpleXAdapter newAdapter(FakeSimpleXProcess fake, List<Duration> wsReconnectBackoff) {
        SimpleXConfig cfg = new SimpleXConfig(
                "/usr/bin/simplex-chat", tempDir.toString(), fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused here */ },
                wsReconnectBackoff);
    }

    /**
     * Supervised child that stays alive for the whole test ({@code exec
     * sleep 30}): the peer-close tests need the process healthy while its
     * WebSocket dies — the exact MSG-1 shape — and a dying stand-in would
     * make the supervisor churn mid-test (the M1-655 lesson).
     */
    private SimpleXSubprocess newStayAliveSubprocess() {
        return new SimpleXSubprocess(
                List.of(SH, "-c", "exec sleep 30"),
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                /* crashCap */ 5,
                msg -> { /* unused */ },
                new Random(0L));
    }

    private static double connectionStatus(SimpleMeterRegistry registry) {
        return registry.get("adapter.connection.status")
                .tags("adapter", "simplex").gauge().value();
    }

    private static void awaitDisconnectedGauge(SimpleMeterRegistry registry, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (connectionStatus(registry) == 0.0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError("adapter.connection.status stayed "
                + connectionStatus(registry) + " after the peer close; expected 0");
    }

    private SimpleXAdapter newAdapter(FakeSimpleXProcess fake) {
        // The binary/dataDir are never exercised: start() is not called
        // (cfg.validate() lives there) and the subprocess command is the
        // flag-file script, not commandFor(cfg).
        SimpleXConfig cfg = new SimpleXConfig(
                "/usr/bin/simplex-chat", tempDir.toString(), fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused here */ });
    }

    /**
     * Supervised child that dies exactly once, on the test's signal: the
     * first launch waits for the DIE flag file and exits; the respawned
     * child sees the RESTARTED flag and {@code exec sleep 30}s (stable).
     */
    private SimpleXSubprocess newOneShotSubprocess() {
        return newOneShotSubprocess(Duration.ofMillis(10), Duration.ofMillis(50));
    }

    /**
     * Backoff-parameterised variant: the campaign-carries-the-restart test
     * needs the RESTARTING window held open across several ladder rungs, so
     * the campaign observes it rather than sleeping through it.
     */
    private SimpleXSubprocess newOneShotSubprocess(Duration backoffBase, Duration backoffMax) {
        Path die = tempDir.resolve("die-flag");
        Path restarted = tempDir.resolve("restarted-flag");
        String script = "if [ -f " + restarted + " ]; then exec sleep 30; fi; "
                + "while [ ! -f " + die + " ]; do sleep 0.05; done; "
                + "touch " + restarted + "; exit 0";
        return new SimpleXSubprocess(
                List.of(SH, "-c", script),
                backoffBase,
                backoffMax,
                /* crashCap */ 5,
                msg -> { /* unused */ },
                new Random(0L));
    }

    /**
     * Supervised child that crashes on the test's signal and keeps crashing:
     * once the DIE flag exists every incarnation exits immediately, so the
     * supervisor burns its (deliberately small) crash cap and latches FAILED.
     */
    private SimpleXSubprocess newCrashLoopingSubprocess() {
        Path die = tempDir.resolve("die-flag");
        String script = "while [ ! -f " + die + " ]; do sleep 0.05; done; exit 1";
        return new SimpleXSubprocess(
                List.of(SH, "-c", script),
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                /* crashCap */ 2,
                msg -> { /* unused */ },
                new Random(0L));
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

    /**
     * Barrier that waits for the recovery campaign to raise {@code
     * reconnecting}, recognised through the classification message because the
     * flag itself is private. Until it is raised the latched-dead-client arm
     * answers with its own TRANSIENT, so both readings are asserted transient
     * and only the message distinguishes them.
     */
    private static void awaitReconnectingClassification(SimpleXAdapter adapter, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        // Latch barrier first. A send issued before the transport-death latch
        // is visible reaches the dying socket and is categorised by the client
        // rather than by requireConnected, so no classification assertion below
        // is meaningful until connected() has flipped.
        while (adapter.connected()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("the peer close never latched the transport dead;"
                        + " connected() still reports the adapter up");
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        String last = "<no send attempted>";
        while (System.nanoTime() < deadline) {
            MessagingException failure = assertThrows(MessagingException.class,
                    () -> adapter.send(outbound("await-reconnecting")));
            assertEquals(FailureCategory.TRANSIENT, failure.category(),
                    "with the transport latched dead and the supervisor still alive,"
                            + " a gap send is TRANSIENT");
            last = failure.getMessage();
            if (last.contains("reconnecting")) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("the recovery campaign never raised `reconnecting`;"
                + " last send failure was: " + last);
    }

    private void touchDieFlag() throws Exception {
        java.nio.file.Files.createFile(tempDir.resolve("die-flag"));
    }

    /**
     * Retry-send until success or deadline, asserting every interim
     * failure is TRANSIENT — the Provider-retry view of the outage. The
     * gap between the fake completing the rebuilt client's handshake and
     * the reconnect thread clearing `reconnecting` is microseconds wide
     * but real; polling absorbs it without weakening the category
     * assertion.
     */
    private static MessageHandle sendUntilSuccess(SimpleXAdapter adapter, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        MessagingException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return adapter.send(outbound("post-restart"));
            } catch (MessagingException e) {
                assertEquals(FailureCategory.TRANSIENT, e.category(),
                        "failures while the rebuild is settling must stay TRANSIENT");
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("send did not succeed within " + timeoutMs
                + " ms of the supervised restart; last failure: " + last);
    }

    /**
     * Background acker: answer every client command frame with a
     * {@code newChatItems} success so {@code adapter.send} calls can complete
     * after the supervised restart. Exits on interrupt or when no frame
     * arrives for 15 s.
     */
    private static Thread startSendResponder(FakeSimpleXProcess fake) {
        return Thread.ofVirtual().name("fake-simplex-send-responder").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String envelope = fake.awaitFrame(Duration.ofSeconds(15));
                    JsonNode root = MAPPER.readTree(envelope);
                    JsonNode corrId = root.get("corrId");
                    if (corrId == null) {
                        continue;
                    }
                    fake.sendFrame("""
                            {
                              "corrId": "%s",
                              "resp": {
                                "type": "newChatItems",
                                "chatItems": {"itemId": "acked-item-1"}
                              }
                            }
                            """.formatted(corrId.asText()));
                }
            } catch (Exception e) {
                // awaitFrame timeout / interrupt / teardown IO — done.
            }
        });
    }

    private static OutboundMessage outbound(String text) {
        return new OutboundMessage(
                new ScopeRef.Dm("alice-queue-addr"), text, Instant.now(), "corr-" + text);
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

    private static String pickBinary(String... candidates) {
        for (String path : candidates) {
            if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(path))) {
                return path;
            }
        }
        throw new IllegalStateException(
                "no usable test binary among " + List.of(candidates));
    }

    private static String inboundFrame(String text, String itemId) {
        return """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "direct",
                        "contact": {
                          "contactId": "alice-queue-addr",
                          "localDisplayName": "Alice"
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "%s"},
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "%s"
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(itemId, text);
    }
}
