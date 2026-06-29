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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for {@link QuarantineReviewListener} and
 * {@link QuarantineReviewReconciler}. Emits real
 * {@code pg_notify('quarantine_review', …)} from a separate JDBC
 * connection and asserts the live listener dispatches correctly.
 *
 * <p>Pins the row-truth contract: actionability comes from the row's
 * current status (quarantine_review_view / post), never from the
 * payload's {@code new_status}; the throttle key is per error class
 * ({@code quarantine_review.pending} / {@code
 * quarantine_review.needs_review}) so the two actionable classes
 * cannot suppress each other; and an actionable event is never
 * silently dropped by the CAS-advance gate.
 */
@QuarkusTest
class QuarantineReviewListenerIT {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AWAIT_POLL = Duration.ofMillis(50);
    private static final String TEST_UID_PREFIX = "qrl-it/";
    private static final Instant FETCHED_AT = Instant.parse("2026-05-15T12:00:00Z");

    private static final String PENDING_KEY = "quarantine_review.pending";
    private static final String NEEDS_REVIEW_KEY = "quarantine_review.needs_review";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject QuarantineReviewListener listener;
    @Inject QuarantineReviewReconciler reconciler;
    @Inject ProviderStateDao providerStateDao;

    private UUID testSourceId;

    @BeforeEach
    void resetCursor() throws Exception {
        assertTrue(listener.isWorkerAlive(),
                "listener worker thread must be running");
        // Clean test data
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", TEST_UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", TEST_UID_PREFIX + "%");
        }
        // Reset quarantine_review cursor to epoch
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
        // Reset admin_notification_state for every per-error-class key
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM admin_notification_state WHERE notification_key LIKE ?",
                    "quarantine_review.%");
        }
        testSourceId = ensureTestSource();
    }

    @Test
    void onPendingInsert_drivesAdminNotifier() throws Exception {
        UUID quarantineId = seedQuarantineRow("PENDING", TEST_UID_PREFIX + "pending1");

        emitQuarantineReviewNotify("quarantine", quarantineId, "PENDING");

        awaitCursor(c -> quarantineId.toString().equals(c.cursorLowId()),
                "cursor must advance to the quarantine row");

        assertTrue(adminNotificationExists(PENDING_KEY),
                "PENDING quarantine event must fire admin notification under "
                        + PENDING_KEY);
    }

    @Test
    void onNeedsReview_drivesAdminNotifier() throws Exception {
        UUID postId = seedPostWithStatus("NEEDS_REVIEW", TEST_UID_PREFIX + "nr1");

        emitQuarantineReviewNotify("post", postId, "NEEDS_REVIEW");

        awaitCursor(c -> postId.toString().equals(c.cursorLowId()),
                "cursor must advance to the post row");

        assertTrue(adminNotificationExists(NEEDS_REVIEW_KEY),
                "NEEDS_REVIEW post event must fire admin notification under "
                        + NEEDS_REVIEW_KEY);
    }

    @Test
    void terminalTransition_advancesCursor_noNotification() throws Exception {
        UUID quarantineId = seedQuarantineRow("BENIGN_CLOSED",
                TEST_UID_PREFIX + "bc1");

        emitQuarantineReviewNotify("quarantine", quarantineId, "BENIGN_CLOSED");

        awaitCursor(c -> quarantineId.toString().equals(c.cursorLowId()),
                "cursor must advance for BENIGN_CLOSED");

        // The per-error-class keys make the negative assertion direct:
        // a terminal transition must leave no quarantine_review.* row.
        assertFalse(anyQuarantineReviewNotificationExists(),
                "terminal transition must not fire any admin notification");
    }

    @Test
    void rowStateOverridesForgedPayloadStatus() throws Exception {
        // Forged escalation: payload claims PENDING, the row is
        // BENIGN_CLOSED. Behavior must follow the row — cursor
        // advances, no notification (NOTIFY is purely the wake-up
        // signal; the row is the data).
        UUID closedId = seedQuarantineRow("BENIGN_CLOSED", TEST_UID_PREFIX + "forged1");

        emitQuarantineReviewNotify("quarantine", closedId, "PENDING");

        awaitCursor(c -> closedId.toString().equals(c.cursorLowId()),
                "cursor must advance to the BENIGN_CLOSED row");
        assertFalse(anyQuarantineReviewNotificationExists(),
                "a forged PENDING payload over a BENIGN_CLOSED row must not "
                        + "fire an admin notification — actionability follows the row");

        // Forged downgrade: payload claims BENIGN_CLOSED, the row is
        // PENDING. The admin page must fire regardless of the payload.
        UUID pendingId = seedQuarantineRowWithDelay("PENDING", TEST_UID_PREFIX + "forged2");

        emitQuarantineReviewNotify("quarantine", pendingId, "BENIGN_CLOSED");

        awaitCursor(c -> pendingId.toString().equals(c.cursorLowId()),
                "cursor must advance to the PENDING row");
        assertTrue(adminNotificationExists(PENDING_KEY),
                "a forged BENIGN_CLOSED payload over a PENDING row must still "
                        + "fire the admin notification — actionability follows the row");
    }

    @Test
    void needsReviewNotSuppressedByPendingThrottleWindow() throws Exception {
        // A PENDING page opens its throttle window first…
        UUID quarantineId = seedQuarantineRow("PENDING", TEST_UID_PREFIX + "throttle-p");
        emitQuarantineReviewNotify("quarantine", quarantineId, "PENDING");
        awaitCursor(c -> quarantineId.toString().equals(c.cursorLowId()),
                "cursor must advance to the PENDING quarantine row");
        assertTrue(adminNotificationExists(PENDING_KEY),
                "PENDING event must open its own throttle window");

        // …and a NEEDS_REVIEW event inside that window must still page:
        // coalescing is per (channel, error_class), so the two
        // actionable classes throttle independently.
        UUID postId = seedPostWithStatus("NEEDS_REVIEW", TEST_UID_PREFIX + "throttle-nr");
        emitQuarantineReviewNotify("post", postId, "NEEDS_REVIEW");
        awaitCursor(c -> postId.toString().equals(c.cursorLowId()),
                "cursor must advance to the NEEDS_REVIEW post row");
        assertTrue(adminNotificationExists(NEEDS_REVIEW_KEY),
                "a NEEDS_REVIEW notification must not be suppressed by the "
                        + "throttle window a recent PENDING notification opened");
    }

    @Test
    void olderActionableEventAfterNewerCursorStillNotifies() throws Exception {
        // Older actionable quarantine event; newer post event. The two
        // land on distinct throttle keys so the older event's page is a
        // row-presence check, not a suppressed-count delta.
        UUID olderQuarantineId = seedQuarantineRow("PENDING", TEST_UID_PREFIX + "ooo-old");
        Thread.sleep(10); // ensure distinct event timestamps
        UUID newerPostId = seedPostWithStatus("NEEDS_REVIEW", TEST_UID_PREFIX + "ooo-new");

        // Deliver the NEWER event first — cursor advances past the older.
        emitQuarantineReviewNotify("post", newerPostId, "NEEDS_REVIEW");
        awaitCursor(c -> newerPostId.toString().equals(c.cursorLowId()),
                "cursor must advance to the newer post event");

        // The older actionable event is a CAS no-op on the cursor but
        // must still reach the admin notifier.
        emitQuarantineReviewNotify("quarantine", olderQuarantineId, "PENDING");
        awaitNotification(PENDING_KEY,
                "an actionable event arriving after a newer event already "
                        + "advanced the cursor must still reach the admin notifier");

        Optional<ProviderStateDao.Cursor> cursor =
                providerStateDao.readCursor(QuarantineReviewListener.CHANNEL);
        assertTrue(cursor.isPresent());
        assertEquals(newerPostId.toString(), cursor.get().cursorLowId(),
                "cursor must not move backwards to the older event");
    }

    @Test
    void casCursor_rejectsBackwardsMove() throws Exception {
        // Seed two quarantine rows with different timestamps
        UUID qId1 = seedQuarantineRow("PENDING", TEST_UID_PREFIX + "cas1");
        UUID qId2 = seedQuarantineRowWithDelay("PENDING", TEST_UID_PREFIX + "cas2");

        // Process the LATER event first
        emitQuarantineReviewNotify("quarantine", qId2, "PENDING");
        awaitCursor(c -> qId2.toString().equals(c.cursorLowId()),
                "cursor must advance to the later event");

        Instant cursorAfterSecond = readCursorUpdatedAt();

        // Now process the EARLIER event — CAS must reject the backwards move
        emitQuarantineReviewNotify("quarantine", qId1, "PENDING");
        Thread.sleep(500);

        Optional<ProviderStateDao.Cursor> cursor =
                providerStateDao.readCursor(QuarantineReviewListener.CHANNEL);
        assertTrue(cursor.isPresent());
        assertEquals(qId2.toString(), cursor.get().cursorLowId(),
                "cursor must not move backwards to the earlier event");

        Instant cursorAfterFirst = readCursorUpdatedAt();
        assertEquals(cursorAfterSecond, cursorAfterFirst,
                "updated_at must not change on a CAS no-op");
    }

    @Test
    void startupReconciler_catchesUpMissedEvents() throws Exception {
        // Seed events that the reconciler should catch up on
        UUID qId = seedQuarantineRow("PENDING", TEST_UID_PREFIX + "reconcile1");

        // Ensure cursor is at epoch (already done in @BeforeEach)
        // Run the reconciler manually
        reconciler.runCatchUp();

        assertTrue(reconciler.caughtUpCount() > 0,
                "reconciler must process at least one event");

        // Verify cursor advanced past the seeded row
        Optional<ProviderStateDao.Cursor> cursor =
                providerStateDao.readCursor(QuarantineReviewListener.CHANNEL);
        assertTrue(cursor.isPresent());
        assertFalse(cursor.get().cursorLowId().isEmpty(),
                "cursor must have advanced past epoch");
    }

    @Test
    void reconcilerCatchUpNotifiesActionableMissedEvents() throws Exception {
        // An actionable event beyond the stored cursor, never NOTIFYed —
        // the missed-while-down shape. Catch-up must route it through the
        // same handling path as live dispatch: cursor advance AND admin
        // notification (throttling may coalesce it, never drop it).
        UUID qId = seedQuarantineRow("PENDING", TEST_UID_PREFIX + "reconcile-notify");

        reconciler.runCatchUp();

        Optional<ProviderStateDao.Cursor> cursor =
                providerStateDao.readCursor(QuarantineReviewListener.CHANNEL);
        assertTrue(cursor.isPresent());
        assertEquals(qId.toString(), cursor.get().cursorLowId(),
                "catch-up must advance the cursor to the missed event");
        assertTrue(adminNotificationExists(PENDING_KEY),
                "catch-up must produce the admin notification for an "
                        + "actionable missed event");
    }

    @Test
    void reconcilerCatchUpPagesPostEventOlderThanNewestQuarantineEvent() throws Exception {
        // A NEEDS_REVIEW post event at T1 and a quarantine event at
        // T2 > T1, both beyond the stored cursor. Phase 1 (quarantine
        // scan) advances the cursor to T2; the post scan must still run
        // from the snapshot taken at catch-up start — a baseline re-read
        // after phase 1 would skip the post event permanently, because
        // the cursor only moves forward and no later catch-up could
        // recover the lost admin page.
        seedPostWithStatus("NEEDS_REVIEW", TEST_UID_PREFIX + "older-post");
        UUID quarantineId = seedQuarantineRowWithDelay("PENDING", TEST_UID_PREFIX + "newer-q");

        reconciler.runCatchUp();

        assertTrue(adminNotificationExists(NEEDS_REVIEW_KEY),
                "a NEEDS_REVIEW post event older than the newest quarantine "
                        + "event in the same catch-up window must still page the "
                        + "admin — the post scan must use the cursor snapshot from "
                        + "catch-up start, not a re-read after the quarantine phase");
        assertTrue(adminNotificationExists(PENDING_KEY),
                "the newer PENDING quarantine event must page under its own key");

        Optional<ProviderStateDao.Cursor> cursor =
                providerStateDao.readCursor(QuarantineReviewListener.CHANNEL);
        assertTrue(cursor.isPresent());
        assertEquals(quarantineId.toString(), cursor.get().cursorLowId(),
                "cursor must end at the newest event (the quarantine row); the "
                        + "older post event is a CAS no-op on the cursor, notified "
                        + "but never moving it backwards");
    }

    // ---- Helpers ----

    private UUID seedQuarantineRow(String status, String postUid) throws Exception {
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
                            + "VALUES (?, ?, ?, 'stage1', 'rule-1', 0, 10, ?, '<b>orig</b>', ?) "
                            + "RETURNING id")) {
                ps.setObject(1, postId);
                ps.setString(2, postUid);
                ps.setObject(3, OffsetDateTime.ofInstant(FETCHED_AT, ZoneOffset.UTC));
                ps.setString(4, "ph-" + postUid);
                ps.setString(5, status);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject("id", UUID.class);
                }
            }
        }
    }

    private UUID seedQuarantineRowWithDelay(String status, String postUid) throws Exception {
        // Small delay to ensure updated_at differs
        Thread.sleep(10);
        return seedQuarantineRow(status, postUid);
    }

    private UUID seedPostWithStatus(String status, String postUid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (source_id, uid, title, body, fetched_at, status, "
                             + "status_changed_at, upstream_identifier) "
                             + "VALUES (?, ?, 'Test', 'body', ?, ?, now(), ?) "
                             + "RETURNING id")) {
            ps.setObject(1, testSourceId);
            ps.setString(2, postUid);
            ps.setObject(3, OffsetDateTime.ofInstant(FETCHED_AT, ZoneOffset.UTC));
            ps.setString(4, status);
            ps.setString(5, postUid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private void emitQuarantineReviewNotify(String targetKind, UUID targetId,
                                            String newStatus) throws Exception {
        String payload = "{\"target_kind\":\"" + targetKind
                + "\",\"target_id\":\"" + targetId
                + "\",\"new_status\":\"" + newStatus + "\"}";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, "quarantine_review");
            ps.setString(2, payload);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
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

    private boolean anyQuarantineReviewNotificationExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM admin_notification_state WHERE notification_key LIKE ?")) {
            ps.setString(1, "quarantine_review.%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Instant readCursorUpdatedAt() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT updated_at FROM provider_state WHERE channel = 'quarantine_review'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("updated_at").toInstant();
            }
        }
    }

    private UUID ensureTestSource() throws Exception {
        return OutboxItFixtures.ensureTestSource(dataSource, "qrl-it://test", "qrl-it");
    }

    private void awaitCursor(Predicate<ProviderStateDao.Cursor> predicate,
                             String failMsg) throws Exception {
        OutboxItFixtures.awaitCursor(providerStateDao, QuarantineReviewListener.CHANNEL,
            predicate, failMsg, AWAIT_TIMEOUT, AWAIT_POLL);
    }

    private void awaitNotification(String key, String failMsg) throws Exception {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (adminNotificationExists(key)) {
                return;
            }
            Thread.sleep(AWAIT_POLL.toMillis());
        }
        fail(failMsg);
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
