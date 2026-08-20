package app.zcat.infochat.provider.command;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape B (Thin-SQL) tests for {@link ReplyModeCommandHandler} per
 * {@code docs/process/test-pyramid.md} §Shape B: @QuarkusTest against the
 * default-profile DevServices Postgres image, direct
 * {@code handler.handle(scope, rawText)} calls (the inbound → router chain
 * belongs to the ITs). The configured reply mode is decisive; a native
 * setting is active immediately and independent of model or language.
 */
@QuarkusTest
class ReplyModeCommandHandlerIT {

    private static final String PREFIX = "m1-848-replymode-";
    private static final String ADAPTER = "inmemory";

    @Inject ReplyModeCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
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
    void translateWriteUpsertsAndConfirms() throws Exception {
        String actor = PREFIX + "translate-actor";
        UUID actorId = seedUser(actor);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/reply-mode translate");

        assertEquals("translate", scopeReplyModeOf(actorId),
                "scope_preferences.reply_mode must be UPSERTed to 'translate'");
        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_MODE_SUCCESS, inboundContext.effectiveLanguage()),
                "translate");
        assertEquals(expected, reply.text());
    }

    @Test
    void nativeWriteIsStoredAndConfirmedActive() throws Exception {
        String actor = PREFIX + "native-actor";
        UUID actorId = seedUser(actor);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/reply-mode native");

        assertEquals("native", scopeReplyModeOf(actorId),
                "the native override is stored and takes effect when set");
        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_MODE_SUCCESS, inboundContext.effectiveLanguage()),
                "native");
        assertEquals(expected, reply.text(),
                "a native write confirms the decisive configured mode");
    }

    @Test
    void bareInvocationReportsInheritedDefaultWhenUnset() throws Exception {
        String actor = PREFIX + "bare-actor";
        seedUser(actor);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/reply-mode");

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_MODE_STATUS_DEFAULT, inboundContext.effectiveLanguage()),
                "translate");
        assertEquals(expected, reply.text(),
                "an unset scope reports the deployment default it inherits");
    }

    @Test
    void bareInvocationReportsStoredNative() throws Exception {
        String actor = PREFIX + "bare-native-actor";
        seedUser(actor);
        handler.handle(new ScopeRef.Dm(actor), "/reply-mode native");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/reply-mode");

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_MODE_STATUS, inboundContext.effectiveLanguage()),
                "native");
        assertEquals(expected, reply.text(),
                "the status read names the stored native setting");
    }

    @Test
    void unsupportedValueListsSupportedValuesAndWritesNothing() throws Exception {
        String actor = PREFIX + "unsupported-actor";
        UUID actorId = seedUser(actor);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(actor), "/reply-mode pivot");

        assertNull(scopeReplyModeOf(actorId),
                "an unsupported value must NOT write scope_preferences");
        String expected = bundleLoader.get(
                BundleKeys.ERROR_REPLY_MODE_UNSUPPORTED, inboundContext.effectiveLanguage());
        assertEquals(expected, reply.text());
        assertTrue(reply.text().contains("translate") && reply.text().contains("native"),
                "the unsupported-value error lists translate and native — got: " + reply.text());
    }

    @Test
    void writesZeroRowsToAuditLog() throws Exception {
        String actor = PREFIX + "noAudit-actor";
        UUID actorId = seedUser(actor);

        long auditBefore = countAuditByActor(actorId);
        handler.handle(new ScopeRef.Dm(actor), "/reply-mode native");
        long auditAfter = countAuditByActor(actorId);

        assertEquals(auditBefore, auditAfter,
                "/reply-mode is a user-preference mutation, not a privileged action — zero audit rows");
    }

    @Test
    void groupScopeWithoutAdminActorIsRejected() throws Exception {
        // No senderContactId is wired on the context, so the group-actor
        // lookup resolves nothing and the gate rejects before any write.
        String groupId = "some-group-id-" + UUID.randomUUID();
        long groupRowsBefore = countGroupScopePreferences();

        OutboundMessage reply = handler.handle(new ScopeRef.Group(groupId), "/reply-mode native");

        String expected = bundleLoader.get(
                BundleKeys.ERROR_REPLY_MODE_GROUP_ADMIN, inboundContext.effectiveLanguage());
        assertEquals(expected, reply.text());
        assertEquals(groupRowsBefore, countGroupScopePreferences(),
                "a rejected group call must NOT write any scope_preferences row");
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

    private String scopeReplyModeOf(UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT reply_mode FROM scope_preferences "
                             + "WHERE scope_kind = 'dm' AND scope_id = ?")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("reply_mode");
            }
        }
    }

    private long countGroupScopePreferences() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM scope_preferences WHERE scope_kind = 'group'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
