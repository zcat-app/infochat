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

    // ----- M1-700: the four render forms (--short / bare / --full / --flat) --
    //
    // --flat is the renamed legacy --full (flat per-cluster blocks). --full
    // is reclaimed for categorized-uncapped. --short is the roll-up overview.
    // The three form flags are mutually exclusive: at most one per invocation.

    @Test
    void flatFlagAloneIsAcceptedAndLeavesTagAndWindowAtDefaults() {
        ParseResult result = SummaryArgs.parse("/summary --flat");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.FLAT, success.args().form(),
                "--flat selects the flat per-cluster render form");
        assertTrue(success.args().tag().isEmpty());
        assertEquals(SummaryArgs.DEFAULT_WINDOW, success.args().window());
    }

    @Test
    void flatFlagCombinesWithPositionalTag() {
        ParseResult result = SummaryArgs.parse("/summary security --flat");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.FLAT, success.args().form());
        assertEquals("security", success.args().tag().orElseThrow());
    }

    @Test
    void flatFlagCombinesWithExplicitWindow() {
        ParseResult result = SummaryArgs.parse("/summary --flat -w 7d");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.FLAT, success.args().form());
        assertEquals(Duration.ofDays(7), success.args().window());
    }

    @Test
    void shortFlagAloneIsAccepted() {
        ParseResult result = SummaryArgs.parse("/summary --short");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.SHORT, success.args().form(),
                "--short selects the roll-up render form");
        assertTrue(success.args().tag().isEmpty());
        assertEquals(SummaryArgs.DEFAULT_WINDOW, success.args().window());
    }

    @Test
    void shortFlagCombinesWithPositionalTag() {
        ParseResult result = SummaryArgs.parse("/summary security --short");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.SHORT, success.args().form());
        assertEquals("security", success.args().tag().orElseThrow());
    }

    @Test
    void shortFlagCombinesWithExplicitWindow() {
        ParseResult result = SummaryArgs.parse("/summary --short -w 7d");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.SHORT, success.args().form());
        assertEquals(Duration.ofDays(7), success.args().window());
    }

    @Test
    void fullFlagAloneIsAccepted() {
        ParseResult result = SummaryArgs.parse("/summary --full");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.FULL, success.args().form(),
                "--full selects the categorized-uncapped render form (M1-700 reclaim)");
        assertTrue(success.args().tag().isEmpty());
        assertEquals(SummaryArgs.DEFAULT_WINDOW, success.args().window());
    }

    @Test
    void fullFlagCombinesWithPositionalTag() {
        ParseResult result = SummaryArgs.parse("/summary security --full");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.FULL, success.args().form());
        assertEquals("security", success.args().tag().orElseThrow());
    }

    @Test
    void fullFlagCombinesWithExplicitWindow() {
        ParseResult result = SummaryArgs.parse("/summary --full -w 7d");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.FULL, success.args().form());
        assertEquals(Duration.ofDays(7), success.args().window());
    }

    /**
     * The three form flags are mutually exclusive: supplying two is a
     * Failure (acceptance item 1). The failure folds to the same
     * window_out_of_range shape every other unknown/excess flag uses, so
     * the user sees one narrow accepted set.
     */
    @Test
    void twoFormFlagsIsAFailure() {
        assertEqualsFormFlagFailure("/summary --short --full");
        assertEqualsFormFlagFailure("/summary --full --flat");
        assertEqualsFormFlagFailure("/summary --flat --short");
        assertEqualsFormFlagFailure("/summary --short --short");
    }

    private static void assertEqualsFormFlagFailure(String input) {
        ParseResult result = SummaryArgs.parse(input);
        Failure failure = assertInstanceOf(Failure.class, result, input + " must fail");
        assertEquals("error.summary.window_out_of_range", failure.bundleKey(),
                input + ": a second form flag folds to the window_out_of_range shape");
    }

    /**
     * The flag is matched exactly, so a near-miss lands on the unknown-flag
     * error rather than silently selecting a render form the user did not
     * ask for.
     */
    @Test
    void nearMissOfFormFlagIsRejectedAsUnknownFlag() {
        ParseResult result = SummaryArgs.parse("/summary --fully");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.summary.window_out_of_range", failure.bundleKey());
    }

    @Test
    void bareSummaryDefaultsToBareForm() {
        ParseResult result = SummaryArgs.parse("/summary");
        Success success = assertInstanceOf(Success.class, result);
        assertEquals(SummaryArgs.RenderForm.BARE, success.args().form(),
                "the categorized-capped form is the default; every form flag is opt-in");
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
