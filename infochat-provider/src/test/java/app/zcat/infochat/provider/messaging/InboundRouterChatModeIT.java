package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class InboundRouterChatModeIT {

    private static final String ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "chat-mode-it-";
    private static final String GROUP_PREFIX = "chat-mode-it-group-";
    private static final String GUARDIAN = "chat-mode-it-guardian-permanent";

    @Inject InMemoryAdapter adapter;
    @Inject InboundRouter router;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject TestLlmProvider testLlmProvider;

    @BeforeEach
    void setUp() throws Exception {
        adapter.reset();
        testLlmProvider.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Guardian admin so last-admin-protection trigger does not block cleanup
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                  + "VALUES (?, ?, TRUE, 'vouched') "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, GUARDIAN);
            // Clean chat_session rows first (FK constraint blocks user deletion)
            exec(conn,
                    "DELETE FROM chat_session WHERE user_id IN ("
                  + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                    CONTACT_PREFIX + "%", GUARDIAN);
            // Clean group_membership (FK to both groups and users)
            exec(conn,
                    "DELETE FROM group_membership WHERE group_id IN ("
                  + "SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                    GROUP_PREFIX + "%");
            // Disable append-only triggers so test cleanup can delete
            // audit_log rows (FK on actor_user_id blocks user deletion)
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                      + "SELECT id FROM users WHERE contact_id LIKE ? AND contact_id != ?)",
                        CONTACT_PREFIX + "%", GUARDIAN);
                // Clean prior test users
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ? AND contact_id != ?",
                        CONTACT_PREFIX + "%", GUARDIAN);
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            // Clean test groups (after users, since group_membership is already gone)
            exec(conn,
                    "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                    GROUP_PREFIX + "%");
        }
    }

    @Test
    void chatModeDispatchesToAgent() throws Exception {
        seedVouchedUser("user-1");
        testLlmProvider.setResponseText("Hello from the chat agent!");

        adapter.deliverDm(CONTACT_PREFIX + "user-1", "tell me about bitcoin");

        OutboundMessage reply = lastReply();
        String replyBody = reply.text();
        // The reply must NOT be the static sentinel
        assertFalse(replyBody.contains("Chat-mode replies are not in the MVP"),
                "Should dispatch to ChatAgent, not return the old sentinel");
        // The LLM was called
        assertTrue(testLlmProvider.callCount() > 0,
                "TestLlmProvider should have been called");
    }

    @Test
    void rejectsOversizedChatMessage() throws Exception {
        seedVouchedUser("user-2");
        // %test.infochat.chat.body-cap=256 in application.properties
        String oversizedBody = "x".repeat(300);

        adapter.deliverDm(CONTACT_PREFIX + "user-2", oversizedBody);

        OutboundMessage reply = lastReply();
        assertEquals(bundleLoader.get("error.chat.body_too_large"), reply.text());
        assertEquals(0, testLlmProvider.callCount(),
                "LLM should NOT be called for oversized body");
    }

    @Test
    void probationUserBlockedFromChatMode() throws Exception {
        seedProbationUser("user-3");

        adapter.deliverDm(CONTACT_PREFIX + "user-3", "hello from probation");

        OutboundMessage reply = lastReply();
        // Probation users get the error.probation.blocked reply, not the chat agent
        assertTrue(reply.text().contains("probation") || reply.text().contains("full access"),
                "Probation user should receive the probation-blocked reply");
        assertEquals(0, testLlmProvider.callCount(),
                "LLM should NOT be called for probation user");
    }

    @Test
    void llmUnreachableReturnsFriendlyError() throws Exception {
        seedVouchedUser("user-4");
        testLlmProvider.setThrowOnCall(true);

        adapter.deliverDm(CONTACT_PREFIX + "user-4", "hi");

        OutboundMessage reply = lastReply();
        assertEquals(bundleLoader.get("error.chat.unavailable"), reply.text());
    }

    @Test
    void llmRateCapRejectsExcessiveRequests() throws Exception {
        seedVouchedUser("user-5");
        testLlmProvider.setResponseText("ok");

        // %test cap is 3 per minute — send 4 messages in quick succession
        for (int i = 0; i < 3; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "user-5", "msg " + i);
        }
        int callsBeforeCap = testLlmProvider.callCount();

        // 4th message should be rejected by the rate cap
        adapter.deliverDm(CONTACT_PREFIX + "user-5", "one too many");

        OutboundMessage reply = lastReply();
        assertEquals(bundleLoader.get("error.chat.llm_rate_cap"), reply.text(),
                "4th message should get the LLM rate cap error");
        assertEquals(callsBeforeCap, testLlmProvider.callCount(),
                "LLM should NOT be called when rate cap is exceeded");
    }

    @Test
    void groupScopeUsesGroupIdNotActorId() throws Exception {
        UUID userId = seedVouchedUserReturningId("user-group-1");
        UUID groupId = seedGroup("group-1");
        testLlmProvider.setResponseText("Group chat reply");

        deliverGroup(CONTACT_PREFIX + "user-group-1",
                GROUP_PREFIX + "group-1", "hello from group");

        // chat_session.scope_id must be the group UUID, not the actor UUID
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT scope_id FROM chat_session "
                   + "WHERE user_id = ? AND scope_kind = 'group'")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Expected a group-scope chat_session row");
                UUID scopeId = (UUID) rs.getObject("scope_id");
                assertEquals(groupId, scopeId,
                        "scope_id should be the group UUID, not the actor UUID");
                assertNotEquals(userId, scopeId,
                        "scope_id must not be the actor UUID");
            }
        }
    }

    @Test
    void groupScopeAnchorClearUsesGroupId() throws Exception {
        UUID userId = seedVouchedUserReturningId("user-anchor-1");
        UUID groupId = seedGroup("group-anchor-1");
        testLlmProvider.setResponseText("ack");

        // Seed a summary_anchor row for (user, group-scope)
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO summary_anchor "
                  + "(user_id, scope_id, command_kind, command_name, arg_hash, post_uids) "
                  + "VALUES (?, ?, 'personal', 'summary', 'hash1', '{}'::text[]) "
                  + "ON CONFLICT (user_id, scope_id, command_kind) "
                  + "  WHERE user_id IS NOT NULL "
                  + "  DO NOTHING",
                    userId, groupId);
        }

        // Send a non-/retry group message — should clear the group-scope anchor
        deliverGroup(CONTACT_PREFIX + "user-anchor-1",
                GROUP_PREFIX + "group-anchor-1", "hello triggers anchor clear");

        // The group-scope anchor must be deleted
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM summary_anchor "
                   + "WHERE user_id = ? AND scope_id = ? AND command_kind = 'personal'")) {
            ps.setObject(1, userId);
            ps.setObject(2, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(),
                        "Group-scope anchor should be cleared by non-/retry group message");
            }
        }
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

    private void seedProbationUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, registration_state, probation_until) "
                  + "VALUES (?, ?, 'invited', ?) "
                  + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                  + "  SET registration_state = 'invited', is_banned = FALSE, "
                  + "    probation_until = ?",
                    ADAPTER, CONTACT_PREFIX + suffix,
                    OffsetDateTime.now().plusHours(24),
                    OffsetDateTime.now().plusHours(24));
        }
    }

    private OutboundMessage lastReply() {
        var sent = adapter.sentMessages();
        assertFalse(sent.isEmpty(), "Expected at least one reply");
        return sent.getLast();
    }

    private UUID seedVouchedUserReturningId(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state) "
                   + "VALUES (?, ?, 'vouched') "
                   + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                   + "  SET registration_state = 'vouched', is_banned = FALSE, "
                   + "    probation_until = NULL "
                   + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, CONTACT_PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedGroup(String suffix) throws Exception {
        // approval_status='approved' (M1-112): bypass the D47 step-3.5
        // gate so chat-mode tests reach the dispatch path. ON CONFLICT
        // overwrites the column so a row carried over from a prior test
        // run is also normalized to approved.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name, approval_status) "
                   + "VALUES (?, ?, ?, 'approved') "
                   + "ON CONFLICT (adapter, upstream_group_id) DO UPDATE "
                   + "  SET removed_at = NULL, approval_status = 'approved' "
                   + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, GROUP_PREFIX + suffix);
            ps.setString(3, suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void deliverGroup(String contactId, String adapterGroupId, String text) {
        Identity sender = new Identity(contactId, contactId, Instant.now());
        InboundMessage msg = new InboundMessage(
                sender,
                new ScopeRef.Group(adapterGroupId),
                text,
                Instant.now(),
                "inmem-test-group-" + System.nanoTime());
        router.onMessage(msg, ADAPTER);
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
