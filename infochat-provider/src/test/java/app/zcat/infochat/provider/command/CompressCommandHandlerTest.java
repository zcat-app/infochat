package app.zcat.infochat.provider.command;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.ChatSessionRepository;
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
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CompressCommandHandlerTest {

    private static final String PREFIX = "CompressCmd-";
    private static final String ADAPTER = "in-memory";

    @Inject
    CompressCommandHandler handler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    InboundContext inboundContext;

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

    @Test
    void compressesAndTruncates() throws Exception {
        String contactId = PREFIX + "compress-actor";
        UUID userId = seedUser(contactId);
        inboundContext.setSenderContactId(contactId);
        seedChatSession(userId, "dm", userId);
        seedChatMessage(userId, "dm", userId, 0, "user", "Tell me about bitcoin", 10);
        seedChatMessage(userId, "dm", userId, 1, "assistant", "Bitcoin is a cryptocurrency", 12);
        seedChatMessage(userId, "dm", userId, 2, "user", "What about monero?", 8);
        seedChatMessage(userId, "dm", userId, 3, "assistant", "Monero is a privacy coin", 10);

        ScopeRef scope = new ScopeRef.Dm(contactId);
        OutboundMessage reply = handler.handle(scope, "/compress");

        // The LLM may or may not be reachable in test; check both paths.
        // If LLM is reachable: success reply with message count.
        // If LLM is unreachable: failure reply.
        try (Connection conn = dataSource.getConnection()) {
            if (reply.text().contains(bundleLoader.get(BundleKeys.ERROR_COMPRESS_FAILED))) {
                // LLM failure path — session unchanged
                assertEquals(4, countMessages(conn, userId, "dm", userId),
                        "On failure, messages should be preserved");
            } else {
                // Success path — messages truncated, memory written
                assertTrue(reply.text().contains("4"),
                        "Success reply should mention 4 messages");
                assertEquals(0, countMessages(conn, userId, "dm", userId),
                        "After compress, all messages should be deleted");
                assertTrue(countMemory(conn, userId, "dm", userId) >= 1,
                        "After compress, a chat_memory row should exist");

                // token_count drained by the counter trigger's per-row
                // DELETE decrement; next_seq is intentionally NOT reset
                // (the 4 seed inserts advanced it to 4) so turns persisted
                // concurrently with a compress keep increasing seqs.
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT token_count, next_seq FROM chat_session "
                                + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
                    ps.setObject(1, userId);
                    ps.setString(2, "dm");
                    ps.setObject(3, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(0, rs.getInt("token_count"));
                        assertEquals(4, rs.getInt("next_seq"));
                    }
                }
            }
        }
    }

    @Test
    void failurePreservesSession() throws Exception {
        String contactId = PREFIX + "failure-actor";
        UUID userId = seedUser(contactId);
        inboundContext.setSenderContactId(contactId);
        seedChatSession(userId, "dm", userId);
        seedChatMessage(userId, "dm", userId, 0, "user", "hello", 5);
        seedChatMessage(userId, "dm", userId, 1, "assistant", "hi", 3);

        // Deterministic LLM failure (failing stub) so the preservation
        // invariant is asserted unconditionally, not vacuously when a live
        // LLM happens to be reachable.
        CompressCommandHandler testHandler =
                buildHandler(StubCompressLlmProvider.failing());

        CompressCommandHandler.CompressResult result =
                testHandler.compress(userId, "dm", userId, "en");

        assertInstanceOf(CompressCommandHandler.CompressResult.Failure.class, result);
        try (Connection conn = dataSource.getConnection()) {
            assertEquals(2, countMessages(conn, userId, "dm", userId),
                    "On failure, messages must be preserved");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT token_count FROM chat_session "
                            + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
                ps.setObject(1, userId);
                ps.setString(2, "dm");
                ps.setObject(3, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(8, rs.getInt("token_count"),
                            "token_count must be preserved on failure (5 + 3 seeded tokens)");
                }
            }
        }
    }

    @Test
    void turnPersistedDuringLlmCallSurvivesCompression() throws Exception {
        String contactId = PREFIX + "concurrent-actor";
        UUID userId = seedUser(contactId);
        seedChatSession(userId, "dm", userId);
        seedChatMessage(userId, "dm", userId, 0, "user", "first", 5);
        seedChatMessage(userId, "dm", userId, 1, "assistant", "second", 5);

        // While the LLM call is in flight, another worker persists seq 2.
        CompressCommandHandler testHandler = buildHandler(
                StubCompressLlmProvider.succeeding(
                        "SUMMARY: s\nKEYWORDS: k\nREFERENCES: NONE",
                        () -> {
                            try {
                                seedChatMessage(userId, "dm", userId, 2,
                                        "user", "concurrent", 7);
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }));

        CompressCommandHandler.CompressResult result =
                testHandler.compress(userId, "dm", userId, "en");

        assertInstanceOf(CompressCommandHandler.CompressResult.Success.class, result);
        try (Connection conn = dataSource.getConnection()) {
            assertEquals(1, countMessages(conn, userId, "dm", userId),
                    "the turn persisted while the LLM call was in flight "
                            + "must survive compression un-deleted");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT seq, content FROM chat_message "
                            + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
                ps.setObject(1, userId);
                ps.setString(2, "dm");
                ps.setObject(3, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("seq"));
                    assertEquals("concurrent", rs.getString("content"));
                }
            }
            assertTrue(countMemory(conn, userId, "dm", userId) >= 1,
                    "the summarized turns must be checkpointed to chat_memory");
            // Counter trigger keeps the session consistent: token_count is
            // the surviving turn's tokens, next_seq stays monotonic.
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT token_count, next_seq FROM chat_session "
                            + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
                ps.setObject(1, userId);
                ps.setString(2, "dm");
                ps.setObject(3, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(7, rs.getInt("token_count"));
                    assertEquals(3, rs.getInt("next_seq"));
                }
            }
        }
    }

    @Test
    void llmFailureDeletesNothingAndWritesNoSummary() throws Exception {
        String contactId = PREFIX + "llm-failure-actor";
        UUID userId = seedUser(contactId);
        seedChatSession(userId, "dm", userId);
        seedChatMessage(userId, "dm", userId, 0, "user", "hello", 5);
        seedChatMessage(userId, "dm", userId, 1, "assistant", "hi", 3);

        CompressCommandHandler testHandler =
                buildHandler(StubCompressLlmProvider.failing());

        CompressCommandHandler.CompressResult result =
                testHandler.compress(userId, "dm", userId, "en");

        assertInstanceOf(CompressCommandHandler.CompressResult.Failure.class, result);
        try (Connection conn = dataSource.getConnection()) {
            assertEquals(2, countMessages(conn, userId, "dm", userId),
                    "on LLM failure no turns may be deleted");
            assertEquals(0, countMemory(conn, userId, "dm", userId),
                    "on LLM failure no summary may be written");
        }
    }

    // ---- manual wiring (same-package field access) -------------------------

    private CompressCommandHandler buildHandler(LlmProvider provider) {
        CompressCommandHandler testHandler = new CompressCommandHandler();
        testHandler.dataSource = dataSource;
        testHandler.llmRouter = new LlmRouter(
                List.of(new LlmRouter.Entry("test", provider, Set.of("en"))),
                key -> Optional.empty()) {
            @Override
            public LlmProvider forTask(ModelTask task, String lang) {
                return provider;
            }
        };
        return testHandler;
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
