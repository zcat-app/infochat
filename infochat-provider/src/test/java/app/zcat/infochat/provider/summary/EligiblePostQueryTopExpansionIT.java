package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.summary.EligiblePostQuery.TopTagRestriction;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tree-aware positional and top-3 retrieval in {@link EligiblePostQuery} (M1-867). */
@QuarkusTest
@TestProfile(EligiblePostQueryTopExpansionIT.Profile.class)
class EligiblePostQueryTopExpansionIT {

    private static final String PREFIX = "m1-867t-";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");

    @Inject @SeedDataSource DataSource dataSource;
    @Inject EligiblePostQuery query;

    @BeforeEach
    @AfterEach
    void cleanup() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_preferences "
                    + "WHERE scope_id IN (SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_tag "
                    + "WHERE scope_id IN (SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void positionalTopExpandsToSubtreeLeavesForTheFilter() throws Exception {
        UUID userId = insertUser("top-positional");
        UUID sourceId = insertSource("top-positional-src", "TPOS");
        insertSubscription("dm", userId, sourceId);
        insertPost("top-ai-1", sourceId, "AI one", "ai");
        insertPost("top-ai-2", sourceId, "AI two", "ai");
        insertPost("top-cyb-1", sourceId, "Cyber one", "cybersecurity");
        insertPost("top-cyb-2", sourceId, "Cyber two", "cybersecurity");
        insertPost("top-foot-1", sourceId, "Football one", "football");

        Result result = query.fetch("dm", userId, Optional.of("tech"), Duration.ofHours(24));

        assertEquals(4, result.posts().size(),
                "positional tech expands to its subtree leaves (ai + cybersecurity)");
        assertEquals(List.of("AI one", "AI two", "Cyber one", "Cyber two"),
                result.posts().stream().map(EligiblePostQuery.Post::title).sorted().toList(),
                "football lives under sport, never under tech");
    }

    @Test
    void positionalLeafResolvesToItselfUnchanged() throws Exception {
        UUID userId = insertUser("leaf-positional");
        UUID sourceId = insertSource("leaf-positional-src", "LPOS");
        insertSubscription("dm", userId, sourceId);
        insertPost("leaf-ai-1", sourceId, "AI one", "ai");
        insertPost("leaf-ai-2", sourceId, "AI two", "ai");
        insertPost("leaf-cyb-1", sourceId, "Cyber one", "cybersecurity");

        Result result = query.fetch("dm", userId, Optional.of("ai"), Duration.ofHours(24));

        assertEquals(2, result.posts().size(),
                "a positional leaf resolves to itself — no subtree widening");
        assertEquals(List.of("AI one", "AI two"),
                result.posts().stream().map(EligiblePostQuery.Post::title).sorted().toList());
    }

    @Test
    void top3RestrictionCountsSubtreeActivityPerFollowedNodeAndExpandsTheRestrictedSet()
            throws Exception {
        // 6 followed nodes (threshold is >5): the tech TOP plus five leaves.
        // tech's subtree (5 posts) beats football (2), then basketball (1) —
        // the top-3 per NODE expands tech's subtree, never the markets post.
        UUID userId = insertUser("top3-tree");
        UUID sourceId = insertSource("top3-tree-src", "T3T");
        insertSubscription("dm", userId, sourceId);
        for (String name : List.of("tech", "football", "basketball", "hockey", "tennis", "motorsport")) {
            insertScopeTag("dm", userId, tagIdOf(name));
        }
        insertScopePreferences("dm", userId, "ALL");
        for (int i = 1; i <= 4; i++) {
            insertPost("top3-ai-" + i, sourceId, "T3T AI " + i, "ai");
        }
        insertPost("top3-cyb-1", sourceId, "T3T Cyber 1", "cybersecurity");
        for (int i = 1; i <= 2; i++) {
            insertPost("top3-foot-" + i, sourceId, "T3T Foot " + i, "football");
        }
        insertPost("top3-basket-1", sourceId, "T3T Basket 1", "basketball");
        insertPost("top3-markets-1", sourceId, "T3T Markets 1", "markets");

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));

        assertTrue(result.topTagRestriction().isPresent(), "6 followed nodes fires the restriction");
        TopTagRestriction restriction = result.topTagRestriction().get();
        assertEquals(6, restriction.followedTagCount());
        assertEquals(List.of("tech", "football", "basketball"),
                restriction.topTagNames(),
                "top-3 per followed NODE: tech (5 subtree posts), football (2), basketball (1)");
        assertEquals(8, result.totalBeforeCap(),
                "the restricted set EXPANDS tech to its whole subtree: 4 ai + 1 cybersecurity "
                        + "+ 2 football + 1 basketball posts match; the markets post never enters it");
        assertTrue(result.posts().stream().allMatch(p ->
                        p.tags().stream().anyMatch(t -> t.equals("ai")
                                || t.equals("cybersecurity") || t.equals("football")
                                || t.equals("basketball"))),
                "every returned post carries a leaf of the restricted node set");
    }

    // ----- helpers ----------------------------------------------------------

    private UUID tagIdOf(String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM tag WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

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

    private void insertScopePreferences(String scopeKind, UUID scopeId, String tagMode)
            throws Exception {
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

    private void insertPost(String uidSuffix, UUID sourceId, String title, String tag)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, fetched_at, "
                             + "ready_at, status, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?)")) {
            ps.setString(1, PREFIX + uidSuffix);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setTimestamp(5, Timestamp.from(PINNED_NOW.minus(Duration.ofHours(2))));
            ps.setTimestamp(6, Timestamp.from(PINNED_NOW.minus(Duration.ofHours(2))));
            ps.setTimestamp(7, Timestamp.from(PINNED_NOW.minus(Duration.ofMinutes(30))));
            ps.setArray(8, conn.createArrayOf("TEXT", new String[] { tag }));
            ps.setString(9, PREFIX + uidSuffix);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("infochat.summary.cluster-cap", "200");
        }
    }
}
