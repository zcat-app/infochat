package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

/**
 * Pins the M1-177 inbound-dispatch threading contract for the Signal
 * transport: handler callbacks leave the {@code signal-jsonrpc-reader}
 * thread (so a handler that replies synchronously from inside
 * {@code onMessage} cannot deadlock against its own ack delivery), and
 * the single-dispatch-thread handoff preserves per-connection FIFO
 * delivery order.
 */
class SignalInboundDispatchTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(2);
    private static final long QUEUE_WAIT_MS = 2_000;
    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    @Test
    void synchronousReplyFromOnMessageCompletesWithoutDeadlock() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            AtomicReference<MessageHandle> replyHandle = new AtomicReference<>();
            AtomicReference<MessagingException> replyFailure = new AtomicReference<>();
            AtomicBoolean replied = new AtomicBoolean(false);
            client.setInboundHandler(msg -> {
                // Synchronous reply from inside onMessage: send() blocks
                // awaiting the JSON-RPC response that only the reader
                // thread can deliver. Pre-M1-177 the handler ran ON the
                // reader thread, so this send deadlocked until timeout.
                if (replied.compareAndSet(false, true)) {
                    try {
                        replyHandle.set(client.send(new OutboundMessage(
                                msg.scope(), "reply", Instant.now(), "corr-reply")));
                    } catch (MessagingException e) {
                        replyFailure.set(e);
                    }
                }
                delivered.add(msg);
            });
            client.connect();
            try {
                fake.pushNotification("receive", receiveParams("first", 1700000001000L));

                // Serve the reply's ack from the test thread while the
                // handler is blocked inside onMessage.
                JsonObject sendRequest = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", sendRequest.getString("method"));
                fake.respondSuccess(sendRequest.getString("id"), Json.createObjectBuilder()
                        .add("timestamp", 1700000001500L)
                        .add("results", Json.createArrayBuilder())
                        .build());

                InboundMessage first = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(first,
                        "onMessage must complete without deadlocking against its own ack delivery");
                assertNull(replyFailure.get(),
                        "synchronous reply from inside onMessage must not fail: " + replyFailure.get());
                assertNotNull(replyHandle.get(),
                        "synchronous reply send must complete and return a handle");

                // Inbound delivery continues after the synchronous reply.
                fake.pushNotification("receive", receiveParams("second", 1700000002000L));
                InboundMessage second = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(second, "inbound delivery must continue after a synchronous reply");
                assertEquals("second", second.text());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void onMessageRunsOffReaderThreadOnDmAndGroupPaths() throws Exception {
        // Drives the production receive wiring through the package-private
        // attachClient seam (same entry as SignalGroupEndToEndTest) so the
        // thread assertion binds InboundHandler.onMessage on BOTH routes:
        // DM inbound via the codec, group bot-mention via the
        // group-notification route (SignalGroupHandler).
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalAdapter adapter = new SignalAdapter(
                    "/usr/bin/signal-cli",
                    "/tmp/signal-data",
                    "+15551111111",
                    fake.endpoint());
            adapter.adoptBotAci(BOT_ACI);
            LinkedBlockingQueue<String> onMessageThreads = new LinkedBlockingQueue<>();
            adapter.setInboundHandler(
                    msg -> onMessageThreads.add(Thread.currentThread().getName()));
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                adapter.attachClient(client);

                fake.pushNotification("receive", receiveParams("dm body", 1700000003000L));
                fake.pushNotification("receive", groupMention(1700000004000L));

                String dmThread = onMessageThreads.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(dmThread, "InboundHandler.onMessage must be invoked for the DM");
                assertNotEquals("signal-jsonrpc-reader", dmThread,
                        "onMessage must not run on the transport reader thread");
                assertEquals("signal-inbound-dispatch", dmThread,
                        "onMessage must run on the dedicated inbound-dispatch thread");

                String groupThread = onMessageThreads.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(groupThread,
                        "InboundHandler.onMessage must be invoked for the group mention");
                assertNotEquals("signal-jsonrpc-reader", groupThread,
                        "group-route onMessage must not run on the transport reader thread");
                assertEquals("signal-inbound-dispatch", groupThread,
                        "group-route onMessage must run on the dedicated inbound-dispatch thread");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void inboundDeliveryPreservesFifoOrder() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            LinkedBlockingQueue<String> deliveredTexts = new LinkedBlockingQueue<>();
            client.setInboundHandler(msg -> deliveredTexts.add(msg.text()));
            client.connect();
            try {
                int count = 25;
                for (int i = 0; i < count; i++) {
                    fake.pushNotification("receive",
                            receiveParams("msg-" + i, 1700000010000L + i));
                }
                for (int i = 0; i < count; i++) {
                    String text = deliveredTexts.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                    assertNotNull(text, "message " + i + " must be delivered");
                    assertEquals("msg-" + i, text,
                            "inbound messages pushed in order on one connection must reach"
                                    + " the InboundHandler in the same order");
                }
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void dmSenderDisplayNamePopulatedFromSourceName() throws Exception {
        // U-21: the DM-path Identity's displayName (informational only,
        // D10) is taken from the envelope's sourceName, threaded through
        // ReceivedDm into the dispatched InboundMessage.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            client.setInboundHandler(delivered::add);
            client.connect();
            try {
                fake.pushNotification("receive", receiveParams("hello", 1700000020000L));
                InboundMessage msg = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg, "DM must be delivered");
                assertEquals("Alice", msg.sender().displayName(),
                        "DM sender displayName must come from the envelope sourceName");
            } finally {
                client.disconnect();
            }
        }
    }

    private static JsonObject receiveParams(String body, long timestamp) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("source", "+15557654321")
                        .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                        .add("sourceName", "Alice")
                        .add("sourceDevice", 1)
                        .add("timestamp", timestamp)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", timestamp)
                                .add("message", body)))
                .build();
    }

    /**
     * A group bot-mention in the real signal-cli 0.14.5 wire shape
     * ({@code groupInfo{groupId}}, F-live-10) — exercises the non-DM
     * routing into the group handler with a message-bearing envelope.
     */
    private static JsonObject groupMention(long timestamp) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                        .add("timestamp", timestamp)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", timestamp)
                                .add("message", "@bot ping")
                                .add("mentions", Json.createArrayBuilder()
                                        .add(Json.createObjectBuilder()
                                                .add("uuid", BOT_ACI)
                                                .add("start", 0)
                                                .add("length", 4)))
                                .add("groupInfo", Json.createObjectBuilder()
                                        .add("groupId", GROUP_V2_ID)
                                        .add("groupName", "test-group")
                                        .add("revision", 1)
                                        .add("type", "DELIVER"))))
                .build();
    }
}
