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
 * V40 fail-closed hardening (redteam audit M1-190, 2026-06-07).
 *
 * <p>Finding 1: the serialize-at-the-lock argument for Invariant 2
 * silently depended on READ COMMITTED — under REPEATABLE READ the
 * post-lock COUNT reads the transaction snapshot taken before the
 * lock was granted, so two concurrent guarded transitions could each
 * count the other admin as still effective and both commit. V40 now
 * rejects guarded transitions under REPEATABLE READ outright;
 * SERIALIZABLE stays allowed (SSI detects the rw-antidependency).
 *
 * <p>Finding 2: the ban-self check fails open when the
 * {@code infochat.actor_id} GUC is unset. V40 now rejects an
 * actor-less ban of an admin row (the only transition a self-ban can
 * legally be, since /ban is bot-admin-only); non-admin bans are
 * unaffected, and banning the LAST admin still reports IC001 because
 * the actor check runs after the count.
 */
class LastAdminGuardFailClosedTest extends PostgresSchemaTestBase {

    @Test
    void revokeUnderRepeatableReadFailsClosed() throws SQLException {
        String adminA;
        try (Connection setup = newConnection()) {
            adminA = insertAdmin(setup, "rr-alice@example");
            insertAdmin(setup, "rr-bob@example");
        }

        try (Connection c = newConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            SQLException ex;
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE users SET is_admin = FALSE WHERE id = ?::uuid")) {
                update.setString(1, adminA);
                ex = assertThrows(SQLException.class, update::executeUpdate);
            }
            assertTrue(ex.getMessage().contains("last_admin_protection"),
                    "expected last_admin_protection in: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("REPEATABLE READ"),
                    "expected the isolation-level rejection in: " + ex.getMessage());
            c.rollback();
        }

        assertEquals(2, effectiveAdminCount(), "both admins must survive the rejected revoke");
    }

    @Test
    void deleteUnderRepeatableReadFailsClosed() throws SQLException {
        String adminA;
        try (Connection setup = newConnection()) {
            adminA = insertAdmin(setup, "rr-del-alice@example");
            insertAdmin(setup, "rr-del-bob@example");
        }

        try (Connection c = newConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            SQLException ex;
            try (PreparedStatement delete = c.prepareStatement(
                    "DELETE FROM users WHERE id = ?::uuid")) {
                delete.setString(1, adminA);
                ex = assertThrows(SQLException.class, delete::executeUpdate);
            }
            assertTrue(ex.getMessage().contains("REPEATABLE READ"),
                    "expected the isolation-level rejection in: " + ex.getMessage());
            c.rollback();
        }

        assertEquals(2, effectiveAdminCount(), "both admins must survive the rejected delete");
    }

    @Test
    void revokeUnderSerializableSucceeds() throws SQLException {
        String adminA;
        try (Connection setup = newConnection()) {
            adminA = insertAdmin(setup, "ssi-alice@example");
            insertAdmin(setup, "ssi-bob@example");
        }

        try (Connection c = newConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE users SET is_admin = FALSE WHERE id = ?::uuid")) {
                update.setString(1, adminA);
                update.executeUpdate();
            }
            c.commit();
        }

        assertEquals(1, effectiveAdminCount(), "the revoke must have committed");
    }

    @Test
    void banAdminWithoutActorGucFailsClosed() throws SQLException {
        String targetAdmin;
        try (Connection setup = newConnection()) {
            insertAdmin(setup, "guc-actor@example");
            targetAdmin = insertAdmin(setup, "guc-target@example");
        }

        try (Connection c = newConnection()) {
            // No SET infochat.actor_id — the trigger cannot tell this
            // ban from a self-ban, so it must reject.
            SQLException ex;
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE users SET is_banned = TRUE WHERE id = ?::uuid")) {
                update.setString(1, targetAdmin);
                ex = assertThrows(SQLException.class, update::executeUpdate);
            }
            assertTrue(ex.getMessage().contains("infochat.actor_id"),
                    "expected the actor-GUC rejection in: " + ex.getMessage());

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT is_banned FROM users WHERE id = '" + targetAdmin + "'")) {
                assertTrue(rs.next(), "target admin row must exist");
                assertFalse(rs.getBoolean("is_banned"), "target admin must remain unbanned");
            }
        }
    }

    @Test
    void banNonAdminWithoutActorGucStillSucceeds() throws SQLException {
        String regularUser;
        try (Connection c = newConnection()) {
            insertAdmin(c, "guc-admin@example");
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO users (adapter, contact_id, registration_state) "
                            + "VALUES (?, ?, 'invited') RETURNING id")) {
                insert.setString(1, "inmemory");
                insert.setString(2, "guc-regular@example");
                try (ResultSet rs = insert.executeQuery()) {
                    rs.next();
                    regularUser = rs.getString(1);
                }
            }

            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE users SET is_banned = TRUE WHERE id = ?::uuid")) {
                update.setString(1, regularUser);
                update.executeUpdate();
            }

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT is_banned FROM users WHERE id = '" + regularUser + "'")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("is_banned"),
                        "non-admin ban must not require the actor GUC");
            }
        }
    }

    private static int effectiveAdminCount() throws SQLException {
        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM users WHERE is_admin = TRUE AND is_banned = FALSE")) {
            rs.next();
            return rs.getInt(1);
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
