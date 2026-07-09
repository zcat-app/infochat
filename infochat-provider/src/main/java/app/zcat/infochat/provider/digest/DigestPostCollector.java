package app.zcat.infochat.provider.digest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DigestPostCollector {

    @Inject
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    // Same cap the on-demand /summary path applies in EligiblePostQuery: the
    // SQL LIMIT bounds the rows — and with it the renderer's per-cluster LLM
    // fan-out — before post bodies leave the database; the DESC ordering
    // keeps the freshest posts and drops the oldest.
    @ConfigProperty(name = "infochat.summary.cluster-cap", defaultValue = "200")
    int clusterCap;

    public record CollectionResult(
            List<EligiblePostQuery.Post> posts,
            long tagSubscriptionVersion,
            long sourceSubscriptionVersion) {}

    /**
     * Collects posts matching the group's active subscriptions published since
     * the given instant, plus the current subscription versions for cache-keying.
     */
    public CollectionResult collectForGroup(UUID groupId, Instant since)
            throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Same profile-driven statement_timeout the other provider read
            // paths run under (EligiblePostQuery, chat-mode tool calls).
            cancellationService.applyStatementTimeout(conn);
            String tagMode = "ALL";
            long tagSubVer = 0;
            long srcSubVer = 0;

            try (PreparedStatement ps = conn.prepareStatement(SCOPE_PREFS_SQL)) {
                ps.setObject(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        tagMode = rs.getString("tag_mode");
                        tagSubVer = rs.getLong("tag_subscription_version");
                        srcSubVer = rs.getLong("source_subscription_version");
                    }
                }
            }

            String sql = "EXPLICIT".equals(tagMode) ? POSTS_EXPLICIT_SQL : POSTS_ALL_SQL;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                ps.setTimestamp(idx++, Timestamp.from(since));
                ps.setObject(idx++, groupId);
                if ("EXPLICIT".equals(tagMode)) {
                    ps.setObject(idx++, groupId);
                }
                ps.setInt(idx, clusterCap);

                List<EligiblePostQuery.Post> posts = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        posts.add(mapPost(rs));
                    }
                }
                return new CollectionResult(List.copyOf(posts), tagSubVer, srcSubVer);
            }
        }
    }

    private EligiblePostQuery.Post mapPost(ResultSet rs) throws SQLException {
        java.sql.Array tagsArray = rs.getArray("tags");
        List<String> tags = tagsArray == null
                ? List.of()
                : List.of((String[]) tagsArray.getArray());
        return new EligiblePostQuery.Post(
                rs.getObject("id", UUID.class),
                rs.getString("uid"),
                rs.getObject("source_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("body"),
                rs.getTimestamp("published_at").toInstant(),
                tags,
                // The digest path never renders the classification: line (that
                // belongs to /summary + /retry via ClusterBlockRenderer); the
                // shared Post record still requires the field, so it carries the
                // {unknown} sentinel here — the digest SELECT does not project it.
                List.of("unknown"));
    }

    private static final String SCOPE_PREFS_SQL = """
            SELECT tag_mode, tag_subscription_version, source_subscription_version
              FROM scope_preferences
             WHERE scope_kind = 'group' AND scope_id = ?""";

    // Source-subscription filter only; all tags pass.
    private static final String POSTS_ALL_SQL = """
            SELECT p.id, p.uid, p.source_id, s.display_name, p.title,
                   p.url, p.body, p.published_at, p.tags
              FROM post p
              JOIN source s ON s.id = p.source_id
             WHERE p.status = 'READY'
               AND p.published_at >= ?
               AND p.source_id IN (SELECT source_id FROM source_subscription
                                    WHERE scope_kind = 'group' AND scope_id = ?)
             ORDER BY p.published_at DESC, p.id DESC
             LIMIT ?""";

    // Source-subscription + EXPLICIT tag-subscription filter.
    private static final String POSTS_EXPLICIT_SQL = """
            SELECT p.id, p.uid, p.source_id, s.display_name, p.title,
                   p.url, p.body, p.published_at, p.tags
              FROM post p
              JOIN source s ON s.id = p.source_id
             WHERE p.status = 'READY'
               AND p.published_at >= ?
               AND p.source_id IN (SELECT source_id FROM source_subscription
                                    WHERE scope_kind = 'group' AND scope_id = ?)
               AND p.tags && (SELECT COALESCE(array_agg(t.name), ARRAY[]::TEXT[])
                                FROM scope_tag st
                                JOIN tag t ON t.id = st.tag_id
                               WHERE st.scope_kind = 'group' AND st.scope_id = ?)
             ORDER BY p.published_at DESC, p.id DESC
             LIMIT ?""";
}
