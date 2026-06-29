package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts
 * {@link ProbationCheck#inProbation}'s probation-expiry gate decides
 * against that instant — not the wall-clock run date. Deterministic
 * complement to {@link ProbationCheckTest}, whose three {@code inProbation}
 * scenarios seed {@code probation_until} via {@code Instant.now() ± 1h}.
 * (M1-450)
 *
 * <p>Each case is <em>discriminating</em>: its {@code probation_until} is
 * chosen so the verdict under the pinned clock is the OPPOSITE of the verdict
 * under the wall clock. A read still gated on SQL {@code NOW()} would return
 * the wall-clock verdict and fail these assertions. The two fixed instants
 * (year 1999 and year 2100) bracket every realistic CI run date, so the
 * "opposite on the wall clock" property holds regardless of when the suite
 * runs.
 */
@QuarkusTest
class ProbationCheckClockIT {

    private static final String TEST_ADAPTER = "inmemory";

    @Inject
    ProbationCheck probationCheck;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @BeforeEach
    void cleanTestContacts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM users WHERE contact_id LIKE 'probation-clock-%'")) {
            ps.executeUpdate();
        }
    }

    @Test
    void inProbationTrueWhenExpiryAfterPinnedNow_evenThoughPastOnWallClock() throws Exception {
        // Pin "now" to the distant past; a year-2000 expiry is then in the
        // FUTURE relative to the pinned clock (still in probation) but in the
        // PAST relative to any real wall clock (would read as graduated under
        // SQL NOW()). Asserting true proves the read uses the injected Clock.
        QuarkusMock.installMockForType(
                Clock.fixed(Instant.parse("1999-01-01T00:00:00Z"), ZoneOffset.UTC), Clock.class);
        UUID id = seedUser("probation-clock-future-under-pin",
                Instant.parse("2000-01-01T00:00:00Z"));

        assertTrue(probationCheck.inProbation(id),
                "inProbation must gate on the injected Clock: probation_until is after the "
                        + "pinned now (still in probation), even though it is in the past on the wall clock");
    }

    @Test
    void inProbationFalseWhenExpiryBeforePinnedNow_evenThoughFutureOnWallClock() throws Exception {
        // Pin "now" to the distant future; a year-2099 expiry is then in the
        // PAST relative to the pinned clock (graduated) but in the FUTURE
        // relative to any real wall clock (would read as still-in-probation
        // under SQL NOW()). Asserting false proves the read uses the Clock.
        QuarkusMock.installMockForType(
                Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC), Clock.class);
        UUID id = seedUser("probation-clock-past-under-pin",
                Instant.parse("2099-01-01T00:00:00Z"));

        assertFalse(probationCheck.inProbation(id),
                "inProbation must gate on the injected Clock: probation_until is before the "
                        + "pinned now (graduated), even though it is in the future on the wall clock");
    }

    @Test
    void clearIfPromotedNullsColumnWhenGraduatedUnderPinnedClock_evenThoughActiveOnWallClock()
            throws Exception {
        // Pin "now" to the distant future; a year-2099 probation_until is then in
        // the PAST relative to the pinned clock (graduated) but in the FUTURE
        // relative to any real wall clock. The opportunistic UPDATE must clear the
        // column — proving its `<= ?` gate reads the injected Clock, not SQL
        // NOW() (which would match zero rows and leave the column set).
        QuarkusMock.installMockForType(
                Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC), Clock.class);
        UUID id = seedUser("probation-clock-clear-graduated",
                Instant.parse("2099-01-01T00:00:00Z"));

        probationCheck.clearIfPromoted(id);

        assertTrue(probationUntilIsNull(id),
                "clearIfPromoted must null probation_until for a row graduated against the "
                        + "pinned clock, even though it is still future on the wall clock");
    }

    @Test
    void clearIfPromotedLeavesColumnWhenStillProbationUnderPinnedClock_evenThoughPastOnWallClock()
            throws Exception {
        // Pin "now" to the distant past; a year-2000 probation_until is then in
        // the FUTURE relative to the pinned clock (still in probation) but in the
        // PAST on the wall clock. The UPDATE must match zero rows — proving the
        // gate reads the injected Clock, not SQL NOW() (which would null it).
        QuarkusMock.installMockForType(
                Clock.fixed(Instant.parse("1999-01-01T00:00:00Z"), ZoneOffset.UTC), Clock.class);
        UUID id = seedUser("probation-clock-clear-stillprobation",
                Instant.parse("2000-01-01T00:00:00Z"));

        probationCheck.clearIfPromoted(id);

        assertFalse(probationUntilIsNull(id),
                "clearIfPromoted must leave probation_until set for a row still in probation "
                        + "against the pinned clock, even though it is past on the wall clock");
    }

    private boolean probationUntilIsNull(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT probation_until FROM users WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "seeded users row must exist");
                rs.getTimestamp("probation_until");
                return rs.wasNull();
            }
        }
    }

    private UUID seedUser(String contactId, Instant probationUntil) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (id, adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, probation_until) "
                             + "VALUES (?, ?, ?, FALSE, FALSE, 'invited', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, contactId);
            ps.setTimestamp(4, Timestamp.from(probationUntil));
            ps.executeUpdate();
        }
        return id;
    }
}
