package app.zcat.infochat.collector.partition;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Provisions the next calendar month's range partition for every partitioned
 * table ahead of need. With no DEFAULT partition (Invariant 6 forbids one), a
 * month boundary reached without its partition wedges every insert into the
 * partitioned tables — this scheduler is the durable counterpart to the V30
 * migration's one-shot June/July provisioning.
 *
 * <p>Runs on every {@code infochat.partitions.check-interval} tick (daily by
 * default). Provisioning is idempotent via {@code CREATE TABLE IF NOT EXISTS}
 * (see {@link PartitionDdl}), so re-running over an already-provisioned month
 * is a no-op. If provisioning has not fully succeeded within
 * {@link #LIVENESS_THRESHOLD}, the tick logs a WARN: the active month's
 * partition will eventually run out and the daily retries are not keeping up.
 */
@ApplicationScoped
public class PartitionCreator {

    private static final Logger LOG = Logger.getLogger(PartitionCreator.class);

    // 25 days leaves multiple daily-tick retries before the active month ends,
    // so a transient failure does not immediately trip the alarm.
    static final Duration LIVENESS_THRESHOLD = Duration.ofDays(25);

    @Inject
    DataSource dataSource;

    // Seeded at construction so a freshly started instance does not warn before
    // its first tick; advanced only after a fully successful provisioning.
    private volatile Instant lastSuccessfulRun = Instant.now();

    @Scheduled(every = "{infochat.partitions.check-interval}")
    void onTick() {
        YearMonth nextMonth = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
        try {
            provision(nextMonth);
            lastSuccessfulRun = Instant.now();
        } catch (SQLException e) {
            LOG.errorf(e, "Partition provisioning for %s failed", nextMonth);
        }

        Duration sinceSuccess = Duration.between(lastSuccessfulRun, Instant.now());
        if (sinceSuccess.compareTo(LIVENESS_THRESHOLD) > 0) {
            LOG.warnf("PartitionCreator has not successfully provisioned partitions in %d days "
                    + "(threshold %d) — partitioned inserts will fail once the active month ends",
                sinceSuccess.toDays(), LIVENESS_THRESHOLD.toDays());
        }
    }

    private void provision(YearMonth month) throws SQLException {
        List<String> statements = PartitionDdl.createPartitions(month);
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String ddl : statements) {
                stmt.execute(ddl);
            }
        }
    }
}
