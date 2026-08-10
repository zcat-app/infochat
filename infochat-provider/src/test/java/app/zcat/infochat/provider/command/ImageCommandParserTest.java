package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.command.ImageCommandParser.Failure;
import app.zcat.infochat.provider.command.ImageCommandParser.ParseResult;
import app.zcat.infochat.provider.command.ImageCommandParser.Success;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-JUnit grammar + bounds tests for {@link ImageCommandParser}
 * (commands.md §Content grammar); the load-bearing case is the prompt cap
 * rejecting OVER CAP BEFORE ANY GATE runs (the bound lives at the parser). */
class ImageCommandParserTest {

    private static final int CAP = 100;
    private static final long MAX_PIXELS = 2_000_000L;
    private static final long MIN_PIXELS = 16_384L;
    private static final long FINAL_MAX_PIXELS = 5_000_000L;

    @Test
    void overCapPromptIsRejectedBeforeAnyGate() {
        String oversize = "x".repeat(CAP + 1);

        ParseResult result = ImageCommandParser.parse(
                "/image " + oversize, CAP, MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Failure,
                "a maximally-sized prompt must be rejected at the parser, before any gate");
        assertEquals(BundleKeys.IMAGE_ERROR_PROMPT_TOO_LONG, ((Failure) result).bundleKey());
    }

    @Test
    void promptAtExactlyTheCapPasses() {
        String exact = "x".repeat(CAP);

        ParseResult result = ImageCommandParser.parse(
                "/image " + exact, CAP, MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Success);
        assertEquals(exact, ((Success) result).prompt());
    }

    @Test
    void bareWordsAreThePrompt() {
        ParseResult result = ImageCommandParser.parse(
                "/image a red bicycle", CAP, MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Success);
        assertEquals("a red bicycle", ((Success) result).prompt());
        assertEquals(Optional.empty(), ((Success) result).resolution());
    }

    @Test
    void promptFlagCapturesTheRemainderVerbatim() {
        ParseResult result = ImageCommandParser.parse(
                "/image -p a  spaced   out prompt", CAP, MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Success);
        assertEquals("a  spaced   out prompt", ((Success) result).prompt(),
                "interior spacing of the -p remainder is preserved verbatim");
    }

    @Test
    void promptFlagIsLastSoLaterFlagsArePromptText() {
        ParseResult result = ImageCommandParser.parse(
                "/image -p a cat -r 512x512", CAP, MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Success);
        assertEquals("a cat -r 512x512", ((Success) result).prompt(),
                "everything after -p is prompt text, even flag-shaped tokens");
        assertEquals(Optional.empty(), ((Success) result).resolution());
    }

    @Test
    void resolutionFlagParsesAsAnOutputSize() {
        ParseResult result = ImageCommandParser.parse(
                "/image -r 1024x768 a cat", CAP, MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Success);
        assertEquals("a cat", ((Success) result).prompt());
        assertEquals(Optional.of(new ImageCommandParser.Resolution(1024, 768)),
                ((Success) result).resolution());
    }

    @Test
    void belowFloorResolutionIsRejectedBeforeAnyGate() {
        ParseResult result = ImageCommandParser.parse(
                "/image -r 1x1024 a cat", CAP, FINAL_MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Failure);
        assertEquals(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_SMALL, ((Failure) result).bundleKey());
    }

    @Test
    void floorBoundaryIsExactPixelProduct() {
        assertTrue(ImageCommandParser.parse(
                "/image -r 128x128 a cat", CAP, FINAL_MAX_PIXELS, MIN_PIXELS) instanceof Success);
        assertTrue(ImageCommandParser.parse(
                "/image -r 1x16384 a cat", CAP, FINAL_MAX_PIXELS, MIN_PIXELS) instanceof Success);

        for (String value : new String[] {"127x128", "1x16383", "4x4"}) {
            ParseResult result = ImageCommandParser.parse(
                    "/image -r " + value + " a cat", CAP, FINAL_MAX_PIXELS, MIN_PIXELS);
            assertTrue(result instanceof Failure, "expected floor rejection for: " + value);
            assertEquals(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_SMALL, ((Failure) result).bundleKey());
        }
    }

    @Test
    void zeroAndMalformedResolutionsStillSpeakTheGrammarError() {
        for (String value : new String[] {"0x0", "0x512", "foo"}) {
            ParseResult result = ImageCommandParser.parse(
                    "/image -r " + value + " a cat", CAP, FINAL_MAX_PIXELS, MIN_PIXELS);
            assertTrue(result instanceof Failure, "expected a failure for: " + value);
            assertEquals(BundleKeys.IMAGE_ERROR_BAD_RESOLUTION, ((Failure) result).bundleKey());
        }
    }

    @Test
    void overCeilingRejectionAnswersWithTheLargestDimsAtTheRequestedRatio() {
        ParseResult result = ImageCommandParser.parse(
                "/image -r 3000x3000 a cat", CAP, FINAL_MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Failure);
        Failure failure = (Failure) result;
        assertEquals(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_LARGE, failure.bundleKey());
        assertEquals(List.of("2236", "2236"), failure.interpolationArgs());
    }

    @Test
    void hostileOverCeilingValuesNeverSuggestAnOverCeilingResolution() {
        for (String value : new String[] {
                "9223372036854775807x2", "2x9223372036854775807", "5000001x1"}) {
            ParseResult result = ImageCommandParser.parse(
                    "/image -r " + value + " a cat", CAP, FINAL_MAX_PIXELS, MIN_PIXELS);

            assertTrue(result instanceof Failure, "expected a ceiling rejection for: " + value);
            Failure failure = (Failure) result;
            assertEquals(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_LARGE, failure.bundleKey());
            long suggestedWidth = Long.parseLong(failure.interpolationArgs().get(0).toString());
            long suggestedHeight = Long.parseLong(failure.interpolationArgs().get(1).toString());
            assertTrue(suggestedWidth >= 1);
            assertTrue(suggestedHeight >= 1);
            assertTrue(suggestedWidth <= FINAL_MAX_PIXELS / suggestedHeight,
                    "suggestion exceeds ceiling for: " + value);
        }
    }

    @Test
    void resolutionAboveThePixelCeilingIsRejected() {
        ParseResult result = ImageCommandParser.parse(
                "/image -r 2000x2000 a cat", CAP, MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Failure);
        assertEquals(BundleKeys.IMAGE_ERROR_RESOLUTION_TOO_LARGE, ((Failure) result).bundleKey());
    }

    @Test
    void malformedResolutionValuesAreRejected() {
        for (String body : new String[] {
                "/image -r foo a cat",
                "/image -r 1024 a cat",
                "/image -r x512 a cat",
                "/image -r 0x512 a cat",
                "/image -r 512x0 a cat",
                "/image -r -1x512 a cat",
                "/image -r"}) {
            ParseResult result = ImageCommandParser.parse(body, CAP, MAX_PIXELS, MIN_PIXELS);
            assertTrue(result instanceof Failure, "expected a failure for: " + body);
            assertEquals(BundleKeys.IMAGE_ERROR_BAD_RESOLUTION, ((Failure) result).bundleKey(),
                    "malformed --resolution must yield the friendly grammar error: " + body);
        }
    }

    @Test
    void missingPromptIsRejected() {
        for (String body : new String[] {"/image", "/image -r 512x512", "/image -p   "}) {
            ParseResult result = ImageCommandParser.parse(body, CAP, MAX_PIXELS, MIN_PIXELS);
            assertTrue(result instanceof Failure, "expected a failure for: " + body);
            assertEquals(BundleKeys.IMAGE_ERROR_MISSING_PROMPT, ((Failure) result).bundleKey(),
                    "a promptless invocation must yield the missing-prompt error: " + body);
        }
    }

    @Test
    void bareWordsBeforeThePromptFlagJoinTheRemainder() {
        ParseResult result = ImageCommandParser.parse(
                "/image blue -p a door", CAP, MAX_PIXELS, MIN_PIXELS);

        assertTrue(result instanceof Success);
        assertEquals("blue a door", ((Success) result).prompt(),
                "bare words compose with the -p remainder; nothing is dropped");
    }
}
