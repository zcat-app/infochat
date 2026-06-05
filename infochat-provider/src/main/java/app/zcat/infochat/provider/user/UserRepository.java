package app.zcat.infochat.provider.user;

import app.zcat.infochat.core.log.ContactIds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

// Encapsulates the `users` natural-key lookup (V5__identity_audit.sql).
// Natural key: (adapter, contact_id). PK: UUID. Handlers keep their own
// per-handler row records; this repository owns only the shared SELECT
// and the canonical row mapping, so a `users`-schema change touches one
// SQL string instead of every handler.
@ApplicationScoped
public class UserRepository {

    // Union projection: every delegating handler's record is a subset
    // of these columns, so one canonical SELECT serves them all.
    private static final String SELECT_BY_NATURAL_KEY =
            "SELECT id, contact_id, is_admin, is_banned, registration_state, save_count "
          + "  FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_BY_NATURAL_KEY_FOR_UPDATE =
            SELECT_BY_NATURAL_KEY + " FOR UPDATE";

    private static final String SELECT_ID_BY_NATURAL_KEY =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    private final DataSource dataSource;

    @Inject
    public UserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Canonical snapshot of a {@code users} row. Every column in this
     * projection is NOT NULL per the V5 schema.
     */
    public record UserRow(
            UUID id,
            String contactId,
            boolean isAdmin,
            boolean isBanned,
            String registrationState,
            int saveCount) {}

    /**
     * Plain (unlocked) natural-key lookup on a repository-owned
     * connection. For reads that gate a mutation inside a transaction,
     * use {@link #findByAdapterAndContactIdForUpdate} instead.
     */
    public Optional<UserRow> findByAdapterAndContactId(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_NATURAL_KEY)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UserRepository.findByAdapterAndContactId failed for adapter="
                            + adapter + " contact_id=" + ContactIds.redact(contactId), e);
        }
    }

    /**
     * {@code SELECT … FOR UPDATE} variant on the caller's connection.
     * The row lock is held until the caller commits or rolls back, so
     * admin-gate reads done through this method cannot be invalidated
     * by a concurrent mutation (e.g. {@code /revoke-admin}) between the
     * check and the effect — the M1-046 PERM-ESCAL closure pattern.
     */
    public Optional<UserRow> findByAdapterAndContactIdForUpdate(
            Connection conn, String adapter, String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_NATURAL_KEY_FOR_UPDATE)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                return mapRow(rs);
            }
        }
    }

    /** Id-only natural-key lookup for callers that need no row state. */
    public Optional<UUID> resolveUserId(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ID_BY_NATURAL_KEY)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(rs.getObject("id", UUID.class));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UserRepository.resolveUserId failed for adapter="
                            + adapter + " contact_id=" + ContactIds.redact(contactId), e);
        }
    }

    private static Optional<UserRow> mapRow(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            return Optional.empty();
        }
        return Optional.of(new UserRow(
                rs.getObject("id", UUID.class),
                rs.getString("contact_id"),
                rs.getBoolean("is_admin"),
                rs.getBoolean("is_banned"),
                rs.getString("registration_state"),
                rs.getInt("save_count")));
    }
}
