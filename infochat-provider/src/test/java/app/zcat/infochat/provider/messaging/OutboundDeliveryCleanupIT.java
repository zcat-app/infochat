package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-DB integration cover for {@link OutboundDelivery}'s permanent-failure
 * cleanup and cap-exhaustion paths — the effects that the plain-JUnit
 * {@link OutboundDeliveryTest} (recording doubles) cannot observe:
 *
 * <ul>
 *   <li><b>Bot-removed cleanup (item 5).</b> Crossing the per-(group)
 *       permanent-failure threshold actually sets {@code groups.removed_at}
 *       and removes the group from the digest scheduler's active-group
 *       predicate ({@code removed_at IS NULL AND approval_status='approved'
 *       AND digest_enabled}).</li>
 *   <li><b>Cap-exhaustion admin notification (item 3).</b> Two exhaustions
 *       on the same {@code (channel, error_class)} produce exactly one admin
 *       notification within the throttle window (the second is suppressed),
 *       via the real {@link ThrottledAdminNotifier}.</li>
 *   <li><b>User-left cleanup (item 6).</b> The membership soft-clear
 *       primitive sets {@code removed_at}, clears {@code is_group_admin} (V5
 *       trigger) when the departing member was group admin, and PRESERVES
 *       the member's group-scoped chat_session / chat_message /
 *       summary_anchor / subscription rows. (No in-scope send path produces a
 *       per-member permanent failure in v1 — {@code ScopeRef} is sealed to
 *       Dm/Group — so the cleanup primitive is exercised directly, per the
 *       acceptance's "a named test covers the admin case".)</li>
 * </ul>
 */
@QuarkusTest
class OutboundDeliveryCleanupIT {

    // Distinct fixed ids per scenario so the in-memory per-group counter in
    // the shared @ApplicationScoped OutboundDelivery never bleeds across tests.
    static final UUID ITEM5_GROUP = UUID.fromString("0b540001-0001-4000-8000-000000000001");
    static final UUID ITEM5_CONTROL = UUID.fromString("0b540002-0002-4000-8000-000000000002");
    static final UUID ITEM6_GROUP = UUID.fromString("0b540003-0003-4000-8000-000000000003");
    static final UUID ITEM6_USER = UUID.fromString("0b540004-0004-4000-8000-000000000004");
    static final UUID ITEM6_SOURCE = UUID.fromString("0b540005-0005-4000-8000-000000000005");

    static final String EXHAUST_CHANNEL = "ob-it-exhaust-chan";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject OutboundDelivery outboundDelivery;
    @Inject GroupMembershipRepository groupMembershipRepository;
    @Inject ThrottledAdminNotifier throttledAdminNotifier;

    @BeforeEach
    void cleanUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM source_subscription WHERE scope_id = ?", ITEM6_GROUP);
            exec(conn, "DELETE FROM chat_memory WHERE scope_id = ?", ITEM6_GROUP);
            exec(conn, "DELETE FROM chat_session WHERE scope_id = ?", ITEM6_GROUP);
            exec(conn, "DELETE FROM summary_anchor WHERE scope_id = ?", ITEM6_GROUP);
            exec(conn, "DELETE FROM source WHERE id = ?", ITEM6_SOURCE);
            exec(conn, "DELETE FROM group_membership WHERE group_id IN (?, ?, ?)",
                    ITEM5_GROUP, ITEM5_CONTROL, ITEM6_GROUP);
            exec(conn, "DELETE FROM groups WHERE id IN (?, ?, ?)",
                    ITEM5_GROUP, ITEM5_CONTROL, ITEM6_GROUP);
            exec(conn, "DELETE FROM users WHERE id = ?", ITEM6_USER);
            exec(conn, "DELETE FROM admin_notification_state WHERE notification_key LIKE ?",
                    EXHAUST_CHANNEL + "%");
        }
    }

    @Test
    void thresholdPermanentGroupFailuresSetRemovedAtAndExcludeFromScheduler() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertGroup(conn, ITEM5_GROUP, "ob-it-g5", "approved", true);
            insertGroup(conn, ITEM5_CONTROL, "ob-it-g5-control", "approved", true);
        }

        // Threshold is 3 (base/laptop profile). Drive three consecutive
        // PERMANENT group-send failures through the chokepoint.
        for (int i = 0; i < 3; i++) {
            FailingMessagingAdapter adapter =
                    FailingMessagingAdapter.alwaysFailing("ob-it-chan", FailureCategory.PERMANENT);
            assertNull(outboundDelivery.deliverToGroup(adapter, groupMessage(), ITEM5_GROUP));
        }

        // Effect 1: removed_at is set on the target group.
        assertNotNull(readRemovedAt(ITEM5_GROUP), "threshold crossing must set groups.removed_at");
        // Effect 2: the group is excluded from the digest scheduler's
        // active-group selection (removed_at IS NULL is the entire cancel
        // mechanism — DigestScheduler.queryActiveGroups filters on it).
        assertFalse(passesActiveGroupPredicate(ITEM5_GROUP),
                "the removed group must drop out of the active-group predicate");
        // The untouched control group is unaffected.
        assertTrue(passesActiveGroupPredicate(ITEM5_CONTROL),
                "an unrelated group must stay schedulable");
        assertNull(readRemovedAt(ITEM5_CONTROL));
    }

    @Test
    void capExhaustionNotifiesAdminExactlyOncePerChannelErrorClassWindow() {
        // Two cap exhaustions on the same (channel, error_class): the real
        // ThrottledAdminNotifier emits once and suppresses the second within
        // the throttle window.
        assertNull(outboundDelivery.deliver(
                FailingMessagingAdapter.alwaysFailing(EXHAUST_CHANNEL, FailureCategory.TRANSIENT),
                dmMessage()));
        assertNull(outboundDelivery.deliver(
                FailingMessagingAdapter.alwaysFailing(EXHAUST_CHANNEL, FailureCategory.TRANSIENT),
                dmMessage()));

        Optional<AdminNotificationRecord> state =
                throttledAdminNotifier.getState(EXHAUST_CHANNEL + "|TRANSIENT");
        assertTrue(state.isPresent(), "an admin notification must have been recorded");
        assertEquals(1, state.get().notificationCount(), "exactly one emission within the window");
        assertEquals(1, state.get().suppressedCount(), "the second exhaustion is suppressed");
    }

    @Test
    void userLeftAdminMemberSoftClearsAndPreservesScopedState() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertUser(conn, ITEM6_USER, "ob-it-member");
            insertGroup(conn, ITEM6_GROUP, "ob-it-g6", "approved", true);
            insertAdminMembership(conn, ITEM6_GROUP, ITEM6_USER);
            insertChatState(conn, ITEM6_USER, ITEM6_GROUP);
            insertSummaryAnchor(conn, ITEM6_USER, ITEM6_GROUP);
            insertSource(conn, ITEM6_SOURCE);
            insertGroupSubscription(conn, ITEM6_GROUP, ITEM6_SOURCE);
        }

        // The membership soft-clear primitive that a per-member permanent
        // delivery failure (dormant in v1) would invoke.
        groupMembershipRepository.markMemberRemoved(ITEM6_GROUP, ITEM6_USER);

        // Soft-clear + admin-clear (V5 trigger), in one statement.
        assertNotNull(readMembershipRemovedAt(ITEM6_GROUP, ITEM6_USER),
                "removed_at must be set on the membership row");
        assertFalse(readGroupAdminFlag(ITEM6_GROUP, ITEM6_USER),
                "a departing group admin's is_group_admin is cleared in the same transaction");
        // Preservation: none of the member's group-scoped state is purged.
        assertTrue(rowExists("SELECT 1 FROM chat_session WHERE user_id = ? AND scope_id = ?",
                ITEM6_USER, ITEM6_GROUP), "chat_session preserved");
        assertTrue(rowExists("SELECT 1 FROM chat_memory WHERE user_id = ? AND scope_id = ?",
                ITEM6_USER, ITEM6_GROUP), "chat_memory preserved");
        assertTrue(rowExists("SELECT 1 FROM summary_anchor WHERE user_id = ? AND scope_id = ?",
                ITEM6_USER, ITEM6_GROUP), "summary_anchor preserved");
        assertTrue(rowExists("SELECT 1 FROM source_subscription WHERE scope_id = ? AND source_id = ?",
                ITEM6_GROUP, ITEM6_SOURCE), "subscription preserved");
        // The membership row itself is preserved (soft-clear, not deleted).
        assertTrue(rowExists("SELECT 1 FROM group_membership WHERE group_id = ? AND user_id = ?",
                ITEM6_GROUP, ITEM6_USER), "membership row preserved (soft-clear, not deleted)");
    }

    // ----- seeding helpers -------------------------------------------------

    private static OutboundMessage groupMessage() {
        return new OutboundMessage(
                new ScopeRef.Group("ob-it-upstream"), "digest", Instant.now(),
                UUID.randomUUID().toString());
    }

    private static OutboundMessage dmMessage() {
        return new OutboundMessage(
                new ScopeRef.Dm("ob-it-contact"), "reply", Instant.now(),
                UUID.randomUUID().toString());
    }

    private static void insertGroup(Connection conn, UUID id, String upstream,
                                    String approvalStatus, boolean digestEnabled) throws SQLException {
        exec(conn,
                "INSERT INTO groups (id, adapter, upstream_group_id, approval_status, digest_enabled)"
                        + " VALUES (?, 'inmemory', ?, ?, ?)",
                id, upstream, approvalStatus, digestEnabled);
    }

    private static void insertUser(Connection conn, UUID id, String contactId) throws SQLException {
        exec(conn,
                "INSERT INTO users (id, adapter, contact_id, registration_state)"
                        + " VALUES (?, 'inmemory', ?, 'vouched')",
                id, contactId);
    }

    private static void insertAdminMembership(Connection conn, UUID groupId, UUID userId)
            throws SQLException {
        exec(conn,
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                        + " VALUES (?, ?, TRUE)",
                groupId, userId);
    }

    private static void insertChatState(Connection conn, UUID userId, UUID scopeId) throws SQLException {
        exec(conn,
                "INSERT INTO chat_session (user_id, scope_kind, scope_id)"
                        + " VALUES (?, 'group', ?)",
                userId, scopeId);
        exec(conn,
                "INSERT INTO chat_memory (user_id, scope_kind, scope_id, summary, keywords)"
                        + " VALUES (?, 'group', ?, 'prior summary', '{}'::text[])",
                userId, scopeId);
    }

    private static void insertSummaryAnchor(Connection conn, UUID userId, UUID scopeId)
            throws SQLException {
        exec(conn,
                "INSERT INTO summary_anchor"
                        + " (user_id, scope_kind, scope_id, command_kind, command_name, arg_hash, post_uids)"
                        + " VALUES (?, 'group', ?, 'personal', '/summary', 'arghash', '{}'::text[])",
                userId, scopeId);
    }

    private static void insertSource(Connection conn, UUID id) throws SQLException {
        exec(conn,
                "INSERT INTO source (id, kind, identifier, display_name, category)"
                        + " VALUES (?, 'rss', 'http://example.com/ob-it-feed', 'OB IT Source', 'news')",
                id);
    }

    private static void insertGroupSubscription(Connection conn, UUID groupId, UUID sourceId)
            throws SQLException {
        exec(conn,
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id)"
                        + " VALUES ('group', ?, ?)",
                groupId, sourceId);
    }

    private static void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    // ----- read helpers ----------------------------------------------------

    private java.sql.Timestamp readRemovedAt(UUID groupId) throws SQLException {
        return readTimestamp("SELECT removed_at FROM groups WHERE id = ?", groupId);
    }

    private java.sql.Timestamp readMembershipRemovedAt(UUID groupId, UUID userId) throws SQLException {
        return readTimestamp(
                "SELECT removed_at FROM group_membership WHERE group_id = ? AND user_id = ?",
                groupId, userId);
    }

    private java.sql.Timestamp readTimestamp(String sql, Object... params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getTimestamp(1);
            }
        }
    }

    private boolean readGroupAdminFlag(UUID groupId, UUID userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_group_admin FROM group_membership"
                             + " WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private boolean passesActiveGroupPredicate(UUID groupId) throws SQLException {
        // Mirrors DigestScheduler.queryActiveGroups' WHERE clause.
        return rowExists(
                "SELECT 1 FROM groups WHERE id = ?"
                        + " AND removed_at IS NULL AND approval_status = 'approved' AND digest_enabled",
                groupId);
    }

    private boolean rowExists(String sql, Object... params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
