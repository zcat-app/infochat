package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
    // ASCII '#': a byte the readIoException fault stream throws on. Any byte
    // works — it is consumed by the faulting read, never decoded as a line.
    private static final int FAULT_SENTINEL = '#';

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
                // verify FakeSignalCli sees editTimestamp=1700000002500.
                Thread updater = new Thread(() -> {
                    try {
                        client.update(handle, "hello world (edited)");
                    } catch (MessagingException e) {
                        sendFailure.set(e);
                    }
                }, "updater-thread");
                updater.start();

                JsonObject editRequest = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", editRequest.getString("method"),
                        "an edit is a send carrying editTimestamp — signal-cli has no updateMessage");
                JsonObject editParams = editRequest.getJsonObject("params");
                assertEquals(
                        1700000002500L,
                        editParams.getJsonNumber("editTimestamp").longValueExact(),
                        "the first edit must target the timestamp returned by the original send");
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
    void errorResponseMissingCodeClassifiesPermanent() throws Exception {
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
                                "c-no-code"));
                    } catch (MessagingException e) {
                        caught.set(e);
                    }
                }, "missing-code-sender");
                sender.start();
                JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
                // An error object with NO "code" member: a transient cause
                // cannot be proven, so it must fall to the documented
                // default-PERMANENT rule — never into the synthesized
                // -32603→TRANSIENT branch.
                fake.pushRawLine("{\"jsonrpc\":\"2.0\",\"id\":\"" + req.getString("id")
                        + "\",\"error\":{\"message\":\"boom\"}}");
                sender.join(QUEUE_WAIT_MS);
                MessagingException e = caught.get();
                assertNotNull(e, "send must throw on JSON-RPC error response");
                assertEquals(
                        FailureCategory.PERMANENT,
                        e.category(),
                        "an error response missing its code must default to PERMANENT");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void sendRejectsNonNumericTimestampWithMessagingException() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                AtomicReference<MessagingException> caughtMessaging = new AtomicReference<>();
                AtomicReference<RuntimeException> escaped = new AtomicReference<>();
                Thread sender = new Thread(() -> {
                    try {
                        client.send(new OutboundMessage(
                                new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                                "msg",
                                Instant.now(),
                                "c-bad-ts"));
                    } catch (MessagingException e) {
                        caughtMessaging.set(e);
                    } catch (RuntimeException e) {
                        escaped.set(e);
                    }
                }, "bad-timestamp-sender");
                sender.start();
                JsonObject req = fake.nextOutbound(QUEUE_WAIT_MS);
                fake.respondSuccess(req.getString("id"), Json.createObjectBuilder()
                        .add("timestamp", "not-a-number")
                        .build());
                sender.join(QUEUE_WAIT_MS);
                assertNull(escaped.get(),
                        "a wrong-typed daemon field must never escape send() as an"
                                + " unclassified RuntimeException (was: " + escaped.get() + ")");
                MessagingException e = caughtMessaging.get();
                assertNotNull(e, "send must throw MessagingException on a non-numeric timestamp");
                assertEquals(
                        FailureCategory.PERMANENT,
                        e.category(),
                        "a malformed daemon response is not retriable");
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
                // Feed an oversize line (well over MAX_INBOUND_LINE_CHARS). The
                // payload happens to be valid-looking JSON-RPC, but the
                // size-cap must fire BEFORE the codec ever sees it. Sized off
                // the cap constant so this stays a genuine over-cap line if the
                // cap moves (it was decoupled from the body cap in M1-486).
                int oversizeChars = SignalJsonRpcClient.MAX_INBOUND_LINE_CHARS + 4_096;
                StringBuilder oversize = new StringBuilder(oversizeChars + 64);
                oversize.append("{\"jsonrpc\":\"2.0\",\"method\":\"receive\",\"params\":\"");
                for (int i = 0; i < oversizeChars; i++) {
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
    void successiveEditsTargetLatestRevisionTimestamp() throws Exception {
        // The edit chain must follow the LATEST revision (F-live-11 fix):
        // the first edit targets the original send's timestamp, and each
        // successful edit's response timestamp becomes the next edit's
        // editTimestamp — official Signal clients accept a chain targeting
        // the latest revision.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                MessageHandle handle = sendOneAndAck(client, fake, 1700000060000L);

                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread first = new Thread(() -> {
                    try {
                        client.update(handle, "revision 2");
                    } catch (MessagingException e) {
                        failure.set(e);
                    }
                }, "chain-updater-1");
                first.start();
                JsonObject edit1 = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", edit1.getString("method"));
                assertEquals(1700000060000L,
                        edit1.getJsonObject("params").getJsonNumber("editTimestamp").longValueExact(),
                        "the first edit targets the original send's timestamp");
                fake.respondSuccess(edit1.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000061000L).build());
                first.join(QUEUE_WAIT_MS);
                assertNull(failure.get(), "first update failed: " + failure.get());

                Thread second = new Thread(() -> {
                    try {
                        client.update(handle, "revision 3");
                    } catch (MessagingException e) {
                        failure.set(e);
                    }
                }, "chain-updater-2");
                second.start();
                JsonObject edit2 = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", edit2.getString("method"));
                assertEquals(1700000061000L,
                        edit2.getJsonObject("params").getJsonNumber("editTimestamp").longValueExact(),
                        "the second edit targets the FIRST edit's response timestamp"
                                + " — the latest revision");
                fake.respondSuccess(edit2.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000062000L).build());
                second.join(QUEUE_WAIT_MS);
                assertNull(failure.get(), "second update failed: " + failure.get());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void handlerExceptionDoesNotKillReader() throws Exception {
        // A10: a RuntimeException thrown by the InboundHandler must be caught
        // in dispatchNotification (mirroring SimpleXAdapter.onInbound) so the
        // signal-jsonrpc-reader thread survives and keeps delivering inbound
        // messages — a thrown handler used to propagate through readerLoop and
        // kill the reader, leaving the subprocess alive but deaf.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            AtomicBoolean firstThrown = new AtomicBoolean(false);
            client.setInboundHandler(msg -> {
                if (firstThrown.compareAndSet(false, true)) {
                    throw new RuntimeException("boom from handler");
                }
                delivered.add(msg);
            });
            client.connect();
            try {
                fake.pushNotification("receive", receiveParams("first", 1700000050000L));
                fake.pushNotification("receive", receiveParams("second", 1700000051000L));

                InboundMessage msg = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg,
                        "reader must survive a handler RuntimeException and deliver subsequent messages");
                assertEquals("second", msg.text());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void consecutiveTimeoutsForceSubprocessRestart() throws Exception {
        // B-SIGNAL-HUNG: a deadlocked-but-alive daemon never fires
        // Process.onExit, so the JSON-RPC client escalates a run of
        // consecutive response timeouts into a forced subprocess restart.
        // FakeSignalCli captures each outbound request but never responds, so
        // every send() times out; the third consecutive timeout fires the
        // restart hook exactly once.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            // Short response timeout so three timeouts accrue quickly.
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    Duration.ofMillis(150), restartCalls::incrementAndGet,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.connect();
            try {
                for (int i = 0; i < 3; i++) {
                    OutboundMessage out = new OutboundMessage(
                            new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                            "msg", Instant.now(), "c" + i);
                    MessagingException e = assertThrows(MessagingException.class,
                            () -> client.send(out));
                    assertEquals(FailureCategory.TRANSIENT, e.category(),
                            "a JSON-RPC response timeout classifies as TRANSIENT");
                }
                assertEquals(1, restartCalls.get(),
                        "three consecutive JSON-RPC timeouts must force exactly one subprocess restart");
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

    @Test
    void groupSendCarriesGroupIdAndReturnsHandle() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                AtomicReference<MessageHandle> sentHandle = new AtomicReference<>();
                AtomicReference<Exception> sendFailure = new AtomicReference<>();
                OutboundMessage groupMsg = new OutboundMessage(
                        new ScopeRef.Group("group-1"), "hi group", Instant.now(), "g1");
                Thread sender = new Thread(() -> {
                    try {
                        sentHandle.set(client.send(groupMsg));
                    } catch (MessagingException e) {
                        sendFailure.set(e);
                    }
                }, "group-sender");
                sender.start();

                JsonObject request = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", request.getString("method"));
                JsonObject params = request.getJsonObject("params");
                assertEquals("group-1", params.getString("groupId"),
                        "group send must address the group by groupId");
                assertFalse(params.containsKey("recipient"),
                        "group send must not carry a recipient array — groupId replaces it");
                fake.respondSuccess(request.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000002500L).build());

                sender.join(QUEUE_WAIT_MS);
                if (sendFailure.get() != null) {
                    fail("group send() failed: " + sendFailure.get());
                }
                MessageHandle handle = sentHandle.get();
                assertNotNull(handle, "group send() must return a handle within " + QUEUE_WAIT_MS + " ms");
                assertTrue(handle.opaqueValue().startsWith("signal-"),
                        "Signal handle prefix is required for cross-adapter handle namespacing");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void groupSendUpdateFinalizeCycleSucceeds() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                AtomicReference<MessageHandle> sentHandle = new AtomicReference<>();
                AtomicReference<Exception> failure = new AtomicReference<>();
                OutboundMessage groupMsg = new OutboundMessage(
                        new ScopeRef.Group("group-7"), "hi group", Instant.now(), "g7");
                Thread sender = new Thread(() -> {
                    try {
                        sentHandle.set(client.send(groupMsg));
                    } catch (MessagingException e) {
                        failure.set(e);
                    }
                }, "group-sender");
                sender.start();

                JsonObject sendRequest = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("group-7", sendRequest.getJsonObject("params").getString("groupId"));
                fake.respondSuccess(sendRequest.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000002500L).build());
                sender.join(QUEUE_WAIT_MS);
                if (failure.get() != null) {
                    fail("group send() failed: " + failure.get());
                }
                MessageHandle handle = sentHandle.get();
                assertNotNull(handle, "group send() must return a handle");

                // update() on a group handle must re-address by groupId and
                // target the original send's timestamp.
                Thread updater = new Thread(() -> {
                    try {
                        client.update(handle, "hi group (edited)");
                    } catch (MessagingException e) {
                        failure.set(e);
                    }
                }, "group-updater");
                updater.start();
                JsonObject editRequest = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", editRequest.getString("method"),
                        "a group edit is a send carrying editTimestamp — signal-cli has no updateMessage");
                JsonObject editParams = editRequest.getJsonObject("params");
                assertEquals("group-7", editParams.getString("groupId"),
                        "group update must re-address by groupId");
                assertFalse(editParams.containsKey("recipient"),
                        "group update must not carry a recipient array");
                assertEquals(1700000002500L,
                        editParams.getJsonNumber("editTimestamp").longValueExact(),
                        "group update must target the timestamp returned by the original group send");
                fake.respondSuccess(editRequest.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000003000L).build());
                updater.join(QUEUE_WAIT_MS);
                if (failure.get() != null) {
                    fail("group update() failed: " + failure.get());
                }

                // finalize() likewise edits by groupId, then evicts the handle.
                Thread finalizer = new Thread(() -> {
                    try {
                        client.finalizeHandle(handle, "hi group (final)");
                    } catch (MessagingException e) {
                        failure.set(e);
                    }
                }, "group-finalizer");
                finalizer.start();
                JsonObject finalRequest = fake.nextOutbound(QUEUE_WAIT_MS);
                assertEquals("send", finalRequest.getString("method"));
                assertEquals("group-7", finalRequest.getJsonObject("params").getString("groupId"),
                        "group finalize must re-address by groupId");
                assertEquals(1700000003000L,
                        finalRequest.getJsonObject("params")
                                .getJsonNumber("editTimestamp").longValueExact(),
                        "the finalize edit must target the FIRST edit's response timestamp"
                                + " — the latest revision, not the original send");
                fake.respondSuccess(finalRequest.getString("id"),
                        Json.createObjectBuilder().add("timestamp", 1700000003500L).build());
                finalizer.join(QUEUE_WAIT_MS);
                if (failure.get() != null) {
                    fail("group finalize() failed: " + failure.get());
                }
            } finally {
                client.disconnect();
            }
        }
    }

    // ---- M1-681: reader-exit transport-death latch ----

    /** Poll up to {@link #QUEUE_WAIT_MS} for an asynchronously-latched condition. */
    private static void awaitTrue(String what, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUEUE_WAIT_MS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    void peerEofLatchesChannelDead() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                assertTrue(client.isConnected(), "sanity: connected before the kill");
                // killClientConnection is a plain Socket.close() — a clean FIN,
                // so the reader's read() returns -1: the EOF arm of the latch.
                fake.killClientConnection();
                awaitTrue("clean EOF must latch the channel dead — isConnected() false"
                                + " while signal-cli keeps running",
                        () -> !client.isConnected());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void readIoExceptionLatchesChannelDead() throws Exception {
        // FakeSignalCli can only produce a clean FIN (the EOF arm); the
        // IOException arm needs a mid-stream read fault, injected through the
        // newSocket() seam. The fault is keyed to a SENTINEL byte the stream
        // throws on, NOT a flag the test flips: a flag flip races the reader's
        // startup — if the reader reaches its first read() after the flip, it
        // throws before any data arrives, exits, and closes the socket before
        // pushRawLine can write (SocketException, observed under full-suite
        // load). Keying to a byte makes the reader always block in the real
        // read() until the sentinel arrives, so the fault is deterministic.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT) {
                @Override
                Socket newSocket() {
                    return new Socket() {
                        @Override
                        public InputStream getInputStream() throws IOException {
                            return new FilterInputStream(super.getInputStream()) {
                                @Override
                                public int read() throws IOException {
                                    int c = super.read();
                                    if (c == FAULT_SENTINEL) {
                                        throw new IOException("injected transport fault");
                                    }
                                    return c;
                                }

                                @Override
                                public int read(byte[] b, int off, int len) throws IOException {
                                    int n = super.read(b, off, len);
                                    for (int i = off; i < off + n; i++) {
                                        if (b[i] == FAULT_SENTINEL) {
                                            throw new IOException("injected transport fault");
                                        }
                                    }
                                    return n;
                                }
                            };
                        }
                    };
                }
            };
            client.connect();
            try {
                assertTrue(client.isConnected(), "sanity: connected before the fault");
                // The reader is blocked in read() awaiting bytes; the sentinel
                // makes the read that consumes it throw, exiting the reader.
                fake.pushRawLine(String.valueOf((char) FAULT_SENTINEL));
                awaitTrue("a reader IOException must latch the channel dead — isConnected() false",
                        () -> !client.isConnected());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void latchDrainsInFlightCallAndFiresRestartHook() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, restartCalls::incrementAndGet,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.connect();
            try {
                AtomicReference<MessagingException> caught = new AtomicReference<>();
                Thread sender = new Thread(() -> {
                    try {
                        client.send(new OutboundMessage(
                                new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                                "msg", Instant.now(), "c-latch"));
                    } catch (MessagingException e) {
                        caught.set(e);
                    }
                }, "latch-drain-sender");
                sender.start();
                // Await the captured request so the send's future is pending
                // (written, unanswered) before the channel dies.
                fake.nextOutbound(QUEUE_WAIT_MS);
                fake.killClientConnection();
                // The join bound sits far below the 5 s response timeout: only
                // the latch's drain — never the timeout arm — can release the
                // sender this fast, and only the drain stamps PERMANENT.
                sender.join(QUEUE_WAIT_MS);
                assertFalse(sender.isAlive(),
                        "latch drain must release the in-flight call well before the response timeout");
                MessagingException e = caught.get();
                assertNotNull(e, "the drained in-flight call must fail");
                assertEquals(FailureCategory.PERMANENT, e.category(),
                        "a call in flight at channel death drains closed-before-ack PERMANENT");
                awaitTrue("channel death must fire the supervised-restart hook",
                        () -> restartCalls.get() == 1);
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void channelDeathDetectedWithZeroOutboundTraffic() throws Exception {
        // The no-outbound-traffic hole (M1-674 audit finding 2): inbound death
        // removes the traffic whose replies were the only thing the
        // consecutive-timeout counter could observe, so before the latch this
        // realistic case was undetectable — the silence sustained itself.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, restartCalls::incrementAndGet,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.connect();
            try {
                assertTrue(client.isConnected(), "sanity: connected before the kill");
                fake.killClientConnection();
                awaitTrue("detection must fire with ZERO call() invocations:"
                                + " restart hook fired and channel latched dead",
                        () -> restartCalls.get() == 1 && !client.isConnected());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void timeoutEscalationThenReaderExitFiresOneRestart() throws Exception {
        // The two detectors observe the SAME death in sequence: the
        // consecutive-timeout escalation fires the restart, the restart's
        // SIGKILL severs the socket, the reader exits — and the latch must
        // not fire a second restart for that one death.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            // Short response timeout so three timeouts accrue quickly.
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    Duration.ofMillis(150), restartCalls::incrementAndGet,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.connect();
            try {
                for (int i = 0; i < 3; i++) {
                    OutboundMessage out = new OutboundMessage(
                            new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                            "msg", Instant.now(), "c" + i);
                    assertThrows(MessagingException.class, () -> client.send(out));
                }
                assertEquals(1, restartCalls.get(),
                        "sanity: the timeout escalation fired the first restart");
                // The real hook SIGKILLs the child, which severs this socket;
                // the fake severs it directly.
                fake.killClientConnection();
                awaitTrue("reader exit must still latch the channel dead",
                        () -> !client.isConnected());
                assertEquals(1, restartCalls.get(),
                        "the reader-exit latch must not fire a second restart for the same death");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void staleReaderCannotLatchTheConnectionThatReplacedIt() throws Exception {
        // One client instance spans reconnects, so a reader that outlived
        // disconnect()'s bounded 2 s join is the hazard: if the latch's state
        // lived on the CLIENT, that reader would latch, drain, tear down and
        // restart whatever connection happened to be current when it finally
        // woke. Per-connection state makes it structurally unable to reach a
        // successor, and this pins that — the ordering is deterministic
        // because the first reader cannot exit until its own socket is
        // severed, which happens only AFTER the replacement is published.
        // Each assertion answers one mutation the superseded reader would
        // otherwise have made.
        List<Socket> handedOut = new CopyOnWriteArrayList<>();
        // Advances across the simulated respawn, exactly as
        // SignalSubprocess.generation() does: connection 1 is stamped 1,
        // connection 2 is stamped 2.
        AtomicLong daemonGen = new AtomicLong(1);
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, restartCalls::incrementAndGet, daemonGen::get) {
                @Override
                Socket newSocket() {
                    Socket s = super.newSocket();
                    handedOut.add(s);
                    return s;
                }
            };
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            client.setInboundHandler(delivered::add);
            client.connect();
            try {
                Thread staleReader = Objects.requireNonNull(
                        client.readerThread(), "sanity: connect() started a reader thread");
                // The supervised restart respawns the child (generation
                // advances) and then the replacement connection is published,
                // while the first reader is still parked in read() on a live
                // socket. No disconnect() first — skipping it is exactly what
                // models the zombie window; the superseded dispatcher is left
                // to GC.
                daemonGen.incrementAndGet();
                client.connect();
                assertEquals(2, handedOut.size(), "sanity: two sockets were dialed");

                AtomicReference<MessageHandle> sentHandle = new AtomicReference<>();
                AtomicReference<Exception> sendFailure = new AtomicReference<>();
                Thread sender = new Thread(() -> {
                    try {
                        sentHandle.set(client.send(new OutboundMessage(
                                new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                                "on the replacement", Instant.now(), "corr-stale")));
                    } catch (MessagingException e) {
                        sendFailure.set(e);
                    }
                }, "post-replacement-sender");
                sender.start();
                // call() registers the pending future BEFORE writing, so a
                // captured request line proves the replacement has an
                // in-flight call for a stale drain to hit.
                String requestId = fake.nextOutbound(QUEUE_WAIT_MS).getString("id");

                // Sever the FIRST socket from the client side — the fake
                // tracks only its newest accepted connection, so killing
                // through it would hit the replacement instead.
                handedOut.get(0).close();
                awaitTrue("the superseded reader must reach its latch and exit",
                        () -> !staleReader.isAlive());

                assertEquals(0, restartCalls.get(),
                        "a superseded reader must not fire a restart: the subprocess is shared,"
                                + " so that would SIGKILL the daemon under the live connection");
                assertTrue(client.isConnected(),
                        "a superseded reader must not latch the connection that replaced it");
                // The replacement's dispatcher survived: inbound still routes.
                fake.pushNotification("receive", Json.createObjectBuilder()
                        .add("envelope", Json.createObjectBuilder()
                                .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                                .add("sourceDevice", 1)
                                .add("timestamp", 1700000003000L)
                                .add("dataMessage", Json.createObjectBuilder()
                                        .add("timestamp", 1700000003000L)
                                        .add("message", "still delivering")))
                        .build());
                assertNotNull(delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS),
                        "a superseded reader must not shut down the replacement's dispatcher");
                // The replacement's in-flight call was not drained: it still
                // resolves from the wire rather than closed-before-ack.
                fake.respondSuccess(requestId, Json.createObjectBuilder()
                        .add("timestamp", 1700000003500L)
                        .add("results", Json.createArrayBuilder())
                        .build());
                sender.join(QUEUE_WAIT_MS);
                if (sendFailure.get() != null) {
                    fail("a superseded reader must not drain the replacement's in-flight call: "
                            + sendFailure.get());
                }
                assertNotNull(sentHandle.get(), "the in-flight call must complete normally");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void staleReaderDoesNotRestartDaemonReplacedDuringReconnectWindow() throws Exception {
        // RT-M1-681-r2-1: the production window a `conn == current` guard
        // could NOT catch. The supervised restart spawns the successor child
        // (generation advances) up to ENDPOINT_PROBE_TIMEOUT before
        // reconnect() calls disconnect() to retire the dead connection, so for
        // that whole window the ORIGINAL connection is still `current` while a
        // healthy new daemon runs. A reader exiting then must NOT fire the
        // restart — it would SIGKILL the successor. Here `current` is never
        // advanced (no second connect()), so `conn == current` holds; only the
        // daemon-generation gate distinguishes the dead child from the live
        // one.
        AtomicLong daemonGen = new AtomicLong(1);
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, restartCalls::incrementAndGet, daemonGen::get);
            client.connect();
            try {
                assertTrue(client.isConnected(), "sanity: connected on generation 1");
                // The supervisor respawns the child: generation advances, but
                // reconnect() has not run disconnect() yet, so this connection
                // stays current.
                daemonGen.incrementAndGet();
                fake.killClientConnection();
                awaitTrue("the reader must reach its latch and exit",
                        () -> !client.isConnected());
                assertEquals(0, restartCalls.get(),
                        "a reader whose child was already replaced must fire no restart,"
                                + " even though its connection is still current");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void readerExitRestartsWhenItsDaemonGenerationIsStillLive() throws Exception {
        // The positive complement: when the generation still matches — the
        // common case, a channel death with no intervening respawn — the
        // reader-exit latch DOES drive the restart. Proves the gate is not
        // vacuously off.
        AtomicLong daemonGen = new AtomicLong(7);
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, restartCalls::incrementAndGet, daemonGen::get);
            client.connect();
            try {
                fake.killClientConnection();
                awaitTrue("a channel death on the live generation must fire the restart",
                        () -> restartCalls.get() == 1 && !client.isConnected());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void intentionalDisconnectDoesNotFireRestartHook() throws Exception {
        // Every reconnect() teardown and every test's disconnect() routes the
        // reader through its exit latch; reading that exit as a peer death
        // would turn each into a spurious subprocess restart.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            AtomicInteger restartCalls = new AtomicInteger();
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, restartCalls::incrementAndGet,
                    SignalJsonRpcClient.ALWAYS_MATCHING_GENERATION);
            client.connect();
            client.disconnect();
            assertFalse(client.isConnected(), "a disconnected client must read disconnected");
            assertEquals(0, restartCalls.get(),
                    "a local disconnect must never fire the restart hook");
        }
    }
}
