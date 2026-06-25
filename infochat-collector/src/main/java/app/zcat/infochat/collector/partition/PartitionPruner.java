package app.zcat.infochat.collector.partition;

import app.zcat.infochat.collector.partition.PartitionDdl.PartitionedTable;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The drop half of Invariant 6 ("aged out by partition drop, not row
 * delete"): on every {@code infochat.partitions.prune-interval} tick (daily
 * by default), drops each partitioned table's child partitions whose end date
 * is older than the table's {@code infochat.partitions.retention-days.*}
 * horizon. Counterpart to {@link PartitionCreator}, which provisions ahead of
 * need; selection logic (including the never-drop-the-active-or-next-month
 * floor guard) is pure and lives in
 * {@link PartitionDdl#prunablePartitions}.
 *
 * <p>DROP of an aged partition is destructive by design — the rows are gone.
 * Saved posts survive because their bodies are snapshotted in
 * {@code saved_post} at /save time.
 */
@ApplicationScoped
public class PartitionPruner {

    private static final Logger LOG = Logger.getLogger(PartitionPruner.class);

    // Same owner-datasource qualification as PartitionCreator: DROP TABLE on a
    // partition requires parent-table ownership, which the least-privileged
    // collector role on the default datasource does not have.
    @Inject
    @io.quarkus.agroal.DataSource("owner")
    DataSource dataSource;

    // The prune decision (retention cutoff + active-month floor guard) reads
    // its instant from the injected Clock, not ambient Instant.now() /
    // YearMonth.now(), so onTick is pinnable in tests (QuarkusMock-installed
    // Clock.fixed). Production gets Clock.systemUTC() from the CDI producer;
    // the initializer only covers hand-constructed instances. (M1-449, ref M1-444)
    @Inject
    Clock clock = Clock.systemUTC();

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    @ConfigProperty(name = "infochat.partitions.retention-days.post-embedding")
    int postEmbeddingRetentionDays;

    @ConfigProperty(name = "infochat.partitions.retention-days.price-snapshot")
    int priceSnapshotRetentionDays;

    @ConfigProperty(name = "infochat.partitions.retention-days.post-entity")
    int postEntityRetentionDays;

    @ConfigProperty(name = "infochat.partitions.retention-days.post-reference")
    int postReferenceRetentionDays;

    @Scheduled(every = "{infochat.partitions.prune-interval}")
    void onTick() {
        // One Clock sample feeds both the active-month floor guard and the
        // retention cutoff; deriving the UTC YearMonth from that same instant
        // preserves the prior YearMonth.now(ZoneOffset.UTC) under the
        // production systemUTC clock and cannot straddle a month boundary
        // between two reads.
        Instant now = clock.instant();
        pruneOnce(YearMonth.from(now.atZone(ZoneOffset.UTC)), now);
    }

    // Clock-parameterized so tests can pin the active month and instant; the
    // scheduled tick always passes wall-clock UTC.
    void pruneOnce(YearMonth activeMonth, Instant now) {
        for (PartitionedTable table : PartitionedTable.values()) {
            int retentionDays = retentionDays(table);
            try {
                List<String> prunable = PartitionDdl.prunablePartitions(
                    table, listChildren(table), activeMonth, retentionDays, now);
                dropAll(prunable, retentionDays);
            } catch (SQLException e) {
                // Per-table isolation: one table's failure must not block the
                // others' aging-out; the next tick retries.
                LOG.errorf(e, "Partition pruning for %s failed", table.parentTable());
            }
        }
    }

    private int retentionDays(PartitionedTable table) {
        return switch (table) {
            case POST -> postRetentionDays;
            case POST_EMBEDDING -> postEmbeddingRetentionDays;
            case PRICE_SNAPSHOT -> priceSnapshotRetentionDays;
            case POST_ENTITY -> postEntityRetentionDays;
            case POST_REFERENCE -> postReferenceRetentionDays;
        };
    }

    private List<String> listChildren(PartitionedTable table) throws SQLException {
        String sql = "SELECT c.relname FROM pg_inherits i"
            + " JOIN pg_class c ON c.oid = i.inhrelid"
            + " JOIN pg_class p ON p.oid = i.inhparent"
            + " JOIN pg_namespace n ON n.oid = p.relnamespace"
            + " WHERE p.relname = ? AND n.nspname = current_schema()";
        List<String> children = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table.parentTable());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    children.add(rs.getString(1));
                }
            }
        }
        return children;
    }

    // One connection + statement reused across a table's whole drop batch,
    // instead of a fresh connection per partition (cold daily maintenance
    // path). The name is interpolated, not bound: DDL takes no parameters.
    // Safe because prunablePartitions only returns names that round-trip the
    // partition-name convention (prefix + yyyyMM) — no other identifier can
    // reach this statement.
    private void dropAll(List<String> partitionNames, int retentionDays) throws SQLException {
        if (partitionNames.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String partitionName : partitionNames) {
                stmt.execute(PartitionDdl.dropPartition(partitionName));
                LOG.infof("Dropped aged partition %s (retention %d days)", partitionName, retentionDays);
            }
        }
    }
}
