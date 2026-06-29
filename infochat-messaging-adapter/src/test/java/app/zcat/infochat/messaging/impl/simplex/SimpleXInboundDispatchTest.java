package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Pins the M1-177 inbound-dispatch threading contract for the SimpleX
 * transport: the inbound consumer leaves the JDK WebSocket listener
 * thread (so a handler that replies synchronously from inside
 * {@code onMessage} cannot deadlock against its own ack delivery), and
 * the single-dispatch-thread handoff preserves per-connection FIFO
 * delivery order.
 *
 * <p>SimpleX has no {@code MembershipHandler.onEvent} surface to
 * assert: {@code SimpleXAdapter} declares
 * {@code supportsMembershipEvents=false} and does not override
 * {@code setMembershipEventHandler}, so {@code onMessage} is the only
 * SPI callback this transport dispatches (the Signal half of the
 * acceptance covers {@code onEvent}).</p>
 */
class SimpleXInboundDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void synchronousReplyFromOnMessageCompletesWithoutDeadlock() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            AtomicReference<String> replyChatItemId = new AtomicReference<>();
            AtomicReference<MessagingException> replyFailure = new AtomicReference<>();
            AtomicBoolean replied = new AtomicBoolean(false);
            // The consumer lambda needs the client reference before the
            // client local is assigned; route it through an AtomicReference.
            AtomicReference<SimpleXWebSocketClient> clientRef = new AtomicReference<>();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> {
                        // Synchronous reply from inside the inbound
                        // callback: sendCommand blocks awaiting the ack
                        // that only the WS listener thread can deliver.
                        // Pre-M1-177 the consumer ran ON the listener
                        // thread, so this send deadlocked until timeout.
                        if (replied.compareAndSet(false, true)) {
                            try {
                                String envelope = SimpleXMessageCodec.encodeSendCommand(
                                        "corr-reply-1", msg.scope(), "reply");
                                replyChatItemId.set(clientRef.get()
                                        .sendCommand("corr-reply-1", envelope, WAIT));
                            } catch (MessagingException e) {
                                replyFailure.set(e);
                            }
                        }
                        delivered.add(msg);
                    },
                    gc -> { /* no group candidate in this test */ });
            clientRef.set(client);
            client.start();
            try {
                fake.awaitClient(WAIT);
                // Server side: ack the reply command as soon as it arrives.
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
                                    "chatItems": {"itemId": "reply-item-1"}
                                  }
                                }
                                """.formatted(corrId));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                fake.sendFrame(inboundFrame("hi 1", "inbound-1"));
                InboundMessage first = delivered.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                assertNotNull(first,
                        "onMessage must complete without deadlocking against its own ack delivery");
                assertNull(replyFailure.get(),
                        "synchronous reply from inside onMessage must not fail: " + replyFailure.get());
                assertEquals("reply-item-1", replyChatItemId.get(),
                        "synchronous reply send must complete with the acked chat-item id");

                // Inbound delivery continues after the synchronous reply.
                fake.sendFrame(inboundFrame("hi 2", "inbound-2"));
                InboundMessage second = delivered.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                assertNotNull(second, "inbound delivery must continue after a synchronous reply");
                assertEquals("hi 2", second.text());
            } finally {
                client.close();
            }
        }
    }

    @Test
    void onMessageRunsOffWebSocketListenerThread() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            LinkedBlockingQueue<String> onMessageThreads = new LinkedBlockingQueue<>();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> onMessageThreads.add(Thread.currentThread().getName()),
                    gc -> { /* no group candidate in this test */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                fake.sendFrame(inboundFrame("thread probe", "inbound-t1"));
                String onMessageThread = onMessageThreads.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                assertNotNull(onMessageThread, "InboundHandler.onMessage must be invoked");
                // Equality with the dedicated dispatch-thread name proves
                // the callback left the JDK WebSocket listener thread (an
                // HttpClient worker) — there is no stable listener-thread
                // name to assert inequality against.
                assertEquals("simplex-inbound-dispatch", onMessageThread,
                        "onMessage must run on the dedicated inbound-dispatch thread,"
                                + " not the WebSocket listener thread");
            } finally {
                client.close();
            }
        }
    }

    @Test
    void inboundDeliveryPreservesFifoOrder() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            LinkedBlockingQueue<String> deliveredTexts = new LinkedBlockingQueue<>();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> deliveredTexts.add(msg.text()),
                    gc -> { /* no group candidate in this test */ });
            client.start();
            try {
                fake.awaitClient(WAIT);
                int count = 25;
                for (int i = 0; i < count; i++) {
                    fake.sendFrame(inboundFrame("msg-" + i, "inbound-" + i));
                }
                for (int i = 0; i < count; i++) {
                    String text = deliveredTexts.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                    assertNotNull(text, "message " + i + " must be delivered");
                    assertEquals("msg-" + i, text,
                            "inbound messages pushed in order on one connection must reach"
                                    + " the InboundHandler in the same order");
                }
            } finally {
                client.close();
            }
        }
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
