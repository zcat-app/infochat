package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.GrantAdminCommandHandler;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-105 multi-adapter isolation IT. Proves the
 * {@code (adapter, contact_id)} identity boundary and the global
 * last-admin-protection counter operate together as
 * {@code docs/spec/security.md} §Authorization model promises, by
 * exercising two virtual adapter names against the live Postgres
 * container.
 *
 * <p>Why a single physical adapter bean. Gate 5 (production-exclusion
 * in {@link AdapterRegistry}) forbids the {@code "inmemory"} bean
 * from coexisting with any other adapter bean per {@code messaging.md}
 * §6.6. The IT scope is the DB isolation boundary
 * ({@code UNIQUE (adapter, contact_id)} per V5) and the V5
 * {@code trg_last_admin_protection_update} trigger, both of which key
 * off the {@code users.adapter} column rather than the activated
 * adapter beans. Two virtual adapter names ({@code "adapter-a"},
 * {@code "adapter-b"}) seeded via direct INSERT — mirroring the
 * proven {@code GrantRevokeAdminScopingIT} pattern — give the
 * acceptance items 7/8/9 their per-adapter row layout without
 * provoking gate 5. {@code AdapterRegistryTest.multiAdapterHappyPath}
 * already pins the two-CDI-bean activation shape at the wiring tier.
 *
 * <p>Why direct {@link GrantAdminCommandHandler#handle} invocation
 * for {@link #grantAdminScopedToInboundAdapter()}. The handler reads
 * the inbound adapter name from {@link InboundContext}; setting it
 * manually faithfully simulates an inbound dispatch from a chosen
 * adapter without needing two physical adapter beans. The same
 * approach underpins {@code GrantAdminCommandHandlerTest} for the
 * handler unit tests.
 *
 * <p>Per-test isolation discipline. Each test seeds rows under the
 * class-wide {@link #PREFIX} and the {@link #cleanup()} hook deletes
 * by prefix; {@code audit_log} triggers are temporarily disabled
 * during the prefix-scoped delete because the table is append-only at
 * runtime (V5 {@code trg_audit_log_no_update / no_delete}).
 * {@link #lastAdminProtectionGlobal()} captures the snapshot of every
 * pre-existing {@code is_admin = TRUE} contact and restores it in a
 * {@code finally} so other ITs sharing the JVM-wide Postgres
 * container see the same admin landscape they did before.
 */
@QuarkusTest
class MultiAdapterIsolationIT {

    private static final String PREFIX = "m1-105-iso-";
    // Two virtual adapter names exercised by the DB-level isolation
    // tests. Neither matches a registered CDI bean — they live only
    // in the users.adapter column and the InboundContext shim — so
    // gate 5 (inmemory + others) never trips.
    private static final String ADAPTER_A = "adapter-a";
    private static final String ADAPTER_B = "adapter-b";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject GrantAdminCommandHandler grantHandler;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
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

    // ----- (7) crossAdapterIsolation ----------------------------------------
    // Same contact_id string seeded on two different adapter values
    // produces two distinct user rows (V5 UNIQUE constraint is
    // (adapter, contact_id), not contact_id alone). A mutation on
    // (adapter-a, contact_id) leaves the (adapter-b, contact_id) row
    // untouched.

    @Test
    void crossAdapterIsolation() throws Exception {
        String sharedId = PREFIX + "isolation-sharedContactId";

        UUID idOnA = seedUser(ADAPTER_A, sharedId, /* isAdmin */ false, false);
        UUID idOnB = seedUser(ADAPTER_B, sharedId, /* isAdmin */ false, false);
        assertNotEquals(idOnA, idOnB,
                "same contact_id on different adapters MUST yield two distinct user ids");
        assertNotNull(idOnA);
        assertNotNull(idOnB);

        // Mutate (adapter-a, sharedId) — the (adapter-b, sharedId)
        // row must be untouched.
        setIsAdmin(ADAPTER_A, sharedId, true);
        assertTrue(isAdmin(ADAPTER_A, sharedId),
                "(adapter-a, sharedId).is_admin must reflect the per-adapter UPDATE");
        assertFalse(isAdmin(ADAPTER_B, sharedId),
                "(adapter-b, sharedId).is_admin must remain false — UNIQUE (adapter, "
                        + "contact_id) bounds the UPDATE to one row");

        // Mirror the inverse path: mutate the B row and confirm A is
        // unchanged. Two assertions in one test because the isolation
        // boundary is symmetric and the bidirectional check is one
        // line of additional setup per direction.
        setIsBanned(ADAPTER_B, sharedId, true);
        assertTrue(isBanned(ADAPTER_B, sharedId),
                "(adapter-b, sharedId).is_banned must reflect the per-adapter UPDATE");
        assertFalse(isBanned(ADAPTER_A, sharedId),
                "(adapter-a, sharedId).is_banned must remain false — symmetric isolation");
    }

    // ----- (8) grantAdminScopedToInboundAdapter -----------------------------
    // /grant-admin run from adapter-a flips ONLY the (adapter-a, target)
    // row; an identical contact_id on adapter-b is left untouched. The
    // inbound adapter name is read from InboundContext per
    // commands.md §Admin (bot admin); setting it manually simulates
    // dispatch from a chosen adapter.

    @Test
    void grantAdminScopedToInboundAdapter() throws Exception {
        String admin = PREFIX + "grantScope-admin";
        String target = PREFIX + "grantScope-target";

        seedUser(ADAPTER_A, admin, /* isAdmin */ true, false);
        seedUser(ADAPTER_A, target, /* isAdmin */ false, false);
        // Same contact_id mirrored on adapter-b; the inbound-from-A
        // /grant-admin must NOT touch this row.
        seedUser(ADAPTER_B, target, /* isAdmin */ false, false);

        inboundContext.setAdapterName(ADAPTER_A);
        inboundContext.setSenderContactId(admin);
        OutboundMessage reply = grantHandler.handle(
                new ScopeRef.Dm(admin), "/grant-admin " + target);

        assertEquals(redactedGrantSuccess(target), reply.text(),
                "outbound body must be reply.grant_admin.success with redacted target");
        assertTrue(isAdmin(ADAPTER_A, target),
                "(adapter-a, target).is_admin must flip to true");
        assertFalse(isAdmin(ADAPTER_B, target),
                "(adapter-b, target).is_admin must remain false — the per-adapter "
                        + "scoping rule from commands.md §Admin (bot admin) bounds the "
                        + "UPDATE to the inbound adapter");
    }

    // ----- (9) lastAdminProtectionGlobal ------------------------------------
    // V5 trg_last_admin_protection_update counts is_admin=TRUE rows
    // globally across adapters; revoking the deployment-wide last
    // admin raises 'last_admin_protection: ...' regardless of which
    // adapter the revoke targets. The test rebuilds the global admin
    // landscape to exactly two (one per virtual adapter) so the
    // count-to-zero scenario is observable, then restores every
    // pre-existing admin row in finally so concurrent ITs sharing the
    // JVM-wide Postgres container see the same is_admin landscape on
    // exit.

    @Test
    void lastAdminProtectionGlobal() throws Exception {
        List<UserKey> preExisting = snapshotPreExistingAdmins();

        String aliceOnA = PREFIX + "lastAdmin-alice-on-A";
        String bobOnB = PREFIX + "lastAdmin-bob-on-B";
        // Seed our two admins FIRST so the global count never dips
        // below 1 during the subsequent demotion of the pre-existing
        // landscape. The trigger only refuses the revoke that WOULD
        // drop the count to zero; any prior demotion that leaves
        // ≥1 admin remaining (here: us + every still-promoted
        // pre-existing) is fine.
        seedUser(ADAPTER_A, aliceOnA, /* isAdmin */ true, false);
        seedUser(ADAPTER_B, bobOnB, /* isAdmin */ true, false);

        try {
            // Demote every pre-existing admin. After this loop the
            // only is_admin=TRUE rows in the deployment are our two.
            for (UserKey k : preExisting) {
                setIsAdmin(k.adapter, k.contactId, false);
            }

            // First revoke: count 2 → 1, allowed (bobOnB still admin).
            setIsAdmin(ADAPTER_A, aliceOnA, false);
            assertFalse(isAdmin(ADAPTER_A, aliceOnA),
                    "first revoke must succeed when one other admin remains globally");

            // Second revoke: count 1 → 0, MUST trip the trigger
            // regardless of the targeted adapter.
            SQLException trigger = assertThrows(SQLException.class,
                    () -> setIsAdmin(ADAPTER_B, bobOnB, false),
                    "revoking the global last admin must raise from the V5 trigger");
            assertTrue(trigger.getMessage() != null
                            && trigger.getMessage().contains("last_admin_protection"),
                    "V5 trigger error must contain 'last_admin_protection'; got: "
                            + trigger.getMessage());
            assertTrue(isAdmin(ADAPTER_B, bobOnB),
                    "(adapter-b, bobOnB).is_admin must remain true — the trigger "
                            + "rolls back the UPDATE before it commits");
        } finally {
            // Restore: re-promote every pre-existing admin so the
            // JVM-wide DB landscape is identical to entry.
            for (UserKey k : preExisting) {
                setIsAdmin(k.adapter, k.contactId, true);
            }
        }
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * (adapter, contact_id) pair captured by {@link #snapshotPreExistingAdmins()}.
     */
    private record UserKey(String adapter, String contactId) {}

    private List<UserKey> snapshotPreExistingAdmins() throws Exception {
        List<UserKey> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT adapter, contact_id FROM users "
                             + "WHERE is_admin = TRUE AND is_banned = FALSE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new UserKey(rs.getString("adapter"), rs.getString("contact_id")));
            }
        }
        return out;
    }

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

    private void setIsAdmin(String adapter, String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET is_admin = ? WHERE adapter = ? AND contact_id = ?")) {
            ps.setBoolean(1, isAdmin);
            ps.setString(2, adapter);
            ps.setString(3, contactId);
            ps.executeUpdate();
        }
    }

    private void setIsBanned(String adapter, String contactId, boolean isBanned) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET is_banned = ?, banned_at = CASE WHEN ? THEN NOW() ELSE NULL END "
                             + "WHERE adapter = ? AND contact_id = ?")) {
            ps.setBoolean(1, isBanned);
            ps.setBoolean(2, isBanned);
            ps.setString(3, adapter);
            ps.setString(4, contactId);
            ps.executeUpdate();
        }
    }

    private boolean isAdmin(String adapter, String contactId) throws Exception {
        return selectBooleanCol("is_admin", adapter, contactId);
    }

    private boolean isBanned(String adapter, String contactId) throws Exception {
        return selectBooleanCol("is_banned", adapter, contactId);
    }

    private boolean selectBooleanCol(String column, String adapter, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + " FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean(column);
            }
        }
    }

    private String redactedGrantSuccess(String contactId) {
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
