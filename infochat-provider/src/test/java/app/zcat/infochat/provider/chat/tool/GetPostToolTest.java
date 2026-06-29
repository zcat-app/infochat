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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
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

    @Inject
    CancellationService cancellationService;

    @Inject
    InFlightTracker inFlightTracker;

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

    @Test
    void readyAtFieldCarriesReadyAtColumnValueNotPublishedAt() throws Exception {
        UUID userId = seedUser("ready-at");
        UUID sourceId = seedSource("ready-at-src", "Ready-at source");
        seedSubscription("dm", userId, sourceId);
        // Distinct published_at vs ready_at so the assertion can tell
        // the two columns apart.
        Instant publishedAt = FETCHED_AT.minus(2, ChronoUnit.HOURS);
        Instant readyAt = FETCHED_AT.plus(15, ChronoUnit.MINUTES);
        seedReadyPost("ready-at-post", sourceId, "A body.", publishedAt, readyAt);

        String json = tool.execute(userId, "dm", userId,
            Map.of("uid", PREFIX + "ready-at-post"));

        assertTrue(json.contains("\"ready_at\":\"" + readyAt + "\""),
            "the ready_at JSON field carries the ready_at column value; got: " + json);
        assertFalse(json.contains("\"ready_at\":\"" + publishedAt + "\""),
            "the ready_at JSON field must not carry published_at; got: " + json);
    }

    @Test
    void getPostArmsTimeoutAndRegistersPid() throws Exception {
        // Construct the tool against a counting/recording DataSource that
        // delegates to the seed DB, plus the CDI CancellationService (whose
        // InFlightTracker is the injected singleton). The query runs for real
        // (no row need exist); the wrapper observes the SET LOCAL statement_timeout
        // and pg_backend_pid the arming step issues.
        UUID userId = UUID.randomUUID();
        CountingRecordingDataSource countingDs = new CountingRecordingDataSource(dataSource);
        GetPostTool directTool = new GetPostTool(countingDs, cancellationService);

        // Hold the in-flight slot as ChatAgent.handle() does for a chat turn,
        // so the tool has a handle to register the backend pid on.
        InFlightTracker.CancellationHandle slot =
                Objects.requireNonNull(inFlightTracker.tryAcquire(userId, "dm", userId));
        try {
            directTool.execute(userId, "dm", userId, Map.of("uid", PREFIX + "absent"));

            assertTrue(countingDs.executedSql().stream()
                            .anyMatch(s -> s.contains("SET LOCAL statement_timeout")),
                    "getPost's connection must have statement_timeout applied. Got: "
                            + countingDs.executedSql());
            assertTrue(slot.hasPgBackendPid(),
                    "getPost must register the connection's pg backend pid on the in-flight handle");
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
        seedReadyPost(slug, sourceId, body, FETCHED_AT, FETCHED_AT);
    }

    private void seedReadyPost(String slug, UUID sourceId, String body,
                               Instant publishedAt, Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, '{}', ?)")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, body);
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
