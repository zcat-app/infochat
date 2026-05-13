package io.infochat.collector.startup;

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
 * Boots the full Quarkus app and asserts that the Collector's
 * {@link HeartbeatScheduler} {@code @Scheduled} tick advances
 * {@code heartbeat.last_seen_at} after one heartbeat interval. The
 * {@code %laptop} profile (which the test suite runs under, per
 * {@code %test.quarkus.test.profile=laptop} wiring) configures
 * {@code infochat.heartbeat.interval=5s}, so the test waits a small buffer
 * over that interval and re-reads the timestamp.
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin (see
 * {@code infochat-collector/pom.xml}) so this test runs in the verify phase.
 */
@QuarkusTest
class HeartbeatSchedulerIT {

    @Inject
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
                 "SELECT last_seen_at FROM heartbeat WHERE service = 'collector'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "heartbeat row for 'collector' must exist");
            return rs.getTimestamp("last_seen_at");
        }
    }
}
