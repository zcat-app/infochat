package app.zcat.infochat.provider.group;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts the
 * probation-eligibility gate ({@link GroupAutoPromoteService#tryAutoPromote}
 * via {@code isEligible}) decides against that instant — not the wall-clock
 * run date. Deterministic complement to
 * {@link GroupAutoPromoteServiceTest#tryAutoPromote_skipsProbationUser},
 * which seeds {@code probation_until} via {@code Instant.now().plus(24h)}.
 * (M1-447)
 *
 * <p>The discriminating case is the still-in-probation user: its
 * {@code probation_until} is a FIXED instant one minute AFTER
 * {@code PINNED_NOW}. Under the injected fixed clock it is in the future
 * (still in probation → skipped); under the real wall clock it would be
 * far in the past (eligible → promoted). Asserting "skipped" therefore
 * proves the gate reads the injected Clock, not {@code Instant.now()}.
 */
@QuarkusTest
class GroupAutoPromoteServiceClockIT {

    private static final String TEST_ADAPTER = "inmemory";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-25T09:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    GroupRepository groupRepository;

    @Inject
    GroupAutoPromoteService service;

    @BeforeEach
    void pinClock() {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
    }

    @Test
    void probationGate_decidesOnInjectedClock_skipsUserStillInProbation() throws Exception {
        UUID userId = UUID.randomUUID();
        // probation_until one minute AFTER the pinned instant: still in
        // probation under the fixed clock, but in the past under the real clock.
        seedUser(userId, "autopromote-clock-inprobation-" + userId, PINNED_NOW.plusSeconds(60));
        UUID groupId = freshGroup();

        boolean result = service.tryAutoPromote(groupId, userId, TEST_ADAPTER,
            "autopromote-clock-inprobation-" + userId);

        assertFalse(result,
            "a user whose probation_until is after the injected now must be skipped, "
                + "even though it is in the past on the wall clock");
        assertFalse(isGroupAdmin(groupId, userId));
    }

    @Test
    void probationGate_decidesOnInjectedClock_promotesUserPastProbation() throws Exception {
        UUID userId = UUID.randomUUID();
        // probation_until one minute BEFORE the pinned instant: probation
        // elapsed under the fixed clock → eligible.
        seedUser(userId, "autopromote-clock-pastprobation-" + userId, PINNED_NOW.minusSeconds(60));
        UUID groupId = freshGroup();

        boolean result = service.tryAutoPromote(groupId, userId, TEST_ADAPTER,
            "autopromote-clock-pastprobation-" + userId);

        assertTrue(result,
            "a user whose probation_until is before the injected now is eligible");
        assertTrue(isGroupAdmin(groupId, userId));
    }

    private UUID freshGroup() {
        // A unique upstream id per call so each test gets a group with no
        // active admin (the precondition for the auto-promote path).
        return groupRepository.findOrCreateByAdapterAndUpstreamId(
            TEST_ADAPTER, "autopromote-clock-test-" + UUID.randomUUID());
    }

    private void seedUser(UUID userId, String contactId, Instant probationUntil) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (id, adapter, contact_id, registration_state, "
                     + "is_banned, probation_until) "
                     + "VALUES (?, ?, ?, 'vouched', FALSE, ?) "
                     + "ON CONFLICT (id) DO UPDATE SET probation_until = EXCLUDED.probation_until")) {
            ps.setObject(1, userId);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, contactId);
            ps.setTimestamp(4, Timestamp.from(probationUntil));
            ps.executeUpdate();
        }
    }

    private boolean isGroupAdmin(UUID groupId, UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT is_group_admin FROM group_membership "
                     + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_group_admin");
            }
        }
    }
}
