package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant 10 — audit_log is append-only. The trigger guard fires on
 * both UPDATE and DELETE paths against {@code audit_log}, raising an
 * exception whose message contains the literal substring
 * {@code append-only}. The role-level INSERT-only grant is a separate
 * defense layer; this test exercises the trigger directly via the
 * bootstrap superuser, which bypasses the grant matrix.
 */
class AuditLogAppendOnlyTest extends PostgresSchemaTestBase {

    @Test
    void updateOnAuditLogRaisesAppendOnly() throws SQLException {
        seedOneAuditRow();
        SQLException ex;
        try (Connection c = newConnection(); Statement s = c.createStatement()) {
            ex = assertThrows(SQLException.class,
                    () -> s.execute("UPDATE audit_log SET action = 'GRANT_ADMIN'"));
        }
        assertTrue(ex.getMessage().contains("append-only"),
                "expected append-only in: " + ex.getMessage());
    }

    @Test
    void deleteOnAuditLogRaisesAppendOnly() throws SQLException {
        seedOneAuditRow();
        SQLException ex;
        try (Connection c = newConnection(); Statement s = c.createStatement()) {
            ex = assertThrows(SQLException.class,
                    () -> s.execute("DELETE FROM audit_log WHERE id IS NOT NULL"));
        }
        assertTrue(ex.getMessage().contains("append-only"),
                "expected append-only in: " + ex.getMessage());
    }

    private void seedOneAuditRow() throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement insertUser = c.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, TRUE, 'vouched') RETURNING id");
             PreparedStatement insertAudit = c.prepareStatement(
                     "INSERT INTO audit_log (actor_user_id, action, target_kind, target_id) "
                             + "VALUES (?, 'BOOTSTRAP_ADMIN', 'user', ?)")) {
            insertUser.setString(1, "inmemory");
            insertUser.setString(2, "actor@example");
            String actorId;
            try (var rs = insertUser.executeQuery()) {
                rs.next();
                actorId = rs.getString(1);
            }
            insertAudit.setObject(1, java.util.UUID.fromString(actorId));
            insertAudit.setString(2, actorId);
            insertAudit.executeUpdate();
        }
    }
}
