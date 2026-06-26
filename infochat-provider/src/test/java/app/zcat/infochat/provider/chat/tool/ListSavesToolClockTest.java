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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link ListSavesTool} {@code saved_at} retrieval-window boundary
 * against an injected {@link Clock} (M1-454, engineering-rules §9). With the
 * Clock fixed, the cutoff is {@code pinnedNow - window}: a save whose
 * {@code saved_at} equals the cutoff is inside the {@code >=} predicate and
 * returned, one a second earlier is excluded. The fixtures sit weeks before any
 * 7-day wall-clock cutoff, so the boundary save surfacing can only come from
 * the pinned Clock.
 */
@QuarkusTest
class ListSavesToolClockTest {

    private static final String PREFIX = "m1-454-listsaves-clock/";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ListSavesTool tool;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                "DELETE FROM saved_post WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void savedAtWindowBoundaryDecidedByInjectedClock() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);

        UUID userId = seedUser("actor");
        UUID sourceId = seedSource("src");

        Instant cutoff = PINNED_NOW.minus(Duration.ofDays(7));
        // saved_at >= cutoff is the window predicate: a save ON the cutoff is
        // included, one a second before is excluded.
        seedSave(userId, sourceId, PREFIX + "on-cutoff", cutoff);
        seedSave(userId, sourceId, PREFIX + "before-cutoff", cutoff.minusSeconds(1));

        String json = tool.execute(userId, "dm", userId, Map.of("window", "P7D"));

        assertTrue(json.contains(PREFIX + "on-cutoff"),
            "a save whose saved_at equals the injected-clock cutoff is inside the >= "
                + "window and must be returned; got: " + json);
        assertFalse(json.contains(PREFIX + "before-cutoff"),
            "a save one second before the injected-clock cutoff is outside the window "
                + "and must be excluded; got: " + json);
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

    private void seedSave(UUID userId, UUID sourceId, String postUid, Instant savedAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO saved_post (user_id, post_uid, source_id, title, saved_at) "
                     + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.setString(4, "Title for " + postUid);
            ps.setObject(5, OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
