package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises M1-008a redteam Finding 5: GUC-driven audit-row actor
 * consistency via the V24 trg_audit_log_actor_check trigger.
 */
class AuditActorIntegrityTest extends PostgresSchemaTestBase {

    @Test
    void gucUnsetAllowsArbitraryActorInsert() throws SQLException {
        try (Connection c = newConnection()) {
            String userId = insertUser(c, "user-a@example");
            String otherId = insertUser(c, "user-b@example");

            // No SET LOCAL infochat.actor_id — GUC is unset.
            insertAuditRow(c, otherId, userId);
            // Must not raise; preserves bootstrap-admin and pre-wiring paths.
        }
    }

    @Test
    void gucSetActorMismatchRaises() throws SQLException {
        try (Connection c = newConnection()) {
            c.setAutoCommit(false);
            String realActorId = insertUser(c, "real-actor@example");
            String claimedActorId = insertUser(c, "claimed-actor@example");
            String targetId = insertUser(c, "target@example");

            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL infochat.actor_id = '" + realActorId + "'");
            }

            SQLException ex = assertThrows(SQLException.class,
                    () -> insertAuditRow(c, claimedActorId, targetId));
            assertTrue(ex.getMessage().contains("audit_log actor mismatch"),
                    "expected 'audit_log actor mismatch' in: " + ex.getMessage());

            c.rollback();
        }
    }

    @Test
    void gucSetNullActorUserIdSucceeds() throws SQLException {
        try (Connection c = newConnection()) {
            c.setAutoCommit(false);
            String actorId = insertUser(c, "actor@example");
            String targetId = insertUser(c, "target@example");

            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL infochat.actor_id = '" + actorId + "'");
            }

            // System-actor audit row: actor_user_id IS NULL.
            insertAuditRowNullActor(c, targetId);
            // Must not raise; system-actor rows are unconditionally allowed.

            c.commit();
        }
    }

    @Test
    void gucSetMatchingActorSucceeds() throws SQLException {
        try (Connection c = newConnection()) {
            c.setAutoCommit(false);
            String actorId = insertUser(c, "actor@example");
            String targetId = insertUser(c, "target@example");

            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL infochat.actor_id = '" + actorId + "'");
            }

            insertAuditRow(c, actorId, targetId);
            // Must not raise; the claimed actor matches the session GUC.

            c.commit();
        }
    }

    private static String insertUser(Connection c, String contactId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                        + "VALUES (?, ?, FALSE, 'vouched') RETURNING id")) {
            insert.setString(1, "inmemory");
            insert.setString(2, contactId);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insertAuditRow(Connection c, String actorUserId,
                                        String targetId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO audit_log (actor_user_id, action, target_kind, target_id) "
                        + "VALUES (?::uuid, 'GRANT_ADMIN', 'user', ?)")) {
            insert.setString(1, actorUserId);
            insert.setString(2, targetId);
            insert.executeUpdate();
        }
    }

    private static void insertAuditRowNullActor(Connection c,
                                                  String targetId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO audit_log (actor_user_id, action, target_kind, target_id) "
                        + "VALUES (NULL, 'BOOTSTRAP_ADMIN', 'user', ?)")) {
            insert.setString(1, targetId);
            insert.executeUpdate();
        }
    }
}
