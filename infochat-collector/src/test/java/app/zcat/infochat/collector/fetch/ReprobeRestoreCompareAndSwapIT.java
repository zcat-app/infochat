package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.rss.RssFetcher;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.notifier.NotifyOutcome;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins D42 property (e) (M1-754): the restore is a compare-and-swap
 * whose WHERE repeats the full eligibility predicate, because the
 * writers that can invalidate eligibility during the probe run
 * concurrently (the UNKNOWN-rate evaluator is a separate scheduled
 * job; {@code /remove-source} runs in the Provider process — D41's
 * single-Collector topology serializes neither). The racing test
 * Fetcher performs the concurrent write INSIDE {@code fetch()}, which
 * makes the selection→probe→restore race deterministic.
 *
 * <p>Also pins the payload gate: the probe's fetched batch reaches
 * {@code PostPersister}/{@code EvalQueueProducer} ONLY when the CAS
 * updated a row. A zero-post assertion covers both persists and
 * emits — {@code emit} takes the key {@code persist} returns, so
 * nothing can be emitted without a persisted row.
 */
@QuarkusTest
class ReprobeRestoreCompareAndSwapIT {

    private static final String PREFIX = "m1-754-cas-";
    private static final Instant PINNED_NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Inject
    ReprobeScheduler reprobeScheduler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    private RacingRssFetcher mockFetcher;

    @BeforeEach
    void setup() throws Exception {
        mockFetcher = new RacingRssFetcher();
        QuarkusMock.installMockForType(mockFetcher, RssFetcher.class,
            new FetcherKind.Literal("rss"));
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM admin_notification_state WHERE notification_key LIKE ?",
                ReprobeScheduler.ERROR_CLASS_REPROBE_RECOVERED + ":%");
            exec(conn, "DELETE FROM post WHERE source_id IN "
                + "(SELECT id FROM source WHERE identifier LIKE ?)",
                "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?",
                "https://example.com/" + PREFIX + "%");
        }
    }

    @Test
    void unknownRateUpgradeDuringProbeLeavesParkIntact() throws Exception {
        UUID sourceId = seedDueParked("upgradeRace");
        // The concurrent write: the security control upgrades the park
        // reason while the probe's network fetch is in flight (the same
        // statement PerSourceUnknownTracker issues on its upgrade path).
        mockFetcher.midProbe = () -> updateSource(sourceId,
            "UPDATE source SET park_reason = 'unknown-rate' WHERE id = ?");

        reprobeScheduler.runOnce();

        assertEquals(1, mockFetcher.callCount,
            "the probe itself must run (the race is selection→restore, not selection)");
        assertEquals("failed", readColumn(sourceId, "status"),
            "the CAS must no-op: the row was claimed by the security control mid-probe");
        assertEquals("unknown-rate", readColumn(sourceId, "park_reason"),
            "the upgraded manual-only reason must survive — an unguarded restore "
                + "would clear it and readmit the feed the control just caught");
        assertEquals(0, countPosts(sourceId),
            "the probe batch must be DISCARDED on a CAS no-op: zero posts persisted, "
                + "and therefore zero emitted (emit requires a persisted key)");
        assertEquals(0, countRestoreAudit(sourceId),
            "a no-op restore must write NO audit row");
        assertTrue(throttledAdminNotifier.getState(
                ReprobeScheduler.ERROR_CLASS_REPROBE_RECOVERED + ":" + sourceId).isEmpty(),
            "a no-op restore must fire NO RECOVERED notification");
    }

    @Test
    void removeSourceDuringProbeIsNotRevived() throws Exception {
        UUID sourceId = seedDueParked("removeRace");
        mockFetcher.midProbe = () -> updateSource(sourceId,
            "UPDATE source SET deleted_at = now() WHERE id = ?");

        reprobeScheduler.runOnce();

        assertEquals("failed", readColumn(sourceId, "status"),
            "the CAS must no-op: /remove-source landed mid-probe and a background "
                + "job must not undo an admin's soft-delete (D42 property (d))");
        assertNotNull(readColumn(sourceId, "deleted_at"),
            "deleted_at must survive the probe");
        assertEquals(0, countPosts(sourceId),
            "the probe batch must be discarded — a removed source's content "
                + "must not enter the outbox");
        assertEquals(0, countRestoreAudit(sourceId),
            "a no-op restore must write NO audit row");
        assertTrue(throttledAdminNotifier.getState(
                ReprobeScheduler.ERROR_CLASS_REPROBE_RECOVERED + ":" + sourceId).isEmpty(),
            "a no-op restore must fire NO RECOVERED notification");
    }

    @Test
    void uncontestedProbeRestoresPersistsAndAudits() throws Exception {
        // Positive control for both legs above: with no concurrent write
        // the same drive restores the row, lands the batch, and writes
        // the audit row — so the zero-assertions cannot pass vacuously.
        UUID sourceId = seedDueParked("uncontested");
        mockFetcher.midProbe = null;

        reprobeScheduler.runOnce();

        assertEquals("active", readColumn(sourceId, "status"),
            "an uncontested successful probe must restore the source");
        assertEquals(1, countPosts(sourceId),
            "the probe batch must be persisted once the CAS authorized it");
        assertEquals(1, countRestoreAudit(sourceId),
            "the restore must write exactly one SOURCE_REPROBE_RESTORED audit row");
        assertTrue(throttledAdminNotifier.getState(
                ReprobeScheduler.ERROR_CLASS_REPROBE_RECOVERED + ":" + sourceId).isPresent(),
            "the restore must fire the RECOVERED notification");
    }

    @Test
    void rolledBackRestoreLeavesNoOrphanAuditRow() throws Exception {
        // Force the transaction to roll back AFTER the CAS and audit
        // write, via a notifier that fails inside the same transaction:
        // the audit row, the restore, and the notification must all
        // vanish together (the same-transaction obligation of D42's
        // audit commitment).
        UUID sourceId = seedDueParked("rollback");
        mockFetcher.midProbe = null;
        QuarkusMock.installMockForType(new ThrowingConnNotifier(),
            ThrottledAdminNotifier.class);

        assertThrows(IllegalStateException.class, () -> reprobeScheduler.runOnce(),
            "the forced notifier failure must propagate out of the transaction");

        assertEquals("failed", readColumn(sourceId, "status"),
            "the rolled-back restore must leave the park standing");
        assertEquals(0, countRestoreAudit(sourceId),
            "the rolled-back restore must leave NO orphan audit row");
        assertEquals(0, countPosts(sourceId),
            "no batch may land when the restore never committed");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedDueParked(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, parked_at, "
                     + "  next_reprobe_at, consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'failed', 'fetch-failure', "
                     + "  now(), ?, 3) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setTimestamp(3, Timestamp.from(PINNED_NOW.minusSeconds(3600)));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void updateSource(UUID sourceId, String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, sourceId);
            ps.executeUpdate();
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

    private int countPosts(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countRestoreAudit(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM audit_log "
                     + "WHERE action = 'SOURCE_REPROBE_RESTORED' AND target_id = ?")) {
            ps.setString(1, sourceId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, String arg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }

    /**
     * Successful probe returning one post, with an optional concurrent
     * write executed while the "network fetch" is in flight.
     */
    static final class RacingRssFetcher extends RssFetcher {
        interface MidProbe {
            void run() throws Exception;
        }

        volatile MidProbe midProbe;
        volatile int callCount;

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            callCount++;
            if (midProbe != null) {
                try {
                    midProbe.run();
                } catch (Exception e) {
                    throw new IllegalStateException("mid-probe write failed", e);
                }
            }
            return List.of(new NormalizedPost(dispatchKey,
                "m1-754-cas-" + UUID.randomUUID(), null, "probe body", null, null,
                Instant.now(), Map.of()));
        }
    }

    /** Fails the transaction-participating notify to force a rollback. */
    static final class ThrowingConnNotifier extends ThrottledAdminNotifier {
        @Override
        public NotifyOutcome notifyOnce(Connection conn, String key, String errorClass,
                                        String message) throws SQLException {
            throw new SQLException("test-forced notifier failure inside the restore tx");
        }
    }
}
