package app.zcat.infochat.collector.partition;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the DDL builder behind {@link PartitionCreator}. Asserts the
 * emitted {@code CREATE TABLE … PARTITION OF} statements — one per partitioned
 * table, with the correct per-table partition name and half-open FROM/TO month
 * bounds — without touching a database.
 */
class PartitionCreatorTest {

    @Test
    void emitsOnePartitionStatementPerTable() {
        List<String> ddl = PartitionDdl.createPartitions(YearMonth.of(2026, 6));
        assertEquals(5, ddl.size(), "one statement per partitioned table");
        for (String statement : ddl) {
            assertTrue(statement.contains("PARTITION OF"),
                "every statement is a CREATE TABLE … PARTITION OF: " + statement);
        }
    }

    @Test
    void emitsCorrectNamesAndBoundsForJune2026() {
        List<String> ddl = PartitionDdl.createPartitions(YearMonth.of(2026, 6));
        assertEquals(
            "CREATE TABLE IF NOT EXISTS post_202606 PARTITION OF post "
                + "FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00')",
            ddl.get(0));
        assertEquals(
            "CREATE TABLE IF NOT EXISTS post_embedding_202606 PARTITION OF post_embedding "
                + "FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00')",
            ddl.get(1));
        // price_snapshot uses the p-prefixed partition-name convention (V17).
        assertEquals(
            "CREATE TABLE IF NOT EXISTS price_snapshot_p202606 PARTITION OF price_snapshot "
                + "FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00')",
            ddl.get(2));
        assertEquals(
            "CREATE TABLE IF NOT EXISTS post_entity_202606 PARTITION OF post_entity "
                + "FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00')",
            ddl.get(3));
        assertEquals(
            "CREATE TABLE IF NOT EXISTS post_reference_202606 PARTITION OF post_reference "
                + "FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00')",
            ddl.get(4));
    }

    @Test
    void monthBoundsRollOverTheYearEnd() {
        String december = PartitionDdl.createPartition(
            PartitionDdl.PartitionedTable.POST, YearMonth.of(2026, 12));
        assertEquals(
            "CREATE TABLE IF NOT EXISTS post_202612 PARTITION OF post "
                + "FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00')",
            december);
    }
}
