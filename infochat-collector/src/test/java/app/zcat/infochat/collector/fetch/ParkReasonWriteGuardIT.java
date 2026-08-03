package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins D42 property (a) on {@code SourceRepository.RECORD_FAILURE_SQL}
 * (M1-754): the park-reason term rides the SAME
 * {@code consecutive_failures + 1 >= threshold AND status = 'active'}
 * guard as the status flip — NOT the deliberately-unconditional
 * counter increment, which fires even against an already-'failed' row.
 * The write-after-park leg here is the case the static-seed
 * re-probe-exclusion tests cannot reach: the hazard is a LIVE
 * {@code recordFailure} call landing on a row another writer parked
 * moments earlier and relabeling its manual-only security park as
 * re-probe-eligible.
 */
@QuarkusTest
class ParkReasonWriteGuardIT {

    private static final String PREFIX = "m1-754-guard-";

    @Inject
    SourceRepository sourceRepository;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM source WHERE identifier LIKE ?")) {
            ps.setString(1, "https://example.com/" + PREFIX + "%");
            ps.executeUpdate();
        }
    }

    @Test
    void recordFailureAgainstUnknownRateParkKeepsReason() throws Exception {
        // Already parked by the security control: counter high enough
        // that an UNGUARDED reason term would fire (3 + 1 >= 3), so the
        // assertion below fails if the reason ever leaves the guard.
        UUID sourceId = seedSource("writeAfterPark", "failed", "unknown-rate", 3);

        sourceRepository.recordFailure(sourceId, 3);

        assertEquals("unknown-rate", readParkReason(sourceId),
            "recordFailure against a row PerSourceUnknownTracker parked must NOT "
                + "relabel its manual-only reason as 'fetch-failure' (D42 property (a): "
                + "the reason term rides the status guard, not the unconditional increment)");
        assertEquals("failed", readStatus(sourceId),
            "status must stay 'failed' (idempotent against an already-parked row)");
        assertEquals(4, readConsecutiveFailures(sourceId),
            "the counter increment is documented as unconditional and must still fire");
    }

    @Test
    void thresholdCrossingRecordsFetchFailureReason() throws Exception {
        UUID sourceId = seedSource("crossing", "active", null, 2);

        SourceRepository.FailureOutcome outcome = sourceRepository.recordFailure(sourceId, 3);

        assertEquals("failed", outcome.status(),
            "3rd consecutive failure at threshold 3 must park the row");
        assertEquals("fetch-failure", readParkReason(sourceId),
            "the D42 ladder park must record reason 'fetch-failure' in the same statement");
        assertNotNull(readParkedAt(sourceId),
            "the crossing must stamp parked_at for the parked-set summary");
    }

    @Test
    void subThresholdFailureLeavesReasonNull() throws Exception {
        UUID sourceId = seedSource("subThreshold", "active", null, 0);

        sourceRepository.recordFailure(sourceId, 3);

        assertEquals("active", readStatus(sourceId),
            "1st failure at threshold 3 must not park");
        assertNull(readParkReason(sourceId),
            "a non-parking failure must not write any park reason");
        assertNull(readParkedAt(sourceId),
            "a non-parking failure must not stamp parked_at");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedSource(String slug, String status, String parkReason, int failureCount)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', ?, ?, ?) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setString(3, status);
            ps.setString(4, parkReason);
            ps.setInt(5, failureCount);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private String readStatus(UUID sourceId) throws Exception {
        return readColumn(sourceId, "status");
    }

    private String readParkReason(UUID sourceId) throws Exception {
        return readColumn(sourceId, "park_reason");
    }

    private String readParkedAt(UUID sourceId) throws Exception {
        return readColumn(sourceId, "parked_at::TEXT");
    }

    private String readColumn(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int readConsecutiveFailures(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT consecutive_failures FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
