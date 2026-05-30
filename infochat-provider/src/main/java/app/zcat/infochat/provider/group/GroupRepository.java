package app.zcat.infochat.provider.group;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Encapsulates all SQL access to the `groups` table (V5__identity_audit.sql,
// extended in V26 with approval_status + activated_by). Natural key:
// (adapter, upstream_group_id). PK: UUID.
@ApplicationScoped
public class GroupRepository {

    private static final String UPSERT =
            "INSERT INTO groups (adapter, upstream_group_id) "
          + "VALUES (?, ?) "
          + "ON CONFLICT (adapter, upstream_group_id) DO NOTHING";

    private static final String SELECT_BY_NATURAL_KEY =
            "SELECT id FROM groups "
          + "WHERE adapter = ? AND upstream_group_id = ?";

    private static final String SET_REMOVED =
            "UPDATE groups SET removed_at = now() WHERE id = ?";

    private static final String CLEAR_REMOVED =
            "UPDATE groups SET removed_at = NULL WHERE id = ?";

    // D47 step 3.5 (M1-112). Race-safe creation with the activator
    // recorded on the winning row. ON CONFLICT DO NOTHING means a
    // concurrent loser sees no rows returned and falls back to the
    // SELECT path below.
    private static final String INSERT_PENDING_RETURNING =
            "INSERT INTO groups (adapter, upstream_group_id, approval_status, activated_by) "
          + "VALUES (?, ?, 'pending', ?) "
          + "ON CONFLICT (adapter, upstream_group_id) DO NOTHING "
          + "RETURNING id";

    private static final String SELECT_APPROVAL_ROW =
            "SELECT id, approval_status, activated_by, removed_at FROM groups "
          + "WHERE adapter = ? AND upstream_group_id = ?";

    // Per-user activation cap counts all approval states that impose
    // ongoing cost (pending/approved/rejected). Removed groups are
    // excluded so an activate-reject-remove cycle frees a slot back.
    private static final String COUNT_BY_ACTIVATED_BY =
            "SELECT COUNT(*) FROM groups "
          + "WHERE activated_by = ? "
          + "  AND approval_status IN ('pending', 'approved', 'rejected') "
          + "  AND removed_at IS NULL";

    // Global cap counts only pending+approved groups — rejected groups
    // are dormant and impose no ongoing cost. Removed groups are
    // excluded across the board.
    private static final String COUNT_ACTIVE =
            "SELECT COUNT(*) FROM groups "
          + "WHERE removed_at IS NULL "
          + "  AND approval_status IN ('pending', 'approved')";

    private final DataSource dataSource;

    @Inject
    public GroupRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Snapshot of the D47 approval-relevant columns on a {@code groups}
     * row. Returned by {@link #findApprovalRow}. {@code activatedBy} and
     * {@code removedAt} are nullable per the V5 / V26 schema.
     */
    public record GroupApprovalRow(
            @NonNull UUID id,
            @NonNull String approvalStatus,
            @Nullable UUID activatedBy,
            @Nullable Instant removedAt) {}

    // Race-safe upsert: INSERT…ON CONFLICT DO NOTHING + SELECT.
    // Matches the AutoRegisterService precedent.
    public @NonNull UUID findOrCreateByAdapterAndUpstreamId(
            @NonNull String adapter, @NonNull String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
                ps.setString(1, adapter);
                ps.setString(2, upstreamGroupId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_NATURAL_KEY)) {
                ps.setString(1, adapter);
                ps.setString(2, upstreamGroupId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getObject(1, UUID.class);
                    }
                }
            }
            throw new IllegalStateException(
                    "group row missing after upsert — schema invariant violation");
        } catch (SQLException e) {
            throw new IllegalStateException("findOrCreateByAdapterAndUpstreamId failed", e);
        }
    }

    public void markRemoved(@NonNull UUID groupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SET_REMOVED)) {
            ps.setObject(1, groupId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("markRemoved failed", e);
        }
    }

    public void clearRemoved(@NonNull UUID groupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(CLEAR_REMOVED)) {
            ps.setObject(1, groupId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("clearRemoved failed", e);
        }
    }

    /**
     * D47 step 3.5 read-only lookup. Returns the row's id +
     * approval_status + activated_by + removed_at, or
     * {@link Optional#empty()} when no row exists for the natural key.
     * Callers dispatch on {@code approval_status} to decide whether the
     * inbound is processed, short-circuited, or routed to the creation
     * path.
     */
    public @NonNull Optional<GroupApprovalRow> findApprovalRow(
            @NonNull String adapter, @NonNull String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_APPROVAL_ROW)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = rs.getObject("id", UUID.class);
                String status = rs.getString("approval_status");
                UUID activatedBy = rs.getObject("activated_by", UUID.class);
                Timestamp removedTs = rs.getTimestamp("removed_at");
                Instant removedAt = removedTs == null ? null : removedTs.toInstant();
                return Optional.of(new GroupApprovalRow(id, status, activatedBy, removedAt));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "findApprovalRow failed for adapter=" + adapter
                            + " upstreamGroupId=" + upstreamGroupId, e);
        }
    }

    /**
     * D47 step 3.5 race-safe creation. Inserts a new {@code groups} row
     * with {@code approval_status='pending'} and the activating user
     * recorded in {@code activated_by}. Returns {@link Optional#of}
     * the new row's UUID when THIS call won the insert (RETURNING
     * produced the row), {@link Optional#empty} when the natural-key
     * conflict fired — either a pre-existing row or a concurrent race
     * loser. The follow-up {@link #findApprovalRow} resolves the
     * existing row for the loser.
     *
     * <p>The returned UUID lets the caller interpolate the newly-minted
     * group id into the throttled admin notification's
     * {@code /approve-group <uuid>} hint (acceptance item 7).</p>
     *
     * <p>The {@code activated_by} column carries the FK to {@code users.id};
     * the caller resolves the user id from the inbound contact id before
     * invoking this method.</p>
     */
    public @NonNull Optional<UUID> tryInsertPending(
            @NonNull String adapter,
            @NonNull String upstreamGroupId,
            @NonNull UUID activatedByUserId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_PENDING_RETURNING)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            ps.setObject(3, activatedByUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(rs.getObject(1, UUID.class));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "tryInsertPending failed for adapter=" + adapter
                            + " upstreamGroupId=" + upstreamGroupId, e);
        }
    }

    /**
     * Per-user group activation cap counter. Counts rows where
     * {@code activated_by = userId} AND
     * {@code approval_status IN ('pending','approved','rejected')} AND
     * {@code removed_at IS NULL}. The cap is exceeded when the result
     * is {@code >=} the configured cap. Per design/04-security.md §4.9
     * the count includes rejected groups so an activate-reject cycle
     * does NOT free a slot until the row is also removed.
     */
    public long countGroupsActivatedBy(@NonNull UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_BY_ACTIVATED_BY)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "countGroupsActivatedBy failed for userId=" + userId, e);
        }
    }

    /**
     * Global max-groups cap counter. Counts rows where
     * {@code removed_at IS NULL} AND
     * {@code approval_status IN ('pending','approved')}. Rejected
     * groups are dormant and excluded from the global cap.
     */
    public long countActiveGroups() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_ACTIVE);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("countActiveGroups failed", e);
        }
    }
}
