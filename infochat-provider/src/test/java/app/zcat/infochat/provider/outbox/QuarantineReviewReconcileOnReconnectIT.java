package app.zcat.infochat.provider.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.OutboxItFixtures;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the reconnect catch-up on
 * {@link QuarantineReviewListener}: a reconnect must run the
 * {@link QuarantineReviewReconciler} catch-up so quarantine_review
 * NOTIFYs lost during a transient PG blip are recovered — cursor
 * advanced AND actionable missed events routed to the admin notifier —
 * without a process restart. Mirrors
 * {@link NewPostListenerReconcileOnReconnectIT}.
 *
 * <p>Race-free design: the PENDING quarantine row is seeded BEFORE the
 * forced disconnect and its NOTIFY is never emitted — the simulated
 * "NOTIFY lost during the blip". Whenever the worker's reconnect fires,
 * the row already exists, so the test does not depend on winning a
 * timing window. Because no NOTIFY is ever sent for the row and the
 * cursor was reset after the startup reconciler ran, the
 * post-reconnect reconcile is the only mechanism that can advance the
 * cursor to it and fire its notification.
 */
@QuarkusTest
class QuarantineReviewReconcileOnReconnectIT {

    private static final String TEST_UID_PREFIX = "qr-reconnect-it/";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AWAIT_POLL = Duration.ofMillis(100);
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    private static final String PENDING_KEY = "quarantine_review.pending";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject QuarantineReviewListener listener;
    @Inject ProviderStateDao providerStateDao;

    private UUID testSourceId;

    @BeforeEach
    void setUp() throws Exception {
        // Broad cleanup so the post-reconnect catch-up scan (which starts
        // from the reset epoch cursor) sees exactly the rows this test
        // seeds, regardless of which IT seeded rows previously.
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE '%-it/%'");
            exec(conn, "DELETE FROM post WHERE uid LIKE '%-it/%'");
            exec(conn, "DELETE FROM admin_notification_state WHERE notification_key LIKE 'quarantine_review.%'");
        }
        resetCursorToEpoch();
        testSourceId = ensureTestSource();
    }

    @Test
    void reconnectCatchesUpMissedActionableEvent() throws Exception {
        assertTrue(listener.isWorkerAlive(),
                "worker must be alive at test start");

        UUID quarantineId = seedPendingQuarantineRow(TEST_UID_PREFIX + "blip1");

        listener.closeListenConnectionForTest();

        awaitCursor(
                c -> quarantineId.toString().equals(c.cursorLowId()),
                "cursor must catch up to the un-NOTIFYed PENDING quarantine row "
                        + "within " + AWAIT_TIMEOUT + " — no NOTIFY was ever emitted "
                        + "for it, so only the post-reconnect reconcile can deliver "
                        + "it; if the cursor never advances, the reconnect did not "
                        + "run the reconciler");

        // Catch-up routes through the same handler as live dispatch, so
        // the actionable missed event must also have paged the admin —
        // the notification row commits in the same transaction as the
        // cursor advance asserted above.
        assertTrue(adminNotificationExists(PENDING_KEY),
                "the actionable event caught up after the reconnect must reach "
                        + "the admin notifier, not just advance the cursor");

        assertTrue(listener.isWorkerAlive(),
                "worker must remain alive after the reconnect-plus-reconcile sequence");
    }

    // ---- Helpers ----

    private UUID seedPendingQuarantineRow(String postUid) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID postId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post (source_id, uid, title, body, fetched_at, status, "
                            + "upstream_identifier) "
                            + "VALUES (?, ?, 'Test', 'body [REDACTED:ph-' || ? || ']', ?, 'QUARANTINED', ?) "
                            + "RETURNING id")) {
                ps.setObject(1, testSourceId);
                ps.setString(2, postUid);
                ps.setString(3, postUid);
                ps.setObject(4, OffsetDateTime.ofInstant(FETCHED_AT, ZoneOffset.UTC));
                ps.setString(5, postUid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    postId = rs.getObject("id", UUID.class);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO quarantine (post_id, post_uid, post_fetched_at, flagged_by, "
                            + "rule_id, span_start, span_end, placeholder_id, original_html, status) "
                            + "VALUES (?, ?, ?, 'stage1', 'rule-1', 0, 10, ?, '<b>orig</b>', 'PENDING') "
                            + "RETURNING id")) {
                ps.setObject(1, postId);
                ps.setString(2, postUid);
                ps.setObject(3, OffsetDateTime.ofInstant(FETCHED_AT, ZoneOffset.UTC));
                ps.setString(4, "ph-" + postUid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject("id", UUID.class);
                }
            }
        }
    }

    private boolean adminNotificationExists(String key) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void resetCursorToEpoch() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE provider_state "
                             + "SET cursor_high = 'epoch'::TIMESTAMPTZ, "
                             + "    cursor_low_kind = '', "
                             + "    cursor_low_id = '', "
                             + "    updated_at = now() "
                             + "WHERE channel = 'quarantine_review'")) {
            ps.executeUpdate();
        }
    }

    private UUID ensureTestSource() throws Exception {
        return OutboxItFixtures.ensureTestSource(dataSource, "qr-reconnect-it://test", "qr-reconnect-it");
    }

    private void awaitCursor(Predicate<ProviderStateDao.Cursor> predicate,
                             String failMsg) throws Exception {
        OutboxItFixtures.awaitCursor(providerStateDao, QuarantineReviewListener.CHANNEL,
            predicate, failMsg, AWAIT_TIMEOUT, AWAIT_POLL);
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
