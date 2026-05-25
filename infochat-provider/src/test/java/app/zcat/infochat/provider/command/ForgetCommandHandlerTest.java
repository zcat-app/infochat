package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link ForgetCommandHandler} confirm flow, audit row
 * content, idempotent no-op, and remaining-scopes disclosure against
 * a real DevServices Postgres.
 */
@QuarkusTest
class ForgetCommandHandlerTest {

    private static final String PREFIX = "ForgetCmd-";
    private static final String ADAPTER = "in-memory";

    @Inject
    ForgetCommandHandler handler;

    @Inject
    DataSource dataSource;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    InboundContext inboundContext;

    @Inject
    ConfirmStateService confirmStateService;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM saved_post WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM chat_message WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM chat_memory WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM chat_session WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM summary_anchor WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            // Audit cleanup: disable trigger, delete, re-enable.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER ALL");
            exec(conn, "DELETE FROM audit_log WHERE target_contact_id LIKE '"
                    + PREFIX + "%'");
            exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER ALL");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
            exec(conn, "INSERT INTO source (id, identifier, kind, display_name, category, status) "
                    + "VALUES ('" + sourceId() + "', '" + PREFIX + "src', 'rss', '"
                    + PREFIX + "source', 'test', 'active') ON CONFLICT (id) DO NOTHING");
        }
    }

    @AfterEach
    void restoreClock() {
        confirmStateService.setClock(Clock.systemUTC());
    }

    /**
     * Acceptance: ForgetCommandHandler requires confirm.
     */
    @Test
    void requiresConfirm() throws Exception {
        String contactId = PREFIX + "confirm-actor";
        seedUser(contactId);
        inboundContext.setSenderContactId(contactId);

        ScopeRef scope = new ScopeRef.Dm(contactId);
        OutboundMessage reply = handler.handle(scope, "/forget");

        assertTrue(reply.text().contains("/forget confirm"),
                "First call should return a prompt mentioning /forget confirm");
        assertTrue(reply.text().contains(
                Long.toString(confirmStateService.timeoutSeconds())),
                "Prompt should include the timeout");
    }

    /**
     * Acceptance: audit row records counts only — no UID lists,
     * personal tags, or user-authored content.
     */
    @Test
    void auditRowRecordsCountsOnly() throws Exception {
        String contactId = PREFIX + "audit-actor";
        UUID userId = seedUser(contactId);
        inboundContext.setSenderContactId(contactId);
        seedChatSession(userId, "dm", userId);
        seedChatMemory(userId, "dm", userId);
        seedSavedPost(userId, "audit-post");

        ScopeRef scope = new ScopeRef.Dm(contactId);
        handler.handle(scope, "/forget");
        handler.handle(scope, "/forget confirm");

        // Read the audit row.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT scope_id, details_json FROM audit_log "
                             + "WHERE action = 'FORGET' AND target_contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Expected one FORGET audit row");
                // Redteam fix: scope_id must be present so the operator
                // can distinguish which scope was purged.
                assertNotNull(rs.getObject("scope_id"),
                        "Audit row must carry scope_id");
                assertEquals(userId, rs.getObject("scope_id", UUID.class),
                        "scope_id must match the DM scope (user's own id)");
                String json = rs.getString("details_json");
                assertTrue(json.contains("scope_kind") && json.contains("dm"),
                        "Audit should contain scope_kind=dm");
                assertTrue(json.contains("chat_memory_count"),
                        "Audit should contain chat_memory_count");
                assertTrue(json.contains("chat_session_count"),
                        "Audit should contain chat_session_count");
                assertTrue(json.contains("summary_anchor_count"),
                        "Audit should contain summary_anchor_count");
                assertTrue(json.contains("saved_post_count"),
                        "Audit should contain saved_post_count");
                // Must NOT contain user-authored content.
                assertFalse(json.contains("test summary"),
                        "Audit must not leak user content");
                assertFalse(json.contains("personal_tag"),
                        "Audit must not leak personal tags");
                assertFalse(rs.next(), "Expected exactly one FORGET audit row");
            }
        }
    }

    /**
     * Acceptance: idempotent no-op — no audit row on zero count.
     */
    @Test
    void idempotentNoOp() throws Exception {
        String contactId = PREFIX + "noop-actor";
        seedUser(contactId);
        inboundContext.setSenderContactId(contactId);

        ScopeRef scope = new ScopeRef.Dm(contactId);

        // First round: purge (nothing to purge).
        handler.handle(scope, "/forget");
        OutboundMessage reply = handler.handle(scope, "/forget confirm");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_FORGET_NOOP), reply.text());

        // No FORGET audit row for the no-op.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log "
                             + "WHERE action = 'FORGET' AND target_contact_id = ?")) {
            ps.setString(1, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1),
                        "No audit row should be written for a no-op /forget");
            }
        }
    }

    /**
     * Acceptance: remaining-scopes disclosure when other scopes exist.
     */
    @Test
    void disclosesRemainingScopes() throws Exception {
        String contactId = PREFIX + "remaining-actor";
        UUID userId = seedUser(contactId);
        inboundContext.setSenderContactId(contactId);
        UUID groupScopeId = UUID.randomUUID();

        // Data in DM scope (will be purged).
        seedChatSession(userId, "dm", userId);
        // Data in a group scope (will remain).
        seedChatSession(userId, "group", groupScopeId);

        ScopeRef scope = new ScopeRef.Dm(contactId);
        handler.handle(scope, "/forget");
        OutboundMessage reply = handler.handle(scope, "/forget confirm");

        assertTrue(reply.text().contains("1"),
                "Reply should disclose 1 remaining scope");
        assertTrue(reply.text().contains("/forget"),
                "Reply should instruct user to run /forget from other scopes");
    }

    /**
     * Acceptance: when remaining-scopes count is zero, the disclosure
     * clause is omitted.
     */
    @Test
    void omitsDisclosureWhenZero() throws Exception {
        String contactId = PREFIX + "zero-actor";
        UUID userId = seedUser(contactId);
        inboundContext.setSenderContactId(contactId);

        // Data in DM scope only (no other scopes).
        seedChatSession(userId, "dm", userId);
        seedChatMemory(userId, "dm", userId);

        ScopeRef scope = new ScopeRef.Dm(contactId);
        handler.handle(scope, "/forget");
        OutboundMessage reply = handler.handle(scope, "/forget confirm");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_FORGET_CLEARED), reply.text());
    }

    // ---- seeding helpers --------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state) "
                             + "VALUES (?, ?, 'invited') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedChatSession(UUID userId, String scopeKind, UUID scopeId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chat_session (user_id, scope_kind, scope_id) "
                             + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.executeUpdate();
        }
    }

    private void seedChatMemory(UUID userId, String scopeKind, UUID scopeId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chat_memory (user_id, scope_kind, scope_id, "
                             + "summary, keywords) VALUES (?, ?, ?, 'test summary', '{test}')")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.executeUpdate();
        }
    }

    private void seedSavedPost(UUID userId, String postUid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (user_id, post_uid, source_id, title) "
                             + "VALUES (?, ?, ?, 'test title')")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId());
            ps.executeUpdate();
        }
    }

    private static UUID sourceId() {
        return UUID.fromString("00000000-0000-0000-0000-f06937000066");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
