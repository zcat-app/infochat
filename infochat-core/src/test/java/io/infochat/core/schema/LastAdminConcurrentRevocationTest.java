package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant 2 — concurrent revocation must serialize so the
 * deployment cannot end with zero bot admins.
 *
 * <p>Two JDBC Connections each open a transaction and attempt to
 * revoke a different admin row. The {@code SHARE ROW EXCLUSIVE}
 * table-level lock at the top of the trigger function body serializes
 * the two transactions: one acquires the lock, sees the sibling admin
 * still present, and commits cleanly; the other blocks at lock
 * acquisition, then (after the first commits) re-runs the count
 * against the post-commit state, finds zero remaining admins, and
 * raises {@code last_admin_protection}. The final
 * {@code COUNT(*) WHERE is_admin = TRUE AND is_banned = FALSE} is
 * {@code 1}, not {@code 0} — the failure mode the lock prevents.
 */
class LastAdminConcurrentRevocationTest extends PostgresSchemaTestBase {

    @Test
    void concurrentRevocationOfTwoAdminsSerializesViaLockTable() throws Exception {
        String adminA;
        String adminB;
        try (Connection c = newConnection()) {
            adminA = insertAdmin(c, "alice@example");
            adminB = insertAdmin(c, "bob@example");
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readySetGo = new CountDownLatch(1);
            Future<Outcome> futureA = pool.submit(() -> tryRevoke(adminA, readySetGo));
            Future<Outcome> futureB = pool.submit(() -> tryRevoke(adminB, readySetGo));

            readySetGo.countDown();
            Outcome outcomeA = futureA.get(30, TimeUnit.SECONDS);
            Outcome outcomeB = futureB.get(30, TimeUnit.SECONDS);

            int successes = (outcomeA.committed ? 1 : 0) + (outcomeB.committed ? 1 : 0);
            assertEquals(1, successes,
                    "exactly one transaction must commit; outcomes were "
                            + outcomeA + " / " + outcomeB);

            SQLException failure = outcomeA.committed ? outcomeB.exception : outcomeA.exception;
            assertNotNull(failure, "the failing transaction must carry an SQLException");
            assertTrue(failure.getMessage().contains("last_admin_protection"),
                    "expected last_admin_protection in: " + failure.getMessage());
        } finally {
            pool.shutdownNow();
        }

        try (Connection c = newConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery(
                     "SELECT count(*) FROM users WHERE is_admin = TRUE AND is_banned = FALSE")) {
            rs.next();
            assertEquals(1, rs.getInt(1),
                    "exactly one admin must remain — the failure mode Invariant 2 forbids is 0");
        }
    }

    private static Outcome tryRevoke(String adminId, CountDownLatch readySetGo) {
        try (Connection c = newConnection()) {
            c.setAutoCommit(false);
            readySetGo.await();
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE users SET is_admin = FALSE WHERE id = ?::uuid")) {
                s.setString(1, adminId);
                s.executeUpdate();
            }
            c.commit();
            return Outcome.success();
        } catch (SQLException e) {
            return Outcome.failure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.aborted();
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

    private record Outcome(boolean committed, SQLException exception) {
        static Outcome success() {
            return new Outcome(true, null);
        }
        static Outcome failure(SQLException e) {
            return new Outcome(false, e);
        }
        static Outcome aborted() {
            return new Outcome(false, null);
        }
    }
}
