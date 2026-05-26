package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises M1-008a redteam Finding 2: GUC-driven trigger-layer self-ban
 * prevention via the V24 redeclared trg_last_admin_protection_update.
 */
class CannotBanSelfTriggerTest extends PostgresSchemaTestBase {

    @Test
    void banSelfWithGucSetRaisesAndLeavesUnbanned() throws SQLException {
        try (Connection c = newConnection()) {
            String adminId = insertAdmin(c, "self-banner@example");
            // Ensure a second admin so last-admin protection doesn't fire first.
            insertAdmin(c, "other-admin@example");

            // SET LOCAL requires a transaction; start one after the inserts are committed.
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL infochat.actor_id = '" + adminId + "'");
            }

            SQLException ex;
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE users SET is_banned = TRUE WHERE id = ?::uuid")) {
                update.setString(1, adminId);
                ex = assertThrows(SQLException.class, update::executeUpdate);
            }
            assertTrue(ex.getMessage().contains("cannot ban self"),
                    "expected 'cannot ban self' in: " + ex.getMessage());

            c.rollback();
            c.setAutoCommit(true);

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT is_banned FROM users WHERE id = '" + adminId + "'")) {
                assertTrue(rs.next(), "admin row must exist after rollback");
                assertFalse(rs.getBoolean("is_banned"), "is_banned must remain FALSE");
            }
        }
    }

    @Test
    void banOtherAdminWithGucSetSucceeds() throws SQLException {
        try (Connection c = newConnection()) {
            String actorId = insertAdmin(c, "actor-admin@example");
            String targetId = insertAdmin(c, "target-admin@example");

            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL infochat.actor_id = '" + actorId + "'");
            }

            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE users SET is_banned = TRUE WHERE id = ?::uuid")) {
                update.setString(1, targetId);
                update.executeUpdate();
            }
            c.commit();

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT is_banned FROM users WHERE id = '" + targetId + "'")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("is_banned"), "target must be banned");
            }
        }
    }

    @Test
    void legacyGucUnsetPathStillRaisesLastAdminProtection() throws SQLException {
        try (Connection c = newConnection()) {
            String onlyAdminId = insertAdmin(c, "only-admin@example");

            SQLException ex;
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE users SET is_banned = TRUE WHERE id = ?::uuid")) {
                update.setString(1, onlyAdminId);
                ex = assertThrows(SQLException.class, update::executeUpdate);
            }
            assertTrue(ex.getMessage().contains("last_admin_protection"),
                    "expected 'last_admin_protection' in: " + ex.getMessage());
        }
    }

    private static String insertAdmin(Connection c, String contactId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                        + "VALUES (?, ?, TRUE, 'vouched') RETURNING id")) {
            insert.setString(1, "inmemory");
            insert.setString(2, contactId);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
