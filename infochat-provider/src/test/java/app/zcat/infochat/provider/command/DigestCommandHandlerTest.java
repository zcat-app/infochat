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
class DigestCommandHandlerTest {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-227-digest-";
    private static final String UPSTREAM_GROUP_ID = PREFIX + "group-" + UUID.randomUUID();

    @Inject DigestCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject GroupRepository groupRepository;

    private UUID groupId;

    private String botAdminContactId;
    private String groupAdminContactId;
    private String regularUserContactId;
    private UUID groupAdminUserId;

    @BeforeEach
    void setup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);

        botAdminContactId = PREFIX + "botadmin-" + UUID.randomUUID();
        groupAdminContactId = PREFIX + "gadmin-" + UUID.randomUUID();
        regularUserContactId = PREFIX + "regular-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            cleanTestData(conn);

            // Guardian admin — keeps the last-admin invariant satisfied.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-" + PREFIX + "permanent");

            // Bot admin (not a group admin of this group).
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, botAdminContactId);

            // Group admin (not a bot admin).
            groupAdminUserId = seedUserReturningId(conn, groupAdminContactId);

            // Regular user (neither bot admin nor group admin).
            seedUserReturningId(conn, regularUserContactId);
        }

        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(ADAPTER, UPSTREAM_GROUP_ID);

        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id, is_group_admin) VALUES (?, ?, true) "
                            + "ON CONFLICT DO NOTHING",
                    groupId, groupAdminUserId);
        }
    }

    @Test
    void digestOff_setsDigestEnabledFalse_forGroupAdmin() {
        inboundContext.setSenderContactId(groupAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest off");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_DIGEST_OFF), result.text());
        assertFalse(getDigestEnabled(groupId));
        assertEquals(1, countAuditRows("DIGEST_DISABLE", groupId));
    }

    @Test
    void digestOn_setsDigestEnabledTrue_forGroupAdmin() {
        setDigestEnabled(groupId, false);
        inboundContext.setSenderContactId(groupAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest on");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_DIGEST_ON), result.text());
        assertTrue(getDigestEnabled(groupId));
        assertEquals(1, countAuditRows("DIGEST_ENABLE", groupId));
    }

    @Test
    void subVerbMatchedCaseInsensitively() {
        inboundContext.setSenderContactId(groupAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest OFF");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_DIGEST_OFF), result.text());
        assertFalse(getDigestEnabled(groupId));
    }

    @Test
    void nonAdminGroupMember_getsNotAdminError_noStateChange() {
        inboundContext.setSenderContactId(regularUserContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest off");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_DIGEST_NOT_ADMIN), result.text());
        assertTrue(getDigestEnabled(groupId));
        assertEquals(0, countAuditRows("DIGEST_DISABLE", groupId));
    }

    @Test
    void botAdmin_isPermitted_evenWithoutGroupAdmin() {
        inboundContext.setSenderContactId(botAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest off");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_DIGEST_OFF), result.text());
        assertFalse(getDigestEnabled(groupId));
    }

    @Test
    void dmScope_returnsGroupOnlyError() {
        inboundContext.setSenderContactId(botAdminContactId);
        ScopeRef scope = new ScopeRef.Dm(botAdminContactId);

        OutboundMessage result = handler.handle(scope, "/digest off");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_DIGEST_DM_SCOPE), result.text());
    }

    @Test
    void missingSubVerb_returnsUsageError() {
        inboundContext.setSenderContactId(groupAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_DIGEST_USAGE), result.text());
        assertTrue(getDigestEnabled(groupId));
    }

    @Test
    void unknownSubVerb_returnsUsageError() {
        inboundContext.setSenderContactId(groupAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest maybe");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_DIGEST_USAGE), result.text());
        assertTrue(getDigestEnabled(groupId));
    }

    @Test
    void idempotentOn_whenAlreadyOn_repliesAlreadyOn_noUpdate_noAudit() {
        // Group defaults to digest_enabled = true.
        inboundContext.setSenderContactId(groupAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest on");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_DIGEST_ALREADY_ON), result.text());
        assertTrue(getDigestEnabled(groupId));
        assertEquals(0, countAuditRows("DIGEST_ENABLE", groupId));
    }

    @Test
    void idempotentOff_whenAlreadyOff_repliesAlreadyOff_noUpdate_noAudit() {
        setDigestEnabled(groupId, false);
        inboundContext.setSenderContactId(groupAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/digest off");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_DIGEST_ALREADY_OFF), result.text());
        assertFalse(getDigestEnabled(groupId));
        assertEquals(0, countAuditRows("DIGEST_DISABLE", groupId));
    }

    private boolean getDigestEnabled(UUID gId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT digest_enabled FROM groups WHERE id = ?")) {
            ps.setObject(1, gId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean("digest_enabled");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setDigestEnabled(UUID gId, boolean enabled) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE groups SET digest_enabled = ? WHERE id = ?")) {
            ps.setBoolean(1, enabled);
            ps.setObject(2, gId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long countAuditRows(String action, UUID gId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, gId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
        // audit_log is append-only (Invariant 10) and is NOT cleaned here.
        // Each test gets a fresh group UUID (the group is deleted and
        // recreated below, taking a new gen_random_uuid id), so audit rows
        // from a prior test are keyed to a now-deleted group id and never
        // match countAuditRows(action, currentGroupId).
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
