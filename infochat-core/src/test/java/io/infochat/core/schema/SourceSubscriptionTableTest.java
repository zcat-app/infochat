package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-level assertions over the §2.2.3 {@code source_subscription}
 * table:
 * <ul>
 *   <li>{@code scope_kind} is NOT NULL — an INSERT without a value
 *       raises a not-null violation (SQLState 23502).</li>
 *   <li>{@code scope_kind} is CHECK-bounded to {@code ('dm','group')}
 *       — an out-of-set value raises a check-violation
 *       (SQLState 23514).</li>
 *   <li>The compound PRIMARY KEY (scope_kind, scope_id, source_id) —
 *       Invariant 1's schema-level enforcement plus dedup. A second
 *       INSERT with the same triple raises a unique-violation
 *       (SQLState 23505).</li>
 *   <li>The FK to {@code source(id)} — an INSERT with an unknown
 *       {@code source_id} raises a foreign-key violation
 *       (SQLState 23503).</li>
 *   <li>The reverse-lookup index {@code idx_source_sub_source}
 *       exists; the Collector's fan-out path scans it.</li>
 * </ul>
 *
 * <p>Each test seeds its own source row with a unique
 * {@code (kind, identifier)} pair so concurrent invocations and
 * incomplete TRUNCATE coverage in {@link PostgresSchemaTestBase}
 * cannot collide — the per-test-unique-identifier convention M1-008b
 * settled on.
 */
class SourceSubscriptionTableTest extends PostgresSchemaTestBase {

    @Test
    void insertWithoutScopeKindRaisesNotNullViolation() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c, "rss-ss-null-" + UUID.randomUUID());

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "VALUES (NULL, ?::uuid, ?::uuid)")) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, sourceId);
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23502", ex.getSQLState(),
                    "expected not_null_violation (23502), got: " + ex.getSQLState()
                            + " message: " + ex.getMessage());
        }
    }

    @Test
    void scopeKindOutOfSetRaisesCheckViolation() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c, "rss-ss-check-" + UUID.randomUUID());

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "VALUES (?, ?::uuid, ?::uuid)")) {
                stmt.setString(1, "other");
                stmt.setString(2, UUID.randomUUID().toString());
                stmt.setString(3, sourceId);
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23514", ex.getSQLState(),
                    "expected check_violation (23514), got: " + ex.getSQLState()
                            + " message: " + ex.getMessage());
        }
    }

    @Test
    void duplicateScopeKindScopeIdSourceIdRaisesUniqueViolation() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c, "rss-ss-pk-" + UUID.randomUUID());
            String scopeId = UUID.randomUUID().toString();

            insertSubscription(c, "dm", scopeId, sourceId);

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "VALUES (?, ?::uuid, ?::uuid)")) {
                stmt.setString(1, "dm");
                stmt.setString(2, scopeId);
                stmt.setString(3, sourceId);
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23505", ex.getSQLState(),
                    "expected unique_violation (23505) on (scope_kind, scope_id, source_id), got: "
                            + ex.getSQLState() + " message: " + ex.getMessage());
        }
    }

    @Test
    void unknownSourceIdRaisesForeignKeyViolation() throws SQLException {
        try (Connection c = newConnection()) {
            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "VALUES (?, ?::uuid, ?::uuid)")) {
                stmt.setString(1, "dm");
                stmt.setString(2, UUID.randomUUID().toString());
                stmt.setString(3, UUID.randomUUID().toString());
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23503", ex.getSQLState(),
                    "expected foreign_key_violation (23503), got: " + ex.getSQLState()
                            + " message: " + ex.getMessage());
        }
    }

    @Test
    void reverseLookupIndexExists() throws SQLException {
        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT indexdef FROM pg_indexes "
                             + "WHERE schemaname = 'public' "
                             + "AND tablename = 'source_subscription' "
                             + "AND indexname = 'idx_source_sub_source'")) {
            assertTrue(rs.next(),
                    "expected idx_source_sub_source to exist on source_subscription");
            String def = rs.getString("indexdef");
            assertTrue(def.contains("(source_id)"),
                    "expected index on (source_id), got: " + def);
        }
    }

    private static String insertSource(Connection c, String identifier) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            stmt.setString(1, "rss");
            stmt.setString(2, identifier);
            stmt.setString(3, "Test source " + identifier);
            stmt.setString(4, "news");
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insertSubscription(Connection c, String scopeKind,
                                           String scopeId, String sourceId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                        + "VALUES (?, ?::uuid, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            stmt.setString(3, sourceId);
            stmt.executeUpdate();
        }
    }
}
