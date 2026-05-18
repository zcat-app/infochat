package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-level assertions over the §2.2.5 {@code scope_preferences}
 * table:
 * <ul>
 *   <li>{@code language} defaults to {@code 'en'} when not supplied
 *       on INSERT.</li>
 *   <li>{@code tag_mode} CHECK closes the two-value set
 *       {@code ('ALL','EXPLICIT')}; an out-of-set value raises a
 *       check-violation (SQLState 23514).</li>
 *   <li>Both {@code tag_subscription_version} and
 *       {@code source_subscription_version} default to {@code 0}.</li>
 *   <li>An UPDATE that increments either counter monotonically
 *       succeeds — the application-tier increment pattern the
 *       /follow-tag / /add-source mutators will use.</li>
 *   <li>The PRIMARY KEY (scope_kind, scope_id) rejects a second
 *       INSERT with the same pair (SQLState 23505).</li>
 * </ul>
 *
 * <p>Each test uses a fresh {@code scope_id} so concurrent test
 * methods cannot collide.
 */
class ScopePreferencesTableTest extends PostgresSchemaTestBase {

    @Test
    void languageDefaultsToEnglish() throws SQLException {
        try (Connection c = newConnection()) {
            String scopeId = UUID.randomUUID().toString();
            insertPreferences(c, "dm", scopeId);

            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT language FROM scope_preferences "
                            + "WHERE scope_kind = 'dm' AND scope_id = ?::uuid")) {
                sel.setString(1, scopeId);
                try (ResultSet rs = sel.executeQuery()) {
                    assertTrue(rs.next(), "expected one row");
                    assertEquals("en", rs.getString("language"),
                            "language column should default to 'en'");
                }
            }
        }
    }

    @Test
    void tagModeRejectsUnknownValue() throws SQLException {
        try (Connection c = newConnection()) {
            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                            + "VALUES ('group', ?::uuid, ?)")) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, "BOTH");
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23514", ex.getSQLState(),
                    "expected check_violation (23514) for tag_mode='BOTH', got: "
                            + ex.getSQLState() + " message: " + ex.getMessage());
        }
    }

    @Test
    void subscriptionVersionCountersDefaultToZeroAndIncrementMonotonically() throws SQLException {
        try (Connection c = newConnection()) {
            String scopeId = UUID.randomUUID().toString();
            insertPreferences(c, "group", scopeId);

            assertCounters(c, scopeId, 0L, 0L);

            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE scope_preferences "
                            + "SET tag_subscription_version    = tag_subscription_version + 1, "
                            + "    source_subscription_version = source_subscription_version + 2 "
                            + "WHERE scope_kind = 'group' AND scope_id = ?::uuid")) {
                upd.setString(1, scopeId);
                assertEquals(1, upd.executeUpdate(), "expected one row updated");
            }

            assertCounters(c, scopeId, 1L, 2L);

            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE scope_preferences "
                            + "SET tag_subscription_version    = tag_subscription_version + 5 "
                            + "WHERE scope_kind = 'group' AND scope_id = ?::uuid")) {
                upd.setString(1, scopeId);
                upd.executeUpdate();
            }

            assertCounters(c, scopeId, 6L, 2L);
        }
    }

    @Test
    void duplicateScopeKindScopeIdRaisesUniqueViolation() throws SQLException {
        try (Connection c = newConnection()) {
            String scopeId = UUID.randomUUID().toString();
            insertPreferences(c, "dm", scopeId);

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO scope_preferences (scope_kind, scope_id) "
                            + "VALUES ('dm', ?::uuid)")) {
                stmt.setString(1, scopeId);
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23505", ex.getSQLState(),
                    "expected unique_violation (23505) on PK (scope_kind, scope_id), got: "
                            + ex.getSQLState() + " message: " + ex.getMessage());
        }
    }

    private static void insertPreferences(Connection c, String scopeKind,
                                          String scopeId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO scope_preferences (scope_kind, scope_id) "
                        + "VALUES (?, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            stmt.executeUpdate();
        }
    }

    private static void assertCounters(Connection c, String scopeId,
                                       long expectedTagVersion,
                                       long expectedSourceVersion) throws SQLException {
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT tag_subscription_version, source_subscription_version "
                        + "FROM scope_preferences WHERE scope_id = ?::uuid")) {
            sel.setString(1, scopeId);
            try (ResultSet rs = sel.executeQuery()) {
                assertTrue(rs.next(), "expected one row");
                assertEquals(expectedTagVersion, rs.getLong("tag_subscription_version"));
                assertEquals(expectedSourceVersion, rs.getLong("source_subscription_version"));
            }
        }
    }
}
