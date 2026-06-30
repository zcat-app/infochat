package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditVerb;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AuditCommandHandler} against the
 * DevServices Postgres container. One {@code @Test} per audit-command
 * acceptance scenario in M1-081b.
 */
@QuarkusTest
class AuditCommandHandlerTest {

    private static final String PREFIX = "m1-081b-audit-";
    private static final String ADAPTER = "inmemory";

    // Pinned "now" for the -w window tests (M1-528). The window cutoff is read
    // from the injected Clock, pinned here via QuarkusMock so the seeded rows'
    // created_at offsets decide inclusion deterministically (engineering-rules §9).
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");

    @Inject AuditCommandHandler handler;
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
                exec(conn,
                        "DELETE FROM audit_log WHERE target_id LIKE ? "
                                + "OR actor_user_id IN "
                                + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_update");
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_delete");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
        }
    }

    @Test
    void audit_readsRedactedView() throws Exception {
        String admin = PREFIX + "view-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");

        seedAuditRow(adminId, admin, "BAN", "user", PREFIX + "target1",
                PREFIX + "target-contact1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/audit");

        // The handler reads audit_log_view (the V5 redacted view).
        // redact_contact_id is a V5 stub (returns input as-is) — real
        // masking lands when the redaction function is implemented.
        // This test verifies the handler surfaces audit data correctly.
        assertTrue(reply.text().contains("BAN"),
                "audit reply must include the action");
        assertTrue(reply.text().contains(PREFIX + "target1"),
                "audit reply must include the target_id");
        assertTrue(reply.text().contains("user"),
                "audit reply must include the target_kind");
    }

    @Test
    void audit_actorFilter() throws Exception {
        String admin = PREFIX + "actor-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");

        String otherContact = PREFIX + "actor-other";
        UUID otherId = seedUser(otherContact, false, false, "invited");

        seedAuditRow(adminId, admin, "BAN", "user", PREFIX + "actor-t1", null);
        seedAuditRow(otherId, otherContact, "ADD_SOURCE", "source", PREFIX + "actor-t2", null);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/audit --actor " + otherContact);

        assertTrue(reply.text().contains("ADD_SOURCE"),
                "actor filter must return only that actor's rows");
        assertFalse(reply.text().contains(PREFIX + "actor-t1"),
                "actor filter must exclude other actors' rows");
    }

    @Test
    void audit_actionFilter() throws Exception {
        String admin = PREFIX + "action-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");

        seedAuditRow(adminId, admin, "BAN", "user", PREFIX + "action-t1", null);
        seedAuditRow(adminId, admin, "VOUCH", "user", PREFIX + "action-t2", null);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/audit --action VOUCH");

        assertTrue(reply.text().contains("VOUCH"),
                "action filter must return matching rows");
        assertFalse(reply.text().contains(PREFIX + "action-t1"),
                "action filter must exclude non-matching rows");
    }

    @Test
    void audit_unknownAction_listsAccepted() throws Exception {
        String admin = PREFIX + "unkact-admin";
        seedUser(admin, true, false, "vouched");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/audit --action NONSENSE");

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_AUDIT_UNKNOWN_ACTION),
                "NONSENSE",
                Arrays.stream(AuditVerb.values())
                        .map(AuditVerb::name).collect(Collectors.joining(", ")));
        assertEquals(expected, reply.text(),
                "unknown --action must return friendly error listing accepted values");
    }

    @Test
    void audit_unknownActor_returnsNoRows() throws Exception {
        String admin = PREFIX + "unkactor-admin";
        seedUser(admin, true, false, "vouched");

        // Use a well-formed contact id that does not exist
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin),
                "/audit --actor " + PREFIX + "does-not-exist");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_AUDIT_EMPTY), reply.text(),
                "unknown actor must return same 'no audit rows' reply as known-with-no-rows");
    }

    @Test
    void audit_nonAdmin_rejected() throws Exception {
        String nonAdmin = PREFIX + "nonadmin";
        seedUser(nonAdmin, false, false, "invited");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(nonAdmin), "/audit");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /audit must surface error.admin_only");
    }

    @Test
    void audit_pagination() throws Exception {
        String admin = PREFIX + "page-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");

        // Seed enough rows to push to page 2 (page size default = 20)
        for (int i = 0; i < 25; i++) {
            seedAuditRow(adminId, admin, "BAN", "user",
                    PREFIX + "page-t" + String.format("%03d", i), null);
        }

        // Page 1 (default)
        OutboundMessage page1 = handler.handle(
                new ScopeRef.Dm(admin), "/audit");
        assertTrue(page1.text().contains("page 1/2") || page1.text().contains("page 1"),
                "page 1 header must show pagination info");

        // Page 2 explicitly
        OutboundMessage page2 = handler.handle(
                new ScopeRef.Dm(admin), "/audit --page 2");
        assertTrue(page2.text().contains("page 2"),
                "page 2 header must show page 2");
    }

    @Test
    void audit_malformedPage_usageError() throws Exception {
        String admin = PREFIX + "badpage-admin";
        seedUser(admin, true, false, "vouched");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/audit --page abc");

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT),
                "/audit [--actor X] [--action Y] [--page N]");
        assertEquals(expected, reply.text(),
                "malformed --page must surface the usage error, not silently fall back to page 1");
    }

    // ---- -w window (M1-528) ----

    @Test
    void audit_windowFilter() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        String admin = PREFIX + "win-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");

        // Inside the 24h window; outside it.
        seedAuditRowAt(adminId, admin, "BAN", "user", PREFIX + "win-inside",
                PINNED_NOW.minusSeconds(3600));        // 1h ago
        seedAuditRowAt(adminId, admin, "BAN", "user", PREFIX + "win-outside",
                PINNED_NOW.minusSeconds(48 * 3600));   // 48h ago

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/audit -w 24h");

        assertTrue(reply.text().contains(PREFIX + "win-inside"),
                "-w 24h must include rows within the window");
        assertFalse(reply.text().contains(PREFIX + "win-outside"),
                "-w 24h must exclude rows older than the window");
    }

    @Test
    void audit_windowComposesWithActionFilter() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        String admin = PREFIX + "compose-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");

        // Matches both action AND window.
        seedAuditRowAt(adminId, admin, "BAN", "user", PREFIX + "compose-match",
                PINNED_NOW.minusSeconds(5L * 24 * 3600));     // 5d ago
        // Matches action but OUTSIDE the 30d window.
        seedAuditRowAt(adminId, admin, "BAN", "user", PREFIX + "compose-oldban",
                PINNED_NOW.minusSeconds(40L * 24 * 3600));    // 40d ago
        // Inside window but WRONG action.
        seedAuditRowAt(adminId, admin, "VOUCH", "user", PREFIX + "compose-otheraction",
                PINNED_NOW.minusSeconds(5L * 24 * 3600));     // 5d ago

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/audit --action ban -w 30d");

        assertTrue(reply.text().contains(PREFIX + "compose-match"),
                "row matching both action and window must appear");
        assertFalse(reply.text().contains(PREFIX + "compose-oldban"),
                "the window predicate must AND-combine — a matching action outside the window is excluded");
        assertFalse(reply.text().contains(PREFIX + "compose-otheraction"),
                "the action predicate must AND-combine — a row inside the window with the wrong action is excluded");
    }

    @Test
    void audit_windowDefault() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        String admin = PREFIX + "wdefault-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");

        seedAuditRowAt(adminId, admin, "BAN", "user", PREFIX + "wdefault-inside",
                PINNED_NOW.minusSeconds(3600));        // 1h ago
        seedAuditRowAt(adminId, admin, "BAN", "user", PREFIX + "wdefault-outside",
                PINNED_NOW.minusSeconds(48 * 3600));   // 48h ago

        // Bare /audit applies the documented default 24h window.
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(admin), "/audit");

        assertTrue(reply.text().contains(PREFIX + "wdefault-inside"),
                "bare /audit must include rows within the default 24h window");
        assertFalse(reply.text().contains(PREFIX + "wdefault-outside"),
                "bare /audit must exclude rows older than the default 24h window");
    }

    @Test
    void audit_malformedWindow_usageError() throws Exception {
        String admin = PREFIX + "badwin-admin";
        seedUser(admin, true, false, "vouched");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/audit -w abc");

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT),
                "/audit [--actor X] [--action Y] [--page N]");
        assertEquals(expected, reply.text(),
                "malformed -w must surface the usage error, not silently fall back to the default window");
    }

    @Test
    void audit_overRangeWindow_usageError() throws Exception {
        String admin = PREFIX + "overwin-admin";
        seedUser(admin, true, false, "vouched");

        // A value that fits in a long but overflows Duration must be rejected at the
        // boundary with the usage error, never an uncaught ArithmeticException
        // (M1-528 redteam remediation).
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/audit -w 999999999999999d");

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT),
                "/audit [--actor X] [--action Y] [--page N]");
        assertEquals(expected, reply.text(),
                "over-range -w must surface the usage error, not throw or silently empty the view");
    }

    // ---- Audit logging ----

    @Test
    void audit_writesAuditReadRow() throws Exception {
        String admin = PREFIX + "auditread-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");

        // Seed one audit row so the handler has data to return
        seedAuditRow(adminId, admin, "BAN", "user", PREFIX + "auditread-t1", null);

        handler.handle(new ScopeRef.Dm(admin), "/audit --action BAN");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT action, actor_user_id, actor_contact_id, actor_adapter, details_json "
                             + "FROM audit_log WHERE action = 'AUDIT_READ' "
                             + "AND actor_user_id = ?")) {
            ps.setObject(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "AUDIT_READ audit row must exist");
                assertEquals("AUDIT_READ", rs.getString("action"));
                assertEquals(adminId, rs.getObject("actor_user_id", UUID.class));
                assertEquals(admin, rs.getString("actor_contact_id"));
                assertEquals(ADAPTER, rs.getString("actor_adapter"));
                String details = rs.getString("details_json");
                assertTrue(details.contains("\"action\"") && details.contains("\"BAN\""),
                        "details_json must record the --action filter, got: " + details);
            }
        }
    }

    @Test
    void audit_backslashInActorDoesNotBreakAuditWrite() throws Exception {
        String admin = PREFIX + "bs-admin";
        seedUser(admin, true, false, "vouched");

        // A backslash in --actor must not break the detailsJson ::jsonb cast
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/audit --actor test\\");

        // The command should succeed (returning empty results for the
        // non-existent actor), not return an internal error
        assertEquals(bundleLoader.get(BundleKeys.REPLY_AUDIT_EMPTY), reply.text(),
                "backslash in --actor must not break the audit write");
    }

    // ---- Helpers ----

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

    private void seedAuditRow(UUID actorUserId, String actorContactId,
                              String action, String targetKind, String targetId,
                              String targetContactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO audit_log (actor_user_id, actor_contact_id, actor_adapter, "
                             + "action, target_kind, target_id, target_contact_id) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, actorUserId);
            ps.setString(2, actorContactId);
            ps.setString(3, ADAPTER);
            ps.setString(4, action);
            ps.setString(5, targetKind);
            ps.setString(6, targetId);
            if (targetContactId == null) {
                ps.setNull(7, java.sql.Types.VARCHAR);
            } else {
                ps.setString(7, targetContactId);
            }
            ps.executeUpdate();
        }
    }

    // Like seedAuditRow but sets created_at explicitly so the -w window tests
    // control whether a row falls inside or outside the cutoff (M1-528).
    private void seedAuditRowAt(UUID actorUserId, String actorContactId,
                                String action, String targetKind, String targetId,
                                Instant createdAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO audit_log (actor_user_id, actor_contact_id, actor_adapter, "
                             + "action, target_kind, target_id, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, actorUserId);
            ps.setString(2, actorContactId);
            ps.setString(3, ADAPTER);
            ps.setString(4, action);
            ps.setString(5, targetKind);
            ps.setString(6, targetId);
            ps.setObject(7, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            ps.executeUpdate();
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
