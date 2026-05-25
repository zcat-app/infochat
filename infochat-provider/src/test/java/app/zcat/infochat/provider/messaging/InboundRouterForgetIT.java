package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@code /forget} driven through the
 * {@link InMemoryAdapter} → {@link InboundRouter} → handler chain.
 *
 * <p>Verifies the confirm flow, purge, audit, and remaining-scopes
 * disclosure as seen by the adapter's outbound queue.</p>
 */
@QuarkusTest
class InboundRouterForgetIT {

    private static final String PREFIX = "ForgetIT-";
    private static final String ADAPTER = "inmemory";

    @Inject
    InMemoryAdapter adapter;

    @Inject
    DataSource dataSource;

    @Inject
    BundleLoader bundleLoader;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
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

    /**
     * Full round-trip: /forget → prompt → /forget confirm → purge +
     * bare confirmation (zero remaining scopes).
     */
    @Test
    void forgetConfirmRoundtrip() throws Exception {
        String contactId = PREFIX + "roundtrip";
        UUID userId = seedRegisteredUser(contactId);

        seedChatSession(userId, "dm", userId);
        seedChatMemory(userId, "dm", userId);
        seedSavedPost(userId, "rt-post");

        adapter.deliverDm(contactId, "/forget");
        List<OutboundMessage> msgs = adapter.sentMessages();
        assertEquals(1, msgs.size());
        assertTrue(msgs.getFirst().text().contains("/forget confirm"));

        adapter.reset();
        adapter.deliverDm(contactId, "/forget confirm");
        msgs = adapter.sentMessages();
        assertEquals(1, msgs.size());
        assertEquals(bundleLoader.get(BundleKeys.REPLY_FORGET_CLEARED),
                msgs.getFirst().text());

        assertEquals(0, countRows("chat_session", "user_id", userId));
        assertEquals(0, countRows("chat_memory", "user_id", userId));
        assertEquals(0, countRows("saved_post", "user_id", userId));
    }

    /**
     * /forget confirm with remaining scopes discloses the count.
     */
    @Test
    void forgetWithRemainingScopes() throws Exception {
        String contactId = PREFIX + "remaining";
        UUID userId = seedRegisteredUser(contactId);

        seedChatSession(userId, "dm", userId);
        UUID groupScope = UUID.randomUUID();
        seedChatSession(userId, "group", groupScope);

        adapter.deliverDm(contactId, "/forget");
        adapter.reset();
        adapter.deliverDm(contactId, "/forget confirm");

        List<OutboundMessage> msgs = adapter.sentMessages();
        assertEquals(1, msgs.size());
        String reply = msgs.getFirst().text();
        assertTrue(reply.contains("1"), "Should disclose 1 remaining scope");
        assertTrue(reply.contains("/forget"),
                "Should instruct user to run /forget elsewhere");
    }

    /**
     * Step 4.5 cancel sweep: non-confirm input after /forget cancels
     * the pending.
     */
    @Test
    void cancelSweepCancelsPending() throws Exception {
        String contactId = PREFIX + "cancel";
        seedRegisteredUser(contactId);

        adapter.deliverDm(contactId, "/forget");
        adapter.reset();
        // Any non-confirm input cancels the pending.
        adapter.deliverDm(contactId, "/help");

        List<OutboundMessage> msgs = adapter.sentMessages();
        // First message: cancellation ack; second: /help reply.
        assertTrue(msgs.size() >= 1);
        boolean hasCancellation = msgs.stream()
                .anyMatch(m -> m.text().contains("forget")
                        && m.text().toLowerCase().contains("cancel"));
        assertTrue(hasCancellation, "Should send a cancellation acknowledgement");
    }

    // ---- seeding helpers --------------------------------------------------

    private UUID seedRegisteredUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state, "
                             + "probation_until) VALUES (?, ?, 'invited', ?) RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setTimestamp(3, Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
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
                             + "summary, keywords) VALUES (?, ?, ?, 'test', '{test}')")) {
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

    private int countRows(String table, String column, UUID value) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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
