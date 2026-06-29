package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

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
            Thread responder = startSendResponder(
                    fake, "ReconnectRederivedBotQueueAddress00000000008");
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
    void servesOnRebuiltTransportWhenRederivedAddressMalformed() throws Exception {
        // M1-402: the post-restart identity re-derivation rejects a
        // non-well-formed address (a short queue id — valid charset, under the
        // isWellFormed length floor) AFTER waitForWebSocketReady +
        // rebuildWebSocket already produced a live client. reconnect()'s
        // IllegalStateException arm must clear `reconnecting` so the healthy
        // rebuilt transport serves; leaving the flag set would wedge every send
        // TRANSIENT forever with no restart coming (a healthy subprocess never
        // fires another onRestart) to clear it.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            SimpleXSubprocess sub = newOneShotSubprocess();
            sub.start();
            Thread responder = startSendResponder(fake, "MalformedShortQueueAddr");
            try {
                adapter.attachSubprocess(sub);
                adapter.rebuildWebSocket();
                fake.awaitClient(WAIT);
                int generationBeforeKill = fake.clientGeneration();
                fake.killClientConnection();
                touchDieFlag();
                // Restart → reconnect: fresh client handshake (generation +1),
                // then deriveAndAdoptIdentity rejects the malformed address and
                // the IllegalStateException arm clears `reconnecting`.
                fake.awaitClientGeneration(generationBeforeKill + 1, Duration.ofSeconds(10));
                MessageHandle handle = sendUntilSuccess(adapter, 10_000);
                assertNotNull(handle,
                        "the rebuilt transport must serve sends after a malformed"
                                + " re-derivation — a leftover reconnecting flag would"
                                + " classify every send TRANSIENT forever");
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

    // -- choreography helpers ------------------------------------------------

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
        Path die = tempDir.resolve("die-flag");
        Path restarted = tempDir.resolve("restarted-flag");
        String script = "if [ -f " + restarted + " ]; then exec sleep 30; fi; "
                + "while [ ! -f " + die + " ]; do sleep 0.05; done; "
                + "touch " + restarted + "; exit 0";
        return new SimpleXSubprocess(
                List.of(SH, "-c", script),
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                /* crashCap */ 5,
                msg -> { /* unused */ },
                new Random(0L));
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
     * {@code newChatItems} success so {@code adapter.send} calls can
     * complete — except the {@code /show_address} identity query the
     * post-restart reconnect now issues, which gets a {@code
     * userContactLink} frame whose queue id is {@code
     * rederivedQueueAddressId} (a generic ack would feed "acked-item-1"
     * into the adoption gate and wedge the reconnect). Callers pass a
     * well-formed id for the clean-reconnect success path, or a
     * short-but-valid-charset id for the M1-402 malformed-re-derivation
     * case — the latter passes {@code extractQueueAddressId}'s charset
     * gate but fails {@code SimpleXIdentity.isWellFormed}'s length floor,
     * so {@code deriveAndAdoptIdentity} throws IllegalStateException.
     * Exits on interrupt or when no frame arrives for 15 s.
     */
    private static Thread startSendResponder(FakeSimpleXProcess fake,
                                             String rederivedQueueAddressId) {
        return Thread.ofVirtual().name("fake-simplex-send-responder").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String envelope = fake.awaitFrame(Duration.ofSeconds(15));
                    JsonNode root = MAPPER.readTree(envelope);
                    JsonNode corrId = root.get("corrId");
                    if (corrId == null) {
                        continue;
                    }
                    JsonNode cmd = root.get("cmd");
                    if (cmd != null && cmd.asText().startsWith("/show_address")) {
                        // Percent escapes collide with formatted() syntax, so
                        // the contact-link frame is built by concatenation.
                        fake.sendFrame("{\"corrId\":\"" + corrId.asText()
                                + "\",\"resp\":{\"type\":\"userContactLink\","
                                + "\"contactLink\":{\"connLinkContact\":{\"connFullLink\":"
                                + "\"simplex:/contact#/?v=2-7&smp=smp%3A%2F%2FKeyHash%3D"
                                + "%40smp.example.org%2F"
                                + rederivedQueueAddressId
                                + "%23\"}}}}");
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
