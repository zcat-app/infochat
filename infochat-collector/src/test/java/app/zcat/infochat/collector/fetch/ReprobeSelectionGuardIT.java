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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fail-closed selection legs of the D42 re-probe ladder
 * (M1-754): a parked row whose reason is NULL (D42 property (c)) or
 * whose {@code deleted_at} is set (property (d)) is NEVER probed —
 * even with a due {@code next_reprobe_at} seeded directly, which the
 * production writers never produce for these rows, so the exclusion
 * is proven against the selection predicate itself, not against the
 * schedule being unset.
 *
 * <p>Each test carries an eligible positive-control row probed under
 * the SAME sweep, so a zero-probe assertion can never pass vacuously
 * (the scan-window-fixture lesson: a negative without a positive
 * control proves nothing).
 */
@QuarkusTest
class ReprobeSelectionGuardIT {

    private static final String PREFIX = "m1-754-selguard-";
    private static final Instant PINNED_NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Inject
    ReprobeScheduler reprobeScheduler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @ConfigProperty(name = "infochat.fetch.reprobe.cap")
    int reprobeCap;

    private CountingRssFetcher mockFetcher;

    @BeforeEach
    void setup() throws Exception {
        mockFetcher = new CountingRssFetcher();
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
    void nullReasonParkedRowIsNeverProbed() throws Exception {
        UUID controlId = seedParked("nullReason-control", "fetch-failure",
            PINNED_NOW.minusSeconds(3600), false);
        UUID nullReasonId = seedParked("nullReason-victim", null,
            PINNED_NOW.minusSeconds(3600), false);

        reprobeScheduler.runOnce();

        assertEquals(1, mockFetcher.probedIdentifiers.size(),
            "exactly the eligible control row must be probed (proves the sweep ran)");
        assertTrue(mockFetcher.probedIdentifiers.contains(identifierOf("nullReason-control")),
            "the control row must be the probed one");
        assertEquals("active", readColumn(controlId, "status"),
            "positive control: the eligible row must be restored by its probe");
        assertEquals("failed", readColumn(nullReasonId, "status"),
            "a NULL-reason park is fail-closed: never probed, never restored "
                + "(D42 property (c) — pre-discriminator rows stay manual-only)");
        assertEquals(0, readReprobeCount(nullReasonId),
            "zero probe attempts may be consumed against a NULL-reason park");
    }

    @Test
    void softDeletedParkedRowIsNeverProbedOrRevived() throws Exception {
        UUID controlId = seedParked("softDel-control", "fetch-failure",
            PINNED_NOW.minusSeconds(3600), false);
        UUID deletedId = seedParked("softDel-victim", "fetch-failure",
            PINNED_NOW.minusSeconds(3600), true);

        reprobeScheduler.runOnce();

        assertEquals(1, mockFetcher.probedIdentifiers.size(),
            "exactly the eligible control row must be probed (proves the sweep ran)");
        assertEquals("active", readColumn(controlId, "status"),
            "positive control: the eligible row must be restored by its probe");
        assertEquals("failed", readColumn(deletedId, "status"),
            "a soft-deleted park is never revived by a background job "
                + "(D42 property (d): /remove-source must not be undone)");
        assertNotNull(readColumn(deletedId, "deleted_at"),
            "the soft-delete marker must survive the sweep");
        assertEquals(0, readReprobeCount(deletedId),
            "zero probe attempts may be consumed against a soft-deleted row");
    }

    // ----- helpers ---------------------------------------------------------

    private String identifierOf(String slug) {
        return "https://example.com/" + PREFIX + slug;
    }

    private UUID seedParked(String slug, String parkReason, Instant nextReprobeAt,
                            boolean softDeleted) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, parked_at, "
                     + "  next_reprobe_at, deleted_at, consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'failed', ?, now(), ?, ?, 3) "
                     + "RETURNING id")) {
            ps.setString(1, identifierOf(slug));
            ps.setString(2, PREFIX + slug + "-name");
            ps.setString(3, parkReason);
            ps.setTimestamp(4, Timestamp.from(nextReprobeAt));
            ps.setTimestamp(5, softDeleted ? Timestamp.from(PINNED_NOW.minusSeconds(60)) : null);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private String readColumn(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + "::TEXT FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int readReprobeCount(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT reprobe_count FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Successful (empty-batch) probe that records which identifiers it saw. */
    static final class CountingRssFetcher extends RssFetcher {
        final Set<String> probedIdentifiers = ConcurrentHashMap.newKeySet();

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            probedIdentifiers.add(identifier);
            return List.of();
        }
    }
}
