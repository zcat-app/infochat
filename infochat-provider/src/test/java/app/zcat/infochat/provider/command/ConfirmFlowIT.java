package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.impl.inmemory.InMemoryAdapter;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for the M1-051 confirm gate against the
 * DevServices Postgres container + the in-memory adapter wiring.
 * Mirrors {@link AddSourceIT} / {@link SummaryIT}: each test drives
 * the dispatch path through {@link InMemoryAdapter#deliverDm} and
 * asserts on {@link InMemoryAdapter#sentMessages()} plus DB state.
 *
 * <p>Three scenarios pin M1-051 acceptance items 23 + 24:</p>
 * <ul>
 *   <li>{@code banPromptThenConfirmExecutesBanEndToEnd} — /ban →
 *       prompt outbound; /ban confirm → ban-success outbound, target
 *       is_banned=TRUE, one BAN audit row;</li>
 *   <li>{@code inviteCreateOpenPromptThenConfirmExecutesCreateEndToEnd} —
 *       /invite create --open → prompt outbound; /invite create --open
 *       confirm → create-success outbound, one PENDING OPEN_ADAPTER
 *       invite_code row appears;</li>
 *   <li>{@code nonMatchingInputAfterBanPromptCancelsPendingAndDispatchesNewCommand} —
 *       /ban → prompt; /help → cancellation acknowledgement + /help
 *       dispatch reply; target is_banned STILL FALSE; pending peek empty.</li>
 * </ul>
 */
@QuarkusTest
class ConfirmFlowIT {

    private static final String PREFIX = "m1-051-confirm-";
    private static final String ADAPTER = "inmemory";

    @Inject InMemoryAdapter adapter;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject ConfirmStateService confirmStateService;

    @BeforeEach
    void cleanup() throws Exception {
        adapter.reset();
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the per-test DELETE on admin rows.
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-051-confirm-permanent");
            exec(conn,
                    "DELETE FROM invite_code WHERE expected_contact_id LIKE ? "
                            + "OR created_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                    PREFIX + "%", PREFIX + "%");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_contact_id LIKE ? "
                                + "OR actor_user_id IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL WHERE contact_id LIKE ?",
                        PREFIX + "%");
                exec(conn,
                        "UPDATE users SET banned_by = NULL "
                                + "WHERE banned_by IN (SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
                exec(conn,
                        "DELETE FROM users WHERE contact_id LIKE ?",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            // Clear any guardian-created invite rows left from a prior
            // run (the cap-met tests in InviteCommandHandlerTest seed
            // many invite_code rows under the guardian admin).
            exec(conn,
                    "DELETE FROM invite_code "
                            + "WHERE created_by IN (SELECT id FROM users WHERE contact_id LIKE 'guardian-m1-051-confirm-%')");
        }
    }

    @Test
    void banPromptThenConfirmExecutesBanEndToEnd() throws Exception {
        String admin = PREFIX + "ban-admin";
        String target = PREFIX + "ban-target";
        seedUser(admin, /* isAdmin */ true, false, "vouched");
        seedUser(target, false, false, "invited");

        // First call — /ban target.
        adapter.deliverDm(admin, "/ban " + target);
        List<OutboundMessage> outboundsAfterPrompt = adapter.sentMessages();
        assertEquals(1, outboundsAfterPrompt.size(),
                "first /ban call must produce exactly one outbound (the prompt)");
        assertTrue(outboundsAfterPrompt.get(0).text().contains("/ban confirm"),
                "first /ban outbound must be the prompt naming /ban confirm — got: "
                        + outboundsAfterPrompt.get(0).text());
        assertFalse(isBanned(target),
                "target users.is_banned must STILL be FALSE after the prompt");
        // Spec §Authorization step 8 audit-on-intent: the BAN_INTENT
        // row lands after the admin-gate pass and before the prompt
        // is sent. The completion BAN row does NOT exist yet.
        assertEquals(1L, countAuditRows("BAN_INTENT", target),
                "first /ban writes exactly one BAN_INTENT audit row");
        assertEquals(0L, countBanAuditRows(target),
                "first /ban must NOT write a BAN (completion) audit row");

        // Confirm call — /ban confirm.
        adapter.deliverDm(admin, "/ban confirm");
        List<OutboundMessage> outboundsAfterConfirm = adapter.sentMessages();
        assertEquals(2, outboundsAfterConfirm.size(),
                "confirm call must produce exactly one MORE outbound (the ban-success reply)");
        assertTrue(outboundsAfterConfirm.get(1).text().contains("Banned"),
                "confirm outbound must be the M1-044c ban-success reply — got: "
                        + outboundsAfterConfirm.get(1).text());
        assertTrue(isBanned(target),
                "target users.is_banned must be TRUE after the confirm");
        assertEquals(1L, countBanAuditRows(target),
                "exactly one BAN audit_log row must exist for the target");
        assertEquals(1L, countAuditRows("BAN_INTENT", target),
                "the BAN_INTENT intent row from the first call must remain after confirm");
    }

    @Test
    void inviteCreateOpenPromptThenConfirmExecutesCreateEndToEnd() throws Exception {
        String admin = PREFIX + "invOpen-admin";
        UUID adminId = seedUser(admin, /* isAdmin */ true, false, "vouched");
        long invitesBefore = countInvitesByCreator(adminId);

        // First call — /invite create --adapter inmemory --open.
        adapter.deliverDm(admin, "/invite create --adapter " + ADAPTER + " --open");
        List<OutboundMessage> outboundsAfterPrompt = adapter.sentMessages();
        assertEquals(1, outboundsAfterPrompt.size(),
                "first /invite create --open call must produce exactly one outbound (the prompt)");
        assertTrue(outboundsAfterPrompt.get(0).text().contains("/invite create --open confirm"),
                "first /invite create --open outbound must be the prompt naming the confirm form — got: "
                        + outboundsAfterPrompt.get(0).text());
        assertEquals(invitesBefore, countInvitesByCreator(adminId),
                "no invite_code row may be written before the confirm arrives");

        // Confirm call — /invite create --open confirm.
        adapter.deliverDm(admin, "/invite create --open confirm");
        List<OutboundMessage> outboundsAfterConfirm = adapter.sentMessages();
        assertEquals(2, outboundsAfterConfirm.size(),
                "confirm call must produce exactly one MORE outbound (the create-success reply)");
        assertTrue(outboundsAfterConfirm.get(1).text().contains("Invite code"),
                "confirm outbound must be the M1-044c create-success reply — got: "
                        + outboundsAfterConfirm.get(1).text());
        assertEquals(invitesBefore + 1, countInvitesByCreator(adminId),
                "exactly one new invite_code row must exist after the confirm");
    }

    @Test
    void nonMatchingInputAfterBanPromptCancelsPendingAndDispatchesNewCommand() throws Exception {
        String admin = PREFIX + "cancel-admin";
        String target = PREFIX + "cancel-target";
        UUID adminId = seedUser(admin, /* isAdmin */ true, false, "vouched");
        seedUser(target, false, false, "invited");

        // First call — /ban target → prompt.
        adapter.deliverDm(admin, "/ban " + target);
        assertEquals(1, adapter.sentMessages().size(),
                "first /ban call must produce exactly one outbound (the prompt)");

        // Second call — /help → cancellation acknowledgement + /help reply.
        adapter.deliverDm(admin, "/help");
        List<OutboundMessage> outbounds = adapter.sentMessages();
        // Two NEW outbounds (in addition to the prompt): cancellation +
        // /help dispatch reply. The acceptance also permits one
        // combined outbound — assert via "both literals appear in
        // order across the captured outbound queue".
        String afterPrompt =
                outbounds.subList(1, outbounds.size()).stream()
                        .map(OutboundMessage::text)
                        .reduce("", (a, b) -> a + "\n---\n" + b);
        int cancellationAt = afterPrompt.indexOf("cancelled");
        int helpAt = afterPrompt.indexOf("/help");
        assertTrue(cancellationAt >= 0,
                "cancellation acknowledgement literal must appear in the post-prompt outbounds — got: "
                        + afterPrompt);
        assertTrue(helpAt >= 0,
                "/help dispatch reply must appear in the post-prompt outbounds — got: "
                        + afterPrompt);
        assertTrue(cancellationAt < helpAt,
                "cancellation acknowledgement MUST appear BEFORE the /help dispatch reply — got: "
                        + afterPrompt);

        // The ban did NOT happen.
        assertFalse(isBanned(target),
                "target users.is_banned must STILL be FALSE after the cancellation");
        assertEquals(0L, countBanAuditRows(target),
                "no BAN (completion) audit row may exist for a cancelled /ban");

        // ConfirmStateService.peek for the admin must return empty (the
        // sweep drained the pending entry).
        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(adminId, new ScopeRef.Dm(admin));
        assertFalse(peeked.isPresent(),
                "ConfirmStateService.peek must be empty after the cancellation");

        // Spec §Authorization step 8 audit-on-intent: even though the
        // ban was abandoned, the BAN_INTENT row written on the first
        // call persists in audit_log — the trail an operator needs to
        // notice probe-and-abandon patterns. This is the M1-051 round-2
        // remediation of the redteam AUDIT-EVASION finding: a confirm-
        // gated destructive command that never confirms must still
        // leave an audit trail.
        assertEquals(1L, countAuditRows("BAN_INTENT", target),
                "abandoned /ban must STILL leave one BAN_INTENT audit row");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin, boolean isBanned,
                          String registrationState) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at, probation_until) "
                             + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END, ?) "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setString(5, registrationState);
            ps.setBoolean(6, isBanned);
            // M1-045: bootstrap admins are past-probation by construction
            // (they skip the AutoRegisterService path that seeds
            // probation_until). A seeded admin with a 24h probation window
            // would now be blocked at step 5 for /ban + /invite create
            // --open, masking the dispatch-path assertions this file
            // exists to prove. Non-admin targets keep the pre-M1-045
            // 24h seed since they are recipients of /ban + /invite, never
            // actors.
            if (isAdmin) {
                ps.setNull(7, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(7, OffsetDateTime.now().plusHours(24));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private boolean isBanned(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_banned FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getBoolean("is_banned");
            }
        }
    }

    private long countBanAuditRows(String targetContactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = 'BAN' AND target_contact_id = ?")) {
            ps.setString(1, targetContactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditRows(String action, String targetContactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? AND target_contact_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetContactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countInvitesByCreator(UUID createdBy) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM invite_code WHERE created_by = ?")) {
            ps.setObject(1, createdBy);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
