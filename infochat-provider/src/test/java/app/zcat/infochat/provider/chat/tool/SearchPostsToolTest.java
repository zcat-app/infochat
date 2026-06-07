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
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link SearchPostsTool}'s result shape:
 * the emitted {@code ready_at} JSON field carries the post's
 * {@code ready_at} column value (the spec's tool-catalogue shape),
 * not {@code published_at}. Seeds fixtures directly via JDBC against
 * the &#64;QuarkusTest DevServices DB.
 */
@QuarkusTest
class SearchPostsToolTest {

    private static final String PREFIX = "search-posts-test/";
    /** All fixtures share one fetched_at so they land in the V11/V28/V29 May 2026 partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    SearchPostsTool tool;

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
    void readyAtFieldCarriesReadyAtColumnValueNotPublishedAt() throws Exception {
        UUID userId = seedUser("ready-at");
        UUID sourceId = seedSource("ready-at-src", "Ready-at source");
        seedSubscription("dm", userId, sourceId);
        // published_at must sit inside the default search window
        // (published_at is the window filter); ready_at is a distinct
        // value so the assertion can tell the two columns apart.
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        Instant readyAt = publishedAt.plus(15, ChronoUnit.MINUTES);
        seedReadyPost("ready-at-post", sourceId, publishedAt, readyAt);

        String json = tool.execute(userId, "dm", userId, Map.of());

        assertTrue(json.contains("\"ready_at\":\"" + readyAt + "\""),
            "the ready_at JSON field carries the ready_at column value; got: " + json);
        assertFalse(json.contains("\"ready_at\":\"" + publishedAt + "\""),
            "the ready_at JSON field must not carry published_at; got: " + json);
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

    private void seedReadyPost(String slug, UUID sourceId,
                               Instant publishedAt, Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, '{}')")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, "Body " + slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
