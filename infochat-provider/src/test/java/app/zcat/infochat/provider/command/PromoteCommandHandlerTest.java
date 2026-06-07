package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.InboundContext;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PromoteCommandHandlerTest {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-079c-promote-";
    private static final String UPSTREAM_GROUP_ID = PREFIX + "group-" + UUID.randomUUID();

    @Inject PromoteCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject GroupRepository groupRepository;

    private UUID groupId;

    private String adminContactId;
    private String nonAdminContactId;
    private String targetContactId;
    private String bannedTargetContactId;
    private String noMembershipTargetContactId;
    private UUID targetUserId;
    private UUID existingAdminMemberId;

    @BeforeEach
    void setup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);

        adminContactId = PREFIX + "admin-" + UUID.randomUUID();
        nonAdminContactId = PREFIX + "nonadmin-" + UUID.randomUUID();
        targetContactId = PREFIX + "target-" + UUID.randomUUID();
        bannedTargetContactId = PREFIX + "banned-" + UUID.randomUUID();
        noMembershipTargetContactId = PREFIX + "nomember-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            // Clean up
            cleanTestData(conn);

            // Guardian admin to keep last-admin trigger happy
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-" + PREFIX + "permanent");

            // Seed admin caller
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, adminContactId);

            // Seed non-admin caller
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, FALSE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = FALSE",
                    ADAPTER, nonAdminContactId);

            // Seed valid target
            targetUserId = seedUserReturningId(conn, targetContactId, false);

            // Seed banned target
            seedUserReturningId(conn, bannedTargetContactId, true);

            // Seed no-membership target (exists as user, not in group)
            seedUserReturningId(conn, noMembershipTargetContactId, false);
        }

        // Create group
        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(ADAPTER, UPSTREAM_GROUP_ID);

        // Seed group memberships
        try (Connection conn = dataSource.getConnection()) {
            // Target is a member of the group
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id) VALUES (?, ?) "
                            + "ON CONFLICT DO NOTHING",
                    groupId, targetUserId);

            // Existing admin member (will be demoted on promote)
            existingAdminMemberId = seedUserReturningId(conn,
                    PREFIX + "existing-admin-" + UUID.randomUUID(), false);
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id, is_group_admin) VALUES (?, ?, true) "
                            + "ON CONFLICT DO NOTHING",
                    groupId, existingAdminMemberId);

            // Banned target is also a member (but banned as user)
            UUID bannedUserId = getUserId(conn, bannedTargetContactId);
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id) VALUES (?, ?) "
                            + "ON CONFLICT DO NOTHING",
                    groupId, bannedUserId);
        }
    }

    @Test
    void promote_succeedsForBotAdminWithValidTarget() {
        inboundContext.setSenderContactId(adminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/promote " + targetContactId);

        assertTrue(result.text().contains("Promoted"));
        assertTrue(isGroupAdmin(groupId, targetUserId));
    }

    @Test
    void promote_rejectsNonBotAdmin() {
        inboundContext.setSenderContactId(nonAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/promote " + targetContactId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), result.text());
    }

    @Test
    void promote_rejectsBannedTarget() {
        inboundContext.setSenderContactId(adminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/promote " + bannedTargetContactId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_PROMOTE_TARGET_BANNED), result.text());
    }

    @Test
    void promote_rejectsTargetNotInGroup() {
        inboundContext.setSenderContactId(adminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/promote " + noMembershipTargetContactId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_PROMOTE_TARGET_NOT_IN_GROUP), result.text());
    }

    @Test
    void promote_bannedTargetRefusalLeavesPromoteIntentAuditRow() throws Exception {
        // Spec §Authorization model step 8 precedes step 9: an admin's
        // probe that fails an execution-semantics check (here: banned
        // target) must still leave a surviving intent row — a distinct
        // verb from the PROMOTE_GROUP_ADMIN effect row.
        inboundContext.setSenderContactId(adminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/promote " + bannedTargetContactId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_PROMOTE_TARGET_BANNED), result.text());
        assertEquals(1L, countAuditRowsByTargetContact("PROMOTE_GROUP_ADMIN_INTENT",
                        bannedTargetContactId),
                "the admin's refused probe must leave exactly one surviving "
                        + "PROMOTE_GROUP_ADMIN_INTENT row");
        assertEquals(0L, countAuditRowsByTargetContact("PROMOTE_GROUP_ADMIN",
                        bannedTargetContactId),
                "the refused probe must not write a PROMOTE_GROUP_ADMIN effect row");
    }

    @Test
    void promote_demotesExistingAdminInSameTransaction() {
        inboundContext.setSenderContactId(adminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        // Before: existingAdminMemberId is admin
        assertTrue(isGroupAdmin(groupId, existingAdminMemberId));

        handler.handle(scope, "/promote " + targetContactId);

        // After: target is admin, existing is not
        assertTrue(isGroupAdmin(groupId, targetUserId));
        assertFalse(isGroupAdmin(groupId, existingAdminMemberId));
    }

    private long countAuditRowsByTargetContact(String action, String targetContact)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_contact_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetContact);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
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

    private UUID seedUserReturningId(Connection conn, String contactId,
                                     boolean banned) throws Exception {
        UUID userId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, is_admin, is_banned, registration_state) "
                        + "VALUES (?, ?, ?, FALSE, ?, 'vouched') "
                        + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                        + "SET is_banned = EXCLUDED.is_banned RETURNING id")) {
            ps.setObject(1, userId);
            ps.setString(2, ADAPTER);
            ps.setString(3, contactId);
            ps.setBoolean(4, banned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID getUserId(Connection conn, String contactId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void cleanTestData(Connection conn) throws Exception {
        // Clean memberships for any groups with our upstream id
        exec(conn,
                "DELETE FROM group_membership WHERE group_id IN "
                        + "(SELECT id FROM groups WHERE upstream_group_id = ?)",
                UPSTREAM_GROUP_ID);
        exec(conn,
                "DELETE FROM groups WHERE upstream_group_id = ?",
                UPSTREAM_GROUP_ID);
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
