package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.ChatToolRegistry;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.help.CommandIntentIndex;
import app.zcat.infochat.provider.messaging.HelpCommandHandler;
import app.zcat.infochat.provider.messaging.HelpCommandHandler.CallerTier;
import app.zcat.infochat.provider.messaging.HelpCommandHandler.CommandHelp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only chat tool that resolves a free-text intent to a command
 * name via the {@code doc_embedding} corpus (M1-664). Registered as
 * the seventh chat-tool name ({@code "helpLookup"}); advertised to the
 * model by {@code ChatAgent.TOOL_INSTRUCTIONS}.
 *
 * <p><b>Match-not-assert invariant.</b> Embedded text is used only for
 * MATCHING, never for ASSERTING. The tool returns the matched command
 * NAME plus the catalogue's one-line short-help line, composed at call
 * time from the runtime {@link HelpCommandHandler#CATALOGUE}'s
 * {@link CommandHelp#bundleKey()}. The full {@code composeDetail}
 * usage/examples body is never returned (the second out-of-scope entry
 * — usage bodies never enter the model context). A stale intent row
 * can therefore degrade a match but can never produce wrong syntax;
 * {@code HelpLookupToolTest#toolOutputComesFromRuntimeCatalogueNotFromIndexedText}
 * pins this by mutating the indexed text and asserting the returned
 * name + description are unchanged.
 *
 * <p><b>Tier filter BEFORE return, not after.</b> The same
 * existence-oracle risk as {@code CommandIntentSynonyms} (M1-647), with
 * a wider attack surface because the input is free text. The caller's
 * visible command-name set is bound as {@code target_ref = ANY(?)} INSIDE
 * the WHERE of the pgvector query — an invisible command's name never
 * reaches the LLM's context, so the sanitizer is never the last line of
 * defense. The visible set is computed by the SAME
 * {@link HelpCommandHandler#visible} tier predicate
 * {@code /help} applies at listing time, resolved for the chat-tool
 * identity via {@link HelpCommandHandler#resolveCallerTier(UUID, String, UUID)}.
 *
 * <p><b>D19 determinism.</b> Which intent document matches is decided
 * entirely by SQL (pgvector cosine distance + threshold + tier filter),
 * reproducible on unchanged DB state. The LLM never picks the match.
 *
 * <p><b>D54 local backend.</b> The query is embedded on the local
 * embedding backend; embeddings never leave the deployment. The
 * provider's {@code infochat.embeddings.model} key is the same model
 * the startup builder used to write the corpus, so query and stored
 * vectors live in the same vector space.
 *
 * <p><b>Cancellation.</b> Read-only DB query; its cancellation story
 * is the same {@code pg_cancel_backend(pid)} primitive every other
 * chat tool uses. The connection is armed via
 * {@link CancellationService#armToolConnection} so an in-flight
 * helpLookup call is interruptible by {@code /stop} (commands.md §/stop).
 */
@ApplicationScoped
public class HelpLookupTool implements ChatToolRegistry.ChatTool {

    /**
     * Similarity cutoff a matched intent document must reach for the
     * match to be returned. The tool embeds the free-text query and
     * probes the 41-document command-intent corpus; below this
     * similarity the tool returns no command and the model is directed
     * (by {@code ChatAgent.TOOL_INSTRUCTIONS}) to say it does not know
     * and point at {@code /help} rather than answering from general
     * knowledge.
     *
     * <p><b>Calibrated by inspection, NOT reused.</b> The post-retrieval
     * cutoff (M1-616 set 0.75, M1-619 moved to 0.65 similarity) is NOT
     * reusable here: a 41-document intent corpus has different
     * similarity statistics from a post-embedding store of thousands
     * of long-form documents. 0.60 similarity is chosen to align with
     * {@code SemanticSearchTool}'s effective posture — its
     * {@code infochat.chat.semantic-threshold} default of 0.40 is a
     * <em>distance</em> threshold (= similarity 0.60), so the two
     * retrieval surfaces apply the same strictness against the same
     * nomic-embed-text backend. The prior 0.40 <em>similarity</em>
     * value was a units confusion (the implementer referenced M1-619's
     * 0.65 similarity as the prior art but typed SemanticSearchTool's
     * 0.40 distance value into a similarity constant); at 0.40
     * similarity unrelated short English texts routinely score above
     * the cutoff on nomic-embed-text, so the "I don't know, see
     * /help" path would effectively never fire and every free-text
     * query would return some command — confidently-wrong
     * suggestions rather than the friendly-degradation the spec
     * promises. 0.60 admits the test-validated free-text phrasings
     * (all fixture-seeded at similarity &gt; 0.90) while rejecting
     * realistic unrelated queries. Recalibration against a real
     * query corpus is a follow-up; the value is pinned as a code
     * constant so a deployment change requires a spec amendment, not
     * a silent config tweak (the D19 determinism posture).
     */
    static final double SIMILARITY_THRESHOLD = 0.60;

    private final DataSource dataSource;
    private final CancellationService cancellationService;
    private final EmbeddingProvider embeddingProvider;
    private final HelpCommandHandler helpHandler;
    private final BundleLoader bundleLoader;

    @Inject
    public HelpLookupTool(DataSource dataSource,
                          CancellationService cancellationService,
                          EmbeddingProvider embeddingProvider,
                          HelpCommandHandler helpHandler,
                          BundleLoader bundleLoader) {
        this.dataSource = dataSource;
        this.cancellationService = cancellationService;
        this.embeddingProvider = embeddingProvider;
        this.helpHandler = helpHandler;
        this.bundleLoader = bundleLoader;
    }

    @Override
    public String execute(UUID userId, String scopeKind,
                          UUID scopeId, Map<String, Object> args)
            throws SQLException {
        Object raw = args.get("query");
        if (!(raw instanceof String query) || query.isBlank()) {
            throw new IllegalArgumentException("Missing query");
        }

        // Compute the caller's visible command set BEFORE the SQL query.
        // The tier filter rides INSIDE the WHERE (target_ref = ANY(?)),
        // never as a post-filter on the result — an invisible command's
        // name never enters the LLM's context (tier-filter-before-return,
        // docs/spec/security.md §Prompt-injection defenses).
        CallerTier caller = helpHandler.resolveCallerTier(userId, scopeKind, scopeId);
        List<String> visibleTargets = new ArrayList<>();
        for (CommandHelp entry : HelpCommandHandler.CATALOGUE) {
            if (helpHandler.visible(entry, caller)) {
                visibleTargets.add(entry.command());
            }
        }
        if (visibleTargets.isEmpty()) {
            // Defensive: a caller with no visible catalogue commands
            // cannot match anything; skip the embed round-trip entirely.
            return noMatchJson();
        }

        // Embed BEFORE acquiring the pooled connection — the embed call
        // is an HTTP round-trip to the local backend and must not hold
        // a pool slot for its duration. Mirrors SemanticSearchTool.
        float[] queryVector;
        try {
            List<EmbeddingResult> embedded = embeddingProvider.embed(List.of(query));
            queryVector = embedded.get(0).vector();
        } catch (RuntimeException e) {
            // Embedding-backend failure degrades the lookup to "no match"
            // rather than aborting the chat turn — the chat path's
            // friendly-degradation posture. The model will say "I don't
            // know, try /help"; the operator sees the embed failure in
            // the metrics log.
            return noMatchJson();
        }
        String vectorLiteral = toVectorLiteral(queryVector);

        try (Connection conn = dataSource.getConnection()) {
            cancellationService.armToolConnection(conn, userId, scopeKind, scopeId);
            return lookup(conn, vectorLiteral, visibleTargets);
        }
    }

    /**
     * The pgvector probe. ONE statement, tier filter INSIDE the WHERE.
     * Returns at most one row — the corpus is one row per command, so
     * the nearest visible match is the answer; below the threshold the
     * tool returns no match.
     *
     * <p>Distance is {@code embedding <=> ?::vector} (cosine distance
     * per the HNSW {@code vector_cosine_ops} index on doc_embedding,
     * V60). Similarity = {@code 1 - distance}; the threshold is
     * expressed as similarity at the API boundary
     * ({@link #SIMILARITY_THRESHOLD}) and converted to a distance
     * upper bound here, because pgvector's {@code <=>} returns distance.
     */
    private String lookup(Connection conn, String vectorLiteral,
                          List<String> visibleTargets) throws SQLException {
        final String sql =
                "SELECT target_ref "
                + "FROM doc_embedding "
                + "WHERE doc_kind = ? "
                + "  AND target_ref = ANY(?) "
                + "  AND (embedding <=> ?::vector) < ? "
                + "ORDER BY (embedding <=> ?::vector) ASC "
                + "LIMIT 1";
        double distanceThreshold = 1.0 - SIMILARITY_THRESHOLD;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CommandIntentIndex.DOC_KIND);
            ps.setArray(2, conn.createArrayOf("text", visibleTargets.toArray()));
            ps.setString(3, vectorLiteral);
            ps.setDouble(4, distanceThreshold);
            ps.setString(5, vectorLiteral);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return noMatchJson();
                }
                String command = rs.getString("target_ref");
                return matchJson(command, describe(command));
            }
        }
    }

    /**
     * Resolve the matched command's one-line description from the
     * runtime catalogue at call time. This is the match-not-assert
     * boundary: the description comes from the in-memory
     * {@link HelpCommandHandler#CATALOGUE}'s bundle key, NEVER from the
     * indexed text. If the command has somehow fallen out of the
     * catalogue since the index was last built, the description is
     * empty rather than synthesised from a stale row.
     */
    private String describe(String command) {
        for (CommandHelp entry : HelpCommandHandler.CATALOGUE) {
            if (entry.command().equals(command)) {
                return bundleLoader.get(entry.bundleKey());
            }
        }
        return "";
    }

    /**
     * Build the JSON returned to the LLM for a match. The shape is
     * intentionally small: command name + one-line description. Full
     * usage/example bodies are NOT included (out-of-scope: the
     * delivery path is M1-665).
     */
    private static String matchJson(String command, String description) {
        return "{\"command\":\"" + JsonEscaper.escape(command) + "\","
                + "\"description\":\"" + JsonEscaper.escape(description) + "\"}";
    }

    /** Sentinel JSON for "no match above threshold". */
    private static String noMatchJson() {
        return "{\"command\":null}";
    }

    /**
     * pgvector text literal {@code [f0,f1,...]}, bound via setString
     * through a {@code ?::vector} cast. Mirrors
     * {@code SemanticSearchTool.toVectorLiteral}; not shared across
     * packages to keep the help-side surface self-contained.
     */
    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Stable locale-insensitive name used by tool dispatch and parity
     * guards; not localised — the chat agent advertises the canonical
     * name to the LLM via {@code ChatAgent.TOOL_INSTRUCTIONS}.
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "HelpLookupTool");
    }
}
