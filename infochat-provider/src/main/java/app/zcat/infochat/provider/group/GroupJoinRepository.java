package app.zcat.infochat.provider.group;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * SQL access to the {@code auto_joined_group} table (V55), the durable
 * join-tracking the auto-accept surface counts to enforce the D47 total
 * group-count caps (M1-519). Natural key {@code (adapter, upstream_group_id)};
 * one row per group the bot has auto-joined.
 *
 * <p>Distinct from {@link GroupRepository}, which owns the {@code groups}
 * table (the §3.5 @mention approval machine). A group can appear in BOTH:
 * {@code auto_joined_group} records that the bot passively joined via invite;
 * {@code groups} records that the group later entered the D47 approval
 * machine via an @mention. The two counters are intentionally independent —
 * the join surface bounds passive memberships here, the @mention surface
 * bounds approvals in {@link GroupRepository} — so a group counted in both is
 * the conservative (tighter) direction for a flood bound, never looser.</p>
 *
 * <p>Soft-delete via {@code removed_at} (V56, M1-525): when the bot leaves or
 * is removed from a group it auto-joined, the slot is freed by setting
 * {@code removed_at} (never a row DELETE — the V55 append-only guard holds), so
 * it stops counting against the D47 caps and the cap is not a permanent
 * lifetime ratchet. The count methods here exclude {@code removed_at IS NOT
 * NULL} rows. Two paths free a slot: the native {@code BotRemoved} event via
 * {@link #markRemovedByNaturalKey}, and the SimpleX
 * permanent-delivery-failure inference via {@link #markRemovedByGroupId}
 * (called from {@code GroupRepository.markRemovedAudited}).</p>
 */
@ApplicationScoped
public class GroupJoinRepository {

    // Idempotent record of one auto-join, keyed by the natural key. ON CONFLICT
    // DO UPDATE REACTIVATES a previously-freed row: it clears removed_at back to
    // NULL and re-attributes inviter_user_id to the current inviter (M1-525). A
    // plain duplicate of a still-active group is a harmless no-op (removed_at is
    // already NULL, inviter set to the same value), so a re-invite still
    // consumes exactly one slot. Without the reactivation, a leave (removed_at
    // set, excluded from the counts) followed by a re-join would leave an ACTIVE
    // group permanently uncounted by both D47 caps — the cap-laundering DoS the
    // redteam M1-525-2026-06-29 HIGH finding flagged.
    private static final String INSERT_JOIN =
            "INSERT INTO auto_joined_group (adapter, upstream_group_id, inviter_user_id) "
          + "VALUES (?, ?, ?) "
          + "ON CONFLICT (adapter, upstream_group_id) "
          + "DO UPDATE SET removed_at = NULL, inviter_user_id = EXCLUDED.inviter_user_id";

    // Per-inviter activation-cap counter: how many distinct groups this
    // inviter has pulled the bot into. Freed slots (removed_at IS NOT NULL)
    // are excluded so a group the bot later left stops counting (M1-525).
    private static final String COUNT_BY_INVITER =
            "SELECT COUNT(*) FROM auto_joined_group "
          + "WHERE inviter_user_id = ? AND removed_at IS NULL";

    // Global max-groups counter: total passive memberships across all inviters.
    // Freed slots excluded (M1-525), mirroring the per-inviter counter.
    private static final String COUNT_ALL =
            "SELECT COUNT(*) FROM auto_joined_group WHERE removed_at IS NULL";

    // Free the slot for one group by its natural key, used by the native
    // BotRemoved path (the event carries the natural key directly). The
    // removed_at IS NULL guard makes a repeat a verified no-op.
    private static final String MARK_REMOVED_BY_NATURAL_KEY =
            "UPDATE auto_joined_group SET removed_at = now() "
          + "WHERE adapter = ? AND upstream_group_id = ? AND removed_at IS NULL";

    // Free the slot for one group by its groups.id, JOINing on the
    // (adapter, upstream_group_id) natural key so the SimpleX-path caller
    // (which holds only the groups UUID) needs no extra SELECT.
    private static final String MARK_REMOVED_BY_GROUP_ID =
            "UPDATE auto_joined_group aj SET removed_at = now() "
          + "FROM groups g "
          + "WHERE g.id = ? AND aj.adapter = g.adapter "
          + "  AND aj.upstream_group_id = g.upstream_group_id "
          + "  AND aj.removed_at IS NULL";

    private final DataSource dataSource;

    @Inject
    public GroupJoinRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Record one auto-join, keyed by the natural key. On a conflicting natural
     * key the INSERT REACTIVATES the existing row — clears {@code removed_at} to
     * NULL and re-attributes {@code inviter_user_id} to {@code inviterUserId}
     * (M1-525) — so a re-join after a leave re-counts against the D47 caps under
     * the current inviter rather than leaving a laundered, uncounted active
     * group. A duplicate of a still-active group is a harmless no-op (the row is
     * already non-removed). The caller checks the caps BEFORE invoking this;
     * recording here happens on the join path only.
     */
    public void tryRecordJoin(String adapter, String upstreamGroupId, UUID inviterUserId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_JOIN)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            ps.setObject(3, inviterUserId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "tryRecordJoin failed for adapter=" + adapter
                            + " upstreamGroupId=" + upstreamGroupId, e);
        }
    }

    /**
     * Count the groups this inviter has auto-joined the bot into. The
     * per-user activation cap is exceeded when the result is {@code >=} the
     * configured {@code infochat.groups.per-user-activation-cap}.
     */
    public long countJoinsByInviter(UUID inviterUserId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_BY_INVITER)) {
            ps.setObject(1, inviterUserId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "countJoinsByInviter failed for inviterUserId=" + inviterUserId, e);
        }
    }

    /**
     * Count all auto-joined groups across every inviter. The global cap is
     * exceeded when the result is {@code >=} the configured
     * {@code infochat.groups.global-max-groups}.
     */
    public long countJoins() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_ALL);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("countJoins failed", e);
        }
    }

    /**
     * Free the auto-join slot for the group identified by its natural key, so
     * it stops counting against the D47 caps (M1-525). A {@code removed_at}
     * soft-set, never a DELETE (the V55 append-only guard). Idempotent: the
     * {@code removed_at IS NULL} guard makes a repeated free a verified no-op.
     *
     * @return {@code true} when a non-removed row was freed; {@code false}
     *         when no matching non-removed row existed (unknown group, or
     *         already freed). The native {@code BotRemoved} caller uses this to
     *         distinguish a genuine join-only free (INFO) from a truly unknown
     *         group (the redacted unknown-group WARN).
     */
    public boolean markRemovedByNaturalKey(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(MARK_REMOVED_BY_NATURAL_KEY)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "markRemovedByNaturalKey failed for adapter=" + adapter
                            + " upstreamGroupId=" + upstreamGroupId, e);
        }
    }

    /**
     * Free the auto-join slot for the group identified by its {@code groups.id}
     * (M1-525). Runs on the caller's connection so the free is atomic with the
     * caller's audit-before-effect transaction — the {@code markRemoved(
     * Connection, UUID)} precedent in {@link GroupRepository}. A
     * {@code removed_at} soft-set, idempotent via {@code removed_at IS NULL}.
     */
    public void markRemovedByGroupId(Connection conn, UUID groupId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(MARK_REMOVED_BY_GROUP_ID)) {
            ps.setObject(1, groupId);
            ps.executeUpdate();
        }
    }
}
