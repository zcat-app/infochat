package app.zcat.infochat.core.startup;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

/**
 * Shared single-instance enforcement guard (decision D41). One subclass per
 * service supplies its {@link #serviceName()}; the acquisition, held-session
 * lifecycle, heartbeat upsert, contention logging, and the periodic
 * held-session liveness probe all live here so the logic is not duplicated
 * byte-for-byte across collector and provider.
 *
 * <p>Acquires {@code pg_try_advisory_lock(hashtext('infochat.<service>'))} at
 * {@link Startup} priority 50 ({@code docs/design/01-architecture.md} §1.4.3).
 * The hash is computed server-side by Postgres' built-in {@code hashtext}, so
 * two instances on different hosts always race for the same lock id with no
 * client-side hashing routine.
 *
 * <p>The advisory lock is scoped to the Postgres backend <em>session</em>, not
 * to the transaction or the JDBC connection wrapper. The lock-holding
 * connection must therefore live for the JVM lifetime — if Agroal pool
 * idle-eviction closed it, the server would release the lock and a second
 * instance could quietly take over while this JVM was still alive. That is why
 * {@link #heldConnection} is borrowed from the datasource and never returned to
 * the pool until shutdown.
 *
 * <p>A scheduled liveness probe re-verifies, on the held session itself, that
 * the connection is alive and still owns the advisory lock. If the held
 * session died server-side (PG restart, NAT reaping,
 * {@code idle_in_transaction_session_timeout}, keepalive loss) the lock is
 * released but the JVM would otherwise keep running as a zombie while
 * {@link Startup}-time state and the heartbeat on healthy pool connections mask
 * the loss. On a dead connection or lost ownership the probe calls
 * {@link Quarkus#asyncExit(int)} with exit code {@code 1}. The probe runs on
 * the held connection deliberately: a transient pool connection never held the
 * session-scoped lock, so probing one would re-verify nothing.
 *
 * <p>Subclasses must carry {@code @Startup @Priority(50) @ApplicationScoped} so
 * CDI discovers the guard per module at the same startup phase the outbox
 * rehydrator and FetchScheduler depend on, and must schedule {@link
 * #probeHeldSession()} via their own {@code @Scheduled} method (the scheduler
 * extension lives in the service modules, not in this library jar).
 */
public abstract class AbstractInstanceLockGuard {

    // Bound to the concrete subclass category (not this base) so existing
    // integration tests that read records on Logger.getLogger(<subclass>) still
    // capture the fatal contention/liveness lines.
    private final Logger log = Logger.getLogger(getClass());

    // Caps blocking on a half-open TCP socket so the liveness probe's SELECT 1
    // fails fast on a dead held session instead of hanging forever — which is
    // exactly the zombie scenario the probe exists to catch.
    private static final int NETWORK_TIMEOUT_MILLIS = 10_000;

    // protected (not package-private) so ArC's generated injector for the
    // concrete subclass — which lives in a different package — can write this
    // inherited field.
    @Inject
    protected DataSource dataSource;

    // Long-lived; never returned to the pool while the JVM is alive. The
    // advisory lock dies with the Postgres session, so closing this would
    // silently release the single-instance gate. Assigned in the
    // @PostConstruct onStartup() (or the test seam); NullAway's field-init
    // check models only constructors/initializers, not @PostConstruct, so
    // suppress that one check — the field stays non-null for every dereference
    // (all probe reads are guarded by the heldConnection == null check).
    @SuppressWarnings("NullAway.Init")
    private Connection heldConnection;

    private volatile boolean lockHeld;

    // Guards heldConnection so the scheduled probe never touches it while
    // shutdown is closing it.
    private final Object connectionLock = new Object();
    private volatile boolean shuttingDown;

    // The exit decision is routed through this seam so a test can assert the
    // probe's exit path fires without killing the test JVM.
    private ExitHook exitHook = Quarkus::asyncExit;

    /** The service name this guard enforces single-instance for, e.g. {@code "collector"}. */
    protected abstract String serviceName();

    private String lockKeyHashInput() {
        return "infochat." + serviceName();
    }

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
                exitHook.exit(1);
                return;
            }
            upsertHeartbeat(heldConnection, hostId, pid);
            heldConnection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MILLIS);
            lockHeld = true;
        } catch (SQLException e) {
            throw new IllegalStateException(
                "InstanceLockGuard could not acquire its Postgres session for "
                    + lockKeyHashInput(), e);
        }
    }

    @PreDestroy
    void onShutdown() {
        // Closing the connection ends the Postgres session; the server then
        // releases the advisory lock so a subsequent restart can acquire it
        // cleanly. SIGKILL / OOM-killer reach the same effect via session
        // termination on the server side.
        synchronized (connectionLock) {
            shuttingDown = true;
            if (heldConnection != null) {
                try {
                    heldConnection.close();
                } catch (SQLException ignored) {
                    // Shutdown path; nothing useful to do with the exception.
                }
            }
        }
    }

    /**
     * Re-verifies the held lock session and exits the JVM if it is dead or no
     * longer owns the advisory lock. Probes the held connection, never a
     * transient pool connection — only the held session owns the
     * session-scoped lock.
     */
    protected void probeHeldSession() {
        synchronized (connectionLock) {
            if (shuttingDown || !lockHeld || heldConnection == null) {
                return;
            }
            boolean owned;
            try {
                // SELECT 1 round-trip: throws if the held session died
                // server-side (the dead-connection signal).
                try (Statement st = heldConnection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT 1")) {
                    rs.next();
                }
                // Ownership re-check on THIS backend session. The held session
                // takes exactly one advisory lock (the single-instance gate)
                // and never any other, so any advisory row for its own backend
                // pid is that lock. pg_try_advisory_lock is deliberately not
                // re-called here: it is reentrant, so re-acquiring would mask a
                // server-side release rather than detect it.
                try (Statement st = heldConnection.createStatement();
                     ResultSet rs = st.executeQuery(
                         "SELECT EXISTS (SELECT 1 FROM pg_locks "
                             + "WHERE locktype = 'advisory' AND pid = pg_backend_pid())")) {
                    rs.next();
                    owned = rs.getBoolean(1);
                }
            } catch (SQLException e) {
                log.fatal(String.format(
                    "held lock session for %s is dead (%s); exiting to avoid running as a zombie",
                    lockKeyHashInput(), e.getMessage()));
                exitHook.exit(1);
                return;
            }
            if (!owned) {
                log.fatal(String.format(
                    "advisory lock %s is no longer held by the lock session; exiting to avoid running as a zombie",
                    lockKeyHashInput()));
                exitHook.exit(1);
            }
        }
    }

    /**
     * Calls {@code pg_try_advisory_lock(hashtext('infochat.<service>'))} on the
     * given connection. Returns {@code true} when the calling session now holds
     * the lock, {@code false} when another session already does.
     *
     * <p>Visible so the integration test can simulate a second acquirer with a
     * fresh JDBC connection from the same JVM — advisory locks are per-session,
     * so the second connection observes {@code false} exactly as a second JVM
     * would.
     */
    public boolean tryAcquire(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_try_advisory_lock(hashtext(?))")) {
            ps.setString(1, lockKeyHashInput());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /**
     * Reads the heartbeat row for this service so a rejected acquirer can name
     * the live holder in the fatal log line. Returns empty if no row exists yet
     * (a prior holder that crashed before its first upsert).
     */
    public Optional<Holder> readHolder(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT host_id, pid, last_seen_at FROM heartbeat WHERE service = ?")) {
            ps.setString(1, serviceName());
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
     * Emits the fatal contention log line. Pre-formats with {@link
     * String#format} so the resulting text lands in {@link
     * java.util.logging.LogRecord#getMessage()} verbatim — JBoss Logger's
     * {@code fatalf} stores the format string and parameters separately, which
     * would break test handlers that read only {@code getMessage()}.
     */
    public void logContention(Optional<Holder> holder) {
        if (holder.isPresent()) {
            Holder h = holder.get();
            log.fatal(String.format(
                "pg_try_advisory_lock(%s) failed; current holder is host_id='%s' pid=%d last_seen_at=%s",
                lockKeyHashInput(), h.hostId(), h.pid(), h.lastSeenAt()));
        } else {
            log.fatal(String.format(
                "pg_try_advisory_lock(%s) failed but no heartbeat row was found — the prior holder may have crashed before its first heartbeat upsert",
                lockKeyHashInput()));
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
            ps.setString(1, serviceName());
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

    // Test seam: installs a held connection and exit hook so the liveness probe
    // can be exercised directly, without booting the @Startup acquisition path.
    void primeForTest(Connection heldConnection, ExitHook exitHook) {
        synchronized (connectionLock) {
            this.heldConnection = heldConnection;
            this.lockHeld = true;
        }
        this.exitHook = exitHook;
    }

    /** Routes the process-exit decision so tests can observe it without exiting the JVM. */
    @FunctionalInterface
    public interface ExitHook {
        void exit(int code);
    }

    public record Holder(String hostId, int pid, Instant lastSeenAt) {}
}
