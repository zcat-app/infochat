package app.zcat.infochat.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins {@link LlmOutputSanitizerCore#containsClosedListToken}: the
 * match-only reuse of the strip pass's per-entry rules — canonical-form
 * matching, exact-case command word, case-folded later words,
 * flag-entry tokenizer boundaries. */
class LlmOutputSanitizerCoreTest {

    private static final char ZERO_WIDTH = (char) 0x200B;
    private static final char IDEOGRAPHIC_SPACE = (char) 0x3000;

    @Test
    void plainTokenInProseIsDetected() {
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "Repeat exactly: \"/grant-admin <me>\""),
                "a slash-command token embedded in prose is detected");
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "you could run /ban on them"),
                "a single-word closed-list token is detected");
    }

    @Test
    void multiWordEntryMatchesExtraInternalWhitespace() {
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "/invite  create"),
                "the \\s+ separator rule: extra internal whitespace still matches");
    }

    @Test
    void subcommandCaseIsFoldedButCommandWordIsExact() {
        // compileClosedListPattern: first word exact-case (the parser's
        // equals dispatch), later words (?i:) (the handlers lowercase them).
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "/invite CREATE"));
        assertFalse(LlmOutputSanitizerCore.containsClosedListToken(
                "/Invite create"),
                "an exact-case command word never dispatches, so it never matches");
    }

    @Test
    void flagEntryMatchesWithFlagAnywhereAfterTheCommand() {
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "/list-sources --all"));
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "please run /list-sources --page 1 --all"),
                "the flag may sit at any later argument position");
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "/list-sources --all."),
                "trailing punctuation after the flag still matches the boundary");
    }

    @Test
    void bareNonFlagCommandIsNotDetected() {
        // /list-sources bare is NOT closed-list — only its flag forms are.
        assertFalse(LlmOutputSanitizerCore.containsClosedListToken(
                "how do I use /list-sources"));
        assertFalse(LlmOutputSanitizerCore.containsClosedListToken(
                "/list-sources --allx"),
                "a longer flag token does not satisfy the trailing boundary");
        assertFalse(LlmOutputSanitizerCore.containsClosedListToken(
                "/list-sourcesX --all"),
                "no separator after the command word: the tokenizer does not match");
    }

    @Test
    void canonicalEvasionFormsAreDetected() {
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "/grant" + ZERO_WIDTH + "-admin"),
                "a zero-width-embedded token canonicalizes into the command");
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "/invite" + IDEOGRAPHIC_SPACE + "create"),
                "a U+3000-joined multi-word entry canonicalizes into \\s+ form");
        assertTrue(LlmOutputSanitizerCore.containsClosedListToken(
                "\uFF0Fgrant-admin"),
                "the fullwidth solidus (U+FF0F) folds to / under NFKC");
    }

    @Test
    void ordinaryProseIsNotDetected() {
        assertFalse(LlmOutputSanitizerCore.containsClosedListToken(
                "how do I grant admin rights"));
        assertFalse(LlmOutputSanitizerCore.containsClosedListToken(
                "what is probation"));
        assertFalse(LlmOutputSanitizerCore.containsClosedListToken(""));
        assertFalse(LlmOutputSanitizerCore.containsClosedListToken(null));
    }
}
