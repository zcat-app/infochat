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
import java.util.Random;
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
        long shippedCharCeiling = readShippedPreviewConfig("infochat.image.preview-max-chars");
        assertEquals(14_822L, shippedCharCeiling,
                "the shipped ceiling is the 06-messaging.md §6.2.4 recorded accept boundary"
                        + " (14,822 accepted / 16,500 refused), never an invented value");
        byte[] original = PngFixtures.withPromptChunk(
                PngFixtures.realPng(400, 200), "a canary prompt");
        byte[] stripped = PngMetadataStrip.strip(original, 2_000_000L);
        ImagePreviewGenerator generator =
                new ImagePreviewGenerator(8_192L, (int) shippedCharCeiling);

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
        assertTrue((long) image.getWidth() * image.getHeight() <= 8_192L,
                "the preview raster stays within the configured dimension budget");
    }

    /** REPRODUCTION (M1-854): real output is photographic-scale entropy —
     * flat fixtures compress to near-zero and fit any budget, which is how
     * the defect shipped green; shipped defaults must not degrade to null. */
    @Test
    void photographicScaleOutputCarriesAnInCeilingPreviewAtShippedDefaults() throws Exception {
        long shippedCharCeiling = readShippedPreviewConfig("infochat.image.preview-max-chars");
        long shippedPixelBudget = readShippedPreviewConfig("infochat.image.preview-max-pixels");
        byte[] stripped = PngMetadataStrip.strip(
                PngFixtures.withPromptChunk(photographicPng(1792, 1344, 20_260_815L), "a canary prompt"),
                5_000_000L);
        ImagePreviewGenerator generator =
                new ImagePreviewGenerator(shippedPixelBudget, (int) shippedCharCeiling);

        String preview = generator.generate(stripped, 5_000_000L);

        assertNotNull(preview,
                "photographic-scale output carries an inline preview at the shipped defaults");
        assertTrue(preview.startsWith(DATA_URI_PREFIX),
                "the recorded form is a PNG data URI");
        assertTrue(preview.length() <= shippedCharCeiling,
                "the preview stays within the recorded char ceiling");
        byte[] decoded = Base64.getMimeDecoder()
                .decode(preview.substring(DATA_URI_PREFIX.length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertNotNull(image, "the preview payload is itself a decodable PNG");
    }

    /** The shrink ladder (M1-854): a preview over the char ceiling at the
     * initial pixel budget is re-encoded at halved budgets until it fits —
     * config-only recalibration returns null here. */
    @Test
    void overCeilingPreviewShrinksToFitInsteadOfDegrading() throws Exception {
        byte[] stripped = PngMetadataStrip.strip(
                PngFixtures.withPromptChunk(photographicPng(512, 512, 42L), "a canary prompt"),
                5_000_000L);
        ImagePreviewGenerator generator = new ImagePreviewGenerator(65_536L, 20_000);

        String preview = generator.generate(stripped, 5_000_000L);

        assertNotNull(preview, "an over-ceiling preview at the initial budget shrinks to fit");
        assertTrue(preview.length() <= 20_000,
                "the shrunk preview stays within the configured char ceiling");
        byte[] decoded = Base64.getMimeDecoder()
                .decode(preview.substring(DATA_URI_PREFIX.length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertNotNull(image, "the preview payload is itself a decodable PNG");
        assertTrue((long) image.getWidth() * image.getHeight() < 65_536L,
                "the fitting raster is smaller than the initial budget's raster");
    }

    /** REWORK r2 (M1-854): a raster at or under half the starting pixel
     * budget that still encodes over the char ceiling must shrink to fit,
     * not degrade — the ladder exits at the minimal raster, not the budget. */
    @Test
    void underBudgetOverCeilingRasterStillShrinksToFit() throws Exception {
        byte[] stripped = PngMetadataStrip.strip(
                PngFixtures.withPromptChunk(photographicPng(64, 48, 1234L), "a canary prompt"),
                5_000_000L);
        ImagePreviewGenerator generator = new ImagePreviewGenerator(8_192L, 14_822);

        String preview = generator.generate(stripped, 5_000_000L);

        assertNotNull(preview,
                "a raster under the pixel budget but over the char ceiling still shrinks to fit");
        assertTrue(preview.length() <= 14_822,
                "the shrunk preview stays within the recorded char ceiling");
        byte[] decoded = Base64.getMimeDecoder()
                .decode(preview.substring(DATA_URI_PREFIX.length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(decoded));
        assertNotNull(image, "the preview payload is itself a decodable PNG");
        assertTrue((long) image.getWidth() * image.getHeight() < 64L * 48L,
                "the fitting raster is smaller than the over-ceiling source raster");
    }

    /** Ladder termination (M1-854): a ceiling no PNG data URI can meet
     * exhausts the ladder down to the minimal raster and degrades to null —
     * bounded iterations, never an over-ceiling emission. */
    @Test
    void unreachableCeilingExhaustsTheShrinkLadderToNull() throws Exception {
        byte[] stripped = PngMetadataStrip.strip(
                PngFixtures.withPromptChunk(photographicPng(1792, 1344, 99L), "a canary prompt"),
                5_000_000L);
        ImagePreviewGenerator generator = new ImagePreviewGenerator(65_536L, 10);

        assertNull(generator.generate(stripped, 5_000_000L),
                "a ceiling no PNG can meet degrades to null after exhausting the ladder");
    }

    /** FAILURE-MODE (security.md §Trust boundaries item 9): endpoint-chosen
     * bytes degrade to null on every hostile shape — no unbounded decode,
     * no over-limit emit, no escaping exception. */
    @Test
    void refusesOrDegradesHostileInput() throws Exception {
        ImagePreviewGenerator generator = new ImagePreviewGenerator(8_192L, 14_822);

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

    /** A deterministic photographic-entropy PNG: a seeded pseudo-random RGBA
     * raster, near-incompressible — the entropy class real generator output
     * lives in (a flat fixture compresses to near-zero and fits any budget). */
    private static byte[] photographicPng(int width, int height, long seed) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(seed);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt();
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

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

    /** The SHIPPED value of a preview config key from the main
     * application.properties via the filesystem (test-resources shadow
     * the classpath name — PngMetadataStripTest precedent). */
    private static long readShippedPreviewConfig(String key) throws IOException {
        Path current = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < 10; i++) {
            Path candidate = current.resolve(
                    "infochat-provider/src/main/resources/application.properties");
            if (Files.isRegularFile(candidate)) {
                Properties props = new Properties();
                try (InputStream stream = Files.newInputStream(candidate)) {
                    props.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                }
                String value = props.getProperty(key);
                if (value == null) {
                    throw new AssertionError(
                            "main application.properties carries no " + key + " key");
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
