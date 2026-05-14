package io.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-level assertions over the §2.3.1 partitioned {@code post}
 * table:
 * <ul>
 *   <li>An INSERT with a {@code fetched_at} inside the bootstrap
 *       partition's range routes to {@code post_202605}; the row is
 *       visible via {@code SELECT FROM post_202605} and via
 *       {@code tableoid::regclass}.</li>
 *   <li>An INSERT with a {@code fetched_at} OUTSIDE every existing
 *       partition's range raises an SQLException whose message
 *       indicates no partition was found (Postgres's default
 *       behavior on a partitioned table without a fallback —
 *       Invariant 6 forbids a DEFAULT partition).</li>
 *   <li>The {@code status} CHECK closes
 *       {@code ('RAW','READY','QUARANTINED','NEEDS_REVIEW')} —
 *       {@code 'EVALUATING'} is rejected (Invariant 5).</li>
 *   <li>All seven per-stage cursor flags are NOT NULL and default
 *       to {@code FALSE}.</li>
 *   <li>The {@code (uid, fetched_at)} UNIQUE rejects a duplicate
 *       within the same partition (SQLState 23505).</li>
 *   <li>The {@code idx_post_tags_gin} GIN index on
 *       {@code tags TEXT[]} exists.</li>
 * </ul>
 *
 * <p>Tests thread an explicit {@code fetched_at = '2026-05-15
 * 12:00:00+00'::timestamptz} (inside the bootstrap partition's
 * {@code [2026-05-01, 2026-06-01)} range) rather than relying on the
 * column default — that pins the test against CI month boundaries.
 */
class PostPartitioningTest extends PostgresSchemaTestBase {

    private static final String IN_RANGE_FETCHED_AT  = "2026-05-15 12:00:00+00";
    private static final String OUT_OF_RANGE_FETCHED_AT = "2025-01-15 12:00:00+00";

    @Test
    void inRangeInsertRoutesToBootstrapPartition() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);
            String uid = uniqueUid();

            insertPost(c, sourceId, uid, IN_RANGE_FETCHED_AT);

            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT tableoid::regclass::text AS partition_name "
                            + "FROM post WHERE uid = ?")) {
                sel.setString(1, uid);
                try (ResultSet rs = sel.executeQuery()) {
                    assertTrue(rs.next(), "expected one row from post");
                    assertEquals("post_202605", rs.getString("partition_name"),
                            "expected the May-2026 fetched_at to route to post_202605");
                }
            }

            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT title FROM post_202605 WHERE uid = ?")) {
                sel.setString(1, uid);
                try (ResultSet rs = sel.executeQuery()) {
                    assertTrue(rs.next(),
                            "expected SELECT FROM post_202605 (partition) to find the row");
                    assertNotNull(rs.getString("title"));
                }
            }
        }
    }

    @Test
    void outOfRangeInsertRaisesNoPartitionFound() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);

            SQLException ex = assertThrows(SQLException.class,
                    () -> insertPost(c, sourceId, uniqueUid(), OUT_OF_RANGE_FETCHED_AT));
            assertTrue(ex.getMessage().toLowerCase().contains("no partition"),
                    "expected 'no partition' in error message, got: " + ex.getMessage());
        }
    }

    @Test
    void statusCheckRejectsEvaluating() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);

            SQLException ex;
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO post (uid, source_id, title, status, fetched_at) "
                            + "VALUES (?, ?::uuid, ?, 'EVALUATING', ?::timestamptz)")) {
                stmt.setString(1, uniqueUid());
                stmt.setString(2, sourceId);
                stmt.setString(3, "test");
                stmt.setString(4, IN_RANGE_FETCHED_AT);
                ex = assertThrows(SQLException.class, stmt::executeUpdate);
            }
            assertEquals("23514", ex.getSQLState(),
                    "expected check_violation (23514) for status='EVALUATING', got: "
                            + ex.getSQLState() + " message: " + ex.getMessage());
        }
    }

    @Test
    void perStageFlagsDefaultToFalse() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);
            String uid = uniqueUid();

            insertPost(c, sourceId, uid, IN_RANGE_FETCHED_AT);

            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT stage1_done, stage2_done, tagger_done, embedding_done, "
                            + "stage1_flagged, stage2_failed, tagger_fallback "
                            + "FROM post WHERE uid = ?")) {
                sel.setString(1, uid);
                try (ResultSet rs = sel.executeQuery()) {
                    assertTrue(rs.next(), "expected one row");
                    assertEquals(Boolean.FALSE, rs.getBoolean("stage1_done"));
                    assertEquals(Boolean.FALSE, rs.getBoolean("stage2_done"));
                    assertEquals(Boolean.FALSE, rs.getBoolean("tagger_done"));
                    assertEquals(Boolean.FALSE, rs.getBoolean("embedding_done"));
                    assertEquals(Boolean.FALSE, rs.getBoolean("stage1_flagged"));
                    assertEquals(Boolean.FALSE, rs.getBoolean("stage2_failed"));
                    assertEquals(Boolean.FALSE, rs.getBoolean("tagger_fallback"));
                }
            }
        }
    }

    @Test
    void duplicateUidInSamePartitionRaisesUniqueViolation() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);
            String uid = uniqueUid();
            insertPost(c, sourceId, uid, IN_RANGE_FETCHED_AT);

            SQLException ex = assertThrows(SQLException.class,
                    () -> insertPost(c, sourceId, uid, IN_RANGE_FETCHED_AT));
            assertEquals("23505", ex.getSQLState(),
                    "expected unique_violation (23505) on (uid, fetched_at), got: "
                            + ex.getSQLState() + " message: " + ex.getMessage());
        }
    }

    @Test
    void ginIndexOnTagsExists() throws SQLException {
        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT indexdef FROM pg_indexes "
                             + "WHERE schemaname = 'public' "
                             + "AND tablename = 'post' "
                             + "AND indexname = 'idx_post_tags_gin'")) {
            assertTrue(rs.next(), "expected idx_post_tags_gin on post");
            String def = rs.getString("indexdef").toLowerCase();
            assertTrue(def.contains("using gin"), "expected USING gin, got: " + def);
            assertTrue(def.contains("(tags)"), "expected on (tags), got: " + def);
        }
    }

    private static String uniqueUid() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static String insertSource(Connection c) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            stmt.setString(1, "rss");
            stmt.setString(2, "https://example.com/post-" + UUID.randomUUID());
            stmt.setString(3, "Test source");
            stmt.setString(4, "news");
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insertPost(Connection c, String sourceId, String uid,
                                   String fetchedAt) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO post (uid, source_id, title, fetched_at) "
                        + "VALUES (?, ?::uuid, ?, ?::timestamptz)")) {
            stmt.setString(1, uid);
            stmt.setString(2, sourceId);
            stmt.setString(3, "test post " + uid);
            stmt.setString(4, fetchedAt);
            stmt.executeUpdate();
        }
    }
}
