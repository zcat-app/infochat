package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;



import org.junit.jupiter.api.Test;

/**
 * Pins the M1-184 malformed-frame totality contract of
 * {@link SignalMessageCodec}: no inbound frame shape may escape
 * {@code extractDm} as an NPE/CCE (the frame drops instead), and no
 * thrown decode exception may carry frame content in its message or
 * cause chain (D37, security spec §User content in exceptions).
 */
class SignalCodecMalformedFrameTest {

    private static final String SENTINEL = "REDTEAM-SENTINEL-FRAME-BYTES";
    private static final String SOURCE_ACI = "AABBCCDD-1111-2222-3333-444455556666";

    private final SignalMessageCodec codec = new SignalMessageCodec();

    @Test
    void frameWithoutUsableTimestampIsDroppedNotThrown() {
        // Timestamp absent in BOTH envelope and dataMessage — the old
        // getJsonNumber(...).longValueExact() chain threw NPE here.
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(parse("""
                {"envelope":{"sourceUuid":"%s",
                  "dataMessage":{"message":"hi"}}}
                """.formatted(SOURCE_ACI))),
                "absent-in-both timestamp must drop the frame, not throw");

        // Wrong-typed timestamp in envelope, absent in dataMessage —
        // the old envelope.getJsonNumber("timestamp") threw CCE here.
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(parse("""
                {"envelope":{"sourceUuid":"%s","timestamp":"soon",
                  "dataMessage":{"message":"hi"}}}
                """.formatted(SOURCE_ACI))),
                "wrong-typed envelope timestamp with no fallback must drop the frame, not throw");

        // Wrong-typed timestamp in both fields.
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(parse("""
                {"envelope":{"sourceUuid":"%s","timestamp":[1],
                  "dataMessage":{"message":"hi","timestamp":{"x":1}}}}
                """.formatted(SOURCE_ACI))),
                "wrong-typed timestamp in both fields must drop the frame, not throw");

        // Fractional timestamp — longValueExact would have thrown
        // ArithmeticException.
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(parse("""
                {"envelope":{"sourceUuid":"%s","timestamp":17.5,
                  "dataMessage":{"message":"hi","timestamp":17.5}}}
                """.formatted(SOURCE_ACI))),
                "fractional timestamp must drop the frame, not throw");
    }

    @Test
    void timestampFallsBackToDataMessageWhenEnvelopeTimestampUnusable() {
        // Positive control for the guard: an unusable envelope timestamp
        // with a usable dataMessage timestamp still delivers.
        SignalMessageCodec.ReceivedDm dm = assertInstanceOf(
                SignalMessageCodec.DmMessage.class, codec.extractDm(parse("""
                {"envelope":{"sourceUuid":"%s","timestamp":"soon",
                  "dataMessage":{"message":"hi","timestamp":1700000001000}}}
                """.formatted(SOURCE_ACI))),
                "usable dataMessage timestamp must rescue an unusable envelope timestamp")
                .received();
        assertEquals(1700000001000L, dm.timestamp());
    }

    @Test
    void wrongTypedEnvelopeShapesAreDroppedNotThrown() {
        // Wrong-typed envelope — the old getJsonObject("envelope") threw CCE.
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(parse("""
                {"envelope":"junk"}
                """)),
                "wrong-typed envelope must drop the frame, not throw");

        // Wrong-typed dataMessage.
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(parse("""
                {"envelope":{"sourceUuid":"%s","timestamp":1700000001000,
                  "dataMessage":5}}
                """.formatted(SOURCE_ACI))),
                "wrong-typed dataMessage must drop the frame, not throw");

        // Wrong-typed sourceUuid — the default-value getString variant
        // must swallow the shape mismatch and report absence.
        assertInstanceOf(SignalMessageCodec.NotDm.class, codec.extractDm(parse("""
                {"envelope":{"sourceUuid":5,"timestamp":1700000001000,
                  "dataMessage":{"message":"hi","timestamp":1700000001000}}}
                """)),
                "wrong-typed sourceUuid must drop the frame, not throw");
    }

    @Test
    void wrongTypedParamsAndErrorMembersDecodeWithoutThrowing() {
        // Wrong-typed params — the old getJsonObject("params") threw CCE
        // straight through handleLine's IllegalArgumentException-only catch.
        SignalMessageCodec.JsonRpcMessage.Notification notification = assertInstanceOf(
                SignalMessageCodec.JsonRpcMessage.Notification.class,
                codec.decode("""
                        {"jsonrpc":"2.0","method":"receive","params":"junk"}
                        """));
        assertTrue(notification.params().isEmpty(),
                "wrong-typed params must normalize to empty params, not throw");

        // Wrong-typed error member — falls through to the Response branch
        // so the caller's pending future fails fast instead of timing out.
        assertInstanceOf(
                SignalMessageCodec.JsonRpcMessage.Response.class,
                codec.decode("""
                        {"jsonrpc":"2.0","id":"7","error":"junk"}
                        """));
    }

    @Test
    void decodeExceptionMessagesCarryNoFrameContent() {
        // Non-JSON line: the message must be fixed text, and the cause
        // chain must not smuggle the frame either (the JSON parser's own
        // exception message embeds the offending token).
        IllegalArgumentException notJson = assertThrows(IllegalArgumentException.class,
                () -> codec.decode("totally not json {{{ " + SENTINEL));
        assertNoFrameContent(notJson);

        // Well-formed JSON that fits no JSON-RPC shape (neither method
        // nor id).
        IllegalArgumentException noMethodNoId = assertThrows(IllegalArgumentException.class,
                () -> codec.decode("{\"junk\":\"" + SENTINEL + "\"}"));
        assertNoFrameContent(noMethodNoId);
    }

    private static void assertNoFrameContent(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains(SENTINEL),
                    "exception message must not carry frame content: " + t.getMessage());
        }
    }

}
