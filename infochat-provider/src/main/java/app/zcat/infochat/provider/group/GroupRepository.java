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
import java.util.ArrayList;
import java.util.List;
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

    // /approve-group + /reject-group lookup by primary key. Returns a
    // richer projection than findApprovalRow (adds adapter +
    // upstream_group_id) so the handler can build the OutboundMessage
    // addressed at the target group without a follow-up query.
    private static final String SELECT_BY_ID =
            "SELECT id, adapter, upstream_group_id, approval_status, "
          + "       activated_by, removed_at "
          + "  FROM groups WHERE id = ?";

    // Status transition for /approve-group + /reject-group. Returns the
    // pre-update status via RETURNING so the handler can detect the no-op
    // (same-status) path without a separate SELECT — single round-trip.
    // OLD.approval_status is referenced via the standard "RETURNING old"
    // shape isn't supported by PostgreSQL; instead we filter the UPDATE
    // to fire only when the status would actually change, and detect the
    // no-op via getUpdateCount() == 0.
    private static final String UPDATE_APPROVAL_STATUS =
            "UPDATE groups SET approval_status = ? "
          + "WHERE id = ? AND approval_status <> ?";

    // /list-groups paginated read. JOINs users for the activated_by
    // contact id (LEFT JOIN — pre-V26 groups have NULL activated_by, and
    // bootstrap data may too). LEFT JOIN group_membership with removed_at
    // filter aggregates the active member count. ORDER BY created_at DESC
    // matches the /list-sources / /audit ordering convention (newest first).
    private static final String LIST_GROUPS_PAGE =
            "SELECT g.id, g.approval_status, g.timezone, "
          + "       u.contact_id AS activator_contact_id, "
          + "       COALESCE(m.member_count, 0) AS member_count "
          + "  FROM groups g "
          + "  LEFT JOIN users u ON u.id = g.activated_by "
          + "  LEFT JOIN (SELECT group_id, COUNT(*) AS member_count "
          + "               FROM group_membership "
          + "              WHERE removed_at IS NULL "
          + "              GROUP BY group_id) m ON m.group_id = g.id "
          + " ORDER BY g.created_at DESC "
          + " LIMIT ? OFFSET ?";

    private static final String COUNT_ALL_GROUPS =
            "SELECT COUNT(*) FROM groups";

    // /status admin-only pending-groups discovery (M1-114). Mirrors the
    // exact predicate spec'd in docs/spec/commands.md §Discovery: rows
    // with approval_status='pending' that have NOT been removed. Excludes
    // removed rows so a soft-deleted pending group does not haunt the
    // admin's status forever.
    private static final String COUNT_PENDING_GROUPS =
            "SELECT COUNT(*) FROM groups "
          + "WHERE approval_status = 'pending' "
          + "  AND removed_at IS NULL";

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

    /**
     * Richer snapshot of a {@code groups} row than
     * {@link GroupApprovalRow}: adds {@code adapter} +
     * {@code upstreamGroupId} so {@code /approve-group} and
     * {@code /reject-group} can build the post-mutation
     * {@code OutboundMessage} addressed at the target group without
     * a second SELECT. Returned by {@link #findById}.
     */
    public record GroupRow(
            @NonNull UUID id,
            @NonNull String adapter,
            @NonNull String upstreamGroupId,
            @NonNull String approvalStatus,
            @Nullable UUID activatedBy,
            @Nullable Instant removedAt) {}

    /**
     * Look up a group by its UUID primary key. Used by
     * {@code /approve-group} and {@code /reject-group} to resolve the
     * positional {@code <group_id>} argument before mutating
     * {@code approval_status}, and to retrieve the
     * {@code (adapter, upstream_group_id)} pair the post-mutation
     * group-message delivery needs.
     */
    public @NonNull Optional<GroupRow> findById(@NonNull UUID groupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = rs.getObject("id", UUID.class);
                String adapter = rs.getString("adapter");
                String upstreamGroupId = rs.getString("upstream_group_id");
                String status = rs.getString("approval_status");
                UUID activatedBy = rs.getObject("activated_by", UUID.class);
                Timestamp removedTs = rs.getTimestamp("removed_at");
                Instant removedAt = removedTs == null ? null : removedTs.toInstant();
                return Optional.of(new GroupRow(
                        id, adapter, upstreamGroupId, status, activatedBy, removedAt));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "findById failed for groupId=" + groupId, e);
        }
    }

    /**
     * Set {@code approval_status} on the row identified by {@code groupId}.
     * The UPDATE filters on {@code approval_status <> newStatus} so the
     * no-op case (target already in {@code newStatus}) returns row count
     * 0 in a single round-trip, letting the caller distinguish "mutated"
     * from "already in target state" without a separate SELECT. Runs on
     * the caller's connection so the caller can wrap audit-before-effect
     * around the call inside one transaction.
     *
     * @return {@code true} when one row was updated, {@code false} when
     *         the row was already in {@code newStatus} (no-op path).
     *         A non-existent row also returns {@code false}; the caller
     *         must short-circuit on {@link #findById} before calling this.
     */
    public boolean setApprovalStatus(@NonNull Connection conn,
                                     @NonNull UUID groupId,
                                     @NonNull String newStatus) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_APPROVAL_STATUS)) {
            ps.setString(1, newStatus);
            ps.setObject(2, groupId);
            ps.setString(3, newStatus);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * One row of {@code /list-groups} output. {@code activatorContactId}
     * carries the {@code users.contact_id} of {@code activated_by} (NULL
     * when the group has no activator, e.g. pre-V26 rows or bootstrap
     * data); the handler is responsible for redacting it via
     * {@code ContactIds.redact}.
     */
    public record GroupListRow(
            @NonNull UUID id,
            @NonNull String approvalStatus,
            @NonNull String timezone,
            @Nullable String activatorContactId,
            long memberCount) {}

    /**
     * Paginated read for {@code /list-groups}. ORDER BY created_at DESC
     * (newest first). Returns at most {@code pageSize} rows starting at
     * {@code (page - 1) * pageSize}; the caller computes total pages
     * separately via {@link #countAllGroups}.
     */
    public @NonNull List<GroupListRow> listGroupsPage(int page, int pageSize) {
        List<GroupListRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(LIST_GROUPS_PAGE)) {
            ps.setInt(1, pageSize);
            ps.setInt(2, (page - 1) * pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GroupListRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("approval_status"),
                            rs.getString("timezone"),
                            rs.getString("activator_contact_id"),
                            rs.getLong("member_count")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "listGroupsPage failed for page=" + page + " pageSize=" + pageSize, e);
        }
        return out;
    }

    /**
     * Row count for {@code /list-groups} pagination. Counts every row
     * including {@code removed_at IS NOT NULL} ones (mirrors the
     * {@link #listGroupsPage} SELECT, which is unfiltered on removed_at).
     */
    public long countAllGroups() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_ALL_GROUPS);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("countAllGroups failed", e);
        }
    }

    /**
     * Count {@code groups} rows with {@code approval_status='pending'}
     * AND {@code removed_at IS NULL}. Consumed by
     * {@code StatusCommandHandler}'s admin-only pending-groups line per
     * {@code docs/spec/commands.md} §Discovery — passive discovery of
     * groups awaiting approval without running {@code /list-groups}.
     */
    public long countPendingGroups() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_PENDING_GROUPS);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("countPendingGroups failed", e);
        }
    }
}
