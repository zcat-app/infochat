package app.zcat.infochat.provider.summary;

import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deterministic {@code /summary} {@code published_at} retrieval-window
 * boundary against an injected {@link Clock} (M1-454, engineering-rules §9).
 * {@link EligiblePostQuery#fetch} samples the cutoff once from
 * {@code clock.instant()} and threads it to both queries, so fixing the Clock
 * fixes the cutoff at {@code pinnedNow - window}: a post ON the cutoff is inside
 * the {@code >=} predicate and returned, one a second earlier is excluded. The
 * fixtures sit weeks before any 24-hour wall-clock cutoff, so the boundary post
 * surfacing proves the window decision read the injected instant.
 */
@QuarkusTest
class EligiblePostQueryClockIT {

    private static final String PREFIX = "m1-454q-clock-";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");

    @Inject @SeedDataSource DataSource dataSource;

    @Inject EligiblePostQuery query;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void publishedAtWindowBoundaryDecidedByInjectedClock() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);

        UUID userId = insertUser("actor");
        UUID sourceId = insertSource("src", "SRC");
        insertSubscription("dm", userId, sourceId);

        Instant cutoff = PINNED_NOW.minus(Duration.ofHours(24));
        // published_at >= cutoff is the window predicate: a post ON the cutoff
        // is included, one a second before is excluded.
        insertPost("on-cutoff", sourceId, "OnCutoff", cutoff);
        insertPost("before-cutoff", sourceId, "BeforeCutoff", cutoff.minusSeconds(1));

        Result result = query.fetch("dm", userId, Optional.empty(), Duration.ofHours(24));
        Set<String> titles = result.posts().stream()
                .map(Post::title)
                .collect(Collectors.toSet());

        assertTrue(titles.contains("OnCutoff"),
            "a post whose published_at equals the injected-clock cutoff is inside the >= "
                + "window and must be returned; got: " + titles);
        assertFalse(titles.contains("BeforeCutoff"),
            "a post published one second before the injected-clock cutoff is outside the "
                + "window and must be excluded; got: " + titles);
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
                            Instant publishedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, status, tags) "
                             + "VALUES (?, ?, ?, ?, ?, 'READY', ?)")) {
            ps.setString(1, PREFIX + uidSuffix);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setTimestamp(5, Timestamp.from(publishedAt));
            ps.setArray(6, conn.createArrayOf("TEXT", new String[] { PREFIX + "news" }));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
