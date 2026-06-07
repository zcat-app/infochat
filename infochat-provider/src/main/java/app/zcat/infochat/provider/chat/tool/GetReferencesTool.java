package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.ChatToolRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
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
 * post falls outside the caller's subscription set.
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

    private final DataSource dataSource;

    @Inject
    public GetReferencesTool(DataSource dataSource) {
        this.dataSource = dataSource;
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
        // caller scope's source_subscription set; the source post (p1)
        // is the one whose uid the caller supplied — it must also be in
        // scope, otherwise we leak the existence of a non-scope post.
        // Both p1 and p2 are required READY so unreviewed quarantine
        // placeholders never surface.
        final String sql =
            "SELECT p2.uid AS to_uid, p2.title AS to_title, p2.url AS to_url, "
                + "       pr.link_type, pr.score "
                + "  FROM post p1 "
                + "  JOIN post_reference pr ON pr.from_post = p1.id "
                + "  JOIN post p2 ON p2.id = pr.to_post "
                + " WHERE p1.uid = ? "
                + "   AND p1.status = 'READY' "
                + "   AND p2.status = 'READY' "
                + "   AND p1.source_id IN (SELECT source_id FROM source_subscription "
                + "                         WHERE scope_kind = ? AND scope_id = ?) "
                + "   AND p2.source_id IN (SELECT source_id FROM source_subscription "
                + "                         WHERE scope_kind = ? AND scope_id = ?) "
                + " ORDER BY pr.score DESC, p2.uid ASC "
                + " LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uid);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, scopeKind);
            ps.setObject(5, scopeId);
            ps.setInt(6, limit);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"uid\":").append(jsonStr(rs.getString("to_uid")))
                        .append(",\"title\":").append(jsonStr(rs.getString("to_title")))
                        .append(",\"url\":").append(jsonStr(rs.getString("to_url")))
                        .append(",\"link_type\":").append(jsonStr(rs.getString("link_type")))
                        .append(",\"score\":").append(rs.getFloat("score"))
                        .append('}');
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
