package app.zcat.infochat.collector.startup;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full Quarkus app and asserts the Collector's
 * {@link HeartbeatScheduler} tick advances {@code heartbeat.last_seen_at} past
 * the value written by the startup upsert.
 *
 * <p><b>Drives the tick directly</b> ({@code heartbeatScheduler.tick()}) instead
 * of resuming the globally-halted scheduler. The {@code %test} profile sets
 * {@code quarkus.scheduler.start-mode=halted} precisely so background
 * {@code @Scheduled} beans do not mutate the DB shared across the collector's
 * {@code @QuarkusTest} classes. Calling {@code scheduler.resume()} here (the prior
 * approach) un-halted that shared scheduler for the rest of the app and never
 * restored it, so {@link app.zcat.infochat.collector.partition.PartitionPruner}
 * then ran with the real clock and dropped the retention-boundary month partition
 * ({@code post_202605} once the wall clock reached 2026-07-01), breaking sibling
 * ITs that seed fixed-date rows (M1-535). Triggering the one handler directly —
 * the pattern the other scheduler ITs use (e.g. {@code FetchSchedulerIT}) — keeps
 * the halted-scheduler isolation contract intact: no {@code @Scheduled} bean, and
 * in particular not the pruner, runs against the shared DB.
 *
 * <p>Named with the {@code IT} suffix and bound to failsafe (see
 * {@code infochat-collector/pom.xml}) so it runs in the verify phase.
 */
@QuarkusTest
class HeartbeatSchedulerIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    HeartbeatScheduler heartbeatScheduler;

    @Test
    void tickAdvancesLastSeenAt() throws Exception {
        Timestamp initial = readLastSeenAt();
        assertNotNull(initial, "heartbeat row must already exist via the startup upsert");

        heartbeatScheduler.tick();

        Timestamp after = readLastSeenAt();
        assertTrue(after.after(initial),
                "the heartbeat tick must advance last_seen_at; initial=" + initial + " after=" + after);
    }

    private Timestamp readLastSeenAt() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_seen_at FROM heartbeat WHERE service = 'collector'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "heartbeat row for 'collector' must exist");
            return rs.getTimestamp("last_seen_at");
        }
    }
}
