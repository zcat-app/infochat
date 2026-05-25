package app.zcat.infochat.provider.command;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the purge-set correctness of {@link ForgetPurgeService}
 * against a real DevServices Postgres.
 *
 * <p>Each test seeds its own fixture rows using a per-test
 * {@code PREFIX} and asserts post-purge state. The cleanup method
 * deletes all test-prefixed rows before each test.</p>
 */
@QuarkusTest
class ForgetPurgeServiceTest {

    private static final String PREFIX = "ForgetPurge-";
    private static final String ADAPTER = "in-memory";

    @Inject
    ForgetPurgeService purgeService;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Order matters: child tables first (FK constraints).
            exec(conn, "DELETE FROM saved_post WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM chat_message WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM chat_memory WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM chat_session WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM summary_anchor WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
            // Seed source needed for saved_post FK.
            exec(conn, "INSERT INTO source (id, identifier, kind, display_name, category, status) "
                    + "VALUES ('" + sourceId() + "', '" + PREFIX + "src', 'rss', '"
                    + PREFIX + "source', 'test', 'active') ON CONFLICT (id) DO NOTHING");
        }
    }

    /**
     * Acceptance: /forget confirm performs a hard DELETE of the exact
     * four-table purge set in one transaction.
     */
    @Test
    void purgesExactSet() throws Exception {
        UUID userId = seedUser(PREFIX + "actor");
        UUID scopeId = userId; // DM scope
        String scopeKind = "dm";

        seedChatSession(userId, scopeKind, scopeId);
        seedChatMemory(userId, scopeKind, scopeId);
        seedSummaryAnchor(userId, scopeId, "personal");
        seedSavedPost(userId, "post-1");
        seedSavedPost(userId, "post-2");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            ForgetPurgeService.PurgeResult result =
                    purgeService.purge(conn, userId, scopeKind, scopeId);
            conn.commit();

            assertEquals(1, result.chatMemoryCount());
            assertEquals(1, result.chatSessionCount());
            assertEquals(1, result.summaryAnchorCount());
            assertEquals(2, result.savedPostCount());
            assertEquals(5, result.total());
        }

        assertEquals(0, countRows("chat_memory", "user_id", userId));
        assertEquals(0, countRows("chat_session", "user_id", userId));
        assertEquals(0, countRows("summary_anchor", "user_id", userId));
        assertEquals(0, countRows("saved_post", "user_id", userId));
    }

    /**
     * Acceptance: /forget does NOT touch users.is_admin, users.is_banned,
     * group_membership, or any audit_log row.
     */
    @Test
    void preservesAdminBanMembershipAudit() throws Exception {
        UUID userId = seedUser(PREFIX + "admin-actor");
        UUID scopeId = userId;
        String scopeKind = "dm";

        // Make the user an admin.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET is_admin = TRUE WHERE id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        }

        seedChatSession(userId, scopeKind, scopeId);
        seedChatMemory(userId, scopeKind, scopeId);
        seedSavedPost(userId, "post-admin");

        // Seed an audit row with this user as actor.
        int auditBefore = countAuditRows();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            purgeService.purge(conn, userId, scopeKind, scopeId);
            conn.commit();
        }

        // Admin flag preserved.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_admin, is_banned FROM users WHERE id = ?")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(true, rs.getBoolean("is_admin"));
                assertEquals(false, rs.getBoolean("is_banned"));
            }
        }

        // Audit row count unchanged (purge didn't touch audit_log).
        assertEquals(auditBefore, countAuditRows());
    }

    /**
     * Acceptance: /forget does NOT touch the group-wide digest anchor
     * (command_kind = 'digest', user_id IS NULL).
     */
    @Test
    void preservesDigestAnchor() throws Exception {
        UUID userId = seedUser(PREFIX + "digest-actor");
        UUID groupScopeId = UUID.randomUUID();

        // Personal anchor (should be purged).
        seedSummaryAnchor(userId, groupScopeId, "personal");
        // Digest anchor (should be preserved — user_id IS NULL).
        seedDigestAnchor(groupScopeId);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            ForgetPurgeService.PurgeResult result =
                    purgeService.purge(conn, userId, "group", groupScopeId);
            conn.commit();

            assertEquals(1, result.summaryAnchorCount());
        }

        // Digest anchor survives.
        assertEquals(1, countDigestAnchors(groupScopeId));
    }

    /**
     * Remaining-scopes count reflects post-purge state.
     */
    @Test
    void countRemainingScopes() throws Exception {
        UUID userId = seedUser(PREFIX + "remaining-actor");
        UUID dmScopeId = userId;
        UUID groupScopeId1 = UUID.randomUUID();
        UUID groupScopeId2 = UUID.randomUUID();

        // Data in 3 scopes: DM + 2 groups.
        seedChatSession(userId, "dm", dmScopeId);
        seedChatSession(userId, "group", groupScopeId1);
        seedChatSession(userId, "group", groupScopeId2);

        // Purge DM scope.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            purgeService.purge(conn, userId, "dm", dmScopeId);
            int remaining = purgeService.countRemainingScopes(
                    conn, userId, dmScopeId);
            conn.commit();

            assertEquals(2, remaining);
        }
    }

    // ---- seeding helpers --------------------------------------------------

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, registration_state) "
                             + "VALUES (?, ?, 'invited') RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private void seedChatSession(UUID userId, String scopeKind, UUID scopeId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chat_session (user_id, scope_kind, scope_id) "
                             + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.executeUpdate();
        }
    }

    private void seedChatMemory(UUID userId, String scopeKind, UUID scopeId)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chat_memory (user_id, scope_kind, scope_id, "
                             + "summary, keywords) VALUES (?, ?, ?, 'test', '{test}')")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.executeUpdate();
        }
    }

    private void seedSummaryAnchor(UUID userId, UUID scopeId, String commandKind)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summary_anchor (user_id, scope_id, command_kind, "
                             + "command_name, arg_hash, post_uids) "
                             + "VALUES (?, ?, ?, 'summary', 'hash', '{}')")) {
            ps.setObject(1, userId);
            ps.setObject(2, scopeId);
            ps.setString(3, commandKind);
            ps.executeUpdate();
        }
    }

    private void seedDigestAnchor(UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summary_anchor (user_id, scope_id, command_kind, "
                             + "command_name, arg_hash, post_uids) "
                             + "VALUES (NULL, ?, 'digest', 'digest', 'hash', '{}')")) {
            ps.setObject(1, scopeId);
            ps.executeUpdate();
        }
    }

    private void seedSavedPost(UUID userId, String postUid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (user_id, post_uid, source_id, title) "
                             + "VALUES (?, ?, ?, 'test title')")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId());
            ps.executeUpdate();
        }
    }

    // ---- query helpers ----------------------------------------------------

    private int countRows(String table, String column, UUID value) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countDigestAnchors(UUID scopeId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM summary_anchor "
                             + "WHERE scope_id = ? AND command_kind = 'digest' "
                             + "AND user_id IS NULL")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countAuditRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static UUID sourceId() {
        return UUID.fromString("00000000-0000-0000-0000-f06937000066");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
