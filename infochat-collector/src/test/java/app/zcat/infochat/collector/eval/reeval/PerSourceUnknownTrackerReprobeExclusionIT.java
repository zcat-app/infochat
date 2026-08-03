package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetch.ReprobeScheduler;
import app.zcat.infochat.collector.fetcher.rss.RssFetcher;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exclusion leg of the M1-752 amendment's security property (M1-754):
 * a source parked by {@code PerSourceUnknownTracker} — the Stage 2
 * UNKNOWN-rate quarantine-exhaustion defense — is NEVER selected by
 * the re-probe path. Automatically re-enabling an adversary-influenced
 * feed would defeat the control; recovery is {@code /source-enable}
 * only. The seeded row carries a DUE probe slot, so the exclusion is
 * proven against the reason predicate, not an unset schedule; the
 * eligible control row probed in the same sweep keeps the zero-probe
 * assertion from passing vacuously.
 */
@QuarkusTest
class PerSourceUnknownTrackerReprobeExclusionIT {

    private static final String PREFIX = "m1-754-uexcl-";
    private static final Instant PINNED_NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Inject
    ReprobeScheduler reprobeScheduler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    private RecordingRssFetcher mockFetcher;

    @BeforeEach
    void setup() throws Exception {
        mockFetcher = new RecordingRssFetcher();
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
    void unknownRateParkIsNeverSelectedByReprobePath() throws Exception {
        UUID controlId = seedParked("control", "fetch-failure");
        UUID quarantinedId = seedParked("quarantined", "unknown-rate");

        reprobeScheduler.runOnce();

        assertEquals(1, mockFetcher.probedIdentifiers.size(),
            "exactly the eligible control row must be probed (proves the sweep ran)");
        assertTrue(mockFetcher.probedIdentifiers.contains(
                "https://example.com/" + PREFIX + "control"),
            "the probed row must be the fetch-failure control");
        assertEquals("failed", readText(quarantinedId, "status"),
            "the unknown-rate park must stay parked — zero probe attempts");
        assertEquals("unknown-rate", readText(quarantinedId, "park_reason"),
            "the security-park reason must be untouched");
        assertEquals(0, readInt(quarantinedId, "reprobe_count"),
            "no probe budget may be consumed against a security park");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedParked(String slug, String reason) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, parked_at, "
                     + "  next_reprobe_at, consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'failed', ?, now(), ?, 3) "
                     + "RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setString(3, reason);
            ps.setTimestamp(4, Timestamp.from(PINNED_NOW.minusSeconds(3600)));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private String readText(UUID sourceId, String column) throws Exception {
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

    private int readInt(UUID sourceId, String column) throws Exception {
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

    /** Successful empty-batch probe recording the identifiers it saw. */
    static final class RecordingRssFetcher extends RssFetcher {
        final Set<String> probedIdentifiers = ConcurrentHashMap.newKeySet();

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            probedIdentifiers.add(identifier);
            return List.of();
        }
    }
}
