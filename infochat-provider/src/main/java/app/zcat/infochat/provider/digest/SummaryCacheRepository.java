package app.zcat.infochat.provider.digest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
    public void insert(UUID groupId,
                       String slotKind,
                       Instant slotFiredAt,
                       long tagSubscriptionVersion,
                       long sourceSubscriptionVersion,
                       String content,
                       boolean isDegraded,
                       Instant expiresAt) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insert(conn, groupId, slotKind, slotFiredAt,
                    tagSubscriptionVersion, sourceSubscriptionVersion,
                    content, isDegraded, expiresAt);
        }
    }

    /**
     * Connection-accepting variant for callers whose insert must
     * participate in an enclosing transaction (the missed-slot sentinel
     * in {@link DigestScheduler} commits atomically with its audit row).
     * Does not commit or roll back — transaction control stays with the
     * caller that owns the connection.
     */
    public void insert(Connection conn,
                       UUID groupId,
                       String slotKind,
                       Instant slotFiredAt,
                       long tagSubscriptionVersion,
                       long sourceSubscriptionVersion,
                       String content,
                       boolean isDegraded,
                       Instant expiresAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
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
     * Atomically insert-or-overwrite a summary cache row, keyed by the
     * {@code (group_id, slot_kind, slot_fired_at)} unique index. Used by
     * {@link DigestWorker} so a {@code /retry --digest} regeneration replaces
     * the cached content in a single statement — there is no window where the
     * row is deleted but not yet rewritten (the delete-then-execute hazard
     * this method removes).
     *
     * <p>Distinct from {@link #insert} on purpose: the missed-slot sentinel
     * in {@link DigestScheduler} reuses the plain {@code INSERT} and depends on
     * a unique-index violation to roll its audit+sentinel transaction back, so
     * it must NOT become an upsert. Requires the {@code UPDATE} privilege on
     * {@code summary_cache} (granted to {@code infochat_provider} in V46) — the
     * {@code ON CONFLICT DO UPDATE} clause fails with "permission denied" under
     * the weak service role without it.
     */
    public void upsert(UUID groupId,
                       String slotKind,
                       Instant slotFiredAt,
                       long tagSubscriptionVersion,
                       long sourceSubscriptionVersion,
                       String content,
                       boolean isDegraded,
                       Instant expiresAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summary_cache"
                             + " (group_id, slot_kind, slot_fired_at,"
                             + "  tag_subscription_version, source_subscription_version,"
                             + "  content, is_degraded, expires_at)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                             + " ON CONFLICT (group_id, slot_kind, slot_fired_at)"
                             + " DO UPDATE SET"
                             + "   tag_subscription_version = EXCLUDED.tag_subscription_version,"
                             + "   source_subscription_version = EXCLUDED.source_subscription_version,"
                             + "   content = EXCLUDED.content,"
                             + "   is_degraded = EXCLUDED.is_degraded,"
                             + "   expires_at = EXCLUDED.expires_at")) {
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
    public Optional<CacheEntry> findByGroupAndSlot(UUID groupId,
                                                   String slotKind,
                                                   Instant slotFiredAt) throws SQLException {
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
     * Latest {@code slot_fired_at} strictly before the given instant for the
     * group, across slot kinds and regardless of expiry — the previous digest
     * boundary {@link DigestWorker} collects from. Sentinel rows written for
     * missed slots count as boundaries on purpose: skip-not-catch-up
     * (commands.md §Periodic group digests) means a missed window's period is
     * not folded into the next digest.
     */
    public Optional<Instant> findPreviousBoundary(UUID groupId, Instant before)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT max(slot_fired_at) FROM summary_cache"
                             + " WHERE group_id = ? AND slot_fired_at < ?")) {
            ps.setObject(1, groupId);
            ps.setTimestamp(2, Timestamp.from(before));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp boundary = rs.getTimestamp(1);
                return boundary == null
                        ? Optional.empty()
                        : Optional.of(boundary.toInstant());
            }
        }
    }

    /**
     * Check whether a summary_cache row exists for the given group, slot
     * kind, and fired-at instant — regardless of expiration. Used by
     * {@link DigestScheduler} to determine if a slot has already fired
     * (even if the cached content has since expired).
     */
    public boolean existsByGroupAndSlot(UUID groupId,
                                        String slotKind,
                                        Instant slotFiredAt) throws SQLException {
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
            UUID groupId,
            String slotKind,
            Instant slotFiredAt,
            long tagSubscriptionVersion,
            long sourceSubscriptionVersion,
            String content,
            boolean isDegraded,
            Instant createdAt,
            Instant expiresAt) {
    }
}
