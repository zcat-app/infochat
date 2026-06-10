package app.zcat.infochat.core.log;

import app.zcat.infochat.core.schema.PostgresSchemaTestBase;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard between the Java contact-id redactor
 * ({@link ContactIds#redact}) and its SQL mirror
 * ({@code redact_contact_id}, defined in migration V31): the same
 * contact id must redact to the same string regardless of which layer
 * logged it, or log correlation across the Java/SQL boundary breaks
 * and the redaction contract turns ambiguous
 * (docs/design/04-security.md §4.11; docs/spec/security.md §Secrets
 * handling).
 *
 * <p>The fixture set brackets the full-mask boundary (10 → bare
 * ellipsis, 11 → 6-char prefix + ellipsis + 4-char suffix) and spans
 * empty through typical adapter-id lengths. Fixtures are ASCII by
 * design: contact ids are adapter-issued identifiers (SimpleX queue
 * addresses, Signal ACIs — decision D10), and ASCII sidesteps the
 * {@code String.length()} (UTF-16 units) vs {@code char_length}
 * (code points) divergence that no real contact id exercises.</p>
 *
 * <p>Known divergence, pinned below: SQL passes NULL through (audit
 * rows without an actor/target contact id store NULL in those
 * columns) while the Java helper returns {@link ContactIds#NULL_SENTINEL}
 * so an SLF4J pattern never emits the literal string {@code "null"}.
 * Parity is over the string domain only.</p>
 *
 * <p>Lives in {@code app.zcat.infochat.core.log} alongside
 * {@link RedactorSqlParityIT}; reuses {@link PostgresSchemaTestBase}
 * for the Testcontainers Postgres with all Flyway migrations applied.
 * The {@code *IT} suffix routes it through maven-failsafe under
 * {@code mvn verify}.</p>
 */
class ContactIdsSqlParityIT extends PostgresSchemaTestBase {

    private static final List<String> FIXTURES = List.of(
            "",                                                   // 0
            "a",                                                  // 1
            "alice-q12",                                          // 9
            "alice-q123",                                         // 10 — masked boundary
            "alice-q1234",                                        // 11 — redacted boundary
            "alice-q12345",                                       // 12
            "alice-q12345678",                                    // 15
            "0123456789ABCDEF",                                   // 16 — old Java threshold
            "alice-simplex-queue-1234567890abcdef-fingerprint");  // 48 — typical

    /**
     * Fast canary: a test profile that silently stopped migrating
     * before V31 would make the SQL-side assertions exercise the V5
     * RETURN-input stub instead of the real redaction policy.
     */
    @Test
    void migrationCursorReachesV31() throws SQLException {
        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version FROM flyway_schema_history "
                             + "WHERE success = TRUE "
                             + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(rs.next(), "expected at least one applied Flyway version");
            int maxVersion = Integer.parseInt(rs.getString("version"));
            assertTrue(maxVersion >= 31,
                    "expected migration cursor at V31 or later, got V" + maxVersion);
        }
    }

    @Test
    void everyFixtureRedactsIdenticallyOnBothEngines() throws SQLException {
        for (String fixture : FIXTURES) {
            assertEquals(ContactIds.redact(fixture), sqlRedactContactId(fixture),
                    "ContactIds.redact and redact_contact_id diverged for "
                            + fixture.length() + "-char input \"" + fixture + "\"");
        }
    }

    /** Pins the deliberate null divergence documented in the class javadoc. */
    @Test
    void nullHandlingDivergesByDesign() throws SQLException {
        assertEquals(ContactIds.NULL_SENTINEL, ContactIds.redact(null),
                "Java side must keep the SLF4J-safe null sentinel");
        assertNull(sqlRedactContactId(null),
                "SQL side must pass NULL through for nullable audit columns");
    }

    private static @Nullable String sqlRedactContactId(@Nullable String value) throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT redact_contact_id(?::text)")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "redact_contact_id returned no row");
                return rs.getString(1);
            }
        }
    }
}
