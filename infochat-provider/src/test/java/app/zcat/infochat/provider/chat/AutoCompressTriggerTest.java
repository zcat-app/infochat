package app.zcat.infochat.provider.chat;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.CompressCommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoCompressTriggerTest {

    private static final String PREFIX = "AutoCompress-";
    private static final String ADAPTER = "in-memory";

    @Inject
    AutoCompressTrigger trigger;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    InboundContext inboundContext;

    @ConfigProperty(name = "infochat.context-compress-at")
    int compressAtThreshold;

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
    void firesAtThreshold() throws Exception {
        String contactId = PREFIX + "threshold-actor";
        UUID userId = seedUser(contactId);

        // Seed a session with token_count above the threshold.
        seedChatSessionWithTokens(userId, "dm", userId, compressAtThreshold + 10);
        seedChatMessage(userId, "dm", userId, 0, "user", "hello", compressAtThreshold + 5);
        seedChatMessage(userId, "dm", userId, 1, "assistant", "world", 5);

        Optional<String> notice = trigger.checkAndCompress(userId, "dm", userId, "en");

        // The trigger should fire (above threshold). Whether it succeeds
        // or fails depends on LLM availability.
        assertTrue(notice.isPresent(),
                "Auto-compress should fire when above threshold");
    }

    @Test
    void neverInterruptsReply() throws Exception {
        // The auto-compress trigger is called AFTER the reply is computed
        // (by ChatAgent.doHandle, after persistTurn). This test verifies
        // the trigger returns a notification string (not void) — the
        // caller (ChatAgent) appends it after the reply, never mid-stream.
        String contactId = PREFIX + "timing-actor";
        UUID userId = seedUser(contactId);

        // Below threshold: trigger should NOT fire.
        seedChatSessionWithTokens(userId, "dm", userId, compressAtThreshold - 10);

        Optional<String> notice = trigger.checkAndCompress(userId, "dm", userId, "en");

        assertTrue(notice.isEmpty(),
                "Below threshold, auto-compress should not fire — reply is uninterrupted");
    }

    @Test
    void failureHoldsAtCeiling() throws Exception {
        String contactId = PREFIX + "failure-actor";
        UUID userId = seedUser(contactId);

        int aboveThreshold = compressAtThreshold + 50;
        seedChatSessionWithTokens(userId, "dm", userId, aboveThreshold);
        seedChatMessage(userId, "dm", userId, 0, "user", "hello", aboveThreshold);

        // Deterministic compress failure so the ceiling-hold assertion runs
        // unconditionally — not gated on whether a live LLM happens to be down.
        AutoCompressTrigger failTrigger = new AutoCompressTrigger(
                compressAtThreshold, bundleLoader,
                new CompressCommandHandler() {
                    @Override
                    public CompressResult compress(UUID u, String scopeKind,
                                                   UUID scopeId, String scopeLanguage) {
                        return new CompressResult.Failure();
                    }
                }, dataSource);

        // Read the ceiling AFTER seeding (the counter trigger folds the
        // seeded message's tokens into session.token_count, so the value is
        // not the literal seed) but BEFORE the failing compress.
        int tokenCountAtCeiling = readTokenCount(userId);
        assertTrue(tokenCountAtCeiling >= compressAtThreshold,
                "test setup: session must start above the compress ceiling");

        Optional<String> notice = failTrigger.checkAndCompress(userId, "dm", userId, "en");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_COMPRESS_FAILED), notice.orElseThrow(),
                "a deterministic compress failure above threshold must yield the failure notice");

        // On failure the session is held at the ceiling: token_count unchanged.
        assertEquals(tokenCountAtCeiling, readTokenCount(userId),
                "On failure, session must be held at ceiling (token_count unchanged)");
    }

    private int readTokenCount(UUID userId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT token_count FROM chat_session "
                             + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, "dm");
            ps.setObject(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt("token_count");
            }
        }
    }

    @Test
    void failedAutoCompressAtCeilingGatesSessionUntilCompressSucceeds() throws Exception {
        String contactId = PREFIX + "gate-actor";
        UUID userId = seedUser(contactId);
        int aboveThreshold = compressAtThreshold + 50;
        seedChatSessionWithTokens(userId, "dm", userId, aboveThreshold);
        seedChatMessage(userId, "dm", userId, 0, "user", "hello", aboveThreshold);

        // Deterministic compress outcome, flipped mid-test.
        class TogglingCompressHandler extends CompressCommandHandler {
            boolean fail = true;

            @Override
            public CompressResult compress(UUID u, String scopeKind,
                                           UUID scopeId, String scopeLanguage) {
                return fail ? new CompressResult.Failure()
                        : new CompressResult.Success(1);
            }
        }
        TogglingCompressHandler compressHandler = new TogglingCompressHandler();
        AutoCompressTrigger gateTrigger = new AutoCompressTrigger(
                compressAtThreshold, bundleLoader, compressHandler, dataSource);

        assertFalse(gateTrigger.isCeilingGated(userId, "dm", userId),
                "no gate before any compress failure");

        Optional<String> notice = gateTrigger.checkAndCompress(userId, "dm", userId, "en");
        assertEquals(bundleLoader.get(BundleKeys.ERROR_COMPRESS_FAILED),
                notice.orElseThrow());
        assertTrue(gateTrigger.isCeilingGated(userId, "dm", userId),
                "failed auto-compress at the ceiling must gate the session");
        assertFalse(gateTrigger.isCeilingGated(userId, "group", UUID.randomUUID()),
                "the gate is per-(user, scope) — other scopes stay open");

        // A later successful auto-compress clears the gate eagerly.
        compressHandler.fail = false;
        Optional<String> success = gateTrigger.checkAndCompress(userId, "dm", userId, "en");
        assertEquals(bundleLoader.get(BundleKeys.REPLY_AUTO_COMPRESS_NOTICE),
                success.orElseThrow());
        assertFalse(gateTrigger.isCeilingGated(userId, "dm", userId),
                "a successful compress must clear the gate");
    }

    @Test
    void ceilingGateClearsWhenTokenCountFallsBelowThreshold() throws Exception {
        String contactId = PREFIX + "gate-lazy-actor";
        UUID userId = seedUser(contactId);
        int aboveThreshold = compressAtThreshold + 50;
        seedChatSessionWithTokens(userId, "dm", userId, aboveThreshold);
        seedChatMessage(userId, "dm", userId, 0, "user", "hello", aboveThreshold);

        AutoCompressTrigger gateTrigger = new AutoCompressTrigger(
                compressAtThreshold, bundleLoader,
                new CompressCommandHandler() {
                    @Override
                    public CompressResult compress(UUID u, String scopeKind,
                                                   UUID scopeId, String scopeLanguage) {
                        return new CompressResult.Failure();
                    }
                }, dataSource);

        gateTrigger.checkAndCompress(userId, "dm", userId, "en");
        assertTrue(gateTrigger.isCeilingGated(userId, "dm", userId));

        // A successful manual /compress or /clear resets token_count
        // without passing through checkAndCompress — the gate must
        // clear lazily on the next consult.
        seedChatSessionWithTokens(userId, "dm", userId, 0);
        assertFalse(gateTrigger.isCeilingGated(userId, "dm", userId),
                "gate must clear once token_count falls below the threshold");
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

    private void seedChatSessionWithTokens(UUID userId, String scopeKind,
                                           UUID scopeId, int tokenCount) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Insert session then directly set token_count (bypassing the trigger).
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO chat_session (user_id, scope_kind, scope_id) "
                            + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                ps.setObject(1, userId);
                ps.setString(2, scopeKind);
                ps.setObject(3, scopeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE chat_session SET token_count = ? "
                            + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
                ps.setInt(1, tokenCount);
                ps.setObject(2, userId);
                ps.setString(3, scopeKind);
                ps.setObject(4, scopeId);
                ps.executeUpdate();
            }
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

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
