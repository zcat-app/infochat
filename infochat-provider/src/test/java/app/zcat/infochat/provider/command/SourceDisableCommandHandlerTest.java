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
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape B (Thin-SQL) tests for {@link SourceDisableCommandHandler} per
 * {@code docs/process/test-pyramid.md}: real {@code @QuarkusTest},
 * real Postgres via DevServices, hand-written SQL assertions against
 * {@code audit_log} and {@code source}.
 *
 * <p>Test isolation: every fixture row carries the
 * {@code m1-053-disable-} prefix; {@link #cleanup()} deletes only rows
 * matching that prefix.</p>
 */
@QuarkusTest
class SourceDisableCommandHandlerTest {

    private static final String PREFIX = "m1-053-disable-";
    private static final String ADAPTER = "inmemory";

    @Inject SourceDisableCommandHandler handler;
    @Inject DataSource dataSource;
    @Inject BundleLoader bundleLoader;
    @Inject InboundContext inboundContext;

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
                    ADAPTER, "guardian-m1-053-disable-permanent");
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
    void sourceDisableNonAdminReturnsAdminOnlyError() throws Exception {
        String actor = PREFIX + "nonAdmin-actor";
        seedUser(actor, false);
        UUID sourceId = seedSource("nonAdmin", "active", false);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-disable " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY), reply.text(),
                "non-admin /source-disable must surface error.admin_only");
        assertEquals("active", readStatus(sourceId),
                "non-admin /source-disable must not touch source.status");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "non-admin /source-disable must not write any audit row");
    }

    @Test
    void sourceDisableHappyPathTransitionsActiveToDisabled() throws Exception {
        String actor = PREFIX + "happy-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("happy", "active", false);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-disable " + sourceId);

        assertEquals(expectedSuccess(sourceId), reply.text(),
                "happy-path /source-disable must surface the success reply");
        assertEquals("disabled", readStatus(sourceId),
                "happy-path /source-disable must transition status to 'disabled'");
        assertEquals(1L, countAuditByActionForTarget("SOURCE_DISABLE", sourceId),
                "happy-path /source-disable must write exactly one SOURCE_DISABLE audit row");
    }

    @Test
    void sourceDisableAgainstFailedSourceReturnsNotActive() throws Exception {
        String actor = PREFIX + "failed-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("failed", "failed", false);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-disable " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SOURCE_DISABLE_NOT_ACTIVE), reply.text(),
                "/source-disable against a failed source must surface not_active error");
        assertEquals("failed", readStatus(sourceId),
                "/source-disable against a failed source must not change status");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "/source-disable against a failed source must not write any audit row");
    }

    @Test
    void sourceDisableAgainstAlreadyDisabledSourceReturnsNotActive() throws Exception {
        String actor = PREFIX + "disabled-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("disabled", "disabled", false);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-disable " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SOURCE_DISABLE_NOT_ACTIVE), reply.text(),
                "/source-disable against an already-disabled source must surface not_active error");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "/source-disable against an already-disabled source must not write any audit row");
    }

    @Test
    void sourceDisableAgainstSoftDeletedSourceReturnsNotActive() throws Exception {
        String actor = PREFIX + "softDel-actor";
        seedUser(actor, true);
        UUID sourceId = seedSource("softDel", "active", true);
        long auditBefore = countAuditForTarget(sourceId);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(actor),
                "/source-disable " + sourceId);

        assertEquals(bundleLoader.get(BundleKeys.ERROR_SOURCE_DISABLE_NOT_ACTIVE), reply.text(),
                "/source-disable against a soft-deleted source must surface not_active error");
        assertTrue(readDeletedAtIsNotNull(sourceId),
                "/source-disable against a soft-deleted source must not change deleted_at");
        assertEquals(auditBefore, countAuditForTarget(sourceId),
                "/source-disable against a soft-deleted source must not write any audit row");
    }

    // ----- helpers ---------------------------------------------------------

    private void seedUser(String contactId, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                             + "VALUES (?, ?, ?, 'vouched')")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.executeUpdate();
        }
    }

    private UUID seedSource(String slug, String status, boolean softDeleted) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "  bootstrap_tags, status, deleted_at) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', ?, ?) "
                             + "RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setString(3, status);
            if (softDeleted) {
                ps.setObject(4, OffsetDateTime.now());
            } else {
                ps.setObject(4, null);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private String readStatus(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("status");
            }
        }
    }

    private boolean readDeletedAtIsNotNull(UUID sourceId) throws Exception {
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

    private String expectedSuccess(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT display_name FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_SOURCE_DISABLE_SUCCESS),
                        rs.getString("display_name"));
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
