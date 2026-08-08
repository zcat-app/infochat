package app.zcat.infochat.provider.image;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PngMetadataStrip tests — the D75 control removing the workflow
 * metadata (containing the prompt) ComfyUI embeds in PNG text chunks.
 * The builder mirrors ComfyUI's shape with proper CRCs. */
class PngMetadataStripTest {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** The canary prompt string: must be absent from the strip's output. */
    private static final String CANARY = "canary-prompt-string-84f3";

    @Test
    void stripsPromptCarryingTextChunks() {
        // REPRODUCTION (M1-801): the behavior did not exist — no binary
        // sanitizer anywhere — so this test could not compile before the
        // strip. The PNG carries the prompt in tEXt and iTXt chunks.
        byte[] png = png(64, 64,
                chunk("tEXt", ("Comment\0" + CANARY).getBytes(StandardCharsets.UTF_8)),
                chunk("iTXt", ("Description\0\0\0\0\0" + CANARY).getBytes(StandardCharsets.UTF_8)),
                idatChunk(),
                chunk("IEND", new byte[0]));

        byte[] stripped = PngMetadataStrip.strip(png, 64L * 64L);

        assertTrue(!containsSubsequence(stripped, CANARY.getBytes(StandardCharsets.UTF_8)),
                "the strip's output must contain no prompt substring");
        assertTrue(!containsSubsequence(stripped, "tEXt".getBytes(StandardCharsets.UTF_8)),
                "tEXt chunks are dropped at chunk level");
        assertTrue(!containsSubsequence(stripped, "iTXt".getBytes(StandardCharsets.UTF_8)),
                "iTXt chunks are dropped at chunk level");
        assertArrayEquals(SIGNATURE, java.util.Arrays.copyOf(stripped, 8),
                "the PNG signature is preserved");
        assertTrue(crcsAreValid(stripped),
                "surviving chunks are copied verbatim so their CRCs stay valid");
        assertTrue(containsChunkType(stripped, "IDAT"),
                "payload chunks survive the strip");
    }

    @Test
    void refusesOversizedDimensions() {
        // FAILURE-MODE (analysis P5, commands.md:635-636): an IHDR over the
        // pixel bound is rejected before any output — the bound reads IHDR
        // only, never inflating IDAT.
        byte[] png = png(100_000, 100_000, idatChunk(), chunk("IEND", new byte[0]));

        assertThrows(PngMetadataStrip.InvalidPngException.class,
                () -> PngMetadataStrip.strip(png, 64L * 64L));
    }

    @Test
    void refusesTruncatedOrNonPngInput() {
        // FAILURE-MODE: endpoint-chosen bytes (security.md §Trust boundaries
        // item 9) that are not a well-formed PNG are rejected, never passed
        // through — both a non-PNG body and a PNG cut mid-chunk.
        byte[] notPng = "this is not a png, just some bytes".getBytes(StandardCharsets.UTF_8);
        assertThrows(PngMetadataStrip.InvalidPngException.class,
                () -> PngMetadataStrip.strip(notPng, 64L * 64L),
                "a body without the PNG signature is rejected");

        byte[] valid = png(64, 64, idatChunk(), chunk("IEND", new byte[0]));
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 7);
        assertThrows(PngMetadataStrip.InvalidPngException.class,
                () -> PngMetadataStrip.strip(truncated, 64L * 64L),
                "a PNG cut mid-chunk is rejected");
    }

    @Test
    void refusesChunkDeclaringLengthNearIntMax() {
        // FAILURE-MODE (round-1 finding 1): a hostile length 0x7FFFFFFF
        // must hit the typed refusal, not an overflowed bounds check that
        // escapes as IndexOutOfBoundsException.
        byte[] png = new byte[46];
        System.arraycopy(SIGNATURE, 0, png, 0, SIGNATURE.length);
        byte[] ihdr = chunk("IHDR", ByteBuffer.allocate(13)
                .putInt(64).putInt(64)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0)
                .array());
        System.arraycopy(ihdr, 0, png, 8, ihdr.length);
        System.arraycopy(ByteBuffer.allocate(8)
                .putInt(0x7FFFFFFF)
                .put("tEXt".getBytes(StandardCharsets.US_ASCII))
                .array(), 0, png, 8 + ihdr.length, 8);

        assertThrows(PngMetadataStrip.InvalidPngException.class,
                () -> PngMetadataStrip.strip(png, 64L * 64L),
                "a chunk declaring length 0x7FFFFFFF is refused with the typed exception");
    }

    // --- PNG construction helpers ------------------------------------------

    /** Builds a minimal PNG: signature + IHDR + the given chunks. */
    private static byte[] png(int width, int height, byte[]... chunks) {
        byte[] ihdrData = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)   // bit depth
                .put((byte) 6)   // color type RGBA
                .put((byte) 0)   // compression
                .put((byte) 0)   // filter
                .put((byte) 0)   // interlace
                .array();
        byte[] out = new byte[8 + 12 + ihdrData.length + sum(chunks)];
        System.arraycopy(SIGNATURE, 0, out, 0, 8);
        byte[] ihdrChunk = chunk("IHDR", ihdrData);
        System.arraycopy(ihdrChunk, 0, out, 8, ihdrChunk.length);
        int offset = 8 + ihdrChunk.length;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, offset, c.length);
            offset += c.length;
        }
        return out;
    }

    /** One well-formed chunk: length(4) + type(4) + data + crc(4). */
    private static byte[] chunk(String type, byte[] data) {
        byte[] out = new byte[12 + data.length];
        ByteBuffer buffer = ByteBuffer.wrap(out);
        buffer.putInt(data.length);
        buffer.put(type.getBytes(StandardCharsets.US_ASCII));
        buffer.put(data);
        CRC32 crc = new CRC32();
        crc.update(type.getBytes(StandardCharsets.US_ASCII));
        crc.update(data);
        buffer.putInt((int) crc.getValue());
        return out;
    }

    /** A plausible IDAT payload: the strip must copy it verbatim, never inflate it. */
    private static byte[] idatChunk() {
        return chunk("IDAT", new byte[] {0x78, 0x01, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF});
    }

    private static int sum(byte[][] arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        return total;
    }

    private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Re-walks the chunk stream and verifies every chunk's CRC against its type+data. */
    private static boolean crcsAreValid(byte[] png) {
        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = readInt(png, offset);
            if (offset + 12 + length > png.length) {
                return false;
            }
            byte[] typeAndData = java.util.Arrays.copyOfRange(png, offset + 4, offset + 8 + length);
            CRC32 crc = new CRC32();
            crc.update(typeAndData);
            if ((int) crc.getValue() != readInt(png, offset + 8 + length)) {
                return false;
            }
            offset += 12 + length;
        }
        return offset == png.length;
    }

    private static boolean containsChunkType(byte[] png, String type) {
        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = readInt(png, offset);
            if (offset + 12 + length > png.length) {
                return false;
            }
            byte[] chunkType = java.util.Arrays.copyOfRange(png, offset + 4, offset + 8);
            if (type.equals(new String(chunkType, StandardCharsets.US_ASCII))) {
                return true;
            }
            offset += 12 + length;
        }
        return false;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
