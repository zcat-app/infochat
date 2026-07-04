package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;



import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.metrics.AdapterMetrics;

/**
 * T21 (regression pin, no production change): a group frame carrying a
 * non-integer mention-span value must be handled without an exception
 * escaping {@link SignalGroupHandler#handleReceive}. The span read uses
 * the two-arg {@code getInt(name, -1)}, and Parsson returns the default
 * for a non-{@code JsonNumber} value, so a wrong-typed span yields
 * {@code -1} and is skipped by the existing {@code start < 0 / length <= 0}
 * bounds guard — the body is delivered unstripped, no
 * {@code ClassCastException}. This test pins that implementation-dependent
 * trust-boundary behavior so a future refactor (or a JSON-P provider swap)
 * cannot silently reintroduce a crash.
 */
class SignalGroupSpanTypeTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";

    @Test
    void wrongTypedSpanValueDroppedWithoutException() {
        // "start" is a JSON string, not a number. The bot is still
        // ACI-mentioned (the mention uuid matches), so the message is
        // delivered — but the malformed span is skipped, leaving the
        // body unstripped, and no exception escapes handleReceive.
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        assertDoesNotThrow(() -> handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000001000,
                    "dataMessage": {
                      "timestamp": 1700000001000,
                      "message": "@bot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": "zero", "length": 4}
                      ]
                    }
                  }
                }
                """)));

        assertEquals(1, inbound.messages.size(),
                "the message is still delivered — the bot is ACI-mentioned");
        assertEquals("@bot summarise this", inbound.messages.get(0).text(),
                "a wrong-typed span is skipped, so the body is delivered unstripped");
    }

    @Test
    void wellFormedSpanStrippedAsBefore() {
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

        assertEquals(1, inbound.messages.size());
        assertEquals("summarise this", inbound.messages.get(0).text(),
                "a well-formed mention span is stripped before delivery");
    }

}
