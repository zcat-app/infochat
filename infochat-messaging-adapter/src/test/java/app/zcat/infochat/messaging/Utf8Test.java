package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins {@link Utf8} as the single source of UTF-8 byte-length arithmetic:
 * both {@code byteLength} and {@code exceedsByteLength} must agree with a
 * reference {@code getBytes(UTF_8).length} across the four encoding-width
 * classes (ASCII, 2-byte, 3-byte, 4-byte surrogate pair), and the
 * codec-cap boundary must decide identically to that reference — without
 * the reference's byte[] allocation.
 */
class Utf8Test {

    @ParameterizedTest
    @ValueSource(strings = {
        "",            // empty
        "hello",       // ASCII, 1 byte/char
        "é",           // U+00E9, 2 bytes
        "€",           // U+20AC, 3 bytes
        "𝄞",          // U+1D11E, 4 bytes (surrogate pair in Java)
        "héllo→𝄞",    // mixed widths in one string
    })
    void byteLengthMatchesReferenceAcrossEncodingWidths(String s) {
        int reference = s.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(reference, Utf8.byteLength(s),
                "Utf8.byteLength must equal getBytes(UTF_8).length");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "hello", "é", "€", "𝄞", "héllo→𝄞"})
    void exceedsByteLengthDecidesAtTheReferenceBoundary(String s) {
        int reference = s.getBytes(StandardCharsets.UTF_8).length;
        // At the exact byte length the body is within the cap; one below it exceeds.
        assertFalse(Utf8.exceedsByteLength(s, reference),
                "a body exactly at the limit does not exceed it");
        if (reference > 0) {
            assertTrue(Utf8.exceedsByteLength(s, reference - 1),
                    "a body one byte over the limit exceeds it");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "hello", "é", "€", "𝄞", "héllo→𝄞"})
    void exceedsByteLengthAgreesWithByteLength(String s) {
        int len = Utf8.byteLength(s);
        for (int limit = 0; limit <= len + 1; limit++) {
            assertEquals(len > limit, Utf8.exceedsByteLength(s, limit),
                    "exceedsByteLength must agree with byteLength > limit at limit=" + limit);
        }
    }

    @Test
    void earlyExitBoundsTheWalkOfAnOversizeBody() {
        // A body far over the codec cap (16384) still decides "exceeds"
        // via the running-count early exit, so the walk stops near the cap
        // rather than traversing the full attacker-chosen length.
        String oversize = "A".repeat(20_000);
        assertTrue(Utf8.exceedsByteLength(oversize, 16_384));
        assertFalse(Utf8.exceedsByteLength("A".repeat(16_384), 16_384));
    }
}
