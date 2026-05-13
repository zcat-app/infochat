package io.infochat.provider.startup;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Single-instance enforcement guard for the Provider service (decision D41).
 *
 * <p>Acquires {@code pg_try_advisory_lock(hashtext('infochat.provider'))} at
 * {@link Startup} priority 50 ({@code docs/design/01-architecture.md} §1.4.3).
 * Mirror of the Collector-side guard; the hash input differs so the two
 * services race for distinct lock ids on the shared Postgres.
 *
 * <p>The advisory lock is scoped to the Postgres backend <em>session</em>,
 * not to the transaction or the JDBC connection wrapper. The lock-holding
 * connection must therefore live for the JVM lifetime — if Agroal pool
 * idle-eviction closed it, the server would release the lock and a second
 * Provider could quietly take over while this JVM was still alive.
 *
 * <p>On lock-acquisition failure the bean reads the {@code heartbeat} row
 * naming the running holder, logs a fatal-level line carrying the holder's
 * {@code host_id}, {@code pid}, and {@code last_seen_at}, and calls
 * {@link Quarkus#asyncExit(int)} with exit code {@code 1}. The long-term
 * shape is exit code {@code 42} paired with a systemd
 * {@code RestartPreventExitStatus=42} unit file
 * ({@code docs/design/07-deployment.md} §7.8.5); v1 uses {@code 1} and the
 * unit-file ticket later swaps the literal in one place.
 */
@Startup
@Priority(50)
@ApplicationScoped
public class InstanceLockGuard {

    static final String SERVICE = "provider";
    static final String LOCK_KEY_HASH_INPUT = "infochat." + SERVICE;

    private static final Logger LOG = Logger.getLogger(InstanceLockGuard.class);

    @Inject
    DataSource dataSource;

    // Long-lived; never returned to the pool while the JVM is alive. The
    // advisory lock dies with the Postgres session, so closing this would
    // silently release the single-instance gate.
    private Connection heldConnection;

    private boolean lockHeld;

    @PostConstruct
    void onStartup() {
        String hostId = resolveHostId();
        int pid = (int) ProcessHandle.current().pid();

        try {
            heldConnection = dataSource.getConnection();
            heldConnection.setAutoCommit(true);

            if (!tryAcquire(heldConnection)) {
                Optional<Holder> holder = readHolder(heldConnection);
                logContention(holder);
                Quarkus.asyncExit(1);
                return;
            }
            upsertHeartbeat(heldConnection, hostId, pid);
            lockHeld = true;
        } catch (SQLException e) {
            throw new IllegalStateException(
                "InstanceLockGuard could not acquire its Postgres session for "
                    + LOCK_KEY_HASH_INPUT, e);
        }
    }

    @PreDestroy
    void onShutdown() {
        // Closing the connection ends the Postgres session; the server then
        // releases the advisory lock so a subsequent restart can acquire it
        // cleanly. SIGKILL / OOM-killer reach the same effect via session
        // termination on the server side.
        if (heldConnection != null) {
            try {
                heldConnection.close();
            } catch (SQLException ignored) {
                // Shutdown path; nothing useful to do with the exception.
            }
        }
    }

    /**
     * Calls {@code pg_try_advisory_lock(hashtext('infochat.provider'))} on
     * the given connection. Returns {@code true} when the calling session
     * now holds the lock, {@code false} when another session already does.
     *
     * <p>Visible so the integration test can simulate a second acquirer
     * with a fresh JDBC connection from the same JVM — advisory locks are
     * per-session, so the second connection observes {@code false} exactly
     * as a second JVM would.
     */
    public boolean tryAcquire(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_try_advisory_lock(hashtext(?))")) {
            ps.setString(1, LOCK_KEY_HASH_INPUT);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /**
     * Reads the heartbeat row for this service so a rejected acquirer can
     * name the live holder in the fatal log line. Returns empty if no row
     * exists yet (a prior holder that crashed before its first upsert).
     */
    public Optional<Holder> readHolder(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT host_id, pid, last_seen_at FROM heartbeat WHERE service = ?")) {
            ps.setString(1, SERVICE);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Holder(
                    rs.getString("host_id"),
                    rs.getInt("pid"),
                    rs.getTimestamp("last_seen_at").toInstant()));
            }
        }
    }

    /**
     * Emits the fatal contention log line. Pre-formats with
     * {@link String#format} so the resulting text lands in
     * {@link java.util.logging.LogRecord#getMessage()} verbatim — JBoss
     * Logger's {@code fatalf} stores the format string and parameters
     * separately, which would break test handlers that read only
     * {@code getMessage()}.
     */
    public void logContention(Optional<Holder> holder) {
        if (holder.isPresent()) {
            Holder h = holder.get();
            LOG.fatal(String.format(
                "pg_try_advisory_lock(%s) failed; current holder is host_id='%s' pid=%d last_seen_at=%s",
                LOCK_KEY_HASH_INPUT, h.hostId(), h.pid(), h.lastSeenAt()));
        } else {
            LOG.fatal(String.format(
                "pg_try_advisory_lock(%s) failed but no heartbeat row was found — the prior holder may have crashed before its first heartbeat upsert",
                LOCK_KEY_HASH_INPUT));
        }
    }

    public boolean isLockHeld() {
        return lockHeld;
    }

    private void upsertHeartbeat(Connection conn, String hostId, int pid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO heartbeat (service, host_id, pid, last_seen_at) "
                    + "VALUES (?, ?, ?, now()) "
                    + "ON CONFLICT (service) DO UPDATE "
                    + "SET host_id = EXCLUDED.host_id, "
                    + "    pid = EXCLUDED.pid, "
                    + "    last_seen_at = now()")) {
            ps.setString(1, SERVICE);
            ps.setString(2, hostId);
            ps.setInt(3, pid);
            ps.executeUpdate();
        }
    }

    private static String resolveHostId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return ManagementFactory.getRuntimeMXBean().getName();
        }
    }

    public record Holder(String hostId, int pid, Instant lastSeenAt) {}
}
