package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Schema-level assertions over the §2.2.4 {@code scope_tag} table:
 * <ul>
 *   <li>{@code scope_kind} is NOT NULL and CHECK-bounded to
 *       {@code ('dm','group')} — same Invariant 1 enforcement as
 *       {@code source_subscription}.</li>
 *   <li>The compound PRIMARY KEY (scope_kind, scope_id, tag_id)
 *       enforces dedup on /follow-tag.</li>
 *   <li>The FK to {@code tag(id)} — an INSERT with an unknown
 *       {@code tag_id} raises a foreign-key violation
 *       (SQLState 23503).</li>
 * </ul>
 *
 * <p>Each test inserts its own tag with a unique
 * {@code name} so cross-test interference is impossible (the
 * per-test-unique-identifier convention M1-008b settled on).
 */
class ScopeTagTableTest extends PostgresSchemaTestBase {

    @Test
    void insertWithoutScopeKindRaisesNotNullViolation() throws SQLException {
        try (Connection c = newConnection()) {
            String tagId = insertTag(c, uniqueTagName());

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                            + "VALUES (NULL, ?::uuid, ?::uuid)")) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, tagId);
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
            String tagId = insertTag(c, uniqueTagName());

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                            + "VALUES (?, ?::uuid, ?::uuid)")) {
                stmt.setString(1, "other");
                stmt.setString(2, UUID.randomUUID().toString());
                stmt.setString(3, tagId);
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23514", ex.getSQLState(),
                    "expected check_violation (23514), got: " + ex.getSQLState()
                            + " message: " + ex.getMessage());
        }
    }

    @Test
    void duplicateScopeKindScopeIdTagIdRaisesUniqueViolation() throws SQLException {
        try (Connection c = newConnection()) {
            String tagId = insertTag(c, uniqueTagName());
            String scopeId = UUID.randomUUID().toString();

            insertScopeTag(c, "group", scopeId, tagId);

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                            + "VALUES (?, ?::uuid, ?::uuid)")) {
                stmt.setString(1, "group");
                stmt.setString(2, scopeId);
                stmt.setString(3, tagId);
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23505", ex.getSQLState(),
                    "expected unique_violation (23505) on (scope_kind, scope_id, tag_id), got: "
                            + ex.getSQLState() + " message: " + ex.getMessage());
        }
    }

    @Test
    void unknownTagIdRaisesForeignKeyViolation() throws SQLException {
        try (Connection c = newConnection()) {
            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
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

    private static String uniqueTagName() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String insertTag(Connection c, String name) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO tag (name, display) VALUES (?, ?) RETURNING id")) {
            stmt.setString(1, name);
            stmt.setString(2, name);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insertScopeTag(Connection c, String scopeKind,
                                       String scopeId, String tagId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                        + "VALUES (?, ?::uuid, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            stmt.setString(3, tagId);
            stmt.executeUpdate();
        }
    }
}
