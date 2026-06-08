package app.zcat.infochat.provider.command;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit) test for
 * {@link GroupTimezoneCommandHandler#fuzzySuggestions(String)}'s
 * locale-independent case folding per
 * {@code docs/spec/commands.md} §Surface conventions.
 *
 * <p>Pins that the fuzzy match folds case with {@link Locale#ROOT}, not
 * the default locale: under a Turkish-locale JVM the default
 * {@code toLowerCase()} maps {@code 'I'} to the dotless {@code 'ı'}, so
 * {@code "Europe/Istanbul"} would not fold to contain the ASCII input
 * {@code "istanbul"} and the suggestion would silently break. The test
 * forces the Turkish default locale and asserts the suggestion still
 * resolves, then asserts the result is identical to the {@link
 * Locale#ROOT} run — i.e. the default locale does not influence it.
 */
class GroupTimezoneLocaleFoldTest {

    @Test
    void fuzzySuggestionResolvesUnderTurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            // Baseline under a neutral locale.
            Locale.setDefault(Locale.ROOT);
            String rootResult = GroupTimezoneCommandHandler.fuzzySuggestions("istanbul");

            // The dotless-i locale that breaks a default-locale fold.
            Locale.setDefault(Locale.forLanguageTag("tr"));
            String turkishResult = GroupTimezoneCommandHandler.fuzzySuggestions("istanbul");

            assertTrue(turkishResult.contains("Istanbul"),
                    "fuzzySuggestions(\"istanbul\") must surface an Istanbul zone even "
                            + "under a Turkish-locale JVM — got: " + turkishResult);
            assertEquals(rootResult, turkishResult,
                    "fuzzySuggestions must fold with Locale.ROOT, so the default "
                            + "locale must not change the suggestion set");
        } finally {
            Locale.setDefault(original);
        }
    }
}
