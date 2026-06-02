package app.zcat.infochat.collector.partition;

import org.jspecify.annotations.NonNull;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Emits the {@code CREATE TABLE … PARTITION OF} DDL that provisions one
 * monthly range partition per partitioned table. Pure (no I/O) so the
 * statement shape is unit-testable without a database; {@link PartitionCreator}
 * executes the emitted strings against the live connection.
 *
 * <p>Month-bound computation lives here only: a partition for month M covers
 * {@code [first-of-M 00:00 UTC, first-of-next-month 00:00 UTC)} — the same
 * half-open convention as the V7/V11/V17/V28/V29 bootstrap partitions.
 */
public final class PartitionDdl {

    /**
     * The five range-partitioned tables and their per-table partition-name
     * convention. Four use {@code <parent>_YYYYMM}; price_snapshot uses
     * {@code price_snapshot_pYYYYMM} — the {@code p} distinguishes partition
     * children from the parent in pg_class listings (see V17).
     */
    public enum PartitionedTable {
        POST("post", "post_"),
        POST_EMBEDDING("post_embedding", "post_embedding_"),
        PRICE_SNAPSHOT("price_snapshot", "price_snapshot_p"),
        POST_ENTITY("post_entity", "post_entity_"),
        POST_REFERENCE("post_reference", "post_reference_");

        private final String parent;
        private final String childPrefix;

        PartitionedTable(String parent, String childPrefix) {
            this.parent = parent;
            this.childPrefix = childPrefix;
        }

        String partitionName(YearMonth month) {
            return childPrefix + month.format(SUFFIX);
        }
    }

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private PartitionDdl() {
    }

    /**
     * One statement per partitioned table for {@code month}, in
     * {@link PartitionedTable} declaration order.
     */
    public static List<String> createPartitions(@NonNull YearMonth month) {
        return Arrays.stream(PartitionedTable.values())
            .map(table -> createPartition(table, month))
            .toList();
    }

    /**
     * The {@code CREATE TABLE IF NOT EXISTS … PARTITION OF} statement for one
     * table and month. {@code IF NOT EXISTS} makes the scheduler idempotent: a
     * month already provisioned by the V30 migration (or a prior tick) is a
     * no-op rather than an error.
     */
    public static String createPartition(@NonNull PartitionedTable table, @NonNull YearMonth month) {
        return "CREATE TABLE IF NOT EXISTS " + table.partitionName(month)
            + " PARTITION OF " + table.parent
            + " FOR VALUES FROM ('" + bound(month) + "') TO ('" + bound(month.plusMonths(1)) + "')";
    }

    private static String bound(YearMonth month) {
        return month.atDay(1) + " 00:00:00+00";
    }
}
