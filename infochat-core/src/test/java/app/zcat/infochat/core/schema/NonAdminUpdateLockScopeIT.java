package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V40 — the last-admin {@code SHARE ROW EXCLUSIVE} table lock is
 * scoped to admin-relevant transitions; a users-row UPDATE that
 * touches neither {@code is_admin} nor {@code is_banned} must not
 * serialize against other user-row writes.
 *
 * <p>One connection holds a plain {@code last_seen_at} update open in
 * an uncommitted transaction; a second connection then runs the same
 * kind of update against a different row. Before V40 the second
 * UPDATE blocked: the trigger took {@code LOCK TABLE users IN SHARE
 * ROW EXCLUSIVE MODE} on every row update, and SHARE ROW EXCLUSIVE
 * conflicts with the second UPDATE's ROW EXCLUSIVE. After V40 the
 * lock is taken only inside the admin-count branch, so the second
 * UPDATE commits while the first transaction is still open.
 *
 * <p>The holder commits only after the prober returns on the same
 * thread, so a regression to the global lock cannot pass slowly — it
 * blocks until the prober's {@code statement_timeout} converts the
 * hang into a deterministic failure.
 */
class NonAdminUpdateLockScopeIT extends PostgresSchemaTestBase {

    @Test
    void nonAdminUpdateDoesNotBlockConcurrentNonAdminUpdateOnAnotherRow() throws Exception {
        String userA;
        String userB;
        try (Connection setup = newConnection()) {
            userA = insertUser(setup, "carol@example");
            userB = insertUser(setup, "dave@example");
        }

        try (Connection holder = newConnection(); Connection prober = newConnection()) {
            holder.setAutoCommit(false);
            touchLastSeen(holder, userA);

            try (Statement s = prober.createStatement()) {
                s.execute("SET statement_timeout = '5s'");
            }
            // autoCommit connection: returning at all means the UPDATE
            // committed while the holder's transaction was still open.
            touchLastSeen(prober, userB);

            holder.commit();
        }

        try (Connection check = newConnection();
             Statement s = check.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM users WHERE last_seen_at IS NOT NULL")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "both non-admin updates must have committed");
        }
    }

    private static void touchLastSeen(Connection c, String userId) throws SQLException {
        try (PreparedStatement update = c.prepareStatement(
                "UPDATE users SET last_seen_at = now() WHERE id = ?::uuid")) {
            update.setString(1, userId);
            update.executeUpdate();
        }
    }

    private static String insertUser(Connection c, String contactId) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, registration_state) "
                        + "VALUES (?, ?, 'invited') RETURNING id")) {
            insert.setString(1, "inmemory");
            insert.setString(2, contactId);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
