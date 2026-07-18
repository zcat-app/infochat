package app.zcat.infochat.provider.messaging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deterministic intent → command resolver (M1-647).
 *
 * <p>The vocabulary handed to {@link CommandIntentSynonyms#suggest} is
 * the real {@link HelpCommandHandler#CATALOGUE} rather than a
 * hand-written copy, so a command renamed out from under a synonym
 * fails here instead of silently resolving to nothing in production.
 */
class CommandIntentSynonymsTest {

    /** Matches HelpCommandHandler's own suggestion cap. */
    private static final int MAX = 5;

    private static final List<String> FULL_VOCABULARY =
            HelpCommandHandler.CATALOGUE.stream().map(HelpCommandHandler.CommandHelp::command).toList();

    /**
     * Every mapping the ticket requires, as intent word → command. A
     * user reaching for any of these words is reaching for a command
     * whose name shares little or nothing with what they typed.
     */
    private static final Map<String, String> REQUIRED_MAPPINGS = Map.ofEntries(
            Map.entry("mute", "unfollow-source"),
            Map.entry("block", "unfollow-source"),
            Map.entry("hide", "unfollow-source"),
            Map.entry("silence", "unfollow-source"),
            Map.entry("ignore", "unfollow-source"),
            Map.entry("bookmark", "save"),
            Map.entry("keep", "save"),
            Map.entry("star", "save"),
            Map.entry("favorite", "save"),
            Map.entry("bookmarks", "saved"),
            Map.entry("library", "saved"),
            Map.entry("favorites", "saved"),
            Map.entry("subscribe", "add-source"),
            Map.entry("feed", "add-source"),
            Map.entry("rss", "add-source"),
            Map.entry("watch", "add-source"),
            Map.entry("feeds", "list-sources"),
            Map.entry("subscriptions", "list-sources"),
            Map.entry("sources", "list-sources"),
            Map.entry("news", "summary"),
            Map.entry("digest", "summary"),
            Map.entry("catchup", "summary"),
            Map.entry("brief", "summary"),
            Map.entry("recent", "summary"),
            Map.entry("topics", "get-tags"),
            Map.entry("categories", "get-tags"),
            Map.entry("interests", "get-tags"),
            Map.entry("cancel", "stop"),
            Map.entry("abort", "stop"),
            Map.entry("language", "lang"),
            Map.entry("locale", "lang"),
            Map.entry("privacy", "export"),
            Map.entry("data", "export"),
            Map.entry("download", "export"),
            Map.entry("wipe", "clear"),
            Map.entry("reset", "clear"),
            Map.entry("makeadmin", "grant-admin"));

    @Test
    void everyRequiredIntentWordResolvesToItsCommand() {
        for (Map.Entry<String, String> mapping : REQUIRED_MAPPINGS.entrySet()) {
            assertEquals(List.of(mapping.getValue()),
                    CommandIntentSynonyms.suggest(mapping.getKey(), FULL_VOCABULARY, MAX),
                    "intent word '" + mapping.getKey() + "' must resolve to /" + mapping.getValue()
                            + " and to nothing else");
        }
    }

    @Test
    void everyMappedCommandExistsInTheCatalogue() {
        // A synonym pointing at a name no longer in the catalogue would
        // resolve to an empty suggestion in production — silently, because
        // the resolver's contract is "empty means no close match".
        for (String command : REQUIRED_MAPPINGS.values()) {
            assertTrue(FULL_VOCABULARY.contains(command),
                    "synonym target /" + command + " must be a real catalogue command");
        }
    }

    @Test
    void resolutionIsCaseInsensitive() {
        assertEquals(List.of("unfollow-source"),
                CommandIntentSynonyms.suggest("MUTE", FULL_VOCABULARY, MAX));
    }

    @Test
    void intentWordForAHiddenCommandSuggestsNothing() {
        // The security crux: /makeadmin maps to a bot-admin command. Handed a
        // vocabulary that omits it (a non-admin's visible set), the resolver
        // must return nothing rather than fall through to a near-miss list —
        // either outcome that names a command would confirm the mapping
        // resolved to *something* (docs/spec/commands.md §Permission model).
        List<String> nonAdminVocabulary = new ArrayList<>(FULL_VOCABULARY);
        nonAdminVocabulary.remove("grant-admin");

        assertEquals(List.of(),
                CommandIntentSynonyms.suggest("makeadmin", nonAdminVocabulary, MAX),
                "an intent word mapping to a command outside the caller's vocabulary "
                        + "must yield no suggestion at all");
    }

    @Test
    void unrelatedQueryWithNoCloseCandidateSuggestsNothing() {
        // The behaviour the ticket exists to fix: the predecessor scored every
        // candidate 0 for these and returned the alphabetically-first five.
        for (String query : List.of("xyzzy", "qqqq", "zzzzzzzz")) {
            assertEquals(List.of(), CommandIntentSynonyms.suggest(query, FULL_VOCABULARY, MAX),
                    "'" + query + "' matches nothing and must be offered nothing");
        }
    }

    @Test
    void typoResolvesToTheNearestCommand() {
        assertEquals(List.of("summary"),
                CommandIntentSynonyms.suggest("summry", FULL_VOCABULARY, MAX));
    }

    @Test
    void bareTokenReachesTheHyphenatedFamilyWholeTokenFirst() {
        // 'source' shares no prefix with add-source, so the predecessor scored
        // it 0 against the entire -source family.
        List<String> suggestions = CommandIntentSynonyms.suggest("source", FULL_VOCABULARY, MAX);

        assertTrue(suggestions.contains("add-source"),
                "bare 'source' must reach /add-source; got: " + suggestions);
        assertTrue(suggestions.contains("unfollow-source"),
                "bare 'source' must reach /unfollow-source; got: " + suggestions);
        assertEquals("add-source", suggestions.get(0),
                "a whole-token hit must outrank a mid-word one; got: " + suggestions);
    }

    @Test
    void suggestionCountIsCappedAtMax() {
        assertTrue(CommandIntentSynonyms.suggest("source", FULL_VOCABULARY, 2).size() <= 2,
                "the cap must bound the returned list");
    }

    @Test
    void singleCharacterQueryIsTooShortToMatchAnything() {
        // Containment is disabled below three characters precisely so 's' does
        // not resurrect the confidently-irrelevant list.
        assertEquals(List.of(), CommandIntentSynonyms.suggest("s", FULL_VOCABULARY, MAX));
    }
}
