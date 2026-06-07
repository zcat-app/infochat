package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Implements the first-mention auto-promote path per
 * {@code docs/spec/security.md} §Authorization model: when a group has
 * zero active admins, the next non-banned, non-probation sender is
 * promoted to group admin via INSERT into group_membership with
 * {@code is_group_admin=true}. ON CONFLICT (group_id, user_id) DO
 * UPDATE handles re-promotion of existing members whose admin flag was
 * cleared (e.g. after a user-left event); the partial unique index
 * {@code one_admin_per_group} enforces at-most-one active admin.
 */
@ApplicationScoped
public class GroupAutoPromoteService {

    // PostgreSQL SQLSTATE for unique_violation — returned when the
    // one_admin_per_group partial unique index rejects a second admin.
    private static final String PG_UNIQUE_VIOLATION = "23505";

    private static final String CHECK_ELIGIBILITY_SQL =
            "SELECT is_banned, probation_until FROM users WHERE id = ?";

    // ON CONFLICT handles two cases:
    // 1. Existing member (PK conflict on group_id, user_id): UPDATE sets
    //    is_group_admin=true only for active (non-removed) members.
    // 2. Partial unique index one_admin_per_group rejects the UPDATE if
    //    another user already holds the admin slot — executeUpdate returns 0.
    private static final String AUTO_PROMOTE_SQL =
            "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                    + "VALUES (?, ?, true) "
                    + "ON CONFLICT (group_id, user_id) DO UPDATE "
                    + "SET is_group_admin = true "
                    + "WHERE group_membership.removed_at IS NULL";

    private final DataSource dataSource;
    private final AuditLogWriter auditLogWriter;

    @Inject
    public GroupAutoPromoteService(DataSource dataSource,
                                   AuditLogWriter auditLogWriter) {
        this.dataSource = dataSource;
        this.auditLogWriter = auditLogWriter;
    }

    /**
     * Attempt to auto-promote the user to group admin. Skips banned
     * and probation users. Returns true if the INSERT or re-promote
     * UPDATE succeeded (user is now group admin), false if the slot
     * was already occupied, the user was ineligible, or the member
     * was removed. Writes a PROMOTE_GROUP_ADMIN audit row on success
     * within the same transaction.
     */
    public boolean tryAutoPromote(UUID groupId, UUID userId,
                                  String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection()) {
            if (!isEligible(conn, userId)) {
                return false;
            }
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(AUTO_PROMOTE_SQL)) {
                    ps.setObject(1, groupId);
                    ps.setObject(2, userId);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
                RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                        .actorUserId(userId)
                        .actorContactId(contactId)
                        .actorAdapter(adapter)
                        .action(AuditAction.PROMOTE_GROUP_ADMIN)
                        .targetKind("user")
                        .targetId(userId.toString())
                        .scopeId(groupId)
                        .requestId(UUID.randomUUID().toString())
                        .detailsJson("{\"auto_promote\":true}")
                        .build();
                auditLogWriter.write(conn, auditRow);
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                // 23505 = unique_violation from one_admin_per_group partial
                // index: another user already holds the admin slot (race loser
                // for new members where the PK conflict path doesn't fire).
                if (PG_UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    return false;
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("tryAutoPromote failed", e);
        }
    }

    private boolean isEligible(Connection conn, UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CHECK_ELIGIBILITY_SQL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                if (rs.getBoolean("is_banned")) {
                    return false;
                }
                Timestamp ts = rs.getTimestamp("probation_until");
                return ts == null || !ts.toInstant().isAfter(Instant.now());
            }
        }
    }
}
