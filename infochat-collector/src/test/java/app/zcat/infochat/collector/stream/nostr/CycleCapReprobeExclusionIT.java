package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.fetch.ReprobeScheduler;
import app.zcat.infochat.collector.fetch.SourceRepository;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exclusion leg of the M1-752 amendment's security property (M1-754):
 * a source parked by the {@code NostrStreamSource} D38 all-relays-bad
 * cycle cap ({@code park_reason='stream-cycle-cap'}) is NEVER selected
 * by the re-probe path — the cycle-cap park stays terminal and only
 * {@code /source-enable} revives it. The seeded row carries a DUE
 * probe slot so the exclusion is proven against the reason predicate;
 * the eligible rss control row probed in the same sweep keeps the
 * zero-probe assertion from passing vacuously.
 */
@QuarkusTest
class CycleCapReprobeExclusionIT {

    private static final String PREFIX = "m1-754-ccexcl-";
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
            // Both fixture shapes carry the PREFIX somewhere in the
            // identifier (URL path for the rss control, leading tag for
            // the nostr row), so one infix pattern cleans both.
            ps.setString(1, "%" + PREFIX + "%");
            ps.executeUpdate();
        }
    }

    @Test
    void cycleCapParkIsNeverSelectedByReprobePath() throws Exception {
        UUID controlId = seedParked("control", "rss",
            "https://example.com/" + PREFIX + "control", "fetch-failure");
        UUID cycleCapId = seedParked("cyclecap", "nostr",
            PREFIX + "wss://relay.example", "stream-cycle-cap");

        // Selection-level proof: even with a due slot, the cycle-cap
        // row is not a candidate.
        List<SourceRepository.ReprobeCandidate> due =
            sourceRepository.selectDueReprobes(PINNED_NOW, reprobeCap);
        assertFalse(due.stream().anyMatch(c -> c.uuid().equals(cycleCapId)),
            "selectDueReprobes must never return a stream-cycle-cap park");
        assertTrue(due.stream().anyMatch(c -> c.uuid().equals(controlId)),
            "sanity: the eligible control row must be a candidate under the same query");

        // Sweep-level proof: a full sweep probes only the control.
        reprobeScheduler.runOnce();
        assertEquals(1, mockFetcher.callCount.get(),
            "the sweep must probe exactly the eligible control row");
        assertEquals("failed", readText(cycleCapId, "status"),
            "the cycle-cap park must stay parked — zero probe attempts, terminal "
                + "until /source-enable");
        assertEquals("stream-cycle-cap", readText(cycleCapId, "park_reason"),
            "the cycle-cap reason must be untouched");
        assertEquals(0, readInt(cycleCapId, "reprobe_count"),
            "no probe budget may be consumed against a cycle-cap park");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedParked(String slug, String kind, String identifier, String reason)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, parked_at, "
                     + "  next_reprobe_at, consecutive_failures) "
                     + "VALUES (?, ?, ?, 'news', '{}', 'failed', ?, now(), ?, 3) "
                     + "RETURNING id")) {
            ps.setString(1, kind);
            ps.setString(2, identifier);
            ps.setString(3, PREFIX + slug + "-name");
            ps.setString(4, reason);
            ps.setTimestamp(5, Timestamp.from(PINNED_NOW.minusSeconds(3600)));
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

    /** Successful empty-batch probe with a call counter. */
    static final class CountingRssFetcher extends RssFetcher {
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            callCount.incrementAndGet();
            return List.of();
        }
    }
}
