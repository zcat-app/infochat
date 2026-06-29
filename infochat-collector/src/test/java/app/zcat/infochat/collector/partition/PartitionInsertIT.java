package app.zcat.infochat.collector.partition;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.partition.PartitionDdl.PartitionedTable;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partition lifecycle against the live DB. Proves the V30 migration
 * provisioned the June 2026 partition (an INSERT into {@code post} with
 * {@code fetched_at = '2026-06-15'} must succeed against a fresh
 * Flyway-migrated DB — before V30 this failed with "no partition of relation
 * post found for row"), that startup provisioning repairs a missing
 * active-month partition, and that {@link PartitionPruner} drops aged
 * partitions while in-horizon ones survive.
 */
@QuarkusTest
class PartitionInsertIT {

    private static final String UID = "partition-it-uid";
    private static final Instant JUNE_FETCHED_AT = Instant.parse("2026-06-15T12:00:00Z");

    // The seed datasource is the owner-role seam (SeedDataSourceProducer), so
    // partition DDL issued by these tests has parent-table ownership — the
    // same privilege PartitionCreator/PartitionPruner get from the named
    // "owner" datasource.
    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PartitionCreator partitionCreator;

    @Inject
    PartitionPruner partitionPruner;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM post WHERE uid = ?")) {
            ps.setString(1, UID);
            ps.executeUpdate();
        }
        // Synthetic partitions from the pruner test; IF EXISTS makes this a
        // no-op for the tests that never created them.
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(PartitionDdl.dropPartition("post_202001"));
            stmt.execute(PartitionDdl.dropPartition("post_202002"));
        }
    }

    @Test
    void insertIntoJunePartitionSucceeds() throws Exception {
        UUID sourceId = seedSource();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, fetched_at, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?) RETURNING fetched_at")) {
            ps.setString(1, UID);
            ps.setObject(2, sourceId);
            ps.setString(3, "Partition IT title");
            ps.setTimestamp(4, Timestamp.from(JUNE_FETCHED_AT));
            ps.setString(5, UID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT into the June partition must yield a row");
                assertEquals(JUNE_FETCHED_AT, rs.getTimestamp(1).toInstant(),
                    "the June-dated row must be stored as written");
            }
        }
    }

    @Test
    void freshStartProvisionsActiveMonthPartitionOnEveryTable() throws Exception {
        YearMonth active = YearMonth.now(ZoneOffset.UTC);
        // Simulate the state a restart across a month boundary sees: the
        // active month's partition is missing. price_snapshot is the
        // lowest-blast-radius table to drop — recreation happens synchronously
        // below, before any other test runs.
        String activePartition = PartitionedTable.PRICE_SNAPSHOT.partitionName(active);
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(PartitionDdl.dropPartition(activePartition));
        }
        assertFalse(partitionExists(activePartition),
            "precondition: the active-month partition is gone");

        partitionCreator.onStart(new StartupEvent());

        for (PartitionedTable table : PartitionedTable.values()) {
            assertTrue(partitionExists(table.partitionName(active)),
                "startup must provision the active month on " + table.parentTable());
            assertTrue(partitionExists(table.partitionName(active.plusMonths(1))),
                "startup must provision the next month on " + table.parentTable());
        }
    }

    @Test
    void prunerDropsAgedPartitionAndKeepsInHorizonPartitions() throws Exception {
        // The clock is pinned to an active month (2020-03) BEFORE every real
        // partition in the migrated DB: the floor guard makes all 2026+
        // partitions unselectable, so only the two synthetic 2020 partitions
        // can ever be considered — deterministic regardless of wall clock,
        // and the bootstrap partitions other tests insert into (e.g.
        // price_snapshot_p202605) cannot be collateral damage. With the
        // 30-day post horizon and now=2020-03-15, the cutoff is 2020-02-14:
        // January 2020 (ends 02-01) is aged, February 2020 (ends 03-01) is
        // in-horizon.
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(PartitionDdl.createPartition(PartitionedTable.POST, YearMonth.of(2020, 1)));
            stmt.execute(PartitionDdl.createPartition(PartitionedTable.POST, YearMonth.of(2020, 2)));
        }
        assertTrue(partitionExists("post_202001"), "precondition: aged partition exists");
        assertTrue(partitionExists("post_202002"), "precondition: in-horizon partition exists");

        partitionPruner.pruneOnce(YearMonth.of(2020, 3), Instant.parse("2020-03-15T00:00:00Z"));

        assertFalse(partitionExists("post_202001"),
            "a partition past the retention horizon must be dropped");
        assertTrue(partitionExists("post_202002"),
            "an in-horizon partition must survive the prune");
        assertTrue(partitionExists("post_202606"),
            "partitions at or after the active month must survive the prune");
    }

    private boolean partitionExists(String partitionName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                     + "WHERE c.relname = ? AND n.nspname = current_schema()")) {
            ps.setString(1, partitionName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
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
