package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts the Tagger
 * pickup scan window ({@link TaggerWorker#enumeratePending}) decides against
 * that instant — not the wall-clock run date. The deterministic complement to
 * {@link TaggerWorkerIT}, whose per-scenario seeds sit inside the window
 * implicitly. (M1-448)
 *
 * <p>Two otherwise-pickup-ready posts straddle the scan-window floor
 * ({@code PINNED_NOW − (retention + PARTITION_SCAN_SLACK)}): one one day inside
 * it (must be enumerated) and one one day below it (must NOT be).
 */
@QuarkusTest
class TaggerWorkerClockIT {

    // A FIXED instant the scan-window pickup reads via the injected Clock
    // (pinned in pinClock()). The straddle seeds are computed relative to this
    // constant and the configured window, so the boundary is exercised
    // deterministically regardless of the wall-clock date.
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final String UID_PREFIX = "tagger-clock-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TaggerWorker taggerWorker;

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    @BeforeEach
    void pinClock() throws Exception {
        // Same QuarkusMock seam ThrottledAdminNotifier's Clock producer
        // documents (M1-444); pins the instant enumeratePending reads.
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        clearItData();
    }

    @Test
    void enumeratePending_gatesOnInjectedClock() throws Exception {
        Instant floor = PINNED_NOW.minus(
            Duration.ofDays(postRetentionDays + PartitionScan.PARTITION_SCAN_SLACK.toDays()));
        UUID inWindowId = seedPickupReadyPost("in-window", floor.plus(Duration.ofDays(1)));
        UUID belowFloorId = seedPickupReadyPost("below-floor", floor.minus(Duration.ofDays(1)));

        List<TaggerWorker.PostRow> pending = taggerWorker.enumeratePending(Integer.MAX_VALUE);

        assertTrue(pending.stream().anyMatch(r -> r.id().equals(inWindowId)),
            "a pickup-ready post fetched above PINNED_NOW − (retention + slack) must be enumerated");
        assertFalse(pending.stream().anyMatch(r -> r.id().equals(belowFloorId)),
            "a pickup-ready post fetched below the floor must NOT be enumerated — the scan-window "
                + "floor reads the injected Clock, so a fixed clock makes the boundary deterministic");
    }

    // ---------- helpers ----------

    private UUID seedPickupReadyPost(String slug, Instant fetchedAt) throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage2_done, tagger_done, embedding_done,"
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'Tagger Clock IT title', 'Tagger Clock IT body',"
                     + "  ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, '{}'"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "tagger-clock-it-upstream-" + slug);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://tagger-clock-it.example/" + slug);
            ps.setString(2, "Tagger Clock IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void clearItData() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }
}
