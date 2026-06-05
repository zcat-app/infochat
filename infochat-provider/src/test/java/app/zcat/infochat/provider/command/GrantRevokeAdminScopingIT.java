package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
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
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end IT for {@link GrantAdminCommandHandler} and
 * {@link RevokeAdminCommandHandler}, exercising the per-adapter
 * scoping + the global last-admin counter through the fully-wired
 * Provider stack (InMemoryAdapter → InboundRouter → handler).
 *
 * <p><b>Two-adapter shape.</b> The AdapterRegistry registers a single
 * {@code MessagingAdapter} bean ({@link InMemoryAdapter}, name
 * {@code "inmemory"}); gates 2 + 5 forbid running two beans under
 * different names today. T3-A lands the real SimpleX/Signal beans.
 * The substantive per-adapter-scoping contract — that
 * {@code (adapter, contact_id)} bounds the handler's SELECT — is
 * exercised by seeding {@code users} rows on a SECOND virtual adapter
 * name ({@code "signal-mock"}) via direct INSERT and verifying the
 * inmemory inbound mutates ONLY inmemory rows.</p>
 *
 * <p>Scenarios (per M1-046 acceptance items [5] + [6]):
 * <ol>
 *   <li>(a) /grant-admin from inmemory adds the inmemory row; any
 *       signal-mock row with the same contact_id is unchanged.</li>
 *   <li>(b) /revoke-admin from inmemory flips the inmemory row; the
 *       signal-mock row with the same contact_id is unchanged.</li>
 *   <li>(c) /revoke-admin targeting a contact that exists ONLY on
 *       signal-mock returns {@code error.contact_not_registered}
 *       (the inbound-adapter-scoped SELECT misses on inmemory).</li>
 *   <li>(d) Multi-admin same-adapter chained-revoke regression: with
 *       three admins on inmemory and zero elsewhere, revoke two of
 *       them in sequence; both succeed, the V5 last-admin trigger
 *       does NOT spuriously fire while the count stays >= 1.</li>
 * </ol>
 */
@QuarkusTest
@TestProfile(GrantRevokeAdminScopingIT.ScopingProfile.class)
class GrantRevokeAdminScopingIT {

    private static final String INMEMORY = "inmemory";
    private static final String SIGNAL_MOCK = "signal-mock";
    private static final String PREFIX = "m1-046-scoping-";
    private static final String GUARDIAN = "guardian-m1-046-scoping-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin (outside PREFIX) keeps the V5
            // last-admin trigger satisfied for per-test admin DELETEs.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    INMEMORY, GUARDIAN);
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

    // ----- (a) /grant-admin: per-adapter add --------------------------------

    @Test
    void grantAdminFromInmemoryInboundOnlyTouchesInmemoryRow() throws Exception {
        String admin = PREFIX + "grantA-admin";
        String bob = PREFIX + "grantA-bob";
        seedUser(INMEMORY, admin, /* isAdmin */ true, false);
        seedUser(INMEMORY, bob, /* isAdmin */ false, false);
        // Same contact_id seeded on the OTHER (virtual) adapter; the
        // per-adapter-scoped SELECT must NOT see this row from an
        // inmemory inbound.
        seedUser(SIGNAL_MOCK, bob, /* isAdmin */ false, false);

        adapter.deliverDm(admin, "/grant-admin " + bob);
        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "step (a): /grant-admin must produce exactly one outbound");
        assertEquals(redactedGrantSuccess(bob), sent.get(0).text(),
                "step (a): outbound body must be reply.grant_admin.success with redacted target");

        assertTrue(isAdmin(INMEMORY, bob),
                "step (a): (inmemory, bob).is_admin must flip to true");
        assertFalse(isAdmin(SIGNAL_MOCK, bob),
                "step (a): (signal-mock, bob).is_admin must remain false — the lookup is "
                        + "inbound-adapter-scoped");
        assertEquals(1L, countAuditByActionAndTargetAndAdapter("GRANT_ADMIN", bob, INMEMORY),
                "step (a): exactly one GRANT_ADMIN audit row must exist for (inmemory, bob)");
        assertEquals(0L, countAuditByActionAndTargetAndAdapter("GRANT_ADMIN", bob, SIGNAL_MOCK),
                "step (a): no GRANT_ADMIN audit row may exist for (signal-mock, bob) — the "
                        + "inbound adapter NEVER widens cross-adapter");
    }

    // ----- (b) /revoke-admin: per-adapter revoke ----------------------------

    @Test
    void revokeAdminFromInmemoryInboundOnlyTouchesInmemoryRow() throws Exception {
        String admin = PREFIX + "revokeB-admin";
        String alice = PREFIX + "revokeB-alice";
        seedUser(INMEMORY, admin, /* isAdmin */ true, false);
        seedUser(INMEMORY, alice, /* isAdmin */ true, false);
        // Mirror admin alice on signal-mock so the global qualifying
        // count is 3 (admin, alice-on-inmemory, alice-on-signal); the
        // revoke takes one of the three and the V5 trigger passes.
        seedUser(SIGNAL_MOCK, alice, /* isAdmin */ true, false);

        adapter.deliverDm(admin, "/revoke-admin " + alice);
        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "step (b): /revoke-admin must produce exactly one outbound");
        assertEquals(redactedRevokeSuccess(alice), sent.get(0).text(),
                "step (b): outbound body must be reply.revoke_admin.success with redacted target");

        assertFalse(isAdmin(INMEMORY, alice),
                "step (b): (inmemory, alice).is_admin must flip to false");
        assertTrue(isAdmin(SIGNAL_MOCK, alice),
                "step (b): (signal-mock, alice).is_admin must remain true — the inbound "
                        + "adapter scopes the UPDATE");
        assertEquals(1L, countAuditByActionAndTargetAndAdapter("REVOKE_ADMIN", alice, INMEMORY),
                "step (b): exactly one REVOKE_ADMIN audit row for (inmemory, alice)");
        assertEquals(0L, countAuditByActionAndTargetAndAdapter("REVOKE_ADMIN", alice, SIGNAL_MOCK),
                "step (b): no REVOKE_ADMIN audit row may exist for (signal-mock, alice)");
    }

    // ----- (c) Cross-adapter target: contact_not_registered -----------------
    // /revoke-admin targets a contact that exists ONLY on signal-mock;
    // the inmemory-scoped SELECT misses and the handler surfaces
    // error.contact_not_registered.

    @Test
    void revokeAdminWithCrossAdapterTargetReturnsContactNotRegistered() throws Exception {
        String admin = PREFIX + "revokeC-admin";
        String signalOnly = PREFIX + "revokeC-signalOnly";
        seedUser(INMEMORY, admin, /* isAdmin */ true, false);
        // signalOnly exists ONLY on signal-mock — never seeded on inmemory.
        seedUser(SIGNAL_MOCK, signalOnly, /* isAdmin */ true, false);

        adapter.deliverDm(admin, "/revoke-admin " + signalOnly);
        List<OutboundMessage> sent = adapter.sentMessages();
        assertEquals(1, sent.size(),
                "step (c): /revoke-admin must produce exactly one outbound");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED),
                sent.get(0).text(),
                "step (c): cross-adapter target must surface error.contact_not_registered");

        // The signal-mock target row must be unchanged — the handler
        // never resolved it.
        assertTrue(isAdmin(SIGNAL_MOCK, signalOnly),
                "step (c): (signal-mock, signalOnly).is_admin must remain true — the "
                        + "inmemory inbound never reaches this row");
        assertEquals(0L, countAuditByActionAndTargetAndAdapter("REVOKE_ADMIN", signalOnly, SIGNAL_MOCK),
                "step (c): no REVOKE_ADMIN audit row may exist (the handler short-circuited "
                        + "at the per-adapter contact_not_registered branch)");
    }

    // ----- (d) Multi-admin chained-revoke regression ------------------------
    // Seed three admins on inmemory; explicitly remove the guardian +
    // any baseline cross-adapter admins so the global count starts at
    // exactly 3. Revoke admin-2 (count 3 → 2) then admin-1 (count
    // 2 → 1); both succeed, the V5 trigger never fires.

    @Test
    void multiAdminChainedRevokeOnSameAdapterDoesNotFireTrigger() throws Exception {
        String a1 = PREFIX + "chain-admin-1";
        String a2 = PREFIX + "chain-admin-2";
        String a3 = PREFIX + "chain-admin-3";
        seedUser(INMEMORY, a1, /* isAdmin */ true, false);
        seedUser(INMEMORY, a2, /* isAdmin */ true, false);
        seedUser(INMEMORY, a3, /* isAdmin */ true, false);

        // Demote guardian + remove any baseline cross-adapter admins so
        // the global qualifying count starts at exactly 3 (the three
        // seeded above). Restored in finally so subsequent tests can
        // rely on the guardian as the last-admin-protection floor.
        setGuardianAdmin(false);
        try {
            long auditBefore = countAuditByActionPrefix("REVOKE_ADMIN", PREFIX + "chain-");

            // First revoke: count 3 → 2.
            adapter.deliverDm(a1, "/revoke-admin " + a2);
            List<OutboundMessage> sent1 = adapter.sentMessages();
            assertEquals(1, sent1.size(),
                    "step (d).1: first /revoke-admin must produce exactly one outbound");
            assertEquals(redactedRevokeSuccess(a2), sent1.get(0).text(),
                    "step (d).1: outbound body must be reply.revoke_admin.success");
            assertFalse(isAdmin(INMEMORY, a2),
                    "step (d).1: (inmemory, a2).is_admin must flip to false");
            assertTrue(isAdmin(INMEMORY, a1),
                    "step (d).1: (inmemory, a1).is_admin must remain true");
            assertTrue(isAdmin(INMEMORY, a3),
                    "step (d).1: (inmemory, a3).is_admin must remain true");
            adapter.reset();

            // Second revoke: count 2 → 1.
            adapter.deliverDm(a3, "/revoke-admin " + a1);
            List<OutboundMessage> sent2 = adapter.sentMessages();
            assertEquals(1, sent2.size(),
                    "step (d).2: second /revoke-admin must produce exactly one outbound");
            assertEquals(redactedRevokeSuccess(a1), sent2.get(0).text(),
                    "step (d).2: outbound body must be reply.revoke_admin.success");
            assertFalse(isAdmin(INMEMORY, a1),
                    "step (d).2: (inmemory, a1).is_admin must flip to false");
            assertTrue(isAdmin(INMEMORY, a3),
                    "step (d).2: (inmemory, a3).is_admin must remain true — the sole "
                            + "remaining admin, not the target of this revoke");

            assertEquals(auditBefore + 2,
                    countAuditByActionPrefix("REVOKE_ADMIN", PREFIX + "chain-"),
                    "step (d): two REVOKE_ADMIN audit rows must exist for the chain");
        } finally {
            setGuardianAdmin(true);
        }
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String adapterName, String contactId, boolean isAdmin,
                          boolean isBanned) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at) "
                             + "VALUES (?, ?, ?, ?, 'vouched', "
                             + "CASE WHEN ? THEN NOW() ELSE NULL END) "
                             + "RETURNING id")) {
            ps.setString(1, adapterName);
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

    private boolean isAdmin(String adapterName, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapterName);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean("is_admin");
            }
        }
    }

    private long countAuditByActionAndTargetAndAdapter(String action, String targetContactId,
                                                       String adapterName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? "
                             + "  AND target_contact_id = ? AND actor_adapter = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetContactId);
            ps.setString(3, adapterName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditByActionPrefix(String action, String targetPrefix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? "
                             + "  AND target_contact_id LIKE ?")) {
            ps.setString(1, action);
            ps.setString(2, targetPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void setGuardianAdmin(boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET is_admin = ? WHERE adapter = ? AND contact_id = ?")) {
            ps.setBoolean(1, isAdmin);
            ps.setString(2, INMEMORY);
            ps.setString(3, GUARDIAN);
            ps.executeUpdate();
        }
    }

    private String redactedGrantSuccess(String contactId) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_GRANT_ADMIN_SUCCESS),
                ContactIds.redact(contactId));
    }

    private String redactedRevokeSuccess(String contactId) {
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

    /**
     * Configures the AdapterRegistry to register exactly the
     * {@link InMemoryAdapter} under name {@code "inmemory"} with
     * {@code allow-low-trust=true}; matches the
     * {@link app.zcat.infochat.provider.messaging.AdapterRouterIT}
     * profile shape.
     */
    public static final class ScopingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }
    }
}
