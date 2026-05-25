package app.zcat.infochat.provider.digest;

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

/**
 * JDBC repository for the {@code summary_cache} table (V23). Provides
 * insert, TTL-aware lookup, and existence check for the digest slot
 * deduplication logic in {@link DigestScheduler}.
 */
@ApplicationScoped
public class SummaryCacheRepository {

    @Inject
    DataSource dataSource;

    /**
     * Insert a new summary cache row. Called by DigestWorker (M1-080b)
     * after generating digest content.
     */
    public void insert(@NonNull UUID groupId,
                       @NonNull String slotKind,
                       @NonNull Instant slotFiredAt,
                       long tagSubscriptionVersion,
                       long sourceSubscriptionVersion,
                       @NonNull String content,
                       boolean isDegraded,
                       @NonNull Instant expiresAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summary_cache"
                             + " (group_id, slot_kind, slot_fired_at,"
                             + "  tag_subscription_version, source_subscription_version,"
                             + "  content, is_degraded, expires_at)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, groupId);
            ps.setString(2, slotKind);
            ps.setTimestamp(3, Timestamp.from(slotFiredAt));
            ps.setLong(4, tagSubscriptionVersion);
            ps.setLong(5, sourceSubscriptionVersion);
            ps.setString(6, content);
            ps.setBoolean(7, isDegraded);
            ps.setTimestamp(8, Timestamp.from(expiresAt));
            ps.executeUpdate();
        }
    }

    /**
     * Find the latest non-expired cache entry for a group and slot kind
     * within a given window. Returns empty if no valid (non-expired) row
     * exists.
     */
    public Optional<CacheEntry> findByGroupAndSlot(@NonNull UUID groupId,
                                                   @NonNull String slotKind,
                                                   @NonNull Instant slotFiredAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, tag_subscription_version, source_subscription_version,"
                             + " content, is_degraded, created_at, expires_at"
                             + " FROM summary_cache"
                             + " WHERE group_id = ? AND slot_kind = ? AND slot_fired_at = ?"
                             + "   AND expires_at > now()"
                             + " ORDER BY created_at DESC LIMIT 1")) {
            ps.setObject(1, groupId);
            ps.setString(2, slotKind);
            ps.setTimestamp(3, Timestamp.from(slotFiredAt));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new CacheEntry(
                            rs.getLong("id"),
                            groupId,
                            slotKind,
                            slotFiredAt,
                            rs.getLong("tag_subscription_version"),
                            rs.getLong("source_subscription_version"),
                            rs.getString("content"),
                            rs.getBoolean("is_degraded"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant()));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Check whether a summary_cache row exists for the given group, slot
     * kind, and fired-at instant — regardless of expiration. Used by
     * {@link DigestScheduler} to determine if a slot has already fired
     * (even if the cached content has since expired).
     */
    public boolean existsByGroupAndSlot(@NonNull UUID groupId,
                                        @NonNull String slotKind,
                                        @NonNull Instant slotFiredAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM summary_cache"
                             + " WHERE group_id = ? AND slot_kind = ? AND slot_fired_at = ?"
                             + " LIMIT 1")) {
            ps.setObject(1, groupId);
            ps.setString(2, slotKind);
            ps.setTimestamp(3, Timestamp.from(slotFiredAt));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public record CacheEntry(
            long id,
            @NonNull UUID groupId,
            @NonNull String slotKind,
            @NonNull Instant slotFiredAt,
            long tagSubscriptionVersion,
            long sourceSubscriptionVersion,
            @NonNull String content,
            boolean isDegraded,
            @NonNull Instant createdAt,
            @NonNull Instant expiresAt) {
    }
}
