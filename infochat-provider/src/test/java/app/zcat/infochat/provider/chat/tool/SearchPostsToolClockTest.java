package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusMock;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link SearchPostsTool} {@code published_at} retrieval-window
 * boundary against an injected {@link Clock} (M1-454, engineering-rules §9).
 * With the Clock fixed, the window cutoff is {@code pinnedNow - window}: a post
 * whose {@code ready_at} equals that cutoff is inside the {@code >=}
 * predicate and returned; one a second earlier is excluded. The fixtures live
 * in May 2026, weeks before any 2-hour wall-clock cutoff, so the boundary post
 * surfacing can only come from the pinned Clock — proving the window decision
 * reads {@code clock.instant()}, not {@code Instant.now()}.
 *
 * <p>The window compares against {@code ready_at}, not the source-supplied
 * {@code published_at} (M1-689), so both fixtures carry one shared
 * {@code published_at} far outside the window: nothing but {@code ready_at}
 * can account for them landing on opposite sides of the boundary.
 */
@QuarkusTest
class SearchPostsToolClockTest {

    private static final String PREFIX = "m1-454-search-clock/";
    /** All fixtures share one fetched_at so they land in the May 2026 partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");

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
    void readyAtWindowBoundaryDecidedByInjectedClock() throws Exception {
        // The injected Clock is @ApplicationScoped, so the proxy in the tool's
        // field resolves to this mock for the rest of the call.
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);

        UUID userId = seedUser("actor");
        UUID sourceId = seedSource("src");
        seedSubscription("dm", userId, sourceId);

        Instant cutoff = PINNED_NOW.minus(Duration.ofHours(2));
        // ready_at >= cutoff is the window predicate: a post ON the cutoff is
        // included, one a second before is excluded. Both fixtures share a
        // published_at 30 days outside the window, so only ready_at can put
        // them on opposite sides of the boundary.
        Instant sharedPublishedAt = cutoff.minus(Duration.ofDays(30));
        seedReadyPost("on-cutoff", sourceId, sharedPublishedAt, cutoff);
        seedReadyPost("before-cutoff", sourceId, sharedPublishedAt, cutoff.minusSeconds(1));

        String json = tool.execute(userId, "dm", userId, Map.of("window", "PT2H"));

        assertTrue(json.contains(PREFIX + "on-cutoff"),
            "a post whose ready_at equals the injected-clock cutoff is inside the "
                + ">= window and must be returned; got: " + json);
        assertFalse(json.contains(PREFIX + "before-cutoff"),
            "a post that became ready one second before the injected-clock cutoff is "
                + "outside the window and must be excluded; got: " + json);
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

    private UUID seedSource(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, "Source " + suffix);
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

    private void seedReadyPost(String slug, UUID sourceId, Instant publishedAt,
                               Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, '{}', ?)")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, "Body " + slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.setString(9, PREFIX + slug);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
