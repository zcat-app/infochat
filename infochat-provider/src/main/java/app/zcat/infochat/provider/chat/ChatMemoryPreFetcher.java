package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// Deterministic keyword match on chat_memory for a given (user, scope).
// Always runs before the LLM call; results fold into the agent prompt
// (llm.md §Memory retrieval — Pre-fetch).
@ApplicationScoped
public class ChatMemoryPreFetcher {

    public record MemoryHit(Instant createdAt,
                            String summary,
                            List<String> referencedPosts) {}

    private final DataSource dataSource;

    @Inject
    public ChatMemoryPreFetcher(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // Package-private for testing without a real DataSource. The null
    // assignment is the test seam: tests exercising only the no-DB path
    // (e.g. empty-keyword pre-fetch) never reach dataSource.
    @SuppressWarnings("NullAway")
    ChatMemoryPreFetcher() {
        this.dataSource = null;
    }

    public List<MemoryHit> preFetch(UUID userId,
                                              String scopeKind,
                                              UUID scopeId,
                                              String userMessage) {
        List<String> keywords = extractKeywords(userMessage);
        if (keywords.isEmpty()) {
            return List.of();
        }
        return queryMemories(userId, scopeKind, scopeId, keywords);
    }

    // Cheap keyword extraction: split, lowercase, drop short tokens.
    static List<String> extractKeywords(String message) {
        String[] tokens = message.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> keywords = new ArrayList<>();
        for (String token : tokens) {
            String clean = token.replaceAll("[^a-z0-9]", "");
            if (clean.length() >= 3) {
                keywords.add(clean);
            }
        }
        return keywords;
    }

    private List<MemoryHit> queryMemories(UUID userId, String scopeKind,
                                           UUID scopeId, List<String> keywords) {
        String sql = "SELECT created_at, summary, referenced_posts "
                   + "FROM chat_memory "
                   + "WHERE user_id = ? AND scope_kind = ? AND scope_id = ? "
                   + "AND keywords && ?::TEXT[] "
                   + "ORDER BY created_at DESC "
                   + "LIMIT 10";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setArray(4, conn.createArrayOf("TEXT", keywords.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                List<MemoryHit> results = new ArrayList<>();
                while (rs.next()) {
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    String summary = rs.getString("summary");
                    String[] refs = (String[]) rs.getArray("referenced_posts").getArray();
                    results.add(new MemoryHit(createdAt, summary, Arrays.asList(refs)));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ChatMemoryPreFetcher.queryMemories failed", e);
        }
    }
}
