package app.zcat.infochat.provider.image;

import app.zcat.infochat.provider.testsupport.PngFixtures;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Provider-side inline-preview generator (design 06-messaging.md
 * §6.2.4): bounded decode on post-strip bytes, the recorded form and
 * ceiling, and null-degrade on every failure. */
class ImagePreviewGeneratorTest {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    /** REPRODUCTION (M1-842): the preview is generated from the STRIPPED
     * bytes, carries the recorded form, and stays within the recorded char
     * ceiling. */
    @Test
    void generatesABoundedPreviewFromStrippedPng() throws Exception {
        long shippedCharCeiling = readShippedPreviewCharCeiling();
        assertEquals(14_822L, shippedCharCeiling,
                "the shipped ceiling is the 06-messaging.md §6.2.4 recorded accept boundary"
                        + " (14,822 accepted / 16,500 refused), never an invented value");
        byte[] original = PngFixtures.withPromptChunk(
                PngFixtures.realPng(400, 200), "a canary prompt");
        byte[] stripped = PngMetadataStrip.strip(original, 2_000_000L);
        ImagePreviewGenerator generator =
                new ImagePreviewGenerator(65_536L, (int) shippedCharCeiling);

        String preview = generator.generate(stripped, 2_000_000L);

        assertNotNull(preview, "a well-formed stripped PNG generates a preview");
        assertTrue(preview.startsWith(DATA_URI_PREFIX),
                "the recorded form is a PNG data URI");
        assertTrue(preview.length() <= shippedCharCeiling,
                "the preview stays within the recorded char ceiling");
        byte[] decoded = Base64.getMimeDecoder()
                .decode(preview.substring(DATA_URI_PREFIX.length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertNotNull(image, "the preview payload is itself a decodable PNG");
        assertTrue((long) image.getWidth() * image.getHeight() <= 65_536L,
                "the preview raster stays within the configured dimension budget");
    }

    /** FAILURE-MODE (security.md §Trust boundaries item 9): endpoint-chosen
     * bytes degrade to null on every hostile shape — no unbounded decode,
     * no over-limit emit, no escaping exception. */
    @Test
    void refusesOrDegradesHostileInput() throws Exception {
        ImagePreviewGenerator generator = new ImagePreviewGenerator(65_536L, 14_822);

        assertNull(generator.generate("not a png at all".getBytes(StandardCharsets.UTF_8),
                        2_000_000L),
                "bytes that fail strip validation degrade to null");
        assertNull(generator.generate(pngWithGarbageIdat(10, 10), 2_000_000L),
                "a decode failure within the pixel bound degrades to null");
        assertNull(generator.generate(PngFixtures.realPng(1500, 1500), 2_000_000L),
                "an image over the pixel bound is refused from the header before the"
                        + " raster decode — the raster cannot inflate past the IHDR dims");
        assertNull(new ImagePreviewGenerator(65_536L, 10)
                        .generate(PngFixtures.realPng(32, 32), 2_000_000L),
                "a preview still over the char ceiling after downscale is never emitted");
    }

    // --- fixtures --------------------------------------------------------------

    /** Valid signature + IHDR, non-decodable IDAT. */
    private static byte[] pngWithGarbageIdat(int width, int height) {
        byte[] ihdr = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8)
                .put((byte) 6)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        out.writeBytes(chunk("IHDR", ihdr));
        out.writeBytes(chunk("IDAT", new byte[]{0x00, 0x01, 0x02, 0x03, 0x04}));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

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

    /** The SHIPPED char ceiling from the main application.properties via the
     * filesystem (PngMetadataStripTest / ImageCommandHandlerTest precedent —
     * test-resources shadow the classpath name). */
    private static long readShippedPreviewCharCeiling() throws IOException {
        Path current = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < 10; i++) {
            Path candidate = current.resolve(
                    "infochat-provider/src/main/resources/application.properties");
            if (Files.isRegularFile(candidate)) {
                Properties props = new Properties();
                try (InputStream stream = Files.newInputStream(candidate)) {
                    props.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                }
                String value = props.getProperty("infochat.image.preview-max-chars");
                if (value == null) {
                    throw new AssertionError(
                            "main application.properties carries no preview-max-chars key");
                }
                return Long.parseLong(value);
            }
            current = current.getParent();
            if (current == null) {
                break;
            }
        }
        throw new AssertionError("main application.properties not found walking up from user.dir");
    }

}
