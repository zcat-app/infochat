package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.ScopeRef;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.Test;

class SimpleXWebSocketClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void inboundMessageDelivered() throws Exception {
        // Acceptance item 15: a server-side newChatItem JSON frame arrives
        // and the client decodes + delivers it to the registered consumer
        // as a populated InboundMessage tuple.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    delivered::add,
                    gc -> { /* no group candidate in this test */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                fake.sendFrame("""
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
                                "meta": {"itemId": "inbound-1"},
                                "content": {
                                  "msgContent": {
                                    "type": "text",
                                    "text": "hi from alice"
                                  }
                                }
                              }
                            }
                          }
                        }
                        """);
                InboundMessage msg = delivered.poll(WAIT.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                assertNotNull(msg, "client must deliver the decoded inbound message");
                assertEquals("alice-queue-addr", msg.sender().contactId());
                assertEquals("hi from alice", msg.text());
                assertEquals(new ScopeRef.Dm("alice-queue-addr"), msg.scope());
            } finally {
                client.close();
            }
        }
    }

    @Test
    void outboundSendReturnsHandle() throws Exception {
        // Acceptance item 16: sendCommand dispatches via the WebSocket and
        // resolves with the chat-item id from the server's SendAck.
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> { /* unused — no inbound in this test */ },
                    gc -> { /* unused — no group candidate in this test */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                // Server thread: as soon as we see the command, reply with the ack.
                Thread.ofVirtual().start(() -> {
                    try {
                        String envelope = fake.awaitFrame(WAIT);
                        JsonNode root = MAPPER.readTree(envelope);
                        String corrId = root.get("corrId").asText();
                        fake.sendFrame("""
                                {
                                  "corrId": "%s",
                                  "resp": {
                                    "type": "newChatItems",
                                    "chatItems": {"itemId": "msg-42"}
                                  }
                                }
                                """.formatted(corrId));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                String envelope = SimpleXMessageCodec.encodeSendCommand(
                        "corr-out-1",
                        new ScopeRef.Dm("bob-queue-addr"),
                        "hello bob");
                String chatItemId = client.sendCommand(
                        "corr-out-1",
                        envelope,
                        WAIT);
                assertEquals("msg-42", chatItemId,
                        "client returns the chat-item id parsed from the server ack");
            } finally {
                client.close();
            }
        }
    }

    @Test
    void malformedFrameLogIsSafe() throws Exception {
        // M1-119 acceptance item 5: when dispatch() catches
        // MalformedFrameException, the WARN log line must not contain
        // byte fragments from the offending frame (security.md §User
        // content in exceptions). Send a syntactically-valid JSON frame
        // missing the 'resp' envelope so the codec throws, with a sentinel
        // string in a sibling field; assert the captured log does not
        // carry that sentinel.
        String sentinel = "REDTEAM-SENTINEL-XXXXX";
        CapturingLogHandler logCapture =
                CapturingLogHandler.attach(SimpleXWebSocketClient.class);
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> { /* unused */ },
                    gc -> { /* no group candidate in this test */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                fake.sendFrame("{\"junk\":\"" + sentinel + "\"}");
                awaitLogContains(logCapture,
                        "simplex-chat sent a malformed frame",
                        WAIT);
            } finally {
                client.close();
            }
            String captured = logCapture.formatted();
            assertTrue(captured.contains("simplex-chat sent a malformed frame"),
                    "expected the malformed-frame WARN log; captured: " + captured);
            assertFalse(captured.contains(sentinel),
                    "log line must not carry bytes from the offending frame; captured: "
                            + captured);
        } finally {
            logCapture.detach();
        }
    }

    @Test
    void unrecognizedErrorEnvelopeDoesNotLeakBytesToLog() throws Exception {
        // End-to-end defense-in-depth for the codec's unrecognized-error
        // sentinel rule: a chatCmdError frame whose envelope echoes back
        // user bytes but carries no chatError/errorType/error tag must
        // produce a DEBUG log line at failPending() (no pending command
        // for that corrId) that does NOT include the sentinel — the
        // codec contract makes CommandError.detail() a fixed string and
        // the log site interpolates only that detail.
        String sentinel = "REDTEAM-SENTINEL-XXXXX";
        // Force the WS-client logger to publish DEBUG records — default
        // surefire level is INFO, which would suppress the no-pending-command
        // line we are asserting on. Restore the prior level after the test.
        org.jboss.logmanager.Logger jbossLogger =
                LogContext.getLogContext().getLogger(SimpleXWebSocketClient.class.getName());
        java.util.logging.Level prior = jbossLogger.getLevel();
        jbossLogger.setLevel(java.util.logging.Level.FINE);
        CapturingLogHandler logCapture =
                CapturingLogHandler.attach(SimpleXWebSocketClient.class);
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> { /* unused */ },
                    gc -> { /* no group candidate in this test */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                fake.sendFrame(
                        "{\"corrId\":\"never-issued\",\"resp\":{\"type\":\"chatCmdError\","
                                + "\"echoedBody\":\"" + sentinel + "\"}}");
                awaitLogContains(logCapture,
                        "no pending command for error",
                        WAIT);
            } finally {
                client.close();
            }
            String captured = logCapture.formatted();
            assertTrue(captured.contains("no pending command for error"),
                    "expected the unmatched-error DEBUG log; captured: " + captured);
            assertFalse(captured.contains(sentinel),
                    "log line must not carry envelope bytes; captured: " + captured);
        } finally {
            logCapture.detach();
            jbossLogger.setLevel(prior);
        }
    }

    @Test
    void sendAfterCloseRaisesPermanent() throws Exception {
        // close() must release callers waiting on pending acks. A subsequent
        // sendCommand on a closed client raises PERMANENT (matches the SPI
        // contract for terminal failure).
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> { /* unused */ },
                    gc -> { /* unused */ });
            client.start();
            fake.awaitClient(WAIT);
            client.close();
            assertTrue(client.isClosed());
            MessagingException ex = assertThrows(MessagingException.class,
                    () -> client.sendCommand(
                            "corr-x",
                            SimpleXMessageCodec.encodeSendCommand(
                                    "corr-x",
                                    new ScopeRef.Dm("x"),
                                    "should fail"),
                            WAIT));
            assertEquals(FailureCategory.PERMANENT, ex.category());
        }
    }

    @Test
    void sendRacingCloseRaisesPermanentNotRawRuntime() throws Exception {
        // B-SIMPLEX-RACE: when close() aborts the WebSocket between the
        // `closed` check and ws.sendText(), the JDK WebSocket rejects the send
        // with an IllegalStateException. sendCommand must classify it as a
        // PERMANENT MessagingException, not let the raw RuntimeException
        // escape. The real race window is too narrow to hit deterministically,
        // so inject a WebSocket whose sendText throws the documented
        // IllegalStateException (closed stays false) and drive sendCommand
        // straight through the catch clause.
        SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                URI.create("ws://127.0.0.1:1"),
                HttpClient.newHttpClient(),
                msg -> { /* unused */ },
                gc -> { /* unused */ });
        Field wsField = SimpleXWebSocketClient.class.getDeclaredField("webSocket");
        wsField.setAccessible(true);
        wsField.set(client, new ThrowingWebSocket());

        MessagingException ex = assertThrows(MessagingException.class,
                () -> client.sendCommand(
                        "corr-race",
                        SimpleXMessageCodec.encodeSendCommand(
                                "corr-race", new ScopeRef.Dm("x"), "racing send"),
                        WAIT));
        assertEquals(FailureCategory.PERMANENT, ex.category(),
                "a send that races close() must surface as PERMANENT, not a raw RuntimeException");
    }

    @Test
    void peerInitiatedCloseLatchesClientAndFiresDeathNotifier() {
        // M1-674 acceptance item 1 (onClose arm): a peer-initiated close
        // latches the client closed — isClosed() true, so
        // SimpleXAdapter.connected() stops reporting the dead transport as
        // healthy — and fires the transport-death notifier. The listener is
        // driven directly (package-private seam): the RFC-6455 test fake
        // cannot emit a server-side close frame, and the JDK plumbing from
        // wire close frame to onClose is not ours to test.
        SimpleXWebSocketClient client = newUnstartedClient();
        CountDownLatch died = new CountDownLatch(1);
        client.onTransportDeath(died::countDown);
        client.new Listener().onClose(new ThrowingWebSocket(), 1000, "server going away");
        assertTrue(client.isClosed(),
                "a peer-initiated close must latch the client closed");
        assertEquals(0, died.getCount(),
                "a peer-initiated close must fire the transport-death notifier");
    }

    @Test
    void onErrorLatchesClientAndFiresDeathNotifier() {
        // M1-674 acceptance item 1 (onError arm): a transport-error terminal
        // event latches and notifies exactly like a peer close.
        SimpleXWebSocketClient client = newUnstartedClient();
        CountDownLatch died = new CountDownLatch(1);
        client.onTransportDeath(died::countDown);
        client.new Listener().onError(new ThrowingWebSocket(),
                new IOException("connection reset"));
        assertTrue(client.isClosed(), "onError must latch the client closed");
        assertEquals(0, died.getCount(),
                "onError must fire the transport-death notifier");
    }

    @Test
    void localCloseDoesNotFireDeathNotifier() {
        // M1-674: the recovery arm must fire only for PEER-initiated death.
        // A local close() latches `closed` before aborting the socket, so a
        // terminal event the abort provokes is suppressed — otherwise every
        // deliberate teardown (adapter close, reconnect rebuild) would spawn
        // a rebuild campaign against a transport closed on purpose.
        SimpleXWebSocketClient client = newUnstartedClient();
        CountDownLatch died = new CountDownLatch(1);
        client.onTransportDeath(died::countDown);
        client.close();
        client.new Listener().onError(new ThrowingWebSocket(),
                new IOException("socket aborted by local close"));
        assertTrue(client.isClosed());
        assertEquals(1, died.getCount(),
                "a terminal event after a local close() is not a peer death;"
                        + " the notifier must not fire");
    }

    @Test
    void transportDeathCountsAndLogsDiscardedQueuedInbound() throws Exception {
        // M1-674 acceptance item 11 (round-3 redteam rework): the dispatcher
        // teardown on a peer-initiated death discards queued-but-undelivered
        // inbound, and that drop must be observable — added to the counter
        // the readiness payload exposes as dropped-inbound, plus one WARN
        // with the depth — unlike the deliberate local-close teardown, which
        // stays uncounted. Park the single dispatch thread in the consumer,
        // queue three more deliveries behind it, then drive the peer close
        // through the listener seam (the RFC-6455 test fake cannot emit a
        // server-side close frame).
        CountDownLatch consumerEntered = new CountDownLatch(1);
        CountDownLatch holdConsumer = new CountDownLatch(1);
        CapturingLogHandler logCapture =
                CapturingLogHandler.attach(SimpleXWebSocketClient.class);
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> {
                        consumerEntered.countDown();
                        try {
                            holdConsumer.await();
                        } catch (InterruptedException e) {
                            // shutdownNow() interrupts the in-flight
                            // delivery; unblocking is all this consumer does.
                            Thread.currentThread().interrupt();
                        }
                    },
                    gc -> { /* unused */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                fake.sendFrame(inboundFrame("blocker"));
                assertTrue(consumerEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                        "first delivery must reach the parked consumer");
                for (int i = 0; i < 3; i++) {
                    fake.sendFrame(inboundFrame("queued-" + i));
                }
                awaitDispatchQueueDepth(client, 3);
                client.new Listener().onClose(new ThrowingWebSocket(), 1006, "peer died");
                assertEquals(3, client.droppedInboundCount(),
                        "the teardown must count exactly the queued-but-undelivered deliveries");
                String captured = logCapture.formatted();
                assertTrue(captured.contains(
                                "transport death discarded 3 queued inbound deliveries"),
                        "the bulk drop must be WARN-logged with its depth; captured: "
                                + captured);
            } finally {
                holdConsumer.countDown();
                client.close();
            }
        } finally {
            logCapture.detach();
        }
    }

    private static String inboundFrame(String itemId) {
        return """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "direct",
                        "contact": {
                          "contactId": "flood-queue-addr",
                          "localDisplayName": "Flood"
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "%s"},
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "queued while parked"
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(itemId);
    }

    // The queue has no depth accessor (production code has no reader), so the
    // test peeks via reflection — the same seam idiom as the webSocket field
    // above. Awaiting the exact depth before firing the close is what makes
    // the counted-drop assertion deterministic: frames traverse the real
    // socket asynchronously.
    @SuppressWarnings("unchecked")
    private static void awaitDispatchQueueDepth(SimpleXWebSocketClient client,
                                                int depth) throws Exception {
        Field queueField = SimpleXWebSocketClient.class.getDeclaredField("dispatchQueue");
        queueField.setAccessible(true);
        BlockingQueue<Runnable> queue = (BlockingQueue<Runnable>) queueField.get(client);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (queue.size() == depth) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError("dispatch queue never reached depth " + depth
                + "; current depth " + queue.size());
    }

    private static SimpleXWebSocketClient newUnstartedClient() {
        // Never started: the latch tests drive the listener directly, so no
        // socket is needed (the URI is never dialed).
        return new SimpleXWebSocketClient(
                URI.create("ws://127.0.0.1:1"),
                HttpClient.newHttpClient(),
                msg -> { /* unused */ },
                gc -> { /* unused */ });
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
}
