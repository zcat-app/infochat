package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.MembershipEvent;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MembershipEventHandlerTest {

    private static final String TEST_ADAPTER = "inmemory";
    private static final String TEST_UPSTREAM_GROUP_ID = "meh-test-" + UUID.randomUUID();

    @Inject @SeedDataSource DataSource dataSource;
    @Inject GroupRepository groupRepository;
    @Inject GroupMembershipRepository membershipRepository;
    @Inject GroupJoinRepository joinRepository;
    @Inject MembershipEventHandler handler;

    private UUID groupId;
    private UUID userId;
    private String contactId;
    private UUID botAdminId;

    @BeforeEach
    void setup() throws Exception {
        userId = UUID.randomUUID();
        contactId = "meh-contact-" + userId;

        try (Connection conn = dataSource.getConnection()) {
            botAdminId = resolveBotAdmin(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM group_membership WHERE group_id IN "
                            + "(SELECT id FROM groups WHERE upstream_group_id = ?)")) {
                ps.setString(1, TEST_UPSTREAM_GROUP_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM groups WHERE upstream_group_id = ?")) {
                ps.setString(1, TEST_UPSTREAM_GROUP_ID);
                ps.executeUpdate();
            }
            cleanUser(conn, userId);
            seedUser(conn, userId, contactId);
        }
        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(
                TEST_ADAPTER, TEST_UPSTREAM_GROUP_ID);
        groupRepository.clearRemoved(groupId);
    }

    @Test
    void userLeft_marksGroupMemberRemoved() throws Exception {
        membershipRepository.addMember(groupId, userId);

        handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                TEST_ADAPTER);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at, is_group_admin FROM group_membership "
                             + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "membership row should exist");
                assertNotNull(rs.getTimestamp("removed_at"),
                        "removed_at should be set");
                assertFalse(rs.getBoolean("is_group_admin"),
                        "is_group_admin should be false");
            }
        }
        assertTrue(hasAuditEntry(groupId, userId.toString(), AuditAction.MEMBER_LEFT),
                "MEMBER_LEFT audit row should exist");
    }

    @Test
    void userLeft_auditRecordsWasGroupAdmin() throws Exception {
        membershipRepository.addMember(groupId, userId);
        promoteToAdmin(groupId, userId);

        handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                TEST_ADAPTER);

        String details = auditDetails(groupId, userId.toString(), AuditAction.MEMBER_LEFT);
        assertNotNull(details, "audit details should exist");
        // PostgreSQL jsonb normalizes whitespace (space after colon)
        assertTrue(details.contains("\"was_group_admin\": true"),
                "audit should record was_group_admin=true");
    }

    @Test
    void botRemoved_marksGroupRemoved() throws Exception {
        handler.handle(
                new MembershipEvent.BotRemoved(TEST_UPSTREAM_GROUP_ID),
                TEST_ADAPTER);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM groups WHERE id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "group row should exist");
                assertNotNull(rs.getTimestamp("removed_at"),
                        "removed_at should be set");
            }
        }
        assertTrue(hasAuditEntry(groupId, groupId.toString(), AuditAction.BOT_REMOVED),
                "BOT_REMOVED audit row should exist");
    }

    @Test
    void botRemoved_freesJoinOnlyAutoJoinSlot() throws Exception {
        // M1-525 item 2: a join-only auto-joined group — recorded in
        // auto_joined_group but with NO groups row (never @mentioned, so it
        // never entered the approval machine) — must have its slot freed on
        // BotRemoved even though resolveGroup returns null.
        String joinOnlyUpstream = "meh-joinonly-" + UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO auto_joined_group (adapter, upstream_group_id, inviter_user_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, TEST_ADAPTER);
            ps.setString(2, joinOnlyUpstream);
            ps.setObject(3, userId);
            ps.executeUpdate();
        }

        try {
            handler.handle(new MembershipEvent.BotRemoved(joinOnlyUpstream), TEST_ADAPTER);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT removed_at FROM auto_joined_group "
                                 + "WHERE adapter = ? AND upstream_group_id = ?")) {
                ps.setString(1, TEST_ADAPTER);
                ps.setString(2, joinOnlyUpstream);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "the join-only auto_joined_group row should exist");
                    assertNotNull(rs.getTimestamp("removed_at"),
                            "BotRemoved must free the join-only slot (set removed_at)");
                }
            }
        } finally {
            // The join-only row uses a distinct upstream id the @BeforeEach
            // cleanup does not cover; delete it so its inviter FK does not
            // block this test's user teardown on the next run.
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM auto_joined_group WHERE adapter = ? AND upstream_group_id = ?")) {
                ps.setString(1, TEST_ADAPTER);
                ps.setString(2, joinOnlyUpstream);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void userLeft_auditWriteFailureRollsBackMutation() throws Exception {
        membershipRepository.addMember(groupId, userId);
        promoteToAdmin(groupId, userId);

        MembershipEventHandler failingHandler = new MembershipEventHandler(
                dataSource, membershipRepository, groupRepository,
                new FailingAuditLogWriter());

        assertThrows(IllegalStateException.class, () -> failingHandler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                TEST_ADAPTER));

        // The mutation must roll back with the failed audit write: the
        // member is still active and the was_group_admin flag survives.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at, is_group_admin FROM group_membership "
                             + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "membership row should exist");
                assertNull(rs.getTimestamp("removed_at"),
                        "removed_at should be rolled back to NULL");
                assertTrue(rs.getBoolean("is_group_admin"),
                        "is_group_admin should survive the rollback");
            }
        }
        assertFalse(hasAuditEntry(groupId, userId.toString(), AuditAction.MEMBER_LEFT),
                "no MEMBER_LEFT audit row should remain after rollback");
    }

    @Test
    void botRemoved_auditWriteFailureRollsBackMutation() throws Exception {
        MembershipEventHandler failingHandler = new MembershipEventHandler(
                dataSource, membershipRepository, groupRepository,
                new FailingAuditLogWriter());
        // handleBotRemoved frees the auto_joined_group slot before the audit tx
        // (M1-525); the hand-constructed handler has a null field-injected
        // joinRepository, so wire the real one in. The seeded group has no
        // auto_joined_group row, so the free is a 0-row no-op and the rollback
        // assertions below are unaffected.
        failingHandler.joinRepository = joinRepository;

        assertThrows(IllegalStateException.class, () -> failingHandler.handle(
                new MembershipEvent.BotRemoved(TEST_UPSTREAM_GROUP_ID),
                TEST_ADAPTER));

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM groups WHERE id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "group row should exist");
                assertNull(rs.getTimestamp("removed_at"),
                        "removed_at should be rolled back to NULL");
            }
        }
        assertFalse(hasAuditEntry(groupId, groupId.toString(), AuditAction.BOT_REMOVED),
                "no BOT_REMOVED audit row should remain after rollback");
    }

    @Test
    void userLeft_auditWriteFailureExceptionIsSanitized() throws Exception {
        membershipRepository.addMember(groupId, userId);

        MembershipEventHandler failingHandler = new MembershipEventHandler(
                dataSource, membershipRepository, groupRepository,
                new FailingAuditLogWriter());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> failingHandler.handle(
                        new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                        TEST_ADAPTER));

        // SafeLog convention: the SQLException — whose message mimics a
        // Postgres DETAIL line echoing the unredacted audit row — must
        // not travel as cause or message text; only its class name may.
        assertNull(thrown.getCause(),
                "sanitized failure must not carry the SQLException as cause");
        String message = thrown.getMessage();
        assertNotNull(message, "failure message should name the context");
        assertTrue(message.contains(SQLException.class.getName()),
                "failure should preserve the exception class name");
        assertFalse(message.contains("simulated audit-write failure"),
                "SQLException message text must not leak");
        assertFalse(message.contains(contactId),
                "contact id must not leak into the exception");
    }

    @Test
    void userLeft_unknownGroup_doesNotThrow() {
        handler.handle(
                new MembershipEvent.UserLeft("nonexistent-group", contactId),
                TEST_ADAPTER);
    }

    @Test
    void userLeft_unknownUser_doesNotThrow() {
        handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, "nonexistent-contact"),
                TEST_ADAPTER);
    }

    @Test
    void userLeft_concurrentPromoteIsSerializedIntoAudit() throws Exception {
        membershipRepository.addMember(groupId, userId);

        // Worker that runs the leave while this test thread holds the
        // membership row lock through an uncommitted promote. The
        // handler's FOR UPDATE read must block until the promote
        // commits, so the audited was_group_admin reflects the
        // committed promote — on the old plain SELECT the read would
        // not block and would audit the stale pre-promote value.
        Thread leaver = new Thread(() -> handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                TEST_ADAPTER));
        try (Connection promoteConn = dataSource.getConnection()) {
            promoteConn.setAutoCommit(false);
            // try/finally so a failed assertion or poll timeout cannot
            // leave the row lock held (the leaver would block forever).
            try {
                try (PreparedStatement ps = promoteConn.prepareStatement(
                        "UPDATE group_membership SET is_group_admin = true "
                                + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL")) {
                    ps.setObject(1, groupId);
                    ps.setObject(2, userId);
                    assertEquals(1, ps.executeUpdate(),
                            "promote should hit the seeded membership row");
                }
                leaver.start();
                // Wait until the leaver's FOR UPDATE is actually blocked on
                // the promote's row lock before committing, so the lock
                // conflict provably occurred (a commit that races ahead of
                // the leaver's read would not discriminate old vs new code).
                awaitUngrantedLock();
            } finally {
                promoteConn.commit();
            }
        }
        leaver.join(30_000);
        assertFalse(leaver.isAlive(),
                "leave transaction should complete once the promote commits");

        String details = auditDetails(groupId, userId.toString(), AuditAction.MEMBER_LEFT);
        assertNotNull(details, "audit details should exist");
        // PostgreSQL jsonb normalizes whitespace (space after colon)
        assertTrue(details.contains("\"was_group_admin\": true"),
                "audited value must reflect the concurrently committed promote");
    }

    @Test
    void userLeft_repeatedLeaveEventsMintBoundedAuditRows() throws Exception {
        membershipRepository.addMember(groupId, userId);

        handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                TEST_ADAPTER);
        assertEquals(1, countAuditEntries(groupId, userId.toString(), AuditAction.MEMBER_LEFT),
                "one genuine leave should mint exactly one audit row");

        // Leave events are attacker-repeatable: repeats for an
        // already-removed membership are verified no-ops and must not
        // mint one audit row each.
        for (int i = 0; i < 3; i++) {
            handler.handle(
                    new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                    TEST_ADAPTER);
        }
        assertEquals(1, countAuditEntries(groupId, userId.toString(), AuditAction.MEMBER_LEFT),
                "repeated leave events must not mint additional audit rows");
        assertMemberRemoved();

        // Re-activate the membership row directly (simulating the
        // rate-capped @mention re-registration path) and leave again: a
        // genuine active->removed transition must still be audited — the
        // bound suppresses repeats, not real transitions.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE group_membership SET removed_at = NULL "
                             + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            assertEquals(1, ps.executeUpdate(), "re-activation should hit the row");
        }
        handler.handle(
                new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                TEST_ADAPTER);
        assertEquals(2, countAuditEntries(groupId, userId.toString(), AuditAction.MEMBER_LEFT),
                "a genuine re-leave after re-activation should mint a second audit row");
        assertMemberRemoved();
    }

    @Test
    void userLeft_transactionFailureEmitsSafeLogOperatorSignal() throws Exception {
        membershipRepository.addMember(groupId, userId);

        MembershipEventHandler failingHandler = new MembershipEventHandler(
                dataSource, membershipRepository, groupRepository,
                new FailingAuditLogWriter());

        // Quarkus backs SLF4J with jboss-logmanager, whose loggers live
        // in LogContext.getLogContext() — NOT the stock java.util.logging
        // hierarchy when the JVM's LogManager initialized before
        // jboss-logmanager could install itself (the surefire ordering).
        // Attaching to the LogContext logger captures the SafeLog output
        // in both orderings; a plain j.u.l. getLogger would capture
        // nothing here.
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    captured.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger category = org.jboss.logmanager.LogContext.getLogContext()
                .getLogger(MembershipEventHandler.class.getName());
        category.addHandler(capture);
        try {
            assertThrows(IllegalStateException.class, () -> failingHandler.handle(
                    new MembershipEvent.UserLeft(TEST_UPSTREAM_GROUP_ID, contactId),
                    TEST_ADAPTER));
        } finally {
            category.removeHandler(capture);
        }

        assertEquals(1, captured.size(),
                "exactly one ERROR operator signal should be emitted");
        String signal = captured.get(0);
        assertTrue(signal.contains(groupId.toString()),
                "operator signal should carry the group UUID");
        assertTrue(signal.contains(userId.toString()),
                "operator signal should carry the user UUID");
        assertTrue(signal.contains(SQLException.class.getName()),
                "operator signal should carry the exception class name");
        assertFalse(signal.contains(contactId),
                "contact id must not leak into the operator signal");
        assertFalse(signal.contains("simulated audit-write failure"),
                "SQLException message text must not leak into the operator signal");
    }

    @Test
    void userLeft_unknownGroupWarnRedactsAdapterGroupId() {
        String secretGroupId = "meh-secret-upstream-" + UUID.randomUUID();

        List<LogRecord> captured = captureWarnRecords(() -> handler.handle(
                new MembershipEvent.UserLeft(secretGroupId, contactId),
                TEST_ADAPTER));

        assertEquals(1, captured.size(), "exactly one unknown-group WARN expected");
        String text = captured.get(0).getMessage() + " "
                + Arrays.toString(captured.get(0).getParameters());
        assertFalse(text.contains(secretGroupId),
                "the raw adapterGroupId must not reach the WARN line");
        assertTrue(text.contains(ContactIds.redact(secretGroupId)),
                "the redacted form must reach the WARN line");
    }

    @Test
    void botRemoved_unknownGroupWarnRedactsAdapterGroupId() {
        String secretGroupId = "meh-secret-upstream-" + UUID.randomUUID();

        List<LogRecord> captured = captureWarnRecords(() -> handler.handle(
                new MembershipEvent.BotRemoved(secretGroupId),
                TEST_ADAPTER));

        assertEquals(1, captured.size(), "exactly one unknown-group WARN expected");
        String text = captured.get(0).getMessage() + " "
                + Arrays.toString(captured.get(0).getParameters());
        assertFalse(text.contains(secretGroupId),
                "the raw adapterGroupId must not reach the WARN line");
        assertTrue(text.contains(ContactIds.redact(secretGroupId)),
                "the redacted form must reach the WARN line");
    }

    // Captures WARNING-and-above records on the handler's category via
    // the jboss-logmanager LogContext (see the ordering note on the
    // SafeLog capture in userLeft_transactionFailureEmitsSafeLogOperatorSignal).
    private List<LogRecord> captureWarnRecords(Runnable action) {
        List<LogRecord> captured = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // Attach to BOTH the stock JUL hierarchy and the LogContext one —
        // which hierarchy is live depends on whether jboss-logmanager won
        // the LogManager slot in this JVM; the identity check prevents
        // double capture when they are the same logger object.
        java.util.logging.Logger jul = java.util.logging.Logger
                .getLogger(MembershipEventHandler.class.getName());
        java.util.logging.Logger ctx = org.jboss.logmanager.LogContext.getLogContext()
                .getLogger(MembershipEventHandler.class.getName());
        jul.addHandler(capture);
        if (ctx != jul) {
            ctx.addHandler(capture);
        }
        try {
            action.run();
        } finally {
            jul.removeHandler(capture);
            if (ctx != jul) {
                ctx.removeHandler(capture);
            }
        }
        return captured;
    }

    // Polls pg_locks until some backend is waiting on an ungranted lock
    // — the observable signature of the leaver's FOR UPDATE blocking on
    // the uncommitted promote. Suite tests run sequentially, so no other
    // backend competes for locks while this polls.
    private void awaitUngrantedLock() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM pg_locks WHERE NOT granted")) {
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError(
                "leaver's FOR UPDATE never blocked on the promote's row lock");
    }

    private void assertMemberRemoved() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM group_membership "
                             + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "membership row should exist");
                assertNotNull(rs.getTimestamp("removed_at"),
                        "membership state should converge to removed");
            }
        }
    }

    private int countAuditEntries(UUID scopeId, String targetId, AuditAction action) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log "
                             + "WHERE scope_id = ? AND target_id = ? AND action = ?")) {
            ps.setObject(1, scopeId);
            ps.setString(2, targetId);
            ps.setString(3, action.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasAuditEntry(UUID scopeId, String targetId, AuditAction action) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM audit_log "
                             + "WHERE scope_id = ? AND target_id = ? AND action = ?")) {
            ps.setObject(1, scopeId);
            ps.setString(2, targetId);
            ps.setString(3, action.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String auditDetails(UUID scopeId, String targetId, AuditAction action) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT details_json FROM audit_log "
                             + "WHERE scope_id = ? AND target_id = ? AND action = ?")) {
            ps.setObject(1, scopeId);
            ps.setString(2, targetId);
            ps.setString(3, action.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("details_json") : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolve any live bot admin to act as the fixture's actor. The V62
     * routine behind {@code promoteToAdmin} gates on
     * {@code infochat.actor_id} naming an {@code is_admin} row, so this
     * fixture needs one — but it deliberately reuses an existing admin
     * rather than seeding another: an extra unbanned admin row would be a
     * permanent fixture leak that breaks the last-admin-protection tests,
     * whose premise is that exactly one such row remains. The
     * {@code AdminBootstrap} startup bean seeds one for the {@code %test}
     * inmemory contact at every boot.
     */
    private UUID resolveBotAdmin(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM users WHERE is_admin = TRUE AND is_banned = FALSE LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(),
                    "fixture precondition: the %test cluster must carry at least one "
                          + "live bot admin (AdminBootstrap seeds one at boot)");
            return rs.getObject(1, UUID.class);
        }
    }

    /**
     * Drive {@link GroupMembershipRepository#promoteToAdmin} the way
     * production drives a routine-mediated write: one transaction, actor
     * GUC bound first. {@code set_config(..., true)} is transaction-local,
     * so the bind and the call must share a connection.
     */
    private void promoteToAdmin(UUID gId, UUID uId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT set_config('infochat.actor_id', ?, true)")) {
                ps.setString(1, botAdminId.toString());
                ps.execute();
            }
            membershipRepository.promoteToAdmin(conn, gId, uId);
            conn.commit();
        }
    }

    private void seedUser(Connection conn, UUID uid, String cid) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, registration_state, is_banned) "
                        + "VALUES (?, ?, ?, 'vouched', false) "
                        + "ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, uid);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, cid);
            ps.executeUpdate();
        }
    }

    private void cleanUser(Connection conn, UUID uid) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM users WHERE id = ?")) {
            ps.setObject(1, uid);
            ps.executeUpdate();
        }
    }
}
