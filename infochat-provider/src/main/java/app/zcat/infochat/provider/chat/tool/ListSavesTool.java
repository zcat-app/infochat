package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.ChatToolRegistry;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.zcat.infochat.provider.chat.tool.SearchPostsTool.appendJsonArray;
import static app.zcat.infochat.provider.chat.tool.SearchPostsTool.jsonStr;

// Per-user globally across scopes (D13) — never another user's saves.
@ApplicationScoped
public class ListSavesTool implements ChatToolRegistry.ChatTool {

    private static final Duration WINDOW_MAX = Duration.ofDays(30);
    private static final int RESULT_LIMIT = 200;

    private final DataSource dataSource;
    private final CancellationService cancellationService;

    @Inject
    public ListSavesTool(DataSource dataSource, CancellationService cancellationService) {
        this.dataSource = dataSource;
        this.cancellationService = cancellationService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(UUID userId, String scopeKind,
                                    UUID scopeId, Map<String, Object> args)
            throws SQLException {
        List<String> personalTags = args.containsKey("tags")
                ? (List<String>) args.get("tags") : List.of();
        Duration window = args.containsKey("window")
                ? Duration.parse((String) args.get("window")) : WINDOW_MAX;

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT post_uid, saved_at, personal_tags, title, url ")
           .append("FROM saved_post WHERE user_id = ? ");

        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (!personalTags.isEmpty()) {
            sql.append("AND personal_tags && ?::TEXT[] ");
            params.add(personalTags.toArray(new String[0]));
        }

        sql.append("AND saved_at >= ? ");
        params.add(Timestamp.from(Instant.now().minus(window)));

        sql.append("ORDER BY saved_at DESC LIMIT ").append(RESULT_LIMIT);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            cancellationService.armToolConnection(conn, userId, scopeKind, scopeId);
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                switch (p) {
                    case UUID u -> ps.setObject(i + 1, u);
                    case String[] arr -> ps.setArray(i + 1, conn.createArrayOf("TEXT", arr));
                    case Timestamp t -> ps.setTimestamp(i + 1, t);
                    default -> throw new IllegalStateException(
                            "Unhandled param type: " + p.getClass());
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    String[] pTags = (String[]) rs.getArray("personal_tags").getArray();
                    json.append("{\"uid\":").append(jsonStr(rs.getString("post_uid")))
                        .append(",\"saved_at\":").append(jsonStr(
                                rs.getTimestamp("saved_at").toInstant().toString()))
                        .append(",\"personal_tags\":");
                    appendJsonArray(json, pTags);
                    json.append(",\"snapshot_title\":").append(jsonStr(rs.getString("title")))
                        .append(",\"snapshot_url\":").append(jsonStr(rs.getString("url")))
                        .append('}');
                }
                json.append(']');
                return json.toString();
            }
        }
    }
}
