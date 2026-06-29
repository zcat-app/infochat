package app.zcat.infochat.provider.digest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
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
            "SELECT slot_kind, slot_fired_at, expires_at"
                    + " FROM summary_cache"
                    + " WHERE group_id = ?"
                    + " ORDER BY slot_fired_at DESC LIMIT 1";

    private static final String SELECT_GROUP_TIMEZONE =
            "SELECT timezone FROM groups WHERE id = ?";

    // Provider-instance-local lock: cleared on restart per spec
    private final ConcurrentHashMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    // Per-group cooldown: prevents unbounded LLM cost from rapid retries
    private final ConcurrentHashMap<UUID, Instant> lastRetryAt = new ConcurrentHashMap<>();

    @Inject
    DataSource dataSource;

    @Inject
    DigestWorker digestWorker;

    // Per-group cooldown decision time comes from the injected Clock, not
    // Instant.now(), so the retry-cooldown gate is pinnable in tests. The
    // lastRetryAt write and the cooldown read both sample this one Clock — the
    // M1-444 no-two-clock-split rule for a value the component reads back to
    // gate a decision. (M1-449)
    @Inject
    Clock clock = Clock.systemUTC();

    @ConfigProperty(name = "infochat.digest.retry-cooldown", defaultValue = "PT2M")
    Duration retryCooldown;

    /**
     * Retry the most recent digest for the given group by re-executing the
     * digest worker with a synthetic slot built from the old row's
     * coordinates. The worker overwrites the cache row atomically (UPSERT),
     * so the existing digest is never deleted ahead of its replacement.
     *
     * <p>If the worker skips the run because a concurrent scheduled execution
     * already holds its in-flight guard, the cache row is left untouched and
     * this returns {@link RetryResult#ALREADY_IN_PROGRESS} — not SUCCESS, which
     * would falsely claim a regeneration that never happened.
     */
    public RetryResult retryDigest(UUID groupId) {
        Instant last = lastRetryAt.get(groupId);
        if (last != null && clock.instant().isBefore(last.plus(retryCooldown))) {
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
            DigestSlot slot = new DigestSlot(
                    groupId, timezone, coords.slotKind,
                    coords.slotFiredAt, coords.expiresAt);
            if (digestWorker.execute(slot) == DigestWorker.SlotOutcome.SKIPPED_IN_FLIGHT) {
                // A concurrent scheduled run owns the slot — the cache is
                // intact (we never deleted it) and untouched by this retry.
                return RetryResult.ALREADY_IN_PROGRESS;
            }
            lastRetryAt.put(groupId, clock.instant());
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
                        rs.getTimestamp("expires_at").toInstant());
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

    public enum RetryResult {
        SUCCESS,
        ALREADY_IN_PROGRESS,
        NO_PRIOR_DIGEST,
        RATE_LIMITED
    }

    record SlotCoordinates(
            String slotKind,
            Instant slotFiredAt,
            Instant expiresAt) {
    }
}
