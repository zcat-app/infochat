package app.zcat.infochat.provider.command;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parsed form of a {@code /summary} invocation. The router hands the
 * handler the post-normalization inbound body verbatim (including the
 * leading {@code /summary} token); {@link #parse(String)} strips the
 * leading token and produces either a populated {@link SummaryArgs}
 * or a typed {@link Failure} carrying the bundle key the handler should
 * surface as a friendly error.
 *
 * <p>Argument shape per {@code docs/spec/commands.md} §Content
 * ({@code /summary}) + {@code docs/design/03-commands.md} §Time window flag
 * and §{@code /summary [tag] [-w 24h]}:
 * <ul>
 *   <li>positional {@code [tag]} (optional)</li>
 *   <li>{@code -w <duration>} (optional; default 24h)</li>
 * </ul>
 *
 * <p>Tag normalization is delegated to {@link TagNormalizer} — the
 * shared trim&nbsp;&rarr;&nbsp;NFC&nbsp;&rarr;&nbsp;lowercase&nbsp;&rarr;&nbsp;char-class
 * pipeline extracted in M1-489 when {@code /follow-tag} and
 * {@code /unfollow-tag} became the third and fourth controlled-vocabulary
 * consumers. {@code /summary} is a read-side filter with no SQL CHECK to
 * fall back on, so the char-class step runs here at parse time.
 */
public record SummaryArgs(
        Optional<String> tag,
        Duration window) {

    /** Default time window per design 03 §Time window flag. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /** {@code -w <N><unit>} pattern; unit is one of {@code h}, {@code d}, {@code w}. */
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^([0-9]+)([hdwm])$");

    public sealed interface ParseResult permits Success, Failure {}

    public record Success(SummaryArgs args) implements ParseResult {}

    /**
     * Parse failure carrying the en.properties bundle key the handler
     * should surface to the caller. {@code interpolationArgs} captures
     * any caller-supplied values the bundle template references (e.g.
     * the rejected tag).
     */
    public record Failure(String bundleKey, List<String> interpolationArgs) implements ParseResult {
        public Failure(String bundleKey) {
            this(bundleKey, List.of());
        }
    }

    /**
     * Parse the post-normalization inbound body. {@code rawBody} carries
     * the leading {@code /summary} token; the parser drops the first
     * whitespace-delimited token before walking the remaining args.
     */
    public static ParseResult parse(String rawBody) {
        String[] split = rawBody.trim().split("\\s+", 2);
        String remainder = split.length > 1 ? split[1].trim() : "";
        List<String> tokens = remainder.isEmpty() ? List.of() : List.of(remainder.split("\\s+"));

        Optional<String> tag = Optional.empty();
        Duration window = DEFAULT_WINDOW;

        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (token.equals("-w")) {
                if (i + 1 >= tokens.size()) {
                    return new Failure(BUNDLE_WINDOW_OUT_OF_RANGE);
                }
                ParseResult windowResult = parseWindow(tokens.get(i + 1));
                if (windowResult instanceof Failure f) {
                    return f;
                }
                window = ((Success) windowResult).args().window();
                i += 2;
            } else if (token.startsWith("-")) {
                // Unknown flag — fold to malformed range so the user
                // sees the same shape error and a single, narrow
                // accepted set.
                return new Failure(BUNDLE_WINDOW_OUT_OF_RANGE);
            } else {
                // First positional token is the tag; reject a second one
                // (multiple positional tags are not supported in v1).
                if (tag.isPresent()) {
                    return new Failure(BUNDLE_TAG_MALFORMED);
                }
                String normalized = TagNormalizer.normalize(token);
                if (!TagNormalizer.isValid(normalized)) {
                    return new Failure(BUNDLE_TAG_MALFORMED);
                }
                tag = Optional.of(normalized);
                i++;
            }
        }
        return new Success(new SummaryArgs(tag, window));
    }

    private static ParseResult parseWindow(String raw) {
        var m = WINDOW_PATTERN.matcher(raw.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return new Failure(BUNDLE_WINDOW_OUT_OF_RANGE);
        }
        int n;
        try {
            n = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return new Failure(BUNDLE_WINDOW_OUT_OF_RANGE);
        }
        String unit = m.group(2);
        if (unit.equals("m")) {
            return new Failure(BUNDLE_WINDOW_MINUTES_NOT_ACCEPTED);
        }
        Duration d = switch (unit) {
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            case "w" -> Duration.ofDays(n * 7L);
            default -> null;
        };
        if (d == null) {
            return new Failure(BUNDLE_WINDOW_OUT_OF_RANGE);
        }
        if (!isWithinRange(unit, n)) {
            return new Failure(BUNDLE_WINDOW_OUT_OF_RANGE);
        }
        return new Success(new SummaryArgs(Optional.empty(), d));
    }

    private static boolean isWithinRange(String unit, int n) {
        return switch (unit) {
            case "h" -> n >= 1 && n <= 168;
            case "d" -> n >= 1 && n <= 30;
            case "w" -> n >= 1 && n <= 4;
            default -> false;
        };
    }

    // Bundle keys referenced from this file (kept as private constants
    // so the parser file stays decoupled from the
    // app.zcat.infochat.provider.bundle.BundleKeys class — the handler
    // is the one that looks them up via BundleLoader).
    static final String BUNDLE_WINDOW_MINUTES_NOT_ACCEPTED =
            "error.summary.window_minutes_not_accepted";
    static final String BUNDLE_WINDOW_OUT_OF_RANGE = "error.summary.window_out_of_range";
    static final String BUNDLE_UNKNOWN_TAG = "error.summary.unknown_tag";
    static final String BUNDLE_TAG_MALFORMED = "error.summary.tag_malformed";

    /**
     * Compose a typed {@link Failure} carrying the
     * {@link #BUNDLE_UNKNOWN_TAG} key plus the supplied tag and a
     * comma-joined fuzzy-suggestion list. Called by the handler after
     * the parser produces a syntactically-valid tag that misses the
     * controlled vocabulary; the handler's vocabulary lookup is the
     * one that knows the suggestion set.
     */
    public static Failure unknownTagFailure(String suppliedTag, List<String> suggestions) {
        List<String> interpolation = new ArrayList<>();
        interpolation.add(suppliedTag);
        interpolation.add(String.join(", ", suggestions));
        return new Failure(BUNDLE_UNKNOWN_TAG, interpolation);
    }
}
