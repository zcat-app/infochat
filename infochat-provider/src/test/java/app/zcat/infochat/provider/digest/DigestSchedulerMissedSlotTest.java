package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

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

    @Inject
    DataSource dataSource;

    @Inject
    DigestScheduler scheduler;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    SummaryCacheRepository summaryCacheRepository;

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

    @Test
    void missedSlot_notifiesAdminOnce() throws Exception {
        UUID groupId = insertGroup("UTC");

        // Morning window [07:45, 08:15] UTC. Clock at 09:00 = past window-end.
        Instant now = todayAt(9, 0, "UTC");
        scheduler.tickAt(now);

        // Build the expected notification key
        Instant windowStart = todayAt(7, 45, "UTC");
        String date = windowStart.toString().substring(0, 10);
        String expectedKey = "digest_slot_missed:" + groupId + ":morning:" + date;

        Optional<AdminNotificationRecord> state = throttledAdminNotifier.getState(expectedKey);
        assertTrue(state.isPresent(),
                "ThrottledAdminNotifier must have a state entry for key: " + expectedKey);
        assertEquals("DIGEST", state.get().errorClass());

        // A second tick should not emit a second notification (sentinel prevents re-detection)
        scheduler.tickAt(now.plusSeconds(60));
        // The notification count is still 1 (sentinel prevents recordMissedSlot from re-firing)
        Optional<AdminNotificationRecord> stateAfter = throttledAdminNotifier.getState(expectedKey);
        assertTrue(stateAfter.isPresent());
        assertEquals(state.get().notificationCount(), stateAfter.get().notificationCount(),
                "sentinel must prevent re-notification on subsequent ticks");
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
