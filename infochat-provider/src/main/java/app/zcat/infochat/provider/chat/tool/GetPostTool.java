package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.ChatToolRegistry;
import org.jspecify.annotations.NonNull;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import static app.zcat.infochat.provider.chat.tool.SearchPostsTool.appendJsonArray;
import static app.zcat.infochat.provider.chat.tool.SearchPostsTool.jsonStr;

@ApplicationScoped
public class GetPostTool implements ChatToolRegistry.ChatTool {

    private final DataSource dataSource;

    @Inject
    public GetPostTool(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull String execute(@NonNull UUID userId, @NonNull String scopeKind,
                                    @NonNull UUID scopeId, @NonNull Map<String, Object> args)
            throws SQLException {
        String uid = (String) args.get("uid");
        if (uid == null) {
            throw new IllegalArgumentException("Missing required parameter: uid");
        }

        // Scope-filtered: returns null for invisible UIDs (same path as
        // nonexistent — the distinction is never exposed).
        String sql = "SELECT p.uid, p.title, p.body, p.url, p.published_at, p.tags "
                   + "FROM post p "
                   + "WHERE p.uid = ? AND p.status = 'READY' "
                   + "AND p.source_id IN (SELECT source_id FROM source_subscription "
                   + "WHERE scope_kind = ? AND scope_id = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uid);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "null";

                StringBuilder json = new StringBuilder("{");
                json.append("\"uid\":").append(jsonStr(rs.getString("uid")))
                    .append(",\"title\":").append(jsonStr(rs.getString("title")))
                    .append(",\"body\":").append(jsonStr(rs.getString("body")))
                    .append(",\"url\":").append(jsonStr(rs.getString("url")))
                    .append(",\"ready_at\":").append(jsonStr(instantStr(
                            rs.getTimestamp("published_at"))))
                    .append(",\"tags\":");
                appendJsonArray(json, (String[]) rs.getArray("tags").getArray());
                json.append('}');
                return json.toString();
            }
        }
    }

    private static String instantStr(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }
}
