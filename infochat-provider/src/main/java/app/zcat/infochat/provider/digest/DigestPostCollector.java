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
import app.zcat.infochat.provider.summary.TagTreeExpansion;
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
     * Collects posts in the group's D59 world (implicit bootstrap corpus
     * minus the group's exclusions, plus its subscriptions) that became
     * readable since the given instant, plus the current subscription
     * versions for cache-keying.
     *
     * <p>"Became readable" is {@code ready_at}, not the source-supplied
     * {@code published_at}: the window states when a post entered OUR
     * pipeline's output, which is monotonic and always set, where feed
     * metadata is neither. Keying on {@code published_at} silently dropped
     * every post whose fetch+evaluation lag outran the slot that would have
     * carried it (M1-689).
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
                // Two groupId binds: the world predicate's exclusion probe,
                // then its subscription arm.
                ps.setObject(idx++, groupId);
                ps.setObject(idx++, groupId);
                if ("EXPLICIT".equals(tagMode)) {
                    ps.setString(idx++, "group");
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
        java.sql.Array classificationArray = rs.getArray("classification");
        List<String> classification = classificationArray == null
                // NOT NULL in production (V57); the fallback only guards a
                // hand-stubbed ResultSet. Projected for real since M1-724:
                // the prominence urgent gate reads it.
                ? List.of("unknown")
                : List.of((String[]) classificationArray.getArray());
        Timestamp publishedTs = rs.getTimestamp("published_at");
        return new EligiblePostQuery.Post(
                rs.getObject("id", UUID.class),
                rs.getString("uid"),
                rs.getObject("source_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("body"),
                // Nullable per V7__joins_post.sql: a source need not supply a
                // publication date. Reachable here only since M1-689 moved the
                // window predicate off this column — under published_at >= ?
                // a NULL row could never match, so this read was unguarded.
                publishedTs == null ? null : publishedTs.toInstant(),
                tags,
                classification,
                // M1-724 prominence signals. getObject(Integer.class), never
                // getInt — SQL NULL must survive as null (absent term), not
                // collapse to 0 (present term, bottom percentile): M1-723
                // §Absent is not zero. comments joins the same discipline
                // as the fifth ranking term (M1-914).
                rs.getObject("reposts", Integer.class),
                rs.getObject("likes", Integer.class),
                rs.getObject("comments", Integer.class),
                rs.getString("kind"),
                rs.getObject("source_window_posts", Integer.class),
                // Declared per source (V7, NOT NULL DEFAULT 'en'), never
                // inferred from the body — the display-hit translation
                // no-op decision reads it (M1-747). The FULL constructor is
                // load-bearing here: the shorter overload hard-codes this
                // to NULL, which means "unknown, never translate", so a
                // projection without it would leave the digest permanently
                // untranslated with no error anywhere (M1-756).
                rs.getString("language"),
                // The English anchor (V74), NULL until the ingest
                // translator writes it. Same load-bearing-arity point as
                // the language above: both digest SQL blocks project these
                // two columns, or the two queries render different primary
                // lines for the same post (M1-759).
                rs.getString("title_en"),
                rs.getString("body_en"),
                // Free tags (V87) — the digest footer's only structural
                // input beyond post.tags; null-guarded for hand-stubbed
                // ResultSets like the arrays above.
                searchTagsOf(rs));
    }

    private static List<String> searchTagsOf(ResultSet rs) throws SQLException {
        java.sql.Array array = rs.getArray("search_tags");
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static final String SCOPE_PREFS_SQL = """
            SELECT tag_mode, tag_subscription_version, source_subscription_version
              FROM scope_preferences
             WHERE scope_kind = 'group' AND scope_id = ?""";

    // D59 world predicate; all tags pass. Bootstrap-origin sources (live,
    // not excluded by this group) are implicitly visible; subscriptions add
    // the rest. deleted_at IS NULL guards the bootstrap arm only — the
    // subscription arm relies on /remove-source cascade-deleting
    // subscription rows, as before.
    private static final String POSTS_ALL_SQL = """
            SELECT p.id, p.uid, p.source_id, s.display_name, p.title,
                   p.url, p.body, p.published_at, p.tags, p.classification,
                   p.reposts, p.likes, p.comments, s.kind, s.language,
                   p.title_en, p.body_en, p.search_tags,
                   COUNT(*) OVER (PARTITION BY p.source_id)::int AS source_window_posts
              FROM post p
              JOIN source s ON s.id = p.source_id
             WHERE p.status = 'READY'
               AND p.ready_at >= ?
               AND ((s.source_origin = 'bootstrap' AND s.deleted_at IS NULL
                     AND NOT EXISTS (SELECT 1 FROM source_exclusion e
                                      WHERE e.scope_kind = 'group' AND e.scope_id = ?
                                        AND e.source_id = s.id))
                 OR p.source_id IN (SELECT source_id FROM source_subscription
                                     WHERE scope_kind = 'group' AND scope_id = ?))
             ORDER BY COALESCE(p.published_at, p.fetched_at) DESC, p.id DESC
             LIMIT ?""";

    // D59 world predicate + EXPLICIT tag-subscription filter (the digest
    // KEEPS follow-tag narrowing — only chat/RAG decoupled, M1-621).
    private static final String POSTS_EXPLICIT_SQL = """
            SELECT p.id, p.uid, p.source_id, s.display_name, p.title,
                   p.url, p.body, p.published_at, p.tags, p.classification,
                   p.reposts, p.likes, p.comments, s.kind, s.language,
                   p.title_en, p.body_en, p.search_tags,
                   COUNT(*) OVER (PARTITION BY p.source_id)::int AS source_window_posts
              FROM post p
              JOIN source s ON s.id = p.source_id
             WHERE p.status = 'READY'
               AND p.ready_at >= ?
               AND ((s.source_origin = 'bootstrap' AND s.deleted_at IS NULL
                     AND NOT EXISTS (SELECT 1 FROM source_exclusion e
                                      WHERE e.scope_kind = 'group' AND e.scope_id = ?
                                        AND e.source_id = s.id))
                 OR p.source_id IN (SELECT source_id FROM source_subscription
                                     WHERE scope_kind = 'group' AND scope_id = ?))
               AND p.tags && """ + TagTreeExpansion.SCOPE_FOLLOWED_LEAVES_SQL + """
               ORDER BY COALESCE(p.published_at, p.fetched_at) DESC, p.id DESC
              LIMIT ?""";
}
