package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.ChatToolRegistry;

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

    /**
     * Aggregate byte budget for the returned JSON array, measured in
     * UTF-8 bytes. Tool results are reinjected verbatim into the chat
     * prompt (LLM tool-call outputs are a trust boundary), so up to
     * {@code RESULT_LIMIT} rows of unbounded titles would otherwise
     * consume the context window. Mirrors {@link SearchPostsTool#MAX_RESULT_BYTES}
     * and {@link RecallMemoryTool#MAX_RESULT_BYTES}: entries past the budget
     * are dropped, newest-first (the {@code ORDER BY saved_at DESC}) ordering
     * kept.
     */
    static final int MAX_RESULT_BYTES = 16 * 1024;

    /**
     * Per-title byte cap, measured in UTF-8 bytes. {@code snapshot_title} is
     * external post data, uncapped on the provider side (the save path caps
     * only {@code personal_tags}), so one pathological title is truncated
     * before the aggregate budget — otherwise a single oversized title could
     * push one entry far past a reasonable size. Mirrors
     * {@link RecallMemoryTool#MAX_SUMMARY_BYTES}.
     */
    static final int MAX_TITLE_BYTES = 2 * 1024;

    private final DataSource dataSource;
    private final CancellationService cancellationService;

    // The saved_at retrieval-window cutoff is a decision-gate "now", so it
    // reads from the injected Clock to stay pinnable in tests (M1-454,
    // engineering-rules §9). Field initialiser keeps the constructor-built
    // test instances non-null; CDI overrides it at runtime (M1-444 reference).
    @Inject
    Clock clock = Clock.systemUTC();

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
        // Clamp a model-supplied window to WINDOW_MAX. LLM tool-call
        // arguments are a trust boundary: the model can request an arbitrary
        // Duration, but the saved-post scan stays bounded to 30 days
        // (mirrors SearchPostsTool's window clamp).
        if (window.compareTo(WINDOW_MAX) > 0) {
            window = WINDOW_MAX;
        }

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
        params.add(Timestamp.from(clock.instant().minus(window)));

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
                // '[' + ']' — every appended entry adds its own bytes (plus a
                // joining comma) against MAX_RESULT_BYTES. The result is
                // reinjected verbatim into the chat prompt (LLM tool-call
                // outputs are a trust boundary), so the aggregate is bounded
                // here exactly as the sibling tools bound theirs; entries past
                // the budget are dropped, newest-first ordering kept.
                int budgetUsed = 2;
                boolean first = true;
                while (rs.next()) {
                    String[] pTags = (String[]) rs.getArray("personal_tags").getArray();
                    String title = rs.getString("title");
                    StringBuilder entry = new StringBuilder();
                    entry.append("{\"uid\":").append(jsonStr(rs.getString("post_uid")))
                         .append(",\"saved_at\":").append(jsonStr(
                                 rs.getTimestamp("saved_at").toInstant().toString()))
                         .append(",\"personal_tags\":");
                    appendJsonArray(entry, pTags);
                    // snapshot_title is external post data, uncapped on the
                    // provider side, so truncate per-entry before the aggregate
                    // budget (mirrors RecallMemoryTool's summary handling).
                    entry.append(",\"snapshot_title\":").append(jsonStr(
                                 title == null ? null
                                         : GetPostTool.truncateUtf8(title, MAX_TITLE_BYTES)))
                         .append(",\"snapshot_url\":").append(jsonStr(rs.getString("url")))
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
}
