package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Periodic scheduler that evaluates which groups have an open digest
 * slot window and emits {@link DigestSlot} CDI events for the
 * DigestWorker (M1-080b) to consume. Fires on a configurable cadence
 * and applies per-group staggering so groups sharing a window don't
 * all fire simultaneously.
 *
 * <p>Missed slots (past window-end with no summary_cache row) are
 * recorded as {@link AuditAction#DIGEST_SLOT_MISSED} audit rows and
 * a sentinel cache row is inserted to prevent re-detection on the
 * next tick.</p>
 */
@ApplicationScoped
public class DigestScheduler {

    private static final Logger LOG = Logger.getLogger(DigestScheduler.class);

    private static final String SLOT_MORNING = "morning";
    private static final String SLOT_EVENING = "evening";

    // WARN once per (group, offending value), not per tick — the scheduler
    // fires every minute and an unfixed timezone would flood the log.
    private final Set<String> warnedTimezones = ConcurrentHashMap.newKeySet();

    @Inject
    DataSource dataSource;

    @Inject
    Clock clock;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    Event<DigestSlot> digestSlotEvent;

    @Inject
    SummaryCacheRepository summaryCacheRepository;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.digest.morning-slot-hour", defaultValue = "8")
    int morningSlotHour;

    @ConfigProperty(name = "infochat.digest.evening-slot-hour", defaultValue = "20")
    int eveningSlotHour;

    @ConfigProperty(name = "infochat.digest.window-width-minutes", defaultValue = "30")
    int windowWidthMinutes;

    @Scheduled(every = "{infochat.digest.tick-interval:60s}")
    void tick() {
        tickAt(clock.instant());
    }

    // Package-private: called directly by tests with a controlled instant
    void tickAt(Instant now) {
        List<GroupRow> groups = queryActiveGroups();
        for (GroupRow group : groups) {
            ZoneId tz = parseTimezone(group.id, group.timezone);
            if (tz == null) continue;
            processSlot(now, group, tz, SLOT_MORNING, morningSlotHour);
            processSlot(now, group, tz, SLOT_EVENING, eveningSlotHour);
        }
    }

    private void processSlot(Instant now, GroupRow group, ZoneId tz,
                             String slotKind, int centerHour) {
        LocalDate today = now.atZone(tz).toLocalDate();
        ZonedDateTime center = ZonedDateTime.of(today, LocalTime.of(centerHour, 0), tz);
        Instant windowStart = center.minusMinutes(windowWidthMinutes / 2).toInstant();
        Instant windowEnd = center.plusMinutes((windowWidthMinutes + 1) / 2).toInstant();

        int staggerOffsetMinutes = staggerOffset(group.id, windowWidthMinutes);
        Instant effectiveFireTime = windowStart.plus(Duration.ofMinutes(staggerOffsetMinutes));

        try {
            if (now.isBefore(windowStart)) {
                return;
            }

            boolean alreadyFired = summaryCacheRepository.existsByGroupAndSlot(
                    group.id, slotKind, windowStart);
            if (alreadyFired) {
                return;
            }

            if (!now.isBefore(windowEnd)) {
                // Past window-end with no cache row: missed slot
                recordMissedSlot(group.id, slotKind, windowStart, windowEnd);
                return;
            }

            if (!now.isBefore(effectiveFireTime)) {
                // Within window and past stagger time: emit
                digestSlotEvent.fire(new DigestSlot(
                        group.id, group.timezone, slotKind, windowStart, windowEnd));
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Digest scheduler failed for group " + group.id + " slot " + slotKind, e);
        }
    }

    // Package-private: called directly by tests to verify audit+sentinel atomicity
    void recordMissedSlot(UUID groupId, String slotKind,
                          Instant windowStart, Instant windowEnd) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                auditLogWriter.write(conn, new RedactionHook.AuditRow(
                        null, null, null,
                        AuditAction.DIGEST_SLOT_MISSED,
                        "group", groupId.toString(),
                        null, groupId, null,
                        "{\"slot_kind\":\"" + slotKind
                                + "\",\"window_start\":\"" + windowStart + "\"}"));
                // Sentinel in the SAME transaction as the audit row: a crash
                // or unique-index conflict between the two writes must not
                // leave a committed audit row without its sentinel — the next
                // tick would re-detect the miss and duplicate the audit row.
                summaryCacheRepository.insert(conn,
                        groupId, slotKind, windowStart,
                        0L, 0L, "", true, windowEnd);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        // Throttle key: one notification per unique missed slot, not per tick
        String date = windowStart.toString().substring(0, 10);
        throttledAdminNotifier.notifyOnce(
                "digest_slot_missed:" + groupId + ":" + slotKind + ":" + date,
                "DIGEST",
                "Missed digest slot for group " + groupId
                        + " slot " + slotKind + " window " + windowStart);
    }

    /**
     * Deterministic stagger offset for a group within the window.
     * Uses the UUID's most-significant bits so the hash is stable
     * across JVM restarts (unlike Object.hashCode()).
     */
    static int staggerOffset(@NonNull UUID groupId, int windowWidthMinutes) {
        long hash = groupId.getMostSignificantBits();
        return (int) (Math.abs(hash % windowWidthMinutes));
    }

    private List<GroupRow> queryActiveGroups() {
        List<GroupRow> groups = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, timezone FROM groups WHERE removed_at IS NULL"
                             + " AND approval_status = 'approved'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    groups.add(new GroupRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("timezone")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query active groups for digest scheduling", e);
        }
        return groups;
    }

    // Package-private: tests exercise the null branch directly —
    // groups.timezone is NOT NULL in the schema, so tickAt cannot reach it.
    @Nullable ZoneId parseTimezone(UUID groupId, @Nullable String timezone) {
        if (timezone == null) {
            warnBadTimezoneOnce(groupId, "null");
            return null;
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            warnBadTimezoneOnce(groupId, timezone);
            return null;
        }
    }

    private void warnBadTimezoneOnce(UUID groupId, String timezone) {
        if (warnedTimezones.add(groupId + ":" + timezone)) {
            LOG.warnf("Group %s has invalid timezone '%s' — digest slots skipped until it is fixed",
                    groupId, timezone);
        }
    }

    record GroupRow(@NonNull UUID id, @NonNull String timezone) {
    }
}
