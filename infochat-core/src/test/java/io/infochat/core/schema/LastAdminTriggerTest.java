package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant 2 — single-transaction last-admin protection. The
 * {@code trg_last_admin_protection_update} trigger raises when an
 * UPDATE would leave the deployment with zero {@code is_admin = TRUE
 * AND is_banned = FALSE} rows. Both the revoke path (flip
 * {@code is_admin} to FALSE) and the ban path (flip
 * {@code is_banned} to TRUE on the only admin) fire the same guard;
 * both expect an exception whose message contains the literal
 * substring {@code last_admin_protection}. The two-admin happy path
 * verifies the guard does NOT fire when a sibling admin remains.
 */
class LastAdminTriggerTest extends PostgresSchemaTestBase {

    @Test
    void revokingTheOnlyAdminRaisesLastAdminProtection() throws SQLException {
        try (Connection c = newConnection()) {
            String adminId = insertAdmin(c, "only-admin@example");
            SQLException ex = assertThrows(SQLException.class,
                    () -> updateIsAdmin(c, adminId, false));
            assertTrue(ex.getMessage().contains("last_admin_protection"),
                    "expected last_admin_protection in: " + ex.getMessage());
        }
    }

    @Test
    void banningTheOnlyAdminRaisesLastAdminProtection() throws SQLException {
        try (Connection c = newConnection()) {
            String adminId = insertAdmin(c, "only-admin@example");
            SQLException ex = assertThrows(SQLException.class,
                    () -> updateIsBanned(c, adminId, true));
            assertTrue(ex.getMessage().contains("last_admin_protection"),
                    "expected last_admin_protection in: " + ex.getMessage());
        }
    }

    @Test
    void revokingOneOfTwoAdminsSucceeds() throws SQLException {
        try (Connection c = newConnection()) {
            String adminA = insertAdmin(c, "alice@example");
            insertAdmin(c, "bob@example");
            updateIsAdmin(c, adminA, false);
            try (Statement s = c.createStatement();
                 var rs = s.executeQuery(
                         "SELECT count(*) FROM users WHERE is_admin = TRUE AND is_banned = FALSE")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    private static String insertAdmin(Connection c, String contactId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                        + "VALUES (?, ?, TRUE, 'vouched') RETURNING id")) {
            insert.setString(1, "inmemory");
            insert.setString(2, contactId);
            try (var rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void updateIsAdmin(Connection c, String userId, boolean isAdmin) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(
                "UPDATE users SET is_admin = ? WHERE id = ?::uuid")) {
            update.setBoolean(1, isAdmin);
            update.setString(2, userId);
            update.executeUpdate();
        }
    }

    private static void updateIsBanned(Connection c, String userId, boolean isBanned) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(
                "UPDATE users SET is_banned = ? WHERE id = ?::uuid")) {
            update.setBoolean(1, isBanned);
            update.setString(2, userId);
            update.executeUpdate();
        }
    }
}
