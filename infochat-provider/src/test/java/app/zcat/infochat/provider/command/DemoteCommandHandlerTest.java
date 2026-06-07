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
class DemoteCommandHandlerTest {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-079c-demote-";
    private static final String UPSTREAM_GROUP_ID = PREFIX + "group-" + UUID.randomUUID();

    @Inject DemoteCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject GroupRepository groupRepository;

    private UUID groupId;

    private String adminContactId;
    private String nonAdminContactId;
    private String groupAdminContactId;
    private String nonGroupAdminContactId;
    private UUID groupAdminUserId;
    private UUID nonGroupAdminUserId;

    @BeforeEach
    void setup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);

        adminContactId = PREFIX + "admin-" + UUID.randomUUID();
        nonAdminContactId = PREFIX + "nonadmin-" + UUID.randomUUID();
        groupAdminContactId = PREFIX + "gadmin-" + UUID.randomUUID();
        nonGroupAdminContactId = PREFIX + "nogadmin-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            cleanTestData(conn);

            // Guardian admin
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-" + PREFIX + "permanent");

            // Bot admin caller
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, adminContactId);

            // Non-admin caller
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, FALSE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = FALSE",
                    ADAPTER, nonAdminContactId);

            // Target who IS group admin
            groupAdminUserId = seedUserReturningId(conn, groupAdminContactId);
            // Target who is NOT group admin
            nonGroupAdminUserId = seedUserReturningId(conn, nonGroupAdminContactId);
        }

        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(ADAPTER, UPSTREAM_GROUP_ID);

        try (Connection conn = dataSource.getConnection()) {
            // Group admin membership
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id, is_group_admin) VALUES (?, ?, true) "
                            + "ON CONFLICT DO NOTHING",
                    groupId, groupAdminUserId);
            // Non-admin membership
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id) VALUES (?, ?) "
                            + "ON CONFLICT DO NOTHING",
                    groupId, nonGroupAdminUserId);
        }
    }

    @Test
    void demote_succeedsForBotAdminWithValidTarget() {
        inboundContext.setSenderContactId(adminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        assertTrue(isGroupAdmin(groupId, groupAdminUserId));

        OutboundMessage result = handler.handle(scope, "/demote " + groupAdminContactId);

        assertTrue(result.text().contains("Demoted"));
        assertFalse(isGroupAdmin(groupId, groupAdminUserId));
    }

    @Test
    void demote_rejectsNonBotAdmin() {
        inboundContext.setSenderContactId(nonAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/demote " + groupAdminContactId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), result.text());
    }

    @Test
    void demote_rejectsTargetNotCurrentAdmin() {
        inboundContext.setSenderContactId(adminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/demote " + nonGroupAdminContactId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_DEMOTE_TARGET_NOT_ADMIN), result.text());
    }

    @Test
    void demote_targetNotAdminRefusalLeavesDemoteIntentAuditRow() throws Exception {
        // Spec §Authorization model step 8 precedes step 9: an admin's
        // probe that fails an execution-semantics check (here: target
        // is not the current group admin) must still leave a surviving
        // intent row — a distinct verb from the DEMOTE_GROUP_ADMIN
        // effect row.
        inboundContext.setSenderContactId(adminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/demote " + nonGroupAdminContactId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_DEMOTE_TARGET_NOT_ADMIN), result.text());
        assertEquals(1L, countAuditRowsByTargetContact("DEMOTE_GROUP_ADMIN_INTENT",
                        nonGroupAdminContactId),
                "the admin's refused probe must leave exactly one surviving "
                        + "DEMOTE_GROUP_ADMIN_INTENT row");
        assertEquals(0L, countAuditRowsByTargetContact("DEMOTE_GROUP_ADMIN",
                        nonGroupAdminContactId),
                "the refused probe must not write a DEMOTE_GROUP_ADMIN effect row");
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

    private UUID seedUserReturningId(Connection conn, String contactId) throws Exception {
        UUID userId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, is_admin, registration_state) "
                        + "VALUES (?, ?, ?, FALSE, 'vouched') "
                        + "ON CONFLICT (adapter, contact_id) DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setString(2, ADAPTER);
            ps.setString(3, contactId);
            ps.executeUpdate();
        }
        // Resolve actual ID (in case of conflict)
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
