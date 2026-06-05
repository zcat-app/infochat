package app.zcat.infochat.provider.chat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @QuarkusTest because the DB trigger trg_chat_session_counters is
// central to acceptance item 3 — testing it requires a real database.
@QuarkusTest
class ChatSessionRepositoryTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject ChatSessionRepository repository;

    @BeforeEach
    void seedUser() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Ensure a users row exists for the FK on chat_session
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (id, adapter, contact_id, registration_state) "
                  + "VALUES (?, 'inmemory', ?, 'vouched') "
                  + "ON CONFLICT (id) DO NOTHING")) {
                ps.setObject(1, USER_ID);
                ps.setString(2, "csr-" + USER_ID);
                ps.executeUpdate();
            }
            // Clean up any prior test session/messages
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chat_session WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
                ps.setObject(1, USER_ID);
                ps.setString(2, SCOPE_KIND);
                ps.setObject(3, SCOPE_ID);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void persistsTurnsAndCreatesSession() throws Exception {
        int seq0 = repository.persistTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "user", "hello", 2);
        assertEquals(0, seq0);

        // DB trigger should have incremented next_seq to 1
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT next_seq, token_count FROM chat_session "
                   + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
            ps.setObject(1, USER_ID);
            ps.setString(2, SCOPE_KIND);
            ps.setObject(3, SCOPE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "chat_session row should exist");
                assertEquals(1, rs.getInt("next_seq"));
                assertEquals(2, rs.getInt("token_count"));
            }
        }

        // Second turn
        int seq1 = repository.persistTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "assistant", "hi there", 3);
        assertEquals(1, seq1);

        // Verify the message row
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) AS cnt FROM chat_message "
                   + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
            ps.setObject(1, USER_ID);
            ps.setString(2, SCOPE_KIND);
            ps.setObject(3, SCOPE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(2, rs.getInt("cnt"));
            }
        }
    }

    @Test
    void subsequentTurnsReuseExistingSession() throws Exception {
        repository.persistTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "user", "first", 1);
        repository.persistTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "assistant", "second", 2);
        repository.persistTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "user", "third", 1);

        // Should still be a single chat_session row
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) AS cnt FROM chat_session "
                   + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
            ps.setObject(1, USER_ID);
            ps.setString(2, SCOPE_KIND);
            ps.setObject(3, SCOPE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt("cnt"));
            }
        }

        // next_seq should be 3, token_count should be 4
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT next_seq, token_count FROM chat_session "
                   + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ?")) {
            ps.setObject(1, USER_ID);
            ps.setString(2, SCOPE_KIND);
            ps.setObject(3, SCOPE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(3, rs.getInt("next_seq"));
                assertEquals(4, rs.getInt("token_count"));
            }
        }
    }

    @Test
    void estimateTokensReturnsMinimumOne() {
        assertEquals(1, ChatSessionRepository.estimateTokens(""));
        assertEquals(1, ChatSessionRepository.estimateTokens("hi"));
        assertEquals(2, ChatSessionRepository.estimateTokens("12345678"));
    }
}
