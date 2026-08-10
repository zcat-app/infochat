package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The {@code /image} argument parser (commands.md §Content grammar):
 * bare words are the prompt, {@code -p} captures the remainder verbatim,
 * {@code -r <WxH>} an output size; the prompt cap rejects before any gate. */
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

    /** Parse the normalized {@code /image} body; both bounds are config,
     * never user input, so the parser stays pure. */
    public static ParseResult parse(String rawText, int promptMaxChars, long maxOutputPixels) {
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
                @Nullable Failure invalid = validateResolution(value, maxOutputPixels);
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
    private static @Nullable Failure validateResolution(String value, long maxOutputPixels) {
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
        // Division form: width * height would wrap long for hostile values.
        if (width > maxOutputPixels / height) {
            return new Failure(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_LARGE,
                    List.of(Long.toString(maxOutputPixels)));
        }
        return null;
    }

    private static Resolution resolutionOf(String value) {
        int x = value.indexOf('x');
        return new Resolution(Long.parseLong(value.substring(0, x)),
                Long.parseLong(value.substring(x + 1)));
    }
}
