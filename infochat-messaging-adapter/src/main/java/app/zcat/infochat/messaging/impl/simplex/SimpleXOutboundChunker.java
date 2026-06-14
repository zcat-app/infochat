package app.zcat.infochat.messaging.impl.simplex;

import app.zcat.infochat.messaging.Utf8;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an over-cap outbound text into ordered chunks that each fit the
 * SimpleX outbound byte cap
 * ({@link SimpleXMessageCodec#MAX_OUTBOUND_TEXT_BYTES}, UTF-8 bytes), per
 * design §6.3.4. Pure functions; no I/O, no state.
 *
 * <p>Split contract (design §6.3.4 "Outbound chunking"):</p>
 * <ul>
 *   <li>Greedy and line-based: whole lines (with their trailing newline)
 *       are packed while the chunk fits the cap; a single line that cannot
 *       fit a chunk on its own is hard-split at code-point boundaries.</li>
 *   <li>A split never cuts a UTF-8 multi-byte sequence or a UTF-16
 *       surrogate pair — every chunk is well-formed text on its own.</li>
 *   <li>A cut inside a triple-backtick code block closes the fence at the
 *       end of the chunk and reopens it as the first line of the next, so
 *       every chunk renders with balanced fences.</li>
 * </ul>
 */
final class SimpleXOutboundChunker {

    private static final int MAX_BYTES = SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES;
    private static final String FENCE = "```";
    private static final String FENCE_REOPEN = FENCE + "\n";
    /**
     * Worst-case bytes a cut adds to close an open fence: a newline (when
     * the chunk does not already end with one — the hard-split case) plus
     * the three fence backticks. Charged as a reserve while accepting text
     * so the close can never push a chunk past the cap.
     */
    private static final int FENCE_CLOSE_RESERVE = 4;

    private SimpleXOutboundChunker() {
        // Pure-static utility.
    }

    /**
     * Split {@code text} into ordered chunks each within
     * {@link SimpleXMessageCodec#MAX_OUTBOUND_TEXT_BYTES} UTF-8 bytes.
     * Text already within the cap passes through as a single chunk,
     * unchanged. Never returns an empty list.
     */
    static List<String> chunk(String text) {
        // Allocation-free early-exit boolean fits-check via the module's
        // single UTF-8 length source; the per-line utf8Length() below still
        // measures exact lengths for packing (reshaping that is out of scope).
        if (!Utf8.exceedsByteLength(text, MAX_BYTES)) {
            return List.of(text);
        }
        ChunkBuilder builder = new ChunkBuilder();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? text.length() : newline + 1;
            String line = text.substring(lineStart, lineEnd);
            lineStart = lineEnd;
            // A fence-marker line toggles the code-block state; the state
            // AFTER the line decides whether a cut right behind it must
            // close-and-reopen, hence the reserve.
            boolean fenceAfterLine = builder.fenceOpen ^ line.startsWith(FENCE);
            int lineBytes = utf8Length(line);
            int reserve = fenceAfterLine ? FENCE_CLOSE_RESERVE : 0;
            if (builder.currentBytes + lineBytes + reserve > MAX_BYTES) {
                if (builder.currentBytes > 0) {
                    builder.cut();
                }
                if (builder.currentBytes + lineBytes + reserve > MAX_BYTES) {
                    hardSplitLine(builder, line, fenceAfterLine);
                    continue;
                }
            }
            builder.append(line, lineBytes, fenceAfterLine);
        }
        builder.flushLast();
        return List.copyOf(builder.chunks);
    }

    /**
     * Pack one line that cannot fit a chunk on its own, cutting at
     * code-point boundaries. {@link String#codePointAt} plus
     * {@link Character#charCount} walk surrogate pairs as one unit, so a
     * cut can never strand half of one. The fence toggle of a marker line
     * applies once the whole line is consumed; mid-line pieces carry the
     * state from before the line (only the byte caps matter mid-line —
     * a fence-marker line is never long enough to be hard-split in
     * non-degenerate input).
     */
    private static void hardSplitLine(ChunkBuilder builder, String line, boolean fenceAfterLine) {
        int i = 0;
        while (i < line.length()) {
            int codePoint = line.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int codePointBytes = utf8Size(codePoint);
            int reserve = builder.fenceOpen ? FENCE_CLOSE_RESERVE : 0;
            if (builder.currentBytes + codePointBytes + reserve > MAX_BYTES) {
                builder.cut();
            }
            builder.current.append(line, i, i + charCount);
            builder.currentBytes += codePointBytes;
            i += charCount;
        }
        builder.fenceOpen = fenceAfterLine;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** UTF-8 encoded size of one code point. */
    private static int utf8Size(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /**
     * Accumulator for the chunk under construction. {@code fenceOpen} is
     * the code-block state at the current end of {@code current}; it is
     * deliberately NOT reset by {@link #cut} — the close-and-reopen
     * carries the logical state across the chunk boundary.
     */
    private static final class ChunkBuilder {
        final List<String> chunks = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int currentBytes;
        boolean fenceOpen;

        /**
         * End the current chunk at a cut point and start the next one,
         * closing an open fence before the boundary and reopening it
         * after. Callers guarantee the chunk is non-empty at every cut.
         */
        void cut() {
            if (fenceOpen) {
                if (current.charAt(current.length() - 1) != '\n') {
                    current.append('\n');
                }
                current.append(FENCE);
            }
            chunks.add(current.toString());
            current.setLength(0);
            currentBytes = 0;
            if (fenceOpen) {
                current.append(FENCE_REOPEN);
                currentBytes = utf8Length(FENCE_REOPEN);
            }
        }

        void append(String line, int lineBytes, boolean fenceAfterLine) {
            current.append(line);
            currentBytes += lineBytes;
            fenceOpen = fenceAfterLine;
        }

        /**
         * Add the trailing chunk; the end of the text never needs a fence
         * close. Always non-empty: a cut only ever fires with more text
         * pending, and that text is appended in the same loop iteration.
         */
        void flushLast() {
            chunks.add(current.toString());
        }
    }
}
