package app.zcat.infochat.collector.partition;

import app.zcat.infochat.collector.partition.PartitionDdl.PartitionedTable;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and drives
 * {@link PartitionPruner#onTick()} — the clock-reading entry — to prove the
 * retention cutoff and active-month floor guard gate on that instant, not on
 * the wall-clock run date. Complements {@code PartitionInsertIT}, whose pruner
 * test calls {@code pruneOnce(activeMonth, now)} with explicit arguments.
 * (M1-449)
 *
 * <p>The pin is 2020-03-15, BEFORE every real partition in the migrated DB, so
 * the floor guard makes all 2026+ partitions unselectable and only the two
 * synthetic 2020 partitions are ever considered — bootstrap partitions other
 * tests rely on cannot be collateral damage. The {@code post_202002}-survives
 * assertion is the discriminating one: under any real wall clock (2026+),
 * February 2020 is far past the 30-day post horizon and {@code onTick} would
 * drop it, so its survival proves the prune read the injected Clock.
 */
@QuarkusTest
class PartitionPrunerClockIT {

    // 30-day post horizon, now=2020-03-15 → cutoff 2020-02-14: January 2020
    // (ends 02-01) is aged, February 2020 (ends 03-01) is in-horizon.
    private static final Instant PINNED_NOW = Instant.parse("2020-03-15T00:00:00Z");

    // The seed datasource is the owner-role seam (SeedDataSourceProducer), so
    // partition DDL issued here has the parent-table ownership PartitionPruner
    // gets from its named "owner" datasource.
    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    PartitionPruner partitionPruner;

    @BeforeEach
    void pinClock() {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        // IF EXISTS makes this a no-op for the partition onTick already dropped.
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(PartitionDdl.dropPartition("post_202001"));
            stmt.execute(PartitionDdl.dropPartition("post_202002"));
        }
    }

    @Test
    void onTickPrunesAgainstInjectedClockInstant() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(PartitionDdl.createPartition(PartitionedTable.POST, YearMonth.of(2020, 1)));
            stmt.execute(PartitionDdl.createPartition(PartitionedTable.POST, YearMonth.of(2020, 2)));
        }
        assertTrue(partitionExists("post_202001"), "precondition: aged partition exists");
        assertTrue(partitionExists("post_202002"), "precondition: in-horizon partition exists");

        partitionPruner.onTick();

        assertFalse(partitionExists("post_202001"),
            "onTick must drop a partition past the retention horizon under the pinned clock");
        assertTrue(partitionExists("post_202002"),
            "onTick must keep the in-horizon partition: under a 2026+ wall clock February 2020 "
                + "would be dropped, so its survival proves the prune read the injected Clock");
        assertTrue(partitionExists("post_202606"),
            "partitions at or after the pinned active month must survive the prune");
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
}
