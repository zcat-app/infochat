package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.InboundMessage;

/**
 * M1-486: the inbound JSON-RPC line cap ({@link SignalJsonRpcClient#MAX_INBOUND_LINE_CHARS})
 * is a distinct, larger layer than the decoded-body byte cap
 * ({@link SignalMessageCodec#MAX_INBOUND_TEXT_BYTES}). Before the fix the
 * two were the same constant (16_384), so an ASCII body near the body cap
 * — whose enclosing JSON-RPC envelope line is always longer than the body
 * it wraps — was dropped at the line layer before it could reach the
 * body-cap / OversizeDm path, silently discarding legal messages. This
 * test feeds exactly that shape through the real reader loop and asserts
 * the body-cap layer governs, not the line cap.
 */
class SignalInboundLineCapTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long QUEUE_WAIT_MS = 2_000;

    private static final String SENDER_ACI = "AABBCCDD-1111-2222-3333-444455556666";

    @Test
    void lineCapIsSizedAboveBodyCap() {
        // Acceptance item 1: the transport-line cap and the decoded-body
        // cap are no longer the same constant — the line cap is strictly
        // above the body cap so the body-cap layer is reachable.
        assertTrue(SignalJsonRpcClient.MAX_INBOUND_LINE_CHARS
                        > SignalMessageCodec.MAX_INBOUND_TEXT_BYTES,
                "the inbound line cap must be sized strictly above the body cap");
    }

    @Test
    void asciiBodyJustUnderBodyCapReachesBodyCapPathNotLineDropped() throws Exception {
        // An ASCII body just under the body cap: its UTF-8 byte length is
        // below MAX_INBOUND_TEXT_BYTES (so the body cap does NOT fire and
        // the message must be delivered), but the enclosing JSON-RPC line
        // exceeds the old shared cap (= MAX_INBOUND_TEXT_BYTES) once
        // envelope framing is added. Under the old equal caps this legal
        // message was dropped at the line layer.
        String body = "A".repeat(SignalMessageCodec.MAX_INBOUND_TEXT_BYTES - 128);
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length
                        < SignalMessageCodec.MAX_INBOUND_TEXT_BYTES,
                "test body must stay just under the body byte cap so it is a legal, deliverable message");

        String line = Json.createObjectBuilder()
                .add("jsonrpc", "2.0")
                .add("method", "receive")
                .add("params", Json.createObjectBuilder()
                        .add("envelope", Json.createObjectBuilder()
                                .add("source", "+15557654321")
                                .add("sourceUuid", SENDER_ACI)
                                .add("sourceName", "Alice")
                                .add("sourceDevice", 1)
                                .add("timestamp", 1_700_000_123_000L)
                                .add("dataMessage", Json.createObjectBuilder()
                                        .add("timestamp", 1_700_000_123_000L)
                                        .add("message", body))))
                .build()
                .toString();
        assertTrue(line.length() > SignalMessageCodec.MAX_INBOUND_TEXT_BYTES,
                "the enclosing JSON-RPC line must exceed the old shared cap, so the regression "
                        + "(line-layer drop pre-empting the body cap) is actually exercised");

        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            client.setInboundHandler(delivered::add);
            client.connect();
            try {
                fake.pushRawLine(line);

                InboundMessage msg = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg,
                        "a body under the byte cap whose envelope line exceeds the old shared cap "
                                + "must reach the body-cap path and be delivered, not dropped at the line layer");
                assertEquals(body, msg.text(),
                        "the delivered body must be the full near-cap message, intact");
                assertEquals("aabbccdd-1111-2222-3333-444455556666", msg.sender().contactId(),
                        "sender contactId must be the canonicalized ACI");
            } finally {
                client.disconnect();
            }
        }
    }
}
