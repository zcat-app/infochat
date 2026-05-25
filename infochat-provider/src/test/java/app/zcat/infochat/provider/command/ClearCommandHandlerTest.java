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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ClearCommandHandlerTest {

    private static final String PREFIX = "ClearCmd-";
    private static final String ADAPTER = "in-memory";

    @Inject
    ClearCommandHandler handler;

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
            exec(conn, "DELETE FROM chat_message WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM chat_memory WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM chat_session WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @AfterEach
    void restoreClock() {
        confirmStateService.setClock(Clock.systemUTC());
    }

    @Test
    void requiresConfirm() throws Exception {
        String contactId = PREFIX + "confirm-actor";
        seedUser(contactId);
        inboundContext.setSenderContactId(contactId);

        ScopeRef scope = new ScopeRef.Dm(contactId);
        OutboundMessage reply = handler.handle(scope, "/clear");

        assertTrue(reply.text().contains("/clear confirm"),
                "First call should return a prompt mentioning /clear confirm");
        assertTrue(reply.text().contains(
                Long.toString(confirmStateService.timeoutSeconds())),
                "Prompt should include the timeout");
    }

    @Test
    void wipesMessagesPreservesMemory() throws Exception {
        String contactId = PREFIX + "wipe-actor";
        UUID userId = seedUser(contactId);
        inboundContext.setSenderContactId(contactId);
        seedChatSession(userId, "dm", userId);
        seedChatMessage(userId, "dm", userId, 0, "user", "hello", 5);
        seedChatMessage(userId, "dm", userId, 1, "assistant", "hi there", 6);
        seedChatMemory(userId, "dm", userId);

        ScopeRef scope = new ScopeRef.Dm(contactId);
        handler.handle(scope, "/clear");
        OutboundMessage reply = handler.handle(scope, "/clear confirm");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_CLEAR_SUCCESS), reply.text());

        try (Connection conn = dataSource.getConnection()) {
            // Messages should be gone
            assertEquals(0, countMessages(conn, userId, "dm", userId),
                    "All chat_message rows should be deleted");

            // Session counters should be reset
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT token_count, next_seq FROM chat_session "
                            + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
                ps.setObject(1, userId);
                ps.setString(2, "dm");
                ps.setObject(3, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "chat_session row should still exist");
                    assertEquals(0, rs.getInt("token_count"),
                            "token_count should be reset to 0");
                    assertEquals(0, rs.getInt("next_seq"),
                            "next_seq should be reset to 0");
                }
            }

            // chat_memory should NOT be touched (D25)
            assertEquals(1, countMemory(conn, userId, "dm", userId),
                    "chat_memory rows must be preserved (D25)");
        }
    }

    @Test
    void noSessionIsNoOp() throws Exception {
        String contactId = PREFIX + "noop-actor";
        seedUser(contactId);
        inboundContext.setSenderContactId(contactId);

        ScopeRef scope = new ScopeRef.Dm(contactId);
        handler.handle(scope, "/clear");
        OutboundMessage reply = handler.handle(scope, "/clear confirm");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_CLEAR_NOOP), reply.text());
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

    private void seedChatMessage(UUID userId, String scopeKind, UUID scopeId,
                                 int seq, String role, String content, int tokens)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chat_message "
                             + "(user_id, scope_kind, scope_id, seq, role, content, tokens) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setInt(4, seq);
            ps.setString(5, role);
            ps.setString(6, content);
            ps.setInt(7, tokens);
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

    private int countMessages(Connection conn, UUID userId,
                              String scopeKind, UUID scopeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM chat_message "
                        + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countMemory(Connection conn, UUID userId,
                            String scopeKind, UUID scopeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM chat_memory "
                        + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
