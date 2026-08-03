package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.rss.RssFetcher;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the flap-containment leg of D42's re-probe budget (M1-754): a
 * successful restore does NOT refill the cap counter — clearing it is
 * gated on the sustained-success window — so
 * park→probe→restore→immediately-re-park converges on the terminal
 * cap instead of cycling forever. This is what stops a
 * deliberately-flapping feed from re-opening ingest indefinitely.
 */
@QuarkusTest
class ReprobeBudgetNoRefillOnRestoreIT {

    private static final String PREFIX = "m1-754-budget-";
    private static final Instant PINNED_NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Inject
    ReprobeScheduler reprobeScheduler;

    @Inject
    SourceRepository sourceRepository;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @ConfigProperty(name = "infochat.fetch.reprobe.cap")
    int reprobeCap;

    @ConfigProperty(name = "infochat.fetch.reprobe.sustained-success-window")
    Duration sustainedSuccessWindow;

    private SucceedingRssFetcher mockFetcher;

    @BeforeEach
    void setup() throws Exception {
        mockFetcher = new SucceedingRssFetcher();
        QuarkusMock.installMockForType(mockFetcher, RssFetcher.class,
            new FetcherKind.Literal("rss"));
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM source WHERE identifier LIKE ?")) {
            ps.setString(1, "https://example.com/" + PREFIX + "%");
            ps.executeUpdate();
        }
    }

    @Test
    void flapCycleReachesTerminalCapInsteadOfCyclingForever() throws Exception {
        UUID sourceId = seedParkedDue("flap");

        for (int attempt = 1; attempt <= reprobeCap; attempt++) {
            reprobeScheduler.runOnce();

            assertEquals("active", readStatus(sourceId),
                "flap cycle attempt " + attempt + ": the probe must restore the row");
            assertEquals(attempt, readReprobeCount(sourceId),
                "flap cycle attempt " + attempt + ": the restore must KEEP the "
                    + "incremented cap counter — a reset here is the unbounded-cycle bug");
            assertEquals(0, readConsecutiveFailures(sourceId),
                "flap cycle attempt " + attempt + ": the restore must zero "
                    + "consecutive_failures (the one counter it may reset)");

            // The feed flaps: it re-parks immediately, inside the
            // sustained-success window (the fixed clock never advances,
            // so the window can never elapse and never clears the count).
            repark(sourceId);
        }

        int probesAtCap = mockFetcher.callCount;
        reprobeScheduler.runOnce();

        assertEquals(probesAtCap, mockFetcher.callCount,
            "at the absolute cap the source is terminally parked: the re-probe "
                + "path must never select it again");
        assertEquals("failed", readStatus(sourceId),
            "the terminally-capped source must stay parked (only /source-enable revives)");
        assertEquals(reprobeCap, readReprobeCount(sourceId),
            "the terminal count must sit exactly at the cap");
    }

    @Test
    void sustainedSuccessWindowClearsCounter() throws Exception {
        // Positive control for the gate: an ACTIVE row restored longer
        // than the window ago has proven itself healthy and gets its
        // budget back (the half-open→closed circuit-breaker transition).
        UUID sourceId = seedActiveRestored("healthy",
            PINNED_NOW.minus(sustainedSuccessWindow).minusSeconds(3600), 4);

        reprobeScheduler.runOnce();

        assertEquals(0, readReprobeCount(sourceId),
            "a source healthy past the sustained-success window must have its "
                + "re-probe budget cleared");
    }

    @Test
    void insideSustainedSuccessWindowKeepsCounter() throws Exception {
        UUID sourceId = seedActiveRestored("probation",
            PINNED_NOW.minus(sustainedSuccessWindow).plusSeconds(3600), 4);

        reprobeScheduler.runOnce();

        assertEquals(4, readReprobeCount(sourceId),
            "a source still inside the sustained-success window keeps its spent "
                + "budget — the clear is gated on the window, not the restore");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedParkedDue(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, parked_at, "
                     + "  next_reprobe_at, consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'failed', 'fetch-failure', "
                     + "  now(), ?, 3) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setTimestamp(3, Timestamp.from(PINNED_NOW.minusSeconds(60)));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedActiveRestored(String slug, Instant restoredAt, int reprobeCount)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, reprobe_count, reprobe_restored_at) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active', ?, ?) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setInt(3, reprobeCount);
            ps.setTimestamp(4, Timestamp.from(restoredAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    // The flap: back to failed/fetch-failure with a due probe slot, as
    // the D42 ladder would park it after threshold failures. Direct SQL
    // keeps the cycle deterministic under the fixed clock.
    private void repark(UUID sourceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE source SET status = 'failed', park_reason = 'fetch-failure', "
                     + "  parked_at = now(), next_reprobe_at = ?, consecutive_failures = 3 "
                     + "WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(PINNED_NOW.minusSeconds(60)));
            ps.setObject(2, sourceId);
            ps.executeUpdate();
        }
    }

    private String readStatus(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int readReprobeCount(UUID sourceId) throws Exception {
        return readIntColumn(sourceId, "reprobe_count");
    }

    private int readConsecutiveFailures(UUID sourceId) throws Exception {
        return readIntColumn(sourceId, "consecutive_failures");
    }

    private int readIntColumn(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Always-successful empty-batch probe. */
    static final class SucceedingRssFetcher extends RssFetcher {
        volatile int callCount;

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            callCount++;
            return List.of();
        }
    }
}
