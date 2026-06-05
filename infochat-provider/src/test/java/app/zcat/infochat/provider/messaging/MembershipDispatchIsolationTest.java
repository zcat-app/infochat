package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.group.MembershipEventHandler;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-event isolation on the {@code AdapterRegistry}-wired membership
 * dispatch path: when the first of two {@code memberLeft} events fails
 * its transaction, the failure must not escape
 * {@link AdapterRegistry#dispatchMembershipEvent} and the second
 * member's removal must still land. Drives the package-private dispatch
 * method on a hand-constructed registry (no CDI) with a
 * {@link MembershipEventHandler} whose audit writer fails for the first
 * member only and delegates the sibling's row to the real writer.
 */
@QuarkusTest
class MembershipDispatchIsolationTest {

    private static final String TEST_ADAPTER = "inmemory";
    private static final String TEST_UPSTREAM_GROUP_ID = "mdi-test-" + UUID.randomUUID();

    @Inject @SeedDataSource DataSource dataSource;
    @Inject GroupRepository groupRepository;
    @Inject GroupMembershipRepository membershipRepository;
    @Inject AuditLogWriter auditLogWriter;

    private UUID groupId;
    private UUID failingUserId;
    private UUID survivingUserId;
    private String failingContactId;
    private String survivingContactId;

    @BeforeEach
    void setup() throws Exception {
        failingUserId = UUID.randomUUID();
        survivingUserId = UUID.randomUUID();
        failingContactId = "mdi-contact-fail-" + failingUserId;
        survivingContactId = "mdi-contact-ok-" + survivingUserId;

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
            seedUser(conn, failingUserId, failingContactId);
            seedUser(conn, survivingUserId, survivingContactId);
        }
        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(
                TEST_ADAPTER, TEST_UPSTREAM_GROUP_ID);
        groupRepository.clearRemoved(groupId);
        // Both targets MUST be active members: a non-member target would
        // hit the handler's verified-no-op skip and return before ever
        // reaching the failing audit writer, making the test vacuous.
        membershipRepository.addMember(groupId, failingUserId);
        membershipRepository.addMember(groupId, survivingUserId);
    }

    @Test
    void firstEventFailureDoesNotAbortSiblingDispatch() throws Exception {
        MembershipEventHandler failFirstHandler = new MembershipEventHandler(
                dataSource, membershipRepository, groupRepository,
                new TargetedFailingAuditLogWriter(auditLogWriter, failingUserId.toString()));
        AdapterRegistry registry = new AdapterRegistry();
        registry.membershipEventHandler = failFirstHandler;

        // First event's transaction fails — the wired dispatch path must
        // swallow it (per-event isolation), not abort the group update.
        registry.dispatchMembershipEvent(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, failingContactId),
                TEST_ADAPTER);
        registry.dispatchMembershipEvent(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, survivingContactId),
                TEST_ADAPTER);

        // Failed event rolled back whole: member still active, no audit row.
        assertNull(removedAt(failingUserId),
                "failed event's removal must roll back (member stays active)");
        assertFalse(hasMemberLeftAudit(failingUserId),
                "failed event must not leave a MEMBER_LEFT audit row");
        // Sibling event landed whole: removal + audit row.
        assertNotNull(removedAt(survivingUserId),
                "sibling event's removal must still land");
        assertTrue(hasMemberLeftAudit(survivingUserId),
                "sibling event's MEMBER_LEFT audit row must exist");
    }

    private java.sql.Timestamp removedAt(UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM group_membership "
                             + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "membership row should exist for " + userId);
                return rs.getTimestamp("removed_at");
            }
        }
    }

    private boolean hasMemberLeftAudit(UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM audit_log "
                             + "WHERE scope_id = ? AND target_id = ? AND action = ?")) {
            ps.setObject(1, groupId);
            ps.setString(2, userId.toString());
            ps.setString(3, AuditAction.MEMBER_LEFT.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void seedUser(Connection conn, UUID userId, String contactId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, registration_state, is_banned) "
                        + "VALUES (?, ?, ?, 'vouched', false) "
                        + "ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, contactId);
            ps.executeUpdate();
        }
    }
}
