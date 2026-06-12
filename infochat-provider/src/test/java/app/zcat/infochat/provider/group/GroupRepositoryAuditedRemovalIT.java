package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditAction;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real-DB cover for the audit-before-effect rollback in
 * {@link GroupRepository#markRemovedAudited(UUID, String)}: when the
 * {@code BOT_REMOVED} audit write fails, the {@code removed_at} soft-remove
 * must roll back so a group is never left removed without its audit row
 * (Invariant 7).
 *
 * <p>Lives in the {@code group} package so it can substitute a
 * {@link FailingAuditLogWriter} into the field-injected
 * {@code GroupRepository.auditLogWriter} seam. The happy path — the row is
 * written, {@code removed_at} is set, and the columns match the native
 * {@code MembershipEventHandler} path — is covered end-to-end through the real
 * permanent-failure chokepoint by {@code OutboundDeliveryCleanupIT}.</p>
 */
@QuarkusTest
class GroupRepositoryAuditedRemovalIT {

    static final UUID GROUP = UUID.fromString("0b541001-1001-4000-8000-000000000001");

    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM audit_log WHERE scope_id = ?", GROUP);
            exec(conn, "DELETE FROM groups WHERE id = ?", GROUP);
        }
    }

    @Test
    void auditWriteFailureRollsBackGroupSoftRemove() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "INSERT INTO groups (id, adapter, upstream_group_id) "
                    + "VALUES (?, 'inmemory', 'grar-it-upstream')", GROUP);
        }

        // Substitute a writer that always throws into the field-injected seam:
        // the BOT_REMOVED audit write fails, so the whole transaction — the
        // audit row AND the removed_at mutation — must roll back.
        GroupRepository repo = new GroupRepository(dataSource);
        repo.auditLogWriter = new FailingAuditLogWriter();

        assertThrows(IllegalStateException.class,
                () -> repo.markRemovedAudited(GROUP, "grar-it-chan"));

        assertNull(readRemovedAt(GROUP),
                "removed_at must stay NULL when the audit write fails (no orphan removal)");
        assertFalse(hasBotRemovedRow(GROUP),
                "no BOT_REMOVED row may remain after the rolled-back transaction");
    }

    private java.sql.Timestamp readRemovedAt(UUID groupId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM groups WHERE id = ?")) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1) : null;
            }
        }
    }

    private boolean hasBotRemovedRow(UUID scopeId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM audit_log WHERE scope_id = ? AND action = ?")) {
            ps.setObject(1, scopeId);
            ps.setString(2, AuditAction.BOT_REMOVED.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
