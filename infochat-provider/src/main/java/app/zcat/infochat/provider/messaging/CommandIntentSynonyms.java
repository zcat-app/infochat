package app.zcat.infochat.provider.messaging;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic intent → command resolver backing the unknown-command
 * guidance on both {@code /help <unknown>} and the bare unknown-slash
 * path (M1-647). No embeddings, no model call: a static synonym map
 * plus a similarity fallback for typos, so the same query always
 * resolves to the same suggestion.
 *
 * <p><b>Why a synonym map at all.</b> Reaching {@code /help <cmd>}
 * requires already knowing the command's name, and most of the
 * catalogue is not guessable from the first word a user reaches for —
 * "mute" shares no prefix with {@code unfollow-source}. The map closes
 * the vocabulary gap by pointing intent words at existing command
 * names; it renames and aliases nothing.
 *
 * <p><b>Tier safety.</b> {@link #suggest} never sees the catalogue —
 * it ranks over the caller-visible vocabulary its caller passes in.
 * When an intent word resolves to a command absent from that
 * vocabulary the result is empty rather than a fallback near-miss
 * list, so the suggestion path cannot become an existence oracle for
 * a command {@code HelpCommandHandler.visible} hides
 * (docs/spec/commands.md §Permission model).
 *
 * <p><b>The threshold is the point.</b> The predecessor ranked by
 * shared-prefix length alone and always returned the top N, so a query
 * sharing no prefix with anything scored 0 across the board and the
 * alphabetical tie-break confidently offered the first few names.
 * Returning an empty list below {@link #MATCH_THRESHOLD} is what lets
 * the caller answer "no close match" honestly instead.
 *
 * <p>Kept a plain declarative structure rather than logic scattered
 * through the handler so M1-648 can reuse it as the hand-written seed
 * corpus for the semantic intent index.
 */
final class CommandIntentSynonyms {

    /**
     * Minimum similarity a typo candidate must reach to be offered.
     * Calibrated between the two cases that matter: {@code summry} →
     * {@code summary} scores ~0.86 (one edit in seven characters) and
     * must survive, while the nearest neighbours of a genuinely
     * unrelated query — {@code ban} against {@code lang}, three edits
     * apart at best — score ≤0.5 and must not.
     */
    private static final double MATCH_THRESHOLD = 0.6;

    /**
     * Shortest query for which the containment rules below apply. A
     * one- or two-character query is a substring of half the
     * catalogue, so containment there would resurrect exactly the
     * confidently-irrelevant list the threshold exists to kill; short
     * queries fall through to edit distance, which rejects them.
     */
    private static final int MIN_CONTAINMENT_LENGTH = 3;

    /**
     * Natural intent word → catalogue command name. English-only: the
     * command names themselves are English, so a Czech intent
     * vocabulary is a separate follow-up.
     *
     * <p>{@link Map#ofEntries} rejects a duplicate key at class-init,
     * which is the build-time guard against two intents silently
     * claiming the same word.
     */
    private static final Map<String, String> INTENT_TO_COMMAND = Map.ofEntries(
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

            // Bot-admin intent. Present so the tier filter is exercised by a
            // real mapping rather than only in principle: a non-admin typing
            // this must get the no-close-match reply, never the command name.
            Map.entry("makeadmin", "grant-admin"));

    private CommandIntentSynonyms() {
    }

    /**
     * Commands to offer for {@code query}, best first, at most
     * {@code max} of them. Empty means nothing was close enough — the
     * caller must then name no commands at all.
     *
     * @param query             the unrecognized name, without its leading slash
     * @param visibleVocabulary the command names this caller may see; the
     *                          tier filter, applied here so no branch of
     *                          this method can return a hidden name
     */
    static List<String> suggest(String query, List<String> visibleVocabulary, int max) {
        String normalized = query.toLowerCase(Locale.ROOT);

        // Resolve intent FIRST, then filter — never the other way around.
        // An intent word that lands on a hidden command yields nothing and
        // deliberately does NOT fall through to the similarity pass: a
        // near-miss list assembled for a query we could resolve would tell
        // the caller their word meant *something*, which is the leak.
        @Nullable String intentTarget = INTENT_TO_COMMAND.get(normalized);
        if (intentTarget != null) {
            return visibleVocabulary.contains(intentTarget) ? List.of(intentTarget) : List.of();
        }

        record Scored(String name, double score) {}
        List<Scored> scored = new ArrayList<>(visibleVocabulary.size());
        for (String candidate : visibleVocabulary) {
            double score = similarity(normalized, candidate);
            if (score >= MATCH_THRESHOLD) {
                scored.add(new Scored(candidate, score));
            }
        }
        scored.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            return cmp != 0 ? cmp : a.name.compareTo(b.name);
        });

        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, scored.size()); i++) {
            out.add(scored.get(i).name);
        }
        return out;
    }

    /**
     * How close {@code query} is to {@code candidate}, in [0, 1].
     *
     * <p>The containment tiers exist for the hyphenated families the
     * old shared-prefix ranking could not reach at all: {@code source}
     * shares no prefix with {@code add-source}, yet it is plainly what
     * the user means. A whole-token hit outranks a mid-word one so
     * {@code add-source} sorts above {@code list-sources} for the query
     * {@code source}. Anything else falls to edit distance, which
     * carries the plain typo case.
     */
    private static double similarity(String query, String candidate) {
        if (query.equals(candidate)) {
            return 1.0;
        }
        if (query.length() >= MIN_CONTAINMENT_LENGTH) {
            for (String token : candidate.split("-")) {
                if (token.equals(query)) {
                    return 0.9;
                }
            }
            if (candidate.contains(query) || query.contains(candidate)) {
                return 0.8;
            }
        }
        int longer = Math.max(query.length(), candidate.length());
        return 1.0 - (double) editDistance(query, candidate) / longer;
    }

    /** Levenshtein distance, two-row rolling buffer (the full matrix is never needed). */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
