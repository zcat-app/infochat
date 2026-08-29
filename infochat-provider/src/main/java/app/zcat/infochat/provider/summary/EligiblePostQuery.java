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
import java.time.Clock;
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
 * {@code COALESCE(published_at, fetched_at) DESC, id DESC} (the
 * secondary id key breaks ties stably; the COALESCE bounds a source that
 * supplies NO date, which a bare {@code published_at DESC} would put at
 * the unconditional head because Postgres sorts NULLs first — M1-689). The cluster cap is applied here — when more than
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

    @Inject
    TagTreeExpansion tagTreeExpansion;

    // The /summary ready_at window cutoff is a decision-gate "now", so it
    // reads from the injected Clock to stay pinnable in tests (M1-454,
    // engineering-rules §9). Sampled once below and threaded to both
    // selectPosts and topActiveFollowedTags so the two queries share one
    // instant. CDI overrides the systemUTC() default at runtime (M1-444 reference).
    @Inject
    Clock clock = Clock.systemUTC();

    @ConfigProperty(name = "infochat.summary.cluster-cap", defaultValue = "200")
    int clusterCap;

    @ConfigProperty(name = "infochat.profile.label", defaultValue = "unknown")
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
     *
     * <p>The prominence signals are the M1-724 set plus the M1-914
     * reply count: {@code reposts}/{@code likes}/{@code comments} stay
     * NULL-distinct-from-0 end to end (M1-723 §Absent is not zero — an
     * RSS article has no like count; a Bluesky post with 0 was seen and
     * ignored), {@code sourceKind} populations the social percentiles,
     * and {@code sourceWindowPosts} is the pre-LIMIT per-source window
     * count the scarcity term inverts (the window function evaluates
     * before LIMIT, like {@code total_count}). {@code comments} is
     * reddit-only — NULL is the documented no-signal state for every
     * other kind. Only {@link ClusterProminence} reads them; the
     * /summary render is unchanged.
     *
     * <p>The last two components are the M1-759 English anchor
     * ({@code post.title_en}/{@code post.body_en}, V74). Projected RAW
     * and nullable rather than as a SQL {@code coalesce}: the render
     * needs anchor PRESENCE to pick the display translator's source
     * language and to decide bracketing, and a SQL-side coalesce
     * collapses exactly that bit. The {@code coalesce(title_en, title)}
     * semantics D29 describes are applied in Java, by
     * {@code DisplayHeadline}.
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
            List<String> tags,
            List<String> classification,
            @Nullable Integer reposts,
            @Nullable Integer likes,
            @Nullable Integer comments,
            @Nullable String sourceKind,
            @Nullable Integer sourceWindowPosts,
            @Nullable String sourceLanguage,
            @Nullable String titleEn,
            @Nullable String bodyEn,
            List<String> searchTags) {
        /** Pre-search-tags shape: older sites compile with an empty list. */
        public Post(UUID id,
                    String uid,
                    UUID sourceId,
                    String sourceDisplayName,
                    String title,
                    String url,
                    String body,
                    @Nullable Instant publishedAt,
                    List<String> tags,
                    List<String> classification,
                    @Nullable Integer reposts,
                    @Nullable Integer likes,
                    @Nullable Integer comments,
                    @Nullable String sourceKind,
                    @Nullable Integer sourceWindowPosts,
                    @Nullable String sourceLanguage,
                    @Nullable String titleEn,
                    @Nullable String bodyEn) {
            this(id, uid, sourceId, sourceDisplayName, title, url, body,
                    publishedAt, tags, classification, reposts, likes, comments,
                    sourceKind, sourceWindowPosts, sourceLanguage, titleEn, bodyEn,
                    List.of());
        }
        /**
         * Pre-M1-724 shape: every prominence signal absent. Keeps the
         * ~26 construction sites that predate the ranking (tests,
         * {@code RetryCommandHandler}) compiling unchanged, and is the
         * correct shape for any hand-built fixture whose posts carry no
         * signals — NULL drops the term from the score denominator.
         */
        public Post(UUID id,
                    String uid,
                    UUID sourceId,
                    String sourceDisplayName,
                    String title,
                    String url,
                    String body,
                    @Nullable Instant publishedAt,
                    List<String> tags,
                    List<String> classification) {
            this(id, uid, sourceId, sourceDisplayName, title, url, body,
                    publishedAt, tags, classification, null, null, null, null,
                    null, null, null, null);
        }

        /**
         * Pre-M1-747 shape: prominence signals present, source language
         * absent. Keeps the M1-724-era construction sites compiling
         * unchanged. {@code sourceLanguage} NULL means "unknown — never
         * translate" (the display-hit no-op leg), which is the correct
         * default for every hand-built fixture: translation is opt-in per
         * D29's declared-never-inferred rule.
         */
        public Post(UUID id,
                    String uid,
                    UUID sourceId,
                    String sourceDisplayName,
                    String title,
                    String url,
                    String body,
                    @Nullable Instant publishedAt,
                    List<String> tags,
                    List<String> classification,
                    @Nullable Integer reposts,
                    @Nullable Integer likes,
                    @Nullable String sourceKind,
                    @Nullable Integer sourceWindowPosts) {
            this(id, uid, sourceId, sourceDisplayName, title, url, body,
                    publishedAt, tags, classification, reposts, likes, null,
                    sourceKind, sourceWindowPosts, null, null, null);
        }

        /**
         * Pre-M1-759 shape: source language present, English anchor
         * absent. Keeps the M1-747-era construction sites compiling
         * unchanged — 13 of the 16 test files holding a
         * {@code new Post(...)} sit outside this ticket's scope, and the
         * two older compat constructors above delegate through this
         * arity. A NULL anchor is also the CORRECT default for a
         * hand-built fixture: it is what the ingest translator leaves on
         * a post it has not reached (or has given up on), so the
         * anchor-absent render path is what a fixture without an
         * explicit anchor exercises.
         */
        public Post(UUID id,
                    String uid,
                    UUID sourceId,
                    String sourceDisplayName,
                    String title,
                    String url,
                    String body,
                    @Nullable Instant publishedAt,
                    List<String> tags,
                    List<String> classification,
                    @Nullable Integer reposts,
                    @Nullable Integer likes,
                    @Nullable String sourceKind,
                    @Nullable Integer sourceWindowPosts,
                    @Nullable String sourceLanguage) {
            this(id, uid, sourceId, sourceDisplayName, title, url, body,
                    publishedAt, tags, classification, reposts, likes, null,
                    sourceKind, sourceWindowPosts, sourceLanguage, null, null);
        }

        /**
         * M1-914 shape: the M1-724 signal set plus the reddit reply
         * count, English anchor absent. The ranking-only consumer
         * ({@link ClusterProminence}) and its fixtures construct here.
         */
        public Post(UUID id,
                    String uid,
                    UUID sourceId,
                    String sourceDisplayName,
                    String title,
                    String url,
                    String body,
                    @Nullable Instant publishedAt,
                    List<String> tags,
                    List<String> classification,
                    @Nullable Integer reposts,
                    @Nullable Integer likes,
                    @Nullable Integer comments,
                    @Nullable String sourceKind,
                    @Nullable Integer sourceWindowPosts) {
            this(id, uid, sourceId, sourceDisplayName, title, url, body,
                    publishedAt, tags, classification, reposts, likes, comments,
                    sourceKind, sourceWindowPosts, null, null, null);
        }

        /**
         * Pre-M1-914 full shape: the M1-759 canonical without the reply
         * count. Keeps the one production construction site that renders
         * (never re-ranks) — {@code RetryCommandHandler}'s replay fetch —
         * compiling unchanged; its prominence signals stay all-NULL by
         * design, comments included.
         */
        public Post(UUID id,
                    String uid,
                    UUID sourceId,
                    String sourceDisplayName,
                    String title,
                    String url,
                    String body,
                    @Nullable Instant publishedAt,
                    List<String> tags,
                    List<String> classification,
                    @Nullable Integer reposts,
                    @Nullable Integer likes,
                    @Nullable String sourceKind,
                    @Nullable Integer sourceWindowPosts,
                    @Nullable String sourceLanguage,
                    @Nullable String titleEn,
                    @Nullable String bodyEn) {
            this(id, uid, sourceId, sourceDisplayName, title, url, body,
                    publishedAt, tags, classification, reposts, likes, null,
                    sourceKind, sourceWindowPosts, sourceLanguage, titleEn, bodyEn);
        }
    }

    /**
     * Resolve the eligible-post set for the given scope, window, and
     * optional positional tag. Caller pre-resolved the scope to a
     * {@code (scope_kind, scope_id)} pair (DM scopes use the caller's
     * {@code users.id} as the scope_id).
     */
    public Result fetch(String scopeKind, UUID scopeId,
                        Optional<String> positionalTag, Duration window) {
        Instant cutoff = clock.instant().minus(window);
        Optional<TopTagRestriction> restriction = Optional.empty();
        List<String> restrictedTags = List.of();

        // One pooled connection and one statement_timeout SET for the whole
        // /summary read: the up-to-four helper reads all run on this single
        // connection, so the pool sees one acquisition and one SET round-trip
        // rather than four (the SearchPostsTool single-acquisition discipline,
        // SearchPostsTool.java:81; M1-472). The public readVocabulary() is a
        // separate call and keeps its own connection.
        try (Connection conn = dataSource.getConnection()) {
            cancellationService.applyStatementTimeout(conn);

            if (positionalTag.isEmpty()) {
                int followedCount = countFollowedTags(conn, scopeKind, scopeId);
                if (followedCount > TOP_TAG_RESTRICTION_THRESHOLD) {
                    restrictedTags = topActiveFollowedTags(conn, scopeKind, scopeId, cutoff);
                    restriction = Optional.of(new TopTagRestriction(followedCount, restrictedTags));
                }
            }

            TagMode tagMode = readTagMode(conn, scopeKind, scopeId);

            Selection selection = selectPosts(conn, scopeKind, scopeId, positionalTag, cutoff,
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
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.fetch failed", e);
        }
    }

    /** {@link ScopeRef.Dm} → {@code scope_kind='dm'}, {@link ScopeRef.Group} → {@code scope_kind='group'}. */
    public static String scopeKindOf(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> "dm";
            case ScopeRef.Group ignored -> "group";
        };
    }

    /** The /topic drill-down fetch: D59 world, READY, ready_at window,
     * prefix-tolerant free-tag match. The prefix is LIKE-escaped and only
     * code appends the wildcard ({@code qw%n} matches nothing). */
    public Result fetchByTopicPrefix(String scopeKind, UUID scopeId,
                                     String prefix, Duration window) {
        Instant cutoff = clock.instant().minus(window);
        try (Connection conn = dataSource.getConnection()) {
            cancellationService.applyStatementTimeout(conn);
            // TagMode deliberately unread: the category arms do not apply.
            Selection selection = selectPosts(conn, scopeKind, scopeId,
                    Optional.empty(), cutoff, TagMode.ALL, List.of(), prefix);
            int total = selection.totalBeforeCap();
            List<Post> capped = selection.posts();
            int excluded = total - capped.size();
            return new Result(capped, total, excluded, clusterCap, profileLabel,
                    Optional.empty());
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.fetchByTopicPrefix failed", e);
        }
    }

    /** {@code \% \_ \\} — every LIKE metacharacter in {@code value}, backslash-escaped. */
    private static String escapeLike(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ----- private SQL helpers ------------------------------------------

    /** Bounded row set plus the true pre-LIMIT match count. */
    private record Selection(List<Post> posts, int totalBeforeCap) {
    }

    private Selection selectPosts(Connection conn, String scopeKind, UUID scopeId,
                                    Optional<String> positionalTag, Instant cutoff,
                                    TagMode tagMode, List<String> restrictedTags) throws SQLException {
        return selectPosts(conn, scopeKind, scopeId, positionalTag, cutoff, tagMode,
                restrictedTags, null);
    }

    private Selection selectPosts(Connection conn, String scopeKind, UUID scopeId,
                                    Optional<String> positionalTag, Instant cutoff,
                                    TagMode tagMode, List<String> restrictedTags,
                                    @Nullable String topicPrefix) throws SQLException {
        // The SELECT pins:
        //   - status='READY' (exclude RAW, QUARANTINED, NEEDS_REVIEW)
        //   - ready_at >= cutoff (window filter). ready_at, not published_at:
        //     "the last N hours" means posts that reached readers in that
        //     span, so a slow-fetched post with an old feed date still lands
        //     in the window it actually arrived in, and a post whose source
        //     supplied no date at all stops being permanently invisible
        //     (published_at is nullable; ready_at is set by every
        //     status='READY' writer) — M1-689.
        //   - the D59 world predicate: bootstrap-origin sources (live, not
        //     excluded by this scope) are implicitly visible, OR the source
        //     is in this scope's source_subscription. deleted_at IS NULL
        //     guards the bootstrap arm only — the subscription arm relies on
        //     /remove-source cascade-deleting subscription rows, as today.
        //   - optional positional tag → post.tags @> ARRAY[?]
        //   - optional scope_preferences.tag_mode='EXPLICIT' → tags intersect scope_tag
        //   - optional top-3 restricted tags → tags intersect restricted set
        //   - ORDER BY COALESCE(published_at, fetched_at) DESC, id DESC
        //     (deterministic; secondary key breaks ties stably across runs)
        //   - COUNT(*) OVER () projects the pre-LIMIT match total on every
        //     row (window functions evaluate before LIMIT), so the
        //     cap-excess counts stay exact without a second round-trip
        //   - LIMIT clusterCap bounds the rows (and bodies) materialized
        //     in Java to the cap
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT p.id, p.uid, p.source_id, s.display_name, p.title, ")
           .append("       p.url, p.body, p.published_at, p.tags, p.classification, ")
           .append("       p.reposts, p.likes, p.comments, s.kind, s.language, ")
           .append("       p.title_en, p.body_en, p.search_tags, ")
           .append("       COUNT(*) OVER (PARTITION BY p.source_id)::int AS source_window_posts, ")
           .append("       COUNT(*) OVER () AS total_count ")
           .append("  FROM post p ")
           .append("  JOIN source s ON s.id = p.source_id ")
           .append(" WHERE p.status = 'READY' ")
           .append("   AND p.ready_at >= ? ")
           .append("   AND ((s.source_origin = 'bootstrap' AND s.deleted_at IS NULL ")
           .append("         AND NOT EXISTS (SELECT 1 FROM source_exclusion e ")
           .append("                          WHERE e.scope_kind = ? AND e.scope_id = ? ")
           .append("                            AND e.source_id = s.id)) ")
           .append("     OR p.source_id IN (SELECT source_id FROM source_subscription ")
           .append("                         WHERE scope_kind = ? AND scope_id = ?)) ");
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(cutoff));
        params.add(scopeKind);
        params.add(scopeId);
        params.add(scopeKind);
        params.add(scopeId);

        if (positionalTag.isPresent()) {
            List<String> expanded = tagTreeExpansion.expandNames(conn, List.of(positionalTag.get()));
            sql.append("   AND p.tags && ?::TEXT[] ");
            params.add(expanded.toArray(new String[0]));
        } else if (!restrictedTags.isEmpty()) {
            List<String> expanded = tagTreeExpansion.expandNames(conn, restrictedTags);
            sql.append("   AND p.tags && ?::TEXT[] ");
            params.add(expanded.toArray(new String[0]));
        } else if (tagMode == TagMode.EXPLICIT) {
            sql.append("   AND p.tags && ")
               .append(TagTreeExpansion.SCOPE_FOLLOWED_LEAVES_SQL)
               .append(' ');
            params.add(scopeKind);
            params.add(scopeId);
        }
        // tag_mode='ALL' + no positional tag + ≤5 followed → no tag filter.
        if (topicPrefix != null) {
            // The /topic drill-down arm: free-tag prefix match, escaped
            // literal + code-owned wildcard (see fetchByTopicPrefix).
            sql.append("   AND EXISTS (SELECT 1 FROM unnest(p.search_tags) AS t")
               .append("                WHERE t LIKE ? || '%') ");
            params.add(escapeLike(topicPrefix));
        }

        // COALESCE, not a bare published_at: published_at is nullable and
        // Postgres sorts NULLs FIRST under DESC, so once the window predicate
        // moved to ready_at (admitting date-less posts for the first time) a
        // bare sort key would hand every one of them the head of the result —
        // ahead of every row the ingest clamp bounded. schema.md §"published_at
        // clamp" names that position as the thing the clamp defends, and the
        // clamp can only bound a date from above, never supply an absent one.
        //
        // The fallback is fetched_at, NOT ready_at. ready_at is stamped at the
        // RAW->READY promotion, so it is always LATER than the same row's
        // fetched_at — an undated post would outrank every dated post the
        // clamp had bounded to that fetch, and approve_quarantine/re-eval
        // re-stamp ready_at, letting a released post jump to a head position
        // no dated post can reach. fetched_at is the partition key: never
        // re-stamped, and the exact ceiling the clamp gives dated rows. An
        // undated post therefore sorts at the top of its own fetch cycle and
        // no higher. Dated rows are unaffected (COALESCE resolves to
        // published_at). M1-689 redteam rounds 1-2.
        sql.append(" ORDER BY COALESCE(p.published_at, p.fetched_at) DESC, p.id DESC ");
        sql.append(" LIMIT ? ");
        params.add(clusterCap);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
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
                    String[] classificationArr = (String[]) rs.getArray("classification").getArray();
                    List<String> classification = Arrays.asList(classificationArr);
                    // getObject(Integer.class), NEVER getInt: getInt coerces
                    // SQL NULL to 0 and erases the M1-723 absent-vs-zero
                    // distinction the prominence ranking depends on.
                    Integer reposts = rs.getObject("reposts", Integer.class);
                    Integer likes = rs.getObject("likes", Integer.class);
                    Integer comments = rs.getObject("comments", Integer.class);
                    String sourceKind = rs.getString("kind");
                    Integer sourceWindowPosts = rs.getObject("source_window_posts", Integer.class);
                    // Declared per source (V74, NOT NULL DEFAULT 'en'), never
                    // inferred from the body — drives the display-hit
                    // translation no-op decision (M1-747).
                    String sourceLanguage = rs.getString("language");
                    // The English anchor, NULL until the ingest translator
                    // writes it (and permanently NULL once it gives up).
                    // Read raw so the render can tell "translated" from
                    // "never translated" — see the Post javadoc (M1-759).
                    String titleEn = rs.getString("title_en");
                    String bodyEn = rs.getString("body_en");
                    // Free tags (V87): NOT NULL DEFAULT '{}' but a
                    // hand-stubbed ResultSet may still hand back null.
                    java.sql.Array searchTagsArray = rs.getArray("search_tags");
                    List<String> searchTags = searchTagsArray == null
                            ? List.of()
                            : List.of((String[]) searchTagsArray.getArray());
                    // Same value on every row; zero rows → total stays 0.
                    totalBeforeCap = rs.getInt("total_count");
                    out.add(new Post(id, uid, sourceId, displayName, title, url, body,
                            publishedAt, tags, classification,
                            reposts, likes, comments, sourceKind, sourceWindowPosts,
                            sourceLanguage, titleEn, bodyEn, searchTags));
                }
                return new Selection(out, totalBeforeCap);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.selectPosts failed", e);
        }
    }

    private int countFollowedTags(Connection conn, String scopeKind, UUID scopeId) {
        String sql = "SELECT COUNT(*) FROM scope_tag WHERE scope_kind = ? AND scope_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
    private List<String> topActiveFollowedTags(Connection conn, String scopeKind, UUID scopeId, Instant cutoff) {
        // Count per followed NODE via the recursive subtree CTE: a top counts
        // its subtree's posts; a leaf counts itself (M1-621).
        String sql =
                "WITH RECURSIVE subtree(root, node, kind) AS ("
              + " SELECT t.name, t.name, t.node_kind FROM scope_tag st JOIN tag t ON t.id = st.tag_id"
              + " WHERE st.scope_kind = ? AND st.scope_id = ?"
              + " UNION SELECT s.root, c.name, c.node_kind FROM tag c JOIN subtree s ON c.parent_name = s.node"
              + ") SELECT sub.root AS name, COUNT(*) AS post_count "
              + "  FROM post p "
              + "  JOIN source s ON s.id = p.source_id "
              + " CROSS JOIN unnest(p.tags) AS tag_name "
              + "  JOIN subtree sub ON sub.node = tag_name AND sub.kind = 'leaf' "
              + " WHERE p.status = 'READY' "
              + "   AND p.ready_at >= ? "
              + "   AND ((s.source_origin = 'bootstrap' AND s.deleted_at IS NULL "
              + "         AND NOT EXISTS (SELECT 1 FROM source_exclusion e "
              + "                          WHERE e.scope_kind = ? AND e.scope_id = ? "
              + "                            AND e.source_id = s.id)) "
              + "     OR p.source_id IN (SELECT source_id FROM source_subscription "
              + "                         WHERE scope_kind = ? AND scope_id = ?)) "
              + " GROUP BY sub.root "
              + " ORDER BY post_count DESC, sub.root ASC "
              + " LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setTimestamp(3, Timestamp.from(cutoff));
            ps.setString(4, scopeKind);
            ps.setObject(5, scopeId);
            ps.setString(6, scopeKind);
            ps.setObject(7, scopeId);
            ps.setInt(8, TOP_TAG_RESTRICTION_TOP_N);
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

    private TagMode readTagMode(Connection conn, String scopeKind, UUID scopeId) {
        String sql = "SELECT tag_mode FROM scope_preferences WHERE scope_kind = ? AND scope_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
     * the vocabulary. On SQL failure this throws {@link
     * IllegalStateException} rather than degrading to an empty set — a
     * vocabulary read that cannot reach the database is an infrastructure
     * fault that propagates to the caller (which does not catch it), the
     * same posture as {@link #readTagMode}.
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
     * Count the sources in the calling scope's D59 world: live, non-excluded
     * bootstrap sources plus the scope's subscriptions. The /summary empty
     * branch reads this ONLY when the eligible set came back empty, to tell
     * "sources exist but nothing arrived in the window" (→ no_posts_yet)
     * apart from "empty world — every bootstrap source excluded and nothing
     * subscribed" (→ the empty-world steer; M1-621, commands.md §Content).
     * Keeps its own connection (like {@link #readVocabulary}); the happy
     * /summary path never calls it, so a normal summary pays no extra
     * round-trip.
     */
    public int countWorldSources(String scopeKind, UUID scopeId) {
        String sql =
                "SELECT COUNT(*) FROM source s "
              + " WHERE (s.source_origin = 'bootstrap' AND s.deleted_at IS NULL "
              + "        AND NOT EXISTS (SELECT 1 FROM source_exclusion e "
              + "                         WHERE e.scope_kind = ? AND e.scope_id = ? "
              + "                           AND e.source_id = s.id)) "
              + "    OR s.id IN (SELECT source_id FROM source_subscription "
              + "                 WHERE scope_kind = ? AND scope_id = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = prepareTimed(conn, sql)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setString(3, scopeKind);
            ps.setObject(4, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("EligiblePostQuery.countWorldSources failed", e);
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
