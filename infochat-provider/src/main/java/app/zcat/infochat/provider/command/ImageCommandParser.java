package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The {@code /image} argument parser (commands.md §Content grammar):
 * bare words are the prompt, {@code -p} captures the remainder verbatim,
 * {@code -r <WxH>} an output size; all bounds reject before any gate. */
public final class ImageCommandParser {

    private ImageCommandParser() {
    }

    public sealed interface ParseResult permits Success, Failure {}

    /** A parsed invocation: the assembled prompt and the optional output size. */
    public record Success(String prompt, Optional<Resolution> resolution) implements ParseResult {}

    /** A parsed {@code WxH} output size. */
    public record Resolution(long width, long height) {
        public long pixels() {
            return width * height;
        }
    }

    /** A friendly-error outcome: the bundle key plus its interpolation args. */
    public record Failure(String bundleKey, List<Object> interpolationArgs) implements ParseResult {}

    /** Parse the normalized {@code /image} body; all bounds are config,
     * never user input, so the parser stays pure. */
    public static ParseResult parse(String rawText, int promptMaxChars,
            long maxOutputPixels, long minOutputPixels) {
        int firstSpace = rawText.indexOf(' ');
        String remaining = firstSpace < 0 ? "" : rawText.substring(firstSpace + 1).stripLeading();

        List<String> bareWords = new ArrayList<>();
        Optional<Resolution> resolution = Optional.empty();
        String promptRemainder = null;

        while (!remaining.isEmpty()) {
            int space = remaining.indexOf(' ');
            String token = space < 0 ? remaining : remaining.substring(0, space);
            String rest = space < 0 ? "" : remaining.substring(space + 1).stripLeading();

            if ("--prompt".equals(token) || "-p".equals(token)) {
                // Last flag: the remainder of the line is the prompt,
                // verbatim (ends trimmed; interior spacing preserved).
                promptRemainder = rest.strip();
                break;
            }
            if ("--resolution".equals(token) || "-r".equals(token)) {
                if (rest.isEmpty()) {
                    return new Failure(BundleKeys.IMAGE_ERROR_BAD_RESOLUTION, List.of());
                }
                int valueEnd = rest.indexOf(' ');
                String value = valueEnd < 0 ? rest : rest.substring(0, valueEnd);
                remaining = valueEnd < 0 ? "" : rest.substring(valueEnd + 1).stripLeading();
                @Nullable Failure invalid = validateResolution(value, maxOutputPixels, minOutputPixels);
                if (invalid != null) {
                    return invalid;
                }
                resolution = Optional.of(resolutionOf(value));
                continue;
            }
            bareWords.add(token);
            remaining = rest;
        }

        StringBuilder prompt = new StringBuilder();
        for (String word : bareWords) {
            if (prompt.length() > 0) {
                prompt.append(' ');
            }
            prompt.append(word);
        }
        if (promptRemainder != null && !promptRemainder.isEmpty()) {
            if (prompt.length() > 0) {
                prompt.append(' ');
            }
            prompt.append(promptRemainder);
        }
        if (prompt.length() == 0) {
            return new Failure(BundleKeys.IMAGE_ERROR_MISSING_PROMPT, List.of());
        }
        if (prompt.length() > promptMaxChars) {
            return new Failure(BundleKeys.IMAGE_ERROR_PROMPT_TOO_LONG,
                    List.of(Integer.toString(promptMaxChars)));
        }
        return new Success(prompt.toString(), resolution);
    }

    /** The bounds check for one {@code WxH} value, or null when it holds. */
    private static @Nullable Failure validateResolution(
            String value, long maxOutputPixels, long minOutputPixels) {
        int x = value.indexOf('x');
        if (x <= 0 || x == value.length() - 1) {
            return new Failure(BundleKeys.IMAGE_ERROR_BAD_RESOLUTION, List.of());
        }
        long width;
        long height;
        try {
            width = Long.parseLong(value.substring(0, x));
            height = Long.parseLong(value.substring(x + 1));
        } catch (NumberFormatException e) {
            return new Failure(BundleKeys.IMAGE_ERROR_BAD_RESOLUTION, List.of());
        }
        if (width < 1 || height < 1) {
            return new Failure(BundleKeys.IMAGE_ERROR_BAD_RESOLUTION, List.of());
        }
        if (width < ceilDivide(minOutputPixels, height)) {
            long[] suggested = smallestAllowedResolution(
                    width, height, minOutputPixels, maxOutputPixels);
            return new Failure(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_SMALL,
                    List.of(Long.toString(suggested[0]), Long.toString(suggested[1])));
        }
        // Division form: width * height would wrap long for hostile values.
        if (width > maxOutputPixels / height) {
            long[] suggested = largestAllowedResolution(width, height, maxOutputPixels);
            return new Failure(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_LARGE,
                    List.of(Long.toString(suggested[0]), Long.toString(suggested[1])));
        }
        return null;
    }

    private static long[] largestAllowedResolution(long width, long height, long maxOutputPixels) {
        double scale = Math.sqrt((double) maxOutputPixels / ((double) width * (double) height));
        long suggestedWidth = boundedDimension((long) Math.floor(width * scale), maxOutputPixels);
        long suggestedHeight = boundedDimension((long) Math.floor(height * scale), maxOutputPixels);
        suggestedWidth = Math.min(suggestedWidth, maxOutputPixels / suggestedHeight);
        return new long[] {suggestedWidth, suggestedHeight};
    }

    private static long[] smallestAllowedResolution(
            long width, long height, long minOutputPixels, long maxOutputPixels) {
        double scale = Math.sqrt((double) minOutputPixels / ((double) width * (double) height));
        long suggestedWidth = boundedDimension((long) Math.ceil(width * scale), maxOutputPixels);
        long suggestedHeight = boundedDimension((long) Math.ceil(height * scale), maxOutputPixels);
        suggestedWidth = Math.max(suggestedWidth, ceilDivide(minOutputPixels, suggestedHeight));
        if (suggestedWidth > maxOutputPixels) {
            suggestedWidth = maxOutputPixels;
            suggestedHeight = Math.max(suggestedHeight, ceilDivide(minOutputPixels, suggestedWidth));
        }
        return new long[] {suggestedWidth, Math.min(suggestedHeight, maxOutputPixels)};
    }

    private static long boundedDimension(long dimension, long maxOutputPixels) {
        return Math.min(Math.max(1L, dimension), maxOutputPixels);
    }

    private static long ceilDivide(long dividend, long divisor) {
        return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
    }

    private static Resolution resolutionOf(String value) {
        int x = value.indexOf('x');
        return new Resolution(Long.parseLong(value.substring(0, x)),
                Long.parseLong(value.substring(x + 1)));
    }
}
