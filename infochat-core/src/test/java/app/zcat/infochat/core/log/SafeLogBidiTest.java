package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the M1-491 widening of {@link SafeLog#stripControls} to the
 * non-ISO-control codepoints that still break one-line log integrity:
 * the bidi override U+202E and the line / paragraph separators
 * U+2028 / U+2029. Each must be replaced with a single space while the
 * surrounding printable text is left intact. These three survive the
 * C0/C1 sweep (they are not ISO controls), so they need their own pins
 * separate from {@link SafeLogStripControlsTest}.
 */
class SafeLogBidiTest {

    private static final char RLO = '\u202E';      // RIGHT-TO-LEFT OVERRIDE
    private static final char LINE_SEP = '\u2028';  // LINE SEPARATOR
    private static final char PARA_SEP = '\u2029';  // PARAGRAPH SEPARATOR

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
}
