package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape B (Thin-SQL) tests for {@link RemoveSourceCommandHandler}.
 *
 * <p>Test isolation: every fixture row carries the
 * {@code m1-053-remove-} prefix; {@link #cleanup()} deletes only rows
 * matching that prefix.</p>
 */
@QuarkusTest
class RemoveSourceCommandHandlerTest {

    private static final String PREFIX = "m1-053-remove-";
    private static final String ADAPTER = "inmemory";

    @Inject RemoveSourceCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;
    @Inject ConfirmStateService confirmStateService;

    @BeforeEach
    void cleanup() throws Exception {
        inboundContext.setAdapterName(ADAPTER);
        try (Connection conn = dataSource.getConnection()) {
            // Permanent guardian admin so the V5 last-admin-protection
            // trigger does not refuse the per-test DELETE on admin
            // rows (the BanCommandHandlerTest precedent at line 85-90).
            exec(conn,
                    "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                            + "VALUES (?, ?, TRUE, 'vouched') "
                            + "ON CONFLICT (adapter, contact_id) DO UPDATE "
                            + "  SET is_admin = TRUE, is_banned = FALSE",
                    ADAPTER, "guardian-m1-053-remove-permanent");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn,
                        "DELETE FROM audit_log WHERE target_kind = 'source' AND target_id IN ("
                                + "  SELECT id::TEXT FROM source WHERE identifier LIKE ?)",
                        "https://example.com/" + PREFIX + "%");
                exec(conn,
                        "DELETE FROM audit_log WHERE actor_user_id IN ("
                                + "  SELECT id FROM users WHERE contact_id LIKE ?)",
                        PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }
            exec(conn,
                    "DELETE FROM source_subscription WHERE source_id IN ("
                            + "  SELECT id FROM source WHERE identifier LIKE ?)",
                    "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM source WHERE identifier LIKE ?",
                    "https://example.com/" + PREFIX + "%");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void removeSourceNonAdminReturnsAdminOnlyError() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        seedUser(actor, false);
        UUID sourceId = seedSource("nonAdmin");
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/remove-source " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /remove-source must surface error.admin_only");
        assertFalse(isSoftDeleted(sourceId),
                "non-admin /remove-source must not touch source.deleted_at");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "non-admin /remove-source must not write any audit row");
    }

    @Test
    void removeSourceFirstCallReturnsPromptAndWritesIntentAuditRowOnly() throws Exception {
        String actor = PREFIX + "firstCall-actor";
        UUID actorId = seedUser(actor, true);
        UUID sourceId = seedSource("firstCall");
        seedSubscription("dm", actorId, sourceId);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/remove-source " + sourceId);

        // Prompt body should reference the source display name AND
        // the timeout-seconds AND the subscriber count.
        assertTrue(reply.text().contains(PREFIX + "firstCall-name"),
                "first /remove-source must reply with the prompt naming the source — got: "
                        + reply.text());
        assertTrue(reply.text().contains("/remove-source confirm"),
                "prompt must instruct the admin to send /remove-source confirm — got: "
                        + reply.text());

        // Exactly one audit row (REMOVE_SOURCE_INTENT). The completion
        // REMOVE_SOURCE row writes on the confirm leg only.
        assertEquals(auditBefore + 1, countAuditForTarget(sourceId),
                "first /remove-source must write exactly ONE audit row (the intent)");
        assertEquals(1L, countAuditByActionForTarget("REMOVE_SOURCE_INTENT", sourceId),
                "the single audit row must be REMOVE_SOURCE_INTENT");
        assertEquals(0L, countAuditByActionForTarget("REMOVE_SOURCE", sourceId),
                "first /remove-source must NOT write the REMOVE_SOURCE completion row");

        assertFalse(isSoftDeleted(sourceId),
                "first /remove-source must NOT mutate source.deleted_at");
        assertEquals(1L, countSubscriptions(sourceId),
                "first /remove-source must NOT cascade-delete subscriptions");

        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertTrue(peeked.isPresent(),
                "ConfirmStateService.peek must show a pending entry under (actor.id, scope)");
        assertEquals("remove-source", peeked.get().commandName(),
                "pending entry's commandName must be remove-source");
    }

    @Test
    void removeSourceConfirmWithinWindowExecutesSoftDeleteAndCascade() throws Exception {
        String actor = PREFIX + "confirm-actor";
        UUID actorId = seedUser(actor, true);
        UUID sourceId = seedSource("confirm");
        // Two subscriptions in distinct scopes — cascade must remove both.
        seedSubscription("dm", actorId, sourceId);
        UUID otherUserId = seedUser(PREFIX + "confirm-other", false);
        seedSubscription("dm", otherUserId, sourceId);

        // First call to establish pending.
        handler.handle(new ScopeRef.Dm(actor), "/remove-source " + sourceId);
        // Confirm call.
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/remove-source confirm");

        assertTrue(reply.text().contains(PREFIX + "confirm-name"),
                "confirm reply must name the removed source — got: " + reply.text());
        assertTrue(reply.text().contains("removed"),
                "confirm reply must surface the success literal — got: " + reply.text());

        assertTrue(isSoftDeleted(sourceId),
                "confirm /remove-source must set source.deleted_at IS NOT NULL");
        assertEquals(0L, countSubscriptions(sourceId),
                "confirm /remove-source must cascade-delete every source_subscription row");

        // Two audit rows persist: REMOVE_SOURCE_INTENT (first call) +
        // REMOVE_SOURCE (confirm).
        assertEquals(1L, countAuditByActionForTarget("REMOVE_SOURCE_INTENT", sourceId),
                "REMOVE_SOURCE_INTENT row from first call must persist");
        assertEquals(1L, countAuditByActionForTarget("REMOVE_SOURCE", sourceId),
                "REMOVE_SOURCE completion row from confirm must exist");

        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertFalse(peeked.isPresent(),
                "ConfirmStateService.peek must be empty after confirm consumes the pending");
    }

    @Test
    void removeSourceConfirmWithoutPendingReturnsNoPending() throws Exception {
        String actor = PREFIX + "noPending-actor";
        seedUser(actor, true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/remove-source confirm");

        assertEquals(bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING), reply.text(),
                "/remove-source confirm without pending must surface error.confirm.no_pending");
    }

    @Test
    void removeSourceFirstCallAgainstAlreadySoftDeletedReturnsAlreadyDeleted() throws Exception {
        String actor = PREFIX + "alreadyDel-actor";
        UUID actorId = seedUser(actor, true);
        UUID sourceId = seedSoftDeletedSource("alreadyDel");
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/remove-source " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_REMOVE_SOURCE_ALREADY_DELETED), reply.text(),
                "/remove-source against an already-soft-deleted source must surface "
                        + "already_deleted error");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "rejected first call must NOT write any audit row");

        Optional<ConfirmStateService.PendingConfirm> peeked =
                confirmStateService.peek(actorId, new ScopeRef.Dm(actor));
        assertFalse(peeked.isPresent(),
                "rejected first call must NOT store pending state");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedUser(String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private UUID seedSoftDeletedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status, deleted_at) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active', ?) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setObject(3, OffsetDateTime.now());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedSubscription(String scopeKind, UUID scopeId, UUID sourceId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private boolean isSoftDeleted(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT deleted_at FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("deleted_at") != null;
            }
        }
    }

    private long countSubscriptions(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM source_subscription WHERE source_id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditForTarget(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE target_kind = 'source' "
                             + "AND target_id = ?")) {
            ps.setString(1, sourceId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAuditByActionForTarget(String action, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE action = ? "
                             + "AND target_kind = 'source' AND target_id = ?")) {
            ps.setString(1, action);
            ps.setString(2, sourceId.toString());
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
