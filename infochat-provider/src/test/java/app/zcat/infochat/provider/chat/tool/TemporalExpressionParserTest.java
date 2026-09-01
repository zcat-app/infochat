package app.zcat.infochat.provider.chat.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grammar/clamp/zone/narrowest tables of the deterministic temporal-expression parse (plain JUnit, no container): fixed instant and fixed zones, because the parser takes zone and now as parameters — their resolution is the caller's (M1-938). The negative table is the recorded grammar boundary (analysis P8): vague recency, year-scale, absolute-date, and number-word expressions are deliberate non-matches, since inferring a window the user did not state would silently hide posts.
 */
class TemporalExpressionParserTest {

    /** A Wednesday; Prague is CEST (UTC+2), so local midnight is 22:00Z prior day. */
    private static final Instant NOW = Instant.parse("2026-08-26T09:00:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

    private static Optional<TemporalExpressionParser.Window> parse(String query, ZoneId zone) {
        return TemporalExpressionParser.parse(query, zone, NOW);
    }

    private static void assertWindow(String query, ZoneId zone, Duration expected,
                                      String expectedPhrase) {
        Optional<TemporalExpressionParser.Window> parsed = parse(query, zone);
        assertTrue(parsed.isPresent(), "expected a match for <" + query + ">");
        assertEquals(expected, parsed.orElseThrow().window(),
                "window for <" + query + ">");
        assertEquals(expectedPhrase, parsed.orElseThrow().phrase(),
                "phrase for <" + query + ">");
    }

    private static void assertNoMatch(String query, ZoneId zone) {
        assertTrue(parse(query, zone).isEmpty(),
                "expected NO match for <" + query + ">");
    }

    @Test
    void parsesExplicitRelativeExpressionsAndNothingElse() {
        // Calendar forms under UTC at NOW: today started 2026-08-26T00:00Z
        // (9h ago), yesterday 2026-08-25T00:00Z (33h), the ISO week Monday
        // 2026-08-24T00:00Z (57h), the month 2026-08-01T00:00Z (609h).
        assertWindow("today", UTC, Duration.ofHours(9), "today");
        assertWindow("yesterday", UTC, Duration.ofHours(33), "yesterday");
        assertWindow("this week", UTC, Duration.ofHours(57), "this week");
        assertWindow("this month", UTC, Duration.ofHours(609), "this month");
        assertWindow("last week", UTC, Duration.ofHours(7 * 24), "last week");
        assertWindow("last month", UTC, Duration.ofHours(30 * 24), "last month");

        assertWindow("what happened in tech news in the last 2 hours?", UTC,
                Duration.ofHours(2), "in the last 2 hours");
        assertWindow("in the past 3 days", UTC,
                Duration.ofHours(72), "in the past 3 days");
        assertWindow("past 24h", UTC, Duration.ofHours(24), "past 24h");
        assertWindow("over the last 12 hours", UTC,
                Duration.ofHours(12), "over the last 12 hours");
        assertWindow("world news in last 2h", UTC,
                Duration.ofHours(2), "in last 2h");

        // Case-insensitive with word boundaries; the possessive keeps the
        // boundary ("today's" → matches "today", "hotdays" → no match).
        assertWindow("Today", UTC, Duration.ofHours(9), "Today");
        assertWindow("TODAY", UTC, Duration.ofHours(9), "TODAY");
        assertWindow("today's news", UTC, Duration.ofHours(9), "today");
        assertNoMatch("hotdays", UTC);

        // DESIGNED limitation, recorded trade-off (analysis P8): a regex has no negation reading, so "not today" still windows — accepted because a false positive narrows grounding (every entry dated) while a false negative is the original defect.
        assertWindow("not today", UTC, Duration.ofHours(9), "today");

        // Non-matches: vague recency stays on the steering path (P8); year-scale would mislabel a 30d clamp; absolute dates and number words are outside the explicit-relative grammar; blank/null is a no-op miss.
        assertNoMatch("recent news", UTC);
        assertNoMatch("latest headlines", UTC);
        assertNoMatch("top news", UTC);
        assertNoMatch("new posts", UTC);
        assertNoMatch("what happened with qwen", UTC);
        assertNoMatch("", UTC);
        assertNoMatch("   ", UTC);
        assertNoMatch(null, UTC);
        assertNoMatch("this year", UTC);
        assertNoMatch("on August 20", UTC);
        assertNoMatch("a couple of hours ago", UTC);
    }

    @Test
    void clampsToTheSearchPostsWindowBounds() {
        // Both directions asserted against the SHARED SearchPostsTool
        // constants (analysis P7): one window vocabulary across surfaces —
        // a drift in either bound fails here (or at compilation).
        assertWindow("last 30 minutes", UTC, SearchPostsTool.WINDOW_MIN, "last 30 minutes");
        assertWindow("last 45 days", UTC, SearchPostsTool.WINDOW_MAX, "last 45 days");
        assertWindow("last 8 weeks", UTC, SearchPostsTool.WINDOW_MAX, "last 8 weeks");

        // Oversized counts never throw and never wrap: past-Long digits,
        // Duration.ofDays overflow, and 7*n wrap all land on WINDOW_MAX —
        // the only clamp they can ever reach.
        assertWindow("in the last 99999999999999999999 hours", UTC,
                SearchPostsTool.WINDOW_MAX, "in the last 99999999999999999999 hours");
        assertWindow("last 999999999999999 days", UTC,
                SearchPostsTool.WINDOW_MAX, "last 999999999999999 days");
        assertWindow("last 2635249153387078802 weeks", UTC,
                SearchPostsTool.WINDOW_MAX, "last 2635249153387078802 weeks");

        // The bound is on the VALUE (leading zeros stripped), not the digit
        // run's length: padded small counts read their stated number, and
        // an all-zero count clamps up like any sub-minimum window.
        assertWindow("in the last 0000000002 hours", UTC,
                Duration.ofHours(2), "in the last 0000000002 hours");
        assertWindow("last 0000000000000 hours", UTC,
                SearchPostsTool.WINDOW_MIN, "last 0000000000000 hours");
    }

    @Test
    void calendarExpressionsAnchorToTheScopeZone() {
        // Prague local midnight of 2026-08-26 is 2026-08-25T22:00Z, so
        // "today" spans 11h at NOW there but 9h under UTC — a parser
        // hardcoding UTC fails the Prague arm.
        assertWindow("today", PRAGUE, Duration.ofHours(11), "today");
        assertWindow("today", UTC, Duration.ofHours(9), "today");

        // Bounding window since start of yesterday (over-inclusive by
        // design — the hint phrases it "since yesterday"): start of
        // 2026-08-25 Prague-local = 2026-08-24T22:00Z → 35h.
        assertWindow("yesterday", PRAGUE, Duration.ofHours(35), "yesterday");

        // ISO-Monday week start 2026-08-24 Prague-local midnight =
        // 2026-08-23T22:00Z → 59h; month start 2026-08-01T00:00+02:00 =
        // 2026-07-31T22:00Z → 611h.
        assertWindow("this week", PRAGUE, Duration.ofHours(59), "this week");
        assertWindow("this month", PRAGUE, Duration.ofHours(611), "this month");
    }

    @Test
    void narrowestExpressionWinsWhenSeveralMatch() {
        // Both expressions match ("today" → 9h under UTC, counted → 2h);
        // the NARROWEST window wins — a first-match-only mutation that
        // returns "today" fails here.
        assertWindow("what happened today in the last 2 hours", UTC,
                Duration.ofHours(2), "in the last 2 hours");

        // Equal windows resolve to the first-mentioned expression (pinned;
        // code-fixed rule, never configuration — D19 same-message-same-
        // window).
        assertWindow("last week and the past 7 days", UTC,
                Duration.ofHours(7 * 24), "last week");
    }

    @Test
    void countedFormRequiresALeftWordBoundary() {
        // The counted form's left edge is a word boundary, the calendar
        // arm's discipline: a keyword or prefix fused inside a larger
        // word is not a recency phrase.
        assertNoMatch("blast 3 days", UTC);
        assertNoMatch("inlast 2 hours", UTC);

        // The tighten removes substring matches ONLY: a standalone
        // expression still matches, sited at its own word boundary —
        // never inside the preceding word.
        assertWindow("sin the last 2 hours", UTC,
                Duration.ofHours(2), "the last 2 hours");
        assertWindow("thin last 3 days", UTC,
                Duration.ofHours(72), "last 3 days");
    }

    @Test
    void dayScalePhrasesParseToARollingDayWindow() {
        // The digit-less day-scale family is the sibling of the counted
        // "past 24 hours": a ROLLING 24h window, never a since-midnight
        // calendar day — fixed PT24H at this NOW under any zone.
        assertWindow("security news from the past day", UTC,
                Duration.ofHours(24), "the past day");
        assertWindow("environment news from the past day", UTC,
                Duration.ofHours(24), "the past day");
        assertWindow("past day", UTC, Duration.ofHours(24), "past day");
        assertWindow("over the last day", UTC,
                Duration.ofHours(24), "over the last day");
        assertWindow("in the previous day", UTC,
                Duration.ofHours(24), "in the previous day");
        assertWindow("FROM THE PAST DAY", UTC,
                Duration.ofHours(24), "THE PAST DAY");
        assertWindow("the past day", PRAGUE, Duration.ofHours(24), "the past day");

        // Family edges stay non-matches: the plural carries no definite
        // count (analysis P8), and a "day" without last/past/previous is
        // not a recency phrase ("today" is the calendar token).
        assertNoMatch("the past days", UTC);
        assertNoMatch("in the last days", UTC);
        assertNoMatch("a day ago", UTC);
        assertNoMatch("this day", UTC);
        assertNoMatch("next day", UTC);

        // Coexistence with the digit path: a digit between keyword and
        // unit keeps the counted arm the only match.
        assertWindow("past 1 day", UTC, Duration.ofHours(24), "past 1 day");
        assertWindow("in the last 24 hours", UTC,
                Duration.ofHours(24), "in the last 24 hours");

        // The new candidate rides the existing comparator: narrowest
        // window wins, equal windows resolve first-mentioned.
        assertWindow("what happened today and the past day", UTC,
                Duration.ofHours(9), "today");
        assertWindow("the past day and the last 24 hours", UTC,
                Duration.ofHours(24), "the past day");
    }
}
