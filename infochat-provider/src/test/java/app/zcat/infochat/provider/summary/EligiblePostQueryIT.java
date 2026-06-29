package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-tier IT for {@link EligiblePostQuery}. Seeds a fixed set of
 * sources / subscriptions / posts / scope_preferences / scope_tag rows
 * via raw JDBC and asserts each filter rule from M1-037's acceptance
 * items 5 + 6. Test isolation is keyed on the {@code m1-037q-} prefix:
 * every fixture this IT writes carries it, and {@link #cleanup()}
 * deletes rows matching that prefix before each {@code @Test} so two
 * runs do not race and other tests' rows do not leak in.
 */
@QuarkusTest
@TestProfile(EligiblePostQueryIT.MvpProfile.class)
class EligiblePostQueryIT {

    private static final String PREFIX = "m1-037q-";

    @Inject @SeedDataSource DataSource dataSource;

    @Inject EligiblePostQuery query;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_tag "
                    + "WHERE tag_id IN (SELECT id FROM tag WHERE name LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_preferences "
                    + "WHERE scope_id IN ("
                    + "  SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM tag WHERE name LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void filtersOutNonReadyStatusRows() throws Exception {
        UUID userId = insertUser("ready-filter-user");
        UUID sourceId = insertSource("ready-filter-src", "RFS");
        insertSubscription("dm", userId, sourceId);
        // Same window for all four; status differs.
        Instant now = Instant.now();
        insertPost("ready-filter-r", sourceId, "Ready post", now, "READY", List.of(PREFIX + "news"));
        insertPost("ready-filter-q", sourceId, "Quarantined", now, "QUARANTINED", List.of(PREFIX + "news"));
        insertPost("ready-filter-n", sourceId, "Needs review", now, "NEEDS_REVIEW", List.of(PREFIX + "news"));
        insertPost("ready-filter-x", sourceId, "Raw", now, "RAW", List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(), "only READY posts are returned");
        assertEquals("Ready post", result.posts().get(0).title());
    }

    @Test
    void filtersByPublishedAtWindow() throws Exception {
        UUID userId = insertUser("window-user");
        UUID sourceId = insertSource("window-src", "WIN");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        insertPost("window-fresh", sourceId, "Fresh", now.minus(Duration.ofHours(2)), "READY",
                List.of(PREFIX + "tech"));
        insertPost("window-stale", sourceId, "Stale", now.minus(Duration.ofHours(48)), "READY",
                List.of(PREFIX + "tech"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(), "only posts inside the window survive");
        assertEquals("Fresh", result.posts().get(0).title());
    }

    @Test
    void filtersByActiveSubscriptions() throws Exception {
        UUID userId = insertUser("sub-user");
        UUID subscribedSourceId = insertSource("sub-yes", "Yes");
        UUID unsubscribedSourceId = insertSource("sub-no", "No");
        insertSubscription("dm", userId, subscribedSourceId);

        Instant now = Instant.now();
        insertPost("sub-yes-post", subscribedSourceId, "Yes!", now, "READY", List.of(PREFIX + "news"));
        insertPost("sub-no-post", unsubscribedSourceId, "No!", now, "READY", List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(), "unsubscribed sources are excluded");
        assertEquals("Yes!", result.posts().get(0).title());
    }

    @Test
    void filtersByPositionalTag() throws Exception {
        UUID userId = insertUser("tag-user");
        UUID sourceId = insertSource("tag-src", "TagS");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        insertPost("tag-sec", sourceId, "Security post", now, "READY",
                List.of(PREFIX + "sec", PREFIX + "news"));
        insertPost("tag-ai", sourceId, "AI post", now, "READY",
                List.of(PREFIX + "ai"));

        Result result = query.fetch("dm", userId, Optional.of(PREFIX + "sec"),
                Duration.ofHours(24));
        assertEquals(1, result.posts().size());
        assertEquals("Security post", result.posts().get(0).title());
    }

    @Test
    void emptyResultWhenNoSubscriptions() throws Exception {
        UUID userId = insertUser("nosub-user");
        // Source + post exist but the user has no subscription.
        UUID sourceId = insertSource("nosub-src", "NS");
        Instant now = Instant.now();
        insertPost("nosub-post", sourceId, "Lonely", now, "READY", List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertTrue(result.posts().isEmpty(), "no subscriptions → empty post set");
    }

    @Test
    void emptyResultWhenSubscribedButNoReadyPosts() throws Exception {
        UUID userId = insertUser("empty-window-user");
        UUID sourceId = insertSource("empty-window-src", "EWS");
        insertSubscription("dm", userId, sourceId);
        // No posts at all in the window.
        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertTrue(result.posts().isEmpty());
    }

    @Test
    void retainedRedactionPostIsIncluded() throws Exception {
        // stage2_failed=true posts with [REDACTED:<id>] placeholders are
        // STILL eligible per docs/spec/security.md §Failure handling.
        UUID userId = insertUser("redact-user");
        UUID sourceId = insertSource("redact-src", "RDS");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, status, "
                             + "stage2_failed, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, 'READY', TRUE, ?, ?)")) {
            ps.setString(1, PREFIX + "redact-1");
            ps.setObject(2, sourceId);
            ps.setString(3, "Redacted post");
            ps.setString(4, "Body with [REDACTED:abc123] placeholder.");
            ps.setTimestamp(5, Timestamp.from(now));
            ps.setArray(6, conn.createArrayOf("TEXT", new String[] { PREFIX + "news" }));
            ps.setString(7, PREFIX + "redact-1");
            ps.executeUpdate();
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(),
                "stage2_failed=true posts with [REDACTED:...] placeholders remain eligible");
        assertTrue(result.posts().get(0).body().contains("[REDACTED:"),
                "the placeholder is preserved in the projected body");
    }

    @Test
    void capDropsOldestAndReportsExcludedCount() throws Exception {
        UUID userId = insertUser("cap-user");
        UUID sourceId = insertSource("cap-src", "CAP");
        insertSubscription("dm", userId, sourceId);
        // Profile cap defaults to 200 in test profile; seed 205 posts.
        // Cluster cap is %test = 5 (set in MvpProfile) so we don't pay
        // 200 inserts per test run.
        Instant now = Instant.now();
        for (int i = 0; i < 8; i++) {
            // Stagger by minutes so ORDER BY published_at DESC has a
            // deterministic newest→oldest sequence.
            insertPost("cap-" + i, sourceId, "Post " + i,
                    now.minus(Duration.ofMinutes(i)), "READY", List.of(PREFIX + "news"));
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(5, result.posts().size(),
                "cap from MvpProfile is 5; surplus is dropped");
        assertEquals(8, result.totalBeforeCap());
        assertEquals(3, result.excludedCount());
        assertEquals(5, result.profileCap());
        // Freshest 5 retained (posts 0–4 in time order).
        assertEquals("Post 0", result.posts().get(0).title());
        assertEquals("Post 4", result.posts().get(4).title());
    }

    @Test
    void sqlLimitBoundsMaterializedRowsToClusterCap() throws Exception {
        // The main query carries a SQL-side LIMIT: seeding more
        // eligible posts than clusterCap must never materialize more
        // than the cap in Java — the bound is applied before bodies
        // leave the database, not by a post-hoc subList.
        UUID userId = insertUser("sql-bound-user");
        UUID sourceId = insertSource("sql-bound-src", "SQB");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        for (int i = 0; i < 9; i++) {
            insertPost("sql-bound-" + i, sourceId, "Bound " + i,
                    now.minus(Duration.ofMinutes(i)), "READY", List.of(PREFIX + "news"));
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(5, result.posts().size(),
                "9 eligible posts against cap 5 — the SQL LIMIT bounds the rows");
        // The LIMIT keeps the head of the DESC ordering: freshest five.
        assertEquals("Bound 0", result.posts().get(0).title());
        assertEquals("Bound 4", result.posts().get(4).title());
    }

    @Test
    void sqlLimitPreservesTotalAndExcludedCounts() throws Exception {
        // Cap-excess reporting must not regress under the SQL bound:
        // the window-function count keeps total/excluded exact even
        // though only cap rows come back (a naive LIMIT would report
        // total == cap and excluded == 0).
        UUID userId = insertUser("sql-count-user");
        UUID sourceId = insertSource("sql-count-src", "SQC");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        for (int i = 0; i < 7; i++) {
            insertPost("sql-count-" + i, sourceId, "Count " + i,
                    now.minus(Duration.ofMinutes(i)), "READY", List.of(PREFIX + "news"));
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(5, result.posts().size());
        assertEquals(7, result.totalBeforeCap(),
                "totalBeforeCap is the true pre-LIMIT match count");
        assertEquals(2, result.excludedCount(),
                "excludedCount composes the cap-excess message exactly");
        assertEquals(5, result.profileCap());
    }

    @Test
    void deterministicOrderingByPublishedAtThenIdDesc() throws Exception {
        UUID userId = insertUser("order-user");
        UUID sourceId = insertSource("order-src", "ORD");
        insertSubscription("dm", userId, sourceId);
        Instant now = Instant.now();
        insertPost("order-a", sourceId, "A", now.minus(Duration.ofMinutes(10)), "READY",
                List.of(PREFIX + "news"));
        insertPost("order-b", sourceId, "B", now.minus(Duration.ofMinutes(5)), "READY",
                List.of(PREFIX + "news"));
        insertPost("order-c", sourceId, "C", now.minus(Duration.ofMinutes(20)), "READY",
                List.of(PREFIX + "news"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        List<String> titles = result.posts().stream().map(Post::title).toList();
        // Newest → oldest by published_at.
        assertEquals(List.of("B", "A", "C"), titles);
    }

    @Test
    void explicitTagModeRestrictsToScopeTagUnion() throws Exception {
        UUID userId = insertUser("explicit-user");
        UUID sourceId = insertSource("explicit-src", "EXS");
        insertSubscription("dm", userId, sourceId);
        UUID techTagId = insertTag("explicit-tech");
        UUID sportsTagId = insertTag("explicit-sports");
        insertScopeTag("dm", userId, techTagId);
        // sports is NOT a followed tag; sports posts must be excluded.
        insertScopePreferences("dm", userId, "EXPLICIT");

        Instant now = Instant.now();
        insertPost("explicit-tech", sourceId, "Tech post", now, "READY",
                List.of(PREFIX + "explicit-tech"));
        insertPost("explicit-sports", sourceId, "Sports post", now, "READY",
                List.of(PREFIX + "explicit-sports"));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertEquals(1, result.posts().size(),
                "EXPLICIT tag_mode + scope_tag={tech} excludes the sports post");
        assertEquals("Tech post", result.posts().get(0).title());
    }

    @Test
    void top3FollowedTagsRuleWithTieBreakOnNameAsc() throws Exception {
        // Acceptance item 6: when scope follows >5 tags AND no positional
        // tag, retrieval restricts to the top 3 most-active in the
        // window, ordered count DESC, name ASC. Seed 7 followed tags
        // + uneven post distribution; include at least one tied pair.
        UUID userId = insertUser("top3-user");
        UUID sourceId = insertSource("top3-src", "T3S");
        insertSubscription("dm", userId, sourceId);

        // 7 followed tags. Counts (post count in 24h window):
        //   alpha=5, beta=4, gamma=4, delta=3, epsilon=2, zeta=1, eta=1.
        // beta and gamma are tied at 4; tie-break → beta first (name ASC).
        // delta=3 is the third. Top-3 set = {alpha, beta, gamma}.
        Map<String, Integer> counts = new HashMap<>();
        counts.put(PREFIX + "alpha", 5);
        counts.put(PREFIX + "beta", 4);
        counts.put(PREFIX + "gamma", 4);
        counts.put(PREFIX + "delta", 3);
        counts.put(PREFIX + "epsilon", 2);
        counts.put(PREFIX + "zeta", 1);
        counts.put(PREFIX + "eta", 1);
        for (String tagName : counts.keySet()) {
            UUID tagId = insertTag(tagName.substring(PREFIX.length()));
            insertScopeTag("dm", userId, tagId);
        }
        insertScopePreferences("dm", userId, "ALL");

        Instant now = Instant.now();
        int seq = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String tagName = e.getKey();
            for (int i = 0; i < e.getValue(); i++) {
                insertPost("top3-" + tagName + "-" + i, sourceId,
                        tagName + " post " + i,
                        now.minus(Duration.ofMinutes(seq++)),
                        "READY",
                        List.of(tagName));
            }
        }

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertTrue(result.topTagRestriction().isPresent(),
                "scope follows >5 tags → top-3 restriction applies");
        EligiblePostQuery.TopTagRestriction restriction = result.topTagRestriction().get();
        assertEquals(7, restriction.followedTagCount());
        assertEquals(List.of(PREFIX + "alpha", PREFIX + "beta", PREFIX + "gamma"),
                restriction.topTagNames(),
                "top-3 ordered count DESC, then name ASC (beta < gamma)");

        // Every returned post intersects the top-3 tag set.
        long delta = result.posts().stream()
                .filter(p -> p.tags().contains(PREFIX + "delta"))
                .count();
        assertEquals(0, delta, "delta-tagged posts are outside top-3 and excluded");

        // The followedCount>threshold path now threads ONE shared connection
        // through all four helpers (countFollowedTags → topActiveFollowedTags →
        // readTagMode → selectPosts), M1-472. Tighten the eligible-set
        // assertions to pin that the restricted set is unchanged: the pre-cap
        // match count is exactly the 13 alpha/beta/gamma posts (5+4+4), and
        // every returned post carries a top-3 tag.
        assertEquals(13, result.totalBeforeCap(),
                "only the 13 alpha/beta/gamma posts match under the top-3 restriction");
        Set<String> top3 = Set.of(PREFIX + "alpha", PREFIX + "beta", PREFIX + "gamma");
        assertTrue(result.posts().stream()
                        .allMatch(p -> p.tags().stream().anyMatch(top3::contains)),
                "every returned post intersects the top-3 tag set on the shared connection");
    }

    @Test
    void noTop3RestrictionWhenScopeFollows5OrFewerTags() throws Exception {
        UUID userId = insertUser("five-user");
        UUID sourceId = insertSource("five-src", "FIV");
        insertSubscription("dm", userId, sourceId);
        for (int i = 0; i < 5; i++) {
            UUID t = insertTag("five-" + i);
            insertScopeTag("dm", userId, t);
        }
        insertScopePreferences("dm", userId, "ALL");

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        assertFalse(result.topTagRestriction().isPresent(),
                "exactly 5 followed tags is at threshold → no top-3 restriction");
    }

    // ----- helpers ------------------------------------------------------

    private UUID insertUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES ('inmemory', ?, FALSE, 'vouched') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID insertSource(String suffix, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertSubscription(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private void insertPost(String uidSuffix, UUID sourceId, String title,
                             Instant publishedAt, String status, List<String> tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, status, tags, "
                             + "upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, PREFIX + uidSuffix);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setTimestamp(5, Timestamp.from(publishedAt));
            ps.setString(6, status);
            ps.setArray(7, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            ps.setString(8, PREFIX + uidSuffix);
            ps.executeUpdate();
        }
    }

    private UUID insertTag(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tag (name, display, source_origin) "
                             + "VALUES (?, ?, 'user') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertScopeTag(String scopeKind, UUID scopeId, UUID tagId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, tagId);
            ps.executeUpdate();
        }
    }

    private void insertScopePreferences(String scopeKind, UUID scopeId, String tagMode) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                             + "VALUES (?, ?, ?) ON CONFLICT (scope_kind, scope_id) "
                             + "DO UPDATE SET tag_mode = EXCLUDED.tag_mode")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setString(3, tagMode);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * Pinning {@code cluster-cap=5} keeps the cap-excess test from
     * inserting 200 posts (the production laptop default).
     */
    public static final class MvpProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of(
                    "infochat.summary.cluster-cap", "5",
                    "infochat.profile.label", "test");
        }
    }
}
