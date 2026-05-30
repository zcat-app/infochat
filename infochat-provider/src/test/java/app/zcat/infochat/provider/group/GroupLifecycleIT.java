package app.zcat.infochat.provider.group;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-cutting group lifecycle roundtrip: auto-promote, admin-gated
 * commands, member-access, /promote swap, /group-timezone, user-left
 * clearing + re-auto-promote, DM-only gate. Exercises the full path
 * through InMemoryAdapter → InboundRouter → command handlers.
 */
@QuarkusTest
@TestProfile(GroupLifecycleIT.Profile.class)
class GroupLifecycleIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "group-lifecycle-it-";
    private static final String U1 = CONTACT_PREFIX + "u1";
    private static final String U2 = CONTACT_PREFIX + "u2";
    private static final String BOT_ADMIN = CONTACT_PREFIX + "bot-admin";
    private static final String GUARDIAN = CONTACT_PREFIX + "guardian";
    private static final String GROUP_UPSTREAM_ID = "group-lifecycle-it-g1";

    @Inject InMemoryAdapter adapter;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin so last-admin-protection trigger doesn't block cleanup
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                  + "VALUES (?, ?, TRUE, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);
            // Clean group_membership first (FK to groups and users)
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN ("
                  + "SELECT id FROM groups WHERE upstream_group_id = ?)",
                    GROUP_UPSTREAM_ID);
            // Disable append-only trigger, clean audit_log, then re-enable
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                      + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                        CONTACT_PREFIX + "%", GUARDIAN);
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ? AND contact_id != ?",
                        CONTACT_PREFIX + "%", GUARDIAN);
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            exec(conn, "DELETE FROM groups WHERE upstream_group_id = ?", GROUP_UPSTREAM_ID);
        }
    }

    @Test
    void groupLifecycleRoundtrip() throws Exception {
        UUID u1Id, u2Id, groupDbId;
        try (Connection conn = dataSource.getConnection()) {
            u1Id = seedVouchedUser(conn, U1);
            u2Id = seedVouchedUser(conn, U2);
            seedBotAdmin(conn, BOT_ADMIN);
            groupDbId = seedGroup(conn);
        }
        adapter.createGroup(GROUP_UPSTREAM_ID);
        adapter.addMember(GROUP_UPSTREAM_ID, U1);
        adapter.addMember(GROUP_UPSTREAM_ID, U2);
        adapter.addMember(GROUP_UPSTREAM_ID, BOT_ADMIN);

        // Step (a) — first @mention auto-promote
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U1, "/help");
        assertTrue(isGroupAdmin(groupDbId, u1Id),
                "Step a: u-1 should be auto-promoted to group admin on first mention");

        // Step (b) — second member join, no auto-promote
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U2, "/help");
        assertFalse(isGroupAdmin(groupDbId, u2Id),
                "Step b: u-2 should NOT be group admin (slot occupied by u-1)");

        // Step (c) — admin-gated command succeeds for admin
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U1,
                "/add-source https://example.com/feed.xml --tags=test");
        assertNotEquals(
                bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY),
                lastReply().text(),
                "Step c: group-admin u-1 should pass the admin gate for /add-source");

        // Step (d) — admin-gated command rejected for non-admin
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U2,
                "/add-source https://example2.com/feed.xml --tags=test");
        assertEquals(
                bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY),
                lastReply().text(),
                "Step d: non-admin u-2 should get the group-admin-required error");

        // Step (e) — member-access command succeeds for any member
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U2, "/saved");
        String savedReply = lastReply().text();
        assertNotEquals(
                bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY),
                savedReply,
                "Step e: /saved should not require group-admin");
        assertEquals(
                bundleLoader.get(BundleKeys.REPLY_SAVED_EMPTY),
                savedReply,
                "Step e: /saved for user with no saves should return the empty reply");

        // Step (f) — /promote swaps admin
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, BOT_ADMIN, "/promote " + U2);
        assertTrue(isGroupAdmin(groupDbId, u2Id),
                "Step f: u-2 should be group admin after /promote");
        assertFalse(isGroupAdmin(groupDbId, u1Id),
                "Step f: u-1 should be demoted after /promote");

        // Step (f) continued — subsequent admin-gated command from u-2 succeeds
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U2,
                "/add-source https://example3.com/feed.xml --tags=test");
        assertNotEquals(
                bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY),
                lastReply().text(),
                "Step f: promoted u-2 should pass the admin gate");

        // Step (f) continued — admin-gated command from u-1 now rejected
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U1,
                "/add-source https://example4.com/feed.xml --tags=test");
        assertEquals(
                bundleLoader.get(BundleKeys.ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY),
                lastReply().text(),
                "Step f: demoted u-1 should be rejected by the admin gate");

        // Step (g) — /group-timezone updates timezone
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U2, "/group-timezone Europe/Prague");
        assertEquals("Europe/Prague", getGroupTimezone(groupDbId),
                "Step g: group timezone should be Europe/Prague");

        // Step (h) — user-left clears admin slot + next auto-promote
        adapter.removeMember(GROUP_UPSTREAM_ID, U2);
        assertTrue(isMemberRemoved(groupDbId, u2Id),
                "Step h: u-2 should have removed_at set after removeMember");
        assertFalse(isGroupAdmin(groupDbId, u2Id),
                "Step h: u-2's group-admin flag should be cleared after removal");

        // Next mention from u-1 triggers re-auto-promote (slot now empty)
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U1, "/help");
        assertTrue(isGroupAdmin(groupDbId, u1Id),
                "Step h: u-1 should be re-auto-promoted (admin slot empty after u-2 left)");

        // Step (i) — DM-only command in group returns DM-only error
        adapter.deliverGroupMention(GROUP_UPSTREAM_ID, U1, "/grant-admin " + U2);
        assertEquals(
                bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY),
                lastReply().text(),
                "Step i: /grant-admin in group scope should return the DM-only error");
    }

    // --- helpers ---

    private UUID seedVouchedUser(Connection conn, String contactId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (adapter, contact_id, registration_state) "
              + "VALUES (?, ?, 'vouched') "
              + "ON CONFLICT (adapter, contact_id) DO UPDATE "
              + "  SET registration_state = 'vouched', is_banned = FALSE, "
              + "    probation_until = NULL "
              + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedBotAdmin(Connection conn, String contactId) throws Exception {
        exec(conn,
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
              + "VALUES (?, ?, TRUE, 'vouched') "
              + "ON CONFLICT (adapter, contact_id) DO UPDATE "
              + "  SET is_admin = TRUE, is_banned = FALSE, "
              + "    registration_state = 'vouched', probation_until = NULL",
                ADAPTER, contactId);
    }

    private UUID seedGroup(Connection conn) throws Exception {
        // approval_status='approved' (M1-112): bypass the D47 step-3.5
        // gate so lifecycle scenarios reach step 4.1 auto-promote and
        // beyond. ON CONFLICT overwrites the column so a row carried
        // over from a prior test run is also normalized to approved.
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO groups (adapter, upstream_group_id, display_name, approval_status) "
              + "VALUES (?, ?, 'lifecycle-test', 'approved') "
              + "ON CONFLICT (adapter, upstream_group_id) DO UPDATE "
              + "  SET removed_at = NULL, approval_status = 'approved' "
              + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, GROUP_UPSTREAM_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
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

    private boolean isMemberRemoved(UUID groupId, UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM group_membership "
                   + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getTimestamp("removed_at") != null;
            }
        }
    }

    private String getGroupTimezone(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT timezone FROM groups WHERE id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("timezone") : null;
            }
        }
    }

    private OutboundMessage lastReply() {
        var sent = adapter.sentMessages();
        assertFalse(sent.isEmpty(), "Expected at least one reply");
        return sent.getLast();
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }
    }
}
