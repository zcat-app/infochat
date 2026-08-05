package app.zcat.infochat.provider.render;

import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the shared display-headline derivation (M1-714).
 *
 * <p>Uses the real closed-list sanitizer with its audit write stubbed out, so
 * the redaction logic under test is production's, not a stand-in.
 */
class DisplayHeadlineTest {

    private final LlmOutputSanitizer sanitizer = SanitizerTestDoubles.noAuditSanitizer();

    @Test
    void shortTitleReturnedUnchanged() {
        String headline = DisplayHeadline.of(post("Bitcoin hits $100k", "body text"), sanitizer);

        assertEquals("Bitcoin hits $100k", headline,
                "a title already within the bound must round-trip byte-identical");
    }

    @Test
    void longTitleTruncatedWithEllipsis() {
        String longTitle = "x".repeat(DisplayHeadline.MAX_LENGTH + 50);

        String headline = DisplayHeadline.of(post(longTitle, "body"), sanitizer);

        assertTrue(headline.endsWith(DisplayHeadline.ELLIPSIS),
                "a cut headline must carry the trailing ellipsis; got: " + headline);
        assertTrue(headline.length() <= DisplayHeadline.MAX_LENGTH + DisplayHeadline.ELLIPSIS.length(),
                "the headline must be bounded; length was " + headline.length());
    }

    @Test
    void emptyTitleFallsBackToBody() {
        // Every one of the 729 Bluesky posts in the live corpus has an empty
        // title; 728 of them carry usable body text.
        String headline = DisplayHeadline.of(post("", "A post with no title field"), sanitizer);

        assertEquals("A post with no title field", headline,
                "an empty title must fall back to the body");
    }

    @Test
    void blankTitleFallsBackToBody() {
        String headline = DisplayHeadline.of(post("   ", "Body wins"), sanitizer);

        assertEquals("Body wins", headline,
                "a whitespace-only title carries no headline and must fall back to the body");
    }

    @Test
    void storedUntitledSentinelFallsBackToBody() {
        // The shape every titleless-by-design source produces once the ingest
        // write path has run: Bluesky and Nostr pass a null title, an absent
        // Reddit title arrives as "", and all three are stored as the
        // sentinel. Matching only on blank left this dead for every row
        // written since M1-693 (M1-729).
        String headline = DisplayHeadline.of(
                post(IngestTextNormalizer.UNTITLED_TITLE, "The actual post text"), sanitizer);

        assertEquals("The actual post text", headline,
                "the stored sentinel means 'no title' and must fall back to the body");
    }

    @Test
    void storedUntitledSentinelWithNoBodyYieldsEmptyString() {
        // Sentinel title AND no body: the post still resolves to the stable
        // no-renderable-text representation rather than leaking the storage
        // placeholder to a reader.
        String headline = DisplayHeadline.of(
                post(IngestTextNormalizer.UNTITLED_TITLE, ""), sanitizer);

        assertEquals("", headline,
                "the sentinel must never reach a render surface, even with no body");
    }

    @Test
    void titleThatMerelyStartsWithTheSentinelWordRendersAsItself() {
        // The sentinel match is exact equality. A contains/startsWith or
        // case-insensitive test would swallow real titles like these and
        // replace them with body text the author did not write.
        assertEquals("untitled draft #4",
                DisplayHeadline.of(post("untitled draft #4", "BODYMARKER"), sanitizer),
                "a real title that only starts with the sentinel word must survive");
        assertEquals("Untitled",
                DisplayHeadline.of(post("Untitled", "BODYMARKER"), sanitizer),
                "the match is case-sensitive: only the byte-exact sentinel is a placeholder");
    }

    @Test
    void emptyTitleAndBodyYieldsEmptyString() {
        // The all-empty post: no placeholder is invented. Callers omit the
        // headline token and its separator instead.
        String headline = DisplayHeadline.of(post("", ""), sanitizer);

        assertEquals("", headline,
                "a post with no renderable text must yield the empty string, not a placeholder");
    }

    @Test
    void emptyTitleAndNullBodyYieldsEmptyString() {
        // `body` is nullable in the DDL (`body TEXT`), so the null case is real.
        String headline = DisplayHeadline.of(post("", null), sanitizer);

        assertEquals("", headline, "a null body must be treated as no renderable text");
    }

    @Test
    void titleAndBodyAreNeverConcatenated() {
        // The M1-697 sanitize unit is ONE author's field per call. Selecting the
        // title must leave the body entirely out of the result — a concatenation
        // would widen what a single sanitize call can reach.
        String headline = DisplayHeadline.of(post("Real title", "BODYMARKER"), sanitizer);

        assertEquals("Real title", headline,
                "the body must not be appended to a non-blank title");
        assertFalse(headline.contains("BODYMARKER"),
                "body bytes must never join the title in one headline; got: " + headline);
    }

    @Test
    void newlinesAreFlattenedToOneLine() {
        // A body fallback routinely contains newlines; left in place they would
        // inject extra lines into the block that contains the headline.
        String headline = DisplayHeadline.of(post("", "line one\nline two\r\nline three"), sanitizer);

        assertEquals("line one line two line three", headline,
                "the headline must be a single line; got: " + headline);
    }

    @Test
    void unicodeLineSeparatorIsFlattened() {
        // U+2028 is a line boundary that Java's \s does not match, so it needs
        // \R to be caught — a group-broadcast line start is exactly where a
        // smuggled break would do damage. Built by code point rather than as a
        // source literal so the intent survives an editor round-trip.
        String lineSeparator = String.valueOf((char) 0x2028);
        String headline =
                DisplayHeadline.of(post("", "before" + lineSeparator + "after"), sanitizer);

        assertFalse(headline.contains(lineSeparator),
                "U+2028 must not survive into a one-line headline; got: " + headline);
        assertEquals("before after", headline);
    }

    @Test
    void emojiAtCutBoundaryIsNotSplit() {
        // Place an astral-plane character so its surrogate pair straddles the
        // cut index. 287 of 1,868 nitter titles carry emoji, so this is routine.
        String title = "a".repeat(DisplayHeadline.MAX_LENGTH - 1) + "😀" + "tail".repeat(20);

        String headline = DisplayHeadline.of(post(title, "body"), sanitizer);

        assertNoUnpairedSurrogate(headline);
        assertEquals(DisplayHeadline.MAX_LENGTH - 1,
                headline.length() - DisplayHeadline.ELLIPSIS.length(),
                "the cut must back off the split pair rather than emit half of it");
    }

    @Test
    void redactionMarkerStraddlingTheCutIsNotEmittedPartially() {
        // Position "/grant-admin" so that the marker it becomes spans the cut
        // index. A half-emitted "[redacted comm" reads as content, not as a
        // redaction.
        int fillerLength = DisplayHeadline.MAX_LENGTH
                - LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT.length() + 10;
        String title = "x".repeat(fillerLength) + "/grant-admin "
                + "11111111-2222-3333-4444-555555555555 trailing text";

        String headline = DisplayHeadline.of(post(title, "body"), sanitizer);

        assertFalse(headline.contains("["),
                "no fragment of the redaction marker may be emitted; got: " + headline);
        assertTrue(headline.endsWith(DisplayHeadline.ELLIPSIS),
                "the headline was cut, so it must carry the ellipsis; got: " + headline);
        assertEquals("x".repeat(fillerLength) + DisplayHeadline.ELLIPSIS, headline,
                "the cut must fall back to the marker's start; got: " + headline);
    }

    @Test
    void unicodeSeparatorInsideAClosedListEntryIsRedactedNotRewrittenIntoACommand() {
        // Redteam 2026-07-30, medium/INJECTION. The sanitizer's token
        // separators are the ASCII whitespace set only, and its canonical form
        // leaves U+2028 intact, so `/quarantine<U+2028>approve` is ONE token to
        // it. If the whitespace rewrite ran AFTER sanitize, that token would
        // pass unmatched and unaudited and then be rewritten into the
        // dispatchable `/quarantine approve` at a group-broadcast line start.
        // Flattening first is what makes the sanitizer see what is delivered.
        String lineSeparator = String.valueOf((char) 0x2028);
        String title = "/quarantine" + lineSeparator
                + "approve 11111111-2222-3333-4444-555555555555";

        String headline = DisplayHeadline.of(post(title, "body"), sanitizer);

        assertFalse(headline.contains("/quarantine approve"),
                "the flattened form must not survive as a dispatchable command; got: "
                        + headline);
        assertTrue(headline.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the entry must be redacted once flattening precedes sanitize; got: "
                        + headline);
    }

    @Test
    void unicodeSeparatorInsideAFlagEntryIsRedacted() {
        // Same evasion against a flag-bearing entry, whose span runs from the
        // command word to the flag token.
        String lineSeparator = String.valueOf((char) 0x2028);

        String headline = DisplayHeadline.of(
                post("/list-sources" + lineSeparator + "--all", "body"), sanitizer);

        assertFalse(headline.contains("/list-sources --all"),
                "the flag entry must not be reassembled into a dispatchable form; got: "
                        + headline);
        assertTrue(headline.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the flag entry must be redacted; got: " + headline);
    }

    @Test
    void overLongBodyIsBoundedBeforeItReachesTheSanitizer() {
        // Redteam 2026-07-30, low/DOS. post.body has no write-boundary cap, and
        // the sanitizer runs ~35 linear passes over whatever it is handed, once
        // per post on the digest scheduler thread. A recording sanitizer proves
        // the bound is applied to the INPUT, not just to the output.
        List<Integer> observedInputLengths = new ArrayList<>();
        LlmOutputSanitizer recordingSanitizer = new LlmOutputSanitizer(
                SanitizerTestDoubles.noOpAuditLogWriter(), SanitizerTestDoubles.noOpDataSource()) {
            @Override
            public String sanitize(String input) {
                observedInputLengths.add(input.length());
                return super.sanitize(input);
            }
        };
        String hugeBody = "w".repeat(DisplayHeadline.BODY_SCAN_LIMIT * 3);

        DisplayHeadline.of(post("", hugeBody), recordingSanitizer);

        assertEquals(1, observedInputLengths.size(), "exactly one sanitize call per headline");
        assertTrue(observedInputLengths.get(0) <= DisplayHeadline.BODY_SCAN_LIMIT,
                "the body must be bounded BEFORE the sanitizer sees it; sanitizer received "
                        + observedInputLengths.get(0) + " chars, limit is "
                        + DisplayHeadline.BODY_SCAN_LIMIT);
    }

    @Test
    void bodyWithinTheScanLimitReachesTheSanitizerWhole() {
        // The pre-bound must not narrow ordinary input: a body under the limit
        // is handed over intact, so flagged spans in it are still audited.
        List<Integer> observedInputLengths = new ArrayList<>();
        LlmOutputSanitizer recordingSanitizer = new LlmOutputSanitizer(
                SanitizerTestDoubles.noOpAuditLogWriter(), SanitizerTestDoubles.noOpDataSource()) {
            @Override
            public String sanitize(String input) {
                observedInputLengths.add(input.length());
                return super.sanitize(input);
            }
        };
        String body = "w".repeat(DisplayHeadline.MAX_LENGTH * 2);

        DisplayHeadline.of(post("", body), recordingSanitizer);

        assertEquals(body.length(), observedInputLengths.get(0),
                "a body within the scan limit must reach the sanitizer whole");
    }

    @Test
    void stage1RedactionPlaceholderStraddlingTheCutIsNotEmittedPartially() {
        // Redteam 2026-07-30, out-of-model. Stage 1 writes [REDACTED:<id>] into
        // the BODY, which is now a headline source, and security.md commits the
        // marker to exact-match recognition. A cut landing inside it would emit
        // `[REDACTED:9f` which reads as content, not as a redaction.
        String placeholder = "[REDACTED:9f3c1d2e4b5a6789]";
        int fillerLength = DisplayHeadline.MAX_LENGTH - 6;
        String body = "b".repeat(fillerLength) + placeholder + " trailing text";

        String headline = DisplayHeadline.of(post("", body), sanitizer);

        assertFalse(headline.contains("[REDACTED:9f3c"),
                "no fragment of the Stage 1 placeholder may be emitted; got: " + headline);
        assertEquals("b".repeat(fillerLength) + DisplayHeadline.ELLIPSIS, headline,
                "the cut must fall back to the placeholder's start; got: " + headline);
    }

    @Test
    void stage1RedactionPlaceholderWhollyInsideTheBoundSurvivesIntact() {
        // The guard must not over-trim: a placeholder that fits is kept whole.
        String placeholder = "[REDACTED:9f3c1d2e4b5a6789]";
        String body = placeholder + " " + "b".repeat(DisplayHeadline.MAX_LENGTH * 2);

        String headline = DisplayHeadline.of(post("", body), sanitizer);

        assertTrue(headline.startsWith(placeholder),
                "a placeholder inside the bound must survive byte-identical; got: " + headline);
    }

    @Test
    void commandShapedTitleIsRedactedBeforeTruncation() {
        String headline = DisplayHeadline.of(
                post("/grant-admin 11111111-2222-3333-4444-555555555555", "body"), sanitizer);

        assertTrue(headline.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a command-shaped title must be redacted; got: " + headline);
        assertFalse(headline.contains("/grant-admin"),
                "the raw privileged command must not survive; got: " + headline);
    }

    @Test
    void commandShapedBodyIsRedactedWhenItBecomesTheHeadline() {
        // The body reaches a group-broadcast line start for the first time via
        // this fallback, so it inherits the M1-675 threat the title carries.
        String headline = DisplayHeadline.of(
                post("", "/grant-admin 11111111-2222-3333-4444-555555555555"), sanitizer);

        assertTrue(headline.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a command-shaped body fallback must be redacted too; got: " + headline);
        assertFalse(headline.contains("/grant-admin"),
                "the raw privileged command must not survive the body fallback; got: " + headline);
    }

    @Test
    void legitSlashTitlePassesThroughByteIdentical() {
        String headline = DisplayHeadline.of(post("TCP/IP explained", "body"), sanitizer);

        assertEquals("TCP/IP explained", headline,
                "a non-command slash must not trigger redaction");
    }

    @Test
    void titleBodyOverloadRunsTheIdenticalDerivation() {
        // /saved holds saved_post snapshot columns (Invariant 6), not an
        // EligiblePostQuery.Post, so it enters through the pair overload. If
        // the two entry points could diverge, the shared derivation would stop
        // being shared and /saved would drift from the three surfaces M1-729
        // fixed — which is the whole reason this overload exists. (M1-730.)
        String[][] cases = {
                { "Bitcoin hits $100k", "body text" },
                { IngestTextNormalizer.UNTITLED_TITLE, "The actual post text" },
                { "", "line one\nline two" },
                { "", "/grant-admin 11111111-2222-3333-4444-555555555555" },
                { "x".repeat(DisplayHeadline.MAX_LENGTH + 50), "body" },
                { "TCP/IP explained", "body" },
        };
        for (String[] c : cases) {
            assertEquals(DisplayHeadline.of(post(c[0], c[1]), sanitizer),
                    DisplayHeadline.of(c[0], c[1], sanitizer),
                    "the two entry points must agree for title=" + c[0] + " body=" + c[1]);
        }
    }

    @Test
    void titleBodyOverloadTreatsTheSentinelWithNoBodyAsNoHeadline() {
        // The saved_post shape /saved must survive: `title` is NOT NULL so a
        // titleless save carries the sentinel, and `body` is nullable so it can
        // be absent entirely. The caller then omits the headline token rather
        // than printing the storage placeholder. (M1-730.)
        assertEquals("", DisplayHeadline.of(IngestTextNormalizer.UNTITLED_TITLE, null, sanitizer),
                "sentinel title + null body must yield no headline at all");
        assertEquals("", DisplayHeadline.of(IngestTextNormalizer.UNTITLED_TITLE, "  ", sanitizer),
                "sentinel title + blank body must yield no headline at all");
    }

    @Test
    void anchorFirstSanitizesThePairAsOneUnitAndSplitsTheLinesBack() {
        // The ordinary case: no closed-list match, so the renderer-authored
        // newline survives the sanitize call and both lines come back whole.
        DisplayHeadline.AnchoredHeadline headline = DisplayHeadline.anchorFirst(
                "Bitcoin dosáhl 100 tisíc", "telo", "Bitcoin hits $100k", null, sanitizer);

        assertEquals("Bitcoin hits $100k", headline.readerLine());
        assertEquals("Bitcoin dosáhl 100 tisíc", headline.originalLine());
        assertTrue(headline.anchored(), "an anchor was supplied");
    }

    @Test
    void anchorFirstRedactsAClosedListEntryStraddlingThePair() {
        // Redteam 2026-08-05, medium/INJECTION. Per-line sanitize calls matched
        // neither half of `/list-sources --all` when the command word sat on
        // the anchor and the flag on the original, so the pair shipped
        // dispatchable with no marker and no audit row.
        DisplayHeadline.AnchoredHeadline headline = DisplayHeadline.anchorFirst(
                "--all", "body", "/list-sources", null, sanitizer);

        assertTrue(headline.readerLine().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the straddling entry must redact; got: " + headline.readerLine());
        assertFalse(headline.readerLine().contains("/list-sources"),
                "the command word must not survive; got: " + headline.readerLine());
    }

    @Test
    void aSpanThatSwallowsTheSeparatorCollapsesToOneUnanchoredLine() {
        // The flag-span deletion runs from command word to flag token, so a
        // full-pair match consumes the joining newline itself. The block then
        // reports as UNANCHORED with both lines equal, which is what makes the
        // caller's subordinateFor suppress the duplicate — the redacted text
        // renders once, not twice.
        DisplayHeadline.AnchoredHeadline headline = DisplayHeadline.anchorFirst(
                "--all", "body", "/list-sources", null, sanitizer);

        assertEquals(headline.readerLine(), headline.originalLine(),
                "a collapsed pair reports one line in both slots");
        assertFalse(headline.anchored(),
                "reporting it as anchored would bracket a line that is purely a "
                        + "redaction marker");
        assertEquals("", DisplayHeadline.subordinateFor(
                        headline.readerLine(), headline.originalLine()),
                "the subordinate is suppressed, so the marker is not printed twice");
    }

    @Test
    void feedTextCannotForgeTheSeparatorThatJoinsThePair() {
        // The split back into two lines is only safe because the newline is
        // renderer-authored: every operand goes through flattenToOneLine first,
        // which collapses \n, \r, U+0085, U+2028 and U+2029 to a space. Without
        // that, an anchor carrying its own line break would split into a THIRD
        // line and let feed bytes forge an apparent "publisher's own words"
        // line the publisher never wrote.
        DisplayHeadline.AnchoredHeadline headline = DisplayHeadline.anchorFirst(
                "Original words", "body",
                "Anchor line\nForged original Another forgery", null, sanitizer);

        assertEquals("Anchor line Forged original Another forgery", headline.readerLine(),
                "every feed-authored line break collapses to a space before the join");
        assertEquals("Original words", headline.originalLine(),
                "the real original is still the second line, not a forged one");
    }

    @Test
    void aZeroWidthOnlyAnchorDegradesToTheOriginalRatherThanSuppressingTheHeadline() {
        // Redteam 2026-08-05 round 2, out-of-model. A zero-width-only anchor
        // is NOT blank (those codepoints are not whitespace), so it passes
        // derive's isBlank() guard. It survives while nothing matches, because
        // sanitize returns the caller's own bytes on a no-match — but as soon
        // as a closed-list token matches elsewhere in the pair, sanitize
        // returns the CANONICAL form, where the anchor has been stripped to
        // nothing. Reporting that as the reader line would make
        // AnchoredHeadline.isEmpty() true and drop the surviving original with
        // it, suppressing the whole headline.
        DisplayHeadline.AnchoredHeadline headline = DisplayHeadline.anchorFirst(
                "/promote is discussed here", "body", "​‌", null, sanitizer);

        assertFalse(headline.isEmpty(),
                "the surviving original must not be dropped along with the vanished anchor");
        assertFalse(headline.anchored(),
                "an anchor that canonicalized away is not an anchor");
        assertEquals(headline.readerLine(), headline.originalLine(),
                "the block degrades to the anchor-absent shape");
        assertTrue(headline.readerLine().contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "the redaction that triggered canonicalization still applies; got: "
                        + headline.readerLine());
    }

    @Test
    void aZeroWidthOnlyAnchorIsUntouchedWhenNothingInThePairMatches() {
        // The other half of the same mechanism, pinned so the fix above is not
        // mistaken for unconditional: with no closed-list match the sanitizer
        // returns the input verbatim, so the anchor survives as its own line
        // and the pair stays anchored.
        DisplayHeadline.AnchoredHeadline headline = DisplayHeadline.anchorFirst(
                "Ordinary title", "body", "​‌", null, sanitizer);

        assertTrue(headline.anchored(), "no match, so the anchor is returned untouched");
        assertEquals("Ordinary title", headline.originalLine());
    }

    private static void assertNoUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)),
                        "unpaired high surrogate at index " + i + " in: " + s);
            }
            if (Character.isLowSurrogate(c)) {
                assertTrue(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)),
                        "unpaired low surrogate at index " + i + " in: " + s);
            }
        }
    }

    private static Post post(String title, String body) {
        return new Post(UUID.randomUUID(), "p-1", UUID.randomUUID(), "Source",
                title, "https://example.com/x", body, Instant.now(),
                List.of("crypto"), List.of("unknown"));
    }
}
