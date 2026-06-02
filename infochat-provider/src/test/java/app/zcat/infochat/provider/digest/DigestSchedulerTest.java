package app.zcat.infochat.provider.digest;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DigestSchedulerTest {

    @Inject
    DataSource dataSource;

    @Inject
    DigestScheduler scheduler;

    @Inject
    DigestSlotObserver observer;

    @Inject
    SummaryCacheRepository summaryCacheRepository;

    @BeforeEach
    void setUp() throws Exception {
        observer.clear();
        // Clean only summary_cache; audit_log is append-only (Invariant 10)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM summary_cache")) {
            ps.executeUpdate();
        }
    }

    @Test
    void tick_emitsSlotForGroupWithOpenWindow() throws Exception {
        UUID groupId = insertGroup("UTC");

        // Morning window [07:45, 08:15] UTC. Advance past group's stagger offset.
        int stagger = DigestScheduler.staggerOffset(groupId, 30);
        Instant now = todayAt(7, 45, "UTC").plusSeconds(stagger * 60L + 1);

        scheduler.tickAt(now);

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

        scheduler.tickAt(now);

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

        scheduler.tickAt(now);

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

        scheduler.tickAt(now);

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
        scheduler.tickAt(now);

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

        scheduler.tickAt(now);

        boolean emittedForGroup = observer.getCaptured().stream()
                .anyMatch(s -> s.groupId().equals(groupId));
        assertFalse(emittedForGroup, "removed group must not emit a DigestSlot");
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
}
