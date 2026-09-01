package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PerSourceUnknownTrackerTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PerSourceUnknownTracker tracker;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    // The instant every seed is relative to, pinned via the injected Clock
    // (the PerSourceUnknownTrackerClockIT seam) so partition placement is
    // calendar-independent — June 2026 is migration-provisioned forever.
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");

    @ConfigProperty(name = "infochat.reeval.unknown-rate-window")
    Duration unknownRateWindow;

    @BeforeEach
    void pinClock() {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
    }

    @Test
    void unknownRateExceedsThreshold_disablesSource() throws Exception {
        // Seed a source with high UNKNOWN rate: 3 UNKNOWN out of 4
        // total Stage 2-done posts = 75% > 50% threshold.
        UUID sourceId = seedSource("unknown-rate-high");
        Instant now = PINNED_NOW;
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
            now.minusSeconds(60), now.minusSeconds(60));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
            now.minusSeconds(120), now.minusSeconds(120));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
            now.minusSeconds(180), now.minusSeconds(180));
        seedStage2Post(sourceId, "RAW", false, "BENIGN",
            now.minusSeconds(240), now.minusSeconds(240));

        tracker.checkAllSources();

        assertSourceStatus(sourceId, "failed");
        // The coalescing key is now per-source (error-class:sourceId), so the
        // lookup is keyed by source rather than by the bare error class.
        var state = throttledAdminNotifier.getState(
            PerSourceUnknownTracker.ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE + ":" + sourceId);
        assertTrue(state.isPresent(), "Expected throttled notification for source auto-disable");
    }

    @Test
    void twoSourcesDisabledInWindow_eachNotifies() throws Exception {
        // Two distinct sources each exceed the threshold within one throttle
        // window. The per-source coalescing key must let BOTH emit their own
        // notification — a constant key would suppress the second as a
        // duplicate (the bug this fixes).
        UUID sourceA = seedSource("unknown-rate-two-a");
        UUID sourceB = seedSource("unknown-rate-two-b");
        Instant now = PINNED_NOW;
        for (UUID s : List.of(sourceA, sourceB)) {
            seedStage2Post(s, "QUARANTINED", false, "UNKNOWN",
                now.minusSeconds(60), now.minusSeconds(60));
            seedStage2Post(s, "QUARANTINED", false, "UNKNOWN",
                now.minusSeconds(120), now.minusSeconds(120));
            seedStage2Post(s, "QUARANTINED", false, "UNKNOWN",
                now.minusSeconds(180), now.minusSeconds(180));
        }

        tracker.checkAllSources();

        assertSourceStatus(sourceA, "failed");
        assertSourceStatus(sourceB, "failed");
        // Each source has its own throttle row keyed by error-class:sourceId, so
        // both notifications emitted and neither was suppressed. Under a constant
        // key only one row would exist and the second source's notify would land
        // inside the window and be suppressed.
        var stateA = throttledAdminNotifier.getState(
            PerSourceUnknownTracker.ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE + ":" + sourceA);
        var stateB = throttledAdminNotifier.getState(
            PerSourceUnknownTracker.ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE + ":" + sourceB);
        assertTrue(stateA.isPresent(), "Source A must have its own notification");
        assertTrue(stateB.isPresent(), "Source B must have its own notification");
        assertEquals(0L, stateA.get().suppressedCount(), "Source A notification must not be suppressed");
        assertEquals(0L, stateB.get().suppressedCount(), "Source B notification must not be suppressed");
    }

    @Test
    void oldPartitionPost_excludedFromRate_sourceNotDisabled() throws Exception {
        // Two recent UNKNOWN posts (in-window) — below min-sample (3) on their
        // own, so the source is not evaluated. Plus one UNKNOWN post fetched
        // below the tracker's partition floor. Its status_changed_at is
        // recent, so the OLD status_changed_at-only bound WOULD have counted it
        // — pushing total to 3 (>= min-sample) at 100% UNKNOWN and auto-disabling
        // the source. The fetched_at partition bound excludes it.
        UUID sourceId = seedSource("unknown-rate-old-partition");
        // Floors derived from the same config the tracker reads (window +
        // PARTITION_SCAN_SLACK), so a window change cannot silently
        // invalidate the straddle (PerSourceUnknownTrackerClockIT shape).
        Instant statusFloor = PINNED_NOW.minus(unknownRateWindow);
        Instant fetchedFloor = PINNED_NOW.minus(
            unknownRateWindow.plusSeconds(PartitionScan.PARTITION_SCAN_SLACK.toSeconds()));
        Instant recentStatus = statusFloor.plus(Duration.ofMinutes(10));
        Instant oldFetched = fetchedFloor.minus(Duration.ofHours(2));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
            recentStatus.minusSeconds(60), recentStatus);
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
            recentStatus.minusSeconds(120), recentStatus);
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN", oldFetched, recentStatus);

        tracker.checkAllSources();

        // Old post excluded → only 2 in-window posts → below min-sample → the
        // source is not evaluated and stays active. Without the fetched_at bound
        // the old post would be counted and the source would be auto-disabled.
        assertSourceStatus(sourceId, "active");
    }

    @Test
    void autoDisable_inflightPostsContinueUnaffected() throws Exception {
        // Seed a source that exceeds threshold; verify posts already
        // in the outbox (with status='RAW', tagger_done=false) still
        // exist unchanged after disable.
        UUID sourceId = seedSource("unknown-rate-inflight");
        Instant now = PINNED_NOW;
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
            now.minusSeconds(60), now.minusSeconds(60));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
            now.minusSeconds(120), now.minusSeconds(120));
        seedStage2Post(sourceId, "QUARANTINED", false, "UNKNOWN",
            now.minusSeconds(180), now.minusSeconds(180));
        UUID inflightPostId = seedStage2Post(sourceId, "RAW", false, "BENIGN",
            now.minusSeconds(240), now.minusSeconds(240));

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
                                String stage2Verdict, Instant fetchedAt,
                                Instant statusChangedAt) throws Exception {
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
                     + "  ?, ?, ?,"
                     + "  TRUE, TRUE, TRUE, ?,"
                     + "  FALSE, FALSE, FALSE, '{}', 0,"
                     + "  ?"
                     + ") RETURNING id")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + uid);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            ps.setString(5, status);
            ps.setTimestamp(6, Timestamp.from(statusChangedAt));
            ps.setBoolean(7, stage2Failed);
            ps.setString(8, stage2Verdict);
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
