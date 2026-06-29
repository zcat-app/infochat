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
        // The guard rejects the full control range — C0 (<0x20), DEL (0x7f),
        // and C1 (0x80-0x9f) — so it matches the method's "no raw control
        // byte" claim, not only C0. DEL and C1 are valid raw in JSON
        // (RFC 8259), so JsonEscaper passes them through and they are not fed
        // here; the guard still flags any control byte that escaping should
        // have removed from this C0 input.
        for (int i = 0; i < escaped.length(); i++) {
            char ch = escaped.charAt(i);
            assertTrue(ch >= 0x20 && ch != 0x7f && (ch < 0x80 || ch > 0x9f),
                "escaped output must not contain a raw control byte (C0/DEL/C1) at index " + i);
        }
    }
}
