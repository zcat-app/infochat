package app.zcat.infochat.collector.stream.nostr;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that {@link NostrMessage.MalformedFrameException} messages never
 * embed unstripped relay bytes: every throw site that summarizes the
 * frame (or the filter spec, or the verb) control-strips C0, DEL, and
 * C1 first, so the read loop's WARN log cannot be used for newline or
 * ANSI log forging by an untrusted relay.
 */
class NostrMalformedFrameHygieneTest {

    private static final char ESC = (char) 0x1B;
    private static final char CSI = (char) 0x9B;
    private static final char DEL = (char) 0x7F;

    private static void assertNoControls(String message) {
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            assertFalse(c < 0x20 || (c >= 0x7F && c <= 0x9F),
                    "exception message must not contain control char 0x"
                            + Integer.toHexString(c) + ": " + message);
        }
    }

    @Test
    void invalidJsonFrameWithAnsiSequencesYieldsStrippedMessage() {
        String frame = "not-json" + ESC + "[2J" + CSI + "31m\r\nFORGED LINE" + DEL;

        var thrown = assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse(frame));

        assertNoControls(thrown.getMessage());
        assertTrue(thrown.getMessage().contains("frame is not valid JSON"),
                "the fixed reason text must survive");
    }

    @Test
    void nonArrayFrameWithControlCharsYieldsStrippedMessage() {
        // Raw CR/LF are legal JSON inter-token whitespace, so this frame
        // parses (a bare string), fails the array-shape check, and the
        // "not a non-empty JSON array" site summarizes the raw frame —
        // which carries the line-forging controls.
        String frame = "\r\n\"abc\"\r\nFORGED LINE";

        var thrown = assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse(frame));

        assertNoControls(thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not a non-empty JSON array"),
                "the fixed reason text must survive");
    }

    @Test
    void missingTextElementFrameYieldsStrippedMessage() {
        // Element 1 is an object, not text — the requireText site fires
        // and summarizes the raw frame, which carries CR/LF whitespace.
        String frame = "[\"EOSE\",\r\n{\"x\":1}]\r\nFORGED LINE";

        var thrown = assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse(frame));

        assertNoControls(thrown.getMessage());
        assertTrue(thrown.getMessage().contains("missing or not text"),
                "the fixed reason text must survive");
    }

    @Test
    void unknownVerbWithControlCharsYieldsStrippedMessage() {
        // The ESC arrives JSON-escaped, so the frame parses and the
        // PARSED verb carries a real ESC into the unknown-verb site.
        String frame = "[\"EV\\u001b[31mIL\"]";

        var thrown = assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse(frame));

        assertNoControls(thrown.getMessage());
        assertTrue(thrown.getMessage().contains("unknown NIP-01 verb"),
                "the fixed reason text must survive");
    }

    @Test
    void invalidFilterSpecWithControlCharsYieldsStrippedMessage() {
        String filterSpec = "not-json" + CSI + "31m\nFORGED";

        var thrown = assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.serializeReq("sub-1", filterSpec, OptionalLong.empty()));

        assertNoControls(thrown.getMessage());
    }

    @Test
    void longPoisonedFrameIsStrippedAndCapped() {
        String frame = ("x" + ESC).repeat(200); // 400 chars, alternating controls

        var thrown = assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse(frame));

        assertNoControls(thrown.getMessage());
        assertTrue(thrown.getMessage().length() < 200,
                "the summary must stay capped after stripping");
    }
}
