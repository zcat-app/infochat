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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins derivative retention EQUAL to post retention (02-schema.md §2.4.4): under the pruner's one pure per-table predicate all four tables' same-month partitions must drop or survive together, so a post visible under post retention never loses its semantic surface. The 2020-03 clock pin precedes every real partition, leaving 2026+ partitions floor-guarded and only the synthetic 2020 months selectable.
 */
@QuarkusTest
class PartitionRetentionAlignmentIT {

    // now=2020-03-10 → 30-day cutoff 2020-02-09: January 2020 (ends 02-01)
    // is aged, February 2020 (ends 03-01) is in-horizon, on all four tables.
    private static final Instant PINNED_NOW = Instant.parse("2020-03-10T00:00:00Z");

    private static final List<PartitionedTable> ALIGNED_TABLES = List.of(
        PartitionedTable.POST,
        PartitionedTable.POST_EMBEDDING,
        PartitionedTable.POST_ENTITY,
        PartitionedTable.POST_REFERENCE);

    private static final YearMonth JANUARY = YearMonth.of(2020, 1);
    private static final YearMonth FEBRUARY = YearMonth.of(2020, 2);
    private static final YearMonth ACTIVE = YearMonth.of(2020, 3);
    private static final YearMonth NEXT = YearMonth.of(2020, 4);

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
        // IF EXISTS makes this a no-op for partitions onTick already dropped.
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (PartitionedTable table : ALIGNED_TABLES) {
                for (YearMonth month : List.of(JANUARY, FEBRUARY, ACTIVE, NEXT)) {
                    stmt.execute(PartitionDdl.dropPartition(table.partitionName(month)));
                }
            }
        }
    }

    @Test
    void previousMonthDerivativePartitionsSurviveAlongsidePost() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (PartitionedTable table : ALIGNED_TABLES) {
                stmt.execute(PartitionDdl.createPartition(table, FEBRUARY));
            }
        }

        partitionPruner.onTick();

        for (PartitionedTable table : ALIGNED_TABLES) {
            assertTrue(partitionExists(table.partitionName(FEBRUARY)),
                table.partitionName(FEBRUARY) + " must survive onTick alongside "
                    + "post_202002: equal retention under the same predicate keeps "
                    + "same-month partitions together");
        }
    }

    @Test
    void agedMonthStillDropsOnAllTables() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (PartitionedTable table : ALIGNED_TABLES) {
                stmt.execute(PartitionDdl.createPartition(table, JANUARY));
                stmt.execute(PartitionDdl.createPartition(table, ACTIVE));
                stmt.execute(PartitionDdl.createPartition(table, NEXT));
            }
        }

        partitionPruner.onTick();

        // Alignment moves the drop boundary; it must not disable aging, and
        // the never-drop floor guard must keep covering the derivative tables.
        for (PartitionedTable table : ALIGNED_TABLES) {
            assertFalse(partitionExists(table.partitionName(JANUARY)),
                table.partitionName(JANUARY) + " must still drop — retention "
                    + "stays live on every aligned table");
            assertTrue(partitionExists(table.partitionName(ACTIVE)),
                table.partitionName(ACTIVE) + " is the active month — the "
                    + "never-drop floor guard covers the derivative tables");
            assertTrue(partitionExists(table.partitionName(NEXT)),
                table.partitionName(NEXT) + " is the next month — the "
                    + "never-drop floor guard covers the derivative tables");
        }
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
