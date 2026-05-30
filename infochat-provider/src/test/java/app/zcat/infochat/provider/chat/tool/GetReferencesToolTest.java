package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link GetReferencesTool}: end-to-end
 * verification that {@code post_reference} rows are projected through
 * the scope filter into the spec contract JSON shape. Seeds fixtures
 * directly via JDBC against the @QuarkusTest DevServices DB.
 */
@QuarkusTest
class GetReferencesToolTest {

    private static final String PREFIX = "get-refs-test/";
    /** All fixtures share one fetched_at so they land in the V11/V28/V29 May 2026 partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    GetReferencesTool tool;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                "DELETE FROM post_reference "
                    + "WHERE from_post IN (SELECT id FROM post WHERE uid LIKE '" + PREFIX + "%') "
                    + "   OR to_post   IN (SELECT id FROM post WHERE uid LIKE '" + PREFIX + "%')");
            exec(conn,
                "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void returnsLinkedPosts() throws Exception {
        UUID userId = seedUser("happy");
        UUID sourceId = seedSource("happy-src", "Happy source");
        seedSubscription("dm", userId, sourceId);
        UUID a = seedReadyPost("a", sourceId);
        UUID b = seedReadyPost("b", sourceId);
        seedReference(a, b, "entity", 2.0f);

        String json = tool.execute(userId, "dm", userId,
            Map.of("uid", PREFIX + "a"));

        // The tool returns the linked-post metadata — uid=b's uid, title=b's title.
        assertTrue(json.contains("\"uid\":\"" + PREFIX + "b\""),
            "response carries the destination post's uid: " + json);
        assertTrue(json.contains("\"link_type\":\"entity\""),
            "response carries the link_type: " + json);
        assertTrue(json.contains("\"score\":2"),
            "response carries the numeric score: " + json);
        // Spec shape excludes the source uid — the input post is not echoed.
        assertFalse(json.contains("\"uid\":\"" + PREFIX + "a\""),
            "response does not echo the source uid in the projection: " + json);
    }

    @Test
    void scopeFilteredDestinationDropped() throws Exception {
        // Edge exists in the DB (Collector-tier write), but the destination post
        // is not subscribed by the calling scope → must be dropped (Invariant 1).
        UUID userId = seedUser("scoped");
        UUID inScopeSrc = seedSource("scoped-in", "In scope");
        UUID outOfScopeSrc = seedSource("scoped-out", "Out of scope");
        seedSubscription("dm", userId, inScopeSrc);
        // Note: NO subscription to outOfScopeSrc.
        UUID a = seedReadyPost("scoped-a", inScopeSrc);
        UUID hidden = seedReadyPost("scoped-hidden", outOfScopeSrc);
        seedReference(a, hidden, "entity", 3.0f);

        String json = tool.execute(userId, "dm", userId,
            Map.of("uid", PREFIX + "scoped-a"));

        assertEquals("[]", json,
            "destination post outside the caller scope must not surface (Invariant 1)");
    }

    @Test
    void emptyArrayWhenNoEdges() throws Exception {
        // Mirror the v1-deferred stub's prior "[]" return shape: a post
        // with no outbound edges must still return a JSON array, never
        // an error or null.
        UUID userId = seedUser("empty");
        UUID sourceId = seedSource("empty-src", "Empty source");
        seedSubscription("dm", userId, sourceId);
        seedReadyPost("empty-a", sourceId);

        String json = tool.execute(userId, "dm", userId,
            Map.of("uid", PREFIX + "empty-a"));

        assertEquals("[]", json,
            "no edges → empty JSON array (preserves the stub's prior contract)");
    }

    // ---------- helpers ----------

    private UUID seedUser(String suffix) throws Exception {
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

    private UUID seedSource(String suffix, String displayName) throws Exception {
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

    private void seedSubscription(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
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

    private UUID seedReadyPost(String slug, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags) "
                     + "VALUES (?, ?, ?, 'Body of ' || ?, ?, ?, ?, 'READY', ?, '{}') RETURNING id")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedReference(UUID fromPost, UUID toPost, String linkType, float score) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post_reference (from_post, to_post, link_type, score) "
                     + "VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, fromPost);
            ps.setObject(2, toPost);
            ps.setString(3, linkType);
            ps.setFloat(4, score);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
