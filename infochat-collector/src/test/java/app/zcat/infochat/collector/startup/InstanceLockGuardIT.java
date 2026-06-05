package app.zcat.infochat.collector.startup;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full Quarkus app against a DevServices-managed Postgres and
 * asserts that {@link InstanceLockGuard}'s {@code @Startup} hook acquired the
 * Collector advisory lock and upserted the {@code heartbeat} row. Then opens
 * a fresh JDBC connection from the same JVM and asserts that a second
 * {@code pg_try_advisory_lock} call observes {@code false} — proof that the
 * production guard's session-scoped lock is in force. Finally, drives the
 * contention-log path via the bean's read-and-log API and asserts the fatal
 * log record names the running holder's {@code host_id} and {@code pid}
 * (per ticket M1-009 / docs/design/07-deployment.md §7.8.5).
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin (see
 * {@code infochat-collector/pom.xml}) so this test runs in the verify phase.
 */
@QuarkusTest
class InstanceLockGuardIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    InstanceLockGuard guard;

    @Test
    void startupGuardUpsertsHeartbeatRow() throws Exception {
        assertNotNull(dataSource);
        assertTrue(guard.isLockHeld(),
            "the production @Startup guard must hold the lock for the test JVM");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT host_id, pid FROM heartbeat WHERE service = 'collector'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "startup guard must upsert one heartbeat row");
            String hostId = rs.getString("host_id");
            int pid = rs.getInt("pid");
            assertNotNull(hostId, "host_id must be populated");
            assertFalse(hostId.isBlank(), "host_id must not be blank");
            assertTrue(pid > 0, "pid must be positive; got " + pid);
        }
    }

    @Test
    void secondAcquireFromFreshConnectionObservesFalse() throws Exception {
        // Advisory locks are per-Postgres-session, so a second JDBC connection
        // from the same JVM races for the same lock as a second JVM would.
        try (Connection fresh = dataSource.getConnection()) {
            assertFalse(guard.tryAcquire(fresh),
                "a second JDBC session must not acquire the lock the startup guard already holds");
        }
    }

    @Test
    void contentionLogNamesHolderHostIdAndPid() throws Exception {
        CapturingHandler capturer = new CapturingHandler();
        Logger julLogger = Logger.getLogger(InstanceLockGuard.class.getName());
        julLogger.addHandler(capturer);
        try (Connection fresh = dataSource.getConnection()) {
            Optional<InstanceLockGuard.Holder> holder = guard.readHolder(fresh);
            assertTrue(holder.isPresent(), "heartbeat row must be present for contention log");
            guard.logContention(holder);

            String expectedHost = holder.get().hostId();
            String expectedPid = String.valueOf(holder.get().pid());
            assertTrue(
                capturer.records.stream().anyMatch(r ->
                    r.getMessage() != null
                        && r.getMessage().contains(expectedHost)
                        && r.getMessage().contains(expectedPid)),
                "fatal contention log must name host_id and pid; captured: " + capturer.formatted());
        } finally {
            julLogger.removeHandler(capturer);
        }
    }

    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String formatted() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord r : records) {
                sb.append(r.getLevel()).append(": ").append(r.getMessage()).append("; ");
            }
            return sb.append("]").toString();
        }
    }
}
