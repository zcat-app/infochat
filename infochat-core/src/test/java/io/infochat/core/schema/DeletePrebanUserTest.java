package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant 2 carve-out — {@code delete_preban_user(UUID, UUID)} is
 * the single permitted DELETE path on {@code users} and only against
 * {@code registration_state = 'preban'} rows. The procedure writes
 * the {@code UNBAN_PREBAN_DELETE} audit row BEFORE the DELETE
 * (audit-before-effect, Invariant 7); calling against a non-preban
 * row raises with the substring {@code is not in preban state}.
 */
class DeletePrebanUserTest extends PostgresSchemaTestBase {

    @Test
    void callOnPrebanRowDeletesUserAndWritesAudit() throws SQLException {
        try (Connection c = newConnection()) {
            String actorId = insertUser(c, "admin@example", true, "vouched");
            String prebanId = insertUser(c, "preban@example", false, "preban");

            try (PreparedStatement call = c.prepareStatement(
                    "CALL delete_preban_user(?::uuid, ?::uuid)")) {
                call.setString(1, prebanId);
                call.setString(2, actorId);
                call.execute();
            }

            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM users WHERE id = ?::uuid")) {
                check.setString(1, prebanId);
                try (var rs = check.executeQuery()) {
                    assertFalse(rs.next(), "preban user should have been deleted");
                }
            }

            try (PreparedStatement check = c.prepareStatement(
                    "SELECT action, target_id FROM audit_log "
                            + "WHERE action = 'UNBAN_PREBAN_DELETE' AND target_id = ?")) {
                check.setString(1, prebanId);
                try (var rs = check.executeQuery()) {
                    assertTrue(rs.next(), "expected one UNBAN_PREBAN_DELETE audit row");
                    assertEquals("UNBAN_PREBAN_DELETE", rs.getString("action"));
                    assertEquals(prebanId, rs.getString("target_id"));
                    assertFalse(rs.next(), "expected exactly one audit row");
                }
            }
        }
    }

    @Test
    void callOnRegisteredRowRaisesNotInPrebanState() throws SQLException {
        try (Connection c = newConnection()) {
            String actorId = insertUser(c, "admin@example", true, "vouched");
            String registeredId = insertUser(c, "regular@example", false, "vouched");

            SQLException ex;
            try (PreparedStatement call = c.prepareStatement(
                    "CALL delete_preban_user(?::uuid, ?::uuid)")) {
                call.setString(1, registeredId);
                call.setString(2, actorId);
                ex = assertThrows(SQLException.class, call::execute);
            }
            assertTrue(ex.getMessage().contains("is not in preban state"),
                    "expected 'is not in preban state' in: " + ex.getMessage());
        }
    }

    private static String insertUser(Connection c, String contactId, boolean isAdmin,
                                     String registrationState) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            insert.setString(1, "inmemory");
            insert.setString(2, contactId);
            insert.setBoolean(3, isAdmin);
            insert.setString(4, registrationState);
            try (var rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
