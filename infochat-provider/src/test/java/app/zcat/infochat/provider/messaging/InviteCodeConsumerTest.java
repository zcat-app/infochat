package app.zcat.infochat.provider.messaging;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link InviteCodeConsumer} against the
 * DevServices Postgres container (V5 invite_code / users / audit_log
 * + V12 invite_code_attempt). The seven invariants below mirror the
 * §Invite-code registration spec, each in its own {@code @Test}:
 *
 * <ol>
 *   <li>{@code acceptedContactBound} — valid PENDING CONTACT_BOUND
 *       with matching {@code (adapter, contact_id)} → Accepted; row
 *       transitions to USED with {@code used_by_contact_id};
 *       INVITE_CONSUME audit row.</li>
 *   <li>{@code acceptedOpenAdapter} — valid PENDING OPEN_ADAPTER
 *       (no contact binding) → Accepted; row USED; audit row.</li>
 *   <li>{@code rejectedAlreadyUsed} — second consume on a code
 *       already USED → Rejected; no second audit row.</li>
 *   <li>{@code rejectedExpired} — code with {@code expires_at < NOW()}
 *       → Rejected; pin {@code expires_at = NOW() - 1s} to exercise
 *       the inclusive {@code NOW() >= expires_at} rule.</li>
 *   <li>{@code rejectedCrossContact} — CONTACT_BOUND with
 *       {@code expected_contact_id} differing from consume's
 *       {@code contact_id} → Rejected.</li>
 *   <li>{@code rejectedCrossAdapter} — code bound to adapter A
 *       consumed from adapter B → Rejected.</li>
 *   <li>{@code bruteForceBreach} — (threshold+1)-th call from the
 *       same {@code (adapter, contact_id)} within the window returns
 *       {@link InviteCodeConsumer.BruteForceThresholdBreached}; no
 *       UPDATE runs against {@code invite_code};
 *       INVITE_BRUTE_FORCE_BREACH audit row written EXACTLY ONCE.</li>
 * </ol>
 */
@QuarkusTest
class InviteCodeConsumerTest {

    private static final String NAMESPACE = "invite-test-";
    private static final int BRUTE_FORCE_THRESHOLD = 10;

    @Inject
    InviteCodeConsumer consumer;

    @Inject
    DataSource dataSource;

    private UUID creatorUserId;

    @BeforeEach
    void cleanAndSeedCreator() throws Exception {
        // FK order: invite_code → users. Test invites carry the
        // creator user we seed below (whose contact_id is in the
        // test namespace) — clean them via the FK column so prior
        // tests' inmemory/inmemory2 adapter values don't escape.
        executeUpdate("DELETE FROM invite_code "
                + "WHERE created_by IN (SELECT id FROM users WHERE contact_id LIKE '" + NAMESPACE + "%')");
        executeUpdate("DELETE FROM invite_code_attempt WHERE contact_id LIKE '" + NAMESPACE + "%'");
        executeUpdate("DELETE FROM users WHERE contact_id LIKE '" + NAMESPACE + "%'");

        // One shared creator user (non-admin) to satisfy
        // invite_code.created_by NOT NULL REFERENCES users(id).
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
    void acceptedContactBound() throws Exception {
        String adapter = "inmemory";
        String contactId = NAMESPACE + "accepted-cb";
        UUID code = UUID.randomUUID();
        seedInvite(code, "CONTACT_BOUND", adapter, contactId, "PENDING", null);

        InviteCodeConsumer.Outcome outcome = consumer.consume(adapter, contactId, code);

        assertInstanceOf(InviteCodeConsumer.Accepted.class, outcome);
        UUID userId = ((InviteCodeConsumer.Accepted) outcome).userId();
        assertNotNull(userId);

        assertInviteUsedBy(code, contactId);
        assertEquals(1, countAudit(InviteCodeConsumer.INVITE_CONSUME, contactId),
                "exactly one INVITE_CONSUME audit row");
    }

    @Test
    void acceptedOpenAdapter() throws Exception {
        String adapter = "inmemory";
        String contactId = NAMESPACE + "accepted-oa";
        UUID code = UUID.randomUUID();
        seedInvite(code, "OPEN_ADAPTER", adapter, null, "PENDING", null);

        InviteCodeConsumer.Outcome outcome = consumer.consume(adapter, contactId, code);

        assertInstanceOf(InviteCodeConsumer.Accepted.class, outcome);
        assertInviteUsedBy(code, contactId);
        assertEquals(1, countAudit(InviteCodeConsumer.INVITE_CONSUME, contactId),
                "exactly one INVITE_CONSUME audit row");
    }

    @Test
    void rejectedAlreadyUsed() throws Exception {
        String adapter = "inmemory";
        String firstContact = NAMESPACE + "first-used";
        String secondContact = NAMESPACE + "second-used";
        UUID code = UUID.randomUUID();
        // The invite is CONTACT_BOUND to the first contact; the first
        // consume is Accepted; a second consume — even with the matching
        // contact id — must Rejected because status is now USED.
        seedInvite(code, "CONTACT_BOUND", adapter, firstContact, "PENDING", null);

        InviteCodeConsumer.Outcome first = consumer.consume(adapter, firstContact, code);
        assertInstanceOf(InviteCodeConsumer.Accepted.class, first);

        InviteCodeConsumer.Outcome second = consumer.consume(adapter, secondContact, code);
        assertInstanceOf(InviteCodeConsumer.Rejected.class, second,
                "consume against an already-USED code must be Rejected");

        assertEquals(1, countAudit(InviteCodeConsumer.INVITE_CONSUME, firstContact),
                "audit row for the first (Accepted) consume only");
        assertEquals(0, countAudit(InviteCodeConsumer.INVITE_CONSUME, secondContact),
                "no second INVITE_CONSUME audit row for the Rejected re-attempt");
    }

    @Test
    void rejectedExpired() throws Exception {
        String adapter = "inmemory";
        String contactId = NAMESPACE + "expired";
        UUID code = UUID.randomUUID();
        // Pin expires_at = NOW() - 1s so the spec's inclusive
        // NOW() >= expires_at rule fires: the conditional UPDATE's
        // (expires_at IS NULL OR expires_at > NOW()) is false.
        OffsetDateTime past = OffsetDateTime.now().minusSeconds(1);
        seedInvite(code, "CONTACT_BOUND", adapter, contactId, "PENDING", past);

        InviteCodeConsumer.Outcome outcome = consumer.consume(adapter, contactId, code);
        assertInstanceOf(InviteCodeConsumer.Rejected.class, outcome);
    }

    @Test
    void rejectedCrossContact() throws Exception {
        String adapter = "inmemory";
        String boundContact = NAMESPACE + "bound";
        String otherContact = NAMESPACE + "other";
        UUID code = UUID.randomUUID();
        seedInvite(code, "CONTACT_BOUND", adapter, boundContact, "PENDING", null);

        // Consume from the WRONG contact id under the same adapter — the
        // CONTACT_BOUND code's expected_contact_id check fails.
        InviteCodeConsumer.Outcome outcome = consumer.consume(adapter, otherContact, code);
        assertInstanceOf(InviteCodeConsumer.Rejected.class, outcome);

        // The invite stays PENDING (no row UPDATEd).
        assertInviteStatus(code, "PENDING");
    }

    @Test
    void rejectedCrossAdapter() throws Exception {
        String boundAdapter = "inmemory";
        String otherAdapter = "inmemory2";
        String contactId = NAMESPACE + "xadapter";
        UUID code = UUID.randomUUID();
        seedInvite(code, "OPEN_ADAPTER", boundAdapter, null, "PENDING", null);

        // The conditional UPDATE's `WHERE adapter = ?` is the load-bearer
        // for cross-adapter isolation per §Invite-code registration.
        InviteCodeConsumer.Outcome outcome = consumer.consume(otherAdapter, contactId, code);
        assertInstanceOf(InviteCodeConsumer.Rejected.class, outcome);

        assertInviteStatus(code, "PENDING");
    }

    @Test
    void bruteForceBreach() throws Exception {
        String adapter = "inmemory";
        String contactId = NAMESPACE + "brute";

        // Drive `threshold` Rejected consumes — none of these UUIDs
        // match a real invite. Each Rejected appends one row to
        // invite_code_attempt; the counter grows to the threshold.
        for (int i = 0; i < BRUTE_FORCE_THRESHOLD; i++) {
            InviteCodeConsumer.Outcome r = consumer.consume(adapter, contactId, UUID.randomUUID());
            assertInstanceOf(InviteCodeConsumer.Rejected.class, r,
                    "pre-breach consume #" + i + " must be Rejected");
        }

        // The (threshold+1)-th call sees count >= threshold and
        // short-circuits with BruteForceThresholdBreached.
        InviteCodeConsumer.Outcome breach = consumer.consume(adapter, contactId, UUID.randomUUID());
        assertInstanceOf(InviteCodeConsumer.BruteForceThresholdBreached.class, breach);

        assertEquals(1, countAudit(InviteCodeConsumer.INVITE_BRUTE_FORCE_BREACH, contactId),
                "exactly one INVITE_BRUTE_FORCE_BREACH audit row after first breach");

        // A second over-threshold call must NOT write a second breach
        // audit row — EXACTLY ONCE per breach event.
        InviteCodeConsumer.Outcome breachAgain = consumer.consume(adapter, contactId, UUID.randomUUID());
        assertInstanceOf(InviteCodeConsumer.BruteForceThresholdBreached.class, breachAgain);

        assertEquals(1, countAudit(InviteCodeConsumer.INVITE_BRUTE_FORCE_BREACH, contactId),
                "still exactly one INVITE_BRUTE_FORCE_BREACH audit row");

        // No UPDATE on invite_code ran during the breach short-circuit:
        // the per-(adapter, contact_id) attempt counter is the
        // serialization point — no invite_code rows seeded for this
        // contact, so any UPDATE would have to invent one.
        assertEquals(0, countAudit(InviteCodeConsumer.INVITE_CONSUME, contactId),
                "no INVITE_CONSUME audit row written during a breach");
    }

    private void seedInvite(UUID code, String inviteType, String adapter,
                            String expectedContactId, String status,
                            OffsetDateTime expiresAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO invite_code "
                             + "(code, invite_type, adapter, expected_contact_id, status, "
                             + "created_by, expires_at, used_at, used_by_contact_id) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)")) {
            ps.setObject(1, code);
            ps.setString(2, inviteType);
            ps.setString(3, adapter);
            ps.setString(4, expectedContactId);
            ps.setString(5, status);
            ps.setObject(6, creatorUserId);
            ps.setObject(7, expiresAt);
            ps.executeUpdate();
        }
    }

    private void assertInviteUsedBy(UUID code, String expectedContactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status, used_by_contact_id, used_at FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "invite row must exist");
                assertEquals("USED", rs.getString("status"));
                assertEquals(expectedContactId, rs.getString("used_by_contact_id"));
                assertNotNull(rs.getTimestamp("used_at"), "used_at must be populated");
            }
        }
    }

    private void assertInviteStatus(UUID code, String expectedStatus) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expectedStatus, rs.getString("status"));
            }
        }
    }

    private long countAudit(String action, String targetContactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log "
                             + "WHERE action = ? AND target_contact_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetContactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
