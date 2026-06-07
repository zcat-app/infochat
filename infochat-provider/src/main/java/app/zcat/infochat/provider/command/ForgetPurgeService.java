package app.zcat.infochat.provider.command;

import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Executes the {@code /forget} purge set inside a caller-managed
 * transaction.
 *
 * <p>Invariant 7 (audit-before-effect): the caller pre-counts via
 * {@link #preCount}, writes the audit row with those counts, then
 * executes the DELETEs via {@link #purge}. Both methods run inside
 * the same transaction so the pre-counts and the deletes are
 * consistent.</p>
 */
@ApplicationScoped
public class ForgetPurgeService {

    // Pre-count queries for audit-before-effect (Invariant 7).
    private static final String COUNT_CHAT_MEMORY_SQL =
            "SELECT COUNT(*) FROM chat_memory WHERE user_id = ? AND scope_kind = ? AND scope_id = ?";

    private static final String COUNT_CHAT_SESSION_SQL =
            "SELECT COUNT(*) FROM chat_session WHERE user_id = ? AND scope_kind = ? AND scope_id = ?";

    private static final String COUNT_SUMMARY_ANCHOR_SQL =
            "SELECT COUNT(*) FROM summary_anchor "
                    + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
                    + "  AND command_kind = 'personal'";

    private static final String COUNT_SAVED_POST_SQL =
            "SELECT COUNT(*) FROM saved_post WHERE user_id = ?";

    // Per-scope: chat_memory rows for (caller, calling_scope).
    private static final String DELETE_CHAT_MEMORY_SQL =
            "DELETE FROM chat_memory WHERE user_id = ? AND scope_kind = ? AND scope_id = ?";

    // Per-scope: chat_session rows for (caller, calling_scope).
    // ON DELETE CASCADE on chat_message FK handles the child rows.
    private static final String DELETE_CHAT_SESSION_SQL =
            "DELETE FROM chat_session WHERE user_id = ? AND scope_kind = ? AND scope_id = ?";

    // Per-scope: personal summary_anchor rows only. Digest anchors
    // (user_id IS NULL) are excluded by the user_id = ? predicate plus
    // the command_kind guard.
    private static final String DELETE_SUMMARY_ANCHOR_SQL =
            "DELETE FROM summary_anchor "
                    + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
                    + "  AND command_kind = 'personal'";

    // Global: saved_post rows for the caller regardless of scope (D13).
    private static final String DELETE_SAVED_POST_SQL =
            "DELETE FROM saved_post WHERE user_id = ?";

    // Remaining-scopes count: distinct scopes (other than the calling
    // scope) where the user still has chat-tier rows AFTER the purge.
    // A scope is the (scope_kind, scope_id) pair — all three tables
    // carry the discriminator, so a DM scope and a group scope whose
    // UUIDs collide count as two scopes. Runs inside the same
    // transaction so the count reflects the post-purge state.
    private static final String COUNT_REMAINING_SCOPES_SQL =
            "SELECT COUNT(DISTINCT (scope_kind, scope_id)) FROM ("
                    + "  SELECT scope_kind, scope_id FROM chat_memory"
                    + "    WHERE user_id = ? AND NOT (scope_kind = ? AND scope_id = ?)"
                    + "  UNION ALL"
                    + "  SELECT scope_kind, scope_id FROM chat_session"
                    + "    WHERE user_id = ? AND NOT (scope_kind = ? AND scope_id = ?)"
                    + "  UNION ALL"
                    + "  SELECT scope_kind, scope_id FROM summary_anchor"
                    + "    WHERE user_id = ? AND NOT (scope_kind = ? AND scope_id = ?)"
                    + "      AND command_kind = 'personal'"
                    + ") remaining";

    /**
     * Pre-count rows that will be purged. Called before the audit
     * row is written (Invariant 7: audit-before-effect).
     */
    public PurgeResult preCount(Connection conn,
                                UUID userId,
                                String scopeKind,
                                UUID scopeId) throws SQLException {
        int chatMemoryCount = countScoped(conn, COUNT_CHAT_MEMORY_SQL,
                userId, scopeKind, scopeId);
        int chatSessionCount = countScoped(conn, COUNT_CHAT_SESSION_SQL,
                userId, scopeKind, scopeId);
        int summaryAnchorCount = countScoped(conn, COUNT_SUMMARY_ANCHOR_SQL,
                userId, scopeKind, scopeId);
        int savedPostCount = countSavedPost(conn, userId);
        return new PurgeResult(chatMemoryCount, chatSessionCount,
                summaryAnchorCount, savedPostCount);
    }

    /**
     * Execute the four-table purge inside the caller's transaction.
     * Called after the audit row has been written.
     */
    public PurgeResult purge(Connection conn,
                             UUID userId,
                             String scopeKind,
                             UUID scopeId) throws SQLException {
        int chatMemoryCount = deleteScoped(conn, DELETE_CHAT_MEMORY_SQL,
                userId, scopeKind, scopeId);
        int chatSessionCount = deleteScoped(conn, DELETE_CHAT_SESSION_SQL,
                userId, scopeKind, scopeId);
        int summaryAnchorCount = deleteScoped(conn, DELETE_SUMMARY_ANCHOR_SQL,
                userId, scopeKind, scopeId);
        int savedPostCount = deleteSavedPost(conn, userId);
        return new PurgeResult(chatMemoryCount, chatSessionCount,
                summaryAnchorCount, savedPostCount);
    }

    /**
     * Count distinct scopes (other than the calling scope) where the
     * user still has chat-tier rows. Runs inside the caller's
     * transaction so the count reflects the post-purge state.
     */
    public int countRemainingScopes(Connection conn,
                                    UUID userId,
                                    String scopeKind,
                                    UUID scopeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_REMAINING_SCOPES_SQL)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setObject(4, userId);
            ps.setString(5, scopeKind);
            ps.setObject(6, scopeId);
            ps.setObject(7, userId);
            ps.setString(8, scopeKind);
            ps.setObject(9, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countScoped(Connection conn, String sql,
                            UUID userId, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countSavedPost(Connection conn, UUID userId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_SAVED_POST_SQL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int deleteScoped(Connection conn, String sql,
                             UUID userId, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            return ps.executeUpdate();
        }
    }

    private int deleteSavedPost(Connection conn, UUID userId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_SAVED_POST_SQL)) {
            ps.setObject(1, userId);
            return ps.executeUpdate();
        }
    }

    public record PurgeResult(int chatMemoryCount,
                               int chatSessionCount,
                               int summaryAnchorCount,
                               int savedPostCount) {
        public int total() {
            return chatMemoryCount + chatSessionCount
                    + summaryAnchorCount + savedPostCount;
        }
    }
}
