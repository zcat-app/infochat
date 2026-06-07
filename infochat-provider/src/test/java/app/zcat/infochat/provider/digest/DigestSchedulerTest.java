package app.zcat.infochat.provider.digest;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DigestSchedulerTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    DigestScheduler scheduler;

    @Inject
    DigestSlotObserver observer;

    @Inject
    SummaryCacheRepository summaryCacheRepository;

    private CapturingHandler logCapture;
    private org.jboss.logmanager.Logger jbossLogger;
    private java.util.logging.Logger julLogger;

    @BeforeEach
    void setUp() throws Exception {
        observer.clear();
        // Clean only summary_cache; audit_log is append-only (Invariant 10)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM summary_cache")) {
            ps.executeUpdate();
        }
        // Attach to BOTH the jboss-logmanager Logger and the JUL Logger so
        // the scheduler's WARN records are captured regardless of which
        // context resolves the named logger (precedent:
        // InboundRouterContactIdRedactionTest).
        logCapture = new CapturingHandler();
        jbossLogger = LogContext.getLogContext().getLogger(DigestScheduler.class.getName());
        jbossLogger.addHandler(logCapture);
        julLogger = java.util.logging.Logger.getLogger(DigestScheduler.class.getName());
        julLogger.addHandler(logCapture);
    }

    @AfterEach
    void detachLogHandler() {
        jbossLogger.removeHandler(logCapture);
        julLogger.removeHandler(logCapture);
    }

    @Test
    void tick_emitsSlotForGroupWithOpenWindow() throws Exception {
        UUID groupId = insertGroup("UTC");

        // Morning window [07:45, 08:15] UTC. Advance past group's stagger offset.
        int stagger = DigestScheduler.staggerOffset(groupId, 30);
        Instant now = todayAt(7, 45, "UTC").plusSeconds(stagger * 60L + 1);

        awaitDispatches(scheduler.tickAt(now));

        List<DigestSlot> slots = observer.getCaptured().stream()
                .filter(s -> s.groupId().equals(groupId) && "morning".equals(s.slotKind()))
                .toList();
        assertEquals(1, slots.size(), "should emit exactly one morning slot for the group");
    }

    @Test
    void tick_skipsGroupAlreadyFiredInWindow() throws Exception {
        UUID groupId = insertGroup("UTC");
        Instant windowStart = todayAt(7, 45, "UTC");

        // Pre-insert a summary_cache row for this slot
        summaryCacheRepository.insert(groupId, "morning", windowStart,
                1L, 1L, "cached", false,
                Instant.now().plusSeconds(3600));

        // Clock within the morning window, past stagger
        int stagger = DigestScheduler.staggerOffset(groupId, 30);
        Instant now = windowStart.plusSeconds(stagger * 60L + 1);

        awaitDispatches(scheduler.tickAt(now));

        boolean emittedForGroup = observer.getCaptured().stream()
                .anyMatch(s -> s.groupId().equals(groupId) && "morning".equals(s.slotKind()));
        assertFalse(emittedForGroup,
                "should not emit slot when cache row already exists for this group");
    }

    @Test
    void tick_skipsMissedSlotPastWindowEnd() throws Exception {
        UUID groupId = insertGroup("UTC");

        // Clock at 09:00 UTC — well past 08:15 window-end for morning
        Instant now = todayAt(9, 0, "UTC");

        awaitDispatches(scheduler.tickAt(now));

        boolean emittedMorning = observer.getCaptured().stream()
                .anyMatch(s -> s.groupId().equals(groupId) && "morning".equals(s.slotKind()));
        assertFalse(emittedMorning, "missed slot must NOT emit a DigestSlot");

        // Verify audit_log contains DIGEST_SLOT_MISSED for this group
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT target_id FROM audit_log "
                             + "WHERE action = 'DIGEST_SLOT_MISSED' AND target_id = ?")) {
            ps.setString(1, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "missed slot must write DIGEST_SLOT_MISSED audit row");
            }
        }
    }

    @Test
    void tick_respectsPerGroupTimezone() throws Exception {
        // Group A in UTC: morning window [07:45, 08:15] UTC
        UUID groupA = insertGroup("UTC");
        // Group B in America/New_York (UTC-4 in summer):
        // morning [07:45, 08:15] local = [11:45, 12:15] UTC
        UUID groupB = insertGroup("America/New_York");

        // Clock within A's morning window, outside B's morning window
        int staggerA = DigestScheduler.staggerOffset(groupA, 30);
        Instant now = todayAt(7, 45, "UTC").plusSeconds(staggerA * 60L + 1);

        awaitDispatches(scheduler.tickAt(now));

        List<DigestSlot> slots = observer.getCaptured();
        boolean hasGroupA = slots.stream().anyMatch(s ->
                s.groupId().equals(groupA) && "morning".equals(s.slotKind()));
        boolean hasGroupB = slots.stream().anyMatch(s ->
                s.groupId().equals(groupB) && "morning".equals(s.slotKind()));
        assertTrue(hasGroupA, "UTC group should have open morning window");
        assertFalse(hasGroupB, "New York group should NOT have open morning window at this UTC time");
    }

    @Test
    void tick_staggersGroupsAcrossWindow() throws Exception {
        UUID[] groups = new UUID[5];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = insertGroup("UTC");
        }

        // Verify stagger offsets are deterministically spread
        Set<Integer> offsets = new HashSet<>();
        for (UUID gid : groups) {
            offsets.add(DigestScheduler.staggerOffset(gid, 30));
        }
        assertTrue(offsets.size() >= 2,
                "stagger offsets must spread groups across the window; got " + offsets);

        // Verify determinism: same group always gets the same offset
        for (UUID gid : groups) {
            assertEquals(
                    DigestScheduler.staggerOffset(gid, 30),
                    DigestScheduler.staggerOffset(gid, 30),
                    "stagger offset must be deterministic for the same group");
        }

        // Tick near end of window — all groups should have fired
        Instant now = todayAt(8, 14, "UTC");
        awaitDispatches(scheduler.tickAt(now));

        Set<UUID> groupSet = Set.of(groups);
        long morningSlots = observer.getCaptured().stream()
                .filter(s -> groupSet.contains(s.groupId()) && "morning".equals(s.slotKind()))
                .count();
        assertEquals(5, morningSlots, "all 5 groups should emit at end of window");
    }

    @Test
    void tick_skipsRemovedGroups() throws Exception {
        UUID groupId = insertGroup("UTC");

        // Mark the group as removed
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE groups SET removed_at = now() WHERE id = ?")) {
            ps.setObject(1, groupId);
            ps.executeUpdate();
        }

        int stagger = DigestScheduler.staggerOffset(groupId, 30);
        Instant now = todayAt(7, 45, "UTC").plusSeconds(stagger * 60L + 1);

        awaitDispatches(scheduler.tickAt(now));

        boolean emittedForGroup = observer.getCaptured().stream()
                .anyMatch(s -> s.groupId().equals(groupId));
        assertFalse(emittedForGroup, "removed group must not emit a DigestSlot");
    }

    @Test
    void tick_unparseableTimezone_warnsOnceAndSkipsGroup() throws Exception {
        UUID groupId = insertGroup("Not/AZone");

        Instant now = todayAt(8, 0, "UTC");
        awaitDispatches(scheduler.tickAt(now));
        awaitDispatches(scheduler.tickAt(now.plusSeconds(60)));

        boolean emitted = observer.getCaptured().stream()
                .anyMatch(s -> s.groupId().equals(groupId));
        assertFalse(emitted, "group with unparseable timezone must not emit slots");

        assertEquals(1, logCapture.warnCountMentioning(groupId.toString()),
                "unparseable timezone must WARN exactly once, not once per tick");
    }

    @Test
    void parseTimezone_nullTimezone_returnsNullAndWarnsOnce() {
        UUID groupId = UUID.randomUUID();

        assertNull(scheduler.parseTimezone(groupId, null));
        assertNull(scheduler.parseTimezone(groupId, null));

        assertEquals(1, logCapture.warnCountMentioning(groupId.toString()),
                "null timezone must WARN exactly once");
    }

    @Test
    void recordMissedSlot_rollsBackAuditRowWhenSentinelInsertFails() throws Exception {
        UUID groupId = insertGroup("UTC");
        Instant windowStart = todayAt(7, 45, "UTC");
        Instant windowEnd = todayAt(8, 15, "UTC");

        // A pre-existing row for the slot (a concurrent tick won the race):
        // the unique index on (group_id, slot_kind, slot_fired_at) makes the
        // sentinel INSERT inside recordMissedSlot fail.
        summaryCacheRepository.insert(groupId, "morning", windowStart,
                0L, 0L, "", true, windowEnd);

        assertThrows(SQLException.class,
                () -> scheduler.recordMissedSlot(groupId, "morning", windowStart, windowEnd));

        // Audit-and-sentinel span one transaction: the failed sentinel insert
        // must roll back the audit row, otherwise the next tick re-detects
        // the miss and writes a duplicate audit row.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log "
                             + "WHERE action = 'DIGEST_SLOT_MISSED' AND target_id = ?")) {
            ps.setString(1, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1),
                        "audit row must roll back when the sentinel insert fails");
            }
        }
    }

    @Test
    @Timeout(30)
    void tick_slowSlotConsumerDoesNotDelayOtherGroupsEmission() throws Exception {
        UUID groupA = insertGroup("UTC");
        UUID groupB = insertGroup("UTC");

        // Whichever of the two groups is dispatched first blocks inside its
        // consumer. Under synchronous dispatch the block would happen on the
        // tick thread, so the second group's slot could never be emitted
        // within this tick — the await below would time out (and the old
        // code would hang inside tickAt itself, tripping @Timeout).
        observer.gateFirstSlotOf(Set.of(groupA, groupB));
        try {
            // 08:14 UTC: end of the morning window, past every stagger offset
            Instant now = todayAt(8, 14, "UTC");
            List<Future<?>> dispatches = scheduler.tickAt(now);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (capturedMorningGroups(groupA, groupB).size() < 2
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(Set.of(groupA, groupB), capturedMorningGroups(groupA, groupB),
                    "the second group's slot must be emitted while the first group's"
                            + " consumer is still blocked");

            observer.releaseGate();
            awaitDispatches(dispatches);
        } finally {
            observer.releaseGate();
        }
    }

    private Set<UUID> capturedMorningGroups(UUID... groups) {
        Set<UUID> filter = Set.of(groups);
        return observer.getCaptured().stream()
                .filter(s -> filter.contains(s.groupId()) && "morning".equals(s.slotKind()))
                .map(DigestSlot::groupId)
                .collect(Collectors.toSet());
    }

    private void awaitDispatches(List<Future<?>> dispatches) throws Exception {
        for (Future<?> dispatch : dispatches) {
            dispatch.get(10, TimeUnit.SECONDS);
        }
    }

    private UUID insertGroup(String timezone) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name, timezone, approval_status) "
                             + "VALUES ('inmemory', ?, 'test-digest-group', ?, 'approved') "
                             + "RETURNING id")) {
            ps.setString(1, "digest-sched-" + UUID.randomUUID());
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

    /**
     * JUL capturing handler — SLF4J in Quarkus routes through
     * jboss-logmanager, which IS a JUL implementation, so attaching to
     * the {@link DigestScheduler} JUL logger captures the records the
     * production code emits.
     */
    private static final class CapturingHandler extends Handler {
        // addIfAbsent dedupes the same LogRecord instance delivered twice
        // when the JUL logger and the LogContext logger resolve to the
        // same object (both attach calls then hit one logger).
        private final CopyOnWriteArrayList<LogRecord> records = new CopyOnWriteArrayList<>();
        private final SimpleFormatter formatter = new SimpleFormatter();

        CapturingHandler() {
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            records.addIfAbsent(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        long warnCountMentioning(String needle) {
            return records.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .filter(r -> text(r).contains(needle))
                    .count();
        }

        private String text(LogRecord r) {
            // Append raw parameters too — the formatter may not render
            // printf-style substitution for jboss-logging records.
            StringBuilder sb = new StringBuilder(formatter.format(r));
            if (r.getParameters() != null) {
                for (Object p : r.getParameters()) {
                    sb.append(" param=").append(p);
                }
            }
            return sb.toString();
        }
    }
}
