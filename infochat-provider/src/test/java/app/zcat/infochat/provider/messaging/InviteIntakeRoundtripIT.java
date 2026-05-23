package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-044 umbrella IT — the invite/ban/unban DM-gate roundtrip.
 *
 * <p>M1-044a's per-service tests, M1-044b's InboundRouterIntakeOrderingTest,
 * and M1-044c's per-handler tests each verify a slice in isolation. None
 * walks the full intake-step ordering through the real services + the
 * real adapter to the user-observable spec contract. This IT does, end
 * to end, in a single seven-step narrative through {@link InMemoryAdapter}:
 *
 * <ol>
 *   <li>(a) unknown DM without invite → {@code error.invite.required},
 *       no users row, no INVITE_CONSUME audit row.</li>
 *   <li>(b) bot-admin {@code /invite create --adapter inmemory --contact <id>}
 *       → reply contains the new code UUID; one PENDING
 *       {@code invite_code} row; one INVITE_CREATE audit row.</li>
 *   <li>(c) DM-with-code → welcome reply matching
 *       {@code reply.welcome.dm_fresh}; one {@code users} row with
 *       {@code registration_state='invited'} + non-null
 *       {@code probation_until}; the invite row transitions to USED;
 *       one INVITE_CONSUME audit row.</li>
 *   <li>(d) bot-admin {@code /ban} against an unregistered contact
 *       (with a separate pre-existing PENDING invite for the same
 *       contact) → preban row with {@code is_banned=true}; the
 *       pre-existing PENDING invite transitions to REVOKED in the
 *       same transaction; one BAN audit row. {@code /ban} is
 *       confirm-gated per M1-051, so the IT drives the prompt with
 *       {@code /ban <target>} and the completion with
 *       {@code /ban confirm}.</li>
 *   <li>(e) bot-admin {@code /unban} against the preban row → reply
 *       matches {@code reply.unban.preban_deleted}; the
 *       {@code users} row is deleted via the V5
 *       {@code delete_preban_user} procedure; one
 *       UNBAN_PREBAN_DELETE audit row (the procedure writes audit-
 *       before-effect).</li>
 *   <li>(f) post-unban DM from the same contact → fixed
 *       {@code error.invite.required} reply, no users row — i.e.
 *       {@code /unban} did NOT silently bypass the invite gate.</li>
 *   <li>(g) banned registered user (an {@code invited} row with
 *       {@code is_banned=true} set via direct UPDATE) sending
 *       {@code /help} → fixed {@code error.ban.fixed} reply,
 *       {@code sentMessages()} grows by exactly 1 (no {@code /help}
 *       reply).</li>
 * </ol>
 *
 * <p>The bot-admin row is seeded via raw JDBC at {@link BeforeEach}
 * (the {@code @Startup} bootstrap-admin bean is deferred per T1-E).
 * A permanent {@code guardian} admin row remains across tests so the
 * V5 last-admin-protection trigger does not refuse per-test DELETEs
 * on admin rows (the same pattern {@code ConfirmFlowIT} uses).</p>
 */
@QuarkusTest
@TestProfile(InviteIntakeRoundtripIT.RoundtripProfile.class)
class InviteIntakeRoundtripIT {

    private static final String ADAPTER = "inmemory";
    private static final String PREFIX = "m1-044-roundtrip-";
    private static final String GUARDIAN = "guardian-m1-044-roundtrip-permanent";

    /** Matches any RFC-4122 UUID literal in a reply body (case-insensitive). */
    private static final Pattern UUID_IN_REPLY =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject InMemoryAdapter adapter;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the per-test DELETE on admin rows.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);

            // Clear prior-test invite_code rows owned by our prefix
            // (both contact-bound and rows created by our prior admin
            // rows).
            exec(conn,
                    "DELETE FROM invite_code WHERE expected_contact_id LIKE ? "
                            + "OR created_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");

            // audit_log carries no-update + no-delete triggers (V5).
            // The per-test cleanup must disable them temporarily so the
            // DELETE below does not raise.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                                + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                // Clear banned_by FKs before deleting the rows they reference.
                exec(conn,
                        "UPDATE users SET banned_by = NULL WHERE contact_id LIKE ?",
                        PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL "
                                + "WHERE banned_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void inviteBanUnbanDmGateRoundtripThroughInMemoryAdapter() throws Exception {
        String admin = PREFIX + "admin";
        String u1 = PREFIX + "u-1";
        String u2 = PREFIX + "u-2";
        String u3 = PREFIX + "u-3";

        UUID adminId = seedUser(admin, /* isAdmin */ true, /* isBanned */ false,
                "vouched", /* probationUntil */ null);

        // ----- Step (a) — unknown DM without invite -----------------------
        adapter.deliverDm(u1, "random text not a uuid");
        List<OutboundMessage> outA = adapter.sentMessages();
        assertEquals(1, outA.size(),
                "step (a): unknown DM without invite must produce exactly one outbound");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED),
                outA.get(0).text(),
                "step (a): outbound body must equal error.invite.required");
        assertEquals(0L, countUsersByContact(u1),
                "step (a): no users row may be written for an unknown DM");
        assertEquals(0L, countAuditByAction("INVITE_CONSUME"),
                "step (a): no INVITE_CONSUME audit row may be written");
        adapter.reset();

        // ----- Step (b) — bot-admin /invite create --contact u-1 ----------
        adapter.deliverDm(admin,
                "/invite create --adapter " + ADAPTER + " --contact " + u1);
        List<OutboundMessage> outB = adapter.sentMessages();
        assertEquals(1, outB.size(),
                "step (b): /invite create --contact must produce exactly one outbound");
        UUID code = extractUuid(outB.get(0).text());
        assertNotNull(code,
                "step (b): /invite create reply must contain the new code UUID — got: "
                        + outB.get(0).text());
        assertEquals(1L,
                countInvites(u1, "PENDING"),
                "step (b): exactly one PENDING invite_code row must exist for "
                        + u1);
        assertEquals(1L, countAuditByActorAndAction(admin, "INVITE_CREATE"),
                "step (b): one INVITE_CREATE audit row must be written by the admin");
        adapter.reset();

        // ----- Step (c) — invite-consume roundtrip ------------------------
        adapter.deliverDm(u1, code.toString());
        List<OutboundMessage> outC = adapter.sentMessages();
        assertEquals(1, outC.size(),
                "step (c): invite-consume must produce exactly one outbound (welcome)");
        assertEquals(bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH),
                outC.get(0).text(),
                "step (c): outbound body must equal reply.welcome.dm_fresh");
        assertEquals("invited", registrationStateOf(u1),
                "step (c): consumer must transition to registration_state='invited'");
        assertNotNull(probationUntilOf(u1),
                "step (c): probation_until must be populated per D45 slow-start");
        assertEquals("USED", inviteStatusOf(code),
                "step (c): invite_code row must transition to USED");
        assertEquals(1L, countAuditByActorAndAction(u1, "INVITE_CONSUME"),
                "step (c): one INVITE_CONSUME audit row must be written for the consumer");
        adapter.reset();

        // ----- Step (d) — pre-ban + pending-invite revocation -------------
        // Pre-seed a separate PENDING invite for u-2 (a different code
        // than u-1's). The /ban must revoke it in the same transaction.
        UUID u2InvitePrior = UUID.randomUUID();
        seedPendingInvite(u2InvitePrior, u2, adminId);
        assertEquals("PENDING", inviteStatusOf(u2InvitePrior),
                "step (d) precondition: u-2's pre-existing invite must start PENDING");

        // /ban is confirm-gated (M1-051): first call returns the prompt;
        // the second confirms and runs the actual ban.
        adapter.deliverDm(admin, "/ban " + u2 + " --reason \"spam\"");
        assertEquals(1, adapter.sentMessages().size(),
                "step (d): /ban must first emit the confirm prompt (M1-051 gate)");
        adapter.deliverDm(admin, "/ban confirm");
        // Only the state assertions are pinned by the spec; outbound
        // shape is M1-051's responsibility and is already covered by
        // ConfirmFlowIT.

        assertEquals("preban", registrationStateOf(u2),
                "step (d): unknown-contact /ban must mint registration_state='preban'");
        assertTrue(isBanned(u2),
                "step (d): unknown-contact /ban must set is_banned=true");
        assertEquals("REVOKED", inviteStatusOf(u2InvitePrior),
                "step (d): the pending invite for u-2 must transition to REVOKED "
                        + "in the same transaction as the ban");
        assertEquals(1L, countAuditByActionAndTarget("BAN", u2),
                "step (d): exactly one BAN audit row must exist for u-2");
        adapter.reset();

        // ----- Step (e) — /unban deletes preban via V5 procedure ----------
        adapter.deliverDm(admin, "/unban " + u2);
        List<OutboundMessage> outE = adapter.sentMessages();
        assertEquals(1, outE.size(),
                "step (e): /unban must produce exactly one outbound");
        assertEquals(bundleLoader.get(BundleKeys.REPLY_UNBAN_PREBAN_DELETED),
                outE.get(0).text(),
                "step (e): outbound body must equal reply.unban.preban_deleted");
        assertEquals(0L, countUsersByContact(u2),
                "step (e): the preban users row must be deleted by delete_preban_user");
        assertEquals(1L, countAuditByActionAndTarget("UNBAN_PREBAN_DELETE", u2),
                "step (e): one UNBAN_PREBAN_DELETE audit row must be written by the "
                        + "V5 stored procedure (audit-before-effect)");
        adapter.reset();

        // ----- Step (f) — post-unban DM still requires fresh invite -------
        adapter.deliverDm(u2, "any body");
        List<OutboundMessage> outF = adapter.sentMessages();
        assertEquals(1, outF.size(),
                "step (f): post-unban DM must produce exactly one outbound");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_INVITE_REQUIRED),
                outF.get(0).text(),
                "step (f): /unban must NOT silently bypass the invite gate — the "
                        + "next DM from the same contact must still return "
                        + "error.invite.required");
        assertEquals(0L, countUsersByContact(u2),
                "step (f): the preban row must remain deleted");
        adapter.reset();

        // ----- Step (g) — banned registered user gets fixed ban reply -----
        // Seed u-3 as 'invited' with probation already expired (1h ago)
        // so the IT pins step 4 (BanCheck) independently of M1-045's
        // probation gate. Then set is_banned=true via direct UPDATE
        // rather than calling /ban — the IT exercises the intake-side
        // ban check, not the /ban command (which step (d) covers).
        seedUser(u3, /* isAdmin */ false, /* isBanned */ false, "invited",
                OffsetDateTime.now().minusHours(1));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET is_banned = TRUE "
                             + "WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, u3);
            ps.executeUpdate();
        }

        int sentBefore = adapter.sentMessages().size();
        adapter.deliverDm(u3, "/help");
        List<OutboundMessage> outG = adapter.sentMessages();
        assertEquals(sentBefore + 1, outG.size(),
                "step (g): sentMessages() must grow by exactly 1 (no /help reply, "
                        + "no LLM call — the BanCheck step 4 short-circuits)");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_BAN_FIXED),
                outG.get(outG.size() - 1).text(),
                "step (g): outbound body must equal error.ban.fixed");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin, boolean isBanned,
                          String registrationState,
                          OffsetDateTime probationUntilOrNull) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at, probation_until) "
                             + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END, ?) "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setString(5, registrationState);
            ps.setBoolean(6, isBanned);
            if (probationUntilOrNull == null) {
                ps.setObject(7, null);
            } else {
                ps.setObject(7, probationUntilOrNull);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedPendingInvite(UUID code, String expectedContactId, UUID createdBy)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO invite_code (code, invite_type, adapter, "
                             + "expected_contact_id, status, created_by) "
                             + "VALUES (?, 'CONTACT_BOUND', ?, ?, 'PENDING', ?)")) {
            ps.setObject(1, code);
            ps.setString(2, ADAPTER);
            ps.setString(3, expectedContactId);
            ps.setObject(4, createdBy);
            ps.executeUpdate();
        }
    }

    private long countUsersByContact(String contactId) throws Exception {
        return queryLong(
                "SELECT COUNT(*) FROM users WHERE adapter = ? AND contact_id = ?",
                ADAPTER, contactId);
    }

    private long countAuditByAction(String action) throws Exception {
        return queryLong(
                "SELECT COUNT(*) FROM audit_log WHERE action = ?",
                action);
    }

    private long countAuditByActorAndAction(String actorContactId, String action)
            throws Exception {
        return queryLong(
                "SELECT COUNT(*) FROM audit_log WHERE actor_contact_id = ? "
                        + "AND action = ?",
                actorContactId, action);
    }

    private long countAuditByActionAndTarget(String action, String targetContactId)
            throws Exception {
        return queryLong(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? "
                        + "AND target_contact_id = ?",
                action, targetContactId);
    }

    private long countInvites(String expectedContactId, String status) throws Exception {
        return queryLong(
                "SELECT COUNT(*) FROM invite_code WHERE adapter = ? "
                        + "AND expected_contact_id = ? AND status = ?",
                ADAPTER, expectedContactId, status);
    }

    private String inviteStatusOf(UUID code) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "invite_code row must exist for code=" + code);
                return rs.getString("status");
            }
        }
    }

    private String registrationStateOf(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT registration_state FROM users "
                             + "WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "users row must exist for contact_id=" + contactId);
                return rs.getString("registration_state");
            }
        }
    }

    private OffsetDateTime probationUntilOf(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT probation_until FROM users "
                             + "WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "users row must exist for contact_id=" + contactId);
                return rs.getObject("probation_until", OffsetDateTime.class);
            }
        }
    }

    private boolean isBanned(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean("is_banned");
            }
        }
    }

    private long queryLong(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static UUID extractUuid(String body) {
        Matcher m = UUID_IN_REPLY.matcher(body);
        if (!m.find()) {
            return null;
        }
        return UUID.fromString(m.group());
    }

    /**
     * Pins the minimum AdapterRegistry properties (gate 1 non-empty list,
     * gate 2 name resolves, gate 6 LOW-trust opt-in) for the
     * {@code inmemory} adapter — the same shape
     * {@link AdapterRouterIT.MvpProfile} uses. Declared inline so the
     * IT's adapter-config dependency is visible without cross-referencing
     * {@code application.properties}.
     */
    public static final class RoundtripProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }
    }
}
