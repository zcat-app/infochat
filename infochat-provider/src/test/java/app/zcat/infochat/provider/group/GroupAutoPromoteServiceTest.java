package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GroupAutoPromoteServiceTest {

    private static final String TEST_ADAPTER = "inmemory";
    private static final String TEST_UPSTREAM_ID = "autopromote-test-" + UUID.randomUUID();

    @Inject @SeedDataSource DataSource dataSource;
    @Inject GroupRepository groupRepository;
    @Inject GroupMembershipRepository membershipRepository;
    @Inject GroupAutoPromoteService service;

    private UUID groupId;
    private UUID eligibleUserId;
    private UUID bannedUserId;
    private UUID probationUserId;
    private UUID secondUserId;

    @BeforeEach
    void setup() throws Exception {
        eligibleUserId = UUID.randomUUID();
        bannedUserId = UUID.randomUUID();
        probationUserId = UUID.randomUUID();
        secondUserId = UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            // Clean up prior test rows
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM group_membership WHERE group_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id = ?)")) {
                ps.setString(1, TEST_UPSTREAM_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM groups WHERE upstream_group_id = ?")) {
                ps.setString(1, TEST_UPSTREAM_ID);
                ps.executeUpdate();
            }
            // Clean users by known ids
            cleanUser(conn, eligibleUserId);
            cleanUser(conn, bannedUserId);
            cleanUser(conn, probationUserId);
            cleanUser(conn, secondUserId);

            // Seed users
            seedUser(conn, eligibleUserId, "autopromote-eligible-" + eligibleUserId,
                    false, null);
            seedUser(conn, bannedUserId, "autopromote-banned-" + bannedUserId,
                    true, null);
            seedUser(conn, probationUserId, "autopromote-probation-" + probationUserId,
                    false, Instant.now().plus(24, ChronoUnit.HOURS));
            seedUser(conn, secondUserId, "autopromote-second-" + secondUserId,
                    false, null);
        }
        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(TEST_ADAPTER, TEST_UPSTREAM_ID);
    }

    @Test
    void tryAutoPromote_succeedsWhenNoAdminExists() {
        boolean result = service.tryAutoPromote(groupId, eligibleUserId, TEST_ADAPTER, "autopromote-eligible-" + eligibleUserId);

        assertTrue(result);
        assertTrue(isGroupAdmin(groupId, eligibleUserId));
    }

    @Test
    void tryAutoPromote_returnsFalseWhenAdminSlotOccupied() {
        // First user becomes admin
        service.tryAutoPromote(groupId, eligibleUserId, TEST_ADAPTER, "autopromote-eligible-" + eligibleUserId);

        // Second user cannot
        boolean result = service.tryAutoPromote(groupId, secondUserId, TEST_ADAPTER, "autopromote-second-" + secondUserId);

        assertFalse(result);
        assertFalse(isGroupAdmin(groupId, secondUserId));
    }

    @Test
    void tryAutoPromote_skipsBannedUser() {
        boolean result = service.tryAutoPromote(groupId, bannedUserId, TEST_ADAPTER, "autopromote-banned-" + bannedUserId);

        assertFalse(result);
        assertFalse(isGroupAdmin(groupId, bannedUserId));
    }

    @Test
    void tryAutoPromote_skipsProbationUser() {
        boolean result = service.tryAutoPromote(groupId, probationUserId, TEST_ADAPTER, "autopromote-probation-" + probationUserId);

        assertFalse(result);
        assertFalse(isGroupAdmin(groupId, probationUserId));
    }

    @Test
    void tryAutoPromote_rePromotesExistingNonAdminMember() {
        // Seed an existing membership row with is_group_admin=false
        membershipRepository.addMember(groupId, eligibleUserId);

        boolean result = service.tryAutoPromote(groupId, eligibleUserId, TEST_ADAPTER,
                "autopromote-eligible-" + eligibleUserId);

        assertTrue(result);
        assertTrue(isGroupAdmin(groupId, eligibleUserId));
        assertTrue(hasAuditEntry(groupId, eligibleUserId, AuditAction.PROMOTE_GROUP_ADMIN));
    }

    @Test
    void tryAutoPromote_returnsFalseForRemovedMember() {
        // Seed membership then mark removed
        membershipRepository.addMember(groupId, eligibleUserId);
        membershipRepository.markMemberRemoved(groupId, eligibleUserId);

        boolean result = service.tryAutoPromote(groupId, eligibleUserId, TEST_ADAPTER,
                "autopromote-eligible-" + eligibleUserId);

        assertFalse(result);
    }

    private boolean hasAuditEntry(UUID gId, UUID uId, AuditAction action) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM audit_log "
                             + "WHERE scope_id = ? AND target_id = ? AND action = ?")) {
            ps.setObject(1, gId);
            ps.setString(2, uId.toString());
            ps.setString(3, action.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isGroupAdmin(UUID gId, UUID uId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_group_admin FROM group_membership "
                             + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL")) {
            ps.setObject(1, gId);
            ps.setObject(2, uId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_group_admin");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void seedUser(Connection conn, UUID userId, String contactId,
                          boolean banned, Instant probationUntil) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, registration_state, is_banned, probation_until) "
                        + "VALUES (?, ?, ?, 'vouched', ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET is_banned = EXCLUDED.is_banned, "
                        + "probation_until = EXCLUDED.probation_until")) {
            ps.setObject(1, userId);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, contactId);
            ps.setBoolean(4, banned);
            ps.setTimestamp(5, probationUntil == null ? null : Timestamp.from(probationUntil));
            ps.executeUpdate();
        }
    }

    private void cleanUser(Connection conn, UUID userId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM users WHERE id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        }
    }
}
