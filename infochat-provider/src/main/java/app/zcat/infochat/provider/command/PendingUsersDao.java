package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.log.SafeLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only DAO for the {@code /pending} actionable-user list. Surfaces the
 * bounded admin-action input set — users with slow-start probation still
 * running ({@code probation_until > now}, D45) — scoped to a single adapter so
 * every returned {@code contact_id} is exactly the {@code (adapter, contact_id)}
 * key that {@code /vouch} and {@code /ban} match on. "Awaiting a vouch" is a
 * subset of that window (see {@code ACTIONABLE_WHERE}). Deliberately NOT a
 * general user directory (D55): banned and settled (vouched, out-of-probation)
 * users are excluded because an admin has nothing pending to do about them.
 *
 * <p>The probation cutoff is a decision gate on "now", so the caller passes the
 * instant read from its injected {@link java.time.Clock} (engineering-rules §9 /
 * D45) rather than this DAO reading {@code now()} inline in SQL — the read stays
 * pinnable in tests.
 */
@ApplicationScoped
public class PendingUsersDao {

    private static final Logger LOG = LoggerFactory.getLogger(PendingUsersDao.class);

    // Shared actionable-set predicate: an un-banned user still inside the
    // slow-start probation window. The single '?' is the probation cutoff
    // ('now', from the caller's injected Clock).
    //
    // Deliberately NO registration_state = 'invited' arm: post-D47 'invited' is
    // a terminal state — /vouch only nulls probation_until (single-column clear)
    // and the only runtime writers of 'vouched' are AdminBootstrap and
    // SimpleXAdminClaim, both bootstrap-admin paths — so an 'invited' arm would
    // match every regular registered user forever, turning /pending into the
    // permanent full roster D55 forbids. "Awaiting a vouch" is a subset of the
    // probation window (a vouch after natural probation expiry is a no-op), so
    // the narrower predicate loses nothing actionable (M1-579).
    private static final String ACTIONABLE_WHERE =
            " WHERE adapter = ? AND is_banned = FALSE"
                    + " AND probation_until IS NOT NULL AND probation_until > ?";

    @Inject DataSource dataSource;

    /**
     * One actionable-user row. Every row the actionable predicate returns
     * carries a running probation deadline; {@code probationUntil} stays
     * {@code @Nullable} only because the JDBC column read is nullable-typed.
     */
    record PendingUser(String contactId, String adapter, String registrationState,
                       @Nullable OffsetDateTime probationUntil, OffsetDateTime createdAt) {}

    /** The caller's identity for the permission check and the audit-row actor. */
    record ActorRow(UUID id, boolean isAdmin) {}

    /**
     * The caller's user row keyed by {@code (adapter, contactId)}, or empty if no
     * such user exists. A lookup failure fails closed (returns empty → caller
     * surfaces the admin-only error), the same posture as
     * {@code AuditCommandHandler.lookupActor}. The {@code id} feeds the
     * privileged-read audit row's {@code actor_user_id}.
     */
    Optional<ActorRow> lookupActor(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ActorRow(
                        rs.getObject("id", UUID.class), rs.getBoolean("is_admin")));
            }
        } catch (SQLException e) {
            SafeLog.error(LOG, "lookupActor failed for adapter=" + adapter, e);
            return Optional.empty();
        }
    }

    long countActionable(String adapter, Instant now) throws SQLException {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM users" + ACTIONABLE_WHERE)) {
            ps.setString(1, adapter);
            ps.setObject(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    List<PendingUser> listActionable(String adapter, Instant now, int limit, int offset)
            throws SQLException {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        List<PendingUser> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT contact_id, adapter, registration_state, probation_until, created_at"
                             + " FROM users" + ACTIONABLE_WHERE
                             + " ORDER BY created_at ASC, contact_id ASC LIMIT ? OFFSET ?")) {
            ps.setString(1, adapter);
            ps.setObject(2, cutoff);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PendingUser(
                            rs.getString("contact_id"),
                            rs.getString("adapter"),
                            rs.getString("registration_state"),
                            rs.getObject("probation_until", OffsetDateTime.class),
                            rs.getObject("created_at", OffsetDateTime.class)));
                }
            }
        }
        return rows;
    }
}
