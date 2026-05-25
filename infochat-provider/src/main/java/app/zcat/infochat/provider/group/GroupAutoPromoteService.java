package app.zcat.infochat.provider.group;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

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
 * {@code is_group_admin=true}. ON CONFLICT DO NOTHING handles the
 * race-safe case where two concurrent messages compete for the slot.
 */
@ApplicationScoped
public class GroupAutoPromoteService {

    private static final String CHECK_ELIGIBILITY_SQL =
            "SELECT is_banned, probation_until FROM users WHERE id = ?";

    // ON CONFLICT DO NOTHING catches both the (group_id, user_id) PK
    // conflict (user already has a membership row) and the partial
    // unique index one_admin_per_group (another user is already admin).
    private static final String AUTO_PROMOTE_SQL =
            "INSERT INTO group_membership (group_id, user_id, is_group_admin) "
                    + "VALUES (?, ?, true) ON CONFLICT DO NOTHING";

    private final DataSource dataSource;
    private final AuditLogWriter auditLogWriter;

    @Inject
    public GroupAutoPromoteService(@NonNull DataSource dataSource,
                                   @NonNull AuditLogWriter auditLogWriter) {
        this.dataSource = dataSource;
        this.auditLogWriter = auditLogWriter;
    }

    /**
     * Attempt to auto-promote the user to group admin. Skips banned
     * and probation users. Returns true if the INSERT succeeded (user
     * is now group admin), false if the slot was already occupied or
     * the user was ineligible. Writes a PROMOTE_GROUP_ADMIN audit row
     * on success within the same transaction.
     */
    public boolean tryAutoPromote(@NonNull UUID groupId, @NonNull UUID userId,
                                  @NonNull String adapter, @NonNull String contactId) {
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
                // Audit after confirming the INSERT succeeded but before commit
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
