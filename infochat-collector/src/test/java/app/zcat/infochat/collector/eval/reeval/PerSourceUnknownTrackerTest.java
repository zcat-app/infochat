package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PerSourceUnknownTrackerTest {

    @Inject
    DataSource dataSource;

    @Inject
    PerSourceUnknownTracker tracker;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Test
    void unknownRateExceedsThreshold_disablesSource() throws Exception {
        // Seed a source with high UNKNOWN rate: 3 UNKNOWN out of 4
        // total Stage 2-done posts = 75% > 50% threshold.
        UUID sourceId = seedSource("unknown-rate-high");
        Instant now = Instant.now();
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN", now.minusSeconds(60));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN", now.minusSeconds(120));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN", now.minusSeconds(180));
        seedStage2Post(sourceId, "RAW", false, "BENIGN", now.minusSeconds(240));

        tracker.checkAllSources();

        assertSourceStatus(sourceId, "failed");
        var state = throttledAdminNotifier.getState(
            PerSourceUnknownTracker.ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE);
        assertTrue(state.isPresent(), "Expected throttled notification for source auto-disable");
    }

    @Test
    void autoDisable_inflightPostsContinueUnaffected() throws Exception {
        // Seed a source that exceeds threshold; verify posts already
        // in the outbox (with status='RAW', tagger_done=false) still
        // exist unchanged after disable.
        UUID sourceId = seedSource("unknown-rate-inflight");
        Instant now = Instant.now();
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN", now.minusSeconds(60));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN", now.minusSeconds(120));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN", now.minusSeconds(180));
        UUID inflightPostId = seedStage2Post(sourceId, "RAW", false, "BENIGN", now.minusSeconds(240));

        tracker.checkAllSources();

        assertSourceStatus(sourceId, "failed");
        // The in-flight post remains in its current state — the
        // disable only prevents new fetches.
        assertPostExists(inflightPostId, "RAW");
    }

    // ---------- helpers ----------

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[], 'active') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET status = 'active' "
                     + "RETURNING id")) {
            ps.setString(1, "https://unknown-tracker-test.example/" + slug);
            ps.setString(2, "Unknown Tracker " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedStage2Post(UUID sourceId, String status, boolean stage2Failed,
                                String stage2Verdict, Instant fetchedAt) throws Exception {
        String uid = "ut-" + UUID.randomUUID().toString().substring(0, 8);
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
                     + "  ?, ?, now(),"
                     + "  TRUE, TRUE, TRUE, ?,"
                     + "  FALSE, FALSE, FALSE, '{}', 0,"
                     + "  ?"
                     + ") RETURNING id")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + uid);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            ps.setString(5, status);
            ps.setBoolean(6, stage2Failed);
            ps.setString(7, stage2Verdict);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void assertSourceStatus(UUID sourceId, String expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
            }
        }
    }

    private void assertPostExists(UUID postId, String expectedStatus) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Post should exist: " + postId);
                assertEquals(expectedStatus, rs.getString(1));
            }
        }
    }
}
