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

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
                                "chatType": "direct",
                                "contact": {
                                  "contactId": "alice-queue-addr",
                                  "displayName": "Alice"
                                }
                              },
                              "chatItem": {
                                "itemId": "inbound-1",
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
     * jboss-logmanager is the active LogManager under surefire — same
     * pattern as the InboundRouter redaction tests in infochat-provider.
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
