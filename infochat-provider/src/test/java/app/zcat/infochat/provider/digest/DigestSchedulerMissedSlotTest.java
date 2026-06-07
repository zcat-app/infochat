package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.command.ApproveGroupCommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link DigestScheduler} calls
 * {@link ThrottledAdminNotifier#notifyOnce} when a missed digest slot
 * is detected. Complements the existing {@link DigestSchedulerTest}
 * which covers the slot-emission and audit-logging paths.
 */
@QuarkusTest
class DigestSchedulerMissedSlotTest {

    private static final String ADMIN_PREFIX = "digest-preapprove-admin-";
    private static final String UPSTREAM_PREFIX = "digest-preapprove-grp-";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    DigestScheduler scheduler;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    SummaryCacheRepository summaryCacheRepository;

    @Inject
    ApproveGroupCommandHandler approveGroupCommandHandler;

    @Inject
    InboundContext inboundContext;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM summary_cache")) {
            ps.executeUpdate();
        }
        // Clear admin_notification_state rows from prior test runs
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM admin_notification_state WHERE notification_key LIKE 'digest_slot_missed:%'")) {
            ps.executeUpdate();
        }
    }

    // The pre-approval tests drive the real ApproveGroupCommandHandler,
    // which needs a seeded admin user. A leftover is_admin=true row would
    // contaminate the global last-admin count other tests depend on
    // (precedent: ApproveGroupCommandHandlerTest cleanup), so admin users
    // and handler-approved groups are deleted by prefix before AND after
    // each test, with the append-only audit triggers and the last-admin
    // delete trigger temporarily disabled for the cleanup pass only.
    @BeforeEach
    @AfterEach
    void cleanupApprovalFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            exec(conn, "ALTER TABLE users DISABLE TRIGGER trg_users_last_admin_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_id IN ("
                                + "  SELECT id::text FROM groups WHERE upstream_group_id LIKE ?)",
                        UPSTREAM_PREFIX + "%");
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN "
                                + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                        ADMIN_PREFIX + "%");
                // Missed-slot sentinels reference the group via FK; clear
                // them before the group rows or the group DELETE fails.
                exec(conn,
                        "DELETE FROM summary_cache WHERE group_id IN ("
                                + "  SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                        UPSTREAM_PREFIX + "%");
                exec(conn,
                        "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                        UPSTREAM_PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        ADMIN_PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_delete");
            }
        }
    }

    @Test
    void missedSlot_notifiesAdminOnce() throws Exception {
        UUID groupId = insertGroup("UTC");

        // Morning window [07:45, 08:15] UTC. Clock at 09:00 = past window-end.
        Instant now = todayAt(9, 0, "UTC");
        awaitDispatches(scheduler.tickAt(now));

        // Build the expected notification key
        Instant windowStart = todayAt(7, 45, "UTC");
        String date = windowStart.toString().substring(0, 10);
        String expectedKey = "digest_slot_missed:" + groupId + ":morning:" + date;

        Optional<AdminNotificationRecord> state = throttledAdminNotifier.getState(expectedKey);
        assertTrue(state.isPresent(),
                "ThrottledAdminNotifier must have a state entry for key: " + expectedKey);
        assertEquals("DIGEST", state.get().errorClass());

        // A second tick should not emit a second notification (sentinel prevents re-detection)
        awaitDispatches(scheduler.tickAt(now.plusSeconds(60)));
        // The notification count is still 1 (sentinel prevents recordMissedSlot from re-firing)
        Optional<AdminNotificationRecord> stateAfter = throttledAdminNotifier.getState(expectedKey);
        assertTrue(stateAfter.isPresent());
        assertEquals(state.get().notificationCount(), stateAfter.get().notificationCount(),
                "sentinel must prevent re-notification on subsequent ticks");
    }

    @Test
    void approveAfterWindowPassed_recordsNoMissAndNoNotificationForPreApprovalWindow()
            throws Exception {
        // Fixed-offset zone where local time is ~13:00 right now, so today's
        // morning window [07:45, 08:15] local has ALWAYS already ended when
        // the approval lands, regardless of when this test runs.
        ZoneOffset tz = zoneWithLocalHour(13);
        UUID groupId = insertPendingGroup(tz.getId());

        approveViaHandler(groupId);

        awaitDispatches(scheduler.tickAt(Instant.now()));

        assertEquals(0, countMissedSlotAuditRows(groupId),
                "a window that ended before approval must not produce a"
                        + " DIGEST_SLOT_MISSED audit row (skip-not-catch-up)");
        Instant windowStart = todayAt(7, 45, tz.getId());
        String date = windowStart.toString().substring(0, 10);
        String key = "digest_slot_missed:" + groupId + ":morning:" + date;
        assertTrue(throttledAdminNotifier.getState(key).isEmpty(),
                "a window that ended before approval must not notify the admin");
    }

    @Test
    void windowEndingAfterApproval_stillRecordsMissedSlot() throws Exception {
        ZoneOffset tz = zoneWithLocalHour(13);
        UUID groupId = insertPendingGroup(tz.getId());

        approveViaHandler(groupId);

        // Tomorrow's morning window ends AFTER today's approval: a genuine
        // miss for an approved group. The approval row must gate only
        // pre-approval windows, never blanket-skip the group.
        awaitDispatches(scheduler.tickAt(Instant.now().plus(Duration.ofDays(1))));

        assertEquals(1, countMissedSlotAuditRows(groupId),
                "a window ending after approval must still record DIGEST_SLOT_MISSED");
    }

    /**
     * Approve the group through the real handler so the test covers the
     * complete promise: the same-transaction APPROVE_GROUP audit row the
     * scheduler derives eligibility time from is written by the production
     * approval path, not simulated by the test.
     */
    private void approveViaHandler(UUID groupId) throws Exception {
        String adminContact = ADMIN_PREFIX + UUID.randomUUID();
        seedAdmin(adminContact);
        inboundContext.setAdapterName("inmemory");
        inboundContext.setSenderContactId(adminContact);

        approveGroupCommandHandler.handle(
                new ScopeRef.Dm(adminContact), "/approve-group " + groupId);

        assertEquals("approved", readApprovalStatus(groupId),
                "handler must flip the group to approved");
        assertEquals(1, countAuditRows("APPROVE_GROUP", groupId),
                "handler must write the APPROVE_GROUP audit row the scheduler"
                        + " derives the eligibility time from");
    }

    /** Fixed-offset zone whose local time is currently ~targetHour:00. */
    private static ZoneOffset zoneWithLocalHour(int targetHour) {
        int utcHour = Instant.now().atZone(ZoneOffset.UTC).getHour();
        return ZoneOffset.ofHours(targetHour - utcHour);
    }

    private void seedAdmin(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, registration_state) "
                             + "VALUES ('inmemory', ?, true, false, 'vouched')")) {
            ps.setString(1, contactId);
            ps.executeUpdate();
        }
    }

    private UUID insertPendingGroup(String timezone) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name, timezone, approval_status) "
                             + "VALUES ('inmemory', ?, 'test-preapproval', ?, 'pending') "
                             + "RETURNING id")) {
            ps.setString(1, UPSTREAM_PREFIX + UUID.randomUUID());
            ps.setString(2, timezone);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private String readApprovalStatus(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT approval_status FROM groups WHERE id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("approval_status");
            }
        }
    }

    private long countMissedSlotAuditRows(UUID groupId) throws Exception {
        return countAuditRows("DIGEST_SLOT_MISSED", groupId);
    }

    private long countAuditRows(String action, UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void awaitDispatches(List<Future<?>> dispatches) throws Exception {
        for (Future<?> dispatch : dispatches) {
            dispatch.get(10, TimeUnit.SECONDS);
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    private UUID insertGroup(String timezone) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name, timezone, approval_status) "
                             + "VALUES ('inmemory', ?, 'test-missed-slot', ?, 'approved') "
                             + "RETURNING id")) {
            ps.setString(1, "missed-slot-" + UUID.randomUUID());
            ps.setString(2, timezone);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private Instant todayAt(int hour, int minute, String timezone) {
        ZoneId tz = ZoneId.of(timezone);
        LocalDate today = LocalDate.now(tz);
        return ZonedDateTime.of(today, LocalTime.of(hour, minute), tz).toInstant();
    }
}
