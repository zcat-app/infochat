package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link RecallMemoryTool}'s result byte
 * budgets: each entry's summary is bounded at
 * {@link RecallMemoryTool#MAX_SUMMARY_BYTES} and the aggregate JSON at
 * {@link RecallMemoryTool#MAX_RESULT_BYTES}, dropping oldest entries
 * first (the query's newest-first ordering is preserved). Seeds
 * fixtures directly via JDBC against the &#64;QuarkusTest DevServices
 * DB.
 */
@QuarkusTest
class RecallMemoryToolTest {

    private static final String PREFIX = "recall-mem-test/";
    private static final Instant BASE = Instant.parse("2026-05-22T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    RecallMemoryTool tool;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                "DELETE FROM chat_memory WHERE user_id IN "
                    + "(SELECT id FROM users WHERE contact_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void oversizedSummaryComesBackBoundedWithTruncationMarker() throws Exception {
        UUID userId = seedUser("oversize");
        // 1 KiB past the per-entry budget; single-byte chars.
        String oversizedSummary =
                "b".repeat(RecallMemoryTool.MAX_SUMMARY_BYTES + 1024);
        seedMemory(userId, oversizedSummary, BASE);

        String json = tool.execute(userId, "dm", userId,
            Map.of("keywords", List.of(PREFIX + "kw")));

        assertTrue(json.contains(GetPostTool.TRUNCATION_MARKER),
            "an over-budget summary carries the explicit truncation marker");
        assertTrue(json.contains("b".repeat(RecallMemoryTool.MAX_SUMMARY_BYTES)
                + GetPostTool.TRUNCATION_MARKER),
            "the summary is cut exactly at MAX_SUMMARY_BYTES, marker appended");
        assertFalse(json.contains("b".repeat(RecallMemoryTool.MAX_SUMMARY_BYTES + 1)),
            "no byte past the per-entry budget reaches the result");
    }

    @Test
    void aggregateResultIsBoundedDroppingOldestEntries() throws Exception {
        UUID userId = seedUser("aggregate");
        // 12 entries at ~2 KiB each ≈ 25 KiB raw — well past the 16 KiB
        // aggregate budget. mem-0 is newest (created_at DESC ordering),
        // mem-11 oldest; the tail must be the part that's dropped.
        for (int i = 0; i < 12; i++) {
            String summary = "mem-" + i + " "
                    + "c".repeat(RecallMemoryTool.MAX_SUMMARY_BYTES - 16);
            seedMemory(userId, summary, BASE.minus(Duration.ofMinutes(i)));
        }

        String json = tool.execute(userId, "dm", userId,
            Map.of("keywords", List.of(PREFIX + "kw")));

        assertTrue(json.getBytes(StandardCharsets.UTF_8).length
                <= RecallMemoryTool.MAX_RESULT_BYTES,
            "aggregate result stays within MAX_RESULT_BYTES; was "
                + json.getBytes(StandardCharsets.UTF_8).length + " bytes");
        assertTrue(json.contains("mem-0 "),
            "the newest entry survives the aggregate bound");
        assertFalse(json.contains("mem-11 "),
            "the oldest entry is dropped once the budget is exhausted");
    }

    // ---------- helpers ----------

    private UUID seedUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                     + "VALUES ('inmemory', ?, FALSE, 'vouched') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedMemory(UUID userId, String summary, Instant createdAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO chat_memory (user_id, scope_kind, scope_id, created_at, "
                     + "summary, keywords) VALUES (?, 'dm', ?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setObject(2, userId);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            ps.setString(4, summary);
            ps.setArray(5, conn.createArrayOf("TEXT", new String[] { PREFIX + "kw" }));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
