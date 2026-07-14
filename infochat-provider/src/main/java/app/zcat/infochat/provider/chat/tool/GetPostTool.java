package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.ChatToolRegistry;
import org.jspecify.annotations.Nullable;

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

    /**
     * Byte budget for the returned {@code body} field, measured in
     * UTF-8 bytes. Tool results are reinjected into the chat prompt
     * verbatim, so an unbounded body lets a single post consume the
     * context window. 8 KiB ≈ 2K tokens — enough for a full article,
     * small enough to leave room for the conversation. Design-tier
     * value, tunable without a spec amendment (like
     * {@code SearchPostsTool}'s window/limit bounds).
     */
    static final int MAX_BODY_BYTES = 8 * 1024;

    /** Appended where a result string was cut at its byte budget. */
    static final String TRUNCATION_MARKER = "[TRUNCATED]";

    private final DataSource dataSource;
    private final CancellationService cancellationService;

    @Inject
    public GetPostTool(DataSource dataSource, CancellationService cancellationService) {
        this.dataSource = dataSource;
        this.cancellationService = cancellationService;
    }

    @Override
    public String execute(UUID userId, String scopeKind,
                                    UUID scopeId, Map<String, Object> args)
            throws SQLException {
        String uid = (String) args.get("uid");
        if (uid == null) {
            throw new IllegalArgumentException("Missing required parameter: uid");
        }

        // Scope-filtered: returns null for invisible UIDs (same path as
        // nonexistent — the distinction is never exposed). Visibility is
        // the D59 world predicate — the same one the search tools apply —
        // so every uid search can return resolves here (no
        // search-visible-but-unfetchable post).
        String sql = "SELECT p.uid, p.title, p.body, p.url, p.ready_at, p.tags "
                   + "FROM post p "
                   + "WHERE p.uid = ? AND p.status = 'READY' "
                   + "AND " + SearchPostsTool.worldPredicateSql("p");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            cancellationService.armToolConnection(conn, userId, scopeKind, scopeId);
            // World predicate binds: exclusion probe pair, then
            // subscription pair (worldPredicateSql bind contract).
            ps.setString(1, uid);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, scopeKind);
            ps.setObject(5, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "null";

                String body = rs.getString("body");
                StringBuilder json = new StringBuilder("{");
                json.append("\"uid\":").append(jsonStr(rs.getString("uid")))
                    .append(",\"title\":").append(jsonStr(rs.getString("title")))
                    .append(",\"body\":").append(jsonStr(
                            body == null ? null : truncateUtf8(body, MAX_BODY_BYTES)))
                    .append(",\"url\":").append(jsonStr(rs.getString("url")))
                    .append(",\"ready_at\":").append(jsonStr(instantStr(
                            rs.getTimestamp("ready_at"))))
                    .append(",\"tags\":");
                appendJsonArray(json, (String[]) rs.getArray("tags").getArray());
                json.append('}');
                return json.toString();
            }
        }
    }

    private static @Nullable String instantStr(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    /**
     * Truncate {@code s} to at most {@code maxBytes} UTF-8 bytes,
     * cutting on a code-point boundary (never splitting a surrogate
     * pair) and appending {@link #TRUNCATION_MARKER} when anything was
     * cut. Strings within budget return unchanged. Shared by the chat
     * tools that bound their result payloads.
     */
    static String truncateUtf8(String s, int maxBytes) {
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int codePoint = s.codePointAt(i);
            int codePointBytes = codePoint < 0x80 ? 1
                    : codePoint < 0x800 ? 2
                    : codePoint < 0x10000 ? 3 : 4;
            if (bytes + codePointBytes > maxBytes) {
                return s.substring(0, i) + TRUNCATION_MARKER;
            }
            bytes += codePointBytes;
            i += Character.charCount(codePoint);
        }
        return s;
    }
}
