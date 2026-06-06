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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link GrantAdminCommandHandler} against the
 * DevServices Postgres container (V5 users + audit_log). One
 * {@code @Test} per acceptance scenario (a)..(f) in M1-046.
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-046-grant-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes rows under the class-wide prefix.
 * {@code audit_log} is append-only (V5 {@code trg_audit_log_*}
 * triggers); cleanup temporarily disables those triggers in a
 * try/finally so the table cannot be left without its invariant.</p>
 *
 * @implNote Canonical thin-SQL handler exception per
 *     {@code docs/process/test-pyramid.md} §Shape B: Thin-SQL.
 */
@QuarkusTest
class GrantAdminCommandHandlerTest {

    private static final String PREFIX = "m1-046-grant-";
    private static final String ADAPTER = "inmemory";
    private static final String SIMPLEX_ADAPTER = "simplex-mock";
    private static final String SIGNAL_ADAPTER = "signal-mock";

    @Inject GrantAdminCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the test-row DELETE cascade. The
            // guardian's contact_id is outside the PREFIX so cleanup
            // never collects it.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-046-grant-permanent");
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
    void grantByNonAdminReturnsAdminOnly() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        String target = PREFIX + "nonAdmin-target";
        UUID actorId = seedUser(ADAPTER, actor, /* isAdmin */ false, false);
        seedUser(ADAPTER, target, false, false);
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "nonAdmin-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/grant-admin " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /grant-admin must surface error.admin_only");
        assertFalse(isAdmin(ADAPTER, target),
                "target's is_admin must remain false on the admin-gate reject");
        assertEquals(auditBefore, countAuditUnderTargetPrefix(PREFIX + "nonAdmin-"),
                "non-admin /grant-admin must not write any audit row");
        assertEquals(0L, countAuditByActor(actorId),
                "the non-admin dispatch must write zero audit rows of ANY action — "
                        + "the permission check fails at spec step 7, so the step-8 "
                        + "intent write is never reached");
    }

    // ----- (b) Unknown contact → error.contact_not_registered ---------------
    // The reply is unchanged, but the admin-authorized dispatch passes
    // the spec step-7 permission check, so the step-8 GRANT_ADMIN_INTENT
    // row survives the step-5c rollback — an admin probing for
    // registered contacts is no longer invisible to the audit log
    // (the M1-151/M1-173 /revoke-admin AUDIT-EVASION class on the
    // mirror command), and the row's target_registered=false marks
    // its synthetic target_id.

    @Test
    void grantUnknownContactWritesIntentRow() throws Exception {
        String actor = PREFIX + "unknown-actor";
        String unknown = PREFIX + "unknown-target";
        seedUser(ADAPTER, actor, /* isAdmin */ true, false);
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "unknown-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/grant-admin " + unknown);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED), reply.text(),
                "unknown contact must surface error.contact_not_registered");
        assertEquals(auditBefore + 1, countAuditUnderTargetPrefix(PREFIX + "unknown-"),
                "exactly one audit row survives the unknown-contact refusal");
        assertEquals(1L, countAuditByActionAndTarget("GRANT_ADMIN_INTENT", unknown),
                "the admin-authorized probe against an unregistered contact must leave "
                        + "a surviving GRANT_ADMIN_INTENT row");
        assertEquals(0L, countAuditByActionAndTarget("GRANT_ADMIN", unknown),
                "no GRANT_ADMIN effect row may exist for the refused attempt");
        // jsonb canonicalizes details_json on read-back (one space
        // after each colon), so the assertion matches that form.
        String detailsJson = detailsJsonOfIntentRow(unknown);
        assertTrue(detailsJson.contains("\"target_registered\": false"),
                "the intent row against an unregistered contact must mark its synthetic "
                        + "target_id with target_registered=false; got: " + detailsJson);
    }

    // ----- (c) Banned target → error.grant_admin.banned_target --------------
    // The reply is unchanged, but the admin-authorized dispatch passes
    // the spec step-7 permission check, so the step-8 GRANT_ADMIN_INTENT
    // row survives the step-5d rollback — an admin probing for ban
    // state is no longer invisible to the audit log. The registered
    // target's intent row carries target_registered=true.

    @Test
    void grantBannedTargetWritesIntentRow() throws Exception {
        String actor = PREFIX + "banned-actor";
        String target = PREFIX + "banned-target";
        seedUser(ADAPTER, actor, /* isAdmin */ true, false);
        seedUser(ADAPTER, target, /* isAdmin */ false, /* isBanned */ true);
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "banned-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/grant-admin " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_GRANT_ADMIN_BANNED_TARGET), reply.text(),
                "banned target must surface error.grant_admin.banned_target");
        assertFalse(isAdmin(ADAPTER, target),
                "banned-target reject must not flip is_admin");
        assertEquals(auditBefore + 1, countAuditUnderTargetPrefix(PREFIX + "banned-"),
                "exactly one audit row survives the banned-target refusal");
        assertEquals(1L, countAuditByActionAndTarget("GRANT_ADMIN_INTENT", target),
                "the admin-authorized probe against a banned contact must leave "
                        + "a surviving GRANT_ADMIN_INTENT row");
        assertEquals(0L, countAuditByActionAndTarget("GRANT_ADMIN", target),
                "no GRANT_ADMIN effect row may exist for the refused attempt");
        // jsonb canonical form — see the spacing note in test (b).
        String detailsJson = detailsJsonOfIntentRow(target);
        assertTrue(detailsJson.contains("\"target_registered\": true"),
                "the intent row against a registered target must carry "
                        + "target_registered=true; got: " + detailsJson);
    }

    // ----- (d) Already admin → error.grant_admin.already_admin (no-op) ------
    // The users-table no-op keeps its reply, but the admin-authorized
    // dispatch passes the spec step-7 permission check, so the step-8
    // GRANT_ADMIN_INTENT row survives the step-5e rollback — an admin
    // probing for who holds the admin bit is no longer invisible to
    // the audit log. The registered target's intent row carries
    // target_registered=true.

    @Test
    void grantAlreadyAdminWritesIntentRow() throws Exception {
        String actor = PREFIX + "already-actor";
        String target = PREFIX + "already-target";
        seedUser(ADAPTER, actor, /* isAdmin */ true, false);
        seedUser(ADAPTER, target, /* isAdmin */ true, false);
        long auditBefore = countAuditUnderTargetPrefix(PREFIX + "already-");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/grant-admin " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_GRANT_ADMIN_ALREADY_ADMIN), reply.text(),
                "already-admin target must surface error.grant_admin.already_admin");
        assertTrue(isAdmin(ADAPTER, target),
                "target's is_admin must remain true (no-op)");
        assertEquals(auditBefore + 1, countAuditUnderTargetPrefix(PREFIX + "already-"),
                "exactly one audit row survives the already-admin no-op");
        assertEquals(1L, countAuditByActionAndTarget("GRANT_ADMIN_INTENT", target),
                "the admin-authorized probe against an already-admin target must leave "
                        + "a surviving GRANT_ADMIN_INTENT row");
        assertEquals(0L, countAuditByActionAndTarget("GRANT_ADMIN", target),
                "no GRANT_ADMIN effect row may exist for the refused no-op attempt");
        // jsonb canonical form — see the spacing note in test (b).
        String detailsJson = detailsJsonOfIntentRow(target);
        assertTrue(detailsJson.contains("\"target_registered\": true"),
                "the intent row against a registered target must carry "
                        + "target_registered=true; got: " + detailsJson);
    }

    // ----- (e) Happy path: UPDATE + GRANT_ADMIN audit row -------------------

    @Test
    void grantHappyPathFlipsIsAdminAndWritesAudit() throws Exception {
        String actor = PREFIX + "happy-actor";
        String target = PREFIX + "happy-target";
        UUID actorId = seedUser(ADAPTER, actor, /* isAdmin */ true, false);
        UUID targetId = seedUser(ADAPTER, target, /* isAdmin */ false, false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/grant-admin " + target);

        assertEquals(redactedSuccess(target), reply.text(),
                "happy path success reply must interpolate the redacted target contact id");
        assertTrue(isAdmin(ADAPTER, target),
                "target's is_admin must flip to true on the happy path");

        // GRANT_ADMIN audit row: actor + target identities, target_kind='user',
        // target_id = target.id::text, details_json carries target_adapter.
        String effectRequestId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT actor_user_id, actor_contact_id, actor_adapter, "
                             + "target_kind, target_id, target_contact_id, details_json, "
                             + "request_id "
                             + "FROM audit_log WHERE action = 'GRANT_ADMIN' "
                             + "  AND target_contact_id = ?")) {
            ps.setString(1, target);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "exactly one GRANT_ADMIN audit row must exist for the target");
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
                assertTrue(detailsJson.contains(ADAPTER),
                        "details_json must carry the inbound adapter value; got: " + detailsJson);
                effectRequestId = rs.getString("request_id");
                assertFalse(rs.next(),
                        "no second GRANT_ADMIN audit row may exist for the target");
            }
        }
        assertNotNull(effectRequestId, "the GRANT_ADMIN effect row must carry a request_id");
        assertEquals(1L, countAuditByActionAndRequestId("GRANT_ADMIN_INTENT", effectRequestId),
                "the committed GRANT_ADMIN effect row must share its request_id with "
                        + "exactly one GRANT_ADMIN_INTENT row (intent↔effect correlation)");
    }

    // ----- (f) Per-adapter scoping isolation --------------------------------
    // Seed (simplex-mock, alice) AND (signal-mock, alice); issue
    // /grant-admin alice from a SimpleX inbound. ONLY the SimpleX row
    // gains is_admin=true; the Signal row is UNCHANGED.

    @Test
    void grantOnSameContactIdAcrossAdaptersOnlyTouchesInboundAdapter() throws Exception {
        String alice = PREFIX + "scoping-alice";
        String actor = PREFIX + "scoping-actor";
        // Actor is a bot admin on the SimpleX-mock adapter.
        seedUser(SIMPLEX_ADAPTER, actor, /* isAdmin */ true, false);
        // Two `alice` rows — different adapters, same contact_id.
        seedUser(SIMPLEX_ADAPTER, alice, /* isAdmin */ false, false);
        seedUser(SIGNAL_ADAPTER, alice, /* isAdmin */ false, false);

        // Inbound adapter is SimpleX-mock for this call.
        inboundContext.setAdapterName(SIMPLEX_ADAPTER);
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/grant-admin " + alice);

        assertEquals(redactedSuccess(alice), reply.text(),
                "SimpleX inbound /grant-admin alice must surface the success reply");
        assertTrue(isAdmin(SIMPLEX_ADAPTER, alice),
                "(simplex-mock, alice).is_admin must flip to true");
        assertFalse(isAdmin(SIGNAL_ADAPTER, alice),
                "(signal-mock, alice).is_admin must remain false — the lookup is inbound-adapter-scoped");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String adapter, String contactId, boolean isAdmin,
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

    private boolean isAdmin(String adapter, String contactId) throws Exception {
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
                     "SELECT details_json FROM audit_log WHERE action = 'GRANT_ADMIN_INTENT' "
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
                bundleLoader.get(BundleKeys.REPLY_GRANT_ADMIN_SUCCESS),
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
