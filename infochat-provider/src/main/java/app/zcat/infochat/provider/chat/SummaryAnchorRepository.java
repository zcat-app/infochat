package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CRUD over the {@code summary_anchor} table for personal anchors
 * ({@code command_kind = 'personal'}). Digest anchors are T2-F territory.
 *
 * <p>Also maintains an in-memory retry count per (user, scope) — no
 * DB column exists and {@code migration_touch: false} prohibits adding
 * one. The counter resets on Provider restart, which is acceptable per
 * the bounded-retry-window semantics (the anchor row itself persists).
 */
@ApplicationScoped
public class SummaryAnchorRepository {

    private static final String COMMAND_KIND_PERSONAL = "personal";

    // UPSERT by the partial unique index (user_id, scope_kind, scope_id,
    // command_kind) WHERE user_id IS NOT NULL.
    private static final String UPSERT = """
            INSERT INTO summary_anchor (user_id, scope_kind, scope_id, command_kind,
                                        command_name, arg_hash, post_uids, cluster_map)
            VALUES (?, ?, ?, 'personal', ?, ?, ?, ?::jsonb)
            ON CONFLICT (user_id, scope_kind, scope_id, command_kind)
                WHERE user_id IS NOT NULL
            DO UPDATE SET command_name = EXCLUDED.command_name,
                          arg_hash     = EXCLUDED.arg_hash,
                          post_uids    = EXCLUDED.post_uids,
                          cluster_map  = EXCLUDED.cluster_map,
                          generated_at = now()
            """;

    private static final String SELECT =
            "SELECT command_name, arg_hash, post_uids, cluster_map, generated_at "
            + "FROM summary_anchor "
            + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
            + "  AND command_kind = 'personal'";

    private static final String DELETE =
            "DELETE FROM summary_anchor "
            + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
            + "  AND command_kind = 'personal'";

    public record AnchorRow(
            UUID userId,
            UUID scopeId,
            String commandName,
            String argHash,
            List<String> postUids,
            @Nullable String clusterMapJson,
            Instant generatedAt) {}

    // Carries scope_kind for the same reason the table does: a DM and a
    // group scope with colliding UUIDs must not share a retry count.
    private record RetryKey(UUID userId, String scopeKind,
                            UUID scopeId) {}

    @Inject
    DataSource dataSource;

    private final ConcurrentHashMap<RetryKey, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    /**
     * Write (upsert) a personal anchor row. Resets the in-memory retry
     * count for the same (user, scope) since this is a fresh summary.
     */
    public void write(UUID userId, String scopeKind, UUID scopeId,
                      String commandName, String argHash,
                      List<String> postUids, @Nullable String clusterMapJson) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, commandName);
            ps.setString(5, argHash);
            String[] uidStrings = postUids.toArray(new String[0]);
            Array sqlArray = conn.createArrayOf("text", uidStrings);
            ps.setArray(6, sqlArray);
            ps.setString(7, clusterMapJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SummaryAnchorRepository.write failed", e);
        }
        clearRetryCount(userId, scopeKind, scopeId);
    }

    /**
     * Read the personal anchor for the given (user, scope).
     */
    public Optional<AnchorRow> read(UUID userId, String scopeKind,
                                             UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String commandName = rs.getString("command_name");
                String argHash = rs.getString("arg_hash");
                String[] rawUids = (String[]) rs.getArray("post_uids").getArray();
                List<String> postUids = List.of(rawUids);
                String clusterMapJson = rs.getString("cluster_map");
                Instant generatedAt = rs.getTimestamp("generated_at").toInstant();
                return Optional.of(new AnchorRow(
                        userId, scopeId, commandName, argHash,
                        postUids, clusterMapJson, generatedAt));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SummaryAnchorRepository.read failed", e);
        }
    }

    /**
     * Delete the personal anchor and clear the retry count.
     */
    public void clear(UUID userId, String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SummaryAnchorRepository.clear failed", e);
        }
        clearRetryCount(userId, scopeKind, scopeId);
    }

    /**
     * Atomically increment and return the retry count for the given
     * (user, scope). First call after a write (or clear) returns 1.
     */
    public int incrementAndGetRetryCount(UUID userId, String scopeKind,
                                         UUID scopeId) {
        return retryCounts
                .computeIfAbsent(new RetryKey(userId, scopeKind, scopeId),
                        k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * Read the current retry count for the given (user, scope) WITHOUT
     * mutating it — returns 0 when no retry has been recorded since the
     * last {@link #write}/{@link #clear}. {@code /retry} peeks this to
     * enforce the cap before spending an LLM token or incrementing the
     * counter: an at-cap retry must consume neither a rate-cap token nor
     * further (monotonic, non-self-healing) counter growth.
     */
    public int peekRetryCount(UUID userId, String scopeKind, UUID scopeId) {
        AtomicInteger counter = retryCounts.get(new RetryKey(userId, scopeKind, scopeId));
        return counter == null ? 0 : counter.get();
    }

    /**
     * Clear the in-memory retry count.
     */
    public void clearRetryCount(UUID userId, String scopeKind,
                                UUID scopeId) {
        retryCounts.remove(new RetryKey(userId, scopeKind, scopeId));
    }
}
