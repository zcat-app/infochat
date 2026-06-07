package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.ScopeRef;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Pins the per-connection send serialization of
 * {@link SimpleXWebSocketClient}: the JDK WebSocket permits one
 * outstanding text send, and before serialization a colliding send's
 * frame was silently lost (the rejected future was discarded) or — on
 * the synchronous-throw path — misclassified PERMANENT. The harness is
 * hybrid: the client starts against a real {@link FakeSimpleXProcess}
 * so acks flow through the production listener, then the WebSocket
 * field is swapped (same reflection pattern as the
 * {@link ThrowingWebSocket} race test) for a
 * {@link OneOutstandingWebSocket} that enforces the one-outstanding
 * rule deterministically; the test acks each frame the fake accepts by
 * pushing the SendAck through the real server socket.
 */
class SimpleXSendSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void concurrentSendsAllTransmitAndAck() throws Exception {
        // Acceptance item 1 (M7): N concurrent sends on one connection must
        // ALL transmit and ack. Before serialization, only the send that won
        // the race was transmitted; the rest got an exceptionally-completed
        // future nobody read and stalled into the ack timeout.
        int n = 6;
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> { /* unused */ },
                    gc -> { /* unused */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                OneOutstandingWebSocket strictWs = new OneOutstandingWebSocket(
                        5, OneOutstandingWebSocket.CollisionMode.ASYNC_FAILED_FUTURE);
                injectWebSocket(client, strictWs);

                // Ack pump: for every frame the strict socket accepts, push
                // the matching SendAck through the real server connection so
                // the production listener completes the pending future.
                Thread ackPump = Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 0; i < n; i++) {
                            String envelope = strictWs.awaitTransmitted(WAIT);
                            String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                            fake.sendFrame(ackFrame(corrId));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                Map<String, String> ackedItemIds = new ConcurrentHashMap<>();
                Map<String, Exception> failures = new ConcurrentHashMap<>();
                CountDownLatch go = new CountDownLatch(1);
                List<Thread> senders = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String corrId = "corr-" + i;
                    senders.add(Thread.ofVirtual().start(() -> {
                        try {
                            go.await();
                            String envelope = SimpleXMessageCodec.encodeSendCommand(
                                    corrId, new ScopeRef.Dm("bob-queue-addr"), "msg " + corrId);
                            ackedItemIds.put(corrId,
                                    client.sendCommand(corrId, envelope, WAIT));
                        } catch (Exception e) {
                            failures.put(corrId, e);
                        }
                    }));
                }
                go.countDown();
                for (Thread sender : senders) {
                    assertTrue(sender.join(WAIT), "sender did not finish within " + WAIT);
                }
                assertTrue(ackPump.join(WAIT), "ack pump did not finish within " + WAIT);

                assertTrue(failures.isEmpty(),
                        "every concurrent send must transmit and ack; failures: " + failures);
                assertEquals(n, strictWs.acceptedCount(),
                        "all " + n + " frames must be transmitted (none dropped by collision)");
                for (int i = 0; i < n; i++) {
                    assertEquals("item-corr-" + i, ackedItemIds.get("corr-" + i),
                            "send corr-" + i + " must resolve with its own ack's chat-item id");
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    void collidingSendIsNeverFailedPermanent() throws Exception {
        // Acceptance item 2 (M7): a send colliding with an in-progress send
        // is queued (at the transmit lock), never failed PERMANENT. The
        // strict socket throws the synchronous IllegalStateException on
        // overlap — the exact shape sendCommand previously misclassified as
        // PERMANENT "closed concurrently".
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> { /* unused */ },
                    gc -> { /* unused */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                // 100ms transmit completion: send A holds the in-progress
                // window long enough that B's overlap is deterministic.
                OneOutstandingWebSocket strictWs = new OneOutstandingWebSocket(
                        100, OneOutstandingWebSocket.CollisionMode.SYNC_THROW);
                injectWebSocket(client, strictWs);

                Thread ackPump = Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 0; i < 2; i++) {
                            String envelope = strictWs.awaitTransmitted(WAIT);
                            String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                            fake.sendFrame(ackFrame(corrId));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                Map<String, Exception> failures = new ConcurrentHashMap<>();
                Thread senderA = Thread.ofVirtual().start(() -> {
                    try {
                        client.sendCommand("corr-a",
                                SimpleXMessageCodec.encodeSendCommand(
                                        "corr-a", new ScopeRef.Dm("x"), "first"),
                                WAIT);
                    } catch (Exception e) {
                        failures.put("corr-a", e);
                    }
                });
                // Fire B only once A's frame is accepted and in progress, so
                // B demonstrably collides with an incomplete prior send.
                long deadline = System.nanoTime() + WAIT.toNanos();
                while (strictWs.acceptedCount() == 0 && System.nanoTime() < deadline) {
                    TimeUnit.MILLISECONDS.sleep(2);
                }
                assertEquals(1, strictWs.acceptedCount(), "send A must be in progress");
                Thread senderB = Thread.ofVirtual().start(() -> {
                    try {
                        client.sendCommand("corr-b",
                                SimpleXMessageCodec.encodeSendCommand(
                                        "corr-b", new ScopeRef.Dm("x"), "colliding"),
                                WAIT);
                    } catch (Exception e) {
                        failures.put("corr-b", e);
                    }
                });
                assertTrue(senderA.join(WAIT), "sender A did not finish within " + WAIT);
                assertTrue(senderB.join(WAIT), "sender B did not finish within " + WAIT);
                assertTrue(ackPump.join(WAIT), "ack pump did not finish within " + WAIT);

                assertTrue(failures.isEmpty(),
                        "a send colliding with an in-progress send must be queued or "
                                + "retried, never failed (and never PERMANENT); failures: "
                                + failures);
            } finally {
                client.close();
            }
        }
    }

    private static void injectWebSocket(SimpleXWebSocketClient client,
                                        OneOutstandingWebSocket ws) throws Exception {
        Field wsField = SimpleXWebSocketClient.class.getDeclaredField("webSocket");
        wsField.setAccessible(true);
        wsField.set(client, ws);
    }

    private static String ackFrame(String corrId) {
        return """
                {
                  "corrId": "%s",
                  "resp": {
                    "type": "newChatItems",
                    "chatItems": {"itemId": "item-%s"}
                  }
                }
                """.formatted(corrId, corrId);
    }
}
