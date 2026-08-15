package app.zcat.infochat.provider.image;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/** The inline-image preview generator (design 06-messaging.md §6.2.4):
 * decodes STRIPPED bytes only, pixel-bounded by the strip's IHDR bound,
 * and degrades every failure to null — the plain file form. */
@ApplicationScoped
public class ImagePreviewGenerator {

    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    private final long previewMaxPixels;
    private final int previewMaxChars;

    @Inject
    public ImagePreviewGenerator(
            @ConfigProperty(name = "infochat.image.preview-max-pixels") long previewMaxPixels,
            @ConfigProperty(name = "infochat.image.preview-max-chars") int previewMaxChars) {
        this.previewMaxPixels = previewMaxPixels;
        this.previewMaxChars = previewMaxChars;
    }

    /** A PNG data-URI preview of {@code strippedPng}, or {@code null} when
     * the input is undecodable, over the pixel bound, or the encoded
     * preview still exceeds the char ceiling after downscale. */
    public @Nullable String generate(byte[] strippedPng, long maxOutputPixels) {
        try (ImageInputStream input =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(strippedPng))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                // Dimensions come from the IHDR alone; a PNG cannot inflate
                // past its declared scanlines, so this check bounds the
                // raster allocation before any decode.
                if ((long) reader.getWidth(0) * reader.getHeight(0) > maxOutputPixels) {
                    return null;
                }
                BufferedImage downscaled = downscale(reader.read(0));
                String dataUri = DATA_URI_PREFIX + encodeAsPng(downscaled);
                return dataUri.length() <= previewMaxChars ? dataUri : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private BufferedImage downscale(BufferedImage image) {
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= previewMaxPixels) {
            return image;
        }
        double scale = Math.sqrt((double) previewMaxPixels / pixels);
        int width = Math.max(1, (int) Math.floor(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.floor(image.getHeight() * scale));
        BufferedImage preview = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = preview.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return preview;
    }

    private static String encodeAsPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", encoded)) {
            throw new IOException("no PNG writer registered");
        }
        return Base64.getEncoder().encodeToString(encoded.toByteArray());
    }
}
