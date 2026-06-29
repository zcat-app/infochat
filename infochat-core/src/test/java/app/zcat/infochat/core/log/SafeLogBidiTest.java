package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the widening of {@link SafeLog#stripControls} to the
 * non-ISO-control codepoints that still break one-line log integrity:
 * the bidi override U+202E and the line / paragraph separators
 * U+2028 / U+2029 (M1-491), plus the rest of the Unicode bidi-control
 * set — U+061C, U+200E / U+200F, U+202A–U+202D and the directional
 * isolates U+2066–U+2069 (M1-521). Each must be replaced with a single
 * space while the surrounding printable text is left intact. These all
 * survive the C0/C1 sweep (they are not ISO controls), so they need
 * their own pins separate from {@link SafeLogStripControlsTest}.
 */
class SafeLogBidiTest {

    private static final char RLO = '\u202E';      // RIGHT-TO-LEFT OVERRIDE
    private static final char LINE_SEP = '\u2028';  // LINE SEPARATOR
    private static final char PARA_SEP = '\u2029';  // PARAGRAPH SEPARATOR

    // M1-521: the rest of the bidi-control set SafeLog.stripControls did not
    // cover before this ticket (U+202E / RLO is already pinned above). This is
    // the same set IngestTextNormalizer.stripBidiAndZeroWidth strips on the
    // ingest path; codepoints written as escapes to keep the source ASCII.
    private static final char ALM = '\u061c';   // U+061C ARABIC LETTER MARK
    private static final char LRM = '\u200e';   // U+200E LEFT-TO-RIGHT MARK
    private static final char RLM = '\u200f';   // U+200F RIGHT-TO-LEFT MARK
    private static final char LRE = '\u202a';   // U+202A LEFT-TO-RIGHT EMBEDDING
    private static final char RLE = '\u202b';   // U+202B RIGHT-TO-LEFT EMBEDDING
    private static final char PDF = '\u202c';   // U+202C POP DIRECTIONAL FORMATTING
    private static final char LRO = '\u202d';   // U+202D LEFT-TO-RIGHT OVERRIDE
    private static final char LRI = '\u2066';   // U+2066 LEFT-TO-RIGHT ISOLATE
    private static final char RLI = '\u2067';   // U+2067 RIGHT-TO-LEFT ISOLATE
    private static final char FSI = '\u2068';   // U+2068 FIRST STRONG ISOLATE
    private static final char PDI = '\u2069';   // U+2069 POP DIRECTIONAL ISOLATE

    @Test
    void neutralizesBidiOverrideAndLineAndParagraphSeparators() {
        String input = "a" + RLO + "b" + LINE_SEP + "c" + PARA_SEP + "d";
        assertEquals("a b c d", SafeLog.stripControls(input),
                "U+202E, U+2028 and U+2029 must each become a single space");
    }

    @Test
    void neutralizedOutputRetainsNoneOfTheNeutralizedCodepoints() {
        String input = "danger" + RLO + LINE_SEP + PARA_SEP + "end";
        String stripped = SafeLog.stripControls(input);
        assertFalse(stripped.indexOf(RLO) >= 0, "U+202E must not survive: " + stripped);
        assertFalse(stripped.indexOf(LINE_SEP) >= 0, "U+2028 must not survive: " + stripped);
        assertFalse(stripped.indexOf(PARA_SEP) >= 0, "U+2029 must not survive: " + stripped);
    }

    @Test
    void neutralizesEachNewlyCoveredBidiControlToASpace() {
        char[] bidiControls = {ALM, LRM, RLM, LRE, RLE, PDF, LRO, LRI, RLI, FSI, PDI};
        for (char bidi : bidiControls) {
            String input = "x" + bidi + "y";
            assertEquals("x y", SafeLog.stripControls(input),
                    "bidi control U+" + String.format("%04X", (int) bidi)
                            + " must each become a single space");
        }
    }

    @Test
    void leavesPrintableTextByteIdenticalAcrossTheBidiControlSet() {
        String printable = "Plain ASCII text, punctuation !?, and digits 0123.";
        assertEquals(printable, SafeLog.stripControls(printable),
                "printable text must pass through stripControls unchanged");
    }
}
