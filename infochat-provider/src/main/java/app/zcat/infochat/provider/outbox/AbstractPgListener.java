package app.zcat.infochat.provider.outbox;

import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Shared LISTEN/NOTIFY worker machinery for the Provider's PG listeners
 * ({@link NewPostListener}, {@link QuarantineReviewListener}). Owns one
 * dedicated long-lived {@link Connection} per listener for its full
 * lifetime — LISTEN is connection-scoped, so returning the connection to
 * the Agroal pool would silently end the subscription — and runs a
 * reconnect-resilient virtual-thread worker that re-issues {@code LISTEN}
 * and replays missed NOTIFYs through {@link #runCatchUp()} after any
 * connection loss.
 *
 * <p><b>Lifecycle synchronization.</b> The connection-management methods
 * ({@link #ensureListenConnection()}, {@link #openListenConnection()},
 * {@link #closeListenConnectionQuietly()}) are {@code synchronized} and
 * {@link #closeListenConnectionQuietly()} nulls the field after close.
 * This is load-bearing: the worker's check-reopen-catch-up sequence races
 * against a concurrent close (the {@code @PreDestroy} stop path, or the
 * test hook) — an unsynchronized close landing between the reopen and the
 * field read would null out the FRESH connection and NPE-kill the worker
 * thread. The poll itself ({@code getNotifications} in {@link #runLoop()})
 * stays outside the monitor so a close is never blocked for the full
 * notification timeout.
 *
 * <p>Concrete beans drive the lifecycle from their own
 * {@code @PostConstruct}/{@code @PreDestroy} by calling {@link #start()}
 * and {@link #stop()}, and supply the channel name, worker thread name,
 * logger, per-notification {@link #dispatch(PGNotification)} routing, and
 * post-reconnect {@link #runCatchUp()} hook.
 */
abstract class AbstractPgListener {

    /**
     * {@code getNotifications} timeout in milliseconds. Short enough that
     * shutdown wakes the worker promptly (the stop-flag check happens at
     * the top of each loop iteration), long enough that the worker spends
     * most of its time blocked on the JDBC call rather than polling.
     */
    static final int NOTIFICATION_TIMEOUT_MS = 1000;

    /** Maximum shutdown wait for the worker thread to drain. */
    static final long SHUTDOWN_JOIN_TIMEOUT_MS = 5000;

    /**
     * Initial reconnect backoff after a connection-loss or
     * getNotifications failure. The backoff doubles each successive
     * failure up to {@link #MAX_BACKOFF_MS} and resets to this value on
     * the first successful {@code getNotifications} call.
     */
    static final long INITIAL_BACKOFF_MS = 1000;

    /**
     * Backoff ceiling. Bounds the reconnect cadence so persistent
     * Postgres unavailability does not produce a tight retry loop that
     * burns CPU and floods the log.
     */
    static final long MAX_BACKOFF_MS = 30_000;

    @Inject
    DataSource dataSource;

    // Long-lived; never returned to the pool while the JVM is alive. LISTEN
    // is bound to the underlying Postgres backend session, so returning this
    // connection to Agroal would silently end the subscription. (Re)assigned
    // across the LISTEN lifecycle and reset to null on close/shutdown — both
    // this and workerThread are genuinely nullable.
    private @Nullable Connection listenConnection;
    private @Nullable Thread workerThread;
    private volatile boolean stopRequested;

    /** The LISTEN channel name (e.g. {@code new_post}). */
    abstract String channelName();

    /** Name for the virtual worker thread. */
    abstract String workerThreadName();

    /** Concrete-subclass logger so log lines carry the right category. */
    abstract Logger log();

    /** Routes one received notification to the subclass's handler. */
    abstract void dispatch(PGNotification notification);

    /**
     * Post-reconnect catch-up via the subclass's reconciler. Invoked from
     * inside {@link #ensureListenConnection()} after a fresh session is
     * subscribed, so NOTIFYs dropped while no session was listening are
     * replayed.
     */
    abstract void runCatchUp() throws SQLException;

    /**
     * Opens the dedicated LISTEN connection and starts the virtual-thread
     * worker. Concrete beans call this from their {@code @PostConstruct}.
     */
    void start() {
        try {
            openListenConnection();
        } catch (SQLException e) {
            throw new IllegalStateException(
                getClass().getSimpleName() + " could not acquire its dedicated Postgres session "
                    + "or issue LISTEN " + channelName(), e);
        }
        workerThread = Thread.ofVirtual()
            .name(workerThreadName())
            .start(this::runLoop);
    }

    /**
     * Signals shutdown, interrupts the worker (unblocking any in-flight
     * backoff sleep so shutdown does not stall for up to 30s on the
     * maximum backoff), joins it, then closes the LISTEN connection.
     * Concrete beans call this from their {@code @PreDestroy}.
     */
    void stop() {
        stopRequested = true;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeListenConnectionQuietly();
    }

    /**
     * Reconnect-resilient main loop. Each iteration:
     *
     * <ol>
     *   <li>ensures the listen connection is open, re-issuing
     *       {@code LISTEN <channel>} and replaying NOTIFYs missed during
     *       the outage via {@link #runCatchUp()} after a reconnect;</li>
     *   <li>blocks on {@code getNotifications} up to
     *       {@link #NOTIFICATION_TIMEOUT_MS};</li>
     *   <li>on SQLException, closes the dead connection so the next
     *       iteration must reconnect, sleeps the current backoff, then
     *       doubles the backoff up to {@link #MAX_BACKOFF_MS};</li>
     *   <li>on success, resets the backoff to {@link #INITIAL_BACKOFF_MS}
     *       and dispatches notifications.</li>
     * </ol>
     *
     * <p>The backoff is bounded so a persistent Postgres outage does not
     * spin. The post-reconnect catch-up recovers any NOTIFY arrivals
     * dropped during the outage without a process restart, so a transient
     * PG blip cannot leave the live cursor permanently behind.
     */
    private void runLoop() {
        long backoffMs = INITIAL_BACKOFF_MS;
        while (!stopRequested) {
            PGConnection pg;
            try {
                pg = ensureListenConnection();
            } catch (SQLException e) {
                if (stopRequested) {
                    return;
                }
                log().errorf(e,
                    "%s: reconnect sequence failed (acquire/LISTEN/catch-up); backing off %dms",
                    getClass().getSimpleName(), backoffMs);
                if (!sleepBackoff(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                continue;
            }
            PGNotification[] notifications;
            try {
                notifications = pg.getNotifications(NOTIFICATION_TIMEOUT_MS);
            } catch (SQLException e) {
                if (stopRequested) {
                    return;
                }
                log().errorf(e,
                    "%s: getNotifications failed; will reconnect after %dms backoff",
                    getClass().getSimpleName(), backoffMs);
                // Drop the dead connection so the next iteration's
                // ensureListenConnection() forces a fresh session + LISTEN.
                closeListenConnectionQuietly();
                if (!sleepBackoff(backoffMs)) {
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                continue;
            }
            // Healthy poll — reset backoff so the next failure starts at
            // the floor rather than wherever the last failure left off.
            backoffMs = INITIAL_BACKOFF_MS;
            if (notifications == null) {
                continue;
            }
            for (PGNotification n : notifications) {
                dispatch(n);
            }
        }
    }

    /**
     * Acquires the dedicated LISTEN connection if it is null or closed,
     * re-issuing {@code LISTEN <channel>} so a fresh backend session is
     * subscribed to the channel, then running the catch-up to recover
     * NOTIFYs lost while no session was subscribed. Returns the unwrapped
     * {@link PGConnection} for the caller to poll. Idempotent — repeated
     * calls with a live connection short-circuit.
     */
    private synchronized PGConnection ensureListenConnection() throws SQLException {
        if (listenConnection == null || listenConnection.isClosed()) {
            closeListenConnectionQuietly();
            openListenConnection();
            log().infof("%s: (re)acquired LISTEN connection and re-issued LISTEN %s",
                getClass().getSimpleName(), channelName());
            reconcileAfterReconnect();
        }
        return Objects.requireNonNull(listenConnection).unwrap(PGConnection.class);
    }

    /**
     * Post-reconnect catch-up. NOTIFYs fired between the connection loss
     * and the re-{@code LISTEN} above were dropped by Postgres (no session
     * was subscribed), so the subclass reconciler replays rows past the
     * cursor. Ordering matters: {@code LISTEN} is re-issued BEFORE the
     * catch-up so a NOTIFY arriving mid-scan queues on the new session. A
     * catch-up failure closes the fresh connection and rethrows so the
     * caller's backoff path retries the full reconnect-plus-reconcile
     * sequence — otherwise a live connection with an unreconciled gap
     * would short-circuit future {@link #ensureListenConnection()} calls
     * and the gap would persist silently.
     */
    private void reconcileAfterReconnect() throws SQLException {
        try {
            runCatchUp();
        } catch (SQLException e) {
            closeListenConnectionQuietly();
            throw e;
        }
    }

    private synchronized void openListenConnection() throws SQLException {
        listenConnection = dataSource.getConnection();
        listenConnection.setAutoCommit(true);
        try (Statement stmt = listenConnection.createStatement()) {
            stmt.execute("LISTEN " + channelName());
        }
    }

    private synchronized void closeListenConnectionQuietly() {
        if (listenConnection != null) {
            try {
                listenConnection.close();
            } catch (SQLException ignored) {
                // The session is being abandoned regardless; the only
                // observable effect of close() failing is a stray
                // backend-side notice when Postgres notices the dropped
                // socket — out-of-band noise the operator does not need
                // surfaced here.
            }
            listenConnection = null;
        }
    }

    /**
     * Sleeps the given duration. Returns {@code true} on normal wake,
     * {@code false} if the sleep was interrupted (shutdown signal) so the
     * caller can exit promptly instead of resuming the loop.
     */
    private boolean sleepBackoff(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Test hook: closes the LISTEN connection so the worker's next loop
     * iteration is forced through the reconnect path. Package-private —
     * never invoked by production code; the {@code @PreDestroy} shutdown
     * path uses {@link #closeListenConnectionQuietly()} directly.
     */
    void closeListenConnectionForTest() {
        closeListenConnectionQuietly();
    }

    /** Test-visible accessor: is the worker thread still running? */
    boolean isWorkerAlive() {
        return workerThread != null && workerThread.isAlive();
    }
}
