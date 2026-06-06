package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link RevokeAdminCommandHandler} against the
 * DevServices Postgres container. One {@code @Test} per acceptance
 * scenario (a)..(f) in M1-046, refined to commit to the
 * banned-admin-caller-vs-sole-active-admin shape for the trigger-fire
 * path (scenario (e)) per the M1-046 clarity refinement.
 *
 * @implNote Canonical thin-SQL handler exception per
 *     {@code docs/process/test-pyramid.md} §Shape B: Thin-SQL.
 */
@QuarkusTest
class RevokeAdminCommandHandlerTest {

    private static final String PREFIX = "m1-046-revoke-";
    private static final String ADAPTER = "inmemory";

    @Inject RevokeAdminCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin keeps the V5 last-admin trigger
            // happy when tests below DELETE their per-test admins.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-046-revoke-permanent");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                                + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
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

    // ----- (a) Non-admin → error.admin_only --------------------------------

    @Test
    void revokeByNonAdminReturnsAdminOnly() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        String target = PREFIX + "nonAdmin-target";
        UUID actorId = seedUser(actor, /* isAdmin */ false, false);
        seedUser(target, /* isAdmin */ true, false);
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "nonAdmin-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/revoke-admin " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /revoke-admin must surface error.admin_only");
        assertTrue(isAdmin(target),
                "non-admin /revoke-admin must not flip target's is_admin");
        assertEquals(auditBefore, countAuditUnderTargetPrefix(PREFIX + "nonAdmin-"),
                "non-admin /revoke-admin must write no audit row");
        assertEquals(0L, countAuditByActor(actorId),
                "the non-admin dispatch must write zero audit rows of ANY action — "
                        + "the permission check fails at spec step 7, so the step-8 "
                        + "intent write is never reached");
    }

    // ----- (b) Self-revoke → error.revoke_admin.cannot_revoke_self ---------
    // Permission check (spec step 7) and audit-on-intent (step 8) run
    // BEFORE the guard, so an authorized admin's self-revoke leaves a
    // surviving REVOKE_ADMIN_INTENT row (M1-173 redteam finding 1)
    // while the guard still short-circuits before the transaction: no
    // users mutation, no effect row. The V5 trigger stays the LAST
    // line of defense, not the only one. A non-admin self-revoke is
    // refused at step 7 itself — the guard is never reached and no
    // row is written.

    @Test
    void revokeSelfByAdminWritesIntentRowWithoutEffect() throws Exception {
        String actor = PREFIX + "self-actor";
        seedUser(actor, /* isAdmin */ true, false);
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "self-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/revoke-admin " + actor);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_CANNOT_REVOKE_SELF), reply.text(),
                "self-revoke must surface error.revoke_admin.cannot_revoke_self");
        assertTrue(isAdmin(actor),
                "self-revoke must NOT touch users.is_admin (guard short-circuits before the transaction)");
        assertEquals(auditBefore + 1, countAuditUnderTargetPrefix(PREFIX + "self-"),
                "exactly one audit row survives the refused self-revoke");
        assertEquals(1L, countAuditByActionAndTarget("REVOKE_ADMIN_INTENT", actor),
                "the authorized self-revoke attempt must leave a surviving REVOKE_ADMIN_INTENT row");
        assertEquals(0L, countAuditByActionAndTarget("REVOKE_ADMIN", actor),
                "no REVOKE_ADMIN effect row may exist for the refused self-revoke");
    }

    @Test
    void revokeSelfByNonAdminReturnsAdminOnlyNoAudit() throws Exception {
        String actor = PREFIX + "selfNonAdmin-actor";
        UUID actorId = seedUser(actor, /* isAdmin */ false, false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/revoke-admin " + actor);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "the permission check (spec step 7) precedes the self-revoke guard: "
                        + "a non-admin self-revoke surfaces error.admin_only");
        assertEquals(0L, countAuditByActor(actorId),
                "the permission-refused dispatch must write zero audit rows of any action");
    }

    // ----- (c) Unknown contact → error.contact_not_registered --------------
    // The reply is unchanged, but the admin-authorized dispatch passes
    // the spec step-7 permission check, so the step-8 REVOKE_ADMIN_INTENT
    // row survives the step-6c rollback — an admin probing for
    // registered contacts is no longer invisible to the audit log
    // (M1-151 redteam finding 1, target-unknown leg), and the row's
    // target_registered=false marks its synthetic target_id (M1-173
    // redteam finding 3).

    @Test
    void revokeUnknownContactWritesIntentRow() throws Exception {
        String actor = PREFIX + "unknown-actor";
        String unknown = PREFIX + "unknown-target";
        seedUser(actor, /* isAdmin */ true, false);
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "unknown-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/revoke-admin " + unknown);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED), reply.text(),
                "unknown contact must surface error.contact_not_registered");
        assertEquals(auditBefore + 1, countAuditUnderTargetPrefix(PREFIX + "unknown-"),
                "exactly one audit row survives the unknown-contact refusal");
        assertEquals(1L, countAuditByActionAndTarget("REVOKE_ADMIN_INTENT", unknown),
                "the admin-authorized probe against an unregistered contact must leave "
                        + "a surviving REVOKE_ADMIN_INTENT row");
        assertEquals(0L, countAuditByActionAndTarget("REVOKE_ADMIN", unknown),
                "no REVOKE_ADMIN effect row may exist for the refused attempt");
        // jsonb canonicalizes details_json on read-back (one space
        // after each colon), so the assertion matches that form.
        String detailsJson = detailsJsonOfIntentRow(unknown);
        assertTrue(detailsJson.contains("\"target_registered\": false"),
                "the intent row against an unregistered contact must mark its synthetic "
                        + "target_id with target_registered=false; got: " + detailsJson);
    }

    // ----- (d) Target not admin → error.revoke_admin.not_admin (no-op) -----
    // The users-table no-op keeps its reply, but the admin-authorized
    // dispatch passes the spec step-7 permission check, so the step-8
    // REVOKE_ADMIN_INTENT row survives the step-6d rollback — an admin
    // probing for who holds the admin bit is no longer invisible to the
    // audit log (M1-151 redteam finding 1, target-not-admin leg). The
    // registered target's intent row carries target_registered=true.

    @Test
    void revokeTargetNotAdminWritesIntentRow() throws Exception {
        String actor = PREFIX + "notAdmin-actor";
        String target = PREFIX + "notAdmin-target";
        seedUser(actor, /* isAdmin */ true, false);
        seedUser(target, /* isAdmin */ false, false);
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "notAdmin-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/revoke-admin " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_NOT_ADMIN), reply.text(),
                "already-non-admin target must surface error.revoke_admin.not_admin");
        assertFalse(isAdmin(target),
                "target's is_admin must remain false (no-op)");
        assertEquals(auditBefore + 1, countAuditUnderTargetPrefix(PREFIX + "notAdmin-"),
                "exactly one audit row survives the not-admin refusal");
        assertEquals(1L, countAuditByActionAndTarget("REVOKE_ADMIN_INTENT", target),
                "the admin-authorized probe against a registered non-admin must leave "
                        + "a surviving REVOKE_ADMIN_INTENT row");
        assertEquals(0L, countAuditByActionAndTarget("REVOKE_ADMIN", target),
                "no REVOKE_ADMIN effect row may exist for the refused no-op attempt");
        // jsonb canonical form — see the spacing note in test (c).
        String detailsJson = detailsJsonOfIntentRow(target);
        assertTrue(detailsJson.contains("\"target_registered\": true"),
                "the intent row against a registered target must carry "
                        + "target_registered=true; got: " + detailsJson);
    }

    // ----- (e) Trigger-fire path: V5 last-admin protection -----------------
    // Setup: a `banned-admin` caller (is_admin=true, is_banned=true) and a
    // `sole-active-admin` target (is_admin=true, is_banned=false). The
    // handler's caller-admin check passes (it checks only is_admin); the
    // self-revoke guard does NOT fire (caller.id != target.id); the
    // UPDATE attempts to flip target.is_admin to false; the V5 trigger
    // sees the post-update qualifying count would be 0 and raises
    // `last_admin_protection`; the handler catches the SQLException and
    // surfaces error.revoke_admin.last_admin. The unit test bypasses
    // M1-044b's intake-side ban gate by calling the handler directly —
    // a banned caller cannot reach this handler in production. The
    // guardian admin is demoted for the duration of this test so it
    // does not satisfy the trigger's count predicate, restored in the
    // finally so subsequent tests can rely on it.

    @Test
    void revokeLastAdminTriggerFiresAndRollsBack() throws Exception {
        String caller = PREFIX + "lastAdmin-caller";
        String target = PREFIX + "lastAdmin-target";
        seedUser(caller, /* isAdmin */ true, /* isBanned */ true);
        seedUser(target, /* isAdmin */ true, /* isBanned */ false);

        // Capture every other qualifying-admin user id (is_admin=TRUE
        // AND is_banned=FALSE) — including this test's own guardian
        // plus persistent guardians seeded by other test classes that
        // ran earlier in the same JVM (BanCommandHandlerTest's,
        // GrantAdminCommandHandlerTest's, GrantRevokeAdminScopingIT's).
        // Demote them all for the duration of this test so the trigger
        // sees target as the sole qualifying admin; restore the prior
        // is_admin flag in the finally so subsequent tests can rely on
        // their guardians for last-admin-protection floors.
        List<UUID> otherAdmins = captureOtherQualifyingAdmins(target);
        setAdminBatch(otherAdmins, false);
        try {
            long auditBefore = countAuditUnderTargetPrefix(PREFIX + "lastAdmin-");

            OutboundMessage reply = handler.handle(
                    new ScopeRef.Dm(caller),
                    "/revoke-admin " + target);

            assertEquals(bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_LAST_ADMIN), reply.text(),
                    "/revoke-admin against the sole-active-admin must surface error.revoke_admin.last_admin");
            assertTrue(isAdmin(target),
                    "target's is_admin must remain true (transaction rolled back)");
            assertEquals(auditBefore + 1, countAuditUnderTargetPrefix(PREFIX + "lastAdmin-"),
                    "exactly one audit row survives the refused attempt: the REVOKE_ADMIN row "
                            + "pre-written inside the transaction rolls back with the failed "
                            + "UPDATE; the separately-committed REVOKE_ADMIN_INTENT row persists");
            assertEquals(1L, countAuditByActionAndTarget("REVOKE_ADMIN_INTENT", target),
                    "the REVOKE_ADMIN_INTENT row (separate auto-commit connection) must survive "
                            + "the trigger-raised rollback so the refused attempt stays "
                            + "operator-visible");
            assertEquals(0L, countAuditByActionAndTarget("REVOKE_ADMIN", target),
                    "no REVOKE_ADMIN audit row may exist for the trigger-rolled-back attempt");
        } finally {
            setAdminBatch(otherAdmins, true);
        }
    }

    // ----- (f) Multi-admin: revoke one of two — succeeds --------------------
    // Two admins, one on each adapter; revoke one from its own adapter
    // succeeds (the V5 trigger sees the OTHER adapter's admin still
    // qualifying); the OTHER adapter's row is unchanged. The cross-
    // adapter scoping is exercised end-to-end by the IT; here we pin
    // the audit-row + per-adapter-row mutation shape at the unit level.

    @Test
    void revokeOneOfTwoAdminsSucceedsAndLeavesOtherAdapterUntouched() throws Exception {
        String otherAdapter = "signal-mock";
        String actor = PREFIX + "twoAdmins-actor";
        String target = PREFIX + "twoAdmins-target";
        // Two bot admins on the inbound adapter: actor + target both
        // is_admin=true. A separate admin on a DIFFERENT adapter so
        // the trigger sees a non-zero qualifying count from the other
        // adapter even before target's revoke.
        UUID actorId = seedUser(actor, /* isAdmin */ true, false);
        UUID targetId = seedUser(target, /* isAdmin */ true, false);
        // Seed an admin on the OTHER adapter so the deployment-wide
        // count never drops to 1 (so the trigger does NOT fire here).
        seedUserOnAdapter(otherAdapter, PREFIX + "twoAdmins-otherAdapterAdmin",
                /* isAdmin */ true, false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/revoke-admin " + target);

        assertEquals(redactedSuccess(target), reply.text(),
                "multi-admin revoke success reply must interpolate the redacted target");
        assertFalse(isAdmin(target),
                "target's is_admin must flip to false");
        assertTrue(isAdmin(actor),
                "actor's is_admin must remain true");
        assertTrue(isAdminOnAdapter(otherAdapter, PREFIX + "twoAdmins-otherAdapterAdmin"),
                "the OTHER adapter's admin row must be UNCHANGED");

        String effectRequestId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT actor_user_id, actor_contact_id, actor_adapter, "
                             + "target_kind, target_id, target_contact_id, details_json, "
                             + "request_id "
                             + "FROM audit_log WHERE action = 'REVOKE_ADMIN' "
                             + "  AND target_contact_id = ?")) {
            ps.setString(1, target);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "exactly one REVOKE_ADMIN audit row must exist for the target");
                assertEquals(actorId, rs.getObject("actor_user_id"));
                assertEquals(actor, rs.getString("actor_contact_id"));
                assertEquals(ADAPTER, rs.getString("actor_adapter"));
                assertEquals("user", rs.getString("target_kind"));
                assertEquals(targetId.toString(), rs.getString("target_id"));
                assertEquals(target, rs.getString("target_contact_id"));
                String detailsJson = rs.getString("details_json");
                assertNotNull(detailsJson, "details_json must be non-null");
                assertTrue(detailsJson.contains("\"target_adapter\""),
                        "details_json must carry target_adapter key; got: " + detailsJson);
                effectRequestId = rs.getString("request_id");
            }
        }
        assertNotNull(effectRequestId, "the REVOKE_ADMIN effect row must carry a request_id");
        assertEquals(1L, countAuditByActionAndRequestId("REVOKE_ADMIN_INTENT", effectRequestId),
                "the committed REVOKE_ADMIN effect row must share its request_id with "
                        + "exactly one REVOKE_ADMIN_INTENT row (intent↔effect correlation)");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin, boolean isBanned) throws Exception {
        return seedUserOnAdapter(ADAPTER, contactId, isAdmin, isBanned);
    }

    private UUID seedUserOnAdapter(String adapter, String contactId, boolean isAdmin,
                                   boolean isBanned) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at) "
                             + "VALUES (?, ?, ?, ?, 'vouched', "
                             + "CASE WHEN ? THEN NOW() ELSE NULL END) "
                             + "RETURNING id")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setBoolean(5, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private boolean isAdmin(String contactId) throws Exception {
        return isAdminOnAdapter(ADAPTER, contactId);
    }

    private boolean isAdminOnAdapter(String adapter, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean("is_admin");
            }
        }
    }

    /**
     * Return every users.id where {@code is_admin=TRUE AND
     * is_banned=FALSE} except the row whose {@code contact_id ==
     * excludeContactId}. The trigger's qualifying-admin count is
     * cross-adapter and cross-prefix, so the result captures
     * persistent guardian rows seeded by other test classes that ran
     * earlier in the same JVM.
     */
    private List<UUID> captureOtherQualifyingAdmins(String excludeContactId) throws Exception {
        List<UUID> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE is_admin = TRUE AND is_banned = FALSE "
                             + "  AND contact_id <> ?")) {
            ps.setString(1, excludeContactId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add((UUID) rs.getObject("id"));
                }
            }
        }
        return ids;
    }

    private void setAdminBatch(List<UUID> ids, boolean isAdmin) throws Exception {
        if (ids.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET is_admin = ? WHERE id = ?")) {
            for (UUID id : ids) {
                ps.setBoolean(1, isAdmin);
                ps.setObject(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
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

    private long countAuditByActionAndTarget(String action, String targetContactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_contact_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetContactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditByActionAndRequestId(String action, String requestId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND request_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String detailsJsonOfIntentRow(String targetContactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT details_json FROM audit_log WHERE action = 'REVOKE_ADMIN_INTENT' "
                             + "  AND target_contact_id = ?")) {
            ps.setString(1, targetContactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("details_json");
            }
        }
    }

    private long countAuditByActor(UUID actorUserId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE actor_user_id = ?")) {
            ps.setObject(1, actorUserId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String redactedSuccess(String contactId) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_REVOKE_ADMIN_SUCCESS),
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
