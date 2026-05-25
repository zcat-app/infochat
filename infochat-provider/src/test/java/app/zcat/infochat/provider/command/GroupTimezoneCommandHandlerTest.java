package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.InboundContext;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GroupTimezoneCommandHandlerTest {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-079c-gtz-";
    private static final String UPSTREAM_GROUP_ID = PREFIX + "group-" + UUID.randomUUID();

    @Inject GroupTimezoneCommandHandler handler;
    @Inject DataSource dataSource;
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

            // Guardian admin
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-" + PREFIX + "permanent");

            // Bot admin
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE SET is_admin = TRUE",
                    ADAPTER, botAdminContactId);

            // Group admin (not bot admin)
            groupAdminUserId = seedUserReturningId(conn, groupAdminContactId);

            // Regular user (neither bot admin nor group admin)
            seedUserReturningId(conn, regularUserContactId);
        }

        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(ADAPTER, UPSTREAM_GROUP_ID);

        try (Connection conn = dataSource.getConnection()) {
            // Group admin membership
            exec(conn,
                    "INSERT INTO group_membership (group_id, user_id, is_group_admin) VALUES (?, ?, true) "
                            + "ON CONFLICT DO NOTHING",
                    groupId, groupAdminUserId);
        }
    }

    @Test
    void groupTimezone_setsValidTimezone() {
        inboundContext.setSenderContactId(botAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/group-timezone Europe/Prague");

        assertTrue(result.text().contains("Europe/Prague"));
        assertEquals("Europe/Prague", getGroupTimezone(groupId));
    }

    @Test
    void groupTimezone_rejectsInvalidZone() {
        inboundContext.setSenderContactId(botAdminContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/group-timezone NotAReal/Zone");

        assertTrue(result.text().contains("Unknown timezone"));
    }

    @Test
    void groupTimezone_rejectsNonAdminCaller() {
        inboundContext.setSenderContactId(regularUserContactId);
        ScopeRef scope = new ScopeRef.Group(UPSTREAM_GROUP_ID);

        OutboundMessage result = handler.handle(scope, "/group-timezone UTC");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_NOT_ADMIN), result.text());
    }

    @Test
    void groupTimezone_rejectsDmScope() {
        inboundContext.setSenderContactId(botAdminContactId);
        ScopeRef scope = new ScopeRef.Dm(botAdminContactId);

        OutboundMessage result = handler.handle(scope, "/group-timezone UTC");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_GROUP_TIMEZONE_DM_SCOPE), result.text());
    }

    private String getGroupTimezone(UUID gId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT timezone FROM groups WHERE id = ?")) {
            ps.setObject(1, gId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("timezone") : null;
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
