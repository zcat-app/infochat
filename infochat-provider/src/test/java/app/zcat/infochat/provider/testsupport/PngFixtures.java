package app.zcat.infochat.provider.testsupport;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Minimal well-formed PNGs for tests that need a real IHDR: signature +
 * IHDR(w,h) + a tiny IDAT + IEND with proper CRCs — the strip never
 * inflates IDAT. */
public final class PngFixtures {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private PngFixtures() {
    }

    /** An ImageIO-encoded RGBA PNG — actually decodable, unlike
     * {@link #minimalPng} (whose IDAT exists only for chunk-level shape). */
    public static byte[] realPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.MAGENTA);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.GREEN);
            graphics.drawLine(0, 0, width - 1, height - 1);
            graphics.setColor(Color.BLUE);
            graphics.drawOval(0, 0, width - 1, height - 1);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("fixture PNG encode failed", e);
        }
        return out.toByteArray();
    }

    /** {@code png} with a prompt-carrying tEXt chunk inserted after IHDR —
     * the workflow-metadata shape the strip drops (D75). */
    public static byte[] withPromptChunk(byte[] png, String prompt) {
        byte[] textChunk = chunk("tEXt",
                ("prompt\0" + prompt).getBytes(StandardCharsets.ISO_8859_1));
        byte[] out = new byte[png.length + textChunk.length];
        System.arraycopy(png, 0, out, 0, 33);
        System.arraycopy(textChunk, 0, out, 33, textChunk.length);
        System.arraycopy(png, 33, out, 33 + textChunk.length, png.length - 33);
        return out;
    }

    public static byte[] minimalPng(int width, int height) {
        byte[] ihdr = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)   // bit depth
                .put((byte) 6)   // color type RGBA
                .put((byte) 0)   // compression
                .put((byte) 0)   // filter
                .put((byte) 0)   // interlace
                .array();
        byte[] idat = {0x78, 0x01, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF};
        byte[] out = new byte[8 + 12 + ihdr.length + 12 + idat.length + 12];
        System.arraycopy(SIGNATURE, 0, out, 0, 8);
        byte[] ihdrChunk = chunk("IHDR", ihdr);
        System.arraycopy(ihdrChunk, 0, out, 8, ihdrChunk.length);
        int offset = 8 + ihdrChunk.length;
        byte[] idatChunk = chunk("IDAT", idat);
        System.arraycopy(idatChunk, 0, out, offset, idatChunk.length);
        byte[] iendChunk = chunk("IEND", new byte[0]);
        System.arraycopy(iendChunk, 0, out, offset + idatChunk.length, iendChunk.length);
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
}
