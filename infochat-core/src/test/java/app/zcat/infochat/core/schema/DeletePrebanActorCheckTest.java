package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises M1-008a redteam Findings 1, 3, 4 against the V24 redeclared
 * delete_preban_user procedure.
 */
class DeletePrebanActorCheckTest extends PostgresSchemaTestBase {

    @Test
    void nonExistentActorRaisesAndDoesNotDelete() throws SQLException {
        try (Connection c = newConnection()) {
            String prebanId = insertUser(c, "target@example", false, "preban");
            String fakeActorId = "00000000-0000-0000-0000-000000000099";

            SQLException ex;
            try (PreparedStatement call = c.prepareStatement(
                    "CALL delete_preban_user(?::uuid, ?::uuid)")) {
                call.setString(1, prebanId);
                call.setString(2, fakeActorId);
                ex = assertThrows(SQLException.class, call::execute);
            }
            assertTrue(ex.getMessage().contains("actor is not a bot admin"),
                    "expected 'actor is not a bot admin' in: " + ex.getMessage());

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM users WHERE id = '" + prebanId + "'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "preban row must NOT be deleted");
            }
        }
    }

    @Test
    void nonAdminActorRaisesAndDoesNotDelete() throws SQLException {
        try (Connection c = newConnection()) {
            String nonAdminId = insertUser(c, "regular@example", false, "vouched");
            String prebanId = insertUser(c, "target@example", false, "preban");

            SQLException ex;
            try (PreparedStatement call = c.prepareStatement(
                    "CALL delete_preban_user(?::uuid, ?::uuid)")) {
                call.setString(1, prebanId);
                call.setString(2, nonAdminId);
                ex = assertThrows(SQLException.class, call::execute);
            }
            assertTrue(ex.getMessage().contains("actor is not a bot admin"),
                    "expected 'actor is not a bot admin' in: " + ex.getMessage());

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM users WHERE id = '" + prebanId + "'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "preban row must NOT be deleted");
            }
        }
    }

    @Test
    void adminActorHappyPathDeletesAndWritesAudit() throws SQLException {
        try (Connection c = newConnection()) {
            String adminId = insertUser(c, "admin@example", true, "vouched");
            String prebanId = insertUser(c, "target@example", false, "preban");

            try (PreparedStatement call = c.prepareStatement(
                    "CALL delete_preban_user(?::uuid, ?::uuid)")) {
                call.setString(1, prebanId);
                call.setString(2, adminId);
                call.execute();
            }

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM users WHERE id = '" + prebanId + "'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "preban row must be deleted");
            }

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT action, target_id, actor_user_id::TEXT FROM audit_log "
                                 + "WHERE action = 'UNBAN_PREBAN_DELETE'")) {
                assertTrue(rs.next(), "audit row must exist");
                assertEquals("UNBAN_PREBAN_DELETE", rs.getString("action"));
                assertEquals(prebanId, rs.getString("target_id"));
                assertEquals(adminId, rs.getString("actor_user_id"));
            }
        }
    }

    @Test
    void searchPathShadowAttackStillWritesToRealAuditLog() throws SQLException {
        try (Connection c = newConnection()) {
            String adminId = insertUser(c, "admin@example", true, "vouched");
            String prebanId = insertUser(c, "target@example", false, "preban");

            try (Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA IF NOT EXISTS attack_schema");
                s.execute("CREATE TABLE IF NOT EXISTS attack_schema.audit_log "
                        + "(id SERIAL PRIMARY KEY, action TEXT)");
                s.execute("TRUNCATE attack_schema.audit_log");
                s.execute("SET search_path = attack_schema, public");
            }

            try (PreparedStatement call = c.prepareStatement(
                    "CALL delete_preban_user(?::uuid, ?::uuid)")) {
                call.setString(1, prebanId);
                call.setString(2, adminId);
                call.execute();
            }

            // The procedure's pinned search_path resolves audit_log to public.audit_log.
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM public.audit_log "
                                 + "WHERE action = 'UNBAN_PREBAN_DELETE'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "real audit_log must have the row");
            }

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM attack_schema.audit_log")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "shadow audit_log must be empty");
            }

            try (Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA attack_schema CASCADE");
            }
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
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
