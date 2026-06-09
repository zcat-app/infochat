package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the full control-character range of {@link SafeLog#stripControls}:
 * C0 (0x00-0x1F), DEL (0x7F), and C1 (0x80-0x9F, including the
 * single-byte CSI 0x9B) all map to a single space; printable text is
 * untouched.
 */
class SafeLogStripControlsTest {

    private static final char DEL = (char) 0x7F;
    private static final char C1_START = (char) 0x80;
    private static final char NEL = (char) 0x85;
    private static final char CSI = (char) 0x9B;
    private static final char C1_END = (char) 0x9F;
    private static final char ESC = (char) 0x1B;
    private static final char NUL = (char) 0x00;
    private static final char NBSP = (char) 0xA0;

    @Test
    void stripsDelAndC1ControlsIncludingSingleByteCsi() {
        String input = "a" + DEL + "b" + C1_START + "c" + NEL + "d" + CSI + "e" + C1_END + "f";
        assertEquals("a b c d e f", SafeLog.stripControls(input),
                "DEL and every C1 control (incl. 0x9B CSI) must become a space");
    }

    @Test
    void stripsC0ControlsIncludingEscCrLf() {
        String input = "x" + NUL + "y" + ESC + "[31mz\r\nw";
        assertEquals("x y [31mz  w", SafeLog.stripControls(input),
                "C0 controls (NUL, ESC, CR, LF) must become spaces");
    }

    @Test
    void printableTextAndNonControlUnicodePassThrough() {
        String input = "plain text, " + NBSP + "unicode: üé ~!@#";
        assertEquals(input, SafeLog.stripControls(input),
                "printable ASCII and non-control text (incl. NBSP) must be untouched");
    }
}
