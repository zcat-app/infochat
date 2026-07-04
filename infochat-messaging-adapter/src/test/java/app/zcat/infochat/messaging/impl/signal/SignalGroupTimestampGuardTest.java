package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import jakarta.json.JsonObject;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

/**
 * Pins the group inbound path's timestamp guard: a group frame that passes
 * the bot-mention gate but carries a missing / null / non-numeric /
 * fractional {@code timestamp} must be dropped cleanly rather than letting
 * a typed JSON accessor throw out of {@link SignalGroupHandler#handleReceive}.
 *
 * <p>The signal-cli daemon stream is a trust boundary; an NPE/CCE escaping
 * the handler reaches the reader thread's dispatch catch and the frame is
 * lost either way, but the two inbound paths (DM via the codec, group via
 * this handler) must treat the same untrusted field identically. The fix
 * routes the group path through {@code SignalMessageCodec.usableTimestamp},
 * the same total helper the DM path uses.</p>
 */
class SignalGroupTimestampGuardTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    @Test
    void wellFormedFrameDelivered() {
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000001000,
                    "dataMessage": {
                      "timestamp": 1700000001000,
                      "message": "@bot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """));

        assertEquals(1, inbound.messages.size(), "well-formed group frame must be delivered");
        InboundMessage msg = inbound.messages.get(0);
        assertEquals(Instant.ofEpochMilli(1700000001000L), msg.receivedAt(),
                "delivered message must carry the envelope timestamp as its receivedAt");
        assertEquals("signal-1700000001000", msg.adapterMessageId(),
                "adapter message id is derived from the same usable timestamp");
    }

    @Test
    void missingTimestampDroppedCleanly() {
        // Neither envelope nor dataMessage carries a timestamp. The old
        // code's getJsonNumber("timestamp") returned null and NPE'd on
        // longValueExact(); the guard must drop the frame instead.
        assertDroppedCleanly("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "dataMessage": {
                      "message": "@bot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """);
    }

    @Test
    void nullTimestampDroppedCleanly() {
        // A present-but-null timestamp — JsonValue.NULL, not a JsonNumber.
        assertDroppedCleanly("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": null,
                    "dataMessage": {
                      "timestamp": null,
                      "message": "@bot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """);
    }

    @Test
    void nonNumericTimestampDroppedCleanly() {
        // A string where a number is expected — the old getJsonNumber cast
        // threw ClassCastException; the guard must drop the frame.
        assertDroppedCleanly("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": "soon",
                    "dataMessage": {
                      "timestamp": "soon",
                      "message": "@bot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """);
    }

    @Test
    void fractionalTimestampDroppedCleanly() {
        // A fractional value — longValueExact() throws ArithmeticException;
        // the guard treats it as not a usable timestamp and drops.
        assertDroppedCleanly("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000001.5,
                    "dataMessage": {
                      "timestamp": 1700000001.5,
                      "message": "@bot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """);
    }

    private static void assertDroppedCleanly(String frameJson) {
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());
        JsonObject params = parse(frameJson);
        assertDoesNotThrow(() -> handler.handleReceive(params),
                "an unusable group timestamp must not throw out of handleReceive");
        assertEquals(0, inbound.messages.size(),
                "a frame with no usable timestamp must be dropped, not delivered");
    }

}
