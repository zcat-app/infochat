package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies the group-admin permission gate across representative
 * handlers (AddSource, FollowTag, Lang). Each test seeds a group,
 * a user, and a group_membership row, then asserts that an admin
 * caller proceeds past the gate while a non-admin caller receives
 * the group-admin-required friendly error.
 */
@QuarkusTest
class AdminGatedGroupScopeTest {

    private static final String PREFIX = "m1-079d-gate-";
    private static final String ADAPTER = "inmemory";

    @Inject AddSourceCommandHandler addSourceHandler;
    @Inject FollowTagCommandHandler followTagHandler;
    @Inject LangCommandHandler langHandler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM scope_tag WHERE scope_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM scope_preferences WHERE scope_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source_subscription WHERE scope_id IN "
                            + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM source WHERE identifier LIKE ?",
                    PREFIX + "%");
            exec(conn,
                    "DELETE FROM tag WHERE name LIKE ?",
                    PREFIX + "%");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN "
                                + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL "
                                + "WHERE banned_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    // ----- /add-source: group admin proceeds ---------------------------------

    @Test
    void addSource_allowsGroupAdmin() throws Exception {
        String adminContact = PREFIX + "addSrc-admin";
        UUID adminId = seedUser(adminContact);
        String groupUpstreamId = PREFIX + "addSrc-grp";
        UUID groupId = seedGroup(groupUpstreamId);
        seedGroupMembership(groupId, adminId, true);

        inboundContext.setSenderContactId(adminContact);

        OutboundMessage reply = addSourceHandler.handle(
                new ScopeRef.Group(groupUpstreamId),
                "/add-source https://example.com/" + PREFIX + "admin.xml --tags " + PREFIX + "t1");

        String errorText = bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY);
        assertNotEquals(errorText, reply.text(),
                "group admin must NOT see the group-admin-only error — got: " + reply.text());
    }

    // ----- /add-source: non-admin rejected -----------------------------------

    @Test
    void addSource_rejectsNonAdmin() throws Exception {
        String memberContact = PREFIX + "addSrc-member";
        UUID memberId = seedUser(memberContact);
        String groupUpstreamId = PREFIX + "addSrc-nonadmin-grp";
        UUID groupId = seedGroup(groupUpstreamId);
        seedGroupMembership(groupId, memberId, false);

        inboundContext.setSenderContactId(memberContact);

        OutboundMessage reply = addSourceHandler.handle(
                new ScopeRef.Group(groupUpstreamId),
                "/add-source https://example.com/" + PREFIX + "member.xml --tags " + PREFIX + "t1");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY),
                reply.text(),
                "non-admin in group scope must see the group-admin-only error");
    }

    // ----- /follow-tag: group admin proceeds ---------------------------------

    @Test
    void followTag_allowsGroupAdmin() throws Exception {
        String adminContact = PREFIX + "followTag-admin";
        UUID adminId = seedUser(adminContact);
        String groupUpstreamId = PREFIX + "followTag-grp";
        UUID groupId = seedGroup(groupUpstreamId);
        seedGroupMembership(groupId, adminId, true);
        seedTag(PREFIX + "crypto");
        seedScopePreferences("group", groupId, "ALL");

        inboundContext.setSenderContactId(adminContact);

        OutboundMessage reply = followTagHandler.handle(
                new ScopeRef.Group(groupUpstreamId),
                "/follow-tag " + PREFIX + "crypto");

        String errorText = bundleLoader.get(BundleKeys.ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY);
        assertNotEquals(errorText, reply.text(),
                "group admin must NOT see the group-admin-only error — got: " + reply.text());
    }

    // ----- /follow-tag: non-admin rejected -----------------------------------

    @Test
    void followTag_rejectsNonAdmin() throws Exception {
        String memberContact = PREFIX + "followTag-member";
        UUID memberId = seedUser(memberContact);
        String groupUpstreamId = PREFIX + "followTag-nonadmin-grp";
        UUID groupId = seedGroup(groupUpstreamId);
        seedGroupMembership(groupId, memberId, false);

        inboundContext.setSenderContactId(memberContact);

        OutboundMessage reply = followTagHandler.handle(
                new ScopeRef.Group(groupUpstreamId),
                "/follow-tag " + PREFIX + "anytag");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY),
                reply.text(),
                "non-admin in group scope must see the group-admin-only error");
    }

    // ----- /lang: group admin proceeds ---------------------------------------

    @Test
    void lang_allowsGroupAdmin() throws Exception {
        String adminContact = PREFIX + "lang-admin";
        UUID adminId = seedUser(adminContact);
        String groupUpstreamId = PREFIX + "lang-grp";
        UUID groupId = seedGroup(groupUpstreamId);
        seedGroupMembership(groupId, adminId, true);

        inboundContext.setSenderContactId(adminContact);

        OutboundMessage reply = langHandler.handle(
                new ScopeRef.Group(groupUpstreamId),
                "/lang en");

        String errorText = bundleLoader.get(BundleKeys.ERROR_LANG_GROUP_ADMIN_NOT_IN_V1);
        assertNotEquals(errorText, reply.text(),
                "group admin must NOT see the lang group-admin error — got: " + reply.text());
    }

    // ----- /lang: non-admin rejected -----------------------------------------

    @Test
    void lang_rejectsNonAdmin() throws Exception {
        String memberContact = PREFIX + "lang-member";
        UUID memberId = seedUser(memberContact);
        String groupUpstreamId = PREFIX + "lang-nonadmin-grp";
        UUID groupId = seedGroup(groupUpstreamId);
        seedGroupMembership(groupId, memberId, false);

        inboundContext.setSenderContactId(memberContact);

        OutboundMessage reply = langHandler.handle(
                new ScopeRef.Group(groupUpstreamId),
                "/lang en");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_LANG_GROUP_ADMIN_NOT_IN_V1),
                reply.text(),
                "non-admin in group scope must see the lang group-admin error");
    }

    // ----- helpers -----------------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state) "
                             + "VALUES (?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedGroup(String upstreamGroupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id) "
                             + "VALUES (?, ?) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedGroupMembership(UUID groupId, UUID userId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                             + "VALUES (?, ?, ?)")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.setBoolean(3, isAdmin);
            ps.executeUpdate();
        }
    }

    private void seedTag(String tagName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tag (name, display) VALUES (?, ?) "
                             + "ON CONFLICT (name) DO NOTHING")) {
            ps.setString(1, tagName);
            ps.setString(2, tagName);
            ps.executeUpdate();
        }
    }

    private void seedScopePreferences(String scopeKind, UUID scopeId, String tagMode)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                             + "VALUES (?, ?, ?) "
                             + "ON CONFLICT (scope_kind, scope_id) DO NOTHING")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setString(3, tagMode);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
