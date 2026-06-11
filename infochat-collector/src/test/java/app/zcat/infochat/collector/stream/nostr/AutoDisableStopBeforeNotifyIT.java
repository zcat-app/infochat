package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.eval.reeval.PerSourceUnknownTracker;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.NotifyOutcome;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the U-03 ordering contract: inside
 * {@code PerSourceUnknownTracker.disableSource} the worker-stop signal is
 * dispatched BEFORE the admin notify, so the "source disabled" notification is
 * true the moment it fires. The signal is a synchronous CDI event, so a test
 * observer records the dispatch the instant it reaches the supervisor-stopping
 * observers, ahead of the (mocked) notifier.
 *
 * <p>Driving through the real tracker also confirms the disable path is
 * unbroken when no stream worker is registered for the disabled id: the
 * production {@code NostrStreamSource.Registrar} observer also fires here, hits
 * a map miss, and no-ops.
 */
@QuarkusTest
class AutoDisableStopBeforeNotifyIT {

    // Append-order log shared by the recording observer (STOP_SIGNAL) and the
    // recording notifier (ADMIN_NOTIFY). Cleared per test.
    static final List<String> ORDER = new CopyOnWriteArrayList<>();

    static final String STOP_SIGNAL = "stop-signal";
    static final String ADMIN_NOTIFY = "admin-notify";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PerSourceUnknownTracker tracker;

    @BeforeEach
    void installRecordingNotifier() {
        ORDER.clear();
        QuarkusMock.installMockForType(new RecordingNotifier(), ThrottledAdminNotifier.class);
    }

    @Test
    void stopSignalIsDispatchedBeforeAdminNotify() throws Exception {
        UUID sourceId = seedSource("stop-before-notify");
        Instant now = Instant.now();
        // Three UNKNOWN stage-2 posts in-window: 3/3 = 100% > 0.5 threshold and
        // count 3 >= the test min-sample (3), so the source is auto-disabled.
        seedStage2UnknownPost(sourceId, now.minusSeconds(60));
        seedStage2UnknownPost(sourceId, now.minusSeconds(120));
        seedStage2UnknownPost(sourceId, now.minusSeconds(180));

        // onTick() is the public scheduled entry point; it runs the same
        // source scan + disableSource path checkAllSources does.
        tracker.onTick();

        assertSourceStatus(sourceId, "failed");
        assertEquals(List.of(STOP_SIGNAL, ADMIN_NOTIFY), ORDER,
                "the worker-stop signal must reach the supervisor before the admin notify fires");
    }

    // ---------- helpers (mirror PerSourceUnknownTrackerTest's seeding) ----------

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[], 'active') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET status = 'active' "
                     + "RETURNING id")) {
            ps.setString(1, "https://stop-before-notify-test.example/" + slug);
            ps.setString(2, "Stop Before Notify " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedStage2UnknownPost(UUID sourceId, Instant fetchedAt) throws Exception {
        String uid = "sbn-" + UUID.randomUUID().toString().substring(0, 8);
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
                     + "  'UNKNOWN'"
                     + ")")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + uid);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            ps.executeUpdate();
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
}

/**
 * Records the {@link PerSourceUnknownTracker.SourceDisabled} signal the moment
 * it is dispatched. For a synchronous CDI event that dispatch completes before
 * {@code disableSource} proceeds to the admin notify, so the recorded order is
 * the ordering contract under test. Top-level package-private CDI bean per the
 * module's test-double convention.
 */
@ApplicationScoped
class RecordingDisableObserver {

    void onSourceDisabled(@Observes PerSourceUnknownTracker.SourceDisabled event) {
        AutoDisableStopBeforeNotifyIT.ORDER.add(AutoDisableStopBeforeNotifyIT.STOP_SIGNAL);
    }
}

/**
 * Records that the admin notify fired, in append order relative to the stop
 * signal. Overrides {@code notifyOnce} only — no DB, no throttle state — and is
 * installed for the tracker via {@code QuarkusMock}.
 */
class RecordingNotifier extends ThrottledAdminNotifier {

    @Override
    public NotifyOutcome notifyOnce(String key, String errorClass, String message) {
        AutoDisableStopBeforeNotifyIT.ORDER.add(AutoDisableStopBeforeNotifyIT.ADMIN_NOTIFY);
        return NotifyOutcome.EMITTED;
    }
}
