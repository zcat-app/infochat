package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BanCommandHandler} against the
 * DevServices Postgres container (V5 users + audit_log + invite_code,
 * V12 invite_code_attempt). One {@code @Test} per acceptance scenario
 * 2..8 in M1-044c.
 *
 * <p>Test isolation: each {@code @Test} uses a unique sub-prefix
 * within the class-wide {@code PREFIX} ({@code m1-044c-ban-}); the
 * {@link #cleanup()} {@code @BeforeEach} deletes only rows under the
 * class-wide prefix. {@code audit_log} is append-only (V5
 * {@code trg_audit_log_append_only}) so cleanup does NOT touch that
 * table; assertions filter audit rows by the per-method sub-prefix
 * via {@code target_contact_id LIKE ?}, so rows from prior test runs
 * never collide with the current scenario.</p>
 */
@QuarkusTest
class BanCommandHandlerTest {

    private static final String PREFIX = "m1-044c-ban-";
    private static final String ADAPTER = "inmemory";

    @Inject BanCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the DELETE on test admin rows
            // below. The guardian's contact id is intentionally outside
            // the PREFIX so the cleanup never collects it.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-044c-ban-permanent");
            // invite_code (FK to users.created_by, expected_contact_id is text).
            exec(conn,
                    "DELETE FROM invite_code WHERE expected_contact_id LIKE ? "
                            + "OR created_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");
            // audit_log carries FK actor_user_id REFERENCES users(id); the V5
            // append-only triggers prevent UPDATE/DELETE on audit_log. We
            // are the DB owner, so we can DISABLE the triggers for the
            // cleanup pass and re-enable in a try/finally so a cleanup
            // failure cannot leave the table without its invariant.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                                + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                // Clear banned_by self-references that would block the
                // users DELETE (FK users.banned_by REFERENCES users(id)
                // NO ACTION).
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

    // ----- (2) Non-admin → error.admin_only, no DB write -------------------

    @Test
    void banByNonAdminReturnsAdminOnly() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        String target = PREFIX + "nonAdmin-target";
        seedUser(actor, false, false, "invited");
        seedUser(target, false, false, "invited");
        long usersBefore = countUsersUnderPrefix();
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "nonAdmin-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/ban " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /ban must surface error.admin_only");
        assertEquals(usersBefore, countUsersUnderPrefix(),
                "non-admin /ban must not touch users");
        assertEquals(auditBefore, countAuditUnderTargetPrefix(PREFIX + "nonAdmin-"),
                "non-admin /ban must not write any audit row");
    }

    // ----- (3) Self-ban → error.ban.cannot_ban_self, no DB write -----------

    @Test
    void banSelfReturnsCannotBanSelf() throws Exception {
        String actor = PREFIX + "self-actor";
        seedUser(actor, /* isAdmin */ true, false, "vouched");
        long usersBefore = countUsersUnderPrefix();
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "self-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/ban " + actor);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_BAN_CANNOT_BAN_SELF), reply.text(),
                "self-ban must surface error.ban.cannot_ban_self");
        assertEquals(usersBefore, countUsersUnderPrefix(),
                "self-ban must not touch users");
        assertEquals(auditBefore, countAuditUnderTargetPrefix(PREFIX + "self-"),
                "self-ban must not write any audit row");
    }

    // ----- (4) Unknown contact mints a preban row --------------------------

    @Test
    void banUnknownContactMintsPreban() throws Exception {
        String actor = PREFIX + "preban-actor";
        String unknown = PREFIX + "preban-unknown";
        seedUser(actor, /* isAdmin */ true, false, "vouched");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/ban " + unknown + " --reason \"spam\"");

        assertEquals(redactedSuccess(unknown), reply.text(),
                "/ban success reply must interpolate the redacted target contact id");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_banned, registration_state, banned_by, ban_reason "
                             + "FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, unknown);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "preban row must exist after /ban against unknown contact");
                assertTrue(rs.getBoolean("is_banned"), "preban row is_banned must be TRUE");
                assertEquals("preban", rs.getString("registration_state"),
                        "preban row registration_state must be 'preban'");
                UUID bannedBy = (UUID) rs.getObject("banned_by");
                assertNotNull(bannedBy, "banned_by must be set to actor.id");
                assertEquals(userId(actor), bannedBy,
                        "banned_by must equal the actor's users.id");
                assertEquals("spam", rs.getString("ban_reason"),
                        "ban_reason must equal the --reason flag value");
            }
        }
    }

    // ----- (5) Known user: UPDATE flips is_banned --------------------------

    @Test
    void banKnownUserSetsIsBannedTrue() throws Exception {
        String actor = PREFIX + "known-actor";
        String target = PREFIX + "known-target";
        seedUser(actor, /* isAdmin */ true, false, "vouched");
        seedUser(target, false, /* isBanned */ false, "invited");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/ban " + target + " --reason policy");

        assertEquals(redactedSuccess(target), reply.text());

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_banned, banned_at, banned_by, ban_reason, registration_state "
                             + "FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, target);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "users row must remain after /ban against known contact");
                assertTrue(rs.getBoolean("is_banned"), "is_banned must be TRUE after /ban");
                assertNotNull(rs.getTimestamp("banned_at"),
                        "banned_at must be set to NOW() on the UPDATE path");
                assertEquals(userId(actor), rs.getObject("banned_by"),
                        "banned_by must equal the actor's users.id");
                assertEquals("policy", rs.getString("ban_reason"),
                        "ban_reason must equal the --reason flag value");
                // registration_state stays at its prior 'invited' value (the
                // ban UPDATE does NOT touch registration_state for a known
                // row — that column is only set by the preban INSERT).
                assertEquals("invited", rs.getString("registration_state"),
                        "registration_state must be unchanged by /ban on a known row");
            }
        }
    }

    // ----- (6) Same transaction revokes pending CONTACT_BOUND invites ------

    @Test
    void banWithPendingContactBoundInviteRevokesItInSameTransaction() throws Exception {
        String actor = PREFIX + "revoke-actor";
        String target = PREFIX + "revoke-target";
        UUID actorId = seedUser(actor, /* isAdmin */ true, false, "vouched");
        seedUser(target, false, false, "invited");

        // Pending CONTACT_BOUND invite targeting the user we ban.
        UUID code = UUID.randomUUID();
        seedInvite(code, "CONTACT_BOUND", ADAPTER, target, actorId, "PENDING");

        // Also seed an OPEN invite for the same adapter — spec says
        // open invites are NOT revoked on /ban; this row MUST remain
        // PENDING after the dispatch.
        UUID openCode = UUID.randomUUID();
        seedInvite(openCode, "OPEN_ADAPTER", ADAPTER, null, actorId, "PENDING");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/ban " + target);
        assertEquals(redactedSuccess(target), reply.text());

        // users row: is_banned=TRUE.
        assertTrue(isBanned(target), "target users row must be is_banned=TRUE after /ban");
        // CONTACT_BOUND invite: status=REVOKED.
        assertEquals("REVOKED", inviteStatus(code),
                "CONTACT_BOUND pending invite must transition to REVOKED in the same tx");
        // OPEN invite: untouched, still PENDING.
        assertEquals("PENDING", inviteStatus(openCode),
                "OPEN_ADAPTER pending invite must NOT be revoked by /ban per spec");
    }

    // ----- (7) BAN + INVITE_REVOKE audit rows share request_id -------------

    @Test
    void banAndInviteRevokeAuditRowsShareRequestId() throws Exception {
        String actor = PREFIX + "corrId-actor";
        String target = PREFIX + "corrId-target";
        UUID actorId = seedUser(actor, /* isAdmin */ true, false, "vouched");
        seedUser(target, false, false, "invited");
        seedInvite(UUID.randomUUID(), "CONTACT_BOUND", ADAPTER, target, actorId, "PENDING");

        handler.handle(new ScopeRef.Dm(actor), "/ban " + target);

        Set<String> requestIds = new HashSet<>();
        Set<String> actions = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT action, request_id FROM audit_log "
                             + "WHERE target_contact_id = ? "
                             + "  AND action IN ('BAN', 'INVITE_REVOKE') "
                             + "ORDER BY created_at")) {
            ps.setString(1, target);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    actions.add(rs.getString("action"));
                    String requestId = rs.getString("request_id");
                    assertNotNull(requestId, "audit rows must carry a non-null request_id");
                    requestIds.add(requestId);
                }
            }
        }
        assertTrue(actions.contains("BAN"),
                "expected one BAN audit row for the /ban dispatch; got: " + actions);
        assertTrue(actions.contains("INVITE_REVOKE"),
                "expected one INVITE_REVOKE audit row for the contact-bound invite "
                        + "revoked by /ban; got: " + actions);
        assertEquals(1, requestIds.size(),
                "BAN + INVITE_REVOKE audit rows from the same /ban dispatch must "
                        + "share one request_id; got: " + requestIds);
    }

    // ----- (8) Last-admin protection: trigger rolls back the transaction ---

    @Test
    void banOfOnlyAdminSurfacesLastAdminError() throws Exception {
        // Contrived but spec-correct setup: actor is the LAST admin (passes
        // admin gate). To force the protection trigger to fire, actor itself
        // must NOT count as "admin not banned" against the post-ban target.
        // Seed actor as is_admin=TRUE AND is_banned=TRUE — passes our
        // handler's admin-only gate (which checks only is_admin) but does
        // NOT satisfy the trigger's `id <> NEW.id AND is_banned = FALSE`
        // count predicate. Target is the only remaining
        // `is_admin=TRUE AND is_banned=FALSE` row; banning them would
        // leave zero unbanned admins.
        //
        // The setup mimics an upstream-gate bypass (a banned admin running
        // raw /ban). M1-044b's intake step 4 ban check blocks this in
        // production; the test exercises the trigger directly to pin the
        // last-resort defense.
        //
        // The cleanup's guardian admin is temporarily demoted so it does
        // NOT count as another unbanned admin; restored in the finally
        // block so subsequent tests' cleanup can still rely on it as the
        // last-admin-protection floor when deleting test admins.
        String actor = PREFIX + "lastAdmin-actor";
        String target = PREFIX + "lastAdmin-target";
        seedUser(actor, /* isAdmin */ true, /* isBanned */ true, "vouched");
        seedUser(target, /* isAdmin */ true, /* isBanned */ false, "vouched");

        setGuardianAdmin(false);
        try {
            // Snapshot pre-state (after guardian demotion).
            boolean targetBannedBefore = isBanned(target);
            long auditBefore = countAuditUnderTargetPrefix(PREFIX + "lastAdmin-");

            OutboundMessage reply = handler.handle(
                    new ScopeRef.Dm(actor),
                    "/ban " + target);

            assertEquals(bundleLoader.get(BundleKeys.ERROR_BAN_LAST_ADMIN), reply.text(),
                    "/ban of the only remaining is_admin=TRUE/is_banned=FALSE row must "
                            + "surface error.ban.last_admin");
            // The transaction rolled back — target's is_banned is unchanged AND
            // the pre-written audit row went with the rollback (no new audit
            // row for this target).
            assertEquals(targetBannedBefore, isBanned(target),
                    "target row must be unchanged after the trigger-driven rollback");
            assertEquals(auditBefore, countAuditUnderTargetPrefix(PREFIX + "lastAdmin-"),
                    "pre-written audit row must roll back with the failed mutation "
                            + "(audit-vs-state divergence is forbidden by Invariant 7)");
        } finally {
            setGuardianAdmin(true);
        }
    }

    private void setGuardianAdmin(boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET is_admin = ? "
                             + "WHERE adapter = ? AND contact_id = 'guardian-m1-044c-ban-permanent'")) {
            ps.setBoolean(1, isAdmin);
            ps.setString(2, ADAPTER);
            ps.executeUpdate();
        }
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin, boolean isBanned,
                          String registrationState) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at) "
                             + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END) "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setString(5, registrationState);
            ps.setBoolean(6, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedInvite(UUID code, String inviteType, String adapter,
                            String expectedContactId, UUID createdBy, String status)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO invite_code (code, invite_type, adapter, "
                             + "expected_contact_id, status, created_by) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, code);
            ps.setString(2, inviteType);
            ps.setString(3, adapter);
            if (expectedContactId == null) {
                ps.setNull(4, java.sql.Types.VARCHAR);
            } else {
                ps.setString(4, expectedContactId);
            }
            ps.setString(5, status);
            ps.setObject(6, createdBy);
            ps.executeUpdate();
        }
    }

    private UUID userId(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
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

    private String inviteStatus(UUID code) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM invite_code WHERE code = ?")) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("status");
            }
        }
    }

    private long countUsersUnderPrefix() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM users WHERE contact_id LIKE ?")) {
            ps.setString(1, PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditUnderTargetPrefix(String subPrefix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE target_contact_id LIKE ?")) {
            ps.setString(1, subPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String redactedSuccess(String contactId) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_BAN_SUCCESS),
                ContactIds.redact(contactId));
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
