package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

class SignalJsonRpcClientTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long QUEUE_WAIT_MS = 2_000;

    @Test
    void inboundMessageDelivered() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            client.setInboundHandler(delivered::add);
            client.connect();
            try {
                JsonObject receiveParams = Json.createObjectBuilder()
                        .add("envelope", Json.createObjectBuilder()
                                .add("source", "+15557654321")
                                .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                                .add("sourceName", "Alice")
                                .add("sourceDevice", 1)
                                .add("timestamp", 1700000001000L)
                                .add("dataMessage", Json.createObjectBuilder()
                                        .add("timestamp", 1700000001000L)
                                        .add("message", "hi from Alice")))
                        .build();
                fake.pushNotification("receive", receiveParams);

                InboundMessage msg = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg, "InboundHandler must receive the DM within " + QUEUE_WAIT_MS + " ms");
                assertEquals("hi from Alice", msg.text());
                assertEquals(
                        "aabbccdd-1111-2222-3333-444455556666",
                        msg.sender().contactId(),
                        "sender contactId must be the canonicalized ACI");
                assertTrue(msg.scope() instanceof ScopeRef.Dm,
                        "scope must be Dm for direct messages");
                assertEquals(
                        "aabbccdd-1111-2222-3333-444455556666",
                        ((ScopeRef.Dm) msg.scope()).contactId());
                assertEquals(Instant.ofEpochMilli(1700000001000L), msg.receivedAt());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void outboundSendReturnsHandle() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                // Spawn a producer thread that sends; the test thread runs
                // FakeSignalCli's accept-then-respond protocol.
                AtomicReference<MessageHandle> sentHandle = new AtomicReference<>();
                AtomicReference<Exception> sendFailure = new AtomicReference<>();
                OutboundMessage out = new OutboundMessage(
                        new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                        "hello world",
                        Instant.now(),
                        "corr-1");
                Thread sender = new Thread(() -> {
                    try {
                        sentHandle.set(client.send(out));
                    } catch (MessagingException e) {
                        sendFailure.set(e);
                    }
                }, "sender-thread");
                sender.start();

                JsonObject request = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", request.getString("method"));
                String requestId = request.getString("id");

                JsonObject result = Json.createObjectBuilder()
                        .add("timestamp", 1700000002500L)
                        .add("results", Json.createArrayBuilder())
                        .build();
                fake.respondSuccess(requestId, result);

                sender.join(QUEUE_WAIT_MS);
                if (sendFailure.get() != null) {
                    fail("send() failed: " + sendFailure.get());
                }
                MessageHandle handle = sentHandle.get();
                assertNotNull(handle, "send() must return a handle within " + QUEUE_WAIT_MS + " ms");
                assertTrue(handle.opaqueValue().startsWith("signal-"),
                        "Signal handle prefix is required for cross-adapter handle namespacing");

                // Subsequent update() on the same handle must target the
                // original send's timestamp. Drive another round-trip and
                // verify FakeSignalCli sees targetSentTimestamp=1700000002500.
                Thread updater = new Thread(() -> {
                    try {
                        client.update(handle, "hello world (edited)");
                    } catch (MessagingException e) {
                        sendFailure.set(e);
                    }
                }, "updater-thread");
                updater.start();

                JsonObject editRequest = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("updateMessage", editRequest.getString("method"));
                JsonObject editParams = editRequest.getJsonObject("params");
                assertEquals(
                        1700000002500L,
                        editParams.getJsonNumber("targetSentTimestamp").longValueExact(),
                        "updateMessage must target the timestamp returned by the original send");
                assertEquals("hello world (edited)", editParams.getString("message"));
                fake.respondSuccess(editRequest.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000003000L).build());

                updater.join(QUEUE_WAIT_MS);
                if (sendFailure.get() != null) {
                    fail("update() failed: " + sendFailure.get());
                }

                // finalize() then update() — second update must throw PERMANENT.
                Thread finalizer = new Thread(() -> {
                    try {
                        client.finalizeHandle(handle, "hello world (final)");
                    } catch (MessagingException e) {
                        sendFailure.set(e);
                    }
                }, "finalizer-thread");
                finalizer.start();
                JsonObject finalRequest = fake.nextOutbound(QUEUE_WAIT_MS);
                fake.respondSuccess(finalRequest.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000003500L).build());
                finalizer.join(QUEUE_WAIT_MS);
                if (sendFailure.get() != null) {
                    fail("finalize() failed: " + sendFailure.get());
                }

                MessagingException afterFinalize = assertThrows(
                        MessagingException.class,
                        () -> client.update(handle, "ignored"),
                        "update after finalize must throw");
                assertEquals(
                        FailureCategory.PERMANENT,
                        afterFinalize.category(),
                        "update-after-finalize must be PERMANENT per SPI invariant");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void transientErrorClassifiesAsTransient() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                AtomicReference<MessagingException> caught = new AtomicReference<>();
                Thread sender = new Thread(() -> {
                    try {
                        client.send(new OutboundMessage(
                                new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                                "msg",
                                Instant.now(),
                                "c1"));
                    } catch (MessagingException e) {
                        caught.set(e);
                    }
                }, "transient-sender");
                sender.start();
                JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
                fake.respondError(req.getString("id"), -32603, "Internal error");
                sender.join(QUEUE_WAIT_MS);
                MessagingException e = caught.get();
                assertNotNull(e, "send must throw on JSON-RPC error response");
                assertEquals(
                        FailureCategory.TRANSIENT,
                        e.category(),
                        "JSON-RPC -32603 (internal error) maps to TRANSIENT");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void permanentErrorClassifiesAsPermanent() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                AtomicReference<MessagingException> caught = new AtomicReference<>();
                Thread sender = new Thread(() -> {
                    try {
                        client.send(new OutboundMessage(
                                new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                                "msg",
                                Instant.now(),
                                "c2"));
                    } catch (MessagingException e) {
                        caught.set(e);
                    }
                }, "permanent-sender");
                sender.start();
                JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
                // -32602 (invalid params) is one of the JSON-RPC-defined
                // non-internal errors; signal-cli's "unknown recipient" and
                // "user blocked" responses likewise carry non-transient
                // codes and must classify as PERMANENT per the spec's
                // default-to-PERMANENT rule.
                fake.respondError(req.getString("id"), -32602, "Invalid recipient");
                sender.join(QUEUE_WAIT_MS);
                MessagingException e = caught.get();
                assertNotNull(e, "send must throw on JSON-RPC error response");
                assertEquals(
                        FailureCategory.PERMANENT,
                        e.category(),
                        "non-internal JSON-RPC errors default to PERMANENT");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void oversizeInboundLineIsDroppedAndReaderSurvives() throws Exception {
        // Regression for the M1-107 red-team DOS finding: the
        // SignalAdapter capability declares maxInboundMessageBytes=16384,
        // but BufferedReader.readLine() had no bound. A peer that emits
        // a line longer than the cap could grow the JVM heap until OOM.
        // The fix replaces readLine() with a cap-aware accumulator that
        // drops oversize lines and resumes at the next terminator.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            client.setInboundHandler(delivered::add);
            client.connect();
            try {
                // Feed an oversize line (well over the 16384 cap). The
                // payload happens to be valid-looking JSON-RPC, but the
                // size-cap must fire BEFORE the codec ever sees it.
                StringBuilder oversize = new StringBuilder(20_000);
                oversize.append("{\"jsonrpc\":\"2.0\",\"method\":\"receive\",\"params\":\"");
                for (int i = 0; i < 20_000; i++) {
                    oversize.append('A');
                }
                oversize.append("\"}");
                fake.pushRawLine(oversize.toString());

                // Then a normal-sized receive notification. If the
                // reader correctly drained the oversize line up to its
                // terminator and resumed, this MUST be delivered.
                JsonObject receiveParams = Json.createObjectBuilder()
                        .add("envelope", Json.createObjectBuilder()
                                .add("source", "+15557654321")
                                .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                                .add("sourceName", "Alice")
                                .add("sourceDevice", 1)
                                .add("timestamp", 1700000099000L)
                                .add("dataMessage", Json.createObjectBuilder()
                                        .add("timestamp", 1700000099000L)
                                        .add("message", "post-overflow"))).build();
                fake.pushNotification("receive", receiveParams);

                InboundMessage msg = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg,
                        "reader loop must survive oversize line and deliver subsequent normal notifications");
                assertEquals("post-overflow", msg.text());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void handleEvictedOnFinalize() throws Exception {
        // Regression for the M1-107 red-team DOS finding: the open-handle
        // map grew without bound — every send() added an entry, no path
        // removed entries. Fix: finalizeHandle() removes from the map,
        // so the steady-state handle count tracks the in-flight (open)
        // message count, not the cumulative send count.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                assertEquals(0, client.openHandleCount(), "fresh client has zero open handles");

                MessageHandle handle = sendOneAndAck(client, fake, 1700000010000L);
                assertEquals(1, client.openHandleCount(), "send() registers one open handle");

                finalizeOneAndAck(client, fake, handle, 1700000010500L);
                assertEquals(0, client.openHandleCount(),
                        "finalizeHandle() must evict the handle so the map is bounded by in-flight count");

                // A second update on the now-evicted handle must throw
                // PERMANENT — the SPI invariant for both "unknown" and
                // "already finalized" outcomes is unchanged.
                MessagingException afterEvict = assertThrows(
                        MessagingException.class,
                        () -> client.update(handle, "post-finalize"),
                        "update after finalize must throw");
                assertEquals(FailureCategory.PERMANENT, afterEvict.category());
            } finally {
                client.disconnect();
            }
        }
    }

    private MessageHandle sendOneAndAck(SignalJsonRpcClient client, FakeSignalCli fake, long ts)
            throws Exception {
        AtomicReference<MessageHandle> sentHandle = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        OutboundMessage out = new OutboundMessage(
                new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                "hello",
                Instant.now(),
                "corr-x");
        Thread sender = new Thread(() -> {
            try {
                sentHandle.set(client.send(out));
            } catch (MessagingException e) {
                failure.set(e);
            }
        }, "evict-sender");
        sender.start();
        JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
        fake.respondSuccess(req.getString("id"),
                Json.createObjectBuilder().add("timestamp", ts).add("results", Json.createArrayBuilder()).build());
        sender.join(QUEUE_WAIT_MS);
        if (failure.get() != null) {
            fail("send() failed: " + failure.get());
        }
        return sentHandle.get();
    }

    private void finalizeOneAndAck(SignalJsonRpcClient client, FakeSignalCli fake,
                                   MessageHandle handle, long ts) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread finalizer = new Thread(() -> {
            try {
                client.finalizeHandle(handle, "final");
            } catch (MessagingException e) {
                failure.set(e);
            }
        }, "evict-finalizer");
        finalizer.start();
        JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
        fake.respondSuccess(req.getString("id"),
                Json.createObjectBuilder().add("timestamp", ts).build());
        finalizer.join(QUEUE_WAIT_MS);
        if (failure.get() != null) {
            fail("finalize() failed: " + failure.get());
        }
    }

    @Test
    void groupScopeSendRejectedPermanent() throws Exception {
        // M1-107 is DM-only; group send must throw PERMANENT (M1-108
        // owns the group send path).
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                OutboundMessage groupMsg = new OutboundMessage(
                        new ScopeRef.Group("group-1"),
                        "hi group",
                        Instant.now(),
                        "g1");
                MessagingException e = assertThrows(MessagingException.class, () -> client.send(groupMsg));
                assertEquals(FailureCategory.PERMANENT, e.category());
            } finally {
                client.disconnect();
            }
        }
    }
}
