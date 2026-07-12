package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.ChatToolRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Digest-first semantic retrieval (M1-589): embeds the free-text query on
// the LOCAL embedding backend (D54 — embeddings never leave the
// deployment) and runs a pgvector nearest-neighbour probe over
// post_embedding, scoped to the caller's subscribed sources. The
// candidate set and its order are decided entirely by SQL
// (ORDER BY embedding <=> query — D19); the LLM never picks the set.
@ApplicationScoped
public class SemanticSearchTool implements ChatToolRegistry.ChatTool {

    /**
     * Aggregate byte budget for the returned JSON array, measured in
     * UTF-8 bytes. Tool results are reinjected verbatim into the chat
     * prompt (LLM tool-call outputs are a trust boundary), so a large
     * result set would otherwise consume the context window. Mirrors
     * {@link SearchPostsTool#MAX_RESULT_BYTES}: entries past the budget
     * are dropped, nearest-first (the ORDER BY) ordering kept.
     */
    static final int MAX_RESULT_BYTES = 16 * 1024;

    private final DataSource dataSource;
    private final CancellationService cancellationService;
    private final EmbeddingProvider embeddingProvider;
    private final double distanceThreshold;
    private final int defaultLimit;

    @Inject
    public SemanticSearchTool(DataSource dataSource,
                              CancellationService cancellationService,
                              EmbeddingProvider embeddingProvider,
                              // defaultValue duplicates the explicit key in
                              // application.properties; the two must not drift.
                              @ConfigProperty(name = "infochat.chat.semantic-threshold",
                                      defaultValue = "0.40") double distanceThreshold,
                              @ConfigProperty(name = "infochat.chat.semantic-limit",
                                      defaultValue = "8") int defaultLimit) {
        this.dataSource = dataSource;
        this.cancellationService = cancellationService;
        this.embeddingProvider = embeddingProvider;
        this.distanceThreshold = distanceThreshold;
        this.defaultLimit = defaultLimit;
    }

    @Override
    public String execute(UUID userId, String scopeKind,
                          UUID scopeId, Map<String, Object> args)
            throws SQLException {
        String query = args.containsKey("query") ? (String) args.get("query") : "";
        if (query.isBlank()) {
            throw new IllegalArgumentException("Missing query");
        }
        int limit = args.containsKey("limit")
                ? ((Number) args.get("limit")).intValue() : defaultLimit;

        // Embed BEFORE acquiring the pooled connection: the embed call is
        // an HTTP round-trip to the local backend and must not hold a
        // pool slot for its duration.
        float[] queryVector = embeddingProvider.embed(List.of(query)).get(0).vector();
        String vectorLiteral = toVectorLiteral(queryVector);

        try (Connection conn = dataSource.getConnection()) {
            cancellationService.armToolConnection(conn, userId, scopeKind, scopeId);
            enableIterativeScan(conn);
            return queryNearestPosts(conn, scopeKind, scopeId, vectorLiteral, limit);
        }
    }

    // pgvector iterative index scan (>= 0.8): the HNSW scan keeps walking
    // until LIMIT rows survive the query's OTHER predicates (bounded by
    // hnsw.max_scan_tuples, default 20k), so the subscription/READY/
    // threshold filters can sit INSIDE the index-driven query — retrieval
    // is exact over the caller-visible corpus instead of a post-filtered
    // global top-k whose recall would both degrade for small subscription
    // sets and leak unsubscribed-content density through recall shrinkage
    // (redteam M1-589 2026-07-11). strict_order (not relaxed_order) keeps
    // the emitted order exactly distance-ascending — D19's "same DB state
    // + same message -> same set/order" needs it. SET LOCAL joins the
    // transaction armToolConnection already opened for its own SET LOCAL
    // statement_timeout, and dies with it at pool release — never leaking
    // the GUC to other borrowers.
    private static void enableIterativeScan(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("SET LOCAL hnsw.iterative_scan = strict_order");
        }
    }

    private String queryNearestPosts(Connection conn, String scopeKind, UUID scopeId,
                                     String vectorLiteral, int limit) throws SQLException {
        // Single filtered probe: the distance-only ORDER BY + LIMIT drives
        // the HNSW index; the iterative scan (armed above) evaluates the
        // subscription predicate (SearchPostsTool's — a post outside the
        // (user, scope)'s subscribed sources can never surface), READY, and
        // the relevance threshold per candidate, walking deeper until LIMIT
        // survivors. The outer re-sort adds the deterministic post_id
        // tie-break without disturbing the index-driven inner ORDER BY.
        final String sql =
            "SELECT uid, title, url, distance "
                + "  FROM ( "
                + "    SELECT p.uid, p.title, p.url, pe.post_id AS post_id, "
                + "           (pe.embedding <=> ?::vector) AS distance "
                + "      FROM post_embedding pe "
                + "      JOIN post p ON p.id = pe.post_id "
                + "     WHERE p.status = 'READY' "
                + "       AND p.source_id IN (SELECT source_id FROM source_subscription "
                + "           WHERE scope_kind = ? AND scope_id = ?) "
                + "       AND (pe.embedding <=> ?::vector) < ? "
                + "     ORDER BY pe.embedding <=> ?::vector "
                + "     LIMIT ? "
                + "  ) hits "
                + " ORDER BY distance ASC, post_id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vectorLiteral);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, vectorLiteral);
            ps.setDouble(5, distanceThreshold);
            ps.setString(6, vectorLiteral);
            ps.setInt(7, limit);
            try (ResultSet rs = ps.executeQuery()) {
                // '[' + ']' — every appended entry adds its own bytes (plus
                // a joining comma) against MAX_RESULT_BYTES, exactly as
                // SearchPostsTool bounds its own aggregate. similarity is
                // 1 - distance (LinkingJob's computation) — a display value
                // only; the raw embedding vector is never emitted (D5).
                StringBuilder json = new StringBuilder("[");
                int budgetUsed = 2;
                boolean first = true;
                while (rs.next()) {
                    float similarity = (float) (1.0 - rs.getDouble("distance"));
                    StringBuilder entry = new StringBuilder();
                    entry.append("{\"uid\":").append(SearchPostsTool.jsonStr(rs.getString("uid")))
                         .append(",\"title\":").append(SearchPostsTool.jsonStr(rs.getString("title")))
                         .append(",\"url\":").append(SearchPostsTool.jsonStr(rs.getString("url")))
                         .append(",\"similarity\":").append(similarity)
                         .append('}');
                    int entryBytes = entry.toString()
                            .getBytes(StandardCharsets.UTF_8).length + (first ? 0 : 1);
                    if (budgetUsed + entryBytes > MAX_RESULT_BYTES) break;
                    budgetUsed += entryBytes;
                    if (!first) json.append(',');
                    first = false;
                    json.append(entry);
                }
                json.append(']');
                return json.toString();
            }
        }
    }

    // pgvector text literal [f0,f1,...], bound via setString through a
    // ?::vector cast — LinkingJob's binding shape, except LinkingJob reads
    // its literal from the DB (embedding::text) while this one is built
    // from the fresh query embedding. The dimension is whatever the local
    // embedder returned — never hardcoded — because the query vector and
    // the stored column share the same local backend by construction (D54).
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
