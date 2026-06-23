package app.zcat.infochat.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the M1-433 ingest text strip. Test strings embed the
 * obfuscation codepoints via {@code (char) 0xNNNN} so the source stays
 * free of invisible bidi/zero-width characters.
 *
 * <ul>
 *   <li>{@link IngestTextNormalizer#stripMetadataField} removes
 *       bidi-control, zero-width AND control characters (the title/url
 *       field strip) and leaves ordinary text byte-identical.</li>
 *   <li>{@link IngestTextNormalizer#stripBidiAndZeroWidth} removes
 *       bidi/zero-width but deliberately PRESERVES control characters —
 *       this is the contract Stage 1's body path relies on to stay
 *       byte-identical (the body legitimately carries newlines).</li>
 * </ul>
 */
class IngestTextNormalizerTest {

    private static final char BIDI_OVERRIDE = (char) 0x202E;   // RIGHT-TO-LEFT OVERRIDE
    private static final char ZERO_WIDTH = (char) 0x200B;      // ZERO WIDTH SPACE
    private static final char CONTROL_BEL = (char) 0x0007;     // BELL (C0 control)
    private static final char NEWLINE = (char) 0x000A;         // LINE FEED (control)
    private static final char LINE_SEP = (char) 0x2028;        // LINE SEPARATOR (Zl)
    private static final char PARA_SEP = (char) 0x2029;        // PARAGRAPH SEPARATOR (Zp)

    @Test
    void stripMetadataFieldRemovesBidiZeroWidthAndControlCharacters() {
        String input = "before" + BIDI_OVERRIDE + "mid" + ZERO_WIDTH + "after" + CONTROL_BEL + "end";
        assertEquals("beforemidafterend", IngestTextNormalizer.stripMetadataField(input),
            "bidi override, zero-width, and control character must all be stripped");
    }

    @Test
    void stripMetadataFieldRemovesEmbeddedNewline() {
        // A newline embedded in a single-line url/title would inject an
        // apparent extra line into a bare-emitted bot reply.
        String input = "https://example.test/a" + NEWLINE + "b";
        assertEquals("https://example.test/ab",
            IngestTextNormalizer.stripMetadataField(input));
    }

    @Test
    void stripMetadataFieldRemovesLineAndParagraphSeparators() {
        // U+2028/U+2029 are UAX #14 mandatory line breaks but are not ISO
        // control characters, so they escaped the prior strip and could
        // inject an apparent extra line into a bare-emitted title.
        String input = "before" + LINE_SEP + "mid" + PARA_SEP + "after";
        assertEquals("beforemidafter", IngestTextNormalizer.stripMetadataField(input),
            "U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR must be stripped");
    }

    @Test
    void stripMetadataFieldLeavesOrdinaryTextUnchanged() {
        String ordinary = "Ordinary title — with em-dash, digits 123 and /path?q=1";
        assertEquals(ordinary, IngestTextNormalizer.stripMetadataField(ordinary),
            "ordinary text must pass through byte-identical");
    }

    @Test
    void stripBidiAndZeroWidthRemovesBidiAndZeroWidthButPreservesControlCharacters() {
        String input = "a" + BIDI_OVERRIDE + "b" + ZERO_WIDTH + "c" + CONTROL_BEL + NEWLINE + "d";
        // Control characters (BEL, newline) survive: the body path
        // relies on this to stay byte-identical.
        assertEquals("abc" + CONTROL_BEL + NEWLINE + "d",
            IngestTextNormalizer.stripBidiAndZeroWidth(input),
            "bidi/zero-width stripped; control characters preserved");
    }

    @Test
    void stripBidiAndZeroWidthPreservesLineAndParagraphSeparators() {
        // The body path reuses this method and legitimately spans lines;
        // U+2028/U+2029 must survive here. Pins the body-path contract so
        // a future edit cannot silently extend the separator strip to the
        // body. (M1-435)
        String input = "a" + LINE_SEP + "b" + PARA_SEP + "c";
        assertEquals("a" + LINE_SEP + "b" + PARA_SEP + "c",
            IngestTextNormalizer.stripBidiAndZeroWidth(input),
            "line/paragraph separators must be preserved on the body path");
    }

    @Test
    void stripBidiAndZeroWidthLeavesOrdinaryTextUnchanged() {
        String ordinary = "Plain body text with a newline" + NEWLINE + "and a tab\tinside.";
        assertEquals(ordinary, IngestTextNormalizer.stripBidiAndZeroWidth(ordinary));
    }
}
