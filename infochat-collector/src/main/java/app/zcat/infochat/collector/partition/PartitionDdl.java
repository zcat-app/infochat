package app.zcat.infochat.collector.partition;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Emits the {@code CREATE TABLE … PARTITION OF} DDL that provisions one
 * monthly range partition per partitioned table, plus the {@code DROP TABLE}
 * DDL and retention-horizon selection that age partitions out again. Pure
 * (no I/O) so statement shape and prune selection are unit-testable without
 * a database; {@link PartitionCreator} and {@link PartitionPruner} execute
 * the emitted strings against the live connection.
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

        String parentTable() {
            return parent;
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

    /**
     * The months {@link PartitionCreator} must keep provisioned: the active
     * UTC month and the next. The active month is included so a fresh
     * deployment (or an instance that was down across a month boundary) does
     * not depend on a prior month's tick having provisioned it ahead of time.
     */
    public static List<YearMonth> monthsToProvision(@NonNull YearMonth activeMonth) {
        return List.of(activeMonth, activeMonth.plusMonths(1));
    }

    /**
     * The {@code DROP TABLE} statement for one aged child partition.
     * {@code IF EXISTS} keeps the pruner idempotent under concurrent or
     * repeated runs. Dropping a partition detaches and removes it in one O(1)
     * operation — no row deletes, no index bloat (Invariant 6).
     */
    public static String dropPartition(@NonNull String partitionName) {
        return "DROP TABLE IF EXISTS " + partitionName;
    }

    /**
     * Selects which of {@code table}'s existing child partitions are old
     * enough to drop: a partition for month M (covering up to first-of-M+1)
     * is prunable once its end is more than {@code retentionDays} before
     * {@code now}.
     *
     * <p>Floor guard: the active month and anything after it are NEVER
     * selected, regardless of how short (or negative) a misconfigured
     * retention horizon is — a bad config value must not be able to drop the
     * partition live inserts are landing in, nor the pre-provisioned next
     * month.
     *
     * <p>Child names that do not round-trip the table's partition-name
     * convention (e.g. a hand-created partition with a different suffix) are
     * skipped entirely: the pruner only ever drops what the convention says
     * {@link PartitionCreator} would have created.
     */
    public static List<String> prunablePartitions(@NonNull PartitionedTable table,
                                                  @NonNull Collection<String> childNames,
                                                  @NonNull YearMonth activeMonth,
                                                  int retentionDays,
                                                  @NonNull Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        List<String> prunable = new ArrayList<>();
        for (String child : childNames) {
            YearMonth month = monthOf(table, child);
            if (month == null) {
                continue;
            }
            if (!month.isBefore(activeMonth)) {
                continue; // floor guard: active month and later always survive
            }
            Instant partitionEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (partitionEnd.isBefore(cutoff)) {
                prunable.add(child);
            }
        }
        return prunable;
    }

    /**
     * The month a child partition covers, or null when the name does not
     * round-trip {@code table.partitionName(month)} — the round-trip check
     * validates the prefix without re-stating the per-table convention.
     */
    private static @Nullable YearMonth monthOf(PartitionedTable table, String childName) {
        if (childName.length() < 6) {
            return null;
        }
        YearMonth month;
        try {
            month = YearMonth.parse(childName.substring(childName.length() - 6), SUFFIX);
        } catch (DateTimeParseException e) {
            return null;
        }
        return table.partitionName(month).equals(childName) ? month : null;
    }
}
