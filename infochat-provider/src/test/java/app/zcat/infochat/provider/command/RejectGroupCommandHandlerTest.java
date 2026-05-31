package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
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
import java.text.MessageFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link RejectGroupCommandHandler} against
 * DevServices Postgres and the in-memory adapter. One {@code @Test} per
 * acceptance scenario (a)..(d) in M1-113. {@code /reject-group} is
 * confirm-gated (M1-051 pattern) — happy-path tests issue the first
 * call to receive the prompt, then the {@code confirm} leg.
 */
@QuarkusTest
class RejectGroupCommandHandlerTest {

    private static final String PREFIX = "m1-113-reject-";
    private static final String UPSTREAM_PREFIX = "m1-113-reject-grp-";
    private static final String ADAPTER = "inmemory";

    @Inject RejectGroupCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject InMemoryAdapter inMemoryAdapter;

    @BeforeEach
    @AfterEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        inMemoryAdapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // No permanent guardian admin — see the matching comment in
            // ApproveGroupCommandHandlerTest.cleanup. Disable
            // trg_users_last_admin_delete for the cleanup pass; the
            // finally block restores it so the invariant always holds
            // outside this cleanup.
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            exec(conn, "ALTER TABLE users DISABLE TRIGGER trg_users_last_admin_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_id IN ("
                                + "  SELECT id::text FROM groups WHERE upstream_group_id LIKE ?)",
                        UPSTREAM_PREFIX + "%");
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN "
                                + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM group_membership WHERE group_id IN "
                                + "(SELECT id FROM groups WHERE upstream_group_id LIKE ?)",
                        UPSTREAM_PREFIX + "%");
                exec(conn,
                        "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                        UPSTREAM_PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_delete");
            }
        }
    }

    // ----- (a) Non-admin → error.admin_only --------------------------------

    @Test
    void rejectByNonAdminReturnsAdminOnly() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        seedUser(ADAPTER, actor, false, false);
        UUID groupId = seedGroup(ADAPTER, UPSTREAM_PREFIX + "a", "pending");
        inboundContext.setSenderContactId(actor);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/reject-group " + groupId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /reject-group must surface error.admin_only");
        assertEquals("pending", readApprovalStatus(groupId),
                "approval_status must remain pending on the admin-gate reject");
        assertEquals(0, countAuditAction(groupId, "REJECT_GROUP_INTENT"),
                "non-admin first-call must not write a REJECT_GROUP_INTENT audit row");
    }

    // ----- (b) Pending → rejected (confirm required) -----------------------

    @Test
    void rejectPendingGroupRequiresConfirmThenFlipsStatus() throws Exception {
        String actor = PREFIX + "pending-actor";
        seedUser(ADAPTER, actor, true, false);
        UUID groupId = seedGroup(ADAPTER, UPSTREAM_PREFIX + "pending", "pending");
        inboundContext.setSenderContactId(actor);

        // First call → prompt; status unchanged; REJECT_GROUP_INTENT written.
        OutboundMessage prompt = handler.handle(
                new ScopeRef.Dm(actor),
                "/reject-group " + groupId);
        // Match on substring because the prompt's timeout token (e.g.
        // "5s") may differ across profiles; the key behaviour is
        // "is the confirm prompt, not the success reply".
        assertTrue(prompt.text().contains(groupId.toString()),
                "first call must surface the confirm prompt containing the group id, got: "
                        + prompt.text());
        assertFalse(prompt.text().equals(bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING)),
                "first call must NOT surface error.confirm.no_pending");
        assertEquals("pending", readApprovalStatus(groupId),
                "first call must not mutate approval_status");
        assertEquals(1, countAuditAction(groupId, "REJECT_GROUP_INTENT"),
                "first call must write exactly one REJECT_GROUP_INTENT audit row");

        // Confirm call → flips to rejected, writes REJECT_GROUP audit row,
        // sends group notification.
        OutboundMessage success = handler.handle(
                new ScopeRef.Dm(actor),
                "/reject-group " + groupId + " confirm");
        String expectedReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_REJECT_GROUP_SUCCESS), groupId);
        assertEquals(expectedReply, success.text(),
                "confirm call success reply must interpolate the group id");
        assertEquals("rejected", readApprovalStatus(groupId),
                "approval_status must flip to rejected on the confirm call");
        assertEquals(1, countAuditAction(groupId, "REJECT_GROUP"),
                "confirm call must write exactly one REJECT_GROUP audit row");
    }

    // ----- (c) Approved → rejected (confirm + group msg + audit) -----------

    @Test
    void rejectApprovedGroupRequiresConfirmSendsGroupMessageWritesAudit() throws Exception {
        String actor = PREFIX + "approved-actor";
        UUID actorId = seedUser(ADAPTER, actor, true, false);
        String upstream = UPSTREAM_PREFIX + "approved";
        UUID groupId = seedGroup(ADAPTER, upstream, "approved");
        inboundContext.setSenderContactId(actor);

        // First call → prompt.
        OutboundMessage prompt = handler.handle(
                new ScopeRef.Dm(actor),
                "/reject-group " + groupId);
        assertTrue(prompt.text().contains(groupId.toString()),
                "first call must surface the confirm prompt containing the group id");
        assertEquals("approved", readApprovalStatus(groupId),
                "first call must not mutate approval_status");

        // Confirm call → flips approved→rejected; group notification sent;
        // REJECT_GROUP audit row written with previous_status='approved'.
        OutboundMessage success = handler.handle(
                new ScopeRef.Dm(actor),
                "/reject-group " + groupId + " confirm");
        String expectedReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_REJECT_GROUP_SUCCESS), groupId);
        assertEquals(expectedReply, success.text());
        assertEquals("rejected", readApprovalStatus(groupId));

        // Group message addressed at the target group's scope.
        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        boolean foundGroupMessage = sent.stream().anyMatch(m ->
                m.scope() instanceof ScopeRef.Group g
                        && g.adapterGroupId().equals(upstream)
                        && m.text().equals(bundleLoader.get(BundleKeys.GROUP_REJECTED_MESSAGE)));
        assertTrue(foundGroupMessage,
                "exactly one group.rejected_message must be sent to the target group's scope");

        // Audit row: previous_status='approved' captured in details_json.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT actor_user_id, target_kind, target_id, details_json "
                             + "FROM audit_log WHERE action = 'REJECT_GROUP' AND target_id = ?")) {
            ps.setString(1, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "REJECT_GROUP audit row must exist for groupId=" + groupId);
                assertEquals(actorId, (UUID) rs.getObject("actor_user_id"));
                assertEquals("group", rs.getString("target_kind"));
                String detailsJson = rs.getString("details_json");
                assertNotNull(detailsJson);
                // PostgreSQL JSONB normalizes whitespace; match tokens
                // independently rather than the concatenated literal.
                assertTrue(detailsJson.contains("\"previous_status\"")
                                && detailsJson.contains("\"approved\""),
                        "details_json must record previous_status='approved', got: " + detailsJson);
            }
        }
    }

    // ----- (d) Already rejected → no-op reply (first call only) ------------

    @Test
    void rejectAlreadyRejectedGroupReturnsNoopOnFirstCall() throws Exception {
        String actor = PREFIX + "alreadyRejected-actor";
        seedUser(ADAPTER, actor, true, false);
        UUID groupId = seedGroup(ADAPTER, UPSTREAM_PREFIX + "alreadyRejected", "rejected");
        inboundContext.setSenderContactId(actor);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/reject-group " + groupId);

        String expectedReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_REJECT_GROUP_NOOP), groupId);
        assertEquals(expectedReply, reply.text(),
                "already-rejected first call must short-circuit with the no-op reply");
        assertEquals("rejected", readApprovalStatus(groupId),
                "approval_status must remain rejected");
        assertEquals(0, countAuditAction(groupId, "REJECT_GROUP_INTENT"),
                "no-op path must NOT write REJECT_GROUP_INTENT (intent is suppressed when there is no intent)");
        assertEquals(0, countAuditAction(groupId, "REJECT_GROUP"),
                "no-op path must NOT write REJECT_GROUP");
    }

    private long countAuditAction(UUID groupId, String action) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log "
                             + "WHERE action = ? AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ----- DB helpers (inlined to keep file count at the M1-113 budget) ----

    private UUID seedUser(String adapter, String contactId, boolean isAdmin, boolean isBanned) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, registration_state) "
                             + "VALUES (?, ?, ?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedGroup(String adapter, String upstreamGroupId, String approvalStatus) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, approval_status) "
                             + "VALUES (?, ?, ?) RETURNING id")) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            ps.setString(3, approvalStatus);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private String readApprovalStatus(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT approval_status FROM groups WHERE id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("approval_status");
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }
}
