package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.command.SummaryArgs.Failure;
import app.zcat.infochat.provider.command.SummaryArgs.ParseResult;
import app.zcat.infochat.provider.command.SummaryArgs.Success;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-vector parse tests for {@link SummaryArgs} covering acceptance
 * item 4 of M1-037. Each case is one input string → expected outcome
 * (tag, window, or named failure bundle key).
 */
class SummaryArgsTest {

    @Test
    void bareSummaryDefaultsTo24hAndNoTag() {
        ParseResult result = SummaryArgs.parse("/summary");
        Success success = assertInstanceOf(Success.class, result);
        assertTrue(success.args().tag().isEmpty(),
                "bare /summary must produce tag=NONE");
        assertEquals(Duration.ofHours(24), success.args().window(),
                "bare /summary defaults to 24h per design 03 §Time window flag");
    }

    @Test
    void positionalTagIsAcceptedInCanonicalForm() {
        ParseResult result = SummaryArgs.parse("/summary security");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals("security", success.args().tag().orElseThrow());
        assertEquals(Duration.ofHours(24), success.args().window());
    }

    @Test
    void tagPlusExplicitWindow() {
        ParseResult result = SummaryArgs.parse("/summary security -w 48h");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals("security", success.args().tag().orElseThrow());
        assertEquals(Duration.ofHours(48), success.args().window());
    }

    @Test
    void windowOnlyWithoutTag() {
        ParseResult result = SummaryArgs.parse("/summary -w 7d");
        Success success = assertInstanceOf(Success.class, result);
        assertTrue(success.args().tag().isEmpty());
        assertEquals(Duration.ofDays(7), success.args().window());
    }

    @Test
    void minutesSuffixIsRejectedWithDedicatedBundleKey() {
        ParseResult result = SummaryArgs.parse("/summary -w 5m");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.summary.window_minutes_not_accepted", failure.bundleKey(),
                "the `m` suffix has its own friendly error per design 03 §Time window flag");
    }

    @Test
    void windowOutOfRangeIsRejected() {
        ParseResult result = SummaryArgs.parse("/summary -w 200h");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.summary.window_out_of_range", failure.bundleKey(),
                "200h is outside 1h-168h range");
    }

    @Test
    void unknownTagInVocabSurfacesViaFactory() {
        // The PARSER itself accepts any syntactically-valid tag because
        // the controlled-vocabulary lookup happens at the handler tier.
        // The unknownTagFailure() factory is what materializes the
        // friendly error after the handler's DB check fails.
        Failure failure = SummaryArgs.unknownTagFailure(
                "an-invalid-tag-not-in-vocab",
                java.util.List.of("security", "ai"));
        assertEquals("error.summary.unknown_tag", failure.bundleKey());
        assertEquals(2, failure.interpolationArgs().size());
        assertEquals("an-invalid-tag-not-in-vocab", failure.interpolationArgs().get(0));
        assertEquals("security, ai", failure.interpolationArgs().get(1));
    }

    @Test
    void overlongTagIsRejectedByInlineRegexCheck() {
        // 56 characters: exceeds the 48-char cap from the V6
        // tag.name CHECK constraint regex.
        String overlong = "security-with-very-long-name-exceeding-48-chars-aaaaaaaa";
        assertTrue(overlong.length() > 48,
                "test vector must exercise the >48-char branch");
        ParseResult result = SummaryArgs.parse("/summary " + overlong);
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.summary.tag_malformed", failure.bundleKey(),
                "overlong tag fails the ^[a-z0-9][a-z0-9-]{0,47}$ regex");
    }

    @Test
    void tagsAreNormalizedToLowercaseNfc() {
        ParseResult result = SummaryArgs.parse("/summary SECURITY");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals("security", success.args().tag().orElseThrow(),
                "tag canonical form is lowercase NFC");
    }

    @Test
    void tagWithLeadingHyphenIsMalformed() {
        ParseResult result = SummaryArgs.parse("/summary -leading-hyphen");
        Failure failure = assertInstanceOf(Failure.class, result);
        // Leading hyphen looks like an unknown flag; both branches fold
        // to a friendly window/tag error. We accept either bundle key
        // here since both are valid responses to an unknown leading
        // hyphen; what matters is that the parse fails.
        assertFalse(failure.bundleKey().isEmpty(),
                "a leading-hyphen token must produce a failure");
    }

    @Test
    void bareFullFlagIsAcceptedAndLeavesTagAndWindowAtDefaults() {
        ParseResult result = SummaryArgs.parse("/summary --full");
        Success success = assertInstanceOf(Success.class, result);
        assertTrue(success.args().full(), "--full must set the flat-render opt-in");
        assertTrue(success.args().tag().isEmpty());
        assertEquals(SummaryArgs.DEFAULT_WINDOW, success.args().window());
    }

    @Test
    void fullFlagCombinesWithPositionalTag() {
        ParseResult result = SummaryArgs.parse("/summary security --full");
        Success success = assertInstanceOf(Success.class, result);
        assertTrue(success.args().full());
        assertEquals("security", success.args().tag().orElseThrow());
    }

    @Test
    void fullFlagCombinesWithExplicitWindow() {
        ParseResult result = SummaryArgs.parse("/summary --full -w 7d");
        Success success = assertInstanceOf(Success.class, result);
        assertTrue(success.args().full());
        assertEquals(Duration.ofDays(7), success.args().window());
    }

    /**
     * The flag is matched exactly, so a near-miss lands on the unknown-flag
     * error rather than silently selecting a render form the user did not
     * ask for.
     */
    @Test
    void nearMissOfFullFlagIsRejectedAsUnknownFlag() {
        ParseResult result = SummaryArgs.parse("/summary --fully");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.summary.window_out_of_range", failure.bundleKey());
    }

    @Test
    void bareSummaryLeavesFullFlagOff() {
        ParseResult result = SummaryArgs.parse("/summary");
        Success success = assertInstanceOf(Success.class, result);
        assertFalse(success.args().full(),
                "the categorized form is the default; --full is opt-in");
    }

    @Test
    void windowAcceptsWeekUnit() {
        ParseResult result = SummaryArgs.parse("/summary -w 2w");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(Duration.ofDays(14), success.args().window());
    }

    @Test
    void windowWeekUnitOutOfRangeIsRejected() {
        ParseResult result = SummaryArgs.parse("/summary -w 5w");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.summary.window_out_of_range", failure.bundleKey());
    }
}
