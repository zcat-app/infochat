package app.zcat.infochat.collector.eval.embedding;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code fetched_at} partition-scan floor on
 * {@link EmbeddingWorker#enumeratePending}. The pickup query bounds
 * {@code fetched_at >= PartitionScan.scanWindowFloor(now)} so the planner
 * can prune partitions of the RANGE(fetched_at) post table; a post fetched
 * longer ago than the retention horizon + slack drops out of pickup (it is
 * about to be partition-dropped anyway). This is the representative pickup
 * query exercised behaviourally — the identical floor clause is added to
 * the Tagger, Entity, and ReadyPromoter queries, all sourcing the bound
 * from the same {@code PartitionScan} bean (see
 * {@code PartitionScanSharedSourceTest}).
 */
@QuarkusTest
class EmbeddingWorkerPickupFloorIT {

    // The fixed May-2026 bootstrap partition (created by V7, recreated on
    // every fresh test DB). More than the ~32-day window before
    // PINNED_NOW, so this post is always below the floor. PartitionPruner
    // runs only on a 24h schedule, so the partition survives the test run.
    // Same anchor ReEvaluationJobWindowTest uses.
    private static final Instant BELOW_FLOOR = Instant.parse("2026-05-01T00:00:00Z");

    // The pickup floor is computed from the injected Clock; the pin keeps
    // the in-window seed calendar-proof (the ScheduledPathIT seam).
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    EmbeddingWorker embeddingWorker;

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    @BeforeEach
    void pinClock() {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
    }

    @Test
    void postBelowFetchedAtFloorIsNotPickedUpWhileInWindowOneIs() throws Exception {
        UUID sourceId = seedRssSource();
        // Both posts satisfy the embedding pickup predicate (RAW, tagger_done,
        // embedding_done=FALSE); fetched_at is the only discriminator.
        UUID inWindowId = seedPickupReadyPost(sourceId, "in-window", PINNED_NOW);
        UUID belowFloorId = seedPickupReadyPost(sourceId, "below-floor", BELOW_FLOOR);

        // Sanity-check the floor the assertion rests on: BELOW_FLOOR must be
        // older than now() - (horizon + slack), or the test proves nothing.
        assertTrue(BELOW_FLOOR.isBefore(PINNED_NOW.minusSeconds((postRetentionDays + 2L) * 86400)),
            "test fixture invalid: BELOW_FLOOR is inside the pickup window");

        // Huge limit so neither post can be crowded out of the LIMIT slice by
        // other accumulated test rows; membership is what the floor decides.
        Set<UUID> pickedUp = embeddingWorker.enumeratePending(1_000_000).stream()
            .map(EmbeddingWorker.PostRow::id)
            .collect(Collectors.toSet());

        assertTrue(pickedUp.contains(inWindowId),
            "an in-window pending post must be picked up");
        assertFalse(pickedUp.contains(belowFloorId),
            "a post fetched before the now() - (horizon + slack) floor must be pruned out");
    }

    // ---------- helpers ----------

    private UUID seedPickupReadyPost(UUID sourceId, String slug, Instant fetchedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, status_changed_at, "
                     + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags, translation_done"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, 'body', "
                     + "  ?, 'RAW', now(), "
                     + "  TRUE, FALSE, TRUE, FALSE, FALSE, FALSE, FALSE, '{}', TRUE"
                     + ") RETURNING id")) {
            ps.setString(1, "embed-floor-it/" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-floor-upstream-" + slug);
            ps.setString(4, "Embed floor IT " + slug);
            ps.setTimestamp(5, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedRssSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[]) "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://embed-floor-it.example.test/feed.xml");
            ps.setString(2, "Embed floor IT source");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
