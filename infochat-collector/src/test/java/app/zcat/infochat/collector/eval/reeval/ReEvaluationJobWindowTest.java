package app.zcat.infochat.collector.eval.reeval;

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
 * Pins the fetched_at window floor on {@link ReEvaluationJob#enumerateCandidates}.
 *
 * <p>The candidate scan bounds fetched_at at {@code now() - (retention
 * horizon + slack)} so the RANGE(fetched_at) partitioned post table can
 * prune partitions instead of scanning every live one each tick. The floor
 * spans the full post retention horizon
 * ({@code infochat.partitions.retention-days.post}) widened by the partition
 * slack, so a live candidate is never excluded while a post fetched longer
 * ago than the window drops out.
 */
@QuarkusTest
class ReEvaluationJobWindowTest {

    // The oldest bootstrap partition (May 2026, created by V7 and recreated
    // on every fresh test DB) — more than the ~32-day window before
    // PINNED_NOW, so this post is always below the floor. PartitionPruner
    // runs only on a 24h schedule (no startup hook), so the partition survives
    // the test run.
    private static final Instant BELOW_FLOOR = Instant.parse("2026-05-01T00:00:00Z");

    // The candidate scan floors on the injected Clock; the pin keeps the
    // in-window seed calendar-proof (the ScheduledPathIT seam).
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ReEvaluationJob reEvaluationJob;

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    @BeforeEach
    void pinClock() {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
    }

    @Test
    void candidateOlderThanFetchedAtFloorIsNotEnumeratedWhileInWindowIs() throws Exception {
        // enumerateCandidates returns a LIMIT-capped, fetched_at-ordered slice
        // over the whole (shared, accumulating) test DB. Clear the candidate
        // field first so the slice reflects exactly this test's two seeds —
        // nothing in the post table references it, so the delete is FK-safe.
        clearReEvalCandidates();

        UUID sourceId = seedSource("window");
        // Both posts satisfy the UNKNOWN re-eval predicate; fetched_at is the
        // ONLY discriminator. In-window: fetched at PINNED_NOW (the June 2026
        // migration-provisioned bootstrap month), comfortably newer than
        // the ~32-day floor. Below-floor: the fixed May 2026 partition.
        UUID inWindowPostId = seedUnknownQuarantinedPost(sourceId, "in-window", PINNED_NOW);
        UUID belowFloorPostId = seedUnknownQuarantinedPost(sourceId, "below-floor", BELOW_FLOOR);

        // Sanity-check the floor the assertion rests on: BELOW_FLOOR must be
        // older than now() - (horizon + slack), or the test proves nothing.
        assertTrue(BELOW_FLOOR.isBefore(PINNED_NOW.minusSeconds((postRetentionDays + 2L) * 86400)),
            "test fixture invalid: BELOW_FLOOR is inside the candidate window");

        Set<UUID> enumerated = reEvaluationJob.enumerateCandidates().stream()
            .map(ReEvaluationJob.ReEvalCandidate::postId)
            .collect(Collectors.toSet());

        assertTrue(enumerated.contains(inWindowPostId),
            "an in-window candidate must be enumerated");
        assertFalse(enumerated.contains(belowFloorPostId),
            "a candidate fetched before the now() - (horizon + slack) floor must be pruned out");
    }

    // ---------- helpers ----------

    private void clearReEvalCandidates() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post "
                     + "WHERE (stage2_failed = TRUE AND status <> 'NEEDS_REVIEW') "
                     + "   OR (status = 'QUARANTINED' AND stage2_done = TRUE AND stage2_failed = FALSE "
                     + "       AND (stage2_verdict = 'UNKNOWN' OR re_eval_attempts > 0))")) {
            ps.executeUpdate();
        }
    }

    private UUID seedUnknownQuarantinedPost(UUID sourceId, String slug, Instant fetchedAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, status_changed_at,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts,"
                     + "  stage2_verdict"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, 'body',"
                     + "  ?, 'QUARANTINED', now(),"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0,"
                     + "  'UNKNOWN'"
                     + ") RETURNING id")) {
            ps.setString(1, "reeval-window-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-window-" + slug);
            ps.setString(4, "Window " + slug);
            ps.setTimestamp(5, Timestamp.from(fetchedAt));
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
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[]) "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://reeval-window-test.example/" + slug);
            ps.setString(2, "ReEval Window " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
