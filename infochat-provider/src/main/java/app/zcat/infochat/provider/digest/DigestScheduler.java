package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * next tick. Windows that ended before the group's latest approval
 * are skipped without any record (skip-not-catch-up per
 * docs/spec/commands.md §Periodic group digests).</p>
 *
 * <p>Slot events are dispatched on virtual threads: one group's slow
 * consumer cannot delay later groups' slot emissions in the same
 * tick.</p>
 */
@ApplicationScoped
public class DigestScheduler {

    private static final Logger LOG = Logger.getLogger(DigestScheduler.class);

    private static final String SLOT_MORNING = "morning";
    private static final String SLOT_EVENING = "evening";

    // WARN once per (group, offending value), not per tick — the scheduler
    // fires every minute and an unfixed timezone would flood the log.
    private final Set<String> warnedTimezones = ConcurrentHashMap.newKeySet();

    // Slot dispatch runs on a virtual thread per slot: fire() delivers
    // synchronously to observers, so a slow consumer (an LLM digest render)
    // dispatched on the tick thread would delay every later group's slot in
    // the same tick. A tick that re-fires a slot whose previous dispatch is
    // still rendering (no cache row yet) is absorbed by DigestWorker's
    // in-flight guard, and the summary_cache unique index backstops it.
    private final ExecutorService slotDispatchExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

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

    @PreDestroy
    void shutdownSlotDispatch() {
        slotDispatchExecutor.shutdown();
    }

    // Package-private: called directly by tests with a controlled instant.
    // Returns this tick's in-flight dispatch futures so tests can await
    // delivery deterministically; the production tick() ignores them.
    List<Future<?>> tickAt(Instant now) {
        List<Future<?>> dispatches = new ArrayList<>();
        List<GroupRow> groups = queryActiveGroups();
        for (GroupRow group : groups) {
            ZoneId tz = parseTimezone(group.id, group.timezone);
            if (tz == null) continue;
            Future<?> morning = processSlot(now, group, tz, SLOT_MORNING, morningSlotHour);
            if (morning != null) dispatches.add(morning);
            Future<?> evening = processSlot(now, group, tz, SLOT_EVENING, eveningSlotHour);
            if (evening != null) dispatches.add(evening);
        }
        return dispatches;
    }

    private @Nullable Future<?> processSlot(Instant now, GroupRow group, ZoneId tz,
                                            String slotKind, int centerHour) {
        LocalDate today = now.atZone(tz).toLocalDate();
        ZonedDateTime center = ZonedDateTime.of(today, LocalTime.of(centerHour, 0), tz);
        Instant windowStart = center.minusMinutes(windowWidthMinutes / 2).toInstant();
        Instant windowEnd = center.plusMinutes((windowWidthMinutes + 1) / 2).toInstant();

        int staggerOffsetMinutes = staggerOffset(group.id, windowWidthMinutes);
        Instant effectiveFireTime = windowStart.plus(Duration.ofMinutes(staggerOffsetMinutes));

        try {
            if (now.isBefore(windowStart)) {
                return null;
            }

            boolean alreadyFired = summaryCacheRepository.existsByGroupAndSlot(
                    group.id, slotKind, windowStart);
            if (alreadyFired) {
                return null;
            }

            if (!now.isBefore(windowEnd)) {
                // Skip-not-catch-up (commands.md §Periodic group digests): a
                // window that ended before the group's latest approval elapsed
                // while the group was not yet eligible — it is neither caught
                // up nor recorded as missed. Approval time comes from the
                // APPROVE_GROUP audit row, which ApproveGroupCommandHandler
                // writes in the same transaction as the approval_status flip,
                // so 'approved' is visible if and only if the row is.
                Instant approvedAt = latestApprovalTime(group.id);
                if (approvedAt != null && !windowEnd.isAfter(approvedAt)) {
                    return null;
                }
                // Pause carve-out, symmetric to the approval carve-out above: a
                // group currently enabled whose most-recent re-enable happened
                // after this window ended was paused through the window via
                // /digest off, so the slot is neither caught up nor recorded as
                // missed — the absent digest was intentional, not a failure. The
                // boundary comes from the DIGEST_ENABLE audit rows M1-227 writes.
                // A currently-disabled group never reaches this branch: it is
                // excluded from queryActiveGroups by the digest_enabled gate.
                Instant enabledAt = latestDigestEnableTime(group.id);
                if (enabledAt != null && enabledAt.isAfter(windowEnd)) {
                    return null;
                }
                // Past window-end with no cache row: missed slot
                recordMissedSlot(group.id, slotKind, windowStart, windowEnd);
                return null;
            }

            if (!now.isBefore(effectiveFireTime)) {
                // Within window and past stagger time: emit
                DigestSlot slot = new DigestSlot(
                        group.id, group.timezone, slotKind, windowStart, windowEnd);
                return slotDispatchExecutor.submit(() -> fireSlot(slot));
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Digest scheduler failed for group " + group.id + " slot " + slotKind, e);
        }
    }

    // Runs on a dispatch virtual thread. fire() delivers synchronously to
    // observers on THIS thread; an observer failure would otherwise vanish
    // inside the executor's unread Future, so log it here.
    private void fireSlot(DigestSlot slot) {
        try {
            digestSlotEvent.fire(slot);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Digest slot dispatch failed for group %s slot %s window %s",
                    slot.groupId(), slot.slotKind(), slot.windowStart());
        }
    }

    /**
     * Latest APPROVE_GROUP audit timestamp for the group, or {@code null}
     * when no approval was ever recorded — groups grandfathered by V26
     * carry no audit row and are treated as approved-since-forever, which
     * preserves their existing missed-slot behavior. Reads
     * {@code audit_log_view} because the provider role has INSERT-only on
     * {@code audit_log} itself; {@code created_at}/{@code action}/
     * {@code target_id} pass through the view unredacted, and audit_log is
     * append-only (Invariant 10) so the row cannot disappear later.
     */
    private @Nullable Instant latestApprovalTime(UUID groupId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT max(created_at) FROM audit_log_view"
                             + " WHERE action = 'APPROVE_GROUP'"
                             + " AND target_kind = 'group' AND target_id = ?")) {
            ps.setString(1, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp approvedAt = rs.getTimestamp(1);
                return approvedAt == null ? null : approvedAt.toInstant();
            }
        }
    }

    /**
     * Latest DIGEST_ENABLE audit timestamp for the group, or {@code null}
     * when the group has no DIGEST_ENABLE row — never toggled, or digest
     * on by default. Mirrors {@link #latestApprovalTime}: reads
     * {@code audit_log_view} because the provider role has INSERT-only on
     * {@code audit_log} itself, and the DIGEST_ENABLE rows the /digest
     * toggle writes are append-only (Invariant 10) so the re-enable
     * boundary cannot disappear later.
     */
    @Nullable Instant latestDigestEnableTime(UUID groupId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT max(created_at) FROM audit_log_view"
                             + " WHERE action = 'DIGEST_ENABLE'"
                             + " AND target_kind = 'group' AND target_id = ?")) {
            ps.setString(1, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp enabledAt = rs.getTimestamp(1);
                return enabledAt == null ? null : enabledAt.toInstant();
            }
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
    static int staggerOffset(UUID groupId, int windowWidthMinutes) {
        long hash = groupId.getMostSignificantBits();
        return (int) (Math.abs(hash % windowWidthMinutes));
    }

    private List<GroupRow> queryActiveGroups() {
        List<GroupRow> groups = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, timezone FROM groups WHERE removed_at IS NULL"
                             + " AND approval_status = 'approved' AND digest_enabled")) {
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

    record GroupRow(UUID id, String timezone) {
    }
}
