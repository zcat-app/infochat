package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
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
 * Integration tests for {@link ApproveGroupCommandHandler} against
 * DevServices Postgres (V5 users + V26 groups + audit_log) and the
 * in-memory adapter. One {@code @Test} per acceptance scenario
 * (a)..(e) in M1-113.
 *
 * <p>Cleanup uses a class-wide {@code PREFIX} for users + an
 * {@code UPSTREAM_PREFIX} for groups; the {@link #cleanup()}
 * {@code @BeforeEach} deletes rows under both prefixes and clears
 * the {@link InMemoryAdapter#sentMessages} buffer. The
 * {@code audit_log} {@code trg_audit_log_no_update/no_delete}
 * triggers are temporarily disabled to allow row cleanup.
 *
 * @implNote Canonical thin-SQL handler exception per
 *     {@code docs/process/test-pyramid.md} §Shape B: Thin-SQL.
 */
@QuarkusTest
class ApproveGroupCommandHandlerTest {

    private static final String PREFIX = "m1-113-approve-";
    private static final String UPSTREAM_PREFIX = "m1-113-approve-grp-";
    private static final String ADAPTER = "inmemory";

    @Inject ApproveGroupCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject InMemoryAdapter inMemoryAdapter;

    @BeforeEach
    @AfterEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        inMemoryAdapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // No permanent guardian admin: adding one would contaminate the
            // global is_admin=TRUE/is_banned=FALSE count and break tests
            // that intentionally drive the deployment to "only one
            // remaining unbanned admin" to exercise the V5 trigger
            // (BanCommandHandlerTest.banOfOnlyAdminSurfacesLastAdminError).
            // Instead, disable trg_users_last_admin_delete for the cleanup
            // pass — test admin rows are deleted by PREFIX, and the trigger
            // is re-enabled in the finally block so the invariant always
            // holds outside this cleanup.
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
    void approveByNonAdminReturnsAdminOnly() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        seedUser( ADAPTER, actor, false, false);
        UUID groupId = seedGroup( ADAPTER, UPSTREAM_PREFIX + "a", "pending", null);
        inboundContext.setSenderContactId(actor);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/approve-group " + groupId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /approve-group must surface error.admin_only");
        assertEquals("pending", readApprovalStatus( groupId),
                "approval_status must remain pending on the admin-gate reject");
        assertEquals(0, countApproveAuditRows(groupId),
                "non-admin /approve-group must write no APPROVE_GROUP audit row");
    }

    // ----- (a') Non-admin + malformed id → admin_only, no token echo -------
    // M1-657: the admin gate must precede parseGroupId. A non-admin caller
    // supplying a non-UUID argument (here the copy-pasteable "/grant-admin")
    // must be stopped by error.admin_only and must NOT receive the
    // error.group_not_found reply, which interpolates the raw argument —
    // the r2 redteam finding on M1-656.

    @Test
    void approveByNonAdminMalformedIdReturnsAdminOnlyWithoutEcho() throws Exception {
        String actor = PREFIX + "nonAdmin-reflect";
        seedUser( ADAPTER, actor, false, false);
        inboundContext.setSenderContactId(actor);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/approve-group /grant-admin");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /approve-group must surface error.admin_only before parsing");
        assertFalse(reply.text().contains("grant-admin"),
                "the admin-gated reply must not reflect the attacker-supplied token");
    }

    // ----- (b) Unknown group_id → error.group_not_found --------------------

    @Test
    void approveUnknownGroupIdReturnsGroupNotFound() throws Exception {
        String actor = PREFIX + "unknown-actor";
        seedUser( ADAPTER, actor, true, false);
        UUID phantomId = UUID.randomUUID();
        inboundContext.setSenderContactId(actor);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/approve-group " + phantomId);

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_GROUP_NOT_FOUND), phantomId);
        assertEquals(expected, reply.text(),
                "unknown group_id must surface error.group_not_found");
    }

    // ----- (c) Pending → approved + group message + audit ------------------

    @Test
    void approvePendingGroupFlipsStatusSendsMessageWritesAudit() throws Exception {
        String actor = PREFIX + "pending-actor";
        UUID actorId = seedUser( ADAPTER, actor, true, false);
        String upstream = UPSTREAM_PREFIX + "pending";
        UUID groupId = seedGroup( ADAPTER, upstream, "pending", null);
        inboundContext.setSenderContactId(actor);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/approve-group " + groupId);

        String expectedReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_APPROVE_GROUP_SUCCESS), groupId);
        assertEquals(expectedReply, reply.text(), "success reply must interpolate the group id");

        assertEquals("approved", readApprovalStatus( groupId),
                "approval_status must flip from pending to approved");

        // Group notification sent to the target group's scope.
        List<OutboundMessage> sent = inMemoryAdapter.sentMessages();
        boolean foundGroupMessage = sent.stream().anyMatch(m ->
                m.scope() instanceof ScopeRef.Group g
                        && g.adapterGroupId().equals(upstream)
                        && m.text().equals(bundleLoader.get(BundleKeys.GROUP_APPROVED_MESSAGE)));
        assertTrue(foundGroupMessage,
                "exactly one group.approved_message must be sent to the target group's scope");

        // APPROVE_GROUP audit row exists with the actor + target columns
        // populated and previous_status='pending' in details_json.
        assertAuditRow(groupId, "APPROVE_GROUP", actorId, "pending");
    }

    // ----- (d) Rejected → approved -----------------------------------------

    @Test
    void approveRejectedGroupFlipsStatus() throws Exception {
        String actor = PREFIX + "rejected-actor";
        UUID actorId = seedUser( ADAPTER, actor, true, false);
        UUID groupId = seedGroup( ADAPTER,
                UPSTREAM_PREFIX + "rejected", "rejected", null);
        inboundContext.setSenderContactId(actor);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/approve-group " + groupId);

        String expectedReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_APPROVE_GROUP_SUCCESS), groupId);
        assertEquals(expectedReply, reply.text(),
                "rejected→approved must surface the same success reply as pending→approved");
        assertEquals("approved", readApprovalStatus( groupId),
                "approval_status must flip from rejected to approved");
        assertAuditRow(groupId, "APPROVE_GROUP", actorId, "rejected");
    }

    // ----- (e) Already approved → no-op ------------------------------------

    @Test
    void approveAlreadyApprovedGroupReturnsNoopNoAudit() throws Exception {
        String actor = PREFIX + "alreadyApproved-actor";
        seedUser( ADAPTER, actor, true, false);
        UUID groupId = seedGroup( ADAPTER,
                UPSTREAM_PREFIX + "alreadyApproved", "approved", null);
        inboundContext.setSenderContactId(actor);
        long auditBefore = countApproveAuditRows(groupId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/approve-group " + groupId);

        String expectedReply = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_APPROVE_GROUP_NOOP), groupId);
        assertEquals(expectedReply, reply.text(),
                "already-approved must surface the no-op reply");
        assertEquals("approved", readApprovalStatus( groupId),
                "approval_status must remain approved (no UPDATE landed)");
        assertEquals(auditBefore, countApproveAuditRows(groupId),
                "no-op path must write no APPROVE_GROUP audit row");
        assertFalse(inMemoryAdapter.sentMessages().stream().anyMatch(m ->
                m.text().equals(bundleLoader.get(BundleKeys.GROUP_APPROVED_MESSAGE))),
                "no-op path must NOT re-send the group approval notification");
    }

    private long countApproveAuditRows(UUID groupId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log "
                             + "WHERE action = 'APPROVE_GROUP' AND target_id = ?")) {
            ps.setString(1, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void assertAuditRow(UUID groupId, String action, UUID actorId,
                                String expectedPreviousStatus) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT actor_user_id, target_kind, target_id, details_json "
                             + "FROM audit_log WHERE action = ? AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, groupId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), action + " audit row must exist for groupId=" + groupId);
                assertEquals(actorId, (UUID) rs.getObject("actor_user_id"),
                        "actor_user_id must match the calling admin");
                assertEquals("group", rs.getString("target_kind"));
                assertEquals(groupId.toString(), rs.getString("target_id"));
                String detailsJson = rs.getString("details_json");
                assertNotNull(detailsJson);
                // PostgreSQL JSONB normalizes whitespace between keys and
                // values, so we match on the two tokens independently
                // rather than the concatenated literal.
                assertTrue(detailsJson.contains("\"previous_status\"")
                                && detailsJson.contains("\"" + expectedPreviousStatus + "\""),
                        "details_json must record the pre-update approval_status, got: " + detailsJson);
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

    private UUID seedGroup(String adapter, String upstreamGroupId, String approvalStatus,
                           UUID activatedBy) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, approval_status, activated_by) "
                             + "VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            ps.setString(3, approvalStatus);
            if (activatedBy == null) {
                ps.setNull(4, java.sql.Types.OTHER);
            } else {
                ps.setObject(4, activatedBy);
            }
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
