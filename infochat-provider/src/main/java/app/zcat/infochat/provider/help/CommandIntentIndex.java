package app.zcat.infochat.provider.help;

import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

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
 * this invariant; the {@code HelpLookupToolIT} mutation test pins the
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

    /**
     * Tier-filtered intent lookup over {@code doc_embedding}. Returns
     * the matched command name (the nearest visible corpus row whose
     * similarity clears {@code similarityThreshold}) or {@code empty}
     * when no row qualifies. Used by both the model-elected
     * {@code helpLookup} tool path (M1-664) and the deterministic
     * chat-delivery trigger (M1-665), so the two paths share a single
     * SQL shape and cannot drift.
     *
     * <p><b>Match-not-assert.</b> This method returns the matched
     * command NAME only. Description/usage composition is each caller's
     * responsibility: the tool composes the one-line short-help line
     * for the model; the trigger composes the full usage+examples block
     * for delivery. The embedded text never reaches either caller.
     *
     * <p><b>Tier filter BEFORE return.</b> {@code visibleTargets} is
     * bound as {@code target_ref = ANY(?)} INSIDE the WHERE clause —
     * an invisible command's name cannot enter the model's context or
     * the deterministic delivery path. The caller computes the visible
     * set via {@code HelpCommandHandler.visibleCommandNames} and passes
     * it in; this method does not consult the catalogue directly.
     *
     * <p><b>D19 determinism.</b> Which document matches is decided
     * entirely by SQL (pgvector cosine distance + threshold + tier
     * filter), reproducible on unchanged DB state. The caller never
     * picks the match.
     *
     * <p><b>Connection ownership.</b> The caller manages the
     * connection lifecycle (acquire, arm cancellation if applicable,
     * close). This method flips the connection to autocommit-off (a
     * no-op when the caller already opened a transaction, e.g. via
     * {@code CancellationService.armToolConnection}) so its
     * {@code SET LOCAL} arming below joins a transaction that is still
     * open when the probe executes — on an autocommit connection the
     * GUC would expire before the query runs, a silent no-op (M1-660).
     * The probe is read-only, so the transaction is never committed
     * here; it dies (with the GUC) at pool release, exactly like
     * {@code SemanticSearchTool}'s reference shape.
     *
     * @param conn                 an open connection; not closed by this method
     * @param vectorLiteral        the embedded query vector as a pgvector
     *                             text literal {@code "[f0,f1,...]"} (same shape
     *                             {@code SemanticSearchTool} and the tool produce)
     * @param visibleTargets       the caller's visible command-name set,
     *                             bound as {@code ANY(?)} inside the WHERE
     * @param similarityThreshold  minimum similarity (1 − cosine distance)
     *                             for a row to qualify; callers other than
     *                             the tool may pass a conservative higher value
     *
     * @return the matched command name, or {@link Optional#empty()} when
     *         no row clears the threshold or {@code visibleTargets} is empty
     */
    public static Optional<String> lookupCommand(Connection conn,
                                                 String vectorLiteral,
                                                 List<String> visibleTargets,
                                                 double similarityThreshold)
            throws SQLException {
        if (visibleTargets.isEmpty()) {
            return Optional.empty();
        }
        final String sql =
                "SELECT target_ref "
                + "FROM doc_embedding "
                + "WHERE doc_kind = ? "
                + "  AND target_ref = ANY(?) "
                + "  AND (embedding <=> ?::vector) < ? "
                + "ORDER BY (embedding <=> ?::vector) ASC "
                + "LIMIT 1";
        // SET LOCAL is transaction-scoped: on an autocommit connection
        // each statement is its own transaction, so the arming would
        // expire before the probe runs — the silent no-op M1-660 exists
        // to close. Autocommit-off here guarantees the GUC and the query
        // share one transaction on every caller's connection, armed
        // (HelpLookupTool) or bare (ChatAgent.lookupIntentForDelivery).
        conn.setAutoCommit(false);
        enableIterativeScan(conn);
        double distanceThreshold = 1.0 - similarityThreshold;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DOC_KIND);
            ps.setArray(2, conn.createArrayOf("text", visibleTargets.toArray()));
            ps.setString(3, vectorLiteral);
            ps.setDouble(4, distanceThreshold);
            ps.setString(5, vectorLiteral);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("target_ref")) : Optional.empty();
            }
        }
    }

    // pgvector iterative index scan (>= 0.8): the HNSW scan keeps walking
    // until LIMIT rows survive the query's OTHER predicates, so the
    // doc_kind + tier filters can sit INSIDE the index-driven probe —
    // recall is exact over the caller-visible command set regardless of
    // how many rows of OTHER doc_kinds (M1-649 topics) crowd the
    // ef_search window. Without it the probe stops after ef_search
    // candidates and silently under-recalls the moment a second corpus
    // shares idx_doc_embedding_hnsw — the failure arrives with the DATA,
    // not a code change. strict_order (not relaxed_order) keeps the
    // emitted order exactly distance-ascending, which D19's "same DB
    // state + same message -> same set/order" needs. Mirror of
    // SemanticSearchTool.enableIterativeScan (M1-589 lineage).
    private static void enableIterativeScan(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET LOCAL hnsw.iterative_scan = strict_order");
        }
    }

    /**
     * pgvector text literal {@code [f0,f1,...]}, bound via setString
     * through a {@code ?::vector} cast. Mirrors the literal-building
     * convention {@code SemanticSearchTool} and the {@code helpLookup}
     * tool use; lifted here so the deterministic delivery trigger (M1-665)
     * shares the exact same formatting as the model-elected tool path.
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
