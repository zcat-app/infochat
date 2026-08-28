package app.zcat.infochat.provider.chat.tool;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic temporal-expression parse of the ENGLISH-ANCHORED chat query (D19: regex + java.time, no model, no config) — pure by construction: the zone and now are parameters because their resolution (groups.timezone lookup, injected Clock) is the caller's (docs/plan/m1/tick-analysis/temporal-parse-windowing.md P5). Translation is language-only per D58 (d), so the anchored text is English and one grammar serves every scope language. Grammar boundary (analysis P8): explicit relative expressions only — vague recency ("recent", "latest", "top", "new"), year-scale ("this year"), absolute dates, and number words are deliberate non-matches, since an inferred window would silently hide posts the user did not bound; the regex is also negation-blind ("not today"), an accepted trade-off recorded there. Every result is clamped to [SearchPostsTool.WINDOW_MIN, WINDOW_MAX] from the single shared source (P7, M1-689): one conversation, one window vocabulary. Multi-match resolution is code-fixed, never configuration (P3 — same message, same window): narrowest window wins, equal windows resolve to the first-mentioned expression.
 */
final class TemporalExpressionParser {

    record Window(Duration window, String phrase) {}

    // Left edge is a word boundary and the prefix joins with real
    // whitespace (like the `the` junction), so "blast"/"inlast" cannot
    // match — the calendar arm's \btoday\b discipline.
    private static final Pattern COUNTED = Pattern.compile(
            "\\b(?:(?:in|within|over|during)\\s+)?(?:the\\s+)?(?:last|past|previous)\\s+(\\d+)\\s*"
                    + "(hours?|hrs?|h|days?|d|weeks?|w|months?|mo|minutes?|mins?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TODAY = calendar("\\btoday\\b");
    private static final Pattern YESTERDAY = calendar("\\byesterday\\b");
    private static final Pattern THIS_WEEK = calendar("\\bthis week\\b");
    private static final Pattern THIS_MONTH = calendar("\\bthis month\\b");
    private static final Pattern LAST_WEEK = calendar("\\blast week\\b");
    private static final Pattern LAST_MONTH = calendar("\\blast month\\b");

    // "last week"/"last month" read colloquially as rolling windows, not
    // calendar-anchored ones; 30d matches the clamp vocabulary's month.
    private static final Duration LAST_WEEK_WINDOW = Duration.ofDays(7);
    private static final Duration LAST_MONTH_WINDOW = Duration.ofDays(30);

    private static final Comparator<Candidate> NARROWEST_THEN_FIRST_MENTIONED =
            Comparator.comparing(Candidate::window).thenComparingInt(Candidate::start);

    private TemporalExpressionParser() {
    }

    static Optional<Window> parse(String anchoredQuery, ZoneId zone, Instant now) {
        if (anchoredQuery == null || anchoredQuery.isBlank()) {
            return Optional.empty();
        }
        List<Candidate> candidates = new ArrayList<>();
        collectCounted(anchoredQuery, candidates);
        collectCalendars(anchoredQuery, zone, now, candidates);
        return candidates.stream().min(NARROWEST_THEN_FIRST_MENTIONED)
                .map(c -> new Window(c.window(), c.phrase()));
    }

    private static Pattern calendar(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private static void collectCounted(String text, List<Candidate> out) {
        Matcher m = COUNTED.matcher(text);
        while (m.find()) {
            // A count over 999,999,999 (leading zeros stripped) exceeds
            // every clamp bound in every unit, so it takes WINDOW_MAX
            // directly, clear of overflow and wrap in every unit's math.
            String digits = m.group(1).replaceFirst("^0+", "");
            if (digits.isEmpty()) digits = "0";
            Duration window = digits.length() > 9
                    ? SearchPostsTool.WINDOW_MAX
                    : clamp(durationOf(Long.parseLong(digits), m.group(2)));
            out.add(new Candidate(window, m.group(), m.start()));
        }
    }

    private static Duration durationOf(long n, String unit) {
        return switch (unit.charAt(0)) {
            case 'h', 'H' -> Duration.ofHours(n);
            case 'd', 'D' -> Duration.ofDays(n);
            case 'w', 'W' -> Duration.ofDays(7 * n);
            case 'm', 'M' -> unit.regionMatches(true, 1, "i", 0, 1)
                    ? Duration.ofMinutes(n)
                    : Duration.ofDays(30 * n);
            default -> throw new IllegalStateException("Unhandled unit: " + unit);
        };
    }

    private static void collectCalendars(String text, ZoneId zone, Instant now,
                                         List<Candidate> out) {
        ZonedDateTime nowZ = now.atZone(zone);
        collect(TODAY, text, since(nowZ.toLocalDate().atStartOfDay(zone).toInstant(), now), out);
        collect(YESTERDAY, text,
                since(nowZ.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant(), now), out);
        collect(THIS_WEEK, text,
                since(nowZ.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .atStartOfDay(zone).toInstant(), now), out);
        collect(THIS_MONTH, text,
                since(nowZ.toLocalDate().withDayOfMonth(1)
                        .atStartOfDay(zone).toInstant(), now), out);
        collect(LAST_WEEK, text, LAST_WEEK_WINDOW, out);
        collect(LAST_MONTH, text, LAST_MONTH_WINDOW, out);
    }

    private static void collect(Pattern pattern, String text, Duration window,
                                List<Candidate> out) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(new Candidate(clamp(window), m.group(), m.start()));
        }
    }

    private static Duration since(Instant start, Instant now) {
        return Duration.between(start, now);
    }

    private static Duration clamp(Duration window) {
        if (window.compareTo(SearchPostsTool.WINDOW_MIN) < 0) return SearchPostsTool.WINDOW_MIN;
        if (window.compareTo(SearchPostsTool.WINDOW_MAX) > 0) return SearchPostsTool.WINDOW_MAX;
        return window;
    }

    private record Candidate(Duration window, String phrase, int start) {}
}
