package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.util.JsonEscaper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Queries exactly the tables in the spec's field-level positive list
 * ({@code docs/spec/commands.md} §/export) and returns each table's
 * rows as JSON object strings, keyed by the spec's table names in
 * positive-list order.
 *
 * <p>Scope filtering follows the spec's per-table rules:
 * <ul>
 *   <li>Per-scope tables: filtered by {@code (scope_kind, scope_id)}
 *       and — where applicable — {@code user_id = caller}.</li>
 *   <li>{@code saved_post}: {@code user_id = caller} globally,
 *       regardless of calling scope (D13).</li>
 *   <li>{@code users}: {@code id = caller}; authorization-state
 *       fields excluded.</li>
 *   <li>{@code audit_log_view}: {@code actor_user_id = caller}
 *       only (target-only rows excluded).</li>
 * </ul>
 *
 * <p>The return type is {@code LinkedHashMap<String, List<String>>}
 * in positive-list order, ready to pass directly to
 * {@link ExportPaginator#paginate}.
 */
@ApplicationScoped
public class ExportDataCollector {

    /**
     * The 9 table names in the spec's positive-list order. Used as
     * JSON keys in the export output and asserted by the CI shape
     * test.
     */
    static final List<String> POSITIVE_LIST_TABLES = List.of(
            "chat_memory",
            "scope_preferences",
            "scope_tag",
            "chat_session",
            "source_subscription",
            "summary_anchor",
            "saved_post",
            "users",
            "audit_log_view");

    // Authorization-state fields excluded from the users export.
    // Spec: is_admin, banned_by, ban_reason, banned_at, probation_until.
    private static final String USERS_SQL =
            "SELECT id, adapter, contact_id, display_name, is_banned,"
                    + " registration_state, created_at, last_seen_at, save_count"
                    + " FROM users WHERE id = ?";

    private static final String CHAT_MEMORY_SQL =
            "SELECT id, user_id, scope_kind, scope_id, created_at,"
                    + " summary, keywords, referenced_posts, referenced_topics"
                    + " FROM chat_memory"
                    + " WHERE user_id = ? AND scope_kind = ? AND scope_id = ?"
                    + " ORDER BY created_at DESC";

    private static final String SCOPE_PREFERENCES_SQL =
            "SELECT tag_mode, language"
                    + " FROM scope_preferences"
                    + " WHERE scope_kind = ? AND scope_id = ?";

    // JOIN with tag to resolve tag_id → tag name.
    private static final String SCOPE_TAG_SQL =
            "SELECT t.name AS tag"
                    + " FROM scope_tag st JOIN tag t ON t.id = st.tag_id"
                    + " WHERE st.scope_kind = ? AND st.scope_id = ?"
                    + " ORDER BY t.name";

    // Spec names this "chat_session" but the per-turn data is in
    // chat_message; the chat_session table is a per-scope summary.
    private static final String CHAT_MESSAGE_SQL =
            "SELECT role, content, tokens, ts"
                    + " FROM chat_message"
                    + " WHERE user_id = ? AND scope_kind = ? AND scope_id = ?"
                    + " ORDER BY seq";

    private static final String SOURCE_SUBSCRIPTION_SQL =
            "SELECT source_id, added_at"
                    + " FROM source_subscription"
                    + " WHERE scope_kind = ? AND scope_id = ?"
                    + " ORDER BY added_at DESC";

    // user_id = caller already selects personal-only (digest anchors
    // have user_id IS NULL).
    private static final String SUMMARY_ANCHOR_SQL =
            "SELECT command_kind, command_name, arg_hash,"
                    + " post_uids, cluster_map, generated_at"
                    + " FROM summary_anchor"
                    + " WHERE user_id = ? AND scope_kind = ? AND scope_id = ?"
                    + " ORDER BY generated_at DESC";

    // Global regardless of calling scope (D13).
    private static final String SAVED_POST_SQL =
            "SELECT post_uid, personal_tags, saved_at"
                    + " FROM saved_post WHERE user_id = ?"
                    + " ORDER BY saved_at DESC";

    // Actor-only rows per spec. target_contact_id excluded because the
    // V5 redact_contact_id() stub returns input unchanged — including
    // it would leak other users' full contact IDs in admin audit rows.
    private static final String AUDIT_LOG_VIEW_SQL =
            "SELECT id, created_at, actor_user_id, actor_contact_id,"
                    + " actor_adapter, action, target_kind, target_id,"
                    + " scope_id, request_id, details_json"
                    + " FROM audit_log_view WHERE actor_user_id = ?"
                    + " ORDER BY created_at DESC";

    /** Result of {@link #collect}: the table data plus any truncation info. */
    public record ExportResult(
            LinkedHashMap<String, List<String>> tables,
            List<String> truncatedTables) {}

    @Inject
    DataSource dataSource;

    /**
     * Per-table row cap. Bounds memory for power users with large
     * audit histories. When a table hits the cap, its name appears
     * in {@link ExportResult#truncatedTables()}.
     */
    @ConfigProperty(name = "infochat.export.max-rows-per-table", defaultValue = "10000")
    int maxRowsPerTable;

    /**
     * Collect the calling user's data from all 9 positive-list tables.
     *
     * @param userId    the caller's {@code users.id}
     * @param scopeKind {@code "dm"} or {@code "group"}
     * @param scopeId   the scope UUID (for DM: same as userId; for
     *                  group: the group's UUID)
     * @return export result with table data and truncation info
     */
    public ExportResult collect(
            @NonNull UUID userId,
            @NonNull String scopeKind,
            @NonNull UUID scopeId) {

        try (Connection conn = dataSource.getConnection()) {
            LinkedHashMap<String, List<String>> tables = new LinkedHashMap<>();
            List<String> truncated = new ArrayList<>();

            collectTable(tables, truncated, "chat_memory",
                    queryChatMemory(conn, userId, scopeKind, scopeId));
            collectTable(tables, truncated, "scope_preferences",
                    queryScopePreferences(conn, scopeKind, scopeId));
            collectTable(tables, truncated, "scope_tag",
                    queryScopeTags(conn, scopeKind, scopeId));
            collectTable(tables, truncated, "chat_session",
                    queryChatMessages(conn, userId, scopeKind, scopeId));
            collectTable(tables, truncated, "source_subscription",
                    querySourceSubscriptions(conn, scopeKind, scopeId));
            collectTable(tables, truncated, "summary_anchor",
                    querySummaryAnchors(conn, userId, scopeKind, scopeId));
            collectTable(tables, truncated, "saved_post",
                    querySavedPosts(conn, userId));
            collectTable(tables, truncated, "users",
                    queryUser(conn, userId));
            collectTable(tables, truncated, "audit_log_view",
                    queryAuditLog(conn, userId));

            return new ExportResult(tables, truncated);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "ExportDataCollector failed for user=" + userId, e);
        }
    }

    private void collectTable(LinkedHashMap<String, List<String>> tables,
                              List<String> truncated,
                              String tableName, List<String> rows) {
        if (rows.size() >= maxRowsPerTable) {
            truncated.add(tableName);
        }
        tables.put(tableName, rows);
    }

    private String withLimit(String sql) {
        return sql + " LIMIT " + maxRowsPerTable;
    }

    private List<String> queryChatMemory(
            Connection conn, UUID userId, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(CHAT_MEMORY_SQL))) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            return collectRows(ps);
        }
    }

    private List<String> queryScopePreferences(
            Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(SCOPE_PREFERENCES_SQL))) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            return collectRows(ps);
        }
    }

    private List<String> queryScopeTags(
            Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(SCOPE_TAG_SQL))) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            return collectRows(ps);
        }
    }

    private List<String> queryChatMessages(
            Connection conn, UUID userId, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(CHAT_MESSAGE_SQL))) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            return collectRows(ps);
        }
    }

    private List<String> querySourceSubscriptions(
            Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(SOURCE_SUBSCRIPTION_SQL))) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            return collectRows(ps);
        }
    }

    private List<String> querySummaryAnchors(
            Connection conn, UUID userId, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(SUMMARY_ANCHOR_SQL))) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            return collectRows(ps);
        }
    }

    private List<String> querySavedPosts(Connection conn, UUID userId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(SAVED_POST_SQL))) {
            ps.setObject(1, userId);
            return collectRows(ps);
        }
    }

    private List<String> queryUser(Connection conn, UUID userId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(USERS_SQL))) {
            ps.setObject(1, userId);
            return collectRows(ps);
        }
    }

    private List<String> queryAuditLog(Connection conn, UUID userId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(withLimit(AUDIT_LOG_VIEW_SQL))) {
            ps.setObject(1, userId);
            return collectRows(ps);
        }
    }

    /**
     * Execute the query and render each row as a compact JSON object
     * string. Column names from the ResultSet become JSON keys; values
     * are serialized by SQL type.
     */
    private static List<String> collectRows(PreparedStatement ps)
            throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            int columnCount = rs.getMetaData().getColumnCount();
            String[] names = new String[columnCount];
            int[] types = new int[columnCount];
            for (int i = 0; i < columnCount; i++) {
                names[i] = rs.getMetaData().getColumnLabel(i + 1);
                types[i] = rs.getMetaData().getColumnType(i + 1);
            }
            while (rs.next()) {
                rows.add(renderRow(rs, names, types, columnCount));
            }
        }
        return rows;
    }

    private static String renderRow(
            ResultSet rs, String[] names, int[] types, int count)
            throws SQLException {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(names[i]).append("\":");
            appendValue(sb, rs, i + 1, types[i]);
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendValue(
            StringBuilder sb, ResultSet rs, int col, int sqlType)
            throws SQLException {
        Object val = rs.getObject(col);
        if (val == null) {
            sb.append("null");
            return;
        }
        switch (sqlType) {
            case Types.BOOLEAN, Types.BIT ->
                    sb.append(rs.getBoolean(col));
            case Types.INTEGER, Types.SMALLINT, Types.BIGINT ->
                    sb.append(rs.getLong(col));
            case Types.ARRAY -> {
                java.sql.Array arr = rs.getArray(col);
                Object[] elements = (Object[]) arr.getArray();
                sb.append('[');
                for (int j = 0; j < elements.length; j++) {
                    if (j > 0) sb.append(',');
                    appendJsonValue(sb, elements[j]);
                }
                sb.append(']');
                arr.free();
            }
            case Types.OTHER -> {
                // PostgreSQL-specific: UUID, JSONB, etc. Render as
                // string for UUID; inline for JSONB.
                String text = rs.getString(col);
                if (isJsonLike(text)) {
                    sb.append(text);
                } else {
                    appendJsonString(sb, text);
                }
            }
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                Timestamp ts = rs.getTimestamp(col);
                appendJsonString(sb, ts.toInstant().toString());
            }
            default ->
                    appendJsonString(sb, rs.getString(col));
        }
    }

    private static void appendJsonValue(StringBuilder sb, Object val) {
        if (val == null) {
            sb.append("null");
        } else {
            appendJsonString(sb, val.toString());
        }
    }

    static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"').append(JsonEscaper.escape(s)).append('"');
    }

    private static boolean isJsonLike(String text) {
        if (text == null || text.isEmpty()) return false;
        char first = text.charAt(0);
        return first == '{' || first == '[';
    }
}
