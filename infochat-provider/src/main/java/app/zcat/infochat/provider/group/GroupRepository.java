package app.zcat.infochat.provider.group;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

// Encapsulates all SQL access to the `groups` table (V5__identity_audit.sql).
// Natural key: (adapter, upstream_group_id). PK: UUID.
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

    private final DataSource dataSource;

    @Inject
    public GroupRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

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
}
