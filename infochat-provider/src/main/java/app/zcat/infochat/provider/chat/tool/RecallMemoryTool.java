package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.ChatToolRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
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
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    String[] refs = (String[]) rs.getArray("referenced_posts").getArray();
                    json.append("{\"compressed_at\":")
                        .append(jsonStr(rs.getTimestamp("created_at").toInstant().toString()))
                        .append(",\"summary\":").append(jsonStr(rs.getString("summary")))
                        .append(",\"references\":");
                    appendJsonArray(json, refs);
                    json.append('}');
                }
                json.append(']');
                return json.toString();
            }
        }
    }
}
