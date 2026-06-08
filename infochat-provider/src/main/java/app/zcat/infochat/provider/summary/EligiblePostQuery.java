package app.zcat.infochat.provider.summary;

import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.chat.CancellationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic SQL retrieval of eligible {@code READY} posts for
 * {@code /summary}. The query lives BEFORE any LLM invocation per the
 * docs/spec/llm.md §Determinism boundary contract: the same DB state
 * produces the same post set across runs, ordered by
 * {@code published_at DESC, id DESC} (the secondary id key breaks
 * ties stably). The cluster cap is applied here — when more than
 * {@code infochat.summary.cluster-cap} posts match, the OLDEST posts
 * are dropped (the tail of the ORDER BY) and the surplus is reported
 * via {@link Result#totalBeforeCap()} + {@link Result#excludedCount()}.
 *
 * <p>Stage-1 redaction placeholders flow through unchanged: posts with
 * {@code stage2_failed=true} are still included; their body carries
 * {@code [REDACTED:<id>]} as-is. The prose generator's prompt MUST NOT
 * strip the placeholder — see {@link SummaryProseGenerator}.
 *
 * <p>The {@code post_reference} graph is empty in MVP (no V12 yet), so
 * {@link ClusterTraversal} produces N singleton clusters from this
 * query's output. The shape of the {@code Result} record is forward-
 * compatible with the eventual graph traversal.
 */
@ApplicationScoped
public class EligiblePostQuery {

    /**
     * The {@code >5 followed tags} restriction threshold. The spec
     * specifies "more than 5" so the threshold is exclusive at 5.
     */
    private static final int TOP_TAG_RESTRICTION_THRESHOLD = 5;

    /**
     * The number of tags retained when the {@code >5 followed tags}
     * restriction fires (top N by post count).
     */
    private static final int TOP_TAG_RESTRICTION_TOP_N = 3;

    @Inject
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    @ConfigProperty(name = "infochat.summary.cluster-cap", defaultValue = "200")
    int clusterCap;

    @ConfigProperty(name = "infochat.profile.label", defaultValue = "laptop")
    String profileLabel;

    /**
     * The retrieval contract: the deterministic eligible-post list,
     * plus the metadata the handler needs to compose the cap-excess /
     * top-3 reply prefixes.
     */
    public record Result(
            List<Post> posts,
            int totalBeforeCap,
            int excludedCount,
            int profileCap,
            String profileLabel,
            Optional<TopTagRestriction> topTagRestriction) {
    }

    /**
     * Carried only when the {@code >5 followed tags} top-3 rule fired
     * (no positional tag + scope follows >5 tags). The handler uses
     * {@code followedTagCount} to interpolate the
     * {@code reply.summary.top_3_of_n_prefix} bundle template.
     */
    public record TopTagRestriction(
            int followedTagCount,
            List<String> topTagNames) {
    }

    /**
     * Projected row shape. Records here mirror the columns the SELECT
     * projects — fields outside this set (e.g. body_summary, author,
     * ready_at) are unused by /summary and intentionally omitted to
     * keep the row narrow.
     */
    public record Post(
            UUID id,
            String uid,
            UUID sourceId,
            String sourceDisplayName,
            String title,
            String url,
            String body,
            @Nullable Instant publishedAt,
            List<String> tags) {
    }

    /**
     * Resolve the eligible-post set for the given scope, window, and
     * optional positional tag. Caller pre-resolved the scope to a
     * {@code (scope_kind, scope_id)} pair (DM scopes use the caller's
     * {@code users.id} as the scope_id).
     */
    public Result fetch(String scopeKind, UUID scopeId,
                        Optional<String> positionalTag, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        Optional<TopTagRestriction> restriction = Optional.empty();
        List<String> restrictedTags = List.of();

        if (positionalTag.isEmpty()) {
            int followedCount = countFollowedTags(scopeKind, scopeId);
            if (followedCount > TOP_TAG_RESTRICTION_THRESHOLD) {
                restrictedTags = topActiveFollowedTags(scopeKind, scopeId, cutoff);
                restriction = Optional.of(new TopTagRestriction(followedCount, restrictedTags));
            }
        }

        TagMode tagMode = readTagMode(scopeKind, scopeId);

        Selection selection = selectPosts(scopeKind, scopeId, positionalTag, cutoff,
                tagMode, restrictedTags);

        // The SQL LIMIT already kept the freshest clusterCap (head of
        // the DESC ordering, dropping the OLDEST per the spec); the
        // window-function count carries the true pre-LIMIT total so the
        // cap-excess message stays exact without materializing every
        // eligible body in Java.
        int total = selection.totalBeforeCap();
        List<Post> capped = selection.posts();
        int excluded = total - capped.size();

        return new Result(capped, total, excluded, clusterCap, profileLabel, restriction);
    }

    /** {@link ScopeRef.Dm} → {@code scope_kind='dm'}, {@link ScopeRef.Group} → {@code scope_kind='group'}. */
    public static String scopeKindOf(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> "dm";
            case ScopeRef.Group ignored -> "group";
        };
    }

    // ----- private SQL helpers ------------------------------------------

    /** Bounded row set plus the true pre-LIMIT match count. */
    private record Selection(List<Post> posts, int totalBeforeCap) {
    }

    private Selection selectPosts(String scopeKind, UUID scopeId,
                                    Optional<String> positionalTag, Instant cutoff,
                                    TagMode tagMode, List<String> restrictedTags) {
        // The SELECT pins:
        //   - status='READY' (exclude RAW, QUARANTINED, NEEDS_REVIEW)
        //   - published_at >= cutoff (window filter)
        //   - source_id IN (SELECT FROM source_subscription WHERE scope=?) (subscription filter)
        //   - optional positional tag → post.tags @> ARRAY[?]
        //   - optional scope_preferences.tag_mode='EXPLICIT' → tags intersect scope_tag
        //   - optional top-3 restricted tags → tags intersect restricted set
        //   - ORDER BY published_at DESC, id DESC (deterministic; secondary
        //     key breaks ties stably across runs)
        //   - COUNT(*) OVER () projects the pre-LIMIT match total on every
        //     row (window functions evaluate before LIMIT), so the
        //     cap-excess counts stay exact without a second round-trip
        //   - LIMIT clusterCap bounds the rows (and bodies) materialized
        //     in Java to the cap
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT p.id, p.uid, p.source_id, s.display_name, p.title, ")
           .append("       p.url, p.body, p.published_at, p.tags, ")
           .append("       COUNT(*) OVER () AS total_count ")
           .append("  FROM post p ")
           .append("  JOIN source s ON s.id = p.source_id ")
           .append(" WHERE p.status = 'READY' ")
           .append("   AND p.published_at >= ? ")
           .append("   AND p.source_id IN (SELECT source_id FROM source_subscription ")
           .append("                        WHERE scope_kind = ? AND scope_id = ?) ");
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(cutoff));
        params.add(scopeKind);
        params.add(scopeId);

        if (positionalTag.isPresent()) {
            sql.append("   AND p.tags && ARRAY[?]::TEXT[] ");
            params.add(positionalTag.get());
        } else if (!restrictedTags.isEmpty()) {
            // >5 followed tags → restrict to the top-3 set.
            sql.append("   AND p.tags && ?::TEXT[] ");
            params.add(restrictedTags.toArray(new String[0]));
        } else if (tagMode == TagMode.EXPLICIT) {
            // EXPLICIT mode + no positional tag + ≤5 followed: filter
            // by the union of scope_tag rows for the scope.
            sql.append("   AND p.tags && ( ")
               .append("       SELECT COALESCE(array_agg(t.name), ARRAY[]::TEXT[]) ")
               .append("         FROM scope_tag st JOIN tag t ON t.id = st.tag_id ")
               .append("        WHERE st.scope_kind = ? AND st.scope_id = ? ) ");
            params.add(scopeKind);
            params.add(scopeId);
        }
        // tag_mode='ALL' + no positional tag + ≤5 followed → no tag filter.

        sql.append(" ORDER BY p.published_at DESC, p.id DESC ");
        sql.append(" LIMIT ? ");
        params.add(clusterCap);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = prepareTimed(conn, sql.toString())) {
            bindParams(ps, conn, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Post> out = new ArrayList<>();
                int totalBeforeCap = 0;
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject("id");
                    String uid = rs.getString("uid");
                    UUID sourceId = (UUID) rs.getObject("source_id");
                    String displayName = rs.getString("display_name");
                    String title = rs.getString("title");
                    String url = rs.getString("url");
                    String body = rs.getString("body");
                    Timestamp publishedTs = rs.getTimestamp("published_at");
                    Instant publishedAt = publishedTs == null ? null : publishedTs.toInstant();
                    String[] tagArr = (String[]) rs.getArray("tags").getArray();
                    List<String> tags = Arrays.asList(tagArr);
                    // Same value on every row; zero rows → total stays 0.
                    totalBeforeCap = rs.getInt("total_count");
                    out.add(new Post(id, uid, sourceId, displayName, title, url, body,
                            publishedAt, tags));
                }
                return new Selection(out, totalBeforeCap);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.selectPosts failed", e);
        }
    }

    private int countFollowedTags(String scopeKind, UUID scopeId) {
        String sql = "SELECT COUNT(*) FROM scope_tag WHERE scope_kind = ? AND scope_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = prepareTimed(conn, sql)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.countFollowedTags failed", e);
        }
    }

    /**
     * Top-3 most-active followed tags for the scope within the window.
     * Ordering: post-count DESC, then {@code tag.name} ASC for stable
     * tie-break across runs (acceptance item 6).
     */
    private List<String> topActiveFollowedTags(String scopeKind, UUID scopeId, Instant cutoff) {
        // unnest(p.tags) intersected with the scope's followed tag set,
        // counted per tag, ordered count DESC + name ASC.
        String sql =
                "SELECT t.name, COUNT(*) AS post_count "
              + "  FROM post p "
              + "  JOIN source_subscription sub "
              + "    ON sub.source_id = p.source_id "
              + "   AND sub.scope_kind = ? AND sub.scope_id = ? "
              + " CROSS JOIN unnest(p.tags) AS tag_name "
              + "  JOIN scope_tag st ON st.scope_kind = sub.scope_kind "
              + "                    AND st.scope_id = sub.scope_id "
              + "  JOIN tag t ON t.id = st.tag_id AND t.name = tag_name "
              + " WHERE p.status = 'READY' "
              + "   AND p.published_at >= ? "
              + " GROUP BY t.name "
              + " ORDER BY post_count DESC, t.name ASC "
              + " LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = prepareTimed(conn, sql)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setTimestamp(3, Timestamp.from(cutoff));
            ps.setInt(4, TOP_TAG_RESTRICTION_TOP_N);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString("name"));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.topActiveFollowedTags failed", e);
        }
    }

    private TagMode readTagMode(String scopeKind, UUID scopeId) {
        String sql = "SELECT tag_mode FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = prepareTimed(conn, sql)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return TagMode.ALL;
                }
                String raw = rs.getString("tag_mode");
                return raw != null && raw.equalsIgnoreCase("EXPLICIT")
                        ? TagMode.EXPLICIT : TagMode.ALL;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.readTagMode failed", e);
        }
    }

    /**
     * Resolve the controlled-vocabulary tag-name set so the handler can
     * tell whether a positional tag passed parser validation but misses
     * the vocabulary. Returns an empty set on SQL failure; the handler
     * treats a missing vocabulary as "every tag is unknown" — the
     * fuzzy-suggestion footer surfaces in that case as an empty list.
     */
    public List<String> readVocabulary() {
        String sql = "SELECT name FROM tag ORDER BY name ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = prepareTimed(conn, sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.readVocabulary failed", e);
        }
    }

    /**
     * Prepare a statement on {@code conn} after applying the profile-driven
     * statement_timeout. Per commands.md §Conversation control, on-demand
     * /summary's read-only queries run under a statement_timeout that bounds
     * the worst case even when pg_cancel_backend fails. Every caller declares
     * {@code conn} first in its try-with-resources, so a SET that throws still
     * closes the connection (the half-open connection never leaks).
     */
    private PreparedStatement prepareTimed(Connection conn, String sql) throws SQLException {
        cancellationService.applyStatementTimeout(conn);
        return conn.prepareStatement(sql);
    }

    private void bindParams(PreparedStatement ps, Connection conn, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof String[] arr) {
                ps.setArray(i + 1, conn.createArrayOf("TEXT", arr));
            } else if (value instanceof Timestamp ts) {
                ps.setTimestamp(i + 1, ts);
            } else if (value instanceof UUID uuid) {
                ps.setObject(i + 1, uuid);
            } else {
                ps.setObject(i + 1, value);
            }
        }
    }

    private enum TagMode { ALL, EXPLICIT }

    /**
     * Build a fuzzy-suggestion list over the controlled vocabulary for a
     * user-supplied tag that missed the vocabulary. The current
     * implementation is intentionally naive: it sorts the vocabulary by
     * shared-prefix length DESC then by name ASC and returns up to N
     * entries. A future ticket can swap in a real distance metric; the
     * suggestion footer is informational.
     */
    public static List<String> fuzzySuggest(String supplied, List<String> vocabulary, int max) {
        Map<String, Integer> shared = new HashMap<>();
        for (String v : vocabulary) {
            shared.put(v, sharedPrefixLength(supplied, v));
        }
        List<String> sorted = new ArrayList<>(vocabulary);
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(shared.get(b), shared.get(a));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        return sorted.subList(0, Math.min(max, sorted.size()));
    }

    private static int sharedPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }
}
