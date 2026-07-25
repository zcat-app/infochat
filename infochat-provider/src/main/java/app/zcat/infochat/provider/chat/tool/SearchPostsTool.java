package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.ChatToolRegistry;
import org.jspecify.annotations.Nullable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class SearchPostsTool implements ChatToolRegistry.ChatTool {

    private static final Duration WINDOW_MIN = Duration.ofHours(1);
    private static final Duration WINDOW_MAX = Duration.ofDays(30);

    /**
     * Aggregate byte budget for the returned JSON array, measured in
     * UTF-8 bytes. Tool results are reinjected verbatim into the chat
     * prompt (LLM tool-call outputs are a trust boundary), so a large
     * result set would otherwise consume the context window. Mirrors
     * {@link RecallMemoryTool#MAX_RESULT_BYTES}: entries past the budget
     * are dropped, newest-first (the ORDER BY) ordering kept.
     */
    static final int MAX_RESULT_BYTES = 16 * 1024;

    private final DataSource dataSource;
    private final CancellationService cancellationService;

    // The ready_at retrieval-window cutoff is a decision-gate "now", so it
    // reads from the injected Clock to stay pinnable in tests (M1-454,
    // engineering-rules §9). Field initialiser keeps the constructor-built
    // test instances non-null; CDI overrides it at runtime (M1-444 reference).
    @Inject
    Clock clock = Clock.systemUTC();

    @Inject
    public SearchPostsTool(DataSource dataSource, CancellationService cancellationService) {
        this.dataSource = dataSource;
        this.cancellationService = cancellationService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(UUID userId, String scopeKind,
                                    UUID scopeId, Map<String, Object> args)
            throws SQLException {
        List<String> tags = args.containsKey("tags")
                ? (List<String>) args.get("tags") : List.of();
        Duration window = args.containsKey("window")
                ? Duration.parse((String) args.get("window")) : WINDOW_MAX;
        int limit = args.containsKey("limit")
                ? ((Number) args.get("limit")).intValue() : 50;

        if (window.compareTo(WINDOW_MIN) < 0) window = WINDOW_MIN;
        if (window.compareTo(WINDOW_MAX) > 0) window = WINDOW_MAX;

        // One pooled connection per tool call. Arm it for /stop first
        // (statement_timeout safety net + register this connection's backend
        // pid on the in-flight handle), then run every read on this single
        // connection: the registered pid is the one actually executing the
        // query, and the pool sees one acquisition rather than four.
        try (Connection conn = dataSource.getConnection()) {
            cancellationService.armToolConnection(conn, userId, scopeKind, scopeId);

            validateTagsKnown(conn, tags);

            // The scope's /follow-tag preferences intentionally do NOT apply
            // here: tag preferences narrow the DIGEST only; chat/RAG search
            // stays broad over the scope's whole world (D59, M1-621). Only
            // the caller-requested tags (validated above) filter.
            Instant cutoff = clock.instant().minus(window);

            return queryPosts(conn, scopeKind, scopeId, tags, cutoff, limit);
        }
    }

    /**
     * Validate every requested tag in ONE SELECT rather than one per tag.
     * The model-supplied tag list is a trust boundary; a per-tag round-trip
     * is wasteful and scales with the (model-controlled) tag count. Any
     * requested tag absent from the result set is unknown and rejects the
     * whole call with the same {@code "Unknown tag: <tag>"} message the
     * per-tag loop produced (first unknown in request order).
     */
    private void validateTagsKnown(Connection conn, List<String> tags) throws SQLException {
        if (tags.isEmpty()) {
            return;
        }
        Set<String> known = new LinkedHashSet<>();
        String sql = "SELECT name FROM tag WHERE name = ANY(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    known.add(rs.getString("name"));
                }
            }
        }
        for (String tag : tags) {
            if (!known.contains(tag)) {
                throw new IllegalArgumentException("Unknown tag: " + tag);
            }
        }
    }

    private String queryPosts(Connection conn, String scopeKind, UUID scopeId,
                               List<String> requestedTags, Instant cutoff,
                               int limit) throws SQLException {
        StringBuilder sql = new StringBuilder();
        // ready_at is the window filter, and the emitted ready_at field
        // carries the ready_at column per the spec's tool catalogue result
        // shape. Filtering on ready_at keeps "the last N hours" meaning the
        // same thing here as it does in /summary and the digest — otherwise
        // one conversation could hold two definitions of the same window
        // (M1-689).
        //
        // The sort key below is COALESCE(published_at, fetched_at), not a
        // bare published_at: this result is re-injected verbatim into the chat
        // prompt, so its head is the position an attacker most wants. NULLs
        // sort FIRST under DESC in Postgres, and published_at is nullable and
        // source-supplied, so a bare key would let any feed take that head by
        // simply OMITTING its date — strictly easier than the future-dating
        // the ingest clamp already denies (schema.md §"published_at clamp").
        // The fallback is fetched_at rather than ready_at because ready_at is
        // stamped at promotion (later than fetch) and re-stamped by
        // approve_quarantine/re-eval, both of which would rank an undated post
        // above the clamp ceiling; fetched_at is the immutable partition key.
        // M1-689 redteam rounds 1-2.
        sql.append("SELECT p.uid, p.title, p.url, p.ready_at, p.tags ")
           .append("FROM post p ")
           .append("WHERE p.status = 'READY' ")
           .append("AND p.ready_at >= ? ")
           .append("AND ").append(worldPredicateSql("p")).append(' ');

        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(cutoff));
        params.add(scopeKind);
        params.add(scopeId);
        params.add(scopeKind);
        params.add(scopeId);

        if (!requestedTags.isEmpty()) {
            sql.append("AND p.tags && ?::TEXT[] ");
            params.add(requestedTags.toArray(new String[0]));
        }

        sql.append("ORDER BY COALESCE(p.published_at, p.fetched_at) DESC, p.id DESC LIMIT ?");
        params.add(limit);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, conn, params);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder json = new StringBuilder("[");
                // '[' + ']' — every appended entry adds its own bytes (plus a
                // joining comma) against MAX_RESULT_BYTES. The result is
                // reinjected verbatim into the chat prompt (LLM tool-call
                // outputs are a trust boundary), so the aggregate is bounded
                // here exactly as RecallMemoryTool bounds its own; entries
                // past the budget are dropped, newest-first ordering kept.
                int budgetUsed = 2;
                boolean first = true;
                while (rs.next()) {
                    StringBuilder entry = new StringBuilder();
                    entry.append("{\"uid\":").append(jsonStr(rs.getString("uid")))
                         .append(",\"title\":").append(jsonStr(rs.getString("title")))
                         .append(",\"url\":").append(jsonStr(rs.getString("url")))
                         .append(",\"ready_at\":").append(jsonStr(
                                 instantStr(rs.getTimestamp("ready_at"))))
                         .append(",\"tags\":");
                    appendJsonArray(entry, (String[]) rs.getArray("tags").getArray());
                    entry.append('}');
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

    /**
     * The D59 world predicate for post-visibility sites without a
     * {@code source} join, parameterized by the post alias: the post's
     * source is visible to the calling scope iff it is a live
     * ({@code deleted_at IS NULL}), non-excluded bootstrap source, OR in
     * the scope's {@code source_subscription}. Binds two
     * {@code (scope_kind, scope_id)} pairs in order — the exclusion probe
     * first, then the subscription arm. Shared by the chat tools so the
     * privacy-bearing predicate cannot drift per site (M1-621); the inner
     * aliases {@code s_w}/{@code e_w} avoid colliding with callers' own
     * table aliases.
     */
    static String worldPredicateSql(String postAlias) {
        return "(EXISTS (SELECT 1 FROM source s_w "
             + "          WHERE s_w.id = " + postAlias + ".source_id "
             + "            AND s_w.source_origin = 'bootstrap' "
             + "            AND s_w.deleted_at IS NULL "
             + "            AND NOT EXISTS (SELECT 1 FROM source_exclusion e_w "
             + "                             WHERE e_w.scope_kind = ? AND e_w.scope_id = ? "
             + "                               AND e_w.source_id = s_w.id)) "
             + " OR " + postAlias + ".source_id IN "
             + "    (SELECT source_id FROM source_subscription "
             + "      WHERE scope_kind = ? AND scope_id = ?))";
    }

    private static void bindParams(PreparedStatement ps, Connection conn,
                                    List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            switch (p) {
                case String s -> ps.setString(i + 1, s);
                case UUID u -> ps.setObject(i + 1, u);
                case Timestamp t -> ps.setTimestamp(i + 1, t);
                case Integer n -> ps.setInt(i + 1, n);
                case String[] arr -> ps.setArray(i + 1, conn.createArrayOf("TEXT", arr));
                default -> throw new IllegalStateException("Unhandled param type: " + p.getClass());
            }
        }
    }

    static String jsonStr(@Nullable String s) {
        if (s == null) return "null";
        return "\"" + JsonEscaper.escape(s) + "\"";
    }

    static void appendJsonArray(StringBuilder sb, String[] items) {
        sb.append('[');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonStr(items[i]));
        }
        sb.append(']');
    }

    private static @Nullable String instantStr(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
