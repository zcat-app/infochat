package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.ScopeRef;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;

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
                    delivered::add);
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
                    msg -> { /* unused — no inbound in this test */ });
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
    void sendAfterCloseRaisesPermanent() throws Exception {
        // close() must release callers waiting on pending acks. A subsequent
        // sendCommand on a closed client raises PERMANENT (matches the SPI
        // contract for terminal failure).
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> { /* unused */ });
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
}
