package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-level assertions over the §2.2.1 {@code source} table:
 * <ul>
 *   <li>the {@code (kind, identifier)} UNIQUE constraint (decision
 *       D38) — the upsert key the bootstrap loader and /add-source
 *       target; a second INSERT with the same pair raises a
 *       unique-violation (SQLState 23505).</li>
 *   <li>the soft-delete column {@code deleted_at} round-trips
 *       (Invariant 4 — sources are never hard-deleted by the
 *       service roles).</li>
 *   <li>the {@code status} CHECK closes the three-value set
 *       {@code 'active' | 'failed' | 'disabled'}; an out-of-set
 *       value raises a check-violation (SQLState 23514).</li>
 *   <li>the partial activity index {@code idx_source_status} exists
 *       with the expected WHERE predicate; the fetcher / stream-
 *       worker scheduler reads against this index.</li>
 * </ul>
 *
 * <p>Tests run as the bootstrap superuser (via {@link
 * PostgresSchemaTestBase#newConnection()}), so the application-role
 * GRANT matrix is bypassed — these assertions exercise the
 * table-level constraints directly. Cross-role grant exercise is a
 * separate Tier-1 task.
 */
class SourceTableTest extends PostgresSchemaTestBase {

    @Test
    void secondInsertWithSameKindAndIdentifierRaisesUniqueViolation() throws SQLException {
        try (Connection c = newConnection()) {
            insertSource(c, "rss", "https://example.com/feed", "Example", "news");

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO source (kind, identifier, display_name, category) "
                            + "VALUES (?, ?, ?, ?)")) {
                stmt.setString(1, "rss");
                stmt.setString(2, "https://example.com/feed");
                stmt.setString(3, "Example dup");
                stmt.setString(4, "news");
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23505", ex.getSQLState(),
                    "expected unique_violation (23505), got: " + ex.getSQLState()
                            + " message: " + ex.getMessage());
        }
    }

    @Test
    void softDeleteColumnRoundTrips() throws SQLException {
        try (Connection c = newConnection()) {
            String id = insertSource(c, "rss", "https://example.com/soft", "Example", "news");

            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE source SET deleted_at = now() WHERE id = ?::uuid")) {
                upd.setString(1, id);
                upd.executeUpdate();
            }

            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT deleted_at FROM source WHERE id = ?::uuid")) {
                sel.setString(1, id);
                try (ResultSet rs = sel.executeQuery()) {
                    assertTrue(rs.next(), "expected one row");
                    assertNotNull(rs.getTimestamp("deleted_at"),
                            "deleted_at should round-trip a non-null timestamp");
                }
            }
        }
    }

    @Test
    void statusCheckRejectsUnknownValue() throws SQLException {
        try (Connection c = newConnection()) {
            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO source (kind, identifier, display_name, category, status) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                stmt.setString(1, "rss");
                stmt.setString(2, "https://example.com/bad-status");
                stmt.setString(3, "Example");
                stmt.setString(4, "news");
                stmt.setString(5, "paused");
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23514", ex.getSQLState(),
                    "expected check_violation (23514), got: " + ex.getSQLState()
                            + " message: " + ex.getMessage());
        }
    }

    @Test
    void partialActivityIndexExists() throws SQLException {
        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT indexdef FROM pg_indexes "
                             + "WHERE schemaname = 'public' "
                             + "AND tablename = 'source' "
                             + "AND indexname = 'idx_source_status'")) {
            assertTrue(rs.next(), "expected idx_source_status to exist on source");
            String def = rs.getString("indexdef");
            assertTrue(def.contains("(status)"),
                    "expected index on (status), got: " + def);
            assertTrue(def.toLowerCase().contains("where (deleted_at is null)"),
                    "expected partial WHERE deleted_at IS NULL, got: " + def);
        }
    }

    private static String insertSource(Connection c, String kind, String identifier,
                                       String displayName, String category) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            stmt.setString(1, kind);
            stmt.setString(2, identifier);
            stmt.setString(3, displayName);
            stmt.setString(4, category);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
