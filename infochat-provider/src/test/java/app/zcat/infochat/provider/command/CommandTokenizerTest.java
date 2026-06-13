package app.zcat.infochat.provider.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the quote/whitespace behavior of {@link CommandTokenizer} against
 * representative inputs from all four former per-handler copies
 * (InviteCommandHandler, AuditCommandHandler, BanCommandHandler,
 * AddSourceArgs). The copies were byte-identical when unified; these cases
 * make any future silent divergence — or a regression in the shared
 * version — fail loudly.
 */
class CommandTokenizerTest {

    @Test
    void emptyInputYieldsEmptyList() {
        assertEquals(List.of(), CommandTokenizer.tokenize(""));
    }

    @Test
    void blankInputYieldsEmptyList() {
        assertEquals(List.of(), CommandTokenizer.tokenize("    "));
    }

    @Test
    void splitsOnSingleSpaces() {
        assertEquals(List.of("a", "b", "c"), CommandTokenizer.tokenize("a b c"));
    }

    @Test
    void collapsesRepeatedWhitespaceWithoutEmptyTokens() {
        assertEquals(List.of("a", "b"), CommandTokenizer.tokenize("a   b"));
    }

    @Test
    void leadingAndTrailingWhitespaceProduceNoEmptyTokens() {
        assertEquals(List.of("a", "b"), CommandTokenizer.tokenize("   a b   "));
    }

    @Test
    void tabIsWhitespaceSeparator() {
        assertEquals(List.of("a", "b"), CommandTokenizer.tokenize("a\tb"));
    }

    @Test
    void quotedValueKeepsInternalSpacesAsOneToken() {
        // AddSourceArgs: /add-source <url> --name "Display Name With Spaces"
        assertEquals(
            List.of("--name", "Display Name With Spaces"),
            CommandTokenizer.tokenize("--name \"Display Name With Spaces\""));
    }

    @Test
    void quoteCharactersAreStrippedFromToken() {
        assertEquals(List.of("plain"), CommandTokenizer.tokenize("\"plain\""));
    }

    @Test
    void unbalancedQuoteRunsToEndOfInput() {
        assertEquals(List.of("a", "b c"), CommandTokenizer.tokenize("a \"b c"));
    }

    @Test
    void quoteCanToggleMidToken() {
        assertEquals(List.of("ab cd"), CommandTokenizer.tokenize("a\"b c\"d"));
    }

    @Test
    void banReasonQuotedPhraseStaysOneToken() {
        // BanCommandHandler: /ban <contact> --reason "spam and abuse"
        assertEquals(
            List.of("contact-id", "--reason", "spam and abuse"),
            CommandTokenizer.tokenize("contact-id --reason \"spam and abuse\""));
    }

    @Test
    void auditFlagsTokenizeAsPlainTokens() {
        // AuditCommandHandler: /audit --actor X --action Y --page N
        assertEquals(
            List.of("--actor", "alice", "--action", "ban", "--page", "2"),
            CommandTokenizer.tokenize("--actor alice --action ban --page 2"));
    }

    @Test
    void inviteNoteQuotedStaysOneToken() {
        // InviteCommandHandler: /invite create --note "for the press team"
        assertEquals(
            List.of("create", "--note", "for the press team"),
            CommandTokenizer.tokenize("create --note \"for the press team\""));
    }
}
