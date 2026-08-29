package app.zcat.infochat.provider.command;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parsed form of a {@code /topic} invocation per docs/spec/commands.md
 * §Content + docs/design/03-commands.md: optional positional {@code [tag]}
 * (a FREE tag, prefix-matched, never vocabulary-checked), optional
 * {@code -w} (SummaryArgs grammar + failure keys; absent = scope-dependent
 * default), optional {@code --full}. Normalization: {@link TagNormalizer}.
 */
public record TopicArgs(
        Optional<String> tag,
        Optional<Duration> window,
        boolean full) {

    private static final String FULL_FLAG = "--full";

    /** {@code -w <N><unit>} pattern; unit is one of {@code h}, {@code d}, {@code w}. */
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^([0-9]+)([hdwm])$");

    /**
     * Parse the post-normalization body (leading /topic token dropped);
     * grammar and failure keys are SummaryArgs' shared shapes.
     */
    public static ParseResult parse(String rawBody) {
        String[] split = rawBody.trim().split("\\s+", 2);
        String remainder = split.length > 1 ? split[1].trim() : "";
        List<String> tokens = remainder.isEmpty() ? List.of() : List.of(remainder.split("\\s+"));

        Optional<String> tag = Optional.empty();
        Optional<Duration> window = Optional.empty();
        boolean full = false;

        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (token.equals(FULL_FLAG)) {
                if (full) {
                    return new Failure(SummaryArgs.BUNDLE_WINDOW_OUT_OF_RANGE);
                }
                full = true;
                i++;
            } else if (token.equals("-w")) {
                if (i + 1 >= tokens.size()) {
                    return new Failure(SummaryArgs.BUNDLE_WINDOW_OUT_OF_RANGE);
                }
                ParseResult windowResult = parseWindow(tokens.get(i + 1));
                if (windowResult instanceof Failure f) {
                    return f;
                }
                window = ((Success) windowResult).args().window();
                i += 2;
            } else if (token.startsWith("-")) {
                return new Failure(SummaryArgs.BUNDLE_WINDOW_OUT_OF_RANGE);
            } else {
                if (tag.isPresent()) {
                    return new Failure(SummaryArgs.BUNDLE_TAG_MALFORMED);
                }
                String normalized = TagNormalizer.normalize(token);
                if (!TagNormalizer.isValid(normalized)) {
                    return new Failure(SummaryArgs.BUNDLE_TAG_MALFORMED);
                }
                tag = Optional.of(normalized);
                i++;
            }
        }
        return new Success(new TopicArgs(tag, window, full));
    }

    private static ParseResult parseWindow(String raw) {
        var m = WINDOW_PATTERN.matcher(raw.toLowerCase(java.util.Locale.ROOT));
        if (!m.matches()) {
            return new Failure(SummaryArgs.BUNDLE_WINDOW_OUT_OF_RANGE);
        }
        int n;
        try {
            n = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return new Failure(SummaryArgs.BUNDLE_WINDOW_OUT_OF_RANGE);
        }
        String unit = m.group(2);
        if (unit.equals("m")) {
            return new Failure(SummaryArgs.BUNDLE_WINDOW_MINUTES_NOT_ACCEPTED);
        }
        Duration d = switch (unit) {
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            case "w" -> Duration.ofDays(n * 7L);
            default -> null;
        };
        if (d == null || !withinRange(unit, n)) {
            return new Failure(SummaryArgs.BUNDLE_WINDOW_OUT_OF_RANGE);
        }
        // Window-only carrier: the caller reads .window() off it, the
        // summary idiom.
        return new Success(new TopicArgs(Optional.empty(), Optional.of(d), false));
    }

    private static boolean withinRange(String unit, int n) {
        return switch (unit) {
            case "h" -> n >= 1 && n <= 168;
            case "d" -> n >= 1 && n <= 30;
            case "w" -> n >= 1 && n <= 4;
            default -> false;
        };
    }

    /** One success shape, one typed failure — the {@link SummaryArgs} parse-result mirror. */
    public sealed interface ParseResult permits Success, Failure {}

    public record Success(TopicArgs args) implements ParseResult {}

    /** Parse failure carrying the bundle key + interpolation args the handler surfaces. */
    public record Failure(String bundleKey, List<String> interpolationArgs) implements ParseResult {
        public Failure(String bundleKey) {
            this(bundleKey, List.of());
        }
    }
}
