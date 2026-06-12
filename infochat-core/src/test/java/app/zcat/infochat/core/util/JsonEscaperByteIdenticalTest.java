package app.zcat.infochat.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins U-61: the nibble-emission C0 escaper produces byte-identical output to
 * the {@code String.format("\\u%04x", ...)} implementation it replaced, across
 * all 32 C0 control characters plus the named/standard escapes. The reference
 * oracle is {@link String#format} itself, so this asserts byte-identity against
 * the prior implementation directly rather than against a transcribed expected
 * table that could drift from what {@code %04x} actually emitted.
 */
class JsonEscaperByteIdenticalTest {

    @Test
    void allC0ControlsEscapeByteIdenticallyToTheFormatterReference() {
        for (int c = 0; c < 0x20; c++) {
            // The named shortcuts are emitted by the switch ahead of the C0
            // branch, exactly as the pre-change implementation did; the oracle
            // mirrors that so the sweep covers all 32 controls including 0x09,
            // 0x0a, 0x0d.
            String expected = switch (c) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> String.format("\\u%04x", c);
            };
            assertEquals(expected, JsonEscaper.escape(String.valueOf((char) c)),
                "C0 control 0x" + Integer.toHexString(c)
                    + " must escape byte-identically to the Formatter implementation");
        }
    }

    @Test
    void standardEscapesAreUnchanged() {
        assertEquals("\\\\", JsonEscaper.escape("\\"));
        assertEquals("\\\"", JsonEscaper.escape("\""));
        assertEquals("\\n", JsonEscaper.escape("\n"));
        assertEquals("\\r", JsonEscaper.escape("\r"));
        assertEquals("\\t", JsonEscaper.escape("\t"));
    }

    @Test
    void mixedContentEscapesEachControlByteIdentically() {
        String input = "title" + (char) 0x01 + "body" + (char) 0x1f + "\"end\"";
        String expected = "title\\u0001body\\u001f\\\"end\\\"";
        assertEquals(expected, JsonEscaper.escape(input));
    }
}
