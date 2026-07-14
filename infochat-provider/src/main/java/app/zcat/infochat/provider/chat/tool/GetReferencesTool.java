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
import java.util.Map;
import java.util.UUID;

import static app.zcat.infochat.provider.chat.tool.SearchPostsTool.jsonStr;

/**
 * Chat-agent tool returning the cross-source links of a given post. The
 * caller passes a {@code uid} (string) — the post's user-facing
 * identifier — which we resolve to {@code post.id} before joining the
 * V29 {@code post_reference} edge table.
 *
 * <p>Scope-filtered: a linked post that is not in the caller's scope is
 * silently dropped from the response (Invariant 1), matching the
 * {@link GetPostTool} precedent. The agent sees an empty array when
 * either (a) the requested post has no links yet or (b) every linked
 * post falls outside the caller's world (D59: implicit bootstrap minus
 * the scope's exclusions, plus its subscriptions).
 *
 * <p>Output JSON shape per docs/spec/security.md §Prompt-injection
 * defenses: {@code [{"uid": ..., "title": ..., "url": ...,
 * "link_type": ..., "score": ...}, ...]}.
 */
@ApplicationScoped
public class GetReferencesTool implements ChatToolRegistry.ChatTool {

    /** Default + ceiling on the response size; matches the SearchPostsTool surface. */
    private static final int LIMIT_DEFAULT = 25;
    private static final int LIMIT_MAX = 25;

    /**
     * Aggregate byte budget for the returned JSON array, measured in
     * UTF-8 bytes. Tool results are reinjected verbatim into the chat
     * prompt (LLM tool-call outputs are a trust boundary), so up to
     * {@code LIMIT_MAX} rows of unbounded titles would otherwise consume
     * the context window. Mirrors {@link SearchPostsTool#MAX_RESULT_BYTES}
     * and {@link ListSavesTool#MAX_RESULT_BYTES}: entries past the budget
     * are dropped, score-descending (the {@code ORDER BY}) ordering kept.
     */
    static final int MAX_RESULT_BYTES = 16 * 1024;

    /**
     * Per-title byte cap, measured in UTF-8 bytes. {@code to_title} is
     * external post data, uncapped on the provider side, so one pathological
     * title is truncated before the aggregate budget — otherwise a single
     * oversized title could push one entry far past a reasonable size.
     * Mirrors {@link ListSavesTool#MAX_TITLE_BYTES}.
     */
    static final int MAX_TITLE_BYTES = 2 * 1024;

    private final DataSource dataSource;
    private final CancellationService cancellationService;

    @Inject
    public GetReferencesTool(DataSource dataSource, CancellationService cancellationService) {
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
        int limit = readLimit(args);

        // The join shape: post_reference rows out of the requested post,
        // joined back to post for the linked-post metadata. The scope
        // filter mirrors GetPostTool: the linked post (p2) must be in the
        // caller scope's D59 world (SearchPostsTool.worldPredicateSql);
        // the source post (p1) is the one whose uid the caller supplied —
        // it must also be in the world, otherwise we leak the existence
        // of a non-visible post. Both p1 and p2 are required READY so
        // unreviewed quarantine placeholders never surface.
        final String sql =
            "SELECT p2.uid AS to_uid, p2.title AS to_title, p2.url AS to_url, "
                + "       pr.link_type, pr.score "
                + "  FROM post p1 "
                + "  JOIN post_reference pr ON pr.from_post = p1.id "
                + "  JOIN post p2 ON p2.id = pr.to_post "
                + " WHERE p1.uid = ? "
                + "   AND p1.status = 'READY' "
                + "   AND p2.status = 'READY' "
                + "   AND " + SearchPostsTool.worldPredicateSql("p1")
                + "   AND " + SearchPostsTool.worldPredicateSql("p2")
                + " ORDER BY pr.score DESC, p2.uid ASC "
                + " LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            cancellationService.armToolConnection(conn, userId, scopeKind, scopeId);
            // p1's and p2's world predicates each bind two
            // (scope_kind, scope_id) pairs: exclusion probe, then
            // subscription arm (worldPredicateSql bind contract).
            ps.setString(1, uid);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, scopeKind);
            ps.setObject(5, scopeId);
            ps.setString(6, scopeKind);
            ps.setObject(7, scopeId);
            ps.setString(8, scopeKind);
            ps.setObject(9, scopeId);
            ps.setInt(10, limit);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder json = new StringBuilder("[");
                // '[' + ']' — every appended entry adds its own bytes (plus a
                // joining comma) against MAX_RESULT_BYTES. The result is
                // reinjected verbatim into the chat prompt (LLM tool-call
                // outputs are a trust boundary), so the aggregate is bounded
                // here exactly as the sibling tools bound theirs; entries past
                // the budget are dropped, score-descending ordering kept.
                int budgetUsed = 2;
                boolean first = true;
                while (rs.next()) {
                    // to_title is external post data, uncapped on the provider
                    // side, so truncate per-entry before the aggregate budget
                    // (mirrors ListSavesTool's snapshot_title handling).
                    String title = rs.getString("to_title");
                    StringBuilder entry = new StringBuilder();
                    entry.append("{\"uid\":").append(jsonStr(rs.getString("to_uid")))
                         .append(",\"title\":").append(jsonStr(
                                 title == null ? null
                                         : GetPostTool.truncateUtf8(title, MAX_TITLE_BYTES)))
                         .append(",\"url\":").append(jsonStr(rs.getString("to_url")))
                         .append(",\"link_type\":").append(jsonStr(rs.getString("link_type")))
                         .append(",\"score\":").append(rs.getFloat("score"))
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

    private static int readLimit(Map<String, Object> args) {
        if (!args.containsKey("limit")) {
            return LIMIT_DEFAULT;
        }
        int requested = ((Number) args.get("limit")).intValue();
        if (requested < 1) return 1;
        if (requested > LIMIT_MAX) return LIMIT_MAX;
        return requested;
    }
}
