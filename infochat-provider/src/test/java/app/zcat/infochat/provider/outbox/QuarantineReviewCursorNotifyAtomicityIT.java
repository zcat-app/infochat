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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the same-transaction invariant on
 * {@link QuarantineReviewListener#handleEvent}: the cursor advance and
 * the admin-notification persistence commit atomically
 * (docs/spec/architecture.md §Inter-service communication — "the
 * high-water mark advances both fields in the same DB transaction as
 * the side effect it triggers"). The test forces the notification
 * write to fail via a Postgres trigger on
 * {@code admin_notification_state} and asserts the cursor did NOT
 * advance past the event — a split-transaction implementation (cursor
 * committed on one connection, notification attempted on another)
 * would leave the cursor advanced while the notification is lost.
 *
 * <p>The trigger predicate is scoped to
 * {@code quarantine_review.%} keys so concurrently-running beans using
 * other notification keys are unaffected, and the failure injection is
 * deterministic — no sleeps, no timing window.
 */
@QuarkusTest
class QuarantineReviewCursorNotifyAtomicityIT {

    private static final String TEST_UID_PREFIX = "qr-atomicity-it/";
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    private static final String TRIGGER_FUNCTION = "qr_atomicity_it_fail";
    private static final String TRIGGER_NAME = "qr_atomicity_it_fail_tg";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject QuarantineReviewListener listener;
    @Inject ProviderStateDao providerStateDao;

    private UUID testSourceId;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", TEST_UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", TEST_UID_PREFIX + "%");
            exec(conn, "DELETE FROM admin_notification_state WHERE notification_key LIKE ?",
                    "quarantine_review.%");
        }
        resetCursorToEpoch();
        testSourceId = ensureTestSource();
    }

    @Test
    void notificationWriteFailureRollsBackCursorAdvance() throws Exception {
        UUID quarantineId = seedPendingQuarantineRow(TEST_UID_PREFIX + "atomic1");

        installFailingTrigger();
        try {
            // Through the injected client proxy — the @Transactional
            // interceptor must wrap the call for the invariant to hold.
            assertThrows(SQLException.class,
                    () -> listener.handleEvent("quarantine", quarantineId),
                    "the forced notification-write failure must propagate, "
                            + "not be swallowed");
        } finally {
            dropFailingTrigger();
        }

        ProviderStateDao.Cursor cursor = providerStateDao
                .readCursor(QuarantineReviewListener.CHANNEL).orElseThrow();
        assertEquals("", cursor.cursorLowId(),
                "the cursor must not advance past an event whose admin "
                        + "notification failed to persist — cursor advance and "
                        + "notification must commit in the same transaction");

        assertFalse(quarantineReviewNotificationExists(),
                "no notification row may exist after the rolled-back transaction");

        // Same event, no failure injection: both effects now commit.
        assertTrue(listener.handleEvent("quarantine", quarantineId),
                "the retried event must advance the cursor once the "
                        + "notification write succeeds");
        cursor = providerStateDao.readCursor(QuarantineReviewListener.CHANNEL).orElseThrow();
        assertEquals(quarantineId.toString(), cursor.cursorLowId(),
                "cursor must advance to the event on the successful retry");
        assertTrue(quarantineReviewNotificationExists(),
                "the notification row must exist after the successful retry");
    }

    // ---- Helpers ----

    private void installFailingTrigger() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE FUNCTION " + TRIGGER_FUNCTION + "() "
                    + "RETURNS trigger AS $$ BEGIN "
                    + "RAISE EXCEPTION 'atomicity-it: forced admin_notification_state write failure'; "
                    + "END; $$ LANGUAGE plpgsql");
            stmt.execute("CREATE TRIGGER " + TRIGGER_NAME + " "
                    + "BEFORE INSERT OR UPDATE ON admin_notification_state "
                    + "FOR EACH ROW WHEN (NEW.notification_key LIKE 'quarantine_review.%') "
                    + "EXECUTE FUNCTION " + TRIGGER_FUNCTION + "()");
        }
    }

    private void dropFailingTrigger() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TRIGGER IF EXISTS " + TRIGGER_NAME
                    + " ON admin_notification_state");
            stmt.execute("DROP FUNCTION IF EXISTS " + TRIGGER_FUNCTION + "()");
        }
    }

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

    private boolean quarantineReviewNotificationExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM admin_notification_state WHERE notification_key LIKE ?")) {
            ps.setString(1, "quarantine_review.%");
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
        return OutboxItFixtures.ensureTestSource(dataSource, "qr-atomicity-it://test", "qr-atomicity-it");
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
