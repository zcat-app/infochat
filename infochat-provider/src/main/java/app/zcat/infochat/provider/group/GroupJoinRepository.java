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
 * <p>No soft-delete / {@code removed_at}: slot-freeing on bot-leave is
 * deferred to M1-522 (SimpleX reports no membership events, so the bot cannot
 * detect leaving a group). The counts here are a one-way ratchet at the
 * configured ceiling until that follow-up lands; the caps still close the
 * unbounded-growth DoS M1-519 targets.</p>
 */
@ApplicationScoped
public class GroupJoinRepository {

    // Idempotent record of one auto-join. ON CONFLICT DO NOTHING means a
    // duplicate invitation to an already-joined group (same natural key)
    // consumes exactly one slot — re-invites do not inflate the count.
    private static final String INSERT_JOIN =
            "INSERT INTO auto_joined_group (adapter, upstream_group_id, inviter_user_id) "
          + "VALUES (?, ?, ?) "
          + "ON CONFLICT (adapter, upstream_group_id) DO NOTHING";

    // Per-inviter activation-cap counter: how many distinct groups this
    // inviter has pulled the bot into.
    private static final String COUNT_BY_INVITER =
            "SELECT COUNT(*) FROM auto_joined_group WHERE inviter_user_id = ?";

    // Global max-groups counter: total passive memberships across all inviters.
    private static final String COUNT_ALL =
            "SELECT COUNT(*) FROM auto_joined_group";

    private final DataSource dataSource;

    @Inject
    public GroupJoinRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Record one auto-join, keyed by the natural key. Idempotent: a second
     * call for the same {@code (adapter, upstreamGroupId)} is a no-op (ON
     * CONFLICT DO NOTHING), so a duplicate invitation does not double-count.
     * The caller checks the caps BEFORE invoking this; recording here happens
     * on the join path only.
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
}
