package app.zcat.infochat.collector.partition;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Provisions the current and next calendar month's range partitions for every
 * partitioned table. With no DEFAULT partition (Invariant 6 forbids one), a
 * month boundary reached without its partition wedges every insert into the
 * partitioned tables — this scheduler is the durable counterpart to the V30
 * migration's one-shot June/July provisioning.
 *
 * <p>Runs at startup and on every {@code infochat.partitions.check-interval}
 * tick (daily by default). Startup provisioning covers the active month
 * directly, so a fresh deployment — or an instance that was down across a
 * month boundary — repairs the missing active-month partition before the
 * first insert rather than waiting for the first tick. Provisioning is
 * idempotent via {@code CREATE TABLE IF NOT EXISTS} (see {@link PartitionDdl}),
 * so re-running over an already-provisioned month is a no-op. If provisioning
 * has not fully succeeded within {@link #LIVENESS_THRESHOLD}, the tick logs a
 * WARN: the active month's partition will eventually run out and the daily
 * retries are not keeping up.
 */
@ApplicationScoped
public class PartitionCreator {

    private static final Logger LOG = Logger.getLogger(PartitionCreator.class);

    // 25 days leaves multiple daily-tick retries before the active month ends,
    // so a transient failure does not immediately trip the alarm.
    static final Duration LIVENESS_THRESHOLD = Duration.ofDays(25);

    // The one deliberate owner-datasource qualification in the Collector:
    // CREATE TABLE … PARTITION OF requires parent-table ownership, which the
    // least-privileged infochat_collector role on the default datasource does
    // not have. Schema DDL belongs on the owner connection — the same
    // principle that points Flyway at the owner datasource.
    @Inject
    @io.quarkus.agroal.DataSource("owner")
    DataSource dataSource;

    // The provision-month decision, the liveness gate, and the on-success
    // advance all read time from the injected Clock — not ambient Instant.now()
    // / YearMonth.now() — so the gate is pinnable in tests (engineering-rules
    // §9; sibling PartitionPruner is the reference seam). Production gets
    // Clock.systemUTC() from the CDI producer; the initializer only covers
    // hand-constructed instances. (M1-471, ref M1-449)
    @Inject
    Clock clock = Clock.systemUTC();

    // Seeded on the injected Clock so a freshly started instance does not warn
    // before its first tick; advanced only after a fully successful
    // provisioning. The seed, the on-success advance, and the liveness-gate
    // read share this one Clock (no two-clock split — §9). Declared after
    // `clock` so the initializer sees it; its pre-injection default is
    // Clock.systemUTC(), equal to the production clock, so the seed matches the
    // prior Instant.now() byte-for-byte.
    private volatile Instant lastSuccessfulRun = clock.instant();

    void onStart(@Observes StartupEvent event) {
        provisionActiveAndNextMonth(clock.instant());
    }

    @Scheduled(every = "{infochat.partitions.check-interval}")
    void onTick() {
        // One Clock sample feeds both the provisioning attempt's on-success
        // advance and the liveness gate, so the gate reads lastSuccessfulRun
        // back against the same instant that may have just written it.
        Instant now = clock.instant();
        provisionActiveAndNextMonth(now);

        if (livenessWarnDue(lastSuccessfulRun, now)) {
            long staleDays = Duration.between(lastSuccessfulRun, now).toDays();
            LOG.warnf("PartitionCreator has not successfully provisioned partitions in %d days "
                    + "(threshold %d) — partitioned inserts will fail once the active month ends",
                staleDays, LIVENESS_THRESHOLD.toDays());
        }
    }

    // Pure liveness decision, parameterized on the sampled instant so it is
    // pinnable without a Quarkus boot (mirrors the pure-decision pattern of
    // PartitionDdl). WARN is due once the stale interval strictly exceeds the
    // threshold; at exactly the threshold it is not yet due.
    static boolean livenessWarnDue(Instant lastSuccessfulRun, Instant now) {
        return Duration.between(lastSuccessfulRun, now).compareTo(LIVENESS_THRESHOLD) > 0;
    }

    private void provisionActiveAndNextMonth(Instant now) {
        List<YearMonth> months = PartitionDdl.monthsToProvision(YearMonth.from(now.atZone(ZoneOffset.UTC)));
        try {
            for (YearMonth month : months) {
                provision(month);
            }
            lastSuccessfulRun = now;
        } catch (SQLException e) {
            LOG.errorf(e, "Partition provisioning for %s failed", months);
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
