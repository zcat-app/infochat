package app.zcat.infochat.provider.startup;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-side mirror of the Collector heartbeat-scheduler IT (M1-009 /
 * docs/design/07-deployment.md §7.8.5). Asserts that
 * {@code heartbeat.last_seen_at} for {@code service = 'provider'} advances
 * after one configured tick.
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin (see
 * {@code infochat-provider/pom.xml}) so this test runs in the verify phase.
 */
@QuarkusTest
class HeartbeatSchedulerIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Test
    void lastSeenAtAdvancesAfterOneInterval() throws Exception {
        Timestamp initial = readLastSeenAt();
        assertNotNull(initial, "heartbeat row must already exist via the startup upsert");

        // %laptop.infochat.heartbeat.interval=5s; wait a small buffer beyond
        // the configured tick so the @Scheduled handler has definitely fired
        // at least once.
        Thread.sleep(7_000);

        Timestamp after = readLastSeenAt();
        assertTrue(after.after(initial),
            "the @Scheduled tick must advance last_seen_at; initial=" + initial + " after=" + after);
    }

    private Timestamp readLastSeenAt() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT last_seen_at FROM heartbeat WHERE service = 'provider'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "heartbeat row for 'provider' must exist");
            return rs.getTimestamp("last_seen_at");
        }
    }
}
