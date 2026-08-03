package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.fetch.SourceRepository;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the manual-only UPGRADE path (D42 property (b), M1-754): a row
 * the D42 fetch ladder parked FIRST must still be claimable by the
 * Stage 2 UNKNOWN-rate security control — both the tracker's candidate
 * selection and its UPDATE widen to re-probe-eligible parks, upgrading
 * the reason to {@code unknown-rate}. Without this the control is a
 * silent no-op against any source that failed its way into a
 * {@code fetch-failure} park before its UNKNOWN rate climbed, and the
 * re-probe ladder auto-readmits exactly the feed the control exists
 * to stop.
 */
@QuarkusTest
class PerSourceUnknownTrackerUpgradeIT {

    private static final String SOURCE_PREFIX = "https://m1-754-upgrade.example/";

    /**
     * Written by {@link UpgradeDisableRecorder}. Static on the test
     * class, NOT a field on the observer bean: {@code @ApplicationScoped}
     * beans are injected as client proxies, and a direct field read on a
     * proxy returns the proxy's own copy rather than the contextual
     * instance's — so an instance-field recorder reads back empty even
     * when the event fired. Same reason
     * {@code AutoDisableStopBeforeNotifyIT.ORDER} is static.
     */
    static final List<UUID> DISABLED_IDS = new CopyOnWriteArrayList<>();

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PerSourceUnknownTracker tracker;

    @Inject
    SourceRepository sourceRepository;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.fetch.reprobe.cap")
    int reprobeCap;

    @BeforeEach
    void cleanup() throws Exception {
        DISABLED_IDS.clear();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE source_id IN "
                + "(SELECT id FROM source WHERE identifier LIKE ?)", SOURCE_PREFIX + "%");
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?", SOURCE_PREFIX + "%");
        }
    }

    @Test
    void fetchFailureParkUpgradesToUnknownRate() throws Exception {
        // Millisecond truncation so the timestamptz round-trip (micros)
        // preserves the value exactly for the equality assertion below.
        Instant parkedAt = Instant.now().minusSeconds(7200)
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        UUID sourceId = seedFetchFailureParked("victim", parkedAt);
        // Rate 1.0 over the test-profile min-sample of 3 — above the 0.5
        // threshold. The posts are in-window Stage 2 UNKNOWN verdicts.
        Instant now = Instant.now();
        seedStage2UnknownPost(sourceId, now.minusSeconds(60));
        seedStage2UnknownPost(sourceId, now.minusSeconds(120));
        seedStage2UnknownPost(sourceId, now.minusSeconds(180));

        tracker.checkAllSources();

        assertEquals("failed", readText(sourceId, "status"),
            "the row stays parked through the upgrade");
        assertEquals("unknown-rate", readText(sourceId, "park_reason"),
            "the security control must UPGRADE a fetch-failure park to unknown-rate "
                + "(D42 property (b)) — a status='active'-only guard makes it a silent no-op");
        assertEquals(parkedAt, readTimestamp(sourceId, "parked_at").toInstant(),
            "the upgrade must keep the ORIGINAL parked-since stamp (the row has "
                + "been dark since the fetch ladder parked it, not since the upgrade)");

        assertTrue(DISABLED_IDS.contains(sourceId),
            "the SourceDisabled event must still fire on the upgrade path "
                + "(it stops a running stream worker)");
        assertTrue(throttledAdminNotifier.getState(
                PerSourceUnknownTracker.ERROR_CLASS_SOURCE_UNKNOWN_AUTO_DISABLE + ":" + sourceId)
            .isPresent(),
            "the admin notification must still fire on the upgrade path");

        // The upgraded row must be invisible to the re-probe ladder even
        // with a due probe slot on the row.
        setNextReprobeAt(sourceId, Instant.now().minusSeconds(60));
        List<SourceRepository.ReprobeCandidate> due =
            sourceRepository.selectDueReprobes(Instant.now(), reprobeCap);
        assertFalse(due.stream().anyMatch(c -> c.uuid().equals(sourceId)),
            "after the upgrade the re-probe selection must never pick the row — "
                + "the automatic way back in is exactly what the control closes");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedFetchFailureParked(String slug, Instant parkedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, parked_at, "
                     + "  consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'failed', 'fetch-failure', ?, 3) "
                     + "RETURNING id")) {
            ps.setString(1, SOURCE_PREFIX + slug);
            ps.setString(2, "m1-754-upgrade-" + slug);
            ps.setTimestamp(3, Timestamp.from(parkedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedStage2UnknownPost(UUID sourceId, Instant fetchedAt) throws Exception {
        String uid = "m1754u-" + UUID.randomUUID().toString().substring(0, 8);
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
                     + "  ?, 'QUARANTINED', now(),"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0,"
                     + "  'UNKNOWN')")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + uid);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            ps.executeUpdate();
        }
    }

    private void setNextReprobeAt(UUID sourceId, Instant when) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE source SET next_reprobe_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(when));
            ps.setObject(2, sourceId);
            ps.executeUpdate();
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

    private Timestamp readTimestamp(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp ts = rs.getTimestamp(1);
                assertNotNull(ts, column + " must not be NULL");
                return ts;
            }
        }
    }

    private static void exec(Connection conn, String sql, String arg) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }
}

/**
 * Records every {@link PerSourceUnknownTracker.SourceDisabled} dispatch
 * into the test class's static list so the upgrade test can assert the
 * event still fires. Top-level package-private CDI bean per the
 * module's test-double convention (the {@code RecordingDisableObserver}
 * pattern, whose static-collection choice this follows deliberately —
 * see {@link PerSourceUnknownTrackerUpgradeIT#DISABLED_IDS}).
 */
@ApplicationScoped
class UpgradeDisableRecorder {

    void onSourceDisabled(@Observes PerSourceUnknownTracker.SourceDisabled event) {
        PerSourceUnknownTrackerUpgradeIT.DISABLED_IDS.add(event.sourceId());
    }
}
