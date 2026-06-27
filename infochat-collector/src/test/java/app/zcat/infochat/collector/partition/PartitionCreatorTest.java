package app.zcat.infochat.collector.partition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static app.zcat.infochat.collector.partition.PartitionCreator.LIVENESS_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the DDL builder and month logic behind {@link PartitionCreator}
 * and {@link PartitionPruner}. Asserts the emitted
 * {@code CREATE TABLE … PARTITION OF} statements — one per partitioned table,
 * with the correct per-table partition name and half-open FROM/TO month bounds
 * — plus the provisioning-month coverage and retention-horizon prune selection
 * (including the active-month floor guard), without touching a database.
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

    @Test
    void monthsToProvisionCoverBothCurrentAndNextMonth() {
        assertEquals(List.of(YearMonth.of(2026, 6), YearMonth.of(2026, 7)),
            PartitionDdl.monthsToProvision(YearMonth.of(2026, 6)),
            "the active month must be provisioned by code, not only by V30's one-shot");
    }

    @Test
    void prunableSelectsOnlyPartitionsOlderThanTheRetentionHorizon() {
        // 30-day horizon at 2026-06-15: cutoff 2026-05-16. April ends
        // 2026-05-01 (aged); May ends 2026-06-01 (in horizon).
        List<String> prunable = PartitionDdl.prunablePartitions(
            PartitionDdl.PartitionedTable.POST,
            List.of("post_202604", "post_202605", "post_202606", "post_202607"),
            YearMonth.of(2026, 6), 30, Instant.parse("2026-06-15T00:00:00Z"));
        assertEquals(List.of("post_202604"), prunable);
    }

    @Test
    void floorGuardKeepsActiveAndNextMonthEvenWhenHorizonIsShorterThanOneMonth() {
        // 1-day horizon at 2026-06-15: cutoff 2026-06-14. May ends 2026-06-01
        // (aged, drops) — but the active and next month must survive any
        // horizon, however misconfigured.
        List<String> prunable = PartitionDdl.prunablePartitions(
            PartitionDdl.PartitionedTable.POST,
            List.of("post_202605", "post_202606", "post_202607"),
            YearMonth.of(2026, 6), 1, Instant.parse("2026-06-15T00:00:00Z"));
        assertEquals(List.of("post_202605"), prunable);

        List<String> pathological = PartitionDdl.prunablePartitions(
            PartitionDdl.PartitionedTable.POST,
            List.of("post_202606", "post_202607"),
            YearMonth.of(2026, 6), -3650, Instant.parse("2026-06-15T00:00:00Z"));
        assertTrue(pathological.isEmpty(),
            "even a negative retention horizon must not select the active or next month");
    }

    @Test
    void livenessWarnFiresOnlyOnceStaleExceedsTheThreshold() {
        // The gate reads its instant from the injected Clock (M1-471), so the
        // decision is pinnable directly: WARN is due exactly when fixed-now
        // minus lastSuccessfulRun crosses LIVENESS_THRESHOLD, with the boundary
        // strict (== threshold does not yet warn).
        Instant now = Instant.parse("2026-06-15T00:00:00Z");
        assertFalse(PartitionCreator.livenessWarnDue(now.minus(LIVENESS_THRESHOLD), now),
            "stale interval exactly at the threshold must NOT warn (strict > comparison)");
        assertFalse(PartitionCreator.livenessWarnDue(now.minus(LIVENESS_THRESHOLD).plusSeconds(1), now),
            "stale interval just under the threshold must NOT warn");
        assertTrue(PartitionCreator.livenessWarnDue(now.minus(LIVENESS_THRESHOLD).minusSeconds(1), now),
            "stale interval just past the threshold must warn");
    }

    @Test
    void prunableSkipsChildNamesOutsideThePartitionNamingConvention() {
        List<String> prunable = PartitionDdl.prunablePartitions(
            PartitionDdl.PartitionedTable.POST,
            List.of("post_default", "post_2020", "post_archive_202001"),
            YearMonth.of(2026, 6), 30, Instant.parse("2026-06-15T00:00:00Z"));
        assertTrue(prunable.isEmpty(),
            "only convention-named monthly partitions may ever be dropped");
    }
}
