package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.core.util.JsonEscaper;
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

    private enum TagMode { ALL, EXPLICIT }

    private final DataSource dataSource;
    private final CancellationService cancellationService;

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

            for (String tag : tags) {
                if (!isKnownTag(conn, tag)) {
                    throw new IllegalArgumentException("Unknown tag: " + tag);
                }
            }

            TagMode tagMode = readTagMode(conn, scopeKind, scopeId);
            List<String> effectiveTags =
                    computeEffectiveTags(conn, tags, tagMode, scopeKind, scopeId);
            Instant cutoff = Instant.now().minus(window);

            return queryPosts(conn, scopeKind, scopeId, effectiveTags, cutoff, limit);
        }
    }

    private boolean isKnownTag(Connection conn, String tag) throws SQLException {
        String sql = "SELECT 1 FROM tag WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tag);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private TagMode readTagMode(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        String sql = "SELECT tag_mode FROM scope_preferences "
                   + "WHERE scope_kind = ? AND scope_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return TagMode.ALL;
                String raw = rs.getString("tag_mode");
                return raw != null && raw.equalsIgnoreCase("EXPLICIT")
                        ? TagMode.EXPLICIT : TagMode.ALL;
            }
        }
    }

    private List<String> computeEffectiveTags(Connection conn, List<String> requestedTags,
                                               TagMode tagMode, String scopeKind, UUID scopeId)
            throws SQLException {
        if (!requestedTags.isEmpty()) {
            if (tagMode == TagMode.EXPLICIT) {
                Set<String> scopeTags = readScopeTags(conn, scopeKind, scopeId);
                List<String> intersection = new ArrayList<>();
                for (String tag : requestedTags) {
                    if (scopeTags.contains(tag)) intersection.add(tag);
                }
                return intersection;
            }
            return requestedTags;
        }
        if (tagMode == TagMode.EXPLICIT) {
            return new ArrayList<>(readScopeTags(conn, scopeKind, scopeId));
        }
        return List.of();
    }

    private Set<String> readScopeTags(Connection conn, String scopeKind, UUID scopeId)
            throws SQLException {
        String sql = "SELECT t.name FROM scope_tag st "
                   + "JOIN tag t ON t.id = st.tag_id "
                   + "WHERE st.scope_kind = ? AND st.scope_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> names = new LinkedHashSet<>();
                while (rs.next()) names.add(rs.getString("name"));
                return names;
            }
        }
    }

    private String queryPosts(Connection conn, String scopeKind, UUID scopeId,
                               List<String> effectiveTags, Instant cutoff,
                               int limit) throws SQLException {
        StringBuilder sql = new StringBuilder();
        // published_at stays the window filter and sort key below; the
        // emitted ready_at field carries the ready_at column per the
        // spec's tool catalogue result shape.
        sql.append("SELECT p.uid, p.title, p.url, p.ready_at, p.tags ")
           .append("FROM post p ")
           .append("WHERE p.status = 'READY' ")
           .append("AND p.published_at >= ? ")
           .append("AND p.source_id IN (SELECT source_id FROM source_subscription ")
           .append("WHERE scope_kind = ? AND scope_id = ?) ");

        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(cutoff));
        params.add(scopeKind);
        params.add(scopeId);

        if (!effectiveTags.isEmpty()) {
            sql.append("AND p.tags && ?::TEXT[] ");
            params.add(effectiveTags.toArray(new String[0]));
        }

        sql.append("ORDER BY p.published_at DESC, p.id DESC LIMIT ?");
        params.add(limit);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, conn, params);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"uid\":").append(jsonStr(rs.getString("uid")))
                        .append(",\"title\":").append(jsonStr(rs.getString("title")))
                        .append(",\"url\":").append(jsonStr(rs.getString("url")))
                        .append(",\"ready_at\":").append(jsonStr(
                                instantStr(rs.getTimestamp("ready_at"))))
                        .append(",\"tags\":");
                    appendJsonArray(json, (String[]) rs.getArray("tags").getArray());
                    json.append('}');
                }
                json.append(']');
                return json.toString();
            }
        }
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
