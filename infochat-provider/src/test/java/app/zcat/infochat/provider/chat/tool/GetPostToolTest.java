package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link GetPostTool}'s result byte budget:
 * an oversized seeded body must come back bounded at
 * {@link GetPostTool#MAX_BODY_BYTES} with the explicit
 * {@link GetPostTool#TRUNCATION_MARKER}, while bodies within budget
 * pass through unchanged. Seeds fixtures directly via JDBC against the
 * &#64;QuarkusTest DevServices DB.
 */
@QuarkusTest
class GetPostToolTest {

    private static final String PREFIX = "get-post-test/";
    /** All fixtures share one fetched_at so they land in the V11/V28/V29 May 2026 partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    GetPostTool tool;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void oversizedBodyComesBackBoundedWithTruncationMarker() throws Exception {
        UUID userId = seedUser("oversize");
        UUID sourceId = seedSource("oversize-src", "Oversize source");
        seedSubscription("dm", userId, sourceId);
        // 1 KiB past the budget; single-byte chars so bytes == chars.
        String oversizedBody = "a".repeat(GetPostTool.MAX_BODY_BYTES + 1024);
        seedReadyPost("oversize-post", sourceId, oversizedBody);

        String json = tool.execute(userId, "dm", userId,
            Map.of("uid", PREFIX + "oversize-post"));

        assertTrue(json.contains(GetPostTool.TRUNCATION_MARKER),
            "a body over the byte budget carries the explicit truncation marker");
        assertTrue(json.contains("a".repeat(GetPostTool.MAX_BODY_BYTES)
                + GetPostTool.TRUNCATION_MARKER),
            "the body is cut exactly at MAX_BODY_BYTES, marker appended");
        assertFalse(json.contains("a".repeat(GetPostTool.MAX_BODY_BYTES + 1)),
            "no byte past the budget reaches the result");
    }

    @Test
    void bodyWithinBudgetPassesThroughUnchanged() throws Exception {
        UUID userId = seedUser("small");
        UUID sourceId = seedSource("small-src", "Small source");
        seedSubscription("dm", userId, sourceId);
        seedReadyPost("small-post", sourceId, "A short body.");

        String json = tool.execute(userId, "dm", userId,
            Map.of("uid", PREFIX + "small-post"));

        assertTrue(json.contains("\"body\":\"A short body.\""),
            "a body within budget is returned verbatim: " + json);
        assertFalse(json.contains(GetPostTool.TRUNCATION_MARKER),
            "no marker when nothing was cut");
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

    private void seedReadyPost(String slug, UUID sourceId, String body) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, '{}')")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, body);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(FETCHED_AT));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
