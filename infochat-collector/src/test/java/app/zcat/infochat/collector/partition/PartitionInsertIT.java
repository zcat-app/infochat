package app.zcat.infochat.collector.partition;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the V30 migration provisioned the June 2026 partition: an INSERT into
 * {@code post} with {@code fetched_at = '2026-06-15'} must succeed against a
 * fresh Flyway-migrated DB. Before V30 this failed with "no partition of
 * relation post found for row" — the collector was dead on its first June
 * insert.
 */
@QuarkusTest
class PartitionInsertIT {

    private static final String UID = "partition-it-uid";
    private static final Instant JUNE_FETCHED_AT = Instant.parse("2026-06-15T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM post WHERE uid = ?")) {
            ps.setString(1, UID);
            ps.executeUpdate();
        }
    }

    @Test
    void insertIntoJunePartitionSucceeds() throws Exception {
        UUID sourceId = seedSource();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, fetched_at) "
                     + "VALUES (?, ?, ?, ?) RETURNING fetched_at")) {
            ps.setString(1, UID);
            ps.setObject(2, sourceId);
            ps.setString(3, "Partition IT title");
            ps.setTimestamp(4, Timestamp.from(JUNE_FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT into the June partition must yield a row");
                assertEquals(JUNE_FETCHED_AT, rs.getTimestamp(1).toInstant(),
                    "the June-dated row must be stored as written");
            }
        }
    }

    private UUID seedSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://partition-it.example.test/feed.xml");
            ps.setString(2, "Partition IT source");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }
}
