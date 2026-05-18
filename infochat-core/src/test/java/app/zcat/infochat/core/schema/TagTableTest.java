package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Schema-level assertions over the §2.2.2 {@code tag} table:
 * <ul>
 *   <li>the {@code name} CHECK regex
 *       {@code ^[a-z0-9][a-z0-9-]{0,47}$} closes the normalized
 *       storage form — uppercase letters, a leading hyphen, an
 *       over-length value, and special characters all raise a
 *       check-violation (SQLState 23514).</li>
 *   <li>a valid lower-case name inserts cleanly.</li>
 *   <li>the {@code source_origin} CHECK closes the two-value set
 *       {@code 'bootstrap' | 'user'}; an out-of-set value raises a
 *       check-violation.</li>
 * </ul>
 *
 * <p>The application-tier Java normalizer (NFC + Locale.ROOT
 * lower-case + character-class filter) is out of scope here; this
 * class exercises the storage-layer CHECK that catches a missed
 * normalizer call.
 */
class TagTableTest extends PostgresSchemaTestBase {

    @Test
    void uppercaseNameRaisesCheckViolation() {
        assertNameRejectedWithCheckViolation("Hello");
    }

    @Test
    void leadingHyphenNameRaisesCheckViolation() {
        assertNameRejectedWithCheckViolation("-leading");
    }

    @Test
    void overLengthNameRaisesCheckViolation() {
        assertNameRejectedWithCheckViolation("a".repeat(49));
    }

    @Test
    void specialCharacterNameRaisesCheckViolation() {
        assertNameRejectedWithCheckViolation("news!");
    }

    @Test
    void spaceInNameRaisesCheckViolation() {
        assertNameRejectedWithCheckViolation("news space");
    }

    @Test
    void validNameInsertsCleanly() throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "INSERT INTO tag (name, display) VALUES (?, ?)")) {
            stmt.setString(1, "news");
            stmt.setString(2, "News");
            assertEquals(1, stmt.executeUpdate(),
                    "expected one row inserted for a valid tag name");
        }
    }

    @Test
    void sourceOriginCheckRejectsOutOfSetValue() throws SQLException {
        try (Connection c = newConnection()) {
            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO tag (name, display, source_origin) VALUES (?, ?, ?)")) {
                stmt.setString(1, "news");
                stmt.setString(2, "News");
                stmt.setString(3, "imported");
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23514", ex.getSQLState(),
                    "expected check_violation (23514), got: " + ex.getSQLState()
                            + " message: " + ex.getMessage());
        }
    }

    private static void assertNameRejectedWithCheckViolation(String invalidName) {
        SQLException ex;
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "INSERT INTO tag (name, display) VALUES (?, ?)")) {
            stmt.setString(1, invalidName);
            stmt.setString(2, invalidName);
            ex = assertThrows(SQLException.class, stmt::executeUpdate);
        } catch (SQLException openEx) {
            throw new AssertionError("connection setup failed", openEx);
        }
        assertEquals("23514", ex.getSQLState(),
                "expected check_violation (23514) for name=" + invalidName
                        + ", got: " + ex.getSQLState() + " message: " + ex.getMessage());
    }
}
