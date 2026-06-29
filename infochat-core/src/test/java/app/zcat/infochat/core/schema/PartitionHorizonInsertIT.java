package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tripwire for the post-partition horizon (M1-479).
 *
 * <p>The infochat-core test datasource boots Flyway — V30 provisions post
 * partitions only through July 2026 (upper bound exclusive '2026-08-01') and
 * there is deliberately no DEFAULT partition — but it never runs the collector's
 * PartitionCreator scheduler. So a post insert whose {@code fetched_at} is at or
 * beyond the horizon fails with "no partition of relation post found for row"
 * until a covering partition exists.
 *
 * <p>This test demonstrates both halves so the cliff cannot silently return:
 * the beyond-horizon insert fails while no partition covers it, and the same
 * insert succeeds once the partition is provisioned (the remedy PartitionCreator
 * applies in production). If a future change added a DEFAULT partition or removed
 * the no-fallback invariant, the first assertion would trip.
 */
class PartitionHorizonInsertIT extends PostgresSchemaTestBase {

    // A fetched_at strictly beyond V30's horizon (>= '2026-08-01'). Far-future so
    // the throwaway partition created below can never collide with a real
    // migration's monthly bucket.
    private static final String BEYOND_HORIZON_FETCHED_AT = "2099-01-15 00:00:00+00";

    @Test
    void beyondHorizonInsertFailsWithoutPartitionThenSucceedsWithOne() throws SQLException {
        try (Connection c = newConnection()) {
            UUID sourceId = insertSource(c);

            // Without a covering partition, the beyond-horizon insert is the cliff.
            // (autoCommit is on, so the failed statement does not poison the
            // connection — the remedy below runs on the same Connection.)
            SQLException cliff = assertThrows(SQLException.class,
                    () -> insertPost(c, sourceId));
            assertTrue(cliff.getMessage().contains("no partition of relation"),
                    "expected the partition cliff, got: " + cliff.getMessage());

            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS post_209901 PARTITION OF post "
                        + "FOR VALUES FROM ('2099-01-01 00:00:00+00') "
                        + "TO ('2099-02-01 00:00:00+00')");
            }
            try {
                UUID postId = insertPost(c, sourceId);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM post WHERE id = ?")) {
                    ps.setObject(1, postId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(1, rs.getInt(1),
                                "beyond-horizon insert must land once the partition exists");
                    }
                }
            } finally {
                // Leave the singleton container clean for sibling test classes.
                try (Statement s = c.createStatement()) {
                    s.execute("DROP TABLE IF EXISTS post_209901");
                }
            }
        }
    }

    private static UUID insertSource(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES ('rss', 'https://example.com/horizon', 'Horizon', 'news') "
                        + "RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    private static UUID insertPost(Connection c, UUID sourceId) throws SQLException {
        UUID postId = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO post (id, source_id, fetched_at, uid, title, upstream_identifier) "
                        + "VALUES (?, ?, ?::timestamptz, ?, 'Horizon', ?)")) {
            ps.setObject(1, postId);
            ps.setObject(2, sourceId);
            ps.setString(3, BEYOND_HORIZON_FETCHED_AT);
            ps.setString(4, "uid-" + postId);
            ps.setString(5, "uid-" + postId);
            ps.executeUpdate();
        }
        return postId;
    }
}
