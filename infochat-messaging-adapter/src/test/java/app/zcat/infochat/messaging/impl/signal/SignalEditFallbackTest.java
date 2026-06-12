package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

/**
 * Pins the M1-285 edit-failure fallback at the Signal JSON-RPC surface: an
 * {@code updateMessage}/{@code finalize} that signal-cli rejects as
 * non-recoverable (edit window expired, message deleted — any non-{@code
 * -32603} JSON-RPC error → PERMANENT) falls back to a fresh {@code send}
 * carrying the new body and the original {@code correlationId}, re-addressed
 * to the original recipient (design 06-messaging.md §6.3.8 / §6.5.7). Same
 * producer-thread harness as {@link SignalJsonRpcClientTest}: a thread issues
 * the blocking call while the test thread runs {@link FakeSignalCli}'s
 * accept-then-respond protocol.
 */
class SignalEditFallbackTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long QUEUE_WAIT_MS = 2_000;
    private static final String RECIPIENT = "aabbccdd-1111-2222-3333-444455556666";

    @Test
    void editFailureFallsBackToFreshSend() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                MessageHandle handle = sendAndAck(client, fake,
                        new ScopeRef.Dm(RECIPIENT), "corr-fb", 1700000010000L);

                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread updater = runAsync("fallback-updater",
                        () -> client.update(handle, "edited body"), failure);

                // The edit reaches the daemon and is rejected as non-recoverable
                // (edit window expired / message deleted): a non-(-32603) code,
                // which classifies PERMANENT.
                JsonObject editReq = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("updateMessage", editReq.getString("method"),
                        "the first update must attempt an in-place edit");
                fake.respondError(editReq.getString("id"), -32602, "edit window expired");

                // The adapter must fall back to a fresh send of the new body,
                // re-addressed to the original recipient.
                JsonObject sendReq = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", sendReq.getString("method"),
                        "an unrecoverable edit must fall back to a fresh send");
                JsonObject params = sendReq.getJsonObject("params");
                assertEquals("edited body", params.getString("message"));
                assertEquals(RECIPIENT, params.getJsonArray("recipient").getString(0),
                        "the fresh send must re-address the original recipient");
                fake.respondSuccess(sendReq.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000011000L).build());

                updater.join(QUEUE_WAIT_MS);
                assertNull(failure.get(),
                        "the edit fallback must not surface an exception: " + failure.get());

                // After the fallback the handle is in fresh-send mode: a
                // subsequent update fresh-sends directly — no further
                // updateMessage may reach the transport.
                Thread updater2 = runAsync("fallback-updater-2",
                        () -> client.update(handle, "edited again"), failure);
                JsonObject sendReq2 = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", sendReq2.getString("method"),
                        "after the fallback, subsequent updates fresh-send and never edit again");
                assertEquals("edited again", sendReq2.getJsonObject("params").getString("message"));
                fake.respondSuccess(sendReq2.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000012000L).build());
                updater2.join(QUEUE_WAIT_MS);
                assertNull(failure.get(), "the second fallback send must not throw: " + failure.get());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void finalizeEditFailureFallsBackToFreshSend() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                MessageHandle handle = sendAndAck(client, fake,
                        new ScopeRef.Dm(RECIPIENT), "corr-fin", 1700000020000L);

                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread finalizer = runAsync("fallback-finalizer",
                        () -> client.finalizeHandle(handle, "final body"), failure);

                JsonObject editReq = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("updateMessage", editReq.getString("method"));
                fake.respondError(editReq.getString("id"), -32602, "message deleted");

                JsonObject sendReq = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", sendReq.getString("method"),
                        "an unrecoverable finalize edit must fall back to a fresh send");
                assertEquals("final body", sendReq.getJsonObject("params").getString("message"));
                fake.respondSuccess(sendReq.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000021000L).build());

                finalizer.join(QUEUE_WAIT_MS);
                assertNull(failure.get(),
                        "the finalize fallback must not surface an exception: " + failure.get());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void groupEditFailureFallsBackToFreshGroupSend() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                MessageHandle handle = sendAndAck(client, fake,
                        new ScopeRef.Group("group-9"), "corr-grp", 1700000030000L);

                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread updater = runAsync("group-fallback-updater",
                        () -> client.update(handle, "group edit"), failure);

                JsonObject editReq = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("updateMessage", editReq.getString("method"));
                fake.respondError(editReq.getString("id"), -32602, "edit window expired");

                // The fresh fallback send must re-address the group by groupId,
                // not as a recipient array.
                JsonObject sendReq = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", sendReq.getString("method"));
                JsonObject params = sendReq.getJsonObject("params");
                assertEquals("group-9", params.getString("groupId"),
                        "a group fallback send must re-address by groupId");
                assertEquals("group edit", params.getString("message"));
                fake.respondSuccess(sendReq.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000031000L).build());

                updater.join(QUEUE_WAIT_MS);
                assertNull(failure.get(),
                        "the group edit fallback must not surface an exception: " + failure.get());
            } finally {
                client.disconnect();
            }
        }
    }

    // -- harness -------------------------------------------------------------

    private interface ThrowingCall {
        void run() throws MessagingException;
    }

    private static Thread runAsync(String name, ThrowingCall call, AtomicReference<Exception> failure) {
        Thread t = new Thread(() -> {
            try {
                call.run();
            } catch (Exception e) {
                failure.set(e);
            }
        }, name);
        t.start();
        return t;
    }

    /** Send one placeholder of the given scope and ack it, returning the handle. */
    private MessageHandle sendAndAck(SignalJsonRpcClient client, FakeSignalCli fake,
                                     ScopeRef scope, String correlationId, long timestamp)
            throws Exception {
        AtomicReference<MessageHandle> sent = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        OutboundMessage out = new OutboundMessage(scope, "placeholder", Instant.now(), correlationId);
        Thread sender = new Thread(() -> {
            try {
                sent.set(client.send(out));
            } catch (MessagingException e) {
                failure.set(e);
            }
        }, "fallback-sender");
        sender.start();
        JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
        fake.respondSuccess(req.getString("id"), Json.createObjectBuilder()
                .add("timestamp", timestamp)
                .add("results", Json.createArrayBuilder())
                .build());
        sender.join(QUEUE_WAIT_MS);
        if (failure.get() != null) {
            fail("placeholder send() failed: " + failure.get());
        }
        MessageHandle handle = sent.get();
        assertNotNull(handle, "send() must return a handle within " + QUEUE_WAIT_MS + " ms");
        return handle;
    }
}
