package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the §6.3.4 outbound-chunking split contract: per-chunk byte cap,
 * code-block fence preservation across a cut, and code-point-safe hard
 * splits. The adapter-level delivery behaviour (over-cap send no longer
 * fails PERMANENT) is pinned separately by
 * {@link SimpleXAdapterChunkedSendTest}.
 */
class SimpleXOutboundChunkerTest {

    private static final int CAP = SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES;

    @Test
    void underCapTextPassesThroughAsSingleChunk() {
        assertEquals(List.of("short digest"), SimpleXOutboundChunker.chunk("short digest"));
        // Exactly at the cap is still a single, unchanged chunk.
        String atCap = "a".repeat(CAP);
        assertEquals(List.of(atCap), SimpleXOutboundChunker.chunk(atCap));
    }

    @Test
    void fenceSpanningChunkBoundaryIsClosedAndReopened() {
        // One code block big enough that the cut must land inside it.
        StringBuilder code = new StringBuilder();
        for (int i = 0; code.length() < CAP + 1_000; i++) {
            code.append("code line ").append(i).append(" with some padding text\n");
        }
        String text = "intro prose\n```\n" + code + "```\noutro prose\n";

        List<String> chunks = SimpleXOutboundChunker.chunk(text);

        assertTrue(chunks.size() >= 2, "over-cap text must split");
        for (String chunk : chunks) {
            assertTrue(utf8Length(chunk) <= CAP,
                    "every chunk must fit the cap; got " + utf8Length(chunk) + " bytes");
            assertEquals(0, fenceLineCount(chunk) % 2,
                    "every chunk must carry balanced fences:\n" + chunk);
        }
        // Reassembly: stripping the inserted close ("\n```" at a chunk end)
        // and reopen ("```\n" at the next chunk's start) restores the
        // original text — nothing was lost or reordered at the cut.
        StringBuilder reassembled = new StringBuilder(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String previousTail = "\n" + "```";
            assertTrue(reassembled.toString().endsWith(previousTail),
                    "a cut inside the block must close the fence at the chunk end");
            reassembled.setLength(reassembled.length() - "```".length());
            // The close shares the line break with the original text, so
            // only the backticks are stripped; the reopen line is whole.
            String chunk = chunks.get(i);
            assertTrue(chunk.startsWith("```\n"),
                    "the continuation chunk must reopen the fence first");
            reassembled.append(chunk, "```\n".length(), chunk.length());
        }
        assertEquals(text, reassembled.toString(),
                "reassembling the chunks minus inserted fences must restore the original");
    }

    @Test
    void multiByteRunNeverSplitsACodePoint() {
        // One unbroken line: a 1-byte prefix then 4-byte emoji (surrogate
        // pairs in UTF-16), so any byte-offset split at a multiple of the
        // cap would cut an emoji in half.
        String text = "x" + "😀".repeat(2 * CAP / 4);
        assertTrue(utf8Length(text) > CAP, "test input must exceed the cap");

        List<String> chunks = SimpleXOutboundChunker.chunk(text);

        assertTrue(chunks.size() >= 2, "over-cap text must split");
        StringBuilder reassembled = new StringBuilder();
        for (String chunk : chunks) {
            assertTrue(utf8Length(chunk) <= CAP,
                    "every chunk must fit the cap; got " + utf8Length(chunk) + " bytes");
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)),
                    "a chunk must not start with the low half of a surrogate pair");
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)),
                    "a chunk must not end with the high half of a surrogate pair");
            reassembled.append(chunk);
        }
        assertEquals(text, reassembled.toString(),
                "concatenated chunks must equal the original text exactly");
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int fenceLineCount(String chunk) {
        int count = 0;
        for (String line : chunk.split("\n", -1)) {
            if (line.startsWith("```")) {
                count++;
            }
        }
        return count;
    }
}
