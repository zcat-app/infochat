package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests for {@link VouchCommandHandler} against the
 * DevServices Postgres container. Covers the D47 /vouch contract
 * (M1-111): the command clears {@code probation_until} but no longer
 * advances {@code registration_state}. Scenarios: non-admin →
 * admin_only; unknown contact → contact_not_registered; registered
 * user in probation → probation cleared + success; past-probation
 * user → no-op; banned target → banned_target; happy path runs in
 * one transaction.
 *
 * <p>Test isolation: each {@code @Test} uses a unique sub-prefix
 * within the class-wide {@code PREFIX} ({@code m1-045-vouch-});
 * {@link #cleanup()} disables the V5 audit-log append-only triggers
 * for the cleanup pass (we own the table) so audit rows from prior
 * runs can be deleted alongside the users they reference. The
 * triggers are re-enabled in {@code finally} so the invariant is
 * intact for the test body and any other concurrent reader.
 *
 * @implNote Canonical thin-SQL handler exception per
 *     {@code docs/process/test-pyramid.md} §Shape B: Thin-SQL.
 */
@QuarkusTest
class VouchCommandHandlerTest {

    private static final String PREFIX = "m1-045-vouch-";
    private static final String ADAPTER = "inmemory";

    @Inject VouchCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin survives cleanup so the last-admin trigger
            // does not refuse the DELETE on test admins below.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-045-vouch-permanent");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                                + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    // ----- (M1-198) group scope → command_dm_only, before caller resolution -

    @Test
    void vouchInGroupScopeReturnsCommandDmOnly() throws Exception {
        // Bot-global admin command is DM-only: the group-scope guard
        // returns the accurate scope error before caller resolution and
        // before any transaction opens.
        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(PREFIX + "grp-dm-only"), "/vouch " + PREFIX + "someone");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY), reply.text(),
                "/vouch in group scope must return error.command_dm_only");
    }

    // ----- (a) non-admin → error.admin_only, no DB write --------------------

    @Test
    void vouchByNonAdminReturnsAdminOnly() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        String target = PREFIX + "nonAdmin-target";
        seedUser(actor, /* isAdmin */ false, "invited", Instant.now().plus(1, ChronoUnit.HOURS));
        UUID targetId = seedUser(target, /* isAdmin */ false, "invited",
                Instant.now().plus(2, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text());
        // No DB write: target untouched.
        assertEquals("invited", readRegistrationState(targetId));
        assertNotNull(readProbationUntil(targetId),
                "non-admin /vouch must not null probation_until");
        assertEquals(0L, countVouchAuditRows(targetId),
                "non-admin /vouch must write no VOUCH audit row");
    }

    // ----- (b) unknown contact → error.contact_not_registered, no DB write --

    @Test
    void vouchUnknownContactReturnsContactNotRegistered() throws Exception {
        String actor = PREFIX + "unknown-actor";
        String absent = PREFIX + "unknown-absent";
        seedUser(actor, /* isAdmin */ true, "vouched", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + absent);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED), reply.text());
        // No row written for absent contact.
        assertNull(userId(absent), "/vouch must not synthesize a users row for unknown contact");
    }

    // ----- post-permission refusal leaves a VOUCH_INTENT row -----------------

    @Test
    void vouchUnknownContactRefusalLeavesVouchIntentAuditRow() throws Exception {
        // Spec §Authorization model step 8 precedes step 9: an admin's
        // probe that fails an execution-semantics check (here:
        // unknown contact) must still leave a surviving intent row —
        // a distinct verb from the VOUCH effect row.
        String actor = PREFIX + "intent-actor";
        String absent = PREFIX + "intent-absent";
        seedUser(actor, /* isAdmin */ true, "vouched", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + absent);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED), reply.text());
        assertEquals(1L, countAuditRowsByTargetContact("VOUCH_INTENT", absent),
                "the admin's refused probe must leave exactly one surviving VOUCH_INTENT row");
        assertEquals(0L, countAuditRowsByTargetContact("VOUCH", absent),
                "the refused probe must not write a VOUCH effect row");
    }

    // ----- (c) registered user in probation → probation clears, state kept --

    @Test
    void vouchInProbationClearsProbationAndKeepsState() throws Exception {
        String actor = PREFIX + "inProb-actor";
        String target = PREFIX + "inProb-target";
        seedUser(actor, /* isAdmin */ true, "vouched", null);
        UUID targetId = seedUser(target, /* isAdmin */ false, "invited",
                Instant.now().plus(1, ChronoUnit.HOURS));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + target);

        assertEquals(bundleLoader.get(BundleKeys.REPLY_VOUCH_SUCCESS), reply.text());
        // Per D47 /vouch clears probation only; registration_state is unchanged.
        assertEquals("invited", readRegistrationState(targetId),
                "registration_state must be unchanged — D47 removed the /vouch state advance");
        assertNull(readProbationUntil(targetId),
                "probation_until must be NULL after happy-path /vouch");
        assertEquals(1L, countVouchAuditRows(targetId));
        VouchDetails details = readVouchDetails(targetId);
        assertNotNull(details, "VOUCH audit row must exist");
        assertEquals("true", details.probationCleared(),
                "details_json must carry probation_cleared=true");
    }

    // ----- (f) invited past probation → no-op, no audit row -----------------

    @Test
    void vouchInvitedPastProbationIsNoOp() throws Exception {
        String actor = PREFIX + "invitedNoop-actor";
        String target = PREFIX + "invitedNoop-target";
        seedUser(actor, /* isAdmin */ true, "vouched", null);
        UUID targetId = seedUser(target, /* isAdmin */ false, "invited", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + target);

        assertEquals(bundleLoader.get(BundleKeys.REPLY_VOUCH_NOOP), reply.text(),
                "invited + past-probation /vouch must surface the no-op reply");
        assertEquals(0L, countVouchAuditRows(targetId),
                "no-op /vouch must write no VOUCH audit row — matches the M1-036 / /unban pattern");
    }

    // ----- (g) vouched past probation → no-op -------------------------------

    @Test
    void vouchVouchedPastProbationIsNoOp() throws Exception {
        String actor = PREFIX + "vouchedNoop-actor";
        String target = PREFIX + "vouchedNoop-target";
        seedUser(actor, /* isAdmin */ true, "vouched", null);
        UUID targetId = seedUser(target, /* isAdmin */ false, "vouched", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + target);

        assertEquals(bundleLoader.get(BundleKeys.REPLY_VOUCH_NOOP), reply.text(),
                "vouched + past-probation /vouch must surface the no-op reply");
        assertEquals(0L, countVouchAuditRows(targetId),
                "no-op /vouch must write no VOUCH audit row");
    }

    // ----- (j) non-admin + unknown contact → admin_only, NOT contact_not_registered

    @Test
    void vouchByNonAdminAgainstUnknownContactReturnsAdminOnlyNotContactNotRegistered() throws Exception {
        // M1-045 redteam-fix round 2 INFO-LEAK closure: with admin
        // check FIRST inside the tx, a non-admin caller invoking
        // /vouch on ANY target (existing OR unknown) receives the
        // same error.admin_only reply. Before this fix, the handler
        // ordering (target lookup before admin check) let a non-admin
        // distinguish unknown-contact (error.contact_not_registered)
        // from existing-contact (error.admin_only), enabling user-
        // existence probing across the deployment.
        String actor = PREFIX + "infoleak-nonAdmin-actor";
        String absent = PREFIX + "infoleak-absent-target";
        seedUser(actor, /* isAdmin */ false, "invited", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + absent);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin must receive error.admin_only (NOT contact_not_registered) "
                        + "even when the named target does not exist — admin gate fires first");
        // Defense in depth: no row synthesized for the unknown target.
        assertNull(userId(absent),
                "non-admin /vouch must not synthesize a row for the unknown target");
    }

    // ----- (i) banned target → error.vouch.banned_target, no DB write -------

    @Test
    void vouchBannedTargetReturnsBannedTarget() throws Exception {
        // M1-045 redteam-fix OUT-OF-MODEL #1: /vouch must refuse to
        // advance a banned user past probation. Intake step 4 still
        // blocks the contact from any access, so vouching them would
        // strand state into a misleading shape for the operator's
        // later /unban pass. Assert: no UPDATE, no audit row, banned
        // row's columns preserved verbatim.
        String actor = PREFIX + "bannedTarget-actor";
        String target = PREFIX + "bannedTarget-target";
        Instant originalProbation = Instant.now().plus(1, ChronoUnit.HOURS);
        seedUser(actor, /* isAdmin */ true, "vouched", null);
        UUID targetId = seedBannedUser(target, "invited", originalProbation);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + target);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_VOUCH_BANNED_TARGET), reply.text());
        // No DB write: target row preserved.
        assertEquals("invited", readRegistrationState(targetId),
                "banned-target /vouch must NOT change registration_state or clear probation");
        assertNotNull(readProbationUntil(targetId),
                "banned-target /vouch must NOT null probation_until");
        assertEquals(0L, countVouchAuditRows(targetId),
                "banned-target /vouch must write no VOUCH audit row");
    }

    // ----- (h) UPDATE runs in ONE transaction -------------------------------

    @Test
    void vouchHappyPathRunsInOneTransaction() throws Exception {
        String actor = PREFIX + "tx-actor";
        String target = PREFIX + "tx-target";
        seedUser(actor, /* isAdmin */ true, "vouched", null);
        seedUser(target, /* isAdmin */ false, "invited",
                Instant.now().plus(1, ChronoUnit.HOURS));

        // Wrap the handler's DataSource to record BEGIN/COMMIT/ROLLBACK
        // calls. The package-private field injection lets the test
        // swap in a recording delegate without altering the handler
        // contract; we restore the real DataSource in finally.
        //
        // ClientProxy.unwrap: the @Inject'd handler is the @Application
        // Scoped ARC client-proxy. A field assignment on the proxy
        // hits the proxy's inherited field slot, NOT the underlying
        // bean instance whose body actually reads this.dataSource —
        // so without unwrap, the wrapper would never be observed and
        // txCalls would stay empty. Resolve the contextual instance
        // explicitly and write the field there.
        List<String> txCalls = new ArrayList<>();
        VouchCommandHandler bean = ClientProxy.unwrap(handler);
        DataSource real = bean.dataSource;
        bean.dataSource = new TxRecordingDataSource(real, txCalls);
        try {
            OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/vouch " + target);
            assertEquals(bundleLoader.get(BundleKeys.REPLY_VOUCH_SUCCESS), reply.text());
        } finally {
            bean.dataSource = real;
        }

        // The handler opens TWO connections during a happy-path call:
        // (1) lookupUser for the actor's admin gate, and
        // (2) lookupUser for the target — both with autoCommit=true
        // (no BEGIN/COMMIT recorded for those).
        // Then the third connection opens the transaction. The
        // recorded transactional calls must contain exactly one
        // setAutoCommit(false) followed by exactly one commit(), and
        // no rollback. This proves the UPDATE + audit-row INSERT
        // ran together as one transaction per acceptance item 10(h).
        assertEquals(List.of("BEGIN", "COMMIT"), txCalls,
                "happy-path /vouch must execute exactly one BEGIN/COMMIT pair "
                        + "(audit-row INSERT + UPDATE inside the same transaction); got: " + txCalls);
    }

    // ----- helpers ----------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin, String registrationState,
                          Instant probationUntil) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state, "
                             + "probation_until) "
                             + "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setString(4, registrationState);
            if (probationUntil == null) {
                ps.setNull(5, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(5, Timestamp.from(probationUntil));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    /**
     * Seeds a row with {@code is_banned=true} + {@code banned_at=NOW()}
     * alongside the supplied {@code registrationState} and
     * {@code probationUntil}. Used by the (i) banned-target scenario;
     * mirrors the M1-044c {@code BanCommandHandler} write pattern
     * (is_admin=false, banned_at populated).
     */
    private UUID seedBannedUser(String contactId, String registrationState,
                                Instant probationUntil) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state, "
                             + "probation_until, is_banned, banned_at) "
                             + "VALUES (?, ?, FALSE, ?, ?, TRUE, NOW()) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setString(3, registrationState);
            if (probationUntil == null) {
                ps.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(4, Timestamp.from(probationUntil));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID userId(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return (UUID) rs.getObject("id");
            }
        }
    }

    private String readRegistrationState(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT registration_state FROM users WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private Timestamp readProbationUntil(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT probation_until FROM users WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("probation_until");
            }
        }
    }

    private long countAuditRowsByTargetContact(String action, String targetContactId)
            throws Exception {
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

    private long countVouchAuditRows(UUID targetId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = 'VOUCH' AND target_id = ?")) {
            ps.setString(1, targetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Audit-row {@code details_json} extracted via jsonb-operator
     * projection rather than substring matching on the canonicalized
     * text. Postgres rewrites jsonb text on read (key reorder, space
     * after colon), so a brittle {@code contains("\"k\":\"v\"")}
     * assertion would flake on the same row that {@code ->>'k' = 'v'}
     * pins deterministically.
     */
    private record VouchDetails(String probationCleared) {}

    private VouchDetails readVouchDetails(UUID targetId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT details_json->>'probation_cleared' AS pc "
                             + "FROM audit_log WHERE action = 'VOUCH' AND target_id = ?")) {
            ps.setString(1, targetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new VouchDetails(rs.getString("pc"));
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

    /**
     * {@link DataSource} that delegates every call to the real Agroal
     * pool but wraps each returned {@link Connection} with a
     * {@link Proxy} that records {@code setAutoCommit(false)} as
     * {@code "BEGIN"} and {@code commit()} / {@code rollback()} as
     * {@code "COMMIT"} / {@code "ROLLBACK"} into the supplied list.
     * Used by scenario (h) to assert the handler opened exactly one
     * application-side transaction around the audit + UPDATE pair.
     */
    private static final class TxRecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<String> calls;

        TxRecordingDataSource(DataSource delegate, List<String> calls) {
            this.delegate = delegate;
            this.calls = calls;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection real) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, methodArgs) -> {
                        switch (method.getName()) {
                            case "setAutoCommit":
                                if (Boolean.FALSE.equals(methodArgs[0])) {
                                    calls.add("BEGIN");
                                }
                                return method.invoke(real, methodArgs);
                            case "commit":
                                calls.add("COMMIT");
                                return method.invoke(real, methodArgs);
                            case "rollback":
                                calls.add("ROLLBACK");
                                return method.invoke(real, methodArgs);
                            default:
                                return method.invoke(real, methodArgs);
                        }
                    });
        }

        @Override public java.io.PrintWriter getLogWriter() throws java.sql.SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException { delegate.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws java.sql.SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws java.sql.SQLException { return delegate.getLoginTimeout(); }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("TxRecordingDataSource"); }
        @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException { return delegate.isWrapperFor(iface); }
    }
}
