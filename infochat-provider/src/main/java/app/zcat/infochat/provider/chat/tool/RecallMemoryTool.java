package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.ChatToolRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.zcat.infochat.provider.chat.tool.SearchPostsTool.appendJsonArray;
import static app.zcat.infochat.provider.chat.tool.SearchPostsTool.jsonStr;

// Per-(user, scope) only — never cross-scope (D28). Not the user-facing
// /recall command (v2-deferred); this is the LLM tool for deeper digs.
@ApplicationScoped
public class RecallMemoryTool implements ChatToolRegistry.ChatTool {

    /**
     * Byte budgets, measured in UTF-8 bytes. The chat_memory LRU
     * trigger caps row count (200 per scope) and the query LIMITs to
     * 50, but neither bounds bytes — tool results are reinjected into
     * the chat prompt verbatim, so 50 rows of unbounded summaries could
     * consume the context window. {@code MAX_SUMMARY_BYTES} caps each
     * entry's summary (with {@code [TRUNCATED]} marker); entries past
     * {@code MAX_RESULT_BYTES} are dropped, newest-first ordering kept.
     * Design-tier values, tunable without a spec amendment (like
     * {@code SearchPostsTool}'s window/limit bounds).
     */
    static final int MAX_SUMMARY_BYTES = 2 * 1024;
    static final int MAX_RESULT_BYTES = 16 * 1024;

    private final DataSource dataSource;

    @Inject
    public RecallMemoryTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(UUID userId, String scopeKind,
                                    UUID scopeId, Map<String, Object> args)
            throws SQLException {
        List<String> keywords = args.containsKey("keywords")
                ? (List<String>) args.get("keywords") : List.of();
        if (keywords.isEmpty()) return "[]";

        String sql = "SELECT created_at, summary, referenced_posts "
                   + "FROM chat_memory "
                   + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
                   + "AND keywords && ?::TEXT[] "
                   + "ORDER BY created_at DESC "
                   + "LIMIT 50";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setArray(4, conn.createArrayOf("TEXT", keywords.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder json = new StringBuilder("[");
                // '[' + ']' — every appended entry adds its own bytes
                // (plus a joining comma) against MAX_RESULT_BYTES.
                int budgetUsed = 2;
                boolean first = true;
                while (rs.next()) {
                    String[] refs = (String[]) rs.getArray("referenced_posts").getArray();
                    StringBuilder entry = new StringBuilder();
                    entry.append("{\"compressed_at\":")
                         .append(jsonStr(rs.getTimestamp("created_at").toInstant().toString()))
                         .append(",\"summary\":").append(jsonStr(GetPostTool.truncateUtf8(
                                 rs.getString("summary"), MAX_SUMMARY_BYTES)))
                         .append(",\"references\":");
                    appendJsonArray(entry, refs);
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
}
