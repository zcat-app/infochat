package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts the two
 * Java-side decision reads M1-447 converted in {@link InviteCodeConsumer}
 * decide against that instant: the {@code probation_until} write and the
 * brute-force-window attempt count. Deterministic complement to
 * {@link InviteCodeConsumerTest}, whose fixtures are relative to the real
 * clock.
 *
 * <p>The SQL {@code expires_at > NOW()} invite-expiry gate is intentionally
 * NOT asserted here — it stays on the DB clock (intra-statement comparison)
 * per docs/plan/m1/now-clock-audit.md, so a pinned Java clock would not
 * govern it. {@code InviteCodeConsumerTest.rejectedExpired} covers expiry
 * against the DB clock.
 */
@QuarkusTest
class InviteCodeConsumerClockIT {

    private static final String NAMESPACE = "invite-clock-test-";
    private static final Instant PINNED_NOW = Instant.parse("2026-05-25T09:00:00Z");

    @Inject
    InviteCodeConsumer consumer;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @ConfigProperty(name = "infochat.invite.brute-force-threshold", defaultValue = "10")
    int bruteForceThreshold;

    @ConfigProperty(name = "infochat.invite.brute-force-window", defaultValue = "1h")
    Duration bruteForceWindow;

    @ConfigProperty(name = "infochat.probation.duration", defaultValue = "24h")
    Duration probationDuration;

    private UUID creatorUserId;

    @BeforeEach
    void pinClockAndSeedCreator() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);

        executeUpdate("DELETE FROM invite_code "
            + "WHERE created_by IN (SELECT id FROM users WHERE contact_id LIKE '" + NAMESPACE + "%')");
        executeUpdate("DELETE FROM invite_code_attempt WHERE contact_id LIKE '" + NAMESPACE + "%'");
        executeUpdate("DELETE FROM users WHERE contact_id LIKE '" + NAMESPACE + "%'");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                     + "VALUES ('inmemory', '" + NAMESPACE + "creator', FALSE, 'vouched') "
                     + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                creatorUserId = rs.getObject(1, UUID.class);
            }
        }
    }

    @Test
    void probationUntilWrite_usesInjectedClock() throws Exception {
        String adapter = "inmemory";
        String contactId = NAMESPACE + "probation-write";
        UUID code = UUID.randomUUID();
        seedPendingInvite(code, adapter, contactId);

        InviteCodeConsumer.Outcome outcome = consumer.consume(adapter, contactId, code.toString());

        assertInstanceOf(InviteCodeConsumer.Accepted.class, outcome);
        // The new users row's probation_until must be PINNED_NOW + probationDuration
        // exactly — proving the write sampled the injected Clock, not Instant.now()
        // (which would land at the wall-clock date instead).
        assertEquals(PINNED_NOW.plus(probationDuration), readProbationUntil(adapter, contactId),
            "probation_until must be the injected now + probation duration");
    }

    @Test
    void bruteForceWindowCount_usesInjectedClock() throws Exception {
        String adapter = "inmemory";
        String contactId = NAMESPACE + "window-count";
        // Seed exactly `threshold` attempts INSIDE the window relative to the
        // pinned clock (halfway into the window). These instants sit on
        // 2026-05-25, so under the REAL wall clock they are far older than the
        // window and would NOT be counted — making a breach observable only if
        // the count reads the injected Clock.
        Instant insideWindow = PINNED_NOW.minus(bruteForceWindow.dividedBy(2));
        for (int i = 0; i < bruteForceThreshold; i++) {
            seedAttempt(adapter, contactId, insideWindow);
        }

        // count == threshold ≥ threshold → BruteForceThresholdBreached.
        InviteCodeConsumer.Outcome outcome =
            consumer.consume(adapter, contactId, UUID.randomUUID().toString());

        assertInstanceOf(InviteCodeConsumer.BruteForceThresholdBreached.class, outcome,
            "attempts dated inside the window relative to the injected now must be "
                + "counted, tripping the brute-force threshold; under the wall clock "
                + "they would be outside the window and the consume would be Rejected");
    }

    private void seedPendingInvite(UUID code, String adapter, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO invite_code "
                     + "(code, invite_type, adapter, expected_contact_id, status, "
                     + "created_by, expires_at, used_at, used_by_contact_id) "
                     + "VALUES (?, 'CONTACT_BOUND', ?, ?, 'PENDING', ?, NULL, NULL, NULL)")) {
            ps.setObject(1, code);
            ps.setString(2, adapter);
            ps.setString(3, contactId);
            ps.setObject(4, creatorUserId);
            ps.executeUpdate();
        }
    }

    private void seedAttempt(String adapter, String contactId, Instant attemptedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO invite_code_attempt (adapter, contact_id, attempted_at) "
                     + "VALUES (?, ?, ?)")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setTimestamp(3, Timestamp.from(attemptedAt));
            ps.executeUpdate();
        }
    }

    private Instant readProbationUntil(String adapter, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT probation_until FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must exist after Accepted consume");
                return rs.getTimestamp("probation_until").toInstant();
            }
        }
    }

    private void executeUpdate(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
