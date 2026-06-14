package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
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

    @Test
    void budgetDropsOldestEntriesBeyondMaxResultBytes() throws Exception {
        UUID userId = seedUser("budget");
        UUID sourceId = seedSource("budget-src");
        // Each title is ~1 KiB (under MAX_TITLE_BYTES, so no per-title
        // truncation); 30 such rows is ~31 KiB of JSON, well past the 16 KiB
        // aggregate budget. The newest entries must be kept and the oldest
        // dropped (ORDER BY saved_at DESC). All 30 saves sit inside the
        // 30-day window so only the byte budget — not the window — drops rows.
        String title = "t".repeat(1024);
        int rows = 30;
        for (int i = 0; i < rows; i++) {
            seedSaveWithTitle(userId, sourceId, PREFIX + "budget-" + i, title,
                    Instant.now().minus(Duration.ofHours(i + 1)));
        }

        String json = tool.execute(userId, "dm", userId, Map.of());

        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= ListSavesTool.MAX_RESULT_BYTES,
                "the returned array must stay within MAX_RESULT_BYTES; got "
                        + json.getBytes(StandardCharsets.UTF_8).length + " bytes");
        assertTrue(json.contains(PREFIX + "budget-0"),
                "the newest entry must be retained; got: " + json);
        assertFalse(json.contains(PREFIX + "budget-" + (rows - 1)),
                "the oldest entry must be dropped once the budget is exhausted; got: " + json);
    }

    @Test
    void oversizedTitleTruncatedToMaxTitleBytes() throws Exception {
        UUID userId = seedUser("bigtitle");
        UUID sourceId = seedSource("bigtitle-src");
        // One save whose external title far exceeds MAX_TITLE_BYTES (2 KiB).
        String hugeTitle = "y".repeat(5000);
        seedSaveWithTitle(userId, sourceId, PREFIX + "big", hugeTitle,
                Instant.now().minus(Duration.ofHours(1)));

        String json = tool.execute(userId, "dm", userId, Map.of());

        assertTrue(json.contains(GetPostTool.TRUNCATION_MARKER),
                "an oversized title must be truncated with the marker; got: " + json);
        assertFalse(json.contains("y".repeat(3000)),
                "the title must be cut to MAX_TITLE_BYTES, not reinjected whole; got length: "
                        + json.length());
    }

    @Test
    void normalLibraryReturnedUnchanged() throws Exception {
        UUID userId = seedUser("normal");
        UUID sourceId = seedSource("normal-src");
        seedSaveWithTitle(userId, sourceId, PREFIX + "one", "First saved title",
                Instant.now().minus(Duration.ofHours(2)));
        seedSaveWithTitle(userId, sourceId, PREFIX + "two", "Second saved title",
                Instant.now().minus(Duration.ofHours(1)));

        String json = tool.execute(userId, "dm", userId, Map.of());

        assertFalse(json.contains(GetPostTool.TRUNCATION_MARKER),
                "a small library must not be truncated; got: " + json);
        assertTrue(json.contains("First saved title") && json.contains("Second saved title"),
                "both small titles must be returned in full; got: " + json);
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
        seedSaveWithTitle(userId, sourceId, postUid, "Title for " + postUid, savedAt);
    }

    private void seedSaveWithTitle(UUID userId, UUID sourceId, String postUid,
                                   String title, Instant savedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO saved_post (user_id, post_uid, source_id, title, saved_at) "
                     + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.setString(4, title);
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
