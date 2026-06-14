package app.zcat.infochat.provider.messaging;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ProbationCheck} against the
 * DevServices Postgres container (Flyway-applied V5 users table).
 * Six scenarios pin the two methods against the three column
 * states (in-probation / past-probation / NULL):
 *
 * <ul>
 *   <li>(a) {@link ProbationCheck#inProbation} returns true for
 *       {@code probation_until = NOW() + 1h}.</li>
 *   <li>(b) {@code inProbation} returns false for
 *       {@code probation_until = NOW() - 1h} (past — graduated).</li>
 *   <li>(c) {@code inProbation} returns false for
 *       {@code probation_until = NULL} (already promoted).</li>
 *   <li>(d) {@link ProbationCheck#clearIfPromoted} nulls the
 *       column for a past-probation row.</li>
 *   <li>(e) {@code clearIfPromoted} does NOT modify a still-
 *       in-probation row.</li>
 *   <li>(f) {@code clearIfPromoted} is a no-op for a NULL row.</li>
 * </ul>
 *
 * <p>Per-test PREFIX isolation via {@code contact_id LIKE
 * 'probation-test-%'} mirrors the BanCheckTest pattern.
 */
@QuarkusTest
class ProbationCheckTest {

    @Inject
    ProbationCheck probationCheck;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @BeforeEach
    void cleanTestContacts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM users WHERE contact_id LIKE 'probation-test-%'")) {
            ps.executeUpdate();
        }
    }

    @Test
    void inProbationTrueForFutureExpiry() throws Exception {
        UUID id = seedUser("probation-test-future", Instant.now().plus(1, ChronoUnit.HOURS));
        assertTrue(probationCheck.inProbation(id),
                "inProbation must return true for a row with probation_until > NOW()");
    }

    @Test
    void inProbationFalseForPastExpiry() throws Exception {
        UUID id = seedUser("probation-test-past", Instant.now().minus(1, ChronoUnit.HOURS));
        assertFalse(probationCheck.inProbation(id),
                "inProbation must return false for a row whose probation_until is in the past (graduated)");
    }

    @Test
    void inProbationFalseForNullColumn() throws Exception {
        UUID id = seedUser("probation-test-null", null);
        assertFalse(probationCheck.inProbation(id),
                "inProbation must return false for a row with probation_until = NULL (already promoted)");
    }

    @Test
    void clearIfPromotedNullsPastExpiryColumn() throws Exception {
        UUID id = seedUser("probation-test-clear-past", Instant.now().minus(1, ChronoUnit.HOURS));
        probationCheck.clearIfPromoted(id);
        assertNull(readProbationUntil(id),
                "clearIfPromoted must null probation_until for a past-expiry row");
    }

    @Test
    void clearIfPromotedLeavesFutureExpiryUnchanged() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        UUID id = seedUser("probation-test-clear-future", future);
        probationCheck.clearIfPromoted(id);
        Timestamp afterCall = readProbationUntil(id);
        assertNotNull(afterCall,
                "clearIfPromoted must NOT null probation_until for a still-in-probation row");
        // The WHERE clause's `<= NOW()` guard prevented the UPDATE
        // from firing; the column retains its seeded value.
    }

    @Test
    void clearIfPromotedNoOpOnNullColumn() throws Exception {
        UUID id = seedUser("probation-test-clear-null", null);
        probationCheck.clearIfPromoted(id);
        assertNull(readProbationUntil(id),
                "clearIfPromoted must remain a no-op for a row whose probation_until is already NULL");
    }

    private UUID seedUser(String contactId, Instant probationUntil) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (id, adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, probation_until) "
                             + "VALUES (?, ?, ?, FALSE, FALSE, 'invited', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "inmemory");
            ps.setString(3, contactId);
            if (probationUntil == null) {
                ps.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(4, Timestamp.from(probationUntil));
            }
            ps.executeUpdate();
        }
        return id;
    }

    private Timestamp readProbationUntil(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT probation_until FROM users WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getTimestamp("probation_until");
            }
        }
    }
}
