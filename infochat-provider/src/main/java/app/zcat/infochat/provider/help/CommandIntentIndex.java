package app.zcat.infochat.provider.help;

import org.jspecify.annotations.Nullable;

/**
 * Read-side contract for the chat-mode command-intent index (M1-664).
 *
 * <p>The index itself lives in the {@code doc_embedding} table (V60);
 * this class holds no in-memory state at runtime. What it does hold:
 * <ul>
 *   <li>the {@code doc_kind} constant that scopes every {@code doc_embedding}
 *       read + write to this corpus (so a future USER_GUIDE-topics corpus
 *       per M1-649 can share the table without cross-matching);</li>
 *   <li>the {@link LookupResult} shape the {@code HelpLookupTool} returns
 *       to the LLM — a matched command name plus the runtime catalogue's
 *       one-line description, or {@link #empty()} below threshold.</li>
 * </ul>
 *
 * <p><b>Match-not-assert invariant.</b> The embedded intent document is
 * a matching surface only — its text never appears in {@link LookupResult}.
 * The {@code description} field is composed at call time from the runtime
 * {@code HelpCommandHandler.CATALOGUE} (a {@code provider.messaging} type
 * the builder and the tool both consult read-only), so a stale or
 * attacker-edited intent row can degrade a match but can never produce
 * wrong syntax. {@code CommandIntentIndexTest} pins the runtime-side of
 * this invariant; the {@code HelpLookupToolTest} mutation test pins the
 * full-path version.
 *
 * <p>This is the second embedded corpus; the first is the post-embedding
 * store (V11, Collector-written). One Postgres, one pgvector, one
 * embedding model — both corpora share the dimension pinned by
 * {@code embedding_metadata}'s singleton (D54). See decision D66 for
 * the boundary.
 */
public final class CommandIntentIndex {

    /** Corpus discriminator on every {@code doc_embedding} read + write. */
    public static final String DOC_KIND = "command_intent";

    private CommandIntentIndex() {
    }

    /**
     * Matched command name + the runtime catalogue's one-line
     * description, or empty (no match above threshold).
     *
     * @param command     the matched command name (e.g.
     *                    {@code "unfollow-source"}); {@code null} on no
     *                    match
     * @param description the runtime catalogue's short-help line,
     *                    resolved at call time from the matched
     *                    command's {@code bundleKey}; {@code null} on
     *                    no match
     */
    public record LookupResult(@Nullable String command, @Nullable String description) {

        /** Whether this result carries a match. */
        public boolean isPresent() {
            return command != null;
        }
    }

    /** Sentinel for "no command matched above threshold". */
    public static LookupResult empty() {
        return new LookupResult(null, null);
    }
}
