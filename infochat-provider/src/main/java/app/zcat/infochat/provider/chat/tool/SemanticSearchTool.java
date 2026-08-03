package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.ChatToolRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Hybrid semantic + lexical retrieval (M1-589 semantic arm; M1-617 lexical
// arm + RRF fusion, D58): embeds the free-text query on the LOCAL embedding
// backend (D54 — embeddings never leave the deployment) and runs ONE fused
// SQL statement joining a pgvector nearest-neighbour probe over
// post_embedding with a full-text probe over post.search_tsv (V58), fused
// by Reciprocal Rank Fusion. Both arms carry the READY + D59 world
// (implicit-bootstrap-not-excluded OR subscribed) predicates INSIDE the
// arm, and the fused set and its order are decided entirely by SQL (D19);
// the LLM never picks the set. The query text reaching both arms is
// anchored to the corpus language (English, D29) when the scope declares
// a non-English /lang (D58 bounded exception, M1-746): the anchored
// string is what gets embedded AND what plainto_tsquery receives, so the
// arms always see the same text. The lexical arm recovers keyword-exact
// posts (CVE ids, product names) whose embeddings fall outside the
// semantic threshold.
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

    /**
     * Reciprocal Rank Fusion constant: fused score = Σ 1/(k + rank_arm).
     * k=60 is the standard from the original RRF paper (Cormack et al.
     * 2009); it damps the head-of-list dominance so a post ranked well by
     * BOTH arms outranks a post ranked first by one arm only. A fixed code
     * constant (not config) because varying it would silently change the
     * retrieved set across deployments — the D19 reproducibility story is
     * simplest when the only retrieval inputs are DB state, query, and the
     * calibrated threshold/limit properties.
     */
    static final int RRF_K = 60;

    // The scope's declared language (D58 (c)): identical SQL to
    // InboundRouter's lookup — a missing row means 'en' (D43).
    private static final String SELECT_SCOPE_LANGUAGE_SQL =
            "SELECT language FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";

    private static final Logger LOG = LoggerFactory.getLogger(SemanticSearchTool.class);

    private final DataSource dataSource;
    private final CancellationService cancellationService;
    private final EmbeddingProvider embeddingProvider;
    private final QueryAnchorTranslator queryAnchorTranslator;
    private final double distanceThreshold;
    private final int defaultLimit;

    @Inject
    public SemanticSearchTool(DataSource dataSource,
                              CancellationService cancellationService,
                              EmbeddingProvider embeddingProvider,
                              QueryAnchorTranslator queryAnchorTranslator,
                              // defaultValue duplicates the explicit key in
                              // application.properties; the two must not drift.
                              @ConfigProperty(name = "infochat.chat.semantic-threshold",
                                      defaultValue = "0.40") double distanceThreshold,
                              @ConfigProperty(name = "infochat.chat.semantic-limit",
                                      defaultValue = "8") int defaultLimit) {
        this.dataSource = dataSource;
        this.cancellationService = cancellationService;
        this.embeddingProvider = embeddingProvider;
        this.queryAnchorTranslator = queryAnchorTranslator;
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

        // D58 (c) DECLARED: the source language is the scope's declared
        // /lang (scope_preferences.language, defaulting to 'en' for a
        // missing row per D43) — never inferred from the query text. This
        // is a quick indexed SELECT on a short acquisition: it must NOT
        // run on the main pooled connection, because the translation and
        // embed calls below are HTTP round-trips that must not hold a
        // pool slot.
        String scopeLanguage = lookupScopeLanguage(scopeKind, scopeId);

        // D58: anchor the query to the corpus language (English, D29).
        // An en-declared scope is a strict no-op (byte-identical to
        // today, no translator call); a non-English scope yields ONE
        // translated string that BOTH arms consume — it is what gets
        // embedded and what plainto_tsquery receives, so the two arms
        // always see the same text. Runs before the pooled connection is
        // acquired: the translation call is an HTTP round-trip (same
        // discipline as the embed call below). The scope travels with
        // the call so the translation cache is scope-partitioned (R2).
        String anchoredQuery = queryAnchorTranslator.translate(
                query, scopeLanguage, scopeKind, scopeId);

        // Embed BEFORE acquiring the pooled connection: the embed call is
        // an HTTP round-trip to the local backend and must not hold a
        // pool slot for its duration.
        float[] queryVector = embeddingProvider.embed(List.of(anchoredQuery)).get(0).vector();
        String vectorLiteral = toVectorLiteral(queryVector);

        try (Connection conn = dataSource.getConnection()) {
            cancellationService.armToolConnection(conn, userId, scopeKind, scopeId);
            enableIterativeScan(conn);
            return queryFusedPosts(conn, scopeKind, scopeId, vectorLiteral, anchoredQuery, limit);
        }
    }

    // The scope's declared language — the same SQL and the same
    // missing-row default ('en', D43) as
    // InboundRouter.lookupScopeLanguage. That method cannot be reused
    // here (package-private, bound to the inbound router's per-dispatch
    // connection), so the lookup is replicated with identical semantics.
    // A lookup FAILURE degrades to 'en' — the pre-M1-746 behaviour — and
    // logs: an unreadable language must not fail the search. The query
    // then reaches both arms exactly as it did before this ticket, which
    // is the fallback-direction decision of the ticket applied to its
    // own pre-flight (degraded retrieval beats no retrieval); nothing
    // isolation-relevant depends on the value.
    private String lookupScopeLanguage(String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SCOPE_LANGUAGE_SQL)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "en";
                }
                return rs.getString("language");
            }
        } catch (SQLException e) {
            SafeLog.warn(LOG, "SemanticSearchTool.lookupScopeLanguage failed for scope_kind="
                    + scopeKind + " scope_id=" + scopeId
                    + "; degrading to 'en' (pre-M1-746 behaviour)", e);
            return "en";
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

    private String queryFusedPosts(Connection conn, String scopeKind, UUID scopeId,
                                   String vectorLiteral, String query, int limit)
            throws SQLException {
        // ONE fused statement, two arms, both fully filtered BEFORE their
        // LIMIT — the isolation predicates (READY + the shared D59 world
        // predicate, SearchPostsTool.worldPredicateSql) sit INSIDE each arm
        // so no over-fetch-then-filter path can leak a post outside the
        // caller's world (redteam M1-589 2026-07-11 leak class).
        //
        // Semantic arm: unchanged filtered HNSW probe — the distance-only
        // ORDER BY + LIMIT drives the index; the iterative scan (armed
        // above) evaluates the predicates per candidate, walking deeper
        // until LIMIT survivors.
        //
        // Lexical arm (M1-617): full-text probe over the V58 generated
        // column; the model-supplied query text reaches plainto_tsquery
        // ONLY as a bind parameter, with the regconfig pinned to 'english'
        // (matching the stored column — a mismatched or GUC-derived config
        // would silently miss the GIN index and make results
        // session-dependent).
        //
        // Fusion is Reciprocal Rank Fusion computed in SQL: each arm's
        // rank comes from ROW_NUMBER() over an explicit total order
        // (distance/ts_rank, tie-broken by post_id — never input row
        // order), and the outer ORDER BY (fused_score, post_id) is total
        // too, so same DB state -> same set, same order (D19).
        final String sql =
            "SELECT uid, title, url, distance "
                + "  FROM ( "
                + "    SELECT COALESCE(s.post_id, l.post_id) AS post_id, "
                + "           COALESCE(s.uid, l.uid) AS uid, "
                + "           COALESCE(s.title, l.title) AS title, "
                + "           COALESCE(s.url, l.url) AS url, "
                + "           s.distance AS distance, "
                + "           COALESCE(1.0 / (" + RRF_K + " + s.arm_rank), 0) "
                + "             + COALESCE(1.0 / (" + RRF_K + " + l.arm_rank), 0) AS fused_score "
                + "      FROM ( "
                + "        SELECT hits.*, ROW_NUMBER() OVER "
                + "               (ORDER BY distance ASC, post_id ASC) AS arm_rank "
                + "          FROM ( "
                + "            SELECT p.uid, p.title, p.url, pe.post_id AS post_id, "
                + "                   (pe.embedding <=> ?::vector) AS distance "
                + "              FROM post_embedding pe "
                + "              JOIN post p ON p.id = pe.post_id "
                + "             WHERE p.status = 'READY' "
                + "               AND " + SearchPostsTool.worldPredicateSql("p")
                + "               AND (pe.embedding <=> ?::vector) < ? "
                + "             ORDER BY pe.embedding <=> ?::vector "
                + "             LIMIT ? "
                + "          ) hits "
                + "      ) s "
                + "      FULL OUTER JOIN ( "
                + "        SELECT lhits.*, ROW_NUMBER() OVER "
                + "               (ORDER BY lex_score DESC, post_id ASC) AS arm_rank "
                + "          FROM ( "
                + "            SELECT p.uid, p.title, p.url, p.id AS post_id, "
                + "                   ts_rank(p.search_tsv, "
                + "                           plainto_tsquery('english', ?)) AS lex_score "
                + "              FROM post p "
                + "             WHERE p.status = 'READY' "
                + "               AND " + SearchPostsTool.worldPredicateSql("p")
                + "               AND p.search_tsv @@ plainto_tsquery('english', ?) "
                + "             ORDER BY lex_score DESC, post_id ASC "
                + "             LIMIT ? "
                + "          ) lhits "
                + "      ) l ON s.post_id = l.post_id "
                + "  ) fused "
                + " ORDER BY fused_score DESC, post_id ASC "
                + " LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // Each arm's world predicate binds two (scope_kind, scope_id)
            // pairs: exclusion probe, then subscription arm
            // (SearchPostsTool.worldPredicateSql bind contract).
            ps.setString(1, vectorLiteral);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, scopeKind);
            ps.setObject(5, scopeId);
            ps.setString(6, vectorLiteral);
            ps.setDouble(7, distanceThreshold);
            ps.setString(8, vectorLiteral);
            ps.setInt(9, limit);
            ps.setString(10, query);
            ps.setString(11, scopeKind);
            ps.setObject(12, scopeId);
            ps.setString(13, scopeKind);
            ps.setObject(14, scopeId);
            ps.setString(15, query);
            ps.setInt(16, limit);
            ps.setInt(17, limit);
            try (ResultSet rs = ps.executeQuery()) {
                // '[' + ']' — every appended entry adds its own bytes (plus
                // a joining comma) against MAX_RESULT_BYTES, exactly as
                // SearchPostsTool bounds its own aggregate. similarity is
                // 1 - distance (LinkingJob's computation) — a display value
                // only; the raw embedding vector is never emitted (D5). A
                // lexical-only row may have NO post_embedding row at all
                // (embedding-failure posts are released without a vector),
                // so its similarity is emitted as JSON null, not 0.
                StringBuilder json = new StringBuilder("[");
                int budgetUsed = 2;
                boolean first = true;
                while (rs.next()) {
                    double distance = rs.getDouble("distance");
                    String similarity = rs.wasNull()
                            ? "null" : Float.toString((float) (1.0 - distance));
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
