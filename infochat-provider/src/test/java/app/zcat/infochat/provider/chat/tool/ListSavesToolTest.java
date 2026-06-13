package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link ListSavesTool}'s /stop arming: the
 * single pooled connection it opens per call gets the profile-driven
 * {@code statement_timeout} applied and its Postgres backend pid
 * registered on the in-flight cancellation handle, so an in-flight
 * listSaves query is bounded and cancellable by /stop. Runs against the
 * &#64;QuarkusTest DevServices DB.
 */
@QuarkusTest
class ListSavesToolTest {

    private static final String PREFIX = "listsaves-test/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ListSavesTool tool;

    @Inject
    CancellationService cancellationService;

    @Inject
    InFlightTracker inFlightTracker;

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
    void oversizedWindowIsClampedToWindowMax() throws Exception {
        UUID userId = seedUser("clamp");
        UUID sourceId = seedSource("clamp-src");
        // One save just inside the 30-day cap, one well outside it (60 days).
        String insidePost = PREFIX + "inside";
        String outsidePost = PREFIX + "outside";
        seedSave(userId, sourceId, insidePost, Instant.now().minus(Duration.ofDays(10)));
        seedSave(userId, sourceId, outsidePost, Instant.now().minus(Duration.ofDays(60)));

        // A model-supplied window far larger than WINDOW_MAX (30 days). The
        // clamp must bound the scan to 30 days, so the 60-day-old save is
        // excluded even though an unclamped 9999-day window would include it.
        String json = tool.execute(userId, "dm", userId, Map.of("window", "P9999D"));

        assertTrue(json.contains(insidePost),
                "a save inside the 30-day cap must be returned; got: " + json);
        assertFalse(json.contains(outsidePost),
                "an oversized window must be clamped to WINDOW_MAX (30 days), excluding the "
                        + "60-day-old save; got: " + json);
    }

    @Test
    void listSavesArmsTimeoutAndRegistersPid() throws Exception {
        // Construct the tool against a counting/recording DataSource that
        // delegates to the seed DB, plus the CDI CancellationService (whose
        // InFlightTracker is the injected singleton). The query runs for real
        // (no saved_post row need exist); the wrapper observes the SET
        // statement_timeout and pg_backend_pid the arming step issues.
        UUID userId = UUID.randomUUID();
        CountingRecordingDataSource countingDs = new CountingRecordingDataSource(dataSource);
        ListSavesTool directTool = new ListSavesTool(countingDs, cancellationService);

        // Hold the in-flight slot as ChatAgent.handle() does for a chat turn,
        // so the tool has a handle to register the backend pid on.
        InFlightTracker.CancellationHandle slot =
                Objects.requireNonNull(inFlightTracker.tryAcquire(userId, "dm", userId));
        try {
            directTool.execute(userId, "dm", userId, Map.of());

            assertTrue(countingDs.executedSql().stream()
                            .anyMatch(s -> s.contains("SET LOCAL statement_timeout")),
                    "listSaves's connection must have statement_timeout applied. Got: "
                            + countingDs.executedSql());
            assertTrue(slot.hasPgBackendPid(),
                    "listSaves must register the connection's pg backend pid on the in-flight handle");
        } finally {
            inFlightTracker.release(userId, "dm", userId, slot);
        }
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
