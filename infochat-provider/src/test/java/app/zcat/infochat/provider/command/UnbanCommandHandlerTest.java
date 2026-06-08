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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link UnbanCommandHandler} against the
 * DevServices Postgres container. One {@code @Test} per acceptance
 * scenario 11..15 in M1-044c.
 *
 * <p>Test isolation: each {@code @Test} uses a unique sub-prefix
 * within the class-wide {@code PREFIX} ({@code m1-044c-unban-});
 * {@link #cleanup()} disables the V5 audit-log append-only triggers
 * for the cleanup pass (we own the table) so audit rows from prior
 * runs can be deleted alongside the users they reference. The triggers
 * are re-enabled in {@code finally} so the invariant is intact for
 * the test body and any other concurrent reader.</p>
 *
 * @implNote Canonical thin-SQL handler exception per
 *     {@code docs/process/test-pyramid.md} §Shape B: Thin-SQL.
 */
@QuarkusTest
class UnbanCommandHandlerTest {

    private static final String PREFIX = "m1-044c-unban-";
    private static final String ADAPTER = "inmemory";

    @Inject UnbanCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin survives cleanup so the last-admin trigger
            // does not refuse the DELETE on test admins below.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-044c-unban-permanent");
            exec(conn,
                    "DELETE FROM invite_code WHERE expected_contact_id LIKE ? "
                            + "OR created_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                                + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                exec(conn,
                        "DELETE FROM group_membership "
                                + "WHERE user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM groups WHERE display_name LIKE ?",
                        PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL WHERE contact_id LIKE ?",
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

    // ----- (M1-198) group scope → command_dm_only, before admin gate -------

    @Test
    void unbanInGroupScopeReturnsCommandDmOnly() throws Exception {
        // Bot-global admin command is DM-only: the group-scope guard
        // returns the accurate scope error before the admin gate, so the
        // group_admins_restored reply never discloses cross-group roles.
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(PREFIX + "grp-dm-only"), "/unban " + PREFIX + "someone");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY), reply.text(),
                "/unban in group scope must return error.command_dm_only");
    }

    // ----- (11) Non-admin /unban → error.admin_only, no DB write ----------

    @Test
    void unbanByNonAdminReturnsAdminOnly() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        String target = PREFIX + "nonAdmin-target";
        seedUser(actor, false, false, "invited");
        seedUser(target, false, true, "invited");
        long usersBefore = countUsersUnderPrefix();
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "nonAdmin-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unban " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text());
        assertEquals(usersBefore, countUsersUnderPrefix(),
                "non-admin /unban must not touch users");
        assertEquals(auditBefore, countAuditUnderTargetPrefix(PREFIX + "nonAdmin-"),
                "non-admin /unban must not write any audit row");
        // Target still banned.
        assertTrue(isBanned(target), "target row must still be is_banned=TRUE");
    }

    // ----- (12) Unknown contact → error.contact_not_registered, no write --

    @Test
    void unbanUnknownContactReturnsContactNotRegistered() throws Exception {
        String actor = PREFIX + "unknown-actor";
        String absent = PREFIX + "unknown-absent";
        seedUser(actor, /* isAdmin */ true, false, "vouched");
        long usersBefore = countUsersUnderPrefix();

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unban " + absent);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED), reply.text(),
                "/unban against a contact with no users row must surface "
                        + "error.contact_not_registered");
        assertEquals(usersBefore, countUsersUnderPrefix(),
                "/unban against unknown contact must not write any users row");
        // The admin's probe leaves exactly one UNBAN_INTENT row — the
        // intent write precedes target resolution, per the GrantAdmin
        // unknown-contact semantics — and no effect row.
        assertEquals(1L, countAuditRowsByTargetContact("UNBAN_INTENT", absent),
                "the admin's unknown-contact probe must leave a surviving UNBAN_INTENT row");
        assertEquals(0L, countAuditRowsByTargetContact("UNBAN", absent),
                "/unban against unknown contact must not write an UNBAN effect row");
        assertEquals(0L, countAuditRowsByTargetContact("UNBAN_PREBAN_DELETE", absent),
                "/unban against unknown contact must not write an UNBAN_PREBAN_DELETE row");
    }

    // ----- not-banned no-op: no UNBAN row, no restoration claim ------------

    @Test
    void unbanOfNonBannedNonPrebanUserWritesNoUnbanAuditRow() throws Exception {
        String actor = PREFIX + "noop-actor";
        String target = PREFIX + "noop-target";
        seedUser(actor, /* isAdmin */ true, false, "vouched");
        UUID targetId = seedUser(target, /* isAdmin */ false, /* isBanned */ false, "invited");
        // Target currently holds a group-admin slot: the no-op reply
        // must NOT claim that slot was "restored" (nothing was).
        UUID groupId = seedGroup(PREFIX + "noop-group", "GroupNoop");
        seedMembership(groupId, targetId, /* isGroupAdmin */ true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unban " + target);

        assertEquals(bundleLoader.get(BundleKeys.REPLY_UNBAN_PLAIN), reply.text(),
                "no-op /unban of a non-banned user must reply plainly, with no "
                        + "group-admin restoration claim");
        assertEquals(0L, countAuditRows("UNBAN", targetId),
                "no-op /unban must not fabricate an UNBAN audit row");
        // The probe is still visible: exactly one intent row survives.
        assertEquals(1L, countAuditRows("UNBAN_INTENT", targetId),
                "no-op /unban must leave a surviving UNBAN_INTENT row");
    }

    // ----- (13) Preban path: CALL delete_preban_user, audit + delete -------

    @Test
    void unbanOfPrebanRowCallsDeletePrebanUserProcedure() throws Exception {
        String actor = PREFIX + "preban-actor";
        String target = PREFIX + "preban-target";
        UUID actorId = seedUser(actor, /* isAdmin */ true, false, "vouched");
        UUID targetId = seedUser(target, /* isAdmin */ false, /* isBanned */ true, "preban");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unban " + target);

        // Reply pinned to bundle value containing the spec literals.
        String body = reply.text();
        assertEquals(bundleLoader.get(BundleKeys.REPLY_UNBAN_PREBAN_DELETED), body);
        assertTrue(body.contains("pre-ban-only"),
                "reply.unban.preban_deleted MUST contain literal `pre-ban-only` per spec — got: "
                        + body);
        assertTrue(body.contains("fresh invite"),
                "reply.unban.preban_deleted MUST contain literal `fresh invite` per spec — got: "
                        + body);
        // users row is gone (the procedure deletes it).
        assertNull(userId(target),
                "preban users row must be deleted by delete_preban_user");
        // Exactly one UNBAN_PREBAN_DELETE audit row exists referencing the deleted user.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*), MAX(request_id) FROM audit_log "
                             + "WHERE action = 'UNBAN_PREBAN_DELETE' "
                             + "  AND target_id = ?")) {
            ps.setString(1, targetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1L, rs.getLong(1),
                        "exactly one UNBAN_PREBAN_DELETE audit row must reference the "
                                + "deleted user's id");
                String requestId = rs.getString(2);
                assertNotNull(requestId,
                        "request_id must propagate from the handler's SET LOCAL into the "
                                + "procedure-written audit row — null means SET LOCAL was "
                                + "missing or applied to a different Connection");
            }
        }
    }

    // ----- (14) Non-preban path, zero group-admin rows → plain reply ------

    @Test
    void unbanOfNonPrebanWithoutGroupAdminsReturnsPlainReply() throws Exception {
        String actor = PREFIX + "plain-actor";
        String target = PREFIX + "plain-target";
        UUID actorId = seedUser(actor, /* isAdmin */ true, false, "vouched");
        UUID targetId = seedUser(target, /* isAdmin */ false, /* isBanned */ true, "invited");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unban " + target);

        assertEquals(bundleLoader.get(BundleKeys.REPLY_UNBAN_PLAIN), reply.text(),
                "/unban of a non-preban row with zero group-admin rows must surface "
                        + "reply.unban.plain");
        // is_banned flipped to FALSE; ban metadata cleared.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_banned, banned_at, banned_by, ban_reason "
                             + "FROM users WHERE id = ?")) {
            ps.setObject(1, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must remain post-unban");
                assertFalse(rs.getBoolean("is_banned"),
                        "is_banned must be FALSE after /unban");
                assertNull(rs.getTimestamp("banned_at"),
                        "banned_at must be cleared by /unban");
                assertNull(rs.getObject("banned_by"),
                        "banned_by must be cleared by /unban");
                assertNull(rs.getString("ban_reason"),
                        "ban_reason must be cleared by /unban");
            }
        }
        // Exactly one UNBAN audit row exists for the target.
        assertEquals(1L, countAuditRows("UNBAN", targetId),
                "/unban must write exactly one UNBAN audit row");
    }

    // ----- (15) Non-preban path with group-admin row → restored reply ------

    @Test
    void unbanOfNonPrebanWithGroupAdminsReturnsRestoredReply() throws Exception {
        String actor = PREFIX + "restore-actor";
        String target = PREFIX + "restore-target";
        UUID actorId = seedUser(actor, /* isAdmin */ true, false, "vouched");
        UUID targetId = seedUser(target, /* isAdmin */ false, /* isBanned */ true, "invited");
        // Seed one group + membership with is_group_admin=TRUE.
        UUID groupId = seedGroup(PREFIX + "restore-group", "GroupAlpha");
        seedMembership(groupId, targetId, /* isGroupAdmin */ true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/unban " + target);

        // Reply enumerates the group display name AND contains /demote.
        String body = reply.text();
        assertTrue(body.contains("GroupAlpha"),
                "reply must interpolate the group's display name — got: " + body);
        assertTrue(body.contains("/demote"),
                "reply MUST contain literal /demote hint per spec — got: " + body);
        // is_banned flipped to FALSE.
        assertFalse(isBanned(target),
                "is_banned must be FALSE after /unban on non-preban path");
        // UNBAN audit row's details_json carries restored_group_admin list
        // including the same group.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT details_json::text FROM audit_log "
                             + "WHERE action = 'UNBAN' AND target_id = ?")) {
            ps.setString(1, targetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "exactly one UNBAN audit row must exist");
                String details = rs.getString(1);
                assertNotNull(details, "details_json must be non-null");
                assertTrue(details.contains("restored_group_admin"),
                        "details_json must carry restored_group_admin key — got: " + details);
                assertTrue(details.contains(groupId.toString()),
                        "details_json.restored_group_admin must list the restored group id — got: "
                                + details);
            }
        }
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin, boolean isBanned,
                          String registrationState) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at) "
                             + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END) "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setString(5, registrationState);
            ps.setBoolean(6, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedGroup(String displayName, String label) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name) "
                             + "VALUES (?, ?, ?) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, displayName + "-upstream-id");
            ps.setString(3, label);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedMembership(UUID groupId, UUID userId, boolean isGroupAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                             + "VALUES (?, ?, ?)")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.setBoolean(3, isGroupAdmin);
            ps.executeUpdate();
        }
    }

    private UUID userId(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private boolean isBanned(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean("is_banned");
            }
        }
    }

    private long countUsersUnderPrefix() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM users WHERE contact_id LIKE ?")) {
            ps.setString(1, PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditUnderTargetPrefix(String subPrefix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE target_contact_id LIKE ?")) {
            ps.setString(1, subPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditRowsByTargetContact(String action, String targetContactId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_contact_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetContactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditRows(String action, UUID targetId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
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
