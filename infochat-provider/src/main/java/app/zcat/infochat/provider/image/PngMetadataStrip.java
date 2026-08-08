package app.zcat.infochat.provider.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** The D75 metadata strip for endpoint-chosen PNG bytes (commands.md
 * §Content, security.md §Trust boundaries item 9): drops text chunks,
 * copies the rest verbatim, refuses malformed input and oversize IHDRs. */
public final class PngMetadataStrip {

    /** The 8-byte PNG signature ({@code 89 50 4E 47 0D 0A 1A 0A}). */
    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** Text chunks carry the workflow metadata; dropped at chunk level. */
    private static final String TEXT = "tEXt";
    private static final String COMPRESSED_TEXT = "zTXt";
    private static final String INTERNATIONAL_TEXT = "iTXt";
    private static final String IHDR = "IHDR";

    private PngMetadataStrip() {
    }

    /** Drop the text chunks of a PNG; the pixel bound reads IHDR only
     * ({@code commands.md} §Content) — IDAT is never inflated. */
    public static byte[] strip(byte[] png, long maxPixels) {
        if (png.length < SIGNATURE.length) {
            throw new InvalidPngException("input shorter than the PNG signature");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                throw new InvalidPngException("input does not carry the PNG signature");
            }
        }
        return walk(png, maxPixels);
    }

    private static byte[] walk(byte[] png, long maxPixels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length);
        out.writeBytes(SIGNATURE);
        int offset = SIGNATURE.length;
        boolean sawFirstChunk = false;
        while (offset < png.length) {
            if (offset + 12 > png.length) {
                throw new InvalidPngException("truncated chunk header at offset " + offset);
            }
            int length = readInt(png, offset);
            // Subtraction form: `offset + 12 + length` wraps int-negative for
            // a hostile length near Integer.MAX_VALUE and skips the refusal
            // (round-1 review finding 1); both subtrahends are < png.length.
            if (length < 0 || length > png.length - offset - 12) {
                throw new InvalidPngException("chunk at offset " + offset
                        + " overruns the input (length=" + length + ")");
            }
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            if (!sawFirstChunk) {
                sawFirstChunk = true;
                if (!IHDR.equals(type)) {
                    throw new InvalidPngException("first chunk is not IHDR (found " + type + ")");
                }
                if (length < 8) {
                    throw new InvalidPngException("IHDR is shorter than its dimensions");
                }
                long width = readInt(png, offset + 8) & 0xFFFFFFFFL;
                long height = readInt(png, offset + 12) & 0xFFFFFFFFL;
                long pixels = width * height;
                if (pixels > maxPixels) {
                    throw new InvalidPngException("IHDR dimensions " + width + "x" + height
                            + " (" + pixels + " pixels) exceed the bound " + maxPixels);
                }
                out.write(png, offset, 12 + length);
                offset += 12 + length;
                continue;
            }
            if (TEXT.equals(type) || COMPRESSED_TEXT.equals(type) || INTERNATIONAL_TEXT.equals(type)) {
                offset += 12 + length;
                continue;
            }
            out.write(png, offset, 12 + length);
            offset += 12 + length;
        }
        return out.toByteArray();
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    /** Input was not a well-formed PNG (signature, structure, IHDR
     * dimensions); raised before any strip output is produced. */
    public static final class InvalidPngException extends RuntimeException {
        public InvalidPngException(String message) {
            super(message);
        }
    }
}
