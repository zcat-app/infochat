package app.zcat.infochat.provider.scheduler;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that ChatMemoryPruner deletes rows older than the retention
 * horizon and preserves recent rows (Invariant 9).
 */
@QuarkusTest
class ChatMemoryPrunerTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ChatMemoryPruner pruner;

    private final UUID userId = UUID.randomUUID();
    private final UUID scopeId = UUID.randomUUID();

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM chat_message");
            exec(conn, "DELETE FROM chat_session");
            exec(conn, "DELETE FROM chat_memory");
            exec(conn, "DELETE FROM summary_anchor");
            insertUser(conn, userId);
        }
    }

    @Test
    void prunesExpiredRows() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertChatMemory(conn, userId, scopeId, "now() - interval '200 days'");
            insertChatSession(conn, userId, scopeId, "now() - interval '200 days'");
            insertChatMessage(conn, userId, scopeId, 0, "now() - interval '200 days'");
            // The counter trigger resets chat_session.updated_at to now() on
            // message INSERT; restore the old timestamp so the pruner sees it.
            resetSessionUpdatedAt(conn, userId, scopeId, "now() - interval '200 days'");
            insertSummaryAnchor(conn, userId, scopeId, "now() - interval '200 days'");
        }

        pruner.prune();

        try (Connection conn = dataSource.getConnection()) {
            assertEquals(0, count(conn, "chat_memory"));
            assertEquals(0, count(conn, "chat_session"));
            assertEquals(0, count(conn, "chat_message"));
            assertEquals(0, count(conn, "summary_anchor"));
        }
    }

    @Test
    void preservesRecentRows() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertChatMemory(conn, userId, scopeId, "now()");
            insertChatSession(conn, userId, scopeId, "now()");
            insertChatMessage(conn, userId, scopeId, 0, "now()");
            insertSummaryAnchor(conn, userId, scopeId, "now()");
        }

        pruner.prune();

        try (Connection conn = dataSource.getConnection()) {
            assertEquals(1, count(conn, "chat_memory"));
            assertEquals(1, count(conn, "chat_session"));
            assertEquals(1, count(conn, "chat_message"));
            assertEquals(1, count(conn, "summary_anchor"));
        }
    }

    private void insertUser(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, registration_state)"
                    + " VALUES (?, 'test', ?, 'vouched')"
                    + " ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, id);
            ps.setString(2, id.toString());
            ps.executeUpdate();
        }
    }

    private void insertChatMemory(Connection conn, UUID uid, UUID sid, String tsExpr)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chat_memory (user_id, scope_kind, scope_id, created_at, summary, keywords)"
                    + " VALUES (?, 'dm', ?, " + tsExpr + ", 'test summary', '{\"test\"}')")) {
            ps.setObject(1, uid);
            ps.setObject(2, sid);
            ps.executeUpdate();
        }
    }

    private void insertChatSession(Connection conn, UUID uid, UUID sid, String tsExpr)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chat_session (user_id, scope_kind, scope_id, updated_at)"
                    + " VALUES (?, 'dm', ?, " + tsExpr + ")"
                    + " ON CONFLICT (user_id, scope_kind, scope_id) DO UPDATE"
                    + " SET updated_at = " + tsExpr)) {
            ps.setObject(1, uid);
            ps.setObject(2, sid);
            ps.executeUpdate();
        }
    }

    private void insertChatMessage(Connection conn, UUID uid, UUID sid, int seq, String tsExpr)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chat_message (user_id, scope_kind, scope_id, seq, role, content, tokens, ts)"
                    + " VALUES (?, 'dm', ?, ?, 'user', 'hello', 5, " + tsExpr + ")")) {
            ps.setObject(1, uid);
            ps.setObject(2, sid);
            ps.setInt(3, seq);
            ps.executeUpdate();
        }
    }

    private void insertSummaryAnchor(Connection conn, UUID uid, UUID sid, String tsExpr)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO summary_anchor (user_id, scope_kind, scope_id, command_kind, command_name, arg_hash, post_uids, generated_at)"
                    + " VALUES (?, 'dm', ?, 'personal', '/summary', 'abc123', '{}', " + tsExpr + ")")) {
            ps.setObject(1, uid);
            ps.setObject(2, sid);
            ps.executeUpdate();
        }
    }

    private void resetSessionUpdatedAt(Connection conn, UUID uid, UUID sid, String tsExpr)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE chat_session SET updated_at = " + tsExpr
                    + " WHERE user_id = ? AND scope_kind = 'dm' AND scope_id = ?")) {
            ps.setObject(1, uid);
            ps.setObject(2, sid);
            ps.executeUpdate();
        }
    }

    private int count(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
