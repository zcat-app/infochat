package app.zcat.infochat.provider.digest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-group serialized retry of the most recent digest. Invoked by
 * {@link app.zcat.infochat.provider.command.RetryCommandHandler} when
 * the {@code --digest} flag is present. At most one retry is in flight
 * per group at any time (Provider-instance-local; a restart clears
 * in-progress state per spec).
 */
@ApplicationScoped
public class DigestRetryService {

    private static final String SELECT_LATEST_CACHE =
            "SELECT slot_kind, slot_fired_at, expires_at, is_degraded"
                    + " FROM summary_cache"
                    + " WHERE group_id = ?"
                    + " ORDER BY slot_fired_at DESC LIMIT 1";

    private static final String SELECT_GROUP_TIMEZONE =
            "SELECT timezone FROM groups WHERE id = ?";

    private static final String DELETE_CACHE_ROW =
            "DELETE FROM summary_cache"
                    + " WHERE group_id = ? AND slot_kind = ? AND slot_fired_at = ?";

    // Provider-instance-local lock: cleared on restart per spec
    private final ConcurrentHashMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    // Per-group cooldown: prevents unbounded LLM cost from rapid retries
    private final ConcurrentHashMap<UUID, Instant> lastRetryAt = new ConcurrentHashMap<>();

    @Inject
    DataSource dataSource;

    @Inject
    DigestWorker digestWorker;

    @ConfigProperty(name = "infochat.digest.retry-cooldown", defaultValue = "PT2M")
    Duration retryCooldown;

    /**
     * Retry the most recent digest for the given group. Deletes the
     * existing cache row and re-executes the digest worker with a
     * synthetic slot built from the old row's coordinates.
     */
    public @NonNull RetryResult retryDigest(@NonNull UUID groupId) {
        Instant last = lastRetryAt.get(groupId);
        if (last != null && Instant.now().isBefore(last.plus(retryCooldown))) {
            return RetryResult.RATE_LIMITED;
        }
        if (inFlight.putIfAbsent(groupId, Boolean.TRUE) != null) {
            return RetryResult.ALREADY_IN_PROGRESS;
        }
        try {
            SlotCoordinates coords = findLatestCacheEntry(groupId);
            if (coords == null) {
                return RetryResult.NO_PRIOR_DIGEST;
            }
            String timezone = lookupGroupTimezone(groupId);
            if (timezone == null) {
                return RetryResult.NO_PRIOR_DIGEST;
            }
            deleteCacheRow(groupId, coords.slotKind, coords.slotFiredAt);
            DigestSlot slot = new DigestSlot(
                    groupId, timezone, coords.slotKind,
                    coords.slotFiredAt, coords.expiresAt);
            digestWorker.execute(slot);
            lastRetryAt.put(groupId, Instant.now());
            return RetryResult.SUCCESS;
        } finally {
            inFlight.remove(groupId);
        }
    }

    private @Nullable SlotCoordinates findLatestCacheEntry(UUID groupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_LATEST_CACHE)) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SlotCoordinates(
                        rs.getString("slot_kind"),
                        rs.getTimestamp("slot_fired_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("is_degraded"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DigestRetryService.findLatestCacheEntry failed", e);
        }
    }

    private @Nullable String lookupGroupTimezone(UUID groupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_TIMEZONE)) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("timezone");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DigestRetryService.lookupGroupTimezone failed", e);
        }
    }

    private void deleteCacheRow(UUID groupId, String slotKind, Instant slotFiredAt) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_CACHE_ROW)) {
            ps.setObject(1, groupId);
            ps.setString(2, slotKind);
            ps.setTimestamp(3, Timestamp.from(slotFiredAt));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DigestRetryService.deleteCacheRow failed", e);
        }
    }

    public enum RetryResult {
        SUCCESS,
        ALREADY_IN_PROGRESS,
        NO_PRIOR_DIGEST,
        RATE_LIMITED
    }

    record SlotCoordinates(
            String slotKind,
            Instant slotFiredAt,
            Instant expiresAt,
            boolean isDegraded) {
    }
}
