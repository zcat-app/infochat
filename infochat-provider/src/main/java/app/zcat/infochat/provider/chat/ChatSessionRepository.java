package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

// Persists chat turns (user + assistant) as chat_message rows and creates
// a chat_session on first message per (user, scope). The DB trigger
// trg_chat_session_counters manages next_seq and token_count — this code
// reads next_seq to supply the seq column but does NOT increment it.
@ApplicationScoped
public class ChatSessionRepository {

    private static final String UPSERT_SESSION =
            "INSERT INTO chat_session (user_id, scope_kind, scope_id) "
          + "VALUES (?, ?, ?) "
          + "ON CONFLICT (user_id, scope_kind, scope_id) DO NOTHING";

    // FOR UPDATE serializes concurrent persistTurn callers on the session
    // row: without it two writers read the same next_seq and the second
    // INSERT collides on the chat_message PK. The lock also serializes
    // against /clear and /compress resetting next_seq = 0 directly.
    private static final String SELECT_NEXT_SEQ =
            "SELECT next_seq FROM chat_session "
          + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
          + "FOR UPDATE";

    private static final String INSERT_MESSAGE =
            "INSERT INTO chat_message (user_id, scope_kind, scope_id, seq, role, content, tokens) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final DataSource dataSource;

    @Inject
    public ChatSessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Persist a single chat turn. Creates the session row on first call
     * for this (user, scope). The seq value is read from chat_session.next_seq
     * and the DB trigger increments it after our INSERT.
     *
     * @return the seq number assigned to this message
     */
    public int persistTurn(UUID userId, String scopeKind,
                           UUID scopeId, String role,
                           String content, int tokens) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Ensure session exists (idempotent upsert)
                try (PreparedStatement ps = conn.prepareStatement(UPSERT_SESSION)) {
                    ps.setObject(1, userId);
                    ps.setString(2, scopeKind);
                    ps.setObject(3, scopeId);
                    ps.executeUpdate();
                }

                // Read current next_seq, locking the session row until commit
                int seq;
                try (PreparedStatement ps = conn.prepareStatement(SELECT_NEXT_SEQ)) {
                    ps.setObject(1, userId);
                    ps.setString(2, scopeKind);
                    ps.setObject(3, scopeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        seq = rs.getInt("next_seq");
                    }
                }

                // Insert the message row — the AFTER INSERT trigger
                // increments next_seq and adds to token_count
                try (PreparedStatement ps = conn.prepareStatement(INSERT_MESSAGE)) {
                    ps.setObject(1, userId);
                    ps.setString(2, scopeKind);
                    ps.setObject(3, scopeId);
                    ps.setInt(4, seq);
                    ps.setString(5, role);
                    ps.setString(6, content);
                    ps.setInt(7, tokens);
                    ps.executeUpdate();
                }

                conn.commit();
                return seq;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ChatSessionRepository.persistTurn failed", e);
        }
    }

    /**
     * Rough token estimate: characters / 4, minimum 1. Adequate for the
     * session token_count bookkeeping; M1-064 auto-compress can refine.
     */
    public static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
