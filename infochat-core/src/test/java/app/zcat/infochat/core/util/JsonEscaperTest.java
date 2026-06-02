package app.zcat.infochat.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JSON-string-content escaping contract, with emphasis on the
 * C0 control-character handling that the hand-rolled escapers this class
 * replaces got wrong (they emitted bare-control bytes raw, producing
 * invalid JSON). Control-char inputs are built with {@code (char)} casts
 * so the source stays pure ASCII rather than embedding raw control bytes.
 */
class JsonEscaperTest {

    @Test
    void plainTextPassesThroughUnchanged() {
        assertEquals("hello world", JsonEscaper.escape("hello world"));
    }

    @Test
    void backslashAndQuoteAreEscaped() {
        assertEquals("a\\\\b\\\"c", JsonEscaper.escape("a\\b\"c"));
    }

    @Test
    void namedShortcutsUseTheShortForm() {
        assertEquals("\\n\\r\\t", JsonEscaper.escape("\n\r\t"));
    }

    /**
     * Acceptance item 4: C0 controls without a named shortcut
     * (backspace 0x08, form-feed 0x0c, vertical tab 0x0b) must be
     * {@code \\u}-escaped, never emitted raw.
     */
    @Test
    void c0ControlsWithoutNamedShortcutAreUnicodeEscaped() {
        assertEquals("\\u0008", JsonEscaper.escape(String.valueOf((char) 0x08)));
        assertEquals("\\u000c", JsonEscaper.escape(String.valueOf((char) 0x0c)));
        assertEquals("\\u000b", JsonEscaper.escape(String.valueOf((char) 0x0b)));
    }

    @Test
    void otherC0ControlsAreUnicodeEscaped() {
        assertEquals("\\u0000", JsonEscaper.escape(String.valueOf((char) 0x00)));
        assertEquals("\\u001f", JsonEscaper.escape(String.valueOf((char) 0x1f)));
    }

    @Test
    void escapedOutputContainsNoRawControlByte() {
        String escaped = JsonEscaper.escape("title" + (char) 0x08 + "with" + (char) 0x0c + "controls");
        for (int i = 0; i < escaped.length(); i++) {
            assertTrue(escaped.charAt(i) >= 0x20,
                "escaped output must not contain a raw C0 byte at index " + i);
        }
    }
}
