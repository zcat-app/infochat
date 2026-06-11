package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises V25 remediation of approve_quarantine and reject_quarantine:
 * actor-admin verification and search_path pinning.
 */
class QuarantineActorCheckTest extends PostgresSchemaTestBase {

    @Test
    void approveWithNonAdminActorRaises() throws SQLException {
        try (Connection c = newConnection()) {
            String nonAdminId = insertUser(c, "regular@example", false);
            UUID quarantineId = seedQuarantineRow(c, nonAdminId);

            SQLException ex = assertThrows(SQLException.class,
                    () -> callApprove(c, quarantineId, UUID.fromString(nonAdminId)));
            assertTrue(ex.getMessage().contains("actor is not a bot admin"),
                    "expected 'actor is not a bot admin' in: " + ex.getMessage());
        }
    }

    @Test
    void approveWithNonExistentActorRaises() throws SQLException {
        try (Connection c = newConnection()) {
            String adminId = insertUser(c, "admin@example", true);
            UUID quarantineId = seedQuarantineRow(c, adminId);
            UUID fakeActorId = UUID.fromString("00000000-0000-0000-0000-000000000099");

            SQLException ex = assertThrows(SQLException.class,
                    () -> callApprove(c, quarantineId, fakeActorId));
            assertTrue(ex.getMessage().contains("actor is not a bot admin"),
                    "expected 'actor is not a bot admin' in: " + ex.getMessage());
        }
    }

    @Test
    void rejectWithNonAdminActorRaises() throws SQLException {
        try (Connection c = newConnection()) {
            String nonAdminId = insertUser(c, "regular@example", false);
            UUID quarantineId = seedQuarantineRow(c, nonAdminId);

            SQLException ex = assertThrows(SQLException.class,
                    () -> callReject(c, quarantineId, UUID.fromString(nonAdminId)));
            assertTrue(ex.getMessage().contains("actor is not a bot admin"),
                    "expected 'actor is not a bot admin' in: " + ex.getMessage());
        }
    }

    @Test
    void approveWithBannedAdminActorRaises() throws SQLException {
        try (Connection c = newConnection()) {
            String bannedAdminId = insertBannedAdmin(c, "banned-admin@example");
            UUID quarantineId = seedQuarantineRow(c, bannedAdminId);

            SQLException ex = assertThrows(SQLException.class,
                    () -> callApprove(c, quarantineId, UUID.fromString(bannedAdminId)));
            assertTrue(ex.getMessage().contains("actor is not a bot admin"),
                    "banned admin must be rejected with the same error a non-admin gets: "
                            + ex.getMessage());
        }
    }

    @Test
    void rejectWithBannedAdminActorRaises() throws SQLException {
        try (Connection c = newConnection()) {
            String bannedAdminId = insertBannedAdmin(c, "banned-admin@example");
            UUID quarantineId = seedQuarantineRow(c, bannedAdminId);

            SQLException ex = assertThrows(SQLException.class,
                    () -> callReject(c, quarantineId, UUID.fromString(bannedAdminId)));
            assertTrue(ex.getMessage().contains("actor is not a bot admin"),
                    "banned admin must be rejected with the same error a non-admin gets: "
                            + ex.getMessage());
        }
    }

    @Test
    void approveWithAdminActorSucceeds() throws SQLException {
        try (Connection c = newConnection()) {
            String adminId = insertUser(c, "admin@example", true);
            UUID quarantineId = seedQuarantineRow(c, adminId);

            callApprove(c, quarantineId, UUID.fromString(adminId));

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status FROM quarantine WHERE id = ?")) {
                ps.setObject(1, quarantineId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue("APPROVED".equals(rs.getString("status")),
                            "quarantine must be APPROVED");
                }
            }
        }
    }

    @Test
    void rejectWithAdminActorSucceeds() throws SQLException {
        try (Connection c = newConnection()) {
            String adminId = insertUser(c, "admin@example", true);
            UUID quarantineId = seedQuarantineRow(c, adminId);

            callReject(c, quarantineId, UUID.fromString(adminId));

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status FROM quarantine WHERE id = ?")) {
                ps.setObject(1, quarantineId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue("REJECTED".equals(rs.getString("status")),
                            "quarantine must be REJECTED");
                }
            }
        }
    }

    private static void callApprove(Connection c, UUID quarantineId,
                                     UUID actorId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT approve_quarantine(?, ?)")) {
            ps.setObject(1, quarantineId);
            ps.setObject(2, actorId);
            ps.execute();
        }
    }

    private static void callReject(Connection c, UUID quarantineId,
                                    UUID actorId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT reject_quarantine(?, ?)")) {
            ps.setObject(1, quarantineId);
            ps.setObject(2, actorId);
            ps.execute();
        }
    }

    private static String insertUser(Connection c, String contactId,
                                      boolean isAdmin) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                        + "VALUES (?, ?, ?, 'vouched') RETURNING id")) {
            insert.setString(1, "inmemory");
            insert.setString(2, contactId);
            insert.setBoolean(3, isAdmin);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String insertBannedAdmin(Connection c,
                                             String contactId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, is_banned, registration_state) "
                        + "VALUES (?, ?, TRUE, TRUE, 'vouched') RETURNING id")) {
            insert.setString(1, "inmemory");
            insert.setString(2, contactId);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /**
     * Seeds the minimum fixture for a PENDING quarantine row: a source,
     * a post with a placeholder body, and a quarantine row pointing at it.
     */
    private static UUID seedQuarantineRow(Connection c,
                                           String actorId) throws SQLException {
        UUID sourceId;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES ('rss', 'https://example.com/feed', 'Test', 'news') "
                        + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                sourceId = UUID.fromString(rs.getString(1));
            }
        }

        UUID postId = UUID.randomUUID();
        String placeholderId = UUID.randomUUID().toString().substring(0, 8);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO post (id, source_id, fetched_at, uid, title, "
                        + "body, status, status_changed_at) "
                        + "VALUES (?, ?, now(), ?, 'Test', ?, 'QUARANTINED', now())")) {
            ps.setObject(1, postId);
            ps.setObject(2, sourceId);
            ps.setString(3, "uid-" + postId);
            ps.setString(4, "[REDACTED:" + placeholderId + "]");
            ps.executeUpdate();
        }

        UUID quarantineId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO quarantine (id, post_id, post_uid, post_fetched_at, "
                        + "placeholder_id, original_html, flagged_by, status) "
                        + "VALUES (?, ?, ?, (SELECT fetched_at FROM post WHERE id = ?), "
                        + "?, ?, 'stage1', 'PENDING')")) {
            ps.setObject(1, quarantineId);
            ps.setObject(2, postId);
            ps.setString(3, "uid-" + postId);
            ps.setObject(4, postId);
            ps.setString(5, placeholderId);
            ps.setString(6, "<b>original content</b>");
            ps.executeUpdate();
        }

        return quarantineId;
    }
}
