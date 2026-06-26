package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
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
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link QuarantineCommandHandler} against the
 * DevServices Postgres container. One {@code @Test} per quarantine-command
 * acceptance scenario in M1-081b.
 */
@QuarkusTest
class QuarantineCommandHandlerTest {

    private static final String PREFIX = "m1-081b-qch-";
    private static final String ADAPTER = "inmemory";
    private static final OffsetDateTime FETCHED_AT =
            OffsetDateTime.parse("2026-05-15T00:00:00Z");

    @Inject QuarantineCommandHandler handler;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject RateCapBucket rateCapBucket;
    @Inject ConfirmStateService confirmStateService;

    private UUID sourceId;

    @AfterEach
    void teardown() throws Exception {
        // Eagerly clean up admin rows so they don't leak into other
        // test classes (notably BanCommandHandlerTest's last-admin trigger test).
        cleanup();
    }

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Clean quarantine rows first (FK to post)
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", PREFIX + "%");

            // Disable audit_log append-only triggers so we can delete
            // audit rows that the stored procedures wrote (their
            // actor_user_id FK blocks the users DELETE below).
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            exec(conn, "ALTER TABLE users DISABLE TRIGGER trg_users_last_admin_update");
            exec(conn, "ALTER TABLE users DISABLE TRIGGER trg_users_last_admin_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_id LIKE ? "
                                + "OR actor_user_id IN "
                                + "(SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%", PREFIX + "%");
                exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_update");
                exec(conn, "ALTER TABLE users ENABLE TRIGGER trg_users_last_admin_delete");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }

            exec(conn, "DELETE FROM post WHERE uid LIKE ?", PREFIX + "%");
            // Shared test source (idempotent upsert)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO source (kind, identifier, display_name, category) "
                            + "VALUES ('rss', ?, 'Test Source', 'news') "
                            + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = 'Test Source' "
                            + "RETURNING id")) {
                ps.setString(1, PREFIX + "source");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    sourceId = (UUID) rs.getObject("id");
                }
            }
        }
    }

    // ---- /quarantine list ----

    @Test
    void listDefault_showsPendingRows() throws Exception {
        String admin = PREFIX + "list-admin";
        seedUser(admin, true, false, "vouched");

        UUID qId = seedQuarantineRow("PENDING", PREFIX + "list-p1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine list");

        assertTrue(reply.text().contains(qId.toString()),
                "list reply must include the quarantine id");
        assertTrue(reply.text().contains(PREFIX + "list-p1"),
                "list reply must include the post uid");
        assertTrue(reply.text().contains("stage1"),
                "list reply must include flagged_by");
        assertTrue(reply.text().contains("rule-1"),
                "list reply must include rule_id");
    }

    @Test
    void listAll_showsAllStatuses() throws Exception {
        String admin = PREFIX + "listall-admin";
        seedUser(admin, true, false, "vouched");

        UUID pending = seedQuarantineRow("PENDING", PREFIX + "listall-p1");
        UUID benign = seedQuarantineRow("BENIGN_CLOSED", PREFIX + "listall-bc1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine list --all");

        assertTrue(reply.text().contains(pending.toString()),
                "list --all must include PENDING rows");
        assertTrue(reply.text().contains(benign.toString()),
                "list --all must include BENIGN_CLOSED rows");
        assertTrue(reply.text().contains("BENIGN_CLOSED"),
                "list --all must show BENIGN_CLOSED status");
    }

    @Test
    void list_nonAdmin_rejected() throws Exception {
        String nonAdmin = PREFIX + "list-nonadmin";
        seedUser(nonAdmin, false, false, "invited");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(nonAdmin), "/quarantine list");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /quarantine list must surface error.admin_only");
    }

    // ---- /quarantine approve ----

    @Test
    void approve_transitionsPendingToApproved() throws Exception {
        String admin = PREFIX + "approve-admin";
        seedUser(admin, true, false, "vouched");
        UUID qId = seedQuarantineRow("PENDING", PREFIX + "approve-p1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine approve " + qId);

        assertEquals(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_QUARANTINE_APPROVE_SUCCESS),
                qId.toString()), reply.text());

        assertEquals("APPROVED", quarantineStatus(qId),
                "quarantine row must transition to APPROVED");
    }

    @Test
    void approve_benignClosedToApproved() throws Exception {
        String admin = PREFIX + "appbc-admin";
        seedUser(admin, true, false, "vouched");
        UUID qId = seedQuarantineRow("BENIGN_CLOSED", PREFIX + "appbc-p1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine approve " + qId);

        assertEquals(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_QUARANTINE_APPROVE_SUCCESS),
                qId.toString()), reply.text());

        assertEquals("APPROVED", quarantineStatus(qId),
                "BENIGN_CLOSED must transition to APPROVED");
    }

    @Test
    void approve_nonAdmin_rejected() throws Exception {
        String nonAdmin = PREFIX + "appna-nonadmin";
        seedUser(nonAdmin, false, false, "invited");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(nonAdmin),
                "/quarantine approve " + UUID.randomUUID());

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /quarantine approve must surface error.admin_only");
    }

    // ---- Stored-procedure error mapping (M1-462 F3) ----
    // mapStoredProcError() distinguishes the two RAISE EXCEPTION shapes of the
    // approve/reject SECURITY DEFINER functions by substring match on the
    // message text ("not found" / "expected PENDING or BENIGN_CLOSED"). These
    // tests drive both real proc error paths and assert each maps to its
    // SPECIFIC bundle reply, never the generic ERROR_INTERNAL fallback — so a
    // future edit to the procedure's RAISE wording fails here loudly instead of
    // silently degrading the user-visible reply. approve is the driving command
    // because handleApprove calls the proc directly with no handler-side
    // status pre-check (handleReject short-circuits terminal states before the
    // proc), so the proc itself raises both errors.

    @Test
    void approve_nonexistentId_mapsToNotFoundNotInternal() throws Exception {
        String admin = PREFIX + "errmap-notfound-admin";
        seedUser(admin, true, false, "vouched");
        UUID missingId = UUID.randomUUID();

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine approve " + missingId);

        assertEquals(MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_QUARANTINE_NOT_FOUND),
                missingId.toString()), reply.text(),
                "a non-existent quarantine id must map to ERROR_QUARANTINE_NOT_FOUND");
        assertNotEquals(bundleLoader.get(BundleKeys.ERROR_INTERNAL), reply.text(),
                "the not-found proc error must NOT collapse to the generic ERROR_INTERNAL fallback");
    }

    @Test
    void approve_terminalStateRow_mapsToInvalidStateNotInternal() throws Exception {
        String admin = PREFIX + "errmap-invstate-admin";
        seedUser(admin, true, false, "vouched");
        // A REJECTED row is neither PENDING nor BENIGN_CLOSED, so approve_quarantine
        // raises "has status REJECTED; expected PENDING or BENIGN_CLOSED".
        UUID qId = seedQuarantineRow("REJECTED", PREFIX + "errmap-invstate-p1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine approve " + qId);

        assertEquals(MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_QUARANTINE_INVALID_STATE),
                qId.toString()), reply.text(),
                "a non-PENDING/BENIGN_CLOSED row must map to ERROR_QUARANTINE_INVALID_STATE");
        assertNotEquals(bundleLoader.get(BundleKeys.ERROR_INTERNAL), reply.text(),
                "the invalid-state proc error must NOT collapse to the generic ERROR_INTERNAL fallback");
    }

    // ---- /quarantine reject ----

    @Test
    void reject_transitionsPendingToRejected() throws Exception {
        String admin = PREFIX + "reject-admin";
        seedUser(admin, true, false, "vouched");
        UUID qId = seedQuarantineRow("PENDING", PREFIX + "reject-p1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine reject " + qId);

        assertEquals(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_QUARANTINE_REJECT_SUCCESS),
                qId.toString()), reply.text());

        assertEquals("REJECTED", quarantineStatus(qId),
                "quarantine row must transition to REJECTED");
    }

    // M1-458: the forensic (BENIGN_CLOSED) reject path is confirm-gated.
    // These four tests replace the pre-M1-458 reject_benignClosedToRejected
    // (which asserted a single call transitioned directly) — the single
    // call now returns a confirm prompt and writes an intent-only audit row.

    @Test
    void rejectBenignClosedFirstCallPromptsAndWritesIntentOnly() throws Exception {
        String admin = PREFIX + "rejbc-first-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");
        UUID qId = seedQuarantineRow("BENIGN_CLOSED", PREFIX + "rejbc-first-p1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine reject " + qId);

        String expectedPrompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_QUARANTINE_REJECT),
                Long.toString(confirmStateService.timeoutSeconds()));
        assertEquals(expectedPrompt, reply.text(),
                "first forensic reject must return the confirm prompt, not execute");
        assertEquals("BENIGN_CLOSED", quarantineStatus(qId),
                "first call must NOT transition the row (reject_quarantine not called)");
        assertEquals(1, countAuditRows("QUARANTINE_REJECT_INTENT", qId, adminId),
                "first call must write exactly one QUARANTINE_REJECT_INTENT row");
        assertEquals(0, countAuditRows("REJECT_QUARANTINE", qId, adminId),
                "first call must NOT write the in-proc REJECT_QUARANTINE execute row");
    }

    @Test
    void rejectBenignClosedConfirmTransitionsToRejected() throws Exception {
        String admin = PREFIX + "rejbc-confirm-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");
        UUID qId = seedQuarantineRow("BENIGN_CLOSED", PREFIX + "rejbc-confirm-p1");
        ScopeRef scope = new ScopeRef.Dm(admin);

        // First call arms the pending intent; confirm executes the reject.
        handler.handle(scope, "/quarantine reject " + qId);
        OutboundMessage reply = handler.handle(scope, "/quarantine reject " + qId + " confirm");

        assertEquals(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_QUARANTINE_REJECT_SUCCESS),
                qId.toString()), reply.text());
        assertEquals("REJECTED", quarantineStatus(qId),
                "confirm must transition the forensic row to REJECTED");
        assertEquals(1, countAuditRows("QUARANTINE_REJECT_INTENT", qId, adminId),
                "the intent row written on the first call persists after confirm");
    }

    @Test
    void rejectBenignClosedConfirmWithoutPendingReturnsNoPending() throws Exception {
        String admin = PREFIX + "rejbc-nopending-admin";
        seedUser(admin, true, false, "vouched");
        UUID qId = seedQuarantineRow("BENIGN_CLOSED", PREFIX + "rejbc-nopending-p1");

        // Confirm with no prior first call — no armed pending.
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine reject " + qId + " confirm");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING), reply.text(),
                "confirm with no armed pending must surface error.confirm.no_pending");
        assertEquals("BENIGN_CLOSED", quarantineStatus(qId),
                "no pending means no transition");
    }

    @Test
    void rejectPendingStillTransitionsDirectlyNoConfirm() throws Exception {
        String admin = PREFIX + "rejp-direct-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");
        UUID qId = seedQuarantineRow("PENDING", PREFIX + "rejp-direct-p1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine reject " + qId);

        assertEquals(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_QUARANTINE_REJECT_SUCCESS),
                qId.toString()), reply.text(),
                "routine PENDING reject must return success in ONE call, no confirm prompt");
        assertEquals("REJECTED", quarantineStatus(qId),
                "PENDING reject transitions directly to REJECTED");
        assertEquals(0, countAuditRows("QUARANTINE_REJECT_INTENT", qId, adminId),
                "the routine PENDING path writes no intent row");
    }

    // ---- Rate limiting ----

    @Test
    void approve_rateLimitAfterBucketDrains() throws Exception {
        String admin = PREFIX + "rl-admin";
        seedUser(admin, true, false, "vouched");
        UUID qId = seedQuarantineRow("PENDING", PREFIX + "rl-p1");

        // Drain the bucket for this admin's quarantine key.
        // RateCapBucket default is 60/min; exhaust them all.
        String rateBucketKey = null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, admin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                rateBucketKey = rs.getObject("id", UUID.class).toString();
            }
        }
        for (int i = 0; i < 60; i++) {
            rateCapBucket.tryAcquire("quarantine", rateBucketKey);
        }

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine approve " + qId);

        assertTrue(reply.text().contains("too quickly"),
                "rate-exceeded approve must return rate-limit reply");
        assertEquals("PENDING", quarantineStatus(qId),
                "stored procedure must NOT be called when rate exceeded");
    }

    // ---- Audit logging ----

    @Test
    void list_writesQuarantineListAuditRow() throws Exception {
        String admin = PREFIX + "audit-admin";
        UUID adminId = seedUser(admin, true, false, "vouched");
        seedQuarantineRow("PENDING", PREFIX + "audit-p1");

        handler.handle(new ScopeRef.Dm(admin), "/quarantine list");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT action, actor_user_id, actor_contact_id, actor_adapter, details_json "
                             + "FROM audit_log WHERE action = 'QUARANTINE_LIST' "
                             + "AND actor_user_id = ?")) {
            ps.setObject(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "QUARANTINE_LIST audit row must exist");
                assertEquals("QUARANTINE_LIST", rs.getString("action"));
                assertEquals(adminId, rs.getObject("actor_user_id", UUID.class));
                assertEquals(admin, rs.getString("actor_contact_id"));
                assertEquals(ADAPTER, rs.getString("actor_adapter"));
                String details = rs.getString("details_json");
                assertTrue(details.contains("\"show_all\"") && details.contains("false"),
                        "details_json must record show_all flag, got: " + details);
            }
        }
    }

    // ---- Pagination ----

    @Test
    void list_page2ReturnsSecondPage() throws Exception {
        String admin = PREFIX + "pg-admin";
        seedUser(admin, true, false, "vouched");

        // Seed 25 rows to push past page 1 (pageSize=20)
        for (int i = 0; i < 25; i++) {
            seedQuarantineRow("PENDING", PREFIX + "pg-p" + String.format("%03d", i));
        }

        OutboundMessage page2 = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine list --page 2");

        assertTrue(page2.text().contains("page 2"),
                "page 2 header must show page 2");
        // Page 2 should have 5 rows (25 - 20)
        assertTrue(page2.text().contains("5 rows"),
                "page 2 must show the remaining rows");
    }

    @Test
    void list_malformedPage_usageError() throws Exception {
        String admin = PREFIX + "badpage-admin";
        seedUser(admin, true, false, "vouched");
        seedQuarantineRow("PENDING", PREFIX + "badpage-p1");

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(admin), "/quarantine list --page abc");

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT),
                "/quarantine list [--all] [--page N]");
        assertEquals(expected, reply.text(),
                "malformed --page must surface the usage error, not silently fall back to page 1");
    }

    // ---- Helpers ----

    private UUID seedUser(String contactId, boolean isAdmin, boolean isBanned,
                          String registrationState) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned, "
                             + "registration_state, banned_at) "
                             + "VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END) "
                             + "RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.setBoolean(4, isBanned);
            ps.setString(5, registrationState);
            ps.setBoolean(6, isBanned);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    /**
     * Seeds a post + quarantine row pair. The post body contains the placeholder
     * so the approve stored procedure can restore it. Returns the quarantine id.
     */
    private UUID seedQuarantineRow(String quarantineStatus, String postUid)
            throws Exception {
        String placeholderId = "ph-" + postUid;
        try (Connection conn = dataSource.getConnection()) {
            // Seed the post with body containing the placeholder
            UUID postId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post (source_id, uid, title, body, fetched_at, status) "
                            + "VALUES (?, ?, 'Test', 'some [REDACTED:' || ? || '] text', ?, 'QUARANTINED') "
                            + "RETURNING id")) {
                ps.setObject(1, sourceId);
                ps.setString(2, postUid);
                ps.setString(3, placeholderId);
                ps.setObject(4, FETCHED_AT);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    postId = (UUID) rs.getObject("id");
                }
            }

            // Seed the quarantine row
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO quarantine (post_id, post_uid, post_fetched_at, flagged_by, "
                            + "rule_id, span_start, span_end, placeholder_id, original_html, status) "
                            + "VALUES (?, ?, ?, 'stage1', 'rule-1', 0, 10, ?, '<b>original</b>', ?) "
                            + "RETURNING id")) {
                ps.setObject(1, postId);
                ps.setString(2, postUid);
                ps.setObject(3, FETCHED_AT);
                ps.setString(4, placeholderId);
                ps.setString(5, quarantineStatus);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return (UUID) rs.getObject("id");
                }
            }
        }
    }

    private int countAuditRows(String action, UUID targetId, UUID actorId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log "
                             + "WHERE action = ? AND target_id = ? AND actor_user_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, targetId.toString());
            ps.setObject(3, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String quarantineStatus(UUID quarantineId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM quarantine WHERE id = ?")) {
            ps.setObject(1, quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString("status");
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
