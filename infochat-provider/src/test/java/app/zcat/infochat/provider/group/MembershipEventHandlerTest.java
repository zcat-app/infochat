package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.messaging.MembershipEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MembershipEventHandlerTest {

    private static final String TEST_ADAPTER = "inmemory";
    private static final String TEST_UPSTREAM_GROUP_ID = "meh-test-" + UUID.randomUUID();

    @Inject DataSource dataSource;
    @Inject GroupRepository groupRepository;
    @Inject GroupMembershipRepository membershipRepository;
    @Inject MembershipEventHandler handler;

    private UUID groupId;
    private UUID userId;
    private String contactId;

    @BeforeEach
    void setup() throws Exception {
        userId = UUID.randomUUID();
        contactId = "meh-contact-" + userId;

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM group_membership WHERE group_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id = ?)")) {
                ps.setString(1, TEST_UPSTREAM_GROUP_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM groups WHERE upstream_group_id = ?")) {
                ps.setString(1, TEST_UPSTREAM_GROUP_ID);
                ps.executeUpdate();
            }
            cleanUser(conn, userId);
            seedUser(conn, userId, contactId);
        }
        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(
                TEST_ADAPTER, TEST_UPSTREAM_GROUP_ID);
        groupRepository.clearRemoved(groupId);
    }

    @Test
    void userLeft_marksGroupMemberRemoved() throws Exception {
        membershipRepository.addMember(groupId, userId);

        handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                TEST_ADAPTER);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at, is_group_admin FROM group_membership "
                             + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "membership row should exist");
                assertNotNull(rs.getTimestamp("removed_at"),
                        "removed_at should be set");
                assertFalse(rs.getBoolean("is_group_admin"),
                        "is_group_admin should be false");
            }
        }
        assertTrue(hasAuditEntry(groupId, userId.toString(), AuditAction.MEMBER_LEFT),
                "MEMBER_LEFT audit row should exist");
    }

    @Test
    void userLeft_auditRecordsWasGroupAdmin() throws Exception {
        membershipRepository.addMember(groupId, userId);
        membershipRepository.promoteToAdmin(groupId, userId);

        handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                TEST_ADAPTER);

        String details = auditDetails(groupId, userId.toString(), AuditAction.MEMBER_LEFT);
        assertNotNull(details, "audit details should exist");
        // PostgreSQL jsonb normalizes whitespace (space after colon)
        assertTrue(details.contains("\"was_group_admin\": true"),
                "audit should record was_group_admin=true");
    }

    @Test
    void botRemoved_marksGroupRemoved() throws Exception {
        handler.handle(
                new MembershipEvent.BotRemoved(TEST_UPSTREAM_GROUP_ID),
                TEST_ADAPTER);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM groups WHERE id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "group row should exist");
                assertNotNull(rs.getTimestamp("removed_at"),
                        "removed_at should be set");
            }
        }
        assertTrue(hasAuditEntry(groupId, groupId.toString(), AuditAction.BOT_REMOVED),
                "BOT_REMOVED audit row should exist");
    }

    @Test
    void userLeft_unknownGroup_doesNotThrow() {
        handler.handle(
                new MembershipEvent.UserLeft("nonexistent-group", contactId),
                TEST_ADAPTER);
    }

    @Test
    void userLeft_unknownUser_doesNotThrow() {
        handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, "nonexistent-contact"),
                TEST_ADAPTER);
    }

    private boolean hasAuditEntry(UUID scopeId, String targetId, AuditAction action) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM audit_log "
                             + "WHERE scope_id = ? AND target_id = ? AND action = ?")) {
            ps.setObject(1, scopeId);
            ps.setString(2, targetId);
            ps.setString(3, action.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String auditDetails(UUID scopeId, String targetId, AuditAction action) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT details_json FROM audit_log "
                             + "WHERE scope_id = ? AND target_id = ? AND action = ?")) {
            ps.setObject(1, scopeId);
            ps.setString(2, targetId);
            ps.setString(3, action.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("details_json") : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void seedUser(Connection conn, UUID uid, String cid) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, registration_state, is_banned) "
                        + "VALUES (?, ?, ?, 'vouched', false) "
                        + "ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, uid);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, cid);
            ps.executeUpdate();
        }
    }

    private void cleanUser(Connection conn, UUID uid) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM users WHERE id = ?")) {
            ps.setObject(1, uid);
            ps.executeUpdate();
        }
    }
}
