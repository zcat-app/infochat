package app.zcat.infochat.collector.eval.reeval;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts the
 * per-source UNKNOWN-rate scan window ({@link PerSourceUnknownTracker#checkAllSources})
 * decides against that instant — not the wall-clock run date. The
 * deterministic complement to {@link PerSourceUnknownTrackerTest}, whose seeds
 * use {@code Instant.now()} / DB {@code now()}. (M1-448)
 *
 * <p>Both scan-window floors are exercised. {@code checkAllSources} bounds
 * candidates on TWO predicates derived from one Clock sample: the
 * {@code fetched_at} partition-pruning floor ({@code now − (window + slack)})
 * and the precise {@code status_changed_at} recency floor ({@code now − window}).
 * Three sources each carry {@code min-sample} all-UNKNOWN posts (rate 1.0,
 * above threshold), differing only in which floor their posts straddle:
 * <ul>
 *   <li>A — both timestamps in-window → counted → auto-disabled;</li>
 *   <li>B — {@code status_changed_at} below the recency floor → excluded → stays active;</li>
 *   <li>C — {@code fetched_at} below the partition floor → excluded → stays active.</li>
 * </ul>
 */
@QuarkusTest
class PerSourceUnknownTrackerClockIT {

    // A FIXED instant the two scan-window floors are computed against via the
    // injected Clock (pinned in pinClock()). Seeds straddle the floors relative
    // to this constant and the configured window, so the boundary is exercised
    // deterministically regardless of the wall-clock date.
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final String SOURCE_PREFIX = "https://unknown-clock-it.example/";
    private static final String UID_PREFIX = "uct-clock-it-";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PerSourceUnknownTracker tracker;

    @ConfigProperty(name = "infochat.reeval.unknown-rate-window")
    Duration unknownRateWindow;

    @ConfigProperty(name = "infochat.reeval.unknown-rate-min-sample", defaultValue = "5")
    int minSampleSize;

    @BeforeEach
    void pinClock() throws Exception {
        // Same QuarkusMock seam ThrottledAdminNotifier's Clock producer
        // documents (M1-444); pins the instant both floors read.
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        clearItData();
    }

    @Test
    void checkAllSources_gatesBothFloorsOnInjectedClock() throws Exception {
        Instant statusFloor = PINNED_NOW.minus(unknownRateWindow);
        Instant fetchedFloor = PINNED_NOW.minus(
            unknownRateWindow.plusSeconds(PartitionScan.PARTITION_SCAN_SLACK.toSeconds()));

        // In-window: comfortably above each floor. Below-floor: comfortably under.
        Instant recentStatus = statusFloor.plus(Duration.ofMinutes(10));
        Instant oldStatus = statusFloor.minus(Duration.ofMinutes(10));
        Instant recentFetched = fetchedFloor.plus(Duration.ofHours(2));
        Instant oldFetched = fetchedFloor.minus(Duration.ofHours(2));

        // A — both timestamps in-window → counted → auto-disabled.
        UUID sourceA = seedActiveSource("a");
        // B — status_changed_at below the recency floor → excluded → stays active.
        UUID sourceB = seedActiveSource("b");
        // C — fetched_at below the partition floor → excluded → stays active.
        UUID sourceC = seedActiveSource("c");
        for (int i = 0; i < minSampleSize; i++) {
            seedUnknownPost(sourceA, recentFetched, recentStatus);
            seedUnknownPost(sourceB, recentFetched, oldStatus);
            seedUnknownPost(sourceC, oldFetched, recentStatus);
        }

        tracker.checkAllSources();

        assertEquals("failed", sourceStatus(sourceA),
            "source A (both timestamps in-window) must be auto-disabled");
        assertEquals("active", sourceStatus(sourceB),
            "source B (status_changed_at below the recency floor) must stay active — the floor "
                + "reads the injected Clock, so a fixed clock makes the boundary deterministic");
        assertEquals("active", sourceStatus(sourceC),
            "source C (fetched_at below the partition floor) must stay active — the floor reads "
                + "the injected Clock, so a fixed clock makes the boundary deterministic");
    }

    // ---------- helpers ----------

    private UUID seedActiveSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[], 'active') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET status = 'active' "
                     + "RETURNING id")) {
            ps.setString(1, SOURCE_PREFIX + slug);
            ps.setString(2, "Unknown Clock IT " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedUnknownPost(UUID sourceId, Instant fetchedAt, Instant statusChangedAt)
            throws Exception {
        String uid = UID_PREFIX + UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, status_changed_at,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts,"
                     + "  stage2_verdict"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'title', 'body',"
                     + "  ?, 'QUARANTINED', ?,"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0,"
                     + "  'UNKNOWN'"
                     + ")")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + uid);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            ps.setTimestamp(5, Timestamp.from(statusChangedAt));
            ps.executeUpdate();
        }
    }

    private String sourceStatus(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "source row must exist");
                return rs.getString(1);
            }
        }
    }

    private void clearItData() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE uid LIKE ?")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM source WHERE identifier LIKE ?")) {
                ps.setString(1, SOURCE_PREFIX + "%");
                ps.executeUpdate();
            }
        }
    }
}
