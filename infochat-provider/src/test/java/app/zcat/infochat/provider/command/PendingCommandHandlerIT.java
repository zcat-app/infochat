package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PendingCommandHandler} against the DevServices
 * Postgres container. One {@code @Test} per M1-575 acceptance scenario. Named
 * {@code *IT} (not {@code *Test}) because it injects a {@link DataSource} and so
 * runs in the failsafe integration phase (IntegrationTestNamingGuard).
 *
 * <p>Uses a dedicated adapter name (not {@code inmemory}) so the actionable-user
 * count is a function of this test's own seeded rows only — the handler counts
 * every actionable user on the adapter, so global {@code inmemory} state would
 * otherwise make the empty/pagination assertions non-deterministic.
 */
@QuarkusTest
class PendingCommandHandlerIT {

    private static final String PREFIX = "m1-575-pending-";
    private static final String ADAPTER = "m1-575-pending";

    // Pinned "now" for the probation-window test. The probation cutoff is read
    // from the injected Clock, pinned here via QuarkusMock so the seeded rows'
    // probation_until offsets decide inclusion deterministically (engineering-rules §9).
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");

    @Inject PendingCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @AfterEach
    void teardown() throws Exception {
        cleanup();
    }

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            exec(conn, "ALTER TABLE users DISABLE TRIGGER trg_users_last_admin_update");
            exec(conn, "ALTER TABLE users DISABLE TRIGGER trg_users_last_admin_delete");
            try {
                exec(conn, "DELETE FROM audit_log WHERE actor_adapter = ?", ADAPTER);
                exec(conn, "DELETE FROM users WHERE adapter = ?", ADAPTER);
            } finally {
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_update");
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_delete");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void pending_adminSeesActionableUsersWithContactIds() throws Exception {
        String admin = PREFIX + "list-admin";
        seedUser(admin, true, false, "vouched", null);
        // A real awaiting-vouch / probation user: appears with its usable contact id.
        String probationUser = PREFIX + "invited-probation";
        seedUser(probationUser, false, false, "invited",
                Instant.parse("2026-08-01T00:00:00Z"));
        // A settled (vouched, no probation) user: nothing pending, must be excluded.
        String settled = PREFIX + "vouched-settled";
        seedUser(settled, false, false, "vouched", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/pending");

        assertTrue(reply.text().contains(probationUser),
                "an awaiting-vouch/probation user must be listed with its contact id");
        assertFalse(reply.text().contains(settled),
                "a settled vouched user is not actionable and must be excluded");
    }

    @Test
    void pending_bannedUserExcluded() throws Exception {
        String admin = PREFIX + "ban-admin";
        seedUser(admin, true, false, "vouched", null);
        // Banned users are already resolved — not part of the actionable set.
        String banned = PREFIX + "banned-invited";
        seedUser(banned, false, true, "invited", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/pending");

        assertEquals(bundleLoader.get("reply.pending.empty"), reply.text(),
                "a banned user must not appear in the actionable list");
    }

    @Test
    void pending_probationPredicateUsesInjectedClock() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        String admin = PREFIX + "clock-admin";
        seedUser(admin, true, false, "vouched", null);
        // Vouched (NOT invited) users, so only the probation predicate can include
        // them — this isolates the Clock-gated comparison from the 'invited' branch.
        String inProbation = PREFIX + "clock-active";
        seedUser(inProbation, false, false, "vouched", PINNED_NOW.plusSeconds(3600));
        String probationExpired = PREFIX + "clock-expired";
        seedUser(probationExpired, false, false, "vouched", PINNED_NOW.minusSeconds(3600));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/pending");

        assertTrue(reply.text().contains(inProbation),
                "a user whose probation ends after the injected-clock now must be listed");
        assertFalse(reply.text().contains(probationExpired),
                "a vouched user whose probation already expired is settled and must be excluded");
    }

    @Test
    void pending_nonAdminRejected() throws Exception {
        String nonAdmin = PREFIX + "nonadmin";
        seedUser(nonAdmin, false, false, "invited", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(nonAdmin), "/pending");

        assertEquals(bundleLoader.get("error.admin_only"), reply.text(),
                "non-admin /pending must surface error.admin_only");
    }

    @Test
    void pending_groupInvocation_dmOnly() throws Exception {
        // Even a bot admin gets the DM-only error in group scope: the group
        // short-circuit runs before the admin lookup.
        String admin = PREFIX + "group-admin";
        seedUser(admin, true, false, "vouched", null);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Group(PREFIX + "some-group"), "/pending");

        assertEquals(bundleLoader.get("error.command_dm_only"), reply.text(),
                "/pending in a group must surface error.command_dm_only");
    }

    @Test
    void pending_emptyWhenNoActionableUsers() throws Exception {
        String admin = PREFIX + "empty-admin";
        seedUser(admin, true, false, "vouched", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/pending");

        assertEquals(bundleLoader.get("reply.pending.empty"), reply.text(),
                "with only a settled admin present, /pending must return the empty reply");
    }

    @Test
    void pending_pagination() throws Exception {
        String admin = PREFIX + "page-admin";
        seedUser(admin, true, false, "vouched", null);
        // 25 actionable (invited) users → 2 pages at the default page size of 20.
        for (int i = 0; i < 25; i++) {
            seedUser(PREFIX + "page-u" + String.format("%03d", i), false, false, "invited", null);
        }

        OutboundMessage page1 = handler.handle(new ScopeRef.Dm(admin), "/pending");
        assertTrue(page1.text().contains("page 1/2"),
                "page 1 header must show pagination 1/2, got: " + page1.text());

        OutboundMessage page2 = handler.handle(new ScopeRef.Dm(admin), "/pending --page 2");
        assertTrue(page2.text().contains("page 2/2"),
                "page 2 header must show pagination 2/2, got: " + page2.text());
    }

    @Test
    void pending_malformedPage_usageError() throws Exception {
        String admin = PREFIX + "badpage-admin";
        seedUser(admin, true, false, "vouched", null);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/pending --page abc");

        String expected = MessageFormat.format(
                bundleLoader.get("error.usage.missing_argument"), "/pending [--page N]");
        assertEquals(expected, reply.text(),
                "malformed --page must surface the usage error, not silently fall back to page 1");
    }

    @Test
    void pending_writesPrivilegedReadAuditRow() throws Exception {
        String admin = PREFIX + "audit-admin";
        UUID adminId = seedUser(admin, true, false, "vouched", null);

        // No actionable users seeded → empty reply, but the audit-before-effect
        // row must still be written (the intent is audited, not the result).
        handler.handle(new ScopeRef.Dm(admin), "/pending");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT action, actor_user_id, actor_contact_id, actor_adapter "
                             + "FROM audit_log WHERE action = 'PENDING_LIST' AND actor_user_id = ?")) {
            ps.setObject(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "PENDING_LIST audit row must exist (audit-before-effect)");
                assertEquals("PENDING_LIST", rs.getString("action"));
                assertEquals(adminId, rs.getObject("actor_user_id", UUID.class));
                assertEquals(admin, rs.getString("actor_contact_id"));
                assertEquals(ADAPTER, rs.getString("actor_adapter"));
            }
        }
    }

    // ---- Helpers ----

    private UUID seedUser(String contactId, boolean isAdmin, boolean isBanned,
                          String registrationState, @Nullable Instant probationUntil)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, probation_until, banned_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END) "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setString(5, registrationState);
            ps.setObject(6, probationUntil == null ? null
                    : OffsetDateTime.ofInstant(probationUntil, ZoneOffset.UTC));
            ps.setBoolean(7, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
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
}
