package app.zcat.infochat.provider.command;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape B (Thin-SQL) tests for {@link LangCommandHandler} per
 * {@code docs/process/test-pyramid.md} §Shape B: @QuarkusTest against
 * the default-profile DevServices Postgres image, @Inject for the
 * handler and its DataSource / BundleLoader / InboundContext
 * collaborators, direct {@code handler.handle(scope, rawText)} calls
 * (no router leak — the inbound → router chain belongs to
 * LangCommandIT).
 *
 * <p>Test isolation: per-test sub-prefix within the class-wide
 * {@code PREFIX} ({@code m1-060-lang-}); the {@link #cleanup()}
 * {@code @BeforeEach} deletes test rows by the class-wide prefix.
 * {@code audit_log} is append-only (V5 {@code trg_audit_log_*}
 * triggers); cleanup temporarily disables those triggers in a
 * try/finally so the table cannot be left without its invariant.</p>
 */
@QuarkusTest
class LangCommandHandlerTest {

    private static final String PREFIX = "m1-060-lang-";
    private static final String ADAPTER = "inmemory";

    @Inject LangCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "DELETE FROM scope_preferences WHERE scope_id IN "
                            + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN "
                                + "(SELECT id FROM users WHERE contact_id LIKE ?)",
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
    void langDmWithCsCodeWritesScopePreferencesAndRepliesInCs() throws Exception {
        String actor = PREFIX + "cs-actor";
        UUID actorId = seedUser(actor);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/lang cs");

        assertEquals("cs", scopeLanguageOf(actorId),
                "scope_preferences.language must be UPSERTed to 'cs'");
        // Reply body MUST be the cs.properties value of reply.lang.success
        // (per acceptance item 1: confirmation lands in the just-set
        // language via the NEW 2-arg accessor).
        String csExpected = bundleLoader.get(BundleKeys.REPLY_LANG_SUCCESS, "cs");
        // The handler interpolates {0}=cs via MessageFormat; equality
        // pins both the bundle resolution AND the interpolation.
        String interpolated = java.text.MessageFormat.format(csExpected, "cs");
        assertEquals(interpolated, reply.text(),
                "confirmation reply must resolve via the 2-arg bundle accessor against 'cs'");
    }

    @Test
    void langDmWithUnsupportedCodeReturnsFriendlyErrorListingSupportedCodes() throws Exception {
        String actor = PREFIX + "xx-actor";
        UUID actorId = seedUser(actor);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/lang xx");

        // No row written to scope_preferences — the handler must
        // short-circuit BEFORE any DB write on the unsupported-code path.
        assertNull(scopeLanguageOf(actorId),
                "unsupported-code reject must NOT write scope_preferences");
        // Reply body lists both supported codes verbatim per spec §Conversation
        // control "lists the supported codes — never a silent no-op".
        assertTrue(reply.text().contains("en"),
                "unsupported-code reply must list 'en' as supported — got: " + reply.text());
        assertTrue(reply.text().contains("cs"),
                "unsupported-code reply must list 'cs' as supported — got: " + reply.text());
    }

    @Test
    void langDmWithEnCodeWritesScopePreferencesAndRepliesInEn() throws Exception {
        String actor = PREFIX + "en-actor";
        UUID actorId = seedUser(actor);
        // Seed cs first so the en write is a genuine UPSERT-UPDATE
        // rather than a fresh INSERT; verifies the ON CONFLICT path.
        handler.handle(new ScopeRef.Dm(actor), "/lang cs");
        assertEquals("cs", scopeLanguageOf(actorId),
                "test setup: cs must have been written first");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/lang en");

        assertEquals("en", scopeLanguageOf(actorId),
                "/lang en must overwrite the prior cs via ON CONFLICT UPDATE");
        String enExpected = bundleLoader.get(BundleKeys.REPLY_LANG_SUCCESS, "en");
        String interpolated = java.text.MessageFormat.format(enExpected, "en");
        assertEquals(interpolated, reply.text(),
                "confirmation reply must resolve via the 2-arg bundle accessor against 'en'");
    }

    @Test
    void langGroupScopeShortCircuitsToGroupAdminNotInV1() throws Exception {
        // Group scope short-circuits without an actor lookup or
        // scope_preferences write — no DB seeding needed. The frozen
        // CommandHandler SPI does not carry the group-scope caller's
        // contact id, so v1 cannot distinguish admin from non-admin and
        // rejects ALL group calls. T2-F lands the actor seam.
        String groupId = "some-group-id-" + UUID.randomUUID();
        ScopeRef.Group scope = new ScopeRef.Group(groupId);

        OutboundMessage replyCs = handler.handle(scope, "/lang cs");
        OutboundMessage replyXx = handler.handle(scope, "/lang xx");
        OutboundMessage replyBare = handler.handle(scope, "/lang");

        String expected = bundleLoader.get(BundleKeys.ERROR_LANG_GROUP_ADMIN_NOT_IN_V1);
        assertEquals(expected, replyCs.text(),
                "group /lang cs must surface error.lang.group_admin_not_in_v1");
        assertEquals(expected, replyXx.text(),
                "group /lang xx must surface the same short-circuit reply (no code parsing)");
        assertEquals(expected, replyBare.text(),
                "group /lang (no arg) must surface the same short-circuit reply");

        // Sanity — no row appears for any group-scope id (group scope_id
        // is the adapterGroupId literal, not a UUID — the UPSERT can't
        // even bind it; the short-circuit MUST happen first).
        assertFalse(scopePreferencesExistsForGroup(groupId),
                "group scope /lang must NOT touch scope_preferences");
    }

    @Test
    void langWritesZeroRowsToAuditLog() throws Exception {
        String actor = PREFIX + "noAudit-actor";
        UUID actorId = seedUser(actor);

        long auditBefore = countAuditByActor(actorId);
        handler.handle(new ScopeRef.Dm(actor), "/lang cs");
        long auditAfter = countAuditByActor(actorId);

        assertEquals(auditBefore, auditAfter,
                "/lang must write zero rows to audit_log — language preference "
                        + "mutations are user-preference, not privileged action "
                        + "(the M1-054 follow-tag precedent)");
    }

    // ----- helpers --------------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state) "
                             + "VALUES (?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    /** Returns the {@code language} column value for the actor's DM-scope row, or {@code null} if no row exists. */
    private String scopeLanguageOf(UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT language FROM scope_preferences "
                             + "WHERE scope_kind = 'dm' AND scope_id = ?")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("language");
            }
        }
    }

    private boolean scopePreferencesExistsForGroup(String adapterGroupId) throws Exception {
        // scope_id is UUID; an adapterGroupId String cannot bind to it,
        // so the existence check uses a literal-string-cast SELECT to
        // confirm no row was written. The query is essentially a no-op
        // sanity check — if the handler short-circuited correctly,
        // count_rows is 0 regardless of how the cast resolves.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM scope_preferences WHERE scope_kind = 'group'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long n = rs.getLong(1);
                // The cleanup() truncates dm rows by users.contact_id;
                // group rows would NEVER be seeded by this test class,
                // so any non-zero count here would mean a group-scope
                // UPSERT slipped through the short-circuit. Loose
                // assertion: the count must be zero across the whole
                // test run, since no group rows are intentionally
                // seeded.
                return n > 0;
            }
        }
    }

    private long countAuditByActor(UUID actorId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE actor_user_id = ?")) {
            ps.setObject(1, actorId);
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
}
