package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.testing.TestLlmProvider;
import app.zcat.infochat.provider.testsupport.DispatchAwaits;
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
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject CommandPermissions commandPermissions;
    @Inject TestLlmProvider testLlmProvider;
    @Inject InterruptibleDispatcher interruptibleDispatcher;

    @ConfigProperty(name = "infochat.llm.chat.timeout-ms")
    long chatTimeoutMs;

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
            // Clear the help corpora: StubEmbeddingProvider makes the
            // step-3c probes match every turn, which would break the
            // exact-body assertion (same mechanism as EmptyLlmReplyDeliveryIT).
            exec(conn,
                    "DELETE FROM doc_embedding WHERE doc_kind IN ('topic', 'command_intent')");
        }
    }

    @Test
    void chatModeDispatchesToAgent() throws Exception {
        seedVouchedUser("user-1");
        testLlmProvider.setResponseText("Hello from the chat agent!");

        adapter.deliverDm(CONTACT_PREFIX + "user-1", "tell me about bitcoin");

        // The chat turn self-delivers via the ProgressNotifier (M1-607):
        // the reply REPLACES the D31 placeholder, so it is read from the
        // finalize event, not the last plain send (mirroring SummaryIT).
        // The outbound carries the M1-617 provenance notice after the
        // reply — general-knowledge here, since this user has no
        // subscriptions and the pre-fetch retrieves nothing. Interruptible
        // dispatch is offloaded (M1-634), so await the terminal.
        DispatchAwaits.await(() -> !adapter.finalizedBodies().isEmpty(),
                "chat turn's finalized terminal");
        String replyBody = lastFinalizedBody();
        assertEquals("Hello from the chat agent!\n\n"
                        + bundleLoader.get("reply.chat.provenance.general_knowledge"),
                replyBody,
                "the finalized placeholder must carry the ChatAgent reply plus "
                        + "the retrieval-provenance notice (M1-617)");
        // The LLM was called
        assertTrue(testLlmProvider.callCount() > 0,
                "TestLlmProvider should have been called");
        // Post-delivery commit runs after the finalize — drain it so the
        // chat_session write cannot bleed past this test's end.
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "chat turn fully complete (incl. post-delivery commit)");
    }

    @Test
    void configKeyTokenIsAbsentFromFinalizedChatReply() throws Exception {
        // BOUNDARY SITING (M1-815 r1 review): the sanitizer's own tests
        // cannot see a bypass between ChatAgent and the adapter, so the
        // finalized body — what the user actually receives — is asserted.
        seedVouchedUser("user-config-key");
        testLlmProvider.setResponseText(
                "The window is set by infochat.probation.duration in this deployment.");

        adapter.deliverDm(CONTACT_PREFIX + "user-config-key", "tell me about the window");

        DispatchAwaits.await(() -> !adapter.finalizedBodies().isEmpty(),
                "chat turn's finalized terminal");
        String replyBody = lastFinalizedBody();
        assertFalse(replyBody.contains("infochat."),
                "no dotted config token may reach the adapter; got: " + replyBody);
        assertEquals("The window is set by   in this deployment.\n\n"
                        + bundleLoader.get("reply.chat.provenance.general_knowledge"),
                replyBody,
                "the finalized body carries the sanitized prose (token replaced by a "
                        + "single space) plus the provenance notice");
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "chat turn fully complete (incl. post-delivery commit)");
    }

    /**
     * BUNDLED TIMEOUT RAISE (M1-607): the committed
     * infochat.llm.chat.timeout-ms default must resolve above the in-code
     * 30000ms floor (OpenAiCompatibleProvider/AnthropicProvider configFor
     * .orElse(30000L)) so a slow-but-working chat generation is not
     * cancelled and the reply lost (F-live-6).
     */
    @Test
    void chatTimeoutDefaultResolvesAboveInCodeThirtySecondFloor() {
        assertTrue(chatTimeoutMs > 30000,
                "infochat.llm.chat.timeout-ms must resolve above the in-code"
                        + " 30000ms floor; resolved: " + chatTimeoutMs);
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
    void oversizedChatMessageDoesNotClearDmAnchor() throws Exception {
        UUID userId = seedVouchedUserReturningId("user-oversize-anchor");
        // Seed a DM-scope anchor (scope_id == actor UUID for DM)
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO summary_anchor "
                  + "(user_id, scope_kind, scope_id, command_kind, command_name, arg_hash, post_uids) "
                  + "VALUES (?, 'dm', ?, 'personal', 'summary', 'hash1', '{}'::text[]) "
                  + "ON CONFLICT (user_id, scope_kind, scope_id, command_kind) "
                  + "  WHERE user_id IS NOT NULL "
                  + "  DO NOTHING",
                    userId, userId);
        }

        adapter.deliverDm(CONTACT_PREFIX + "user-oversize-anchor", "x".repeat(300));

        assertEquals(bundleLoader.get("error.chat.body_too_large"), lastReply().text());
        // The cap fires BEFORE the step-4.6 anchor clear — the spec
        // forbids any DB write for an oversized chat-mode message, so
        // the anchor row must survive.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM summary_anchor "
                   + "WHERE user_id = ? AND scope_id = ? AND command_kind = 'personal'")) {
            ps.setObject(1, userId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                        "oversized chat message must NOT clear the anchor (no DB write past the cap)");
            }
        }
    }

    @Test
    void oversizedGroupChatMessageWritesNoMembershipRow() throws Exception {
        UUID userId = seedVouchedUserReturningId("user-oversize-group");
        UUID groupId = seedGroup("group-oversize");

        deliverGroup(CONTACT_PREFIX + "user-oversize-group",
                GROUP_PREFIX + "group-oversize", "x".repeat(300));

        assertEquals(bundleLoader.get("error.chat.body_too_large"), lastReply().text());
        assertEquals(0, testLlmProvider.callCount(),
                "LLM should NOT be called for oversized body");
        // The cap fires BEFORE the step-4.1 membership write.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM group_membership WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(),
                        "oversized group chat message must NOT write a group_membership row");
            }
        }
    }

    @Test
    void probationUserBlockedFromChatMode() throws Exception {
        seedProbationUser("user-3");

        adapter.deliverDm(CONTACT_PREFIX + "user-3", "hello from probation");

        OutboundMessage reply = lastReply();
        // Probation users get the error.probation.blocked reply, not the chat
        // agent. Assert via the bundle key (not English substring fragments):
        // seeded probation_until = now + 24h, so formatTimeUntilUnlock truncates
        // the strictly-under-24h remaining to the "~23h" {0} token; {1} carries
        // the canonical probation command list (M1-590).
        String expected = java.text.MessageFormat.format(
                bundleLoader.get("error.probation.blocked"), "~23h",
                commandPermissions.renderProbationCommandList());
        assertEquals(expected, reply.text(),
                "Probation user must receive the error.probation.blocked reply "
                        + "with the ~23h time-until-unlock interpolated");
        assertEquals(0, testLlmProvider.callCount(),
                "LLM should NOT be called for probation user");
    }

    @Test
    void llmUnreachableReturnsFriendlyError() throws Exception {
        seedVouchedUser("user-4");
        testLlmProvider.setThrowOnCall(true);

        adapter.deliverDm(CONTACT_PREFIX + "user-4", "hi");

        // ChatAgent's friendly error is the turn's reply, so it lands via
        // the notifier's finalize like any other chat reply (M1-607);
        // await it across the M1-634 worker hop.
        DispatchAwaits.await(() -> !adapter.finalizedBodies().isEmpty(),
                "friendly-error finalized terminal");
        assertEquals(bundleLoader.get("error.chat.unavailable"), lastFinalizedBody());
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "failed turn's worker fully drained");
    }

    @Test
    void llmRateCapRejectsExcessiveRequests() throws Exception {
        seedVouchedUser("user-5");
        testLlmProvider.setResponseText("ok");

        // %test cap is 3 per minute — send 3 messages, awaiting each turn's
        // terminal so the turns run one at a time: rapid-fire same-(user,
        // scope) messages would now contend on the M1-634 in-flight guard
        // instead of consuming the cap deterministically.
        for (int i = 0; i < 3; i++) {
            adapter.deliverDm(CONTACT_PREFIX + "user-5", "msg " + i);
            int expectedTerminals = i + 1;
            DispatchAwaits.await(
                    () -> adapter.finalizedBodies().size() >= expectedTerminals,
                    "turn " + expectedTerminals + " terminal before the next drive");
        }
        int callsBeforeCap = testLlmProvider.callCount();

        // 4th message should be rejected by the rate cap; the rejection is
        // a plain worker-side send (4th send after the 3 placeholders).
        adapter.deliverDm(CONTACT_PREFIX + "user-5", "one too many");

        DispatchAwaits.await(() -> adapter.sentMessages().size() >= 4,
                "rate-cap rejection reply");
        OutboundMessage reply = lastReply();
        assertEquals(bundleLoader.get("error.chat.llm_rate_cap"), reply.text(),
                "4th message should get the LLM rate cap error");
        // Negative assert — pool quiescence makes "no further LLM call can
        // arrive" a happens-before fact.
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "dispatch pool quiescent before the no-LLM-call assert");
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

        // The chat_session write happens in the worker's post-delivery
        // commit (M1-634 offload) — await pool quiescence before reading.
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "group chat turn fully complete (incl. post-delivery commit)");

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
                  + "(user_id, scope_kind, scope_id, command_kind, command_name, arg_hash, post_uids) "
                  + "VALUES (?, 'group', ?, 'personal', 'summary', 'hash1', '{}'::text[]) "
                  + "ON CONFLICT (user_id, scope_kind, scope_id, command_kind) "
                  + "  WHERE user_id IS NOT NULL "
                  + "  DO NOTHING",
                    userId, groupId);
        }

        // Send a non-/retry group message — should clear the group-scope anchor
        deliverGroup(CONTACT_PREFIX + "user-anchor-1",
                GROUP_PREFIX + "group-anchor-1", "hello triggers anchor clear");

        // The anchor clear itself is inline intake (step 4.6, pre-offload);
        // the await only keeps the turn's worker from bleeding past this
        // test's end (M1-634 hygiene).
        DispatchAwaits.await(() -> interruptibleDispatcher.inFlightTaskCount() == 0,
                "group chat turn fully complete");

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

    private String lastFinalizedBody() {
        var finalized = adapter.finalizedBodies();
        assertFalse(finalized.isEmpty(), "Expected at least one finalized reply");
        return finalized.getLast();
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
