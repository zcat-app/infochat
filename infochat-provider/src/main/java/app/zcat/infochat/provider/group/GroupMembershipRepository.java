package app.zcat.infochat.provider.group;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

// Encapsulates all SQL access to the `group_membership` table
// (V5__identity_audit.sql). Composite PK: (group_id, user_id).
// The V5 partial unique index `one_admin_per_group` enforces at most
// one is_group_admin=true row per group; the V5 trigger
// trg_group_membership_clear_admin clears is_group_admin when
// removed_at transitions from NULL to non-NULL.
@ApplicationScoped
public class GroupMembershipRepository {

    private static final String INSERT_MEMBER =
            "INSERT INTO group_membership (group_id, user_id) VALUES (?, ?)";

    private static final String SELECT_ADMIN_FLAG =
            "SELECT is_group_admin FROM group_membership "
          + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL";

    // Both privilege writes live in V62 SECURITY DEFINER routines:
    // infochat_provider no longer holds UPDATE on
    // group_membership.is_group_admin. Each routine is a 1:1 replacement
    // of the statement it replaces, so promoteToAdmin still sees the
    // 23505 from one_admin_per_group and still reports it as false
    // (M1-672).
    //
    // The routines resolve their actor from the infochat.actor_id GUC,
    // which set_config makes transaction-local. That is why the two
    // methods below take a Connection: the GUC has to be bound on the
    // SAME connection, and the no-arg forms these replaced opened their
    // own in autocommit, where nothing could bind it.
    private static final String PROMOTE =
            "SELECT promote_group_admin(?, ?)";

    private static final String DEMOTE =
            "SELECT demote_group_admin(?, ?)";

    private static final String MARK_REMOVED =
            "UPDATE group_membership SET removed_at = now() "
          + "WHERE group_id = ? AND user_id = ? AND removed_at IS NULL";

    // No removed_at filter: an already-removed row must stay visible so
    // the caller can detect a verified no-op (repeated leave events) and
    // skip both the audit row and the mutation.
    private static final String LOCK_MEMBERSHIP =
            "SELECT is_group_admin, removed_at FROM group_membership "
          + "WHERE group_id = ? AND user_id = ? FOR UPDATE";

    private final DataSource dataSource;

    @Inject
    public GroupMembershipRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void addMember(UUID groupId, UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_MEMBER)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("addMember failed", e);
        }
    }

    public boolean isGroupAdmin(UUID groupId, UUID userId) {
        try (Connection conn = dataSource.getConnection()) {
            return isGroupAdmin(conn, groupId, userId);
        } catch (SQLException e) {
            throw new IllegalStateException("isGroupAdmin failed", e);
        }
    }

    // Runs on the caller's connection so the caller can wrap
    // audit-before-effect around the call inside one transaction
    // (the GroupRepository.setApprovalStatus precedent).
    public boolean isGroupAdmin(Connection conn, UUID groupId,
                                UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ADMIN_FLAG)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_group_admin");
            }
        }
    }

    /**
     * Snapshot of one membership row read under {@code FOR UPDATE}.
     * {@code removed} is true when {@code removed_at} is set — the row
     * exists but the member already left.
     */
    public record MembershipState(boolean groupAdmin, boolean removed) {}

    // Locking read on the caller's connection: the row lock held until
    // the caller's commit serializes against concurrent /promote and
    // /demote UPDATEs, so the is_group_admin value read here cannot be
    // invalidated between the read and markMemberRemoved (the audited
    // was_group_admin provenance). Returns null when no row exists.
    public @Nullable MembershipState lockMembership(Connection conn, UUID groupId,
                                                    UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(LOCK_MEMBERSHIP)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new MembershipState(
                        rs.getBoolean("is_group_admin"),
                        rs.getTimestamp("removed_at") != null);
            }
        }
    }

    // Returns false if the partial unique index rejects a second active
    // admin. Runs on the caller's connection: the V62 routine resolves
    // its actor from the transaction-local infochat.actor_id GUC, so the
    // caller must have bound it on this same connection.
    public boolean promoteToAdmin(Connection conn, UUID groupId, UUID userId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(PROMOTE)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.execute();
            return true;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    // Runs on the caller's connection, for the same GUC reason as
    // promoteToAdmin.
    public void demoteAdmin(Connection conn, UUID groupId, UUID userId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DEMOTE)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.execute();
        }
    }

    // Sets removed_at; the V5 trigger clears is_group_admin in the same
    // statement execution, freeing the partial unique index slot.
    public void markMemberRemoved(UUID groupId, UUID userId) {
        try (Connection conn = dataSource.getConnection()) {
            markMemberRemoved(conn, groupId, userId);
        } catch (SQLException e) {
            throw new IllegalStateException("markMemberRemoved failed", e);
        }
    }

    // Runs on the caller's connection so the caller can wrap
    // audit-before-effect around the call inside one transaction.
    public void markMemberRemoved(Connection conn, UUID groupId,
                                  UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(MARK_REMOVED)) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
    }
}
