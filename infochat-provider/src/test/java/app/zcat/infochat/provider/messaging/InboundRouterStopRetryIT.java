package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@code /stop} and {@code /retry} through the full
 * {@link InboundRouter} dispatch with a real DB, {@link TestLlmProvider},
 * and {@link InMemoryAdapter}.
 */
@QuarkusTest
class InboundRouterStopRetryIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "stop-retry-it-";
    private static final String GUARDIAN = "stop-retry-it-guardian-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject TestLlmProvider testLlmProvider;
    @Inject SummaryAnchorRepository summaryAnchorRepository;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                  + "VALUES (?, ?, TRUE, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);
            // Clean summary_anchor rows for test contacts
            exec(conn,
                    "DELETE FROM summary_anchor WHERE user_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ?)",
                    CONTACT_PREFIX + "%");
            exec(conn,
                    "DELETE FROM chat_session WHERE user_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                    CONTACT_PREFIX + "%", GUARDIAN);
            exec(conn,
                    "DELETE FROM users WHERE contact_id LIKE ? AND contact_id != ?",
                    CONTACT_PREFIX + "%", GUARDIAN);
        }
    }

    @Test
    void stopIdempotentWhenNothingInFlight() throws Exception {
        seedVouchedUser("stop-1");

        adapter.deliverDm(CONTACT_PREFIX + "stop-1", "/stop");

        OutboundMessage reply = lastReply();
        assertTrue(reply.text().contains("Nothing to cancel"),
                "/stop with nothing in flight must return idempotent noop. Got: " + reply.text());
    }

    @Test
    void retryWithNoAnchorReturnsError() throws Exception {
        seedVouchedUser("retry-1");

        adapter.deliverDm(CONTACT_PREFIX + "retry-1", "/retry");

        OutboundMessage reply = lastReply();
        assertTrue(reply.text().contains("Nothing to retry"),
                "/retry with no anchor must return friendly error. Got: " + reply.text());
    }

    @Test
    void retryWithDirectAnchorReGeneratesProse() throws Exception {
        String contactId = CONTACT_PREFIX + "retry-2";
        seedVouchedUser("retry-2");
        UUID userId = resolveUserId(contactId);

        // Seed a READY post and write the anchor directly (bypasses
        // the EligiblePostQuery subscription logic which is not this
        // ticket's concern).
        String postUid = seedReadyPost(userId, "retry-it-post-1", "Bitcoin hits new high");
        summaryAnchorRepository.write(userId, userId, "summary", "hash1",
                List.of(postUid), "[{\"topicId\":\"t-retry\",\"postUids\":[\"" + postUid + "\"]}]");

        testLlmProvider.setResponseText("Retried summary prose.");

        adapter.deliverDm(contactId, "/retry");
        OutboundMessage retryReply = lastReply();

        assertTrue(retryReply.text().contains("Retried summary prose")
                        || retryReply.text().contains("[topic_id="),
                "/retry must re-generate prose. Got: " + retryReply.text());
    }

    @Test
    void anchorClearedOnNonRetryInput() throws Exception {
        String contactId = CONTACT_PREFIX + "retry-3";
        seedVouchedUser("retry-3");
        UUID userId = resolveUserId(contactId);

        // Write an anchor directly
        String postUid = seedReadyPost(userId, "retry-it-post-2", "Monero update");
        summaryAnchorRepository.write(userId, userId, "summary", "hash2",
                List.of(postUid), null);

        // Any non-/retry input clears the anchor
        adapter.deliverDm(contactId, "/help");

        // Now /retry should fail because the anchor was cleared
        adapter.deliverDm(contactId, "/retry");
        OutboundMessage retryReply = lastReply();
        assertTrue(retryReply.text().contains("Nothing to retry"),
                "anchor must be cleared by non-/retry input. Got: " + retryReply.text());
    }

    // --- helpers ---

    private void seedVouchedUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, registration_state) "
                  + "VALUES (?, ?, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET registration_state = 'vouched', is_banned = FALSE, "
                  + "    probation_until = NULL",
                    ADAPTER, CONTACT_PREFIX + suffix);
        }
    }

    private UUID resolveUserId(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    /**
     * Seed a READY post and return its uid. Uses a fixed source seeded
     * once; the post's uid is returned so callers can reference it in
     * anchor writes.
     */
    private String seedReadyPost(UUID userId, String uid, String title) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO source (kind, identifier, display_name, category) "
                  + "VALUES ('rss', 'https://example.com/stop-retry-it', 'StopRetryIT Source', 'news') "
                  + "ON CONFLICT (kind, identifier) DO NOTHING");
            UUID sourceId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM source WHERE kind = 'rss' AND identifier = 'https://example.com/stop-retry-it'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    sourceId = (UUID) rs.getObject("id");
                }
            }
            exec(conn,
                    "INSERT INTO post (uid, source_id, title, url, body, published_at, status, tags) "
                  + "VALUES (?, ?, ?, ?, 'Body', now(), 'READY', ARRAY['test-it']::text[]) "
                  + "ON CONFLICT DO NOTHING",
                    uid, sourceId, title, "https://example.com/" + uid);
        }
        return uid;
    }

    private OutboundMessage lastReply() {
        var sent = adapter.sentMessages();
        assertFalse(sent.isEmpty(), "Expected at least one reply");
        return sent.getLast();
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
