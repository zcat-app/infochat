package app.zcat.infochat.collector.partition;

import app.zcat.infochat.collector.partition.PartitionDdl.PartitionedTable;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tripwire (M1-535): a normal Quarkus boot must provision the CURRENT calendar
 * month's {@code post} partition via {@link PartitionCreator#onStart} (an
 * {@code @Observes StartupEvent} observer), so {@code now()}-keyed inserts stay
 * valid at any month boundary — independent of the static V30 June/July-2026
 * migration horizon. This is the guarantee that makes the deterministic-fresh-DB
 * fix (M1-535) time-invariant.
 *
 * <p>Unlike {@link PartitionInsertIT#freshStartProvisionsActiveMonthPartitionOnEveryTable},
 * which drops a partition and calls {@code onStart()} explicitly, this test
 * invokes nothing — it asserts the partition the REAL boot already created
 * exists. It fails if a future change stops booting {@code PartitionCreator} (or
 * its {@code onStart}), or if the suite ever reverts to a drifting reused DB
 * whose active-month partition was pruned away.
 *
 * <p>Reads the SAME injected {@link Clock} the provisioning path uses (no
 * wall-clock split): {@code onStart} provisions current+next month off this
 * Clock, so the month derived here matches — and because "next" is provisioned
 * too, the assertion holds even if the month rolls over between boot and now.
 */
@QuarkusTest
class CurrentMonthPartitionBootIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    Clock clock;

    @Test
    void bootProvisionsCurrentMonthPostPartition() throws Exception {
        YearMonth activeMonth = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        String partition = PartitionedTable.POST.partitionName(activeMonth);
        assertTrue(partitionExists(partition),
                "boot-time PartitionCreator.onStart must have provisioned the current-month post "
                        + "partition (" + partition + ") so now()-keyed inserts never hit "
                        + "\"no partition of relation post found for row\"");
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
